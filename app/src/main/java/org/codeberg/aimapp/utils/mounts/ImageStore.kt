package org.codeberg.aimapp.utils.mounts

import android.content.Context
import android.util.Log
import androidx.core.util.AtomicFile
import org.codeberg.aimapp.ImportedImage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ImageStore(context: Context) {
    private val atomicFile = AtomicFile(File(context.filesDir, FILENAME))

    companion object {
        private const val TAG = "ImageStore"
        private const val FILENAME = "imported_images.json"
        private const val J_PATH = "path"
        private const val J_NAME = "name"
        private const val J_SAF = "saf"
        private const val J_STORAGE = "storage"
        private const val J_PART = "partition"
        private const val J_HAS_PARTS = "hasPartitions"
        private const val J_LABEL = "label"
        private const val J_BIND_DIR = "bindDir"
        private const val J_PRESERVE = "preservePermissions"
        fun loadAll(context: Context): List<ImportedImage> = ImageStore(context).load()
    }

    fun load(): List<ImportedImage> {
        val bytes = try {
            atomicFile.readFully()
        } catch (_: Exception) {
            return emptyList()
        }
        return try {
            val arr = JSONArray(String(bytes))
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val path =
                    obj.optString(J_PATH).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                ImportedImage(
                    path = path,
                    displayName = obj.optString(J_NAME, File(path).name),
                    exposeInSAF = obj.optBoolean(J_SAF, false),
                    exposeInStorage = obj.optBoolean(J_STORAGE, false),
                    selectedPartitionIndex = obj.optInt(J_PART, -1).takeIf { it >= 0 },
                    hasPartitions = obj.optBoolean(J_HAS_PARTS, false),
                    diskLabel = obj.optString(J_LABEL, "").takeIf { it.isNotEmpty() },
                    bindDir = obj.optString(J_BIND_DIR, "").takeIf { it.isNotEmpty() },
                    preservePermissions = obj.optBoolean(J_PRESERVE, false),
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse image store", e)
            emptyList()
        }
    }

    fun save(list: List<ImportedImage>) {
        val arr = JSONArray()
        for (img in list) {
            arr.put(JSONObject().apply {
                put(J_PATH, img.path)
                put(J_NAME, img.displayName)
                put(J_SAF, img.exposeInSAF)
                put(J_STORAGE, img.exposeInStorage)
                put(J_PART, img.selectedPartitionIndex ?: -1)
                put(J_HAS_PARTS, img.hasPartitions)
                img.diskLabel?.let { put(J_LABEL, it) }
                img.bindDir?.let { put(J_BIND_DIR, it) }
                put(J_PRESERVE, img.preservePermissions)
            })
        }
        val stream = atomicFile.startWrite()
        try {
            stream.write(arr.toString().toByteArray())
            atomicFile.finishWrite(stream)
        } catch (e: Exception) {
            atomicFile.failWrite(stream)
            Log.e(TAG, "Failed to save image store", e)
        }
    }
}