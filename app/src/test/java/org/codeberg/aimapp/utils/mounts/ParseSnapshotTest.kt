// SPDX-License-Identifier: GPL-3.0-or-later

package org.codeberg.aimapp.utils.mounts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParseSnapshotTest {

    private val mp = "/data/user/0/org.codeberg.aimapp.debug/files/mounts/test"

    private fun rec(path: String, uid: Int, gid: Int, mode: String) =
        "$path\t$uid\t$gid\t$mode"

    @Test
    fun `parses well-formed multi-line snapshot`() {
        val raw = listOf(
            rec("$mp/a", 1000, 1000, "644"),
            rec("$mp/dir/b", 0, 0, "755"),
        ).joinToString("\n")
        val entries = parseSnapshot(raw, mp)
        assertEquals(2, entries.size)
        assertEquals(PermEntry("$mp/a", 1000, 1000, "644"), entries[0])
    }

    @Test
    fun `skips record split by embedded newline, keeps rest`() {
        val raw = listOf(
            rec("$mp/good", 1000, 1000, "644"),
            "$mp/dirB/evil", // first half of a %n-split filename
            "name.txt\t1001\t1001\t640", // second half
            rec("$mp/also_good", 0, 0, "600"),
        ).joinToString("\n")
        val entries = parseSnapshot(raw, mp)
        assertEquals(2, entries.size)
        assertTrue(entries.none { it.path.contains("evil") || it.path.contains("name.txt") })
    }

    // --- path traversal / escaping mountPoint ---
    @Test
    fun `rejects entries outside mountPoint`() {
        val raw = listOf(
            rec("$mp/ok", 1000, 1000, "644"),
            rec("/etc/passwd", 0, 0, "644"), // absolute escape
            rec("$mp/../../../etc/shadow", 0, 0, "600"), // traversal, still prefix-matches textually
            rec("${mp}evilsibling/x", 0, 0, "777"), // prefix-collision: "/mounts/testevilsibling"
        ).joinToString("\n")
        val entries = parseSnapshot(raw, mp)
        assertEquals(1, entries.size)
        assertEquals("$mp/ok", entries[0].path)
    }

    @Test
    fun `mountPoint itself is accepted as a valid entry`() {
        val raw = rec(mp, 1000, 1000, "755")
        val entries = parseSnapshot(raw, mp)
        assertEquals(1, entries.size)
        assertEquals(PermEntry(mp, 1000, 1000, "755"), entries[0])
    }

    @Test
    fun `rejects non-numeric uid or gid`() {
        val raw = listOf(
            "$mp/a\tnotanumber\t1000\t644",
            "$mp/b\t1000\tnotanumber\t644",
        ).joinToString("\n")
        assertTrue(parseSnapshot(raw, mp).isEmpty())
    }

    @Test
    fun `rejects negative or oversized uid via non-octal-looking mode too`() {
        val raw = "$mp/a\t-1\t0\t644"
        val entries = parseSnapshot(raw, mp)
        assertEquals(listOf(PermEntry("$mp/a", -1, 0, "644")), entries)
    }

    @Test
    fun `rejects non-octal or malformed mode strings`() {
        val bad = listOf("888", "abc", "", "77", "12345", "-644", "644 ", " 644")
        for (m in bad) {
            val entries = parseSnapshot("$mp/a\t1000\t1000\t$m", mp)
            assertTrue("mode '$m' should have been rejected", entries.isEmpty())
        }
    }

    @Test
    fun `accepts 3 and 4 digit octal modes including setuid bits`() {
        val entries = parseSnapshot(
            listOf(
                rec("$mp/a", 1000, 1000, "644"),
                rec("$mp/b", 1000, 1000, "4755"),
                rec("$mp/c", 1000, 1000, "1777"),
                rec("$mp/d", 1000, 1000, "2770"),
            ).joinToString("\n"), mp
        )
        assertEquals(4, entries.size)
    }

    @Test
    fun `rejects records with wrong field count`() {
        val raw = listOf(
            "$mp/toofew\t1000\t1000",
            "$mp/toomany\t1000\t1000\t644\textra",
        ).joinToString("\n")
        assertTrue(parseSnapshot(raw, mp).isEmpty())
    }

    @Test
    fun `path containing a literal tab is rejected, not misparsed as extra field`() {
        val raw = "$mp/weird\tname\t1000\t1000\t644"
        assertTrue(parseSnapshot(raw, mp).isEmpty())
    }

    @Test
    fun `ignores blank lines without counting them as skipped`() {
        val raw = "\n\n${rec("$mp/a", 1000, 1000, "644")}\n\n"
        assertEquals(1, parseSnapshot(raw, mp).size)
    }

    @Test
    fun `tolerates CRLF line endings`() {
        val raw = "${rec("$mp/a", 1000, 1000, "644")}\r\n${rec("$mp/b", 0, 0, "600")}\r\n"
        assertEquals(2, parseSnapshot(raw, mp).size)
    }

    @Test
    fun `handles large snapshot without excessive slowdown`() {
        val raw = (1..20_000).joinToString("\n") { rec("$mp/f_$it", 1000, 1000, "644") }
        val entries = parseSnapshot(raw, mp)
        assertEquals(20_000, entries.size)
    }

    @Test
    fun `empty snapshot yields empty list, not failure`() {
        assertTrue(parseSnapshot("", mp).isEmpty())
    }
}
