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

package org.codeberg.dryerlint.aim.utils

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
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
            }
        } ?: ResolvedImage(error = "Cannot resolve path for $name")
    }

    // attempts to resolve a SAF URI to browsable path (`/storage/emulated/0` is good enough for most access cases here)
    private fun resolveStoragePath(ctx: Context, uri: Uri): String? = when {
        uri.scheme.equals("file", true) -> uri.path
        !uri.scheme.equals("content", true) -> null
        !DocumentsContract.isDocumentUri(ctx, uri) -> null
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
                    }
                }
                else -> null
            }
        }
    }

    private fun queryName(ctx: Context, uri: Uri): String? = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        c -> c.takeIf { it.moveToFirst() }?.getColumnIndex(OpenableColumns.DISPLAY_NAME) ?.takeIf { it >= 0 }?.let { c.getString(it) }
    }
}
