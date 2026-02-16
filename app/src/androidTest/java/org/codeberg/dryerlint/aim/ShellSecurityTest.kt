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
import org.codeberg.dryerlint.aim.utils.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShellSecurityTest {

    @Test
    fun testShellArg_of_escapesSingleQuotes() {
        val arg = ShellArg.of("test'value")
        assertEquals("'test'\\''value'", arg.quoted)
    }

    @Test
    fun testShellArg_of_handlesMultipleSingleQuotes() {
        val arg = ShellArg.of("a'b'c")
        assertEquals("'a'\\''b'\\''c'", arg.quoted)
    }

    @Test
    fun testShellArg_of_wrapsInSingleQuotes() {
        val arg = ShellArg.of("normal_value")
        assertEquals("'normal_value'", arg.quoted)
    }

    @Test
    fun testShellArg_literal_doesNotEscape() {
        val arg = ShellArg.literal("raw'value")
        assertEquals("raw'value", arg.quoted)
    }

    @Test
    fun testShellArg_blocksShellMetacharacters() {
        val badInputs = listOf(
            "\$(whoami)",
            "; rm -rf /",
            "| cat /etc/passwd",
            "& background_command",
            "`malicious_command`",
            "value\nmalicious_line",
            "value;malicious",
        )
        for (input in badInputs) {
            val arg = ShellArg.of(input)
            assertTrue("Failed to safely quote: $input", arg.quoted.startsWith("'"))
            assertTrue("Failed to safely quote: $input", arg.quoted.endsWith("'"))
            assertFalse("Shell metacharacter not escaped in: ${arg.quoted}", arg.quoted.contains("\$(") && !arg.quoted.contains("'\$("))
        }
    }

    @Test
    fun testShellArg_preventsCommandSubstitution() {
        val malicious = "\$(id)"
        val arg = ShellArg.of(malicious)
        assertEquals("'\$(id)'", arg.quoted)
    }

    @Test
    fun testShellArg_preventsBacktickExpansion() {
        val malicious = "`whoami`"
        val arg = ShellArg.of(malicious)
        assertEquals("'`whoami`'", arg.quoted)
    }

    @Test
    fun testNumArg_int_createsLiteral() {
        val arg = numArg(44)
        assertEquals("44", arg.quoted)
    }

    @Test
    fun testNumArg_long_createsLiteral() {
        val arg = numArg(9999L)
        assertEquals("9999", arg.quoted)
    }

    @Test
    fun testNumArg_negative_createsLiteral() {
        val arg = numArg(-100)
        assertEquals("-100", arg.quoted)
    }

    @Test
    fun testPathArg_acceptsValidPath() {
        val arg = pathArg("/data/local/tmp/test.img")
        assertTrue(arg.quoted.contains("/data/local/tmp/test.img"))
    }

    @Test
    fun testPathArg_rejectsPathTraversal() {
        assertThrows(IllegalArgumentException::class.java) {
            pathArg("/data/../etc/passwd")
        }
    }

    @Test
    fun testPathArg_rejectsShellMetacharacters() {
        val invalidPaths = listOf(
            "/tmp/file;\u0000shell",
            "/tmp/file|pipe",
            "/tmp/file&background",
            "/tmp/file\$(cmd)",
            "/tmp/file`cmd`",
        )
        for (path in invalidPaths) {
            assertThrows("Should reject path: $path", IllegalArgumentException::class.java) {
                pathArg(path)
            }
        }
    }

    @Test
    fun testPathArg_rejectsNullByte() {
        assertThrows(IllegalArgumentException::class.java) {
            pathArg("/tmp/file\u0000malicious")
        }
    }

    @Test
    fun testPathArg_rejectsNewlines() {
        assertThrows(IllegalArgumentException::class.java) {
            pathArg("/tmp/file\nmalicious")
        }
    }

    @Test
    fun testEnumArg_acceptsAllowedValue() {
        val allowed = setOf("ext4", "vfat", "exfat")
        val arg = enumArg("ext4", allowed)
        assertEquals("'ext4'", arg.quoted)
    }

    @Test
    fun testEnumArg_rejectsDisallowedValue() {
        val allowed = setOf("ext4", "vfat")
        assertThrows(IllegalArgumentException::class.java) {
            enumArg("ntfs", allowed)
        }
    }

    @Test
    fun testEnumArg_preventsInjectionViaEnum() {
        val allowed = setOf("ext4", "vfat")
        assertThrows(IllegalArgumentException::class.java) {
            enumArg("ext4; rm -rf /", allowed)
        }
    }

    @Test
    fun testMountOptsArg_acceptsValidOptions() {
        val validOpts = listOf(
            "rw,noatime",
            "ro,nosuid,nodev",
            "context=u:object_r:sdcard:s0",
            "uid=1000,gid=1000,umask=0077",
        )
        for (opts in validOpts) {
            val arg = mountOptsArg(opts)
            assertTrue("Should accept: $opts", arg.quoted.contains(opts))
        }
    }

    @Test
    fun testMountOptsArg_rejectsShellMetacharacters() {
        val invalidOpts = listOf(
            "rw;malicious",
            "ro|pipe",
            "rw&background",
            "rw\$(cmd)",
        )
        for (opts in invalidOpts) {
            assertThrows("Should reject: $opts", IllegalArgumentException::class.java) {
                mountOptsArg(opts)
            }
        }
    }

    @Test
    fun testMountOptsArg_rejectsSpaces() {
        assertThrows(IllegalArgumentException::class.java) {
            mountOptsArg("rw noatime")
        }
    }

    @Test
    fun testSecontextArg_acceptsValidContext() {
        val validContexts = listOf(
            "u:object_r:sdcard:s0",
            "u:r:untrusted_app:s0:c512,c768",
        )
        for (ctx in validContexts) {
            val arg = secontextArg(ctx)
            assertTrue("Should accept: $ctx", arg.quoted.contains(ctx))
        }
    }

    @Test
    fun testSecontextArg_rejectsMissingColon() {
        assertThrows(IllegalArgumentException::class.java) {
            secontextArg("invalid_context")
        }
    }

    @Test
    fun testSecontextArg_rejectsShellMetacharacters() {
        assertThrows(IllegalArgumentException::class.java) {
            secontextArg("u:r:malicious;cmd:s0")
        }
    }

    @Test
    fun testLoopDevArg_acceptsValidLoopDevice() {
        val validDevices = listOf(
            "/dev/loop0",
            "/dev/loop123",
            "/dev/block/loop0",
            "/dev/block/loop456",
        )
        for (dev in validDevices) {
            val arg = loopDevArg(dev)
            assertTrue("Should accept: $dev", arg.quoted.contains(dev))
        }
    }

    @Test
    fun testLoopDevArg_rejectsInvalidDevice() {
        val invalidDevices = listOf(
            "/dev/sda",
            "/dev/loopX",
            "/dev/loop",
            "/tmp/loop0",
            "/dev/loop0;malicious",
        )
        for (dev in invalidDevices) {
            assertThrows("Should reject: $dev", IllegalArgumentException::class.java) {
                loopDevArg(dev)
            }
        }
    }

    @Test
    fun testValidatePath_acceptsValidPaths() {
        val validPaths = listOf(
            "/data/local/tmp/test.img",
            "/storage/emulated/0/test.img",
            "/sdcard/Download/image.img",
            "relative/path/test.img",
        )
        for (path in validPaths) {
            assertTrue("Should accept: $path", validatePath(path))
        }
    }

    @Test
    fun testValidatePath_rejectsPathTraversal() {
        val invalidPaths = listOf(
            "/data/../etc/passwd",
            "../../../etc/passwd",
            "/data/local/../../../etc/passwd",
        )
        for (path in invalidPaths) {
            assertFalse("Should reject: $path", validatePath(path))
        }
    }

    @Test
    fun testValidatePath_rejectsShellMetacharacters() {
        val invalidPaths = listOf(
            "/tmp/file;rm",
            "/tmp/file|pipe",
            "/tmp/file&bg",
            "/tmp/file\$(cmd)",
            "/tmp/file`cmd`",
            "/tmp/file\u0000null",
            "/tmp/file\nline",
            "/tmp/file<redirect",
            "/tmp/file>redirect",
        )
        for (path in invalidPaths) {
            assertFalse("Should reject: $path", validatePath(path))
        }
    }

    @Test
    fun testValidateBindDir_acceptsAllowedDirectories() {
        val allowed = listOf(
            "/data/media/0/test",
            "/storage/emulated/0/DCIM",
            "/sdcard/Download",
            "/mnt/media_rw/sdcard1/folder",
        )
        for (dir in allowed) {
            val result = validateBindDir(dir)
            assertNull("Should accept: $dir (got: $result)", result)
        }
    }

    @Test
    fun testValidateBindDir_rejectsSystemDirectories() {
        val blocked = listOf(
            "/system",
            "/system/bin",
            "/vendor/lib",
            "/data/data",
            "/data/app",
            "/proc/self",
            "/dev/block",
        )
        for (dir in blocked) {
            val result = validateBindDir(dir)
            assertNotNull("Should reject: $dir", result)
            assertTrue("Error should mention allowed zones", result!!.contains("must be under"))
        }
    }

    @Test
    fun testValidateBindDir_rejectsRelativePaths() {
        val result = validateBindDir("relative/path")
        assertNotNull(result)
        assertTrue(result!!.contains("absolute"))
    }

    @Test
    fun testValidateBindDir_rejectsEmpty() {
        val result = validateBindDir("")
        assertNotNull(result)
        assertTrue(result!!.contains("empty"))
    }

    @Test
    fun testValidateBindDir_rejectsPathTraversal() {
        val result = validateBindDir("/data/media/../system")
        assertNotNull(result)
    }

    @Test
    fun testValidateBindDir_rejectsDisallowedLocations() {
        val result = validateBindDir("/tmp/test")
        assertNotNull(result)
        assertTrue(result!!.contains("must be under"))
    }

    @Test
    fun testSanitizeStem_removesLeadingDots() {
        assertEquals("test", sanitizeStem(".test"))
        assertEquals("test", sanitizeStem("..test"))
        assertEquals("test", sanitizeStem("...test"))
    }

    @Test
    fun testSanitizeStem_handlesDotDot() {
        assertEquals("mounted_img", sanitizeStem(".."))
    }

    @Test
    fun testSanitizeStem_handlesSingleDot() {
        assertEquals("mounted_img", sanitizeStem("."))
    }

    @Test
    fun testSanitizeStem_handlesEmpty() {
        assertEquals("mounted_img", sanitizeStem(""))
    }

    @Test
    fun testSanitizeStem_handlesBlank() {
        assertEquals("mounted_img", sanitizeStem("   "))
    }

    @Test
    fun testSanitizeStem_preservesValidStem() {
        assertEquals("myimage", sanitizeStem("myimage"))
        assertEquals("test_123", sanitizeStem("test_123"))
    }

    @Test
    fun testRootShell_rejectsNonWhitelistedBinary() {
        val result = RootShell.cmd("malicious_binary", ShellArg.of("arg"))
        assertFalse("Should reject non-whitelisted binary", result.isSuccess)
        assertTrue(result.output.contains("not allowed"))
    }

    @Test
    fun testRootShell_acceptsWhitelistedBinary() {
        val result = RootShell.cmd("echo", ShellArg.of("test"))
        assertFalse(result.output.contains("not allowed"))
    }

    @Test
    fun testRootShell_handlesExecutionFailure() {
        val result = RootShell.cmd("nonexistent_command", ShellArg.of("arg"))
        assertEquals(-1, result.exitCode)
        assertTrue(result.output.contains("not allowed"))
    }
}
