// SPDX-License-Identifier: GPL-3.0-or-later

package org.codeberg.aimapp

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.codeberg.aimapp.utils.SAFImageProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class ImageProviderSecurityTest {

    private lateinit var context: Context
    private lateinit var testDir: File
    private lateinit var mountsDir: File

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        testDir = File(context.cacheDir, "provider_test_${System.currentTimeMillis()}")
        testDir.mkdirs()
        mountsDir = File(context.filesDir, "mounts")
        mountsDir.mkdirs()
    }

    @After
    fun cleanup() {
        testDir.deleteRecursively()
    }

    @Test
    fun testSanitizeDisplayName_removesSlashes() {
        val provider = SAFImageProvider()
        val method =
            SAFImageProvider::class.java.getDeclaredMethod("sanitiseDisplayName", String::class.java)
        method.isAccessible = true

        val result = method.invoke(provider, "test/file.txt") as String
        assertEquals("test_file.txt", result)
    }

    @Test
    fun testSanitizeDisplayName_removesNullBytes() {
        val provider = SAFImageProvider()
        val method =
            SAFImageProvider::class.java.getDeclaredMethod("sanitiseDisplayName", String::class.java)
        method.isAccessible = true

        val result = method.invoke(provider, "test\u0000file.txt") as String
        assertEquals("test_file.txt", result)
    }

    @Test
    fun testSanitizeDisplayName_rejectsDot() {
        val provider = SAFImageProvider()
        val method =
            SAFImageProvider::class.java.getDeclaredMethod("sanitiseDisplayName", String::class.java)
        method.isAccessible = true

        try {
            method.invoke(provider, ".")
            fail("Should throw IllegalArgumentException")
        } catch (e: Exception) {
            assertTrue(e.cause is IllegalArgumentException)
        }
    }

    @Test
    fun testSanitizeDisplayName_rejectsDoubleDot() {
        val provider = SAFImageProvider()
        val method =
            SAFImageProvider::class.java.getDeclaredMethod("sanitiseDisplayName", String::class.java)
        method.isAccessible = true

        try {
            method.invoke(provider, "..")
            fail("Should throw IllegalArgumentException")
        } catch (e: Exception) {
            assertTrue(e.cause is IllegalArgumentException)
        }
    }

    @Test
    fun testSanitizeDisplayName_rejectsEmpty() {
        val provider = SAFImageProvider()
        val method =
            SAFImageProvider::class.java.getDeclaredMethod("sanitiseDisplayName", String::class.java)
        method.isAccessible = true

        try {
            method.invoke(provider, "")
            fail("Should throw IllegalArgumentException")
        } catch (e: Exception) {
            assertTrue(e.cause is IllegalArgumentException)
        }
    }

    @Test
    fun testSanitizeDisplayName_rejectsBlank() {
        val provider = SAFImageProvider()
        val method =
            SAFImageProvider::class.java.getDeclaredMethod("sanitiseDisplayName", String::class.java)
        method.isAccessible = true

        try {
            method.invoke(provider, "   ")
            fail("Should throw IllegalArgumentException")
        } catch (e: Exception) {
            assertTrue(e.cause is IllegalArgumentException)
        }
    }

    @Test
    fun testSafeDelete_handlesSymlinksCorrectly() {
        val mountPoint = File(mountsDir, "test_mount")
        mountPoint.mkdirs()
        val targetFile = File(mountPoint, "target.txt")
        targetFile.writeText("target content")
        val symlinkFile = File(mountPoint, "link.txt")

        try {
            Files.createSymbolicLink(symlinkFile.toPath(), targetFile.toPath())

            val provider = SAFImageProvider()
            val method = SAFImageProvider::class.java.getDeclaredMethod("safeDelete", File::class.java)
            method.isAccessible = true
            method.invoke(provider, symlinkFile)
            assertFalse("Symlink should be deleted", symlinkFile.exists())
            assertTrue("Target should remain", targetFile.exists())
        } finally {
            targetFile.delete()
            if (symlinkFile.exists()) symlinkFile.delete()
            mountPoint.deleteRecursively()
        }
    }

    @Test
    fun testSafeDelete_recursivelyDeletesDirectories() {
        val mountPoint = File(mountsDir, "test_mount")
        mountPoint.mkdirs()
        val parentDir = File(mountPoint, "parent")
        val childDir = File(parentDir, "child")
        childDir.mkdirs()
        val file1 = File(parentDir, "file1.txt")
        val file2 = File(childDir, "file2.txt")
        file1.writeText("content1")
        file2.writeText("content2")

        try {
            val provider = SAFImageProvider()
            val method = SAFImageProvider::class.java.getDeclaredMethod("safeDelete", File::class.java)
            method.isAccessible = true
            method.invoke(provider, parentDir)
            assertFalse("Parent should be deleted", parentDir.exists())
        } finally {
            if (parentDir.exists()) parentDir.deleteRecursively()
            mountPoint.deleteRecursively()
        }
    }

    @Test
    fun testIsChildDocument_preventsPathTraversal() {
        val provider = SAFImageProvider()
        val mountPoint = File(mountsDir, "test_mount")
        mountPoint.mkdirs()

        try {
            val result = provider.isChildDocument(
                mountPoint.absolutePath, mountPoint.absolutePath + "/../../../etc/passwd"
            )
            assertFalse("Path traversal should be rejected", result)
        } finally {
            mountPoint.deleteRecursively()
        }
    }

    @Test
    fun testIsChildDocument_rootNotChildOfRoot() {
        val provider = SAFImageProvider()
        assertFalse(
            "Root should not be child of itself", provider.isChildDocument("mounts", "mounts")
        )
    }

    @Test
    fun testValidateDocumentId_rejectsNullBytes() {
        val provider = SAFImageProvider()
        val method =
            SAFImageProvider::class.java.getDeclaredMethod("validateDocumentId", String::class.java)
        method.isAccessible = true
        try {
            method.invoke(provider, "/valid\u0000inject")
            fail("Should throw for null byte in doc ID")
        } catch (e: Exception) {
            assertTrue(e.cause is SecurityException)
        }
    }

    @Test
    fun testValidateDocumentId_rejectsNewlines() {
        val provider = SAFImageProvider()
        val method =
            SAFImageProvider::class.java.getDeclaredMethod("validateDocumentId", String::class.java)
        method.isAccessible = true
        try {
            method.invoke(provider, "/valid\nmalicious")
            fail("Should throw for newline in doc ID")
        } catch (e: Exception) {
            assertTrue(e.cause is SecurityException)
        }
    }

    @Test
    fun testValidateDocumentId_rejectsCarriageReturn() {
        val provider = SAFImageProvider()
        val method =
            SAFImageProvider::class.java.getDeclaredMethod("validateDocumentId", String::class.java)
        method.isAccessible = true
        try {
            method.invoke(provider, "/valid\rmalicious")
            fail("Should throw for carriage return in doc ID")
        } catch (e: Exception) {
            assertTrue(e.cause is SecurityException)
        }
    }

    @Test
    fun testValidateDocumentId_rejectsPathTraversal() {
        val provider = SAFImageProvider()
        val method =
            SAFImageProvider::class.java.getDeclaredMethod("validateDocumentId", String::class.java)
        method.isAccessible = true
        try {
            method.invoke(provider, "/data/../etc/passwd")
            fail("Should throw for path traversal in doc ID")
        } catch (e: Exception) {
            assertTrue(e.cause is SecurityException)
        }
    }

    @Test
    fun testValidateDocumentId_rejectsRelativePath() {
        val provider = SAFImageProvider()
        val method =
            SAFImageProvider::class.java.getDeclaredMethod("validateDocumentId", String::class.java)
        method.isAccessible = true
        try {
            method.invoke(provider, "relative/path")
            fail("Should throw for relative path in doc ID")
        } catch (e: Exception) {
            assertTrue(e.cause is SecurityException)
        }
    }

    @Test
    fun testValidateDocumentId_rejectsOverlongId() {
        val provider = SAFImageProvider()
        val method =
            SAFImageProvider::class.java.getDeclaredMethod("validateDocumentId", String::class.java)
        method.isAccessible = true
        try {
            method.invoke(provider, "/" + "a".repeat(5_000))
            fail("Should throw for overlong doc ID")
        } catch (e: Exception) {
            assertTrue(e.cause is SecurityException)
        }
    }

    @Test
    fun testValidateDocumentId_acceptsRootDocId() {
        val provider = SAFImageProvider()
        val method =
            SAFImageProvider::class.java.getDeclaredMethod("validateDocumentId", String::class.java)
        method.isAccessible = true
        method.invoke(provider, "mounts")
    }

    @Test
    fun testValidateDocumentId_acceptsValidAbsolutePath() {
        val provider = SAFImageProvider()
        val method =
            SAFImageProvider::class.java.getDeclaredMethod("validateDocumentId", String::class.java)
        method.isAccessible = true
        method.invoke(
            provider, "/data/user/0/org.codeberg.aimapp/files/mounts/test/file.txt"
        )
    }
}
