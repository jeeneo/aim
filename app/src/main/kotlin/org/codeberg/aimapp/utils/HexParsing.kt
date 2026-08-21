// SPDX-License-Identifier: GPL-3.0-or-later

package org.codeberg.aimapp.utils

fun parseLeHexAt(hex: String, byteOffset: Int = 0, byteCount: Int): Long {
    var result = 0L
    for (i in 0 until byteCount) {
        val at = (byteOffset + i) * 2
        result = result or (hex.substring(at, at + 2).toLong(16) shl (i * 8))
    }
    return result
}
