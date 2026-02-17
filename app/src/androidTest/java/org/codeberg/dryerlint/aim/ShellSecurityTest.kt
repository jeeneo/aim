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

    @Test
    fun testRootShell_cmdOverload_acceptsPreBuiltShellCmd() {
        val command = ShellCmd.of("echo", ShellArg.of("hello"))
        val result = RootShell.cmd(command)
        assertFalse(result.output.contains("not allowed"))
    }

    @Test
    fun testRootShell_cmdOverload_withPipeInto() {
        val command = ShellCmd.of("echo", ShellArg.of("pipe test"))
        val pipe = ShellCmd.of("head", ShellArg.literal("-1"))
        val result = RootShell.cmd(command, pipeInto = pipe)
        assertFalse(result.output.contains("not allowed"))
    }

    @Test
    fun testRootShell_cmdOverload_withChain() {
        val command = ShellCmd.of("echo", ShellArg.of("first"))
        val chained = ShellCmd.of("echo", ShellArg.of("second"))
        val result = RootShell.cmd(command, chain = chained)
        assertFalse(result.output.contains("not allowed"))
    }

    @Test
    fun testRootShell_cmdOverload_withOrChain() {
        val command = ShellCmd.of("echo", ShellArg.of("primary"))
        val fallback = ShellCmd.of("echo", ShellArg.of("fallback"))
        val result = RootShell.cmd(command, orChain = fallback)
        assertFalse(result.output.contains("not allowed"))
    }

    @Test
    fun testShellCmd_of_rejectsNonWhitelistedBinary() {
        assertThrows(IllegalArgumentException::class.java) {
            ShellCmd.of("malicious_binary", ShellArg.of("arg"))
        }
    }

    @Test
    fun testShellCmd_of_rejectsArbitraryAbsolutePath() {
        assertThrows(IllegalArgumentException::class.java) {
            ShellCmd.of("/data/local/tmp/evil", ShellArg.of("arg"))
        }
    }

    @Test
    fun testShellCmd_of_acceptsAllowedBinary() {
        val cmd = ShellCmd.of("echo", ShellArg.of("test"))
        assertTrue("Fragment should contain echo", cmd.fragment.contains("echo"))
    }

    @Test
    fun testShellCmd_of_acceptsAllowedAbsolutePath() {
        val cmd = ShellCmd.of("/system/bin/mke2fs", ShellArg.literal("-V"))
        assertTrue(cmd.fragment.contains("mke2fs"))
    }

    @Test
    fun testShellCmd_of_quotesAbsolutePath() {
        val cmd = ShellCmd.of("/system/bin/mke2fs", ShellArg.literal("-V"))
        assertTrue("Absolute path should be single-quoted", cmd.fragment.startsWith("'"))
    }

    @Test
    fun testShellCmd_of_busyboxPrefix() {
        val cmd = ShellCmd.of("grep", ShellArg.literal("-F"), busyboxBin = "/data/adb/magisk/busybox")
        assertTrue("Should contain busybox path", cmd.fragment.contains("busybox"))
        assertTrue("Should contain grep", cmd.fragment.contains("grep"))
    }

    @Test
    fun testShellCmd_of_busyboxPrefixQuotesPath() {
        val cmd = ShellCmd.of("grep", busyboxBin = "/data/adb/magisk/busybox")
        assertTrue("Busybox path should be single-quoted", cmd.fragment.startsWith("'"))
    }

    @Test
    fun testShellCmd_of_stdinRedirection() {
        val imgArg = ShellArg.of("/sdcard/test.img")
        val cmd = ShellCmd.of("wc", ShellArg.literal("-c"), stdinFrom = imgArg)
        assertTrue("Should contain < for stdin", cmd.fragment.contains(" < "))
        assertTrue("Should contain the file path", cmd.fragment.contains("test.img"))
    }

    @Test
    fun testShellCmd_chain_joinsWithDoubleAmpersand() {
        val a = ShellCmd.of("chown", ShellArg.literal("1000:1000"), ShellArg.of("/tmp"))
        val b = ShellCmd.of("chmod", ShellArg.literal("755"), ShellArg.of("/tmp"))
        val chained = ShellCmd.chain(a, b)
        assertTrue("Chain should join with &&", chained.fragment.contains(" && "))
    }

    @Test
    fun testShellCmd_chain_preservesOrder() {
        val a = ShellCmd.of("echo", ShellArg.of("first"))
        val b = ShellCmd.of("echo", ShellArg.of("second"))
        val chained = ShellCmd.chain(a, b)
        val idxFirst = chained.fragment.indexOf("first")
        val idxSecond = chained.fragment.indexOf("second")
        assertTrue("First command should precede second", idxFirst < idxSecond)
    }

    @Test
    fun testShellCmd_chain_multipleCommands() {
        val a = ShellCmd.of("echo", ShellArg.of("a"))
        val b = ShellCmd.of("echo", ShellArg.of("b"))
        val c = ShellCmd.of("echo", ShellArg.of("c"))
        val chained = ShellCmd.chain(a, b, c)
        val parts = chained.fragment.split(" && ")
        assertEquals("Three commands produce three parts", 3, parts.size)
    }

    @Test
    fun testShellCmd_cannotBypassAllowlistViaBusybox() {
        assertThrows(IllegalArgumentException::class.java) {
            ShellCmd.of("rm", ShellArg.literal("-rf"), ShellArg.of("/"), busyboxBin = "/data/adb/magisk/busybox")
        }
    }

    @Test
    fun testShellCmd_rejectsEmptyBinary() {
        assertThrows(IllegalArgumentException::class.java) {
            ShellCmd.of("")
        }
    }

    @Test
    fun testShellCmd_quotesBusyboxPathWithSingleQuotes() {
        val cmd = ShellCmd.of("echo", busyboxBin = "/data/adb/magi'sk/busybox")
        assertTrue("Busybox single quote should be escaped", cmd.fragment.contains("'\\''"))
    }

    @Test
    fun testShellCmd_quotesAbsolutePathWithSingleQuotes() {
        val cmd = ShellCmd.of("/system/bin/mke2fs")
        assertTrue(cmd.fragment.startsWith("'/system/bin/mke2fs'"))
    }

    @Test
    fun testShellCmd_argsAreIncludedInFragment() {
        val cmd = ShellCmd.of("grep", ShellArg.literal("-F"), ShellArg.of("search term"))
        assertTrue(cmd.fragment.contains("-F"))
        assertTrue(cmd.fragment.contains("search term"))
    }

    @Test
    fun testBothCmdOverloads_sameAllowlistReject() {
        val r1 = RootShell.cmd("rm", ShellArg.literal("-rf"))
        assertFalse(r1.isSuccess)
        assertTrue(r1.output.contains("not allowed"))
        assertThrows(IllegalArgumentException::class.java) {
            ShellCmd.of("rm", ShellArg.literal("-rf"))
        }
    }

    @Test
    fun testBothCmdOverloads_sameAllowlistAccept() {
        val r1 = RootShell.cmd("echo", ShellArg.of("test"))
        assertFalse(r1.output.contains("not allowed"))
        val cmd = ShellCmd.of("echo", ShellArg.of("test"))
        val r2 = RootShell.cmd(cmd)
        assertFalse(r2.output.contains("not allowed"))
    }
}
