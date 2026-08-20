// SPDX-License-Identifier: GPL-3.0-or-later

package org.codeberg.aimapp.utils.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit


class ShellArgQuotingTest {
    private fun runViaSh(cmdLine: String): String {
        val p = ProcessBuilder("sh", "-c", cmdLine).redirectErrorStream(true).start()
        val out = BufferedReader(InputStreamReader(p.inputStream)).readText()
        if (!p.waitFor(15, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            throw AssertionError("sh command timed out: $cmdLine")
        }
        return out
    }

    private fun roundTripByteLength(raw: String): Int {
        val arg = ShellArg.of(raw)
        val out = runViaSh("printf '%s' ${arg.quoted} | wc -c")
        return out.trim().toInt()
    }

    @Test
    fun `single quote in value is escaped and survives`() {
        val raw = "it's a test"
        assertEquals(raw.toByteArray().size, roundTripByteLength(raw))
    }

    @Test
    fun `embedded tab is preserved as one argument`() {
        val raw = "a\tb\tc"
        assertEquals(raw.toByteArray().size, roundTripByteLength(raw))
    }

    @Test
    fun `embedded space does not split into multiple args`() {
        val out = runViaSh(
            "set -- ${ShellArg.of("one two three").quoted}; echo \$#"
        )
        assertEquals("1", out.trim())
    }

    @Test
    fun `shell metacharacters are inert`() {
        val dangerous = listOf("\$(id)", "`id`", "; id", "| id", "&& id", "> /tmp/pwned")
        for (raw in dangerous) {
            val out = runViaSh("printf '%s' ${ShellArg.of(raw).quoted}")
            assertEquals("value '$raw' should round-trip literally, not execute", raw, out)
        }
    }

    @Test
    fun `backslash sequences are not interpreted`() {
        val raw = "%n\\t%u"  // literal backslash-t, should NOT become a tab or bare 't'
        val out = runViaSh("printf '%s' ${ShellArg.of(raw).quoted}")
        assertEquals(raw, out)
    }

    @Test
    fun `newline in value stays a single argument`() {
        val out = runViaSh(
            "set -- ${ShellArg.of("line1\nline2").quoted}; echo \$#"
        )
        assertEquals("1", out.trim())
    }

    @Test
    fun `literal is only used with fixed known-safe tokens`() {
        val expectedFixedLiterals = setOf(
            "-p", "-t", "-o", "-f", "-d", "-b", "-Rh", "-dZ", "-R", "-qw",
            "-exec", "{}", "+", "-type", "d", "f", "l", "-not", "b",
        )
        val unsafeChars = listOf(
            ' ', '\t', '\n', '\r', ';', '|', '&', '<', '>', '$', '`', '"', '\'',
            '\\', '(', ')', '[', ']', '*', '?', '#'
        )
        for (tok in expectedFixedLiterals) {
            assertTrue("statement: token '$tok' must be shell-safe for literal()", tok.none { it in unsafeChars })
        }
    }


    @Test
    fun `rejects non-whitelisted binaries`() {
        assertThrows(IllegalArgumentException::class.java) {
            ShellCmd.of("rm", ShellArg.literal("-rf"), ShellArg.literal("/"))
        }
    }

    @Test
    fun `binary path traversal via basename does not bypass allowlist`() {
        assertThrows(IllegalArgumentException::class.java) {
            ShellCmd.of("/usr/bin/../../rm", ShellArg.literal("-rf"))
        }
    }

    @Test
    fun `absolute path to a whitelisted binary is allowed`() {
        val cmd = ShellCmd.of("/system/bin/mke2fs", ShellArg.literal("-V"))
        assertTrue(cmd.fragment.startsWith("'/system/bin/mke2fs'"))
    }
}
