// SPDX-License-Identifier: GPL-3.0-or-later

package org.codeberg.aimapp.utils.paths

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import org.codeberg.aimapp.R
import java.io.File

data class ResolvedImage(
    val path: String? = null,
    val displayName: String? = null,
    val error: String? = null,
)

object ImagePathResolver {
    fun resolve(ctx: Context, uri: Uri): ResolvedImage {
        val name = queryName(ctx, uri) ?: "selected.img"
        return resolveStoragePath(ctx, uri)?.let { path ->
            File(path).takeIf { it.exists() }?.let {
                ResolvedImage(it.absolutePath, name)
            } ?: run {
                Log.w(TAG, "Resolved path does not exist: $path (uri=$uri, name=$name)")
                null
            }
        } ?: run {
            Log.w(TAG, "Could not resolve path for uri=$uri (name=$name)")
            ResolvedImage(error = ctx.getString(R.string.error_cannot_resolve_path, name))
        }
    }

    // attempts to resolve a SAF URI to browsable path (`/storage/emulated/0` is good enough for most access cases here)
    private fun resolveStoragePath(ctx: Context, uri: Uri): String? = when {
        uri.scheme.equals("file", true) -> uri.path
        !uri.scheme.equals("content", true) -> {
            Log.w(TAG, "Unsupported scheme: ${uri.scheme} (uri=$uri)")
            null
        }

        !DocumentsContract.isDocumentUri(ctx, uri) -> {
            Log.w(TAG, "Not a document uri: $uri")
            null
        }

        else -> {
            val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull().orEmpty()
            when {
                docId.startsWith("raw:") -> docId.removePrefix("raw:")
                uri.authority == "com.android.externalstorage.documents" -> {
                    docId.split(':', limit = 2).takeIf { it.size == 2 }?.let { parts ->
                        when (parts[0].lowercase()) {
                            "primary" -> "/storage/emulated/0/${parts[1]}"
                            "home" -> "/storage/emulated/0/Documents/${parts[1]}"
                            else -> "/storage/${parts[0]}/${parts[1]}"
                        }
                    } ?: run {
                        Log.w(TAG, "Malformed externalstorage docId: '$docId' (uri=$uri)")
                        null
                    }
                }

                else -> {
                    Log.w(TAG, "Unsupported authority: ${uri.authority}, docId='$docId' (uri=$uri)")
                    null
                }
            }
        }
    }

    private const val TAG = "ImagePathResolver"

    private fun queryName(ctx: Context, uri: Uri): String? =
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                c.takeIf { it.moveToFirst() }?.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    ?.takeIf { it >= 0 }?.let { c.getString(it) }
            }
}
