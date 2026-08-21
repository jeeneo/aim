// SPDX-License-Identifier: GPL-3.0-or-later

package org.codeberg.aimapp

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.codeberg.aimapp.utils.SAFImageProvider
import org.codeberg.aimapp.utils.checkEnvironment
import org.codeberg.aimapp.utils.disk.DetectFsResult
import org.codeberg.aimapp.utils.disk.detectFilesystem
import org.codeberg.aimapp.utils.mounts.BindResult
import org.codeberg.aimapp.utils.mounts.EnvironmentStatus
import org.codeberg.aimapp.utils.mounts.ImageStore
import org.codeberg.aimapp.utils.mounts.MountManager
import org.codeberg.aimapp.utils.mounts.MountMode
import org.codeberg.aimapp.utils.mounts.MountResult
import org.codeberg.aimapp.utils.mounts.MountedImage
import org.codeberg.aimapp.utils.mounts.PartitionEntry
import org.codeberg.aimapp.utils.mounts.PartitionScheme
import org.codeberg.aimapp.utils.mounts.PartitionedImageResult
import org.codeberg.aimapp.utils.mounts.fsDisplayName
import org.codeberg.aimapp.utils.mounts.generateMountStem
import org.codeberg.aimapp.utils.paths.ImagePathResolver
import org.codeberg.aimapp.utils.paths.validateBindDir
import org.codeberg.aimapp.utils.paths.validatePath
import java.io.File
import org.codeberg.aimapp.utils.disk.formatImage as formatDiskImage
import org.codeberg.aimapp.utils.envStatus as envStatusFlow


data class ImportedImage(
    val path: String,
    val displayName: String,
    val exposeInSAF: Boolean = false,
    val exposeInStorage: Boolean = false,
    val selectedPartitionIndex: Int? = null,
    val hasPartitions: Boolean = false,
    val diskLabel: String? = null,
    val bindDir: String? = null,
    val preservePermissions: Boolean = false,
)

data class ImageInfo(
    val path: String,
    val displayName: String,
    val enabled: Boolean,
    val isMounted: Boolean,
    val mountedImage: MountedImage?,
    val isExposed: Boolean,
    val exposeInSAF: Boolean,
    val exposeInStorage: Boolean = false,
    val isStorageExposed: Boolean = false,
    val isReadOnly: Boolean = false,
    val selectedPartitionIndex: Int? = null,
    val hasPartitions: Boolean = false,
    val bindDir: String? = null,
    val preservePermissions: Boolean = false,
)

sealed interface Alert {
    val message: String

    data class Failure(override val message: String) : Alert
    data class Success(override val message: String) : Alert
    data class Info(override val message: String) : Alert
}

data class PartitionState(
    val imagePath: String,
    val displayName: String,
    val partitions: List<PartitionEntry>,
    val totalSizeBytes: Long,
    val scheme: PartitionScheme = PartitionScheme.MBR,
    val selectedPartitionIndex: Int? = null,
    val isMountFlow: Boolean = false,
)

class MainActivityViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        const val DEFAULT_BIND_DIR = "/storage/emulated/0/mounts"
        private val TAG = MainActivityViewModel::class.java.simpleName
        private const val PREFS_SETTINGS = "app_settings"
        private const val KEY_BIND_DIR = "bindmount_dir"
        private const val KEY_SETTINGS_CONFIRMED = "settings_confirmed"
        private const val KEY_PRESERVE_PERMISSIONS_CONFIRMED = "preserve_permissions_confirmed"
    }

    private data class BindState(val bindDir: String, val directMount: Boolean)

    private val app: Application = application
    val mountManager = MountManager(application) { SAFImageProvider.notifyRootsChanged(it) }

    private data class MountState(
        val stem: String? = null,
        val partitionIndex: Int? = null,
        val bindState: BindState? = null,
    )

    private val mountRuntime = mutableMapOf<String, MountState>()
    private fun runtimeState(path: String): MountState = mountRuntime[path] ?: MountState()
    private fun updateState(
        path: String,
        transform: (MountState) -> MountState,
    ) {
        val next = transform(runtimeState(path))
        if (next == MountState()) mountRuntime.remove(path) else mountRuntime[path] = next
    }

    private val _busyCount = MutableStateFlow(0)
    val isBusy: StateFlow<Boolean> =
        _busyCount.map { it != 0 }.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    private val _alerts = MutableStateFlow<List<Alert>>(emptyList())
    val alerts: StateFlow<List<Alert>> = _alerts
    private val _envChecked = MutableStateFlow(false)
    val envChecked: StateFlow<Boolean> = _envChecked
    private val _images = MutableStateFlow<List<ImageInfo>>(emptyList())
    val images: StateFlow<List<ImageInfo>> = _images
    val envStatus: StateFlow<EnvironmentStatus> = envStatusFlow
    private val _partitionPicker = MutableStateFlow<PartitionState?>(null)
    val partitionPicker: StateFlow<PartitionState?> = _partitionPicker
    private val pendingPartitions = ArrayDeque<PartitionState>()
    private val imageStore = ImageStore(application)
    private val settingsPrefs =
        application.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
    private val _bindDir = MutableStateFlow(
        settingsPrefs.getString(KEY_BIND_DIR, DEFAULT_BIND_DIR) ?: DEFAULT_BIND_DIR
    )
    val bindDir: StateFlow<String> = _bindDir
    private val _showSettingsConfirm = MutableStateFlow(false)
    val showSettingsConfirm: StateFlow<Boolean> = _showSettingsConfirm
    private val _showPreservePermissionsConfirm = MutableStateFlow(false)
    val showPreservePermissionsConfirm: StateFlow<Boolean> = _showPreservePermissionsConfirm
    private var pendingPreservePermissionsPath: String? = null

    private fun errorText(
        throwable: Throwable,
        fallback: String = app.getString(R.string.alert_environment_check_failed),
    ): String {
        return throwable.message?.takeIf { it.isNotBlank() }
            ?: throwable.javaClass.simpleName.takeIf { it.isNotBlank() } ?: fallback
    }

    private fun errorText(
        message: String?,
        fallback: String = app.getString(R.string.alert_environment_check_failed),
    ): String = message?.takeIf { it.isNotBlank() } ?: fallback

    fun setBindDir(dir: String) {
        val trimmed = dir.trim().trimEnd('/')
        if (trimmed.isBlank()) return
        val error = validateBindDir(app, trimmed)
        if (error != null) {
            alert(Alert.Failure(error))
            return
        }
        _bindDir.value = trimmed
        settingsPrefs.edit { putString(KEY_BIND_DIR, trimmed) }
        alert(Alert.Info(app.getString(R.string.alert_bind_dir_set, trimmed)))
    }

    init {
        runEnvCheck()
    }

    private suspend fun <T> withLockedUI(block: suspend () -> T): T {
        _busyCount.update { it + 1 }
        try {
            return block()
        } finally {
            _busyCount.update { it - 1 }
        }
    }

    private fun loadImportedImages(): List<ImportedImage> = imageStore.load()
    private fun saveImportedImages(list: List<ImportedImage>) = imageStore.save(list)
    private fun updateImage(
        path: String,
        transform: (ImportedImage) -> ImportedImage,
    ): ImportedImage? {
        var updated: ImportedImage? = null
        saveImportedImages(loadImportedImages().map {
            if (it.path == path) transform(it).also { t -> updated = t } else it
        })
        return updated?.also { u ->
            _images.update { list ->
                list.map { info ->
                    if (info.path != path) info else info.copy(
                        displayName = u.displayName,
                        exposeInSAF = u.exposeInSAF,
                        exposeInStorage = u.exposeInStorage,
                        selectedPartitionIndex = u.selectedPartitionIndex,
                        hasPartitions = u.hasPartitions,
                        bindDir = u.bindDir,
                        preservePermissions = u.preservePermissions,
                    )
                }
            }
        }
    }

    private suspend fun refreshAndRebuild(notifySaf: Boolean = false) {
        withContext(Dispatchers.IO) { mountManager.refreshMountedImages() }
        rebuildImageList()
        if (notifySaf) SAFImageProvider.notifyRootsChanged(getApplication())
    }

    private suspend fun unmountWithCleanup(
        mountedImage: MountedImage,
        stem: String,
        bindDir: String? = null,
        directMount: Boolean = false,
        imagePath: String? = null,
        preservePermissions: Boolean = false,
    ): String? {
        val oldBind = imagePath?.let { runtimeState(it).bindState }
        val effectiveBindDir = oldBind?.bindDir ?: bindDir
        val effectiveDirectMount = oldBind?.directMount ?: directMount
        withContext(Dispatchers.IO) {
            mountManager.removeBindMount(stem, effectiveBindDir, effectiveDirectMount)
        }
        imagePath?.let { updateState(it) { st -> st.copy(bindState = null) } }
        val result = withContext(Dispatchers.IO) {
            mountManager.unmountImage(mountedImage, preservePermissions)
        }
        return when (result) {
            is MountResult.Failure -> errorText(
                result.message, app.getString(R.string.error_unmount_failed)
            )

            else -> null
        }
    }

    private fun stemFor(
        path: String, allImported: List<ImportedImage> = loadImportedImages()
    ): String {
        val img = allImported.find { it.path == path }
        val allLabels = allImported.associate { it.path to it.diskLabel }
        return generateMountStem(path, allImported.map { it.path }, img?.diskLabel, allLabels)
    }

    private fun modeFor(imported: ImportedImage?): MountMode =
        if (imported?.exposeInSAF == true) MountMode.PUBLIC else MountMode.LOCAL

    private suspend fun mountOrPartitionMount(
        path: String,
        imported: ImportedImage?,
        stem: String,
        displayName: String,
    ): MountResult {
        val mode = modeFor(imported)
        val storedPart = imported?.selectedPartitionIndex
        val preservePermissions = imported?.preservePermissions == true
        return withContext(Dispatchers.IO) {
            if (storedPart != null) mountWithStoredPartition(
                path, storedPart, mode, stem, displayName, preservePermissions
            )
            else mountManager.mountImage(
                path, mode, stem, preservePermissions = preservePermissions
            )
        }
    }

    private fun rebuildImageList() {
        val imported = loadImportedImages()
        val mounted = mountManager.mountedImages.value
        val mountsDir = mountManager.mountsDir
        val allPaths = imported.map { it.path }
        val allLabels = imported.associate { it.path to it.diskLabel }
        _images.value = imported.map { img ->
            val stem = generateMountStem(img.path, allPaths, img.diskLabel, allLabels)
            val activeStem = runtimeState(img.path).stem
            val mountedEntry = mounted.find { mi ->
                mi.mountPoint == "$mountsDir/$stem"
            } ?: if (activeStem != null && activeStem != stem) {
                mounted.find { mi -> mi.mountPoint == "$mountsDir/$activeStem" }
            } else null
            val currentlyMounted = mountedEntry != null
            if (currentlyMounted) {
                // keep in sync with what is actually mounted
                val foundStem = mountedEntry.mountPoint.removePrefix("$mountsDir/")
                updateState(img.path) { st -> st.copy(stem = foundStem) }
            }
            if (currentlyMounted && img.selectedPartitionIndex != null) {
                updateState(img.path) { st ->
                    if (st.partitionIndex == null) {
                        st.copy(partitionIndex = img.selectedPartitionIndex)
                    } else st
                }
            }
            if (!currentlyMounted) {
                mountRuntime.remove(img.path)
            }
            val readOnly = mountedEntry?.fsType?.readOnly == true
            ImageInfo(
                path = img.path,
                displayName = img.displayName,
                enabled = currentlyMounted,
                isMounted = currentlyMounted,
                mountedImage = mountedEntry,
                isExposed = currentlyMounted && img.exposeInSAF,
                exposeInSAF = img.exposeInSAF,
                exposeInStorage = img.exposeInStorage,
                isStorageExposed = currentlyMounted && img.exposeInStorage,
                isReadOnly = readOnly,
                selectedPartitionIndex = img.selectedPartitionIndex,
                hasPartitions = img.hasPartitions,
                bindDir = img.bindDir,
                preservePermissions = img.preservePermissions,
            )
        }
    }

    fun alert(alert: Alert) {
        when (alert) {
            is Alert.Failure -> Log.e(TAG, "Alert: ${alert.message}")
            is Alert.Success -> Log.i(TAG, "Alert: ${alert.message}")
            is Alert.Info -> Log.i(TAG, "Alert: ${alert.message}")
        }
        _alerts.value = listOf(alert)
    }

    private fun queuePartitions(state: PartitionState) {
        val current = _partitionPicker.value
        if (current?.imagePath == state.imagePath) {
            _partitionPicker.value = state
            return
        }
        val existingQueuedIdx = pendingPartitions.indexOfFirst { it.imagePath == state.imagePath }
        if (existingQueuedIdx >= 0) {
            pendingPartitions[existingQueuedIdx] = state
            return
        }
        if (current == null) {
            _partitionPicker.value = state
        } else {
            pendingPartitions.addLast(state)
        }
    }

    private fun dequeueNextPartitionPicker() {
        _partitionPicker.value =
            if (pendingPartitions.isEmpty()) null else pendingPartitions.removeFirst()
    }

    fun acknowledgeFirstAlert() {
        _alerts.update { it.drop(1) }
    }

    fun runEnvCheck() {
        viewModelScope.launch {
            withLockedUI {
                val status = withContext(Dispatchers.IO) { checkEnvironment() }
                _envChecked.value = true
                if (status.ready) refreshAndRebuild()
            }
        }
    }

    fun addImage(uri: Uri) {
        viewModelScope.launch {
            withLockedUI {
                val resolved = withContext(Dispatchers.IO) {
                    ImagePathResolver.resolve(getApplication(), uri)
                }
                resolved.error?.let {
                    alert(Alert.Failure(it))
                    return@withLockedUI
                }
                val path = resolved.path.orEmpty()
                val name = resolved.displayName ?: File(path).name
                if (path.isBlank()) {
                    alert(Alert.Failure(app.getString(R.string.alert_could_not_resolve_path)))
                    return@withLockedUI
                }
                val current = loadImportedImages()
                if (current.any { it.path == path }) {
                    alert(Alert.Failure(app.getString(R.string.alert_image_already_in_list)))
                    return@withLockedUI
                }
                // probe for partitions
                val hasPartitions = withContext(Dispatchers.IO) {
                    mountManager.probePartitions(path) != null
                }
                saveImportedImages(
                    current + ImportedImage(
                        path,
                        name,
                        exposeInSAF = false,
                        exposeInStorage = false,
                        selectedPartitionIndex = null,
                        hasPartitions = hasPartitions
                    )
                )
                rebuildImageList()
                alert(Alert.Info(app.getString(R.string.alert_image_added, name)))
            }
        }
    }

    fun removeImage(path: String) {
        viewModelScope.launch {
            withLockedUI {
                val current = loadImportedImages()
                val img = current.find { it.path == path }
                val ui = _images.value.find { it.path == path }
                val bindDir = img?.bindDir ?: _bindDir.value
                if (ui?.isMounted == true && ui.mountedImage != null) {
                    val unmountStem = runtimeState(path).stem ?: stemFor(path, current)
                    unmountWithCleanup(
                        ui.mountedImage,
                        unmountStem,
                        bindDir,
                        directMount = img?.bindDir != null,
                        imagePath = path,
                        preservePermissions = img?.preservePermissions == true
                    )
                } else if (img != null) {
                    // remove bind mount even if not mounted
                    val cleanupStem = runtimeState(path).stem ?: stemFor(path, current)
                    val oldBind = runtimeState(path).bindState
                    val effectiveBindDir = oldBind?.bindDir ?: bindDir
                    val effectiveDirectMount = oldBind?.directMount ?: (img.bindDir != null)
                    withContext(Dispatchers.IO) {
                        mountManager.removeBindMount(
                            cleanupStem, effectiveBindDir, effectiveDirectMount
                        )
                    }
                    updateState(path) { st -> st.copy(bindState = null) }
                }
                updateState(path) { st -> st.copy(stem = null) }
                saveImportedImages(current.filter { it.path != path })
                refreshAndRebuild()
                val displayName =
                    img?.displayName ?: app.getString(R.string.alert_default_image_name)
                alert(Alert.Info(app.getString(R.string.alert_image_removed, displayName)))
            }
        }
    }

    fun toggleImage(path: String, enabled: Boolean) {
        _images.update { list ->
            list.map { if (it.path == path) it.copy(enabled = enabled) else it }
        }
    }

    fun toggleImageSafExpose(path: String, expose: Boolean) {
        updateImage(path) { it.copy(exposeInSAF = expose) }
    }

    fun toggleImageStorageExpose(path: String, expose: Boolean) {
        updateImage(path) { it.copy(exposeInStorage = expose) }
    }

    fun toggleImagePreservePermissions(path: String, preserve: Boolean) {
        if (!preserve) {
            updateImage(path) { it.copy(preservePermissions = false) }
            return
        }
        if (!settingsPrefs.getBoolean(KEY_PRESERVE_PERMISSIONS_CONFIRMED, false)) {
            pendingPreservePermissionsPath = path
            _showPreservePermissionsConfirm.value = true
            return
        }
        updateImage(path) { it.copy(preservePermissions = true) }
    }

    fun confirmPreservePermissionsDialog() {
        settingsPrefs.edit { putBoolean(KEY_PRESERVE_PERMISSIONS_CONFIRMED, true) }
        _showPreservePermissionsConfirm.value = false
        pendingPreservePermissionsPath?.let { path ->
            updateImage(path) { it.copy(preservePermissions = true) }
        }
        pendingPreservePermissionsPath = null
    }

    fun dismissPreservePermissionsDialog() {
        _showPreservePermissionsConfirm.value = false
        pendingPreservePermissionsPath = null
    }

    fun setImageBindDir(path: String, bindDir: String?) {
        val trimmed = bindDir?.trim()?.trimEnd('/')
        val validatedDir = trimmed?.takeIf { it.isNotEmpty() }
        if (validatedDir != null) {
            val error = validateBindDir(app, validatedDir)
            if (error != null) {
                alert(Alert.Failure(error))
                return
            }
        }
        updateImage(path) { it.copy(bindDir = validatedDir) }
        if (validatedDir != null) {
            alert(Alert.Info(app.getString(R.string.alert_custom_bind_dir_set)))
        } else {
            alert(Alert.Info(app.getString(R.string.alert_using_default_bind_dir)))
        }
    }

    fun applySettings() {
        if (!settingsPrefs.getBoolean(KEY_SETTINGS_CONFIRMED, false)) {
            _showSettingsConfirm.value = true
            return
        }
        viewModelScope.launch {
            withLockedUI {
                val snapshot = _images.value
                val allImported = loadImportedImages()
                val errors = mutableListOf<String>()
                applyUnmounts(snapshot, allImported, errors)
                applyMounts(snapshot, allImported, errors)
                applyRemounts(snapshot, allImported, errors)
                applyStorageBind(snapshot, allImported, errors)
                refreshAndRebuild(notifySaf = true)
                if (errors.isNotEmpty()) alert(Alert.Failure(errors.joinToString("\n")))
                else alert(Alert.Success(app.getString(R.string.alert_settings_applied_success)))
            }
        }
    }

    fun confirmSettingsDialog() {
        settingsPrefs.edit { putBoolean(KEY_SETTINGS_CONFIRMED, true) }
        _showSettingsConfirm.value = false
        applySettings()
    }

    fun dismissSettingsDialog() {
        _showSettingsConfirm.value = false
    }


    private suspend fun applyUnmounts(
        snapshot: List<ImageInfo>,
        allImported: List<ImportedImage>,
        errors: MutableList<String>,
    ) {
        for (img in snapshot) {
            if (!img.enabled && img.isMounted && img.mountedImage != null) {
                val imported = allImported.find { it.path == img.path }
                val unmountStem = runtimeState(img.path).stem ?: stemFor(img.path, allImported)
                val bindDir = imported?.bindDir ?: _bindDir.value
                val err = unmountWithCleanup(
                    img.mountedImage,
                    unmountStem,
                    bindDir,
                    directMount = imported?.bindDir != null,
                    imagePath = img.path,
                    preservePermissions = imported?.preservePermissions == true
                )
                if (err != null) {
                    Log.e(TAG, "Unmount failed: ${img.path}")
                    errors += app.getString(R.string.error_op_unmount, img.displayName, err)
                } else {
                    updateState(img.path) { st ->
                        st.copy(stem = null, partitionIndex = null)
                    }
                }
            }
        }
    }

    private suspend fun applyMounts(
        snapshot: List<ImageInfo>,
        allImported: List<ImportedImage>,
        errors: MutableList<String>,
    ) {
        for (img in snapshot) {
            if (!img.enabled || img.isMounted) continue
            val imported = allImported.find { it.path == img.path }
            val stem = stemFor(img.path, allImported)
            when (val result = mountOrPartitionMount(img.path, imported, stem, img.displayName)) {
                is MountResult.Mounted, is MountResult.AlreadyMounted, is MountResult.Unmounted -> {
                    val part = imported?.selectedPartitionIndex
                    updateState(img.path) { st ->
                        st.copy(partitionIndex = part ?: st.partitionIndex, stem = stem)
                    }
                }

                is MountResult.PartitionedImage -> {
                    if (imported?.hasPartitions != true) {
                        updateImage(img.path) {
                            it.copy(hasPartitions = true, selectedPartitionIndex = null)
                        }
                    }
                    showPartitionDialog(img.path, img.displayName, result.result)
                }

                is MountResult.Failure -> {
                    Log.e(TAG, "Mount failed: ${img.path}")
                    errors += app.getString(
                        R.string.error_op_mount, img.displayName, errorText(result.message)
                    )
                }
            }
        }
    }

    private suspend fun applyRemounts(
        snapshot: List<ImageInfo>,
        allImported: List<ImportedImage>,
        errors: MutableList<String>,
    ) {
        for (img in snapshot) {
            if (!img.enabled || !img.isMounted || img.mountedImage == null) continue
            val imported = allImported.find { it.path == img.path }
            val wantPublic = imported?.exposeInSAF == true
            val knownMountedPart = runtimeState(img.path).partitionIndex
            val partitionChanged =
                img.hasPartitions && imported?.selectedPartitionIndex != null && knownMountedPart != null && knownMountedPart != imported.selectedPartitionIndex
            if (wantPublic == img.isExposed && !partitionChanged) continue
            val bindDir = imported?.bindDir ?: _bindDir.value
            val unmountStem = runtimeState(img.path).stem ?: stemFor(img.path, allImported)
            val newStem = stemFor(img.path, allImported)
            val err = unmountWithCleanup(
                img.mountedImage,
                unmountStem,
                bindDir,
                directMount = imported?.bindDir != null,
                imagePath = img.path,
                preservePermissions = imported?.preservePermissions == true
            )
            if (err != null) {
                Log.e(TAG, "Remount unmount failed: ${img.path}")
                errors += app.getString(R.string.error_op_remount, img.displayName, err)
                continue
            }
            updateState(img.path) { st -> st.copy(stem = null, partitionIndex = null) }
            val result = mountOrPartitionMount(img.path, imported, newStem, img.displayName)
            when (result) {
                is MountResult.Mounted, is MountResult.AlreadyMounted, is MountResult.Unmounted -> {
                    val part = imported?.selectedPartitionIndex
                    updateState(img.path) { st ->
                        st.copy(partitionIndex = part ?: st.partitionIndex, stem = newStem)
                    }
                }

                is MountResult.PartitionedImage -> {
                    if (imported?.hasPartitions != true) {
                        updateImage(img.path) {
                            it.copy(hasPartitions = true, selectedPartitionIndex = null)
                        }
                    }
                    showPartitionDialog(img.path, img.displayName, result.result)
                }

                is MountResult.Failure -> {
                    Log.e(TAG, "Remount failed: ${img.path}")
                    errors += app.getString(
                        R.string.error_op_remount, img.displayName, errorText(result.message)
                    )
                }
            }
        }
    }

    private suspend fun applyStorageBind(
        snapshot: List<ImageInfo>,
        allImported: List<ImportedImage>,
        errors: MutableList<String>,
    ) {
        val currentMounts = mountManager.mountedImages.value
        val defaultBindDir = _bindDir.value
        for (img in snapshot) {
            val imported = allImported.find { it.path == img.path }
            val stem = stemFor(img.path, allImported)
            val isMounted = currentMounts.any { it.mountPoint == "${mountManager.mountsDir}/$stem" }
            val wantBind = imported?.exposeInStorage == true && isMounted
            val imageBindDir = imported?.bindDir ?: defaultBindDir
            val isCustomBindDir = imported?.bindDir != null
            if (wantBind) {
                val oldBind = runtimeState(img.path).bindState
                if (oldBind != null && (oldBind.bindDir != imageBindDir || oldBind.directMount != isCustomBindDir)) {
                    val oldStem = runtimeState(img.path).stem ?: stem
                    withContext(Dispatchers.IO) {
                        mountManager.removeBindMount(
                            oldStem, oldBind.bindDir, oldBind.directMount
                        )
                    }
                    updateState(img.path) { st -> st.copy(bindState = null) }
                }
                val bindRes = withContext(Dispatchers.IO) {
                    mountManager.createStorageBind(
                        stem, imageBindDir, directMount = isCustomBindDir
                    )
                }
                when (bindRes) {
                    is BindResult.Exposed, is BindResult.AlreadyExposed -> {
                        updateState(img.path) { st ->
                            st.copy(bindState = BindState(imageBindDir, isCustomBindDir))
                        }
                    }

                    is BindResult.Failure -> {
                        Log.e(TAG, "Bind create failed: ${img.path}")
                        errors += app.getString(
                            R.string.error_op_bind, img.displayName, errorText(bindRes.message)
                        )
                    }

                    else -> {}
                }
            } else {
                val oldBind = runtimeState(img.path).bindState
                val effectiveBindDir = oldBind?.bindDir ?: imageBindDir
                val effectiveDirectMount = oldBind?.directMount ?: isCustomBindDir
                withContext(Dispatchers.IO) {
                    mountManager.removeBindMount(stem, effectiveBindDir, effectiveDirectMount)
                }
                updateState(img.path) { st -> st.copy(bindState = null) }
            }
        }
    }

    private fun showPartitionDialog(
        imagePath: String,
        displayName: String,
        probed: PartitionedImageResult? = null,
    ) {
        val pr = probed ?: mountManager.probePartitions(imagePath) ?: return
        val imported = loadImportedImages().find { it.path == imagePath }
        queuePartitions(
            PartitionState(
                imagePath = imagePath,
                displayName = displayName,
                partitions = pr.partitions,
                totalSizeBytes = pr.tableInfo.totalSizeBytes,
                scheme = pr.tableInfo.scheme,
                selectedPartitionIndex = imported?.selectedPartitionIndex,
                isMountFlow = true,
            )
        )
    }

    fun formatImage(path: String, fsType: String = "ext4") {
        viewModelScope.launch {
            withLockedUI {
                val ui = _images.value.find { it.path == path }
                if (ui?.isMounted == true && ui.mountedImage != null) {
                    val unmountStem = runtimeState(path).stem ?: stemFor(path)
                    val err = unmountWithCleanup(
                        ui.mountedImage, unmountStem, preservePermissions = ui.preservePermissions
                    )
                    if (err != null) {
                        alert(
                            Alert.Failure(
                                app.getString(
                                    R.string.alert_unmount_before_format_failed, err
                                )
                            )
                        )
                        return@withLockedUI
                    }
                    updateState(path) { st -> st.copy(stem = null) }
                }
                // re-validate path immediately before format to close the TOCTOU window between unmount and format
                if (!validatePath(path) || !path.trim().endsWith(".img", ignoreCase = true)) {
                    alert(Alert.Failure(app.getString(R.string.alert_path_validation_failed)))
                    return@withLockedUI
                }
                val result = withContext(Dispatchers.IO) {
                    formatDiskImage(
                        app, path, envStatus.value.ready, fsType
                    )
                }
                result.onSuccess { msg ->
                    alert(Alert.Success(msg))
                    updateImage(path) {
                        it.copy(
                            hasPartitions = false, selectedPartitionIndex = null, diskLabel = null
                        )
                    }
                }.onFailure { e ->
                    alert(
                        Alert.Failure(
                            errorText(e, app.getString(R.string.alert_format_failed))
                        )
                    )
                }
                refreshAndRebuild()
            }
        }
    }

    fun dismissPartitionPicker() {
        val picker = _partitionPicker.value
        dequeueNextPartitionPicker()
        if (picker != null && picker.isMountFlow) {
            _images.update { list ->
                list.map { if (it.path == picker.imagePath) it.copy(enabled = false) else it }
            }
        }
    }

    fun selectPartition(partition: PartitionEntry) {
        val picker = _partitionPicker.value ?: return
        savePartitionSelection(picker.imagePath, partition)
        if (picker.isMountFlow) {
            dequeueNextPartitionPicker()
        } else {
            _partitionPicker.update { it?.copy(selectedPartitionIndex = partition.index) }
        }
    }

    private fun savePartitionSelection(imagePath: String, partition: PartitionEntry) {
        updateImage(imagePath) {
            it.copy(
                selectedPartitionIndex = partition.index, diskLabel = partition.label
            )
        }
        rebuildImageList()
        alert(
            Alert.Info(
                app.getString(R.string.alert_partition_selected, partition.index)
            )
        )
    }

    fun changePartition(path: String) {
        viewModelScope.launch {
            withLockedUI {
                val imported = loadImportedImages().find { it.path == path }
                val result = withContext(Dispatchers.IO) { mountManager.probePartitions(path) }
                if (result != null) {
                    queuePartitions(
                        PartitionState(
                            imagePath = path,
                            displayName = imported?.displayName ?: File(path).name,
                            partitions = result.partitions,
                            totalSizeBytes = result.tableInfo.totalSizeBytes,
                            scheme = result.tableInfo.scheme,
                            selectedPartitionIndex = imported?.selectedPartitionIndex,
                        )
                    )
                } else {
                    val fileSize = withContext(Dispatchers.IO) { File(path).length() }
                    val detectedFs = try {
                        withContext(Dispatchers.IO) {
                            when (val d = detectFilesystem(app, path)) {
                                is DetectFsResult.Found -> d.fs
                                is DetectFsResult.Unknown -> null
                                is DetectFsResult.AccessError -> {
                                    Log.w(TAG, "fs access error probing $path: ${d.reason}")
                                    null
                                }
                            }
                        }
                    } catch (_: Exception) {
                        null
                    }
                    val single = PartitionEntry(
                        index = 1,
                        bootable = false,
                        typeId = 0,
                        typeName = fsDisplayName(app, detectedFs, path),
                        startLBA = 0L,
                        sizeSectors = if (fileSize > 0) fileSize / 512 else 0L,
                        offsetBytes = 0L,
                        sizeBytes = fileSize,
                        detectedFs = detectedFs,
                        detectedFsName = detectedFs?.mountType,
                        label = null,
                    )
                    queuePartitions(
                        PartitionState(
                            imagePath = path,
                            displayName = imported?.displayName ?: File(path).name,
                            partitions = listOf(single),
                            totalSizeBytes = fileSize,
                            scheme = PartitionScheme.MBR,
                            selectedPartitionIndex = imported?.selectedPartitionIndex,
                        )
                    )
                }
            }
        }
    }

    private suspend fun mountWithStoredPartition(
        path: String,
        partIndex: Int,
        mode: MountMode,
        stem: String,
        displayName: String,
        preservePermissions: Boolean = false,
    ): MountResult {
        val pr = mountManager.probePartitions(path)
        if (pr == null) {
            updateImage(path) {
                it.copy(
                    hasPartitions = false, selectedPartitionIndex = null, diskLabel = null
                )
            }
            return mountManager.mountImage(
                path, mode, stem, preservePermissions = preservePermissions
            )
        }
        val partition = pr.partitions.find { it.index == partIndex }
        if (partition == null) {
            queuePartitions(
                PartitionState(
                    imagePath = path,
                    displayName = displayName,
                    partitions = pr.partitions,
                    totalSizeBytes = pr.tableInfo.totalSizeBytes,
                    scheme = pr.tableInfo.scheme,
                    selectedPartitionIndex = null,
                    isMountFlow = true,
                )
            )
            return MountResult.PartitionedImage(pr)
        }
        return mountManager.mountPartition(
            path, partition, mode, stem, preservePermissions = preservePermissions
        )
    }
}
