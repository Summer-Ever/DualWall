package com.dualwall.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DecodeSampleTest {

    @Test
    fun `常见视口采样结果与预期一致`() {
        assertEquals(4, DecodeSample.inSampleSize(4000, 3000, 1400))
        assertEquals(2, DecodeSample.inSampleSize(4000, 3000, 2560))
        assertEquals(1, DecodeSample.inSampleSize(4000, 3000, 4096))
    }

    @Test
    fun `任意输入都满足像素预算且返回 2 的幂`() {
        val sources = listOf(
            4000 to 3000, 5700 to 4300, 8000 to 6000,
            12000 to 9000, 1000 to 10000, 2560 to 1600, 1 to 1,
        )
        val limits = listOf(1, 16, 256, 1400, 2560, 4096, 99999)
        for ((w, h) in sources) {
            for (limit in limits) {
                val s = DecodeSample.inSampleSize(w, h, limit)
                assertTrue("sample 必须为正：$w x $h / $limit -> $s", s >= 1)
                assertTrue("sample 必须是 2 的幂：$s", s and (s - 1) == 0)
                val budget = limit.coerceIn(1, DecodeSample.MAX_LONG_SIDE).toLong().let { it * it }
                val pixels = (w / s).toLong() * (h / s)
                assertTrue(
                    "像素预算超限：$w x $h / $limit -> $s (${w / s}x${h / s} = $pixels > $budget)",
                    pixels <= budget,
                )
            }
        }
    }

    @Test
    fun `极小目标不死循环也不溢出`() {
        val s = DecodeSample.inSampleSize(4000, 3000, 1)
        assertTrue("sample 应收敛在有限范围：$s", s >= 1 && s <= 1 shl 16)
        assertEquals(s, DecodeSample.inSampleSize(4000, 3000, 0))
        assertEquals(s, DecodeSample.inSampleSize(4000, 3000, -100))
    }

    @Test
    fun `目标越大采样系数不增`() {
        var last = Int.MAX_VALUE
        for (limit in listOf(256, 512, 1024, 1400, 2560, 4096)) {
            val s = DecodeSample.inSampleSize(4000, 3000, limit)
            assertTrue("limit=$limit 时采样反而变大：$s > $last", s <= last)
            last = s
        }
    }

    @Test
    fun `非法源图尺寸被拒绝`() {
        assertTrue(runCatching { DecodeSample.inSampleSize(0, 100, 100) }.isFailure)
        assertTrue(runCatching { DecodeSample.inSampleSize(100, -1, 100) }.isFailure)
    }
}
