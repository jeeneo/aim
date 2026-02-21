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

import android.annotation.SuppressLint

private val PATH_FORBIDDEN = charArrayOf(
    '\u0000', '\n', '\r',
    '`', '$', '|', ';', '&', '(', ')', '{', '}', '<', '>',
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
    // only allow bind mounts on these locations "mount path", subdirs needed
    AllowedZone("/storage/emulated/", 2),   // /storage/emulated/{userId}/{subfolder}
    AllowedZone("/data/media/", 2),         // /data/media/{userId}/{subfolder}
    AllowedZone("/sdcard/", 1),             // /sdcard/{subfolder}
    AllowedZone("/mnt/media_rw/", 2),       // /mnt/media_rw/{deviceId}/{subfolder}
)

@SuppressLint("SdCardPath")
private val BLOCKED_STORAGE_ROOTS = listOf(
    "/data/media/0",
    "/storage/emulated/0",
    "/sdcard",
    "/mnt/media_rw",
)

fun validateBindDir(dir: String): String? {
    if (dir.isBlank()) return "Directory must not be empty"
    if (!dir.startsWith('/')) return "Directory must be an absolute path"
    if (!validatePath(dir)) return "Directory contains invalid characters or path traversal"
    val normalised = java.io.File(dir).normalize().path.trimEnd('/')
    if (normalised.isEmpty()) return "Directory must not be the root filesystem"
    val canonical = try {
        java.io.File(normalised).canonicalPath
    } catch (e: Exception) {
        return "Could not resolve canonical path: ${e.message}"
    }
    if (BLOCKED_STORAGE_ROOTS.any {
            canonical.equals(
                it, ignoreCase = false
            ) || canonical == "$it/"
        }) {
        return "Bind directory must be a subfolder, not the storage root ($canonical)"
    }
    val zone = ALLOWED_ZONES.firstOrNull { canonical.startsWith(it.prefix) }
        ?: return "Bind directory must be under one of: ${ALLOWED_ZONES.joinToString(", ") { it.prefix }}"
    val tail = canonical.removePrefix(zone.prefix).trimEnd('/')
    val segments = if (tail.isEmpty()) emptyList() else tail.split('/')
    if (segments.size < zone.minDepth) {
        return "Bind directory must be a subfolder, not the root of the storage area"
    }
    if (segments.any { it == "Android" }) {
        return "Bind directory must not be inside the Android directory"
    }
    return null // valid
}

fun sanitizeStem(stem: String): String {
    val cleaned = stem.trimStart('.').ifBlank { "mounted_img" }
    return if (cleaned == ".." || cleaned == ".") "mounted_img" else cleaned
}
