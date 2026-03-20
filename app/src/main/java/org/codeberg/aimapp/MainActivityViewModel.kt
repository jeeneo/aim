/**
 * Copyright (C) 2026 dryerlint <codeberg.org/dryerlint>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.codeberg.aimapp

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.codeberg.aimapp.utils.ImagePathResolver
import org.codeberg.aimapp.utils.PartitionEntry
import org.codeberg.aimapp.utils.PartitionScheme
import org.codeberg.aimapp.utils.validateBindDir
import org.codeberg.aimapp.utils.validatePath
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

data class ImportedImage(
    val path: String,
    val displayName: String,
    val exposeInSAF: Boolean = false,
    val exposeInStorage: Boolean = false,
    val selectedPartitionIndex: Int? = null,
    val hasPartitions: Boolean = false,
    val diskLabel: String? = null,
    val bindDir: String? = null,
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
)

class MainActivityViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private val TAG = MainActivityViewModel::class.java.simpleName
        private const val PREFS_SETTINGS = "app_settings"
        private const val KEY_BIND_DIR = "bindmount_dir"
        private const val KEY_DEBUG_MODE = "debug_mode"
        private const val DEFAULT_BIND_DIR = "/storage/emulated/0/mounts"
    }

    private val app: Application = application
    val mountManager = MountManager(
        application, object : MountManager.RootsChangedNotifier {
            override fun notify(context: Context) {
                ImageProvider.notifyRootsChanged(context)
            }
        })
    private val mountedPartitionIndex = mutableMapOf<String, Int>()
    private val mountedStem = mutableMapOf<String, String>()

    private data class BindState(val bindDir: String, val directMount: Boolean)

    private val mountedBindState = mutableMapOf<String, BindState>()
    private val operationsInProgress = AtomicInteger(0)
    private val _canAct = MutableStateFlow(true)
    val canAct: StateFlow<Boolean> = _canAct
    private val _alerts = MutableStateFlow<List<Alert>>(emptyList())
    val alerts: StateFlow<List<Alert>> = _alerts
    private val _envChecked = MutableStateFlow(false)
    val envChecked: StateFlow<Boolean> = _envChecked
    private val _images = MutableStateFlow<List<ImageInfo>>(emptyList())
    val images: StateFlow<List<ImageInfo>> = _images
    val envStatus: StateFlow<EnvironmentStatus> = mountManager.envStatus
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

    private val _debugMode = MutableStateFlow(
        settingsPrefs.getBoolean(KEY_DEBUG_MODE, false)
    )
    val debugMode: StateFlow<Boolean> = _debugMode

    private fun errorText(
        throwable: Throwable,
        fallback: String = app.getString(R.string.alert_environment_check_failed),
    ): String {
        return throwable.message?.takeIf { it.isNotBlank() }
            ?: throwable.javaClass.simpleName.takeIf { it.isNotBlank() } ?: fallback
    }

    fun toggleDebugMode() {
        val newValue = !_debugMode.value
        _debugMode.value = newValue
        settingsPrefs.edit { putBoolean(KEY_DEBUG_MODE, newValue) }
        DebugLog.setEnabled(app, newValue)
        if (newValue) {
            alert(Alert.Info(app.getString(R.string.alert_debug_mode_enabled)))
        } else {
            alert(Alert.Info(app.getString(R.string.alert_debug_mode_disabled)))
        }
    }

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
        if (_debugMode.value) {
            DebugLog.setEnabled(app, true)
        }
        checkEnvironment()
    }

    private fun refreshUiLock() {
        _canAct.update { operationsInProgress.get() == 0 }
    }

    private suspend fun <T> withLockedUi(block: suspend () -> T): T {
        operationsInProgress.incrementAndGet()
        refreshUiLock()
        try {
            return block()
        } finally {
            operationsInProgress.decrementAndGet()
            refreshUiLock()
        }
    }

    private fun loadImportedImages(): List<ImportedImage> = imageStore.load()

    private fun saveImportedImages(list: List<ImportedImage>) = imageStore.save(list)

    private fun updateImportedImage(path: String, transform: (ImportedImage) -> ImportedImage) {
        saveImportedImages(loadImportedImages().map {
            if (it.path == path) transform(it) else it
        })
    }

    private suspend fun refreshAndRebuild(notifySaf: Boolean = false) {
        withContext(Dispatchers.IO) { mountManager.refreshMountedImages() }
        rebuildImageList()
        if (notifySaf) ImageProvider.notifyRootsChanged(getApplication())
    }

    private suspend fun unmountWithCleanup(
        mountedImage: MountedImage,
        stem: String,
        bindDir: String? = null,
        directMount: Boolean = false,
        imagePath: String? = null,
    ): String? {
        val oldBind = imagePath?.let { mountedBindState[it] }
        val effectiveBindDir = oldBind?.bindDir ?: bindDir
        val effectiveDirectMount = oldBind?.directMount ?: directMount
        withContext(Dispatchers.IO) {
            mountManager.removeStorageBind(stem, effectiveBindDir, effectiveDirectMount)
        }
        imagePath?.let { mountedBindState.remove(it) }
        val result = withContext(Dispatchers.IO) { mountManager.unmountImage(mountedImage) }
        return when (result) {
            is MountResult.Failure -> errorText(
                Exception(result.message), app.getString(R.string.error_unmount_failed)
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
        return withContext(Dispatchers.IO) {
            if (storedPart != null) mountWithStoredPartition(
                path, storedPart, mode, stem, displayName
            )
            else mountManager.mountImage(path, mode, stem)
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
            val activeStem = mountedStem[img.path]
            val mountedEntry = mounted.find { mi ->
                mi.mountPoint == "$mountsDir/$stem"
            } ?: if (activeStem != null && activeStem != stem) {
                mounted.find { mi -> mi.mountPoint == "$mountsDir/$activeStem" }
            } else null
            val currentlyMounted = mountedEntry != null
            if (currentlyMounted) {
                // keep mountedStem in sync with what is actually mounted
                val foundStem = mountedEntry.mountPoint.removePrefix("$mountsDir/")
                mountedStem[img.path] = foundStem
            }
            if (currentlyMounted && img.selectedPartitionIndex != null) {
                mountedPartitionIndex.putIfAbsent(img.path, img.selectedPartitionIndex)
            }
            if (!currentlyMounted) {
                mountedPartitionIndex.remove(img.path)
                mountedStem.remove(img.path)
                mountedBindState.remove(img.path)
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
            )
        }
    }

    fun alert(alert: Alert) {
        when (alert) {
            is Alert.Failure -> L.e(TAG, "Alert: ${alert.message}")
            is Alert.Success -> L.i(TAG, "Alert: ${alert.message}")
            is Alert.Info -> L.i(TAG, "Alert: ${alert.message}")
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

    fun checkEnvironment() {
        viewModelScope.launch {
            withLockedUi {
                try {
                    withContext(Dispatchers.IO) { mountManager.checkEnvironment() }
                    _envChecked.value = true
                } catch (e: Exception) {
                    L.e(TAG, "Environment check failed", e)
                    alert(
                        Alert.Failure(
                            e.message ?: app.getString(R.string.alert_environment_check_failed)
                        )
                    )
                }
                refreshAndRebuild()
            }
        }
    }

    fun addImage(uri: Uri) {
        viewModelScope.launch {
            withLockedUi {
                val resolved = withContext(Dispatchers.IO) {
                    ImagePathResolver.resolve(getApplication(), uri)
                }
                resolved.error?.let {
                    alert(Alert.Failure(it))
                    return@withLockedUi
                }
                val path = resolved.path.orEmpty()
                val name = resolved.displayName ?: File(path).name
                if (path.isBlank()) {
                    alert(Alert.Failure(app.getString(R.string.alert_could_not_resolve_path)))
                    return@withLockedUi
                }
                val current = loadImportedImages()
                if (current.any { it.path == path }) {
                    alert(Alert.Failure(app.getString(R.string.alert_image_already_in_list)))
                    return@withLockedUi
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
            withLockedUi {
                val current = loadImportedImages()
                val img = current.find { it.path == path }
                val ui = _images.value.find { it.path == path }
                val bindDir = img?.bindDir ?: _bindDir.value
                if (ui?.isMounted == true && ui.mountedImage != null) {
                    val unmountStem = mountedStem[path] ?: stemFor(path, current)
                    unmountWithCleanup(
                        ui.mountedImage,
                        unmountStem,
                        bindDir,
                        directMount = img?.bindDir != null,
                        imagePath = path
                    )
                } else if (img != null) {
                    // remove bind mount even if not mounted
                    val cleanupStem = mountedStem[path] ?: stemFor(path, current)
                    val oldBind = mountedBindState[path]
                    val effectiveBindDir = oldBind?.bindDir ?: bindDir
                    val effectiveDirectMount = oldBind?.directMount ?: (img.bindDir != null)
                    withContext(Dispatchers.IO) {
                        mountManager.removeStorageBind(
                            cleanupStem, effectiveBindDir, effectiveDirectMount
                        )
                    }
                    mountedBindState.remove(path)
                }
                mountedStem.remove(path)
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
        updateImportedImage(path) { it.copy(exposeInSAF = expose) }
        _images.update { list ->
            list.map { if (it.path == path) it.copy(exposeInSAF = expose) else it }
        }
    }

    fun toggleImageStorageExpose(path: String, expose: Boolean) {
        updateImportedImage(path) { it.copy(exposeInStorage = expose) }
        _images.update { list ->
            list.map { if (it.path == path) it.copy(exposeInStorage = expose) else it }
        }
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
        updateImportedImage(path) { it.copy(bindDir = validatedDir) }
        _images.update { list ->
            list.map { if (it.path == path) it.copy(bindDir = validatedDir) else it }
        }
        if (validatedDir != null) {
            alert(Alert.Info(app.getString(R.string.alert_custom_bind_dir_set)))
        } else {
            alert(Alert.Info(app.getString(R.string.alert_using_default_bind_dir)))
        }
    }

    fun applySettings() {
        viewModelScope.launch {
            withLockedUi {
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

    private suspend fun applyUnmounts(
        snapshot: List<ImageInfo>,
        allImported: List<ImportedImage>,
        errors: MutableList<String>,
    ) {
        for (img in snapshot) {
            if (!img.enabled && img.isMounted && img.mountedImage != null) {
                val imported = allImported.find { it.path == img.path }
                val unmountStem = mountedStem[img.path] ?: stemFor(img.path, allImported)
                val bindDir = imported?.bindDir ?: _bindDir.value
                val err = unmountWithCleanup(
                    img.mountedImage,
                    unmountStem,
                    bindDir,
                    directMount = imported?.bindDir != null,
                    imagePath = img.path
                )
                if (err != null) {
                    L.e(TAG, "Unmount failed: ${img.path}")
                    errors += app.getString(R.string.error_op_unmount, img.displayName, err)
                } else {
                    mountedPartitionIndex.remove(img.path)
                    mountedStem.remove(img.path)
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
                    if (part != null) mountedPartitionIndex[img.path] = part
                    mountedStem[img.path] = stem
                }

                is MountResult.PartitionedImage -> {
                    if (imported?.hasPartitions != true) {
                        updateImportedImage(img.path) {
                            it.copy(hasPartitions = true, selectedPartitionIndex = null)
                        }
                    }
                    showPartitionDialog(img.path, img.displayName, result.result)
                }

                is MountResult.Failure -> {
                    L.e(TAG, "Mount failed: ${img.path}")
                    errors += app.getString(
                        R.string.error_op_mount,
                        img.displayName,
                        errorText(Exception(result.message))
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
            val knownMountedPart = mountedPartitionIndex[img.path]
            val partitionChanged =
                img.hasPartitions && imported?.selectedPartitionIndex != null && knownMountedPart != null && knownMountedPart != imported.selectedPartitionIndex
            if (wantPublic == img.isExposed && !partitionChanged) continue
            val bindDir = imported?.bindDir ?: _bindDir.value
            val unmountStem = mountedStem[img.path] ?: stemFor(img.path, allImported)
            val newStem = stemFor(img.path, allImported)
            val err = unmountWithCleanup(
                img.mountedImage,
                unmountStem,
                bindDir,
                directMount = imported?.bindDir != null,
                imagePath = img.path
            )
            if (err != null) {
                L.e(TAG, "Remount unmount failed: ${img.path}")
                errors += app.getString(R.string.error_op_remount, img.displayName, err)
                continue
            }
            mountedPartitionIndex.remove(img.path)
            mountedStem.remove(img.path)
            val result = mountOrPartitionMount(img.path, imported, newStem, img.displayName)
            when (result) {
                is MountResult.Mounted, is MountResult.AlreadyMounted, is MountResult.Unmounted -> {
                    val part = imported?.selectedPartitionIndex
                    if (part != null) mountedPartitionIndex[img.path] = part
                    mountedStem[img.path] = newStem
                }

                is MountResult.PartitionedImage -> {
                    if (imported?.hasPartitions != true) {
                        updateImportedImage(img.path) {
                            it.copy(hasPartitions = true, selectedPartitionIndex = null)
                        }
                    }
                    showPartitionDialog(img.path, img.displayName, result.result)
                }

                is MountResult.Failure -> {
                    L.e(TAG, "Remount failed: ${img.path}")
                    errors += app.getString(
                        R.string.error_op_remount,
                        img.displayName,
                        errorText(Exception(result.message))
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
        withContext(Dispatchers.IO) { mountManager.refreshMountedImages() }
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
                val oldBind = mountedBindState[img.path]
                if (oldBind != null && (oldBind.bindDir != imageBindDir || oldBind.directMount != isCustomBindDir)) {
                    val oldStem = mountedStem[img.path] ?: stem
                    withContext(Dispatchers.IO) {
                        mountManager.removeStorageBind(
                            oldStem, oldBind.bindDir, oldBind.directMount
                        )
                    }
                    mountedBindState.remove(img.path)
                }
                val bindRes = withContext(Dispatchers.IO) {
                    mountManager.createStorageBind(
                        stem, imageBindDir, directMount = isCustomBindDir
                    )
                }
                when (bindRes) {
                    is BindResult.Exposed, is BindResult.AlreadyExposed -> {
                        mountedBindState[img.path] = BindState(imageBindDir, isCustomBindDir)
                    }

                    is BindResult.Failure -> {
                        L.e(TAG, "Bind create failed: ${img.path}")
                        errors += app.getString(
                            R.string.error_op_bind,
                            img.displayName,
                            errorText(Exception(bindRes.message))
                        )
                    }

                    else -> { /* Skipped */
                    }
                }
            } else {
                val oldBind = mountedBindState[img.path]
                val effectiveBindDir = oldBind?.bindDir ?: imageBindDir
                val effectiveDirectMount = oldBind?.directMount ?: isCustomBindDir
                withContext(Dispatchers.IO) {
                    mountManager.removeStorageBind(stem, effectiveBindDir, effectiveDirectMount)
                }
                mountedBindState.remove(img.path)
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
            )
        )
    }

    fun formatImage(path: String, fsType: String = "ext4") {
        viewModelScope.launch {
            withLockedUi {
                val ui = _images.value.find { it.path == path }
                if (ui?.isMounted == true && ui.mountedImage != null) {
                    val unmountStem = mountedStem[path] ?: stemFor(path)
                    val err = unmountWithCleanup(ui.mountedImage, unmountStem)
                    if (err != null) {
                        alert(
                            Alert.Failure(
                                app.getString(
                                    R.string.alert_unmount_before_format_failed, err
                                )
                            )
                        )
                        return@withLockedUi
                    }
                    mountedStem.remove(path)
                }
                // re-validate path immediately before format to close the TOCTOU window between unmount and format
                if (!validatePath(path) || !path.trim().endsWith(".img", ignoreCase = true)) {
                    alert(Alert.Failure(app.getString(R.string.alert_path_validation_failed)))
                    return@withLockedUi
                }
                val result = withContext(Dispatchers.IO) { mountManager.formatImage(path, fsType) }
                result.onSuccess { msg ->
                    alert(Alert.Success(msg))
                    // clear partition flags since formatting removes the partition table
                    updateImportedImage(path) {
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
        if (picker != null) {
            val wasMounted = _images.value.find { it.path == picker.imagePath }?.isMounted == true
            if (!wasMounted) {
                _images.update { list ->
                    list.map { if (it.path == picker.imagePath) it.copy(enabled = false) else it }
                }
            }
        }
    }

    fun selectPartition(partition: PartitionEntry) {
        val picker = _partitionPicker.value ?: return
        dequeueNextPartitionPicker()
        updateImportedImage(picker.imagePath) {
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
            withLockedUi {
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
                            selectedPartitionIndex = imported?.selectedPartitionIndex
                        )
                    )
                } else {
                    val fileSize = withContext(Dispatchers.IO) { File(path).length() }
                    val detectedFs = try {
                        withContext(Dispatchers.IO) {
                            when (val d =
                                org.codeberg.aimapp.utils.detectFilesystem(app, path, "")) {
                                is org.codeberg.aimapp.utils.DetectFsResult.Found -> d.fs
                                is org.codeberg.aimapp.utils.DetectFsResult.Unknown -> null
                                is org.codeberg.aimapp.utils.DetectFsResult.AccessError -> {
                                    L.w(TAG, "fs access error probing $path: ${d.reason}")
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
                        typeName = detectedFs?.mountType
                            ?: app.getString(R.string.image_type_image),
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
    ): MountResult {
        val pr = mountManager.probePartitions(path)
        if (pr == null) {
            updateImportedImage(path) {
                it.copy(
                    hasPartitions = false, selectedPartitionIndex = null, diskLabel = null
                )
            }
            return mountManager.mountImage(path, mode, stem)
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
                )
            )
            return MountResult.PartitionedImage(pr)
        }
        return mountManager.mountPartition(path, partition, mode, stem)
    }
}
