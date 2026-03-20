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

package org.codeberg.aimapp

import org.codeberg.aimapp.utils.labelToMountStem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LabelParserTest {

    @Test
    fun normalLabelsPassThrough() {
        assertEquals("Ventoy", labelToMountStem("Ventoy"))
        assertEquals("VTOYEFI", labelToMountStem("VTOYEFI"))
        assertEquals("MyVolume", labelToMountStem("MyVolume"))
        assertEquals("MY_ISO_LABEL", labelToMountStem("MY_ISO_LABEL"))
    }

    @Test
    fun trailingSpacesStripped() {
        assertEquals("VTOYEFI", labelToMountStem("VTOYEFI    "))
        assertEquals("label", labelToMountStem("  label  "))
    }

    @Test
    fun emptyAndPlaceholderLabelsReturnNull() {
        assertNull(labelToMountStem(null))
        assertNull(labelToMountStem(""))
        assertNull(labelToMountStem("   "))
        assertNull(labelToMountStem("NO NAME"))
        assertNull(labelToMountStem("no name"))
        assertNull(labelToMountStem("NO_NAME"))
        assertNull(labelToMountStem("NONAME"))
    }

    @Test
    fun controlCharsRejected() {
        assertNull(labelToMountStem("A\u0001B"))
        assertNull(labelToMountStem("te\u0000st"))
        assertNull(labelToMountStem("hel\u007Flo"))
    }

    @Test
    fun shellMetacharsRejected() {
        assertNull(labelToMountStem("sa\$fe"))
        assertNull(labelToMountStem("sa`fe"))
        assertNull(labelToMountStem("sa|fe"))
        assertNull(labelToMountStem("sa;fe"))
        assertNull(labelToMountStem("sa&fe"))
        assertNull(labelToMountStem("sa\\fe"))
    }

    @Test
    fun unicodeLabelsRejected() {
        assertNull(labelToMountStem("日本語"))
        assertNull(labelToMountStem("données"))
        assertNull(labelToMountStem("Тест"))
    }

    @Test
    fun longLabelsTruncated() {
        val long = "A".repeat(200)
        val result = labelToMountStem(long)!!
        assertEquals(64, result.length)
    }

    @Test
    fun unicodeControlCharsRejected() {
        assertNull(labelToMountStem("A\u200BB"))
        assertNull(labelToMountStem("A\u00ADB"))
    }

    @Test
    fun allDangerousCharsProduceNull() {
        assertNull(labelToMountStem("\$`|;&"))
    }

    @Test
    fun spacesRejected() {
        assertNull(labelToMountStem("My Label"))
        assertNull(labelToMountStem("Label With Spaces"))
    }
}
