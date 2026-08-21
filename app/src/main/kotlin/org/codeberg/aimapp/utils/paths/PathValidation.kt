// SPDX-License-Identifier: GPL-3.0-or-later

package org.codeberg.aimapp.utils.paths

import android.annotation.SuppressLint
import android.content.Context
import org.codeberg.aimapp.R
import java.io.File

private val PATH_FORBIDDEN = charArrayOf(
    '\u0000', '\n', '\r',
    '`', '$', '|', ';', '&', '{', '}', '<', '>',
)

// unicode normalization (NFC/NFD) is intentionally not applied here,
// 1. all PATH_FORBIDDEN characters and ".." are ASCII; NFC cannot synthesize ASCII from non-ASCII
// 2. linux treats filenames as raw byte sequences without normalization
// 3. android filesystems (ext4, f2fs) do not perform Unicode normalization
fun isValidPath(path: String): Boolean {
    if (PATH_FORBIDDEN.any { it in path }) return false
    return path.split('/').none { it == ".." }
}

fun validatePath(path: String): Boolean = isValidPath(path)

private data class AllowedZone(val prefix: String, val minDepth: Int)

@SuppressLint("SdCardPath")
private val ALLOWED_ZONES = listOf(
    // only allow bind mounts on these locations: prefix, minDepth
    AllowedZone("/storage/emulated/", 2),  // /storage/emulated/{userId}/{subfolder}
    AllowedZone("/data/media/", 2),        // /data/media/{userId}/{subfolder}
    AllowedZone("/sdcard/", 1),            // /sdcard/{subfolder}
    AllowedZone("/mnt/media_rw/", 2),      // /mnt/media_rw/{deviceId}/{subfolder}
)

@SuppressLint("SdCardPath")
private val BLOCKED_STORAGE_ROOTS = listOf(
    "/data/media/0",
    "/storage/emulated/0",
    "/sdcard",
    "/mnt/media_rw",
)

fun validateBindDir(ctx: Context, dir: String): String? {
    if (dir.isBlank()) return ctx.getString(R.string.error_dir_must_not_be_empty)
    if (!dir.startsWith('/')) return ctx.getString(R.string.error_dir_must_be_absolute)
    if (!validatePath(dir)) return ctx.getString(R.string.error_dir_invalid_chars)
    val normalised = File(dir).normalize().path.trimEnd('/')
    if (normalised.isEmpty()) return ctx.getString(R.string.error_dir_not_root_fs)
    val canonical = try {
        File(normalised).canonicalPath
    } catch (e: Exception) {
        return ctx.getString(R.string.error_resolve_canonical, e.message ?: "")
    }
    if (BLOCKED_STORAGE_ROOTS.any {
            canonical.equals(
                it, ignoreCase = false
            ) || canonical == "$it/"
        }) {
        return ctx.getString(R.string.error_bind_subfolder_not_root, canonical)
    }
    val zone =
        ALLOWED_ZONES.firstOrNull { canonical.startsWith(it.prefix) } ?: return ctx.getString(
            R.string.error_bind_must_be_under, ALLOWED_ZONES.joinToString(", ") { it.prefix })
    val tail = canonical.removePrefix(zone.prefix).trimEnd('/')
    val segments = if (tail.isEmpty()) emptyList() else tail.split('/')
    if (segments.size < zone.minDepth) {
        return ctx.getString(R.string.error_bind_subfolder_not_area_root)
    }
    if (segments.any { it == "Android" }) {
        return ctx.getString(R.string.error_bind_not_android_dir)
    }
    return null // valid
}

fun sanitizeStem(stem: String): String {
    val cleaned = stem.trimStart('.').ifBlank { "mounted_img" }
    return if (cleaned == ".." || cleaned == ".") "mounted_img" else cleaned
}
