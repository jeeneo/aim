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

package org.codeberg.dryerlint.aim

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.codeberg.dryerlint.aim.utils.ImagePathResolver
import org.codeberg.dryerlint.aim.utils.PartitionEntry
import org.codeberg.dryerlint.aim.utils.PartitionScheme
import org.codeberg.dryerlint.aim.utils.PartitionedImageException
import org.codeberg.dryerlint.aim.utils.validateBindDir
import org.codeberg.dryerlint.aim.utils.validatePath
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
        private const val DEFAULT_BIND_DIR = "/data/media/0/mounts"
    }

    val mountManager = MountManager(application)
    private val mountedPartitionIndex = mutableMapOf<String, Int>()
    private val mountedStem = mutableMapOf<String, String>()
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

    fun setBindDir(dir: String) {
        val trimmed = dir.trim().trimEnd('/')
        if (trimmed.isBlank()) return
        val error = validateBindDir(trimmed)
        if (error != null) {
            alert(Alert.Failure(error))
            return
        }
        _bindDir.value = trimmed
        settingsPrefs.edit { putString(KEY_BIND_DIR, trimmed) }
        alert(Alert.Info("Bind directory set to $trimmed"))
    }

    init {
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
    ): String? {
        withContext(Dispatchers.IO) {
            mountManager.removeStorageBind(stem, bindDir)
        }
        val result = withContext(Dispatchers.IO) { mountManager.unmountImage(mountedImage) }
        return result.exceptionOrNull()?.message
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
    ): OpResult {
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
                    Log.e(TAG, "Environment check failed", e)
                    alert(Alert.Failure(e.message ?: "Environment check failed"))
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
                    alert(Alert.Failure("Could not resolve path"))
                    return@withLockedUi
                }
                val current = loadImportedImages()
                if (current.any { it.path == path }) {
                    alert(Alert.Failure("Image already in list"))
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
                alert(Alert.Info("Added: $name"))
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
                    unmountWithCleanup(ui.mountedImage, unmountStem, bindDir)
                } else if (img != null) {
                    // remove bind mount even if not mounted
                    val cleanupStem = mountedStem[path] ?: stemFor(path, current)
                    withContext(Dispatchers.IO) {
                        mountManager.removeStorageBind(cleanupStem, bindDir)
                    }
                }
                mountedStem.remove(path)
                saveImportedImages(current.filter { it.path != path })
                refreshAndRebuild()
                alert(Alert.Info("Removed: ${img?.displayName ?: "Image"}"))
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
            val error = validateBindDir(validatedDir)
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
            alert(Alert.Info("Custom bind directory set for this image"))
        } else {
            alert(Alert.Info("Using global bind directory for this image"))
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
                else alert(Alert.Success("Settings applied"))
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
                val err = unmountWithCleanup(img.mountedImage, unmountStem, bindDir)
                if (err != null) {
                    Log.e(TAG, "Unmount failed: ${img.path}")
                    errors += "Unmount ${img.displayName}: $err"
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
            val result = mountOrPartitionMount(img.path, imported, stem, img.displayName)
            result.onSuccess {
                val part = imported?.selectedPartitionIndex
                if (part != null) mountedPartitionIndex[img.path] = part
                mountedStem[img.path] = stem
            }
            result.onFailure { e ->
                if (e is PartitionedImageException) {
                    if (imported?.hasPartitions != true) {
                        updateImportedImage(img.path) {
                            it.copy(
                                hasPartitions = true, selectedPartitionIndex = null
                            )
                        }
                    }
                    showPartitionDialog(img.path, img.displayName)
                } else {
                    Log.e(TAG, "Mount failed: ${img.path}", e)
                    errors += "Mount ${img.displayName}: ${e.message}"
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
            val err = unmountWithCleanup(img.mountedImage, unmountStem, bindDir)
            if (err != null) {
                Log.e(TAG, "Remount unmount failed: ${img.path}")
                errors += "Remount ${img.displayName}: $err"
                continue
            }
            mountedPartitionIndex.remove(img.path)
            mountedStem.remove(img.path)
            val result = mountOrPartitionMount(img.path, imported, newStem, img.displayName)
            result.onSuccess {
                val part = imported?.selectedPartitionIndex
                if (part != null) mountedPartitionIndex[img.path] = part
                mountedStem[img.path] = newStem
            }
            result.onFailure { e ->
                if (e is PartitionedImageException) {
                    if (imported?.hasPartitions != true) {
                        updateImportedImage(img.path) {
                            it.copy(
                                hasPartitions = true, selectedPartitionIndex = null
                            )
                        }
                    }
                    showPartitionDialog(img.path, img.displayName)
                } else {
                    Log.e(TAG, "Remount failed: ${img.path}", e)
                    errors += "Remount ${img.displayName}: ${e.message}"
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
            if (wantBind) {
                withContext(Dispatchers.IO) {
                    mountManager.createStorageBind(stem, imageBindDir)
                }.onFailure { e ->
                    Log.e(TAG, "Bind create failed: ${img.path}", e)
                    errors += "Bind ${img.displayName}: ${e.message}"
                }
            } else {
                withContext(Dispatchers.IO) {
                    mountManager.removeStorageBind(stem, imageBindDir)
                }
            }
        }
    }

    private fun showPartitionDialog(imagePath: String, displayName: String) {
        val pr = mountManager.pendingPartitionResult ?: return
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
                        alert(Alert.Failure("Unmount before format failed: $err"))
                        return@withLockedUi
                    }
                    mountedStem.remove(path)
                }
                // re-validate path immediately before format to close the TOCTOU window between unmount and format
                if (!validatePath(path) || !path.trim().endsWith(".img", ignoreCase = true)) {
                    alert(Alert.Failure("Path failed validation"))
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
                }.onFailure { e -> alert(Alert.Failure(e.message ?: "Format failed")) }
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
                "Partition ${partition.index} selected. Press Apply to mount."
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
                            org.codeberg.dryerlint.aim.utils.detectFilesystem(
                                path, ""
                            )
                        }
                    } catch (_: Exception) {
                        null
                    }
                    val single = PartitionEntry(
                        index = 1,
                        bootable = false,
                        typeId = 0,
                        typeName = detectedFs?.mountType ?: "Image",
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

    private fun mountWithStoredPartition(
        path: String,
        partIndex: Int,
        mode: MountMode,
        stem: String,
        displayName: String,
    ): OpResult {
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
            return OpResult.failure(PartitionedImageException(pr.tableInfo))
        }
        return mountManager.mountPartition(path, partition, mode, stem)
    }
}
