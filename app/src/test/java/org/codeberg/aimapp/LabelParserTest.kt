// SPDX-License-Identifier: GPL-3.0-or-later

package org.codeberg.aimapp

import org.codeberg.aimapp.utils.paths.labelToMountStem
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
