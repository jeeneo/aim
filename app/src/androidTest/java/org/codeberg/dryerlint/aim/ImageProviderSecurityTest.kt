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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
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
        val provider = ImageProvider()
        val method = ImageProvider::class.java.getDeclaredMethod("sanitiseDisplayName", String::class.java)
        method.isAccessible = true
        
        val result = method.invoke(provider, "test/file.txt") as String
        assertEquals("test_file.txt", result)
    }

    @Test
    fun testSanitizeDisplayName_removesNullBytes() {
        val provider = ImageProvider()
        val method = ImageProvider::class.java.getDeclaredMethod("sanitiseDisplayName", String::class.java)
        method.isAccessible = true
        
        val result = method.invoke(provider, "test\u0000file.txt") as String
        assertEquals("test_file.txt", result)
    }

    @Test
    fun testSanitizeDisplayName_rejectsDot() {
        val provider = ImageProvider()
        val method = ImageProvider::class.java.getDeclaredMethod("sanitiseDisplayName", String::class.java)
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
        val provider = ImageProvider()
        val method = ImageProvider::class.java.getDeclaredMethod("sanitiseDisplayName", String::class.java)
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
        val provider = ImageProvider()
        val method = ImageProvider::class.java.getDeclaredMethod("sanitiseDisplayName", String::class.java)
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
        val provider = ImageProvider()
        val method = ImageProvider::class.java.getDeclaredMethod("sanitiseDisplayName", String::class.java)
        method.isAccessible = true
        
        try {
            method.invoke(provider, "   ")
            fail("Should throw IllegalArgumentException")
        } catch (e: Exception) {
           assertTrue(e.cause is IllegalArgumentException)
        }
    }

    @Test
    fun testPathTraversalPrevention() {
        val mountPoint = File(mountsDir, "test_mount")
        mountPoint.mkdirs()
        val traversalAttempt = File(mountPoint, "../outside.txt")
        
        try {
            val canonical = traversalAttempt.canonicalPath
            val mountCanonical = mountsDir.canonicalPath
            assertFalse("Path traversal should be outside mount directory",
                canonical.startsWith("$mountCanonical/test_mount"))
        } finally {
            mountPoint.deleteRecursively()
        }
    }

    @Test
    fun testAcceptsFileInsideMount() {
        val mountPoint = File(mountsDir, "test_mount")
        mountPoint.mkdirs()
        val testFile = File(mountPoint, "test.txt")
        testFile.writeText("test content")
        
        try {
            val canonical = testFile.canonicalPath
            val mountCanonical = mountsDir.canonicalPath
            assertTrue("File should be inside mount directory",
                canonical.startsWith("$mountCanonical/"))
        } finally {
            testFile.delete()
            mountPoint.deleteRecursively()
        }
    }

    @Test
    fun testSymlinkDetection() {
        val mountPoint = File(mountsDir, "test_mount")
        mountPoint.mkdirs()
        val targetFile = File(mountPoint, "target.txt")
        targetFile.writeText("sensitive data")
        val symlinkFile = File(mountPoint, "symlink.txt")
        
        try {
            Files.createSymbolicLink(symlinkFile.toPath(), targetFile.toPath())
            assertTrue("Symlink should be detected", Files.isSymbolicLink(symlinkFile.toPath()))
        } finally {
            symlinkFile.delete()
            targetFile.delete()
            mountPoint.deleteRecursively()
        }
    }

    @Test
    fun testRegularFileDetection() {
        val mountPoint = File(mountsDir, "test_mount")
        mountPoint.mkdirs()
        val regularFile = File(mountPoint, "regular.txt")
        regularFile.writeText("test content")
        
        try {
            assertFalse("Regular file should not be detected as symlink", 
                Files.isSymbolicLink(regularFile.toPath()))
        } finally {
            regularFile.delete()
            mountPoint.deleteRecursively()
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
            
            val provider = ImageProvider()
            val method = ImageProvider::class.java.getDeclaredMethod("safeDelete", File::class.java)
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
            val provider = ImageProvider()
            val method = ImageProvider::class.java.getDeclaredMethod("safeDelete", File::class.java)
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
        val provider = ImageProvider()
        val mountPoint = File(mountsDir, "test_mount")
        mountPoint.mkdirs()
        
        try {
            val result = provider.isChildDocument(
                mountPoint.absolutePath,
                mountPoint.absolutePath + "/../../../etc/passwd"
            )
            assertFalse("Path traversal should be rejected", result)
        } finally {
            mountPoint.deleteRecursively()
        }
    }

    @Test
    fun testIsChildDocument_rootNotChildOfRoot() {
        val provider = ImageProvider()
        assertFalse("Root should not be child of itself", 
            provider.isChildDocument("root", "root"))
    }
}
