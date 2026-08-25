package com.comicmanager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JmIdParserTest {
    @Test
    fun extractsKnownBackupFileNames() {
        assertEquals("1037526", JmIdParser.extract("1037526.zip"))
        assertEquals("1435644", JmIdParser.extract("1435644 (1).zip"))
        assertEquals("1107546", JmIdParser.extract("1107546 (12)"))
        assertEquals("1037526", JmIdParser.extract("JM1037526.zip"))
        assertEquals("1037526", JmIdParser.extract("[JM1037526] title.zip"))
        assertEquals("1037526", JmIdParser.extract("1037526_title.zip"))
    }

    @Test
    fun ignoresNumbersInsideOrdinaryTitles() {
        assertNull(JmIdParser.extract("C108 动漫.zip"))
        assertNull(JmIdParser.extract("中国国家地理 2022年第1期.zip"))
        assertNull(JmIdParser.extract("title 1037526.zip"))
        assertNull(JmIdParser.extract("0.zip"))
    }
}
