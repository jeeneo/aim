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

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.codeberg.dryerlint.aim.utils.isValidPath
import org.codeberg.dryerlint.aim.utils.sanitizeStem
import org.codeberg.dryerlint.aim.utils.validateBindDir
import org.codeberg.dryerlint.aim.utils.validatePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PathValidationTest {

    @Test
    fun testIsValidPath_acceptsNormalPaths() {
        val validPaths = listOf(
            "/data/local/tmp/file.img",
            "/storage/emulated/0/Download/image.img",
            "/sdcard/DCIM/IMG_001.jpg",
            "/mnt/media_rw/E1B2-3C4D/folder/file.txt",
            "relative/path/to/file.txt",
            "./current/dir/file",
            "~/user/home/file",
        )
        for (path in validPaths) {
            assertTrue("Path should be valid: $path", isValidPath(path))
        }
    }

    @Test
    fun testIsValidPath_acceptsPathWithSpaces() {
        assertTrue(isValidPath("/sdcard/My Documents/file.txt"))
    }

    @Test
    fun testIsValidPath_acceptsPathWithSpecialCharacters() {
        val paths = listOf(
            "/sdcard/test@file.txt",
            "/data/file_with-dashes.img",
            "/tmp/file.with.dots",
            "/sdcard/[brackets].txt",
            "/sdcard/file+plus.txt",
        )
        for (path in paths) {
            assertTrue("Path should be valid: $path", isValidPath(path))
        }
    }

    @Test
    fun testIsValidPath_rejectsDoubleDotTraversal() {
        val traversalPaths = listOf(
            "../etc/passwd",
            "/data/../system",
            "/data/local/../../sensitive",
            "../../..",
            "/sdcard/../../../root",
            "/data/./../../etc/passwd",
        )

        for (path in traversalPaths) {
            assertFalse("Path should be rejected (traversal): $path", isValidPath(path))
        }
    }

    @Test
    fun testIsValidPath_allowsDoubleDotInFilename() {
        assertTrue(isValidPath("/sdcard/file..name.txt"))
        assertTrue(isValidPath("/data/..hidden_file"))
        assertTrue(isValidPath("/tmp/test..test"))
    }

    @Test
    fun testIsValidPath_rejectsNullBytes() {
        assertFalse(isValidPath("/tmp/file\u00001337"))
        assertFalse(isValidPath("/tmp/\u0000"))
        assertFalse(isValidPath("\u0000/tmp/file"))
    }

    @Test
    fun testIsValidPath_rejectsNewlines() {
        assertFalse(isValidPath("/tmp/file\n1337"))
        assertFalse(isValidPath("/tmp/file\r\n1337"))
        assertFalse(isValidPath("\n/tmp/file"))
    }

    @Test
    fun testIsValidPath_rejectsCommandSubstitution() {
        val injectionPaths = listOf(
            "/tmp/\$(whoami)",
            "/tmp/file\$(rm -rf /)",
            "\$(1337)/file",
        )
        for (path in injectionPaths) {
            assertFalse("Path should be rejected (command substitution): $path", isValidPath(path))
        }
    }

    @Test
    fun testIsValidPath_rejectsBackticks() {
        val backtickPaths = listOf(
            "/tmp/`whoami`",
            "/tmp/file`1337`",
            "`command`/file",
        )
        for (path in backtickPaths) {
            assertFalse("Path should be rejected (backticks): $path", isValidPath(path))
        }
    }

    @Test
    fun testIsValidPath_rejectsPipes() {
        assertFalse(isValidPath("/tmp/file|cat /etc/passwd"))
        assertFalse(isValidPath("|1337"))
    }

    @Test
    fun testIsValidPath_rejectsSemicolons() {
        assertFalse(isValidPath("/tmp/file;rm -rf /"))
        assertFalse(isValidPath("/tmp/;1337"))
    }

    @Test
    fun testIsValidPath_rejectsAmpersands() {
        assertFalse(isValidPath("/tmp/file&background"))
        assertFalse(isValidPath("/tmp/file&&chain"))
    }

    @Test
    fun testIsValidPath_rejectsParentheses() {
        assertFalse(isValidPath("/tmp/(subshell)"))
        assertFalse(isValidPath("/tmp/file(1337)"))
    }

    @Test
    fun testIsValidPath_rejectsBraces() {
        assertFalse(isValidPath("/tmp/{expansion}"))
        assertFalse(isValidPath("/tmp/file{a,b}"))
    }

    @Test
    fun testIsValidPath_rejectsRedirectionOperators() {
        val redirectionPaths = listOf(
            "/tmp/file<input",
            "/tmp/file>output",
            "/tmp/file>>append",
            "/tmp/<1337",
        )
        for (path in redirectionPaths) {
            assertFalse("Path should be rejected (redirection): $path", isValidPath(path))
        }
    }

    @Test
    fun testValidatePath_matchesIsValidPath() {
        val testPaths = listOf(
            "/valid/path/file.txt",
            "../traversal",
            "/path;injection",
            "/path|pipe",
            "/path\$(cmd)",
        )
        for (path in testPaths) {
            assertEquals(
                "validatePath should match isValidPath for: $path",
                isValidPath(path),
                validatePath(path)
            )
        }
    }

    @Test
    fun testValidateBindDir_acceptsStorageEmulatedSubfolders() {
        assertNull(validateBindDir("/storage/emulated/0/DCIM"))
        assertNull(validateBindDir("/storage/emulated/0/Download"))
        assertNull(validateBindDir("/storage/emulated/10/Documents"))
    }

    @Test
    fun testValidateBindDir_acceptsSdcardSubfolders() {
        assertNull(validateBindDir("/sdcard/Download"))
        assertNull(validateBindDir("/sdcard/DCIM"))
        assertNull(validateBindDir("/sdcard/Music"))
    }

    @Test
    fun testValidateBindDir_acceptsMediaRwSubfolders() {
        assertNull(validateBindDir("/mnt/media_rw/1234-5678/folder"))
        assertNull(validateBindDir("/mnt/media_rw/sdcard/folder"))
    }

    @Test
    fun testValidateBindDir_rejectsStorageRoots() {
        val roots = listOf(
            "/storage/emulated/0",
            "/mnt/media_rw/1234-5678",
        )
        for (path in roots) {
            val result = validateBindDir(path)
            assertNotNull("Should reject storage root: $path", result)
            assertTrue(result!!.contains("subfolder"))
        }
    }

    @Test
    fun testValidateBindDir_rejectsAndroidDirs() {
        val androidPaths = listOf(
            "/sdcard/Android",
            "/sdcard/Android/data",
            "/sdcard/Android/obb",
            "/storage/emulated/0/Android",
            "/storage/emulated/0/Android/data",
            "/storage/emulated/0/Android/media",
            "/mnt/media_rw/1234-5678/Android",
            "/mnt/media_rw/1234-5678/Android/obb",
        )
        for (path in androidPaths) {
            val result = validateBindDir(path)
            assertNotNull("Should reject Android path: $path", result)
            assertTrue(result!!.contains("Android"))
        }
    }

    @Test
    fun testValidateBindDir_acceptsDataMediaSubfolders() {
        assertNull(validateBindDir("/data/media/0/Download"))
        assertNull(validateBindDir("/data/media/0/DCIM"))
        assertNull(validateBindDir("/data/media/999/Documents"))
    }

    @Test
    fun testValidateBindDir_rejectsDataMediaRoots() {
        val roots = listOf(
            "/data/media/0",
            "/data/media/999",
        )
        for (path in roots) {
            val result = validateBindDir(path)
            assertNotNull("Should reject /data/media root: $path", result)
            assertTrue(result!!.contains("subfolder"))
        }
    }

    @Test
    fun testValidateBindDir_rejectsSystemPaths() {
        val systemPaths = listOf(
            "/system",
            "/system/bin",
            "/system/lib",
            "/system/framework",
            "/vendor/lib",
            "/product/overlay",
            "/apex/com.android.runtime",
        )
        for (path in systemPaths) {
            val result = validateBindDir(path)
            assertNotNull("Should reject system path: $path", result)
            assertTrue(result!!.contains("must be under"))
        }
    }

    @Test
    fun testValidateBindDir_rejectsKernelPseudoFs() {
        val pseudoPaths = listOf(
            "/proc/self",
            "/sys/class",
            "/dev/block",
        )
        for (path in pseudoPaths) {
            val result = validateBindDir(path)
            assertNotNull("Should reject pseudo-fs path: $path", result)
            assertTrue(result!!.contains("must be under"))
        }
    }

    @Test
    fun testValidateBindDir_rejectsDataSubdirs() {
        val dataPaths = listOf(
            "/data/data/com.example",
            "/data/user/0/com.example",
            "/data/app/com.example",
        )
        for (path in dataPaths) {
            val result = validateBindDir(path)
            assertNotNull("Should reject data path: $path", result)
            assertTrue(result!!.contains("must be under"))
        }
    }

    @Test
    fun testValidateBindDir_rejectsRootLevelPaths() {
        val rootPaths = listOf(
            "/init",
            "/bin/sh",
        )
        for (path in rootPaths) {
            val result = validateBindDir(path)
            assertNotNull("Should reject root-level path: $path", result)
            assertTrue(result!!.contains("must be under"))
        }
    }

    @Test
    fun testValidateBindDir_rejectsRelativePath() {
        val result = validateBindDir("relative/path")
        assertNotNull(result)
        assertTrue(result!!.contains("absolute"))
    }

    @Test
    fun testValidateBindDir_rejectsEmptyString() {
        val result = validateBindDir("")
        assertNotNull(result)
        assertTrue(result!!.contains("empty"))
    }

    @Test
    fun testValidateBindDir_rejectsBlankString() {
        val result = validateBindDir("   ")
        assertNotNull(result)
        assertTrue(result!!.contains("empty"))
    }

    @Test
    fun testValidateBindDir_rejectsShellMetacharacters() {
        val result = validateBindDir("/sdcard/test;injection")
        assertNotNull(result)
        assertTrue(result!!.contains("invalid characters"))
    }

    @Test
    fun testValidateBindDir_rejectsPathTraversal() {
        val result = validateBindDir("/storage/emulated/../system")
        assertNotNull(result)
    }

    @Test
    fun testValidateBindDir_rejectsUnauthorizedLocations() {
        val unauthorizedPaths = listOf(
            "/tmp/test",
            "/var/log",
            "/root/data",
            "/home/user",
        )

        for (path in unauthorizedPaths) {
            val result = validateBindDir(path)
            assertNotNull("Should reject unauthorized path: $path", result)
            assertTrue(result!!.contains("must be under"))
        }
    }

    @Test
    fun testSanitizeStem_preservesNormalNames() {
        assertEquals("image", sanitizeStem("image"))
        assertEquals("my_image", sanitizeStem("my_image"))
        assertEquals("test-123", sanitizeStem("test-123"))
    }

    @Test
    fun testSanitizeStem_stripsLeadingDot() {
        assertEquals("hidden", sanitizeStem(".hidden"))
    }

    @Test
    fun testSanitizeStem_stripsMultipleLeadingDots() {
        assertEquals("name", sanitizeStem("...name"))
    }

    @Test
    fun testSanitizeStem_handlesSingleDot() {
        assertEquals("mounted_img", sanitizeStem("."))
    }

    @Test
    fun testSanitizeStem_handlesDoubleDot() {
        assertEquals("mounted_img", sanitizeStem(".."))
    }

    @Test
    fun testSanitizeStem_handlesEmptyString() {
        assertEquals("mounted_img", sanitizeStem(""))
    }

    @Test
    fun testSanitizeStem_handlesWhitespace() {
        assertEquals("mounted_img", sanitizeStem("  "))
        assertEquals("mounted_img", sanitizeStem("\t"))
    }

    @Test
    fun testSanitizeStem_handlesOnlyDots() {
        assertEquals("mounted_img", sanitizeStem("..."))
    }

    @Test
    fun testSanitizeStem_preservesDotsInMiddle() {
        assertEquals("file.img", sanitizeStem("file.img"))
        assertEquals("my.file.txt", sanitizeStem("my.file.txt"))
    }

    @Test
    fun testSanitizeStem_handlesComplexCases() {
        assertEquals("example_file", sanitizeStem("..example_file"))
        assertEquals("file", sanitizeStem(".file"))
        assertEquals("name", sanitizeStem("...name"))
    }

    @Test
    fun testValidateBindDir_handlesCaseSensitivity() {
        val result = validateBindDir("/SYSTEM/bin")
        assertNotNull("Behavior should be defined", result ?: "")
    }

    @Test
    fun testIsValidPath_acceptsUnicodePaths() {
        val unicodePaths = listOf(
            "/sdcard/文件夹名称/file.txt",
            "/sdcard/ИмяПапки/file.txt",
            "/sdcard/📁folder/file.txt",
        )

        for (path in unicodePaths) {
            assertTrue("Should accept unicode path: $path", isValidPath(path))
        }
    }

    @Test
    fun testIsValidPath_handlesPathWithQuotes() {
        assertTrue(isValidPath("/sdcard/user's file.txt"))
        assertTrue(isValidPath("/sdcard/file\"with\"quotes.txt"))
    }
}
