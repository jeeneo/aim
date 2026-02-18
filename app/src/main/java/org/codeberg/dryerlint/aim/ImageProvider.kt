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

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files

class ImageProvider : DocumentsProvider() {
    private val mountsDir by lazy { File(context!!.filesDir, "mounts") }

    companion object {
        private const val TAG = "ImageProvider"
        const val AUTHORITY = "org.codeberg.dryerlint.aim.documents"
        private const val ROOT_ID = "mounted_images"
        private const val ROOT_DOC_ID = "mounts"
        private const val MAX_DOC_ID_LENGTH = 4096
        private val ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_DOCUMENT_ID,
        )
        private val DOC_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
        )

        fun notifyRootsChanged(ctx: Context) {
            ctx.contentResolver.notifyChange(
                DocumentsContract.buildRootsUri(AUTHORITY), null
            )
            ctx.contentResolver.notifyChange(
                DocumentsContract.buildChildDocumentsUri(AUTHORITY, ROOT_DOC_ID), null
            )
        }
    }

    // return the active mount points whose images have SAF exposure enabled
    private fun getSafExposedMounts(): List<File> {
        val images = ImageStore.loadAll(context!!)
        val allPaths = images.map { it.path }
        val safPaths = images.filter { it.exposeInSAF }.map { it.path }.toSet()
        val allLabels = images.associate { it.path to it.diskLabel }
        if (safPaths.isEmpty()) return emptyList()
        val exposedStems =
            safPaths.map { generateMountStem(it, allPaths, allLabels[it], allLabels) }.toSet()
        val prefix = mountsDir.absolutePath
        val result = mutableListOf<File>()
        try {
            val supportedFs = setOf("ext4", "vfat", "exfat")
            File("/proc/mounts").forEachLine { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 3 && parts[2] in supportedFs && "loop" in parts[0] && parts[1].startsWith(
                        "$prefix/"
                    )
                ) {
                    val dirName = parts[1].substringAfterLast('/')
                    if (dirName in exposedStems) {
                        result.add(File(parts[1]))
                    }
                }
            }
        } catch (_: Exception) { /* /proc/mounts unreadable */
        }
        return result
    }

    // return the canonical path of [file] if it resides inside one of the
    // active mount points (inside `files/mounts/`).
    // @throws SecurityException if the path is outside every mount point.
    // @throws FileNotFoundException if the file doesn't exist.
    private fun requireInsideMount(file: File): File {
        if (!file.exists()) throw FileNotFoundException(file.absolutePath)
        val canonical = file.canonicalPath
        val scope = mountsDir.canonicalPath
        if (!canonical.startsWith("$scope/") && canonical != scope) {
            logSecurityEvent("Path outside mount scope", file.path, canonical)
            throw SecurityException("Access denied: path outside mount scope")
        }
        return File(canonical)
    }

    // sanitize a display name supplied by a caller to prevent directory traversal.
    private fun sanitiseDisplayName(name: String): String {
        val clean = name.replace('/', '_').replace('\u0000', '_').trim()
        if (clean.isBlank() || clean == "." || clean == "..") {
            throw IllegalArgumentException("Invalid display name")
        }
        return clean
    }

    private fun validateDocumentId(docId: String) {
        if (docId == ROOT_DOC_ID) return
        if (docId.length > MAX_DOC_ID_LENGTH) {
            logSecurityEvent("Document ID exceeds max length", docId.take(100))
            throw SecurityException("Invalid document ID")
        }
        if ('\u0000' in docId || '\n' in docId || '\r' in docId) {
            logSecurityEvent("Document ID contains forbidden characters", docId.take(100))
            throw SecurityException("Invalid document ID")
        }
        if (!docId.startsWith('/')) {
            logSecurityEvent("Document ID is not an absolute path", docId.take(100))
            throw SecurityException("Invalid document ID")
        }
        if (docId.split('/').any { it == ".." }) {
            logSecurityEvent("Document ID contains path traversal", docId.take(100))
            throw SecurityException("Invalid document ID")
        }
    }

    // disallow all symlinks in the path (to prevent bypassing mount scope restrictions via intermediate symlink swaps)
    private fun disallowSymlinks(target: File) {
        val scope = mountsDir.canonicalPath
        var current = target
        while (current.path != scope && current.path != "/" && current.parentFile != null) {
            if (Files.isSymbolicLink(current.toPath())) {
                logSecurityEvent("Symlink access blocked", current.path)
                throw SecurityException("Access denied: symlinks not allowed")
            }
            current = current.parentFile!!
        }
    }

    // convert SAF mode string (e.g. "r", "rw", "rwt") to OS-level open flags
    private fun safModeToOsFlags(mode: String): Int {
        val hasRead = 'r' in mode
        val hasWrite = 'w' in mode
        var flags = when {
            hasRead && hasWrite -> OsConstants.O_RDWR
            hasWrite -> OsConstants.O_WRONLY
            else -> OsConstants.O_RDONLY
        }
        if ('t' in mode) flags = flags or OsConstants.O_TRUNC
        return flags
    }

    private fun logSecurityEvent(event: String, vararg details: String) {
        Log.w(TAG, "SECURITY: $event | ${details.joinToString(" | ")}")
    }

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: ROOT_PROJECTION)
        result.newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT_ID)
            add(
                Root.COLUMN_FLAGS,
                Root.FLAG_SUPPORTS_CREATE or Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_IS_CHILD,
            )
            add(Root.COLUMN_ICON, R.drawable.aim_logo)
            add(Root.COLUMN_TITLE, "AIM")
            add(Root.COLUMN_SUMMARY, "Mounted images")
            add(Root.COLUMN_DOCUMENT_ID, ROOT_DOC_ID)
        }
        result.setNotificationUri(
            context!!.contentResolver,
            DocumentsContract.buildRootsUri(AUTHORITY),
        )
        return result
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        validateDocumentId(documentId)
        val result = MatrixCursor(projection ?: DOC_PROJECTION)
        if (documentId == ROOT_DOC_ID) {
            result.newRow().apply {
                add(Document.COLUMN_DOCUMENT_ID, ROOT_DOC_ID)
                add(Document.COLUMN_DISPLAY_NAME, "AIM")
                add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
                add(Document.COLUMN_SIZE, 0L)
                add(Document.COLUMN_LAST_MODIFIED, 0L)
                add(Document.COLUMN_FLAGS, 0)
            }
            return result
        }
        val file = requireInsideMount(File(documentId))
        addFileRow(result, file, file.canonicalPath)
        return result
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        validateDocumentId(parentDocumentId)
        val result = MatrixCursor(projection ?: DOC_PROJECTION)
        if (parentDocumentId == ROOT_DOC_ID) {
            for (dir in getSafExposedMounts()) {
                addFileRow(result, dir, dir.canonicalPath)
            }
            result.setNotificationUri(
                context!!.contentResolver,
                DocumentsContract.buildChildDocumentsUri(AUTHORITY, ROOT_DOC_ID),
            )
            return result
        }
        val parent = requireInsideMount(File(parentDocumentId))
        parent.listFiles()?.forEach { child ->
            val safe = runCatching { requireInsideMount(child) }.getOrNull() ?: return@forEach
            addFileRow(result, safe, safe.path)
        }
        return result
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        if (documentId == ROOT_DOC_ID) throw FileNotFoundException("Cannot open root")
        validateDocumentId(documentId)
        val file = requireInsideMount(File(documentId))
        disallowSymlinks(file)
        val osFlags = safModeToOsFlags(mode) or OsConstants.O_NOFOLLOW
        val rawFd = try {
            Os.open(file.path, osFlags, 0)
        } catch (e: ErrnoException) {
            if (e.errno == OsConstants.ELOOP) {
                logSecurityEvent("Blocked symlink open via O_NOFOLLOW", documentId)
                throw SecurityException("Cannot open symbolic links")
            }
            throw FileNotFoundException("Cannot open file: ${e.message}")
        }
        // post-open verification: confirm the fd actually points inside mount scope.
        // catches intermediate path-component swaps that could occur between
        // requireInsideMount() and Os.open()
        val pfd = ParcelFileDescriptor.dup(rawFd)
        Os.close(rawFd)
        try {
            val actualPath = Os.readlink("/proc/self/fd/${pfd.fd}")
            val scope = mountsDir.canonicalPath
            if (!actualPath.startsWith("$scope/") && actualPath != scope) {
                pfd.close()
                logSecurityEvent("Post-open scope escape detected", documentId, actualPath)
                throw SecurityException("Access denied: file outside mount scope")
            }
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            pfd.close()
            logSecurityEvent("Post-open verification failed", documentId, e.toString())
            throw SecurityException("Cannot verify file location: ${e.message}")
        }
        return pfd
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        return try {
            validateDocumentId(parentDocumentId)
            validateDocumentId(documentId)
            val scope = mountsDir.canonicalPath
            if (parentDocumentId == ROOT_DOC_ID) {
                if (documentId == ROOT_DOC_ID) return false
                val canonical = File(documentId).canonicalPath
                return canonical.startsWith("$scope/")
            }
            val parentCanonical = File(parentDocumentId).canonicalPath
            val childCanonical = File(documentId).canonicalPath
            if (!parentCanonical.startsWith("$scope/") && parentCanonical != scope) return false
            if (!childCanonical.startsWith("$scope/") && childCanonical != scope) return false
            childCanonical.startsWith("$parentCanonical/")
        } catch (_: SecurityException) {
            logSecurityEvent("isChildDocument rejected", parentDocumentId, documentId)
            false
        } catch (_: Exception) {
            false
        }
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String {
        validateDocumentId(parentDocumentId)
        if (parentDocumentId == ROOT_DOC_ID) {
            throw UnsupportedOperationException("Cannot create at root level")
        }
        val parent = requireInsideMount(File(parentDocumentId))
        disallowSymlinks(parent)
        val safeName = sanitiseDisplayName(displayName)
        val child = if (mimeType == Document.MIME_TYPE_DIR) {
            File(parent, safeName).also { it.mkdirs() }
        } else {
            File(parent, safeName).also { it.createNewFile() }
        }
        requireInsideMount(child)
        return child.canonicalPath
    }

    override fun deleteDocument(documentId: String) {
        validateDocumentId(documentId)
        if (documentId == ROOT_DOC_ID) throw UnsupportedOperationException("Cannot delete root")
        val file = requireInsideMount(File(documentId))
        disallowSymlinks(file)
        safeDelete(file)
    }

    override fun removeDocument(documentId: String, parentDocumentId: String?) {
        deleteDocument(documentId)
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        validateDocumentId(documentId)
        if (documentId == ROOT_DOC_ID) throw UnsupportedOperationException("Cannot rename root")
        val file = requireInsideMount(File(documentId))
        disallowSymlinks(file)
        val safeName = sanitiseDisplayName(displayName)
        val target = File(file.parentFile, safeName)
        val scope = mountsDir.canonicalPath
        val parentCanonical = target.parentFile?.canonicalPath
            ?: throw SecurityException("Cannot resolve target parent path")
        if (!parentCanonical.startsWith("$scope/") && parentCanonical != scope) {
            throw SecurityException("Rename target would escape mount scope")
        }
        if (!file.renameTo(target)) {
            throw FileNotFoundException("Rename failed for ${file.name}")
        }
        val renamedCanonical = target.canonicalPath
        if (!renamedCanonical.startsWith("$scope/") && renamedCanonical != scope) {
            target.renameTo(file) // attempt to reverse
            logSecurityEvent("Rename result escaped scope, reversed", renamedCanonical)
            throw SecurityException("Rename target escaped mount scope")
        }
        return renamedCanonical
    }

    private fun safeDelete(file: File) {
        if (Files.isSymbolicLink(file.toPath())) {
            file.delete()
            return
        }
        if (file.isDirectory) {
            file.listFiles()?.forEach { child ->
                if (Files.isSymbolicLink(child.toPath())) {
                    child.delete()
                } else {
                    safeDelete(child)
                }
            }
        }
        file.delete()
    }

    private fun addFileRow(cursor: MatrixCursor, file: File, docId: String) {
        val mimeType = if (file.isDirectory) {
            Document.MIME_TYPE_DIR
        } else {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
                ?: "application/octet-stream"
        }
        val flags = if (file.canWrite()) {
            Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME or if (file.isDirectory) {
                Document.FLAG_DIR_SUPPORTS_CREATE
            } else {
                Document.FLAG_SUPPORTS_WRITE
            }
        } else {
            0
        }
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, docId)
            add(Document.COLUMN_DISPLAY_NAME, file.name)
            add(Document.COLUMN_MIME_TYPE, mimeType)
            add(Document.COLUMN_SIZE, file.length())
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            add(Document.COLUMN_FLAGS, flags)
        }
    }
}
