package com.playtranslate.language

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the [ThaiCharacterCluster] port against golden clusters generated from
 * PyThaiNLP 5.3.4 `tcc_p.tcc` (the upstream this is a faithful port of). A
 * Thai-character transcription error in the rule template surfaces here. Pure
 * JVM — mirrors [ChineseEngineTokenizerTest] / [KoreanEngineTokenizerTest].
 */
class ThaiCharacterClusterTest {

    // word -> clusters joined with '/', verbatim from PyThaiNLP tcc_p.tcc.
    private val golden = listOf(
        "สวัสดี" to "ส/วัส/ดี",
        "ความสุข" to "ค/วา/ม/สุ/ข",
        "น้ำ" to "น้ำ",
        "เป็น" to "เป็น",
        "ไป" to "ไป",
        "กรุงเทพมหานคร" to "ก/รุ/ง/เท/พ/ม/หา/น/ค/ร",
        "ผู้ใหญ่" to "ผู้/ให/ญ่",
        "เรียน" to "เรีย/น",
        "เกี่ยว" to "เกี่ย/ว",
        "แล้ว" to "แล้/ว",
        "โทรศัพท์" to "โท/ร/ศัพท์",
        "มหาวิทยาลัย" to "ม/หา/วิ/ท/ยา/ลัย",
        "ที่" to "ที่",
        "แม่น้ำ" to "แม่/น้ำ",
        "กิน" to "กิ/น",
        "ฉันรักเธอ" to "ฉัน/รัก/เธ/อ",
    )

    @Test fun `clusters match PyThaiNLP golden output`() {
        for ((word, expected) in golden) {
            assertEquals(
                "TCC clusters of '$word'",
                expected,
                ThaiCharacterCluster.clusters(word).joinToString("/"),
            )
        }
    }

    @Test fun `rule count matches upstream`() {
        // tcc_p.py `_RE_TCC` has 29 alternatives; a missing/extra rule is a port bug.
        assertEquals(29, ThaiCharacterCluster.ruleCount)
    }

    @Test fun `clusters reconstruct the input verbatim`() {
        for ((word, _) in golden) {
            assertEquals(word, ThaiCharacterCluster.clusters(word).joinToString(""))
        }
    }

    @Test fun `boundary array marks cluster ends`() {
        val text = "แม่น้ำ" // clusters แม่ (end 3) + น้ำ (end 6)
        val arr = ThaiCharacterCluster.tccBoundaryArray(text)
        assertEquals(text.length + 1, arr.size)
        assertTrue(arr[3])
        assertTrue(arr[6])
        assertFalse("mid-cluster position 1 is not a boundary", arr[1])
    }

    @Test fun `empty text yields no clusters`() {
        assertEquals(emptyList<String>(), ThaiCharacterCluster.clusters(""))
        assertEquals(1, ThaiCharacterCluster.tccBoundaryArray("").size)
    }
}
