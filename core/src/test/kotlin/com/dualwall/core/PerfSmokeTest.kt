package com.dualwall.core

import org.junit.Assert.assertTrue
import org.junit.Test

/** 热路径吞吐的回归门槛：阈值取宽松上限，只拦截数量级退化，不依赖具体机器频率。 */
class PerfSmokeTest {

    @Test
    fun `采样计算与裁切几何热路径吞吐`() {
        val rounds = 100_000

        val t0 = System.nanoTime()
        var acc = 0
        repeat(rounds) { acc += DecodeSample.inSampleSize(4000 + it % 512, 3000, 2560) }
        val sampleMs = (System.nanoTime() - t0) / 1_000_000

        val t1 = System.nanoTime()
        var total = 0
        repeat(rounds) {
            total += Crop.cropRect(4000, 3000, 2560, 1600, 0.37f, 0.62f, 1.4f).width
        }
        val cropMs = (System.nanoTime() - t1) / 1_000_000

        println("[perf-smoke] rounds=$rounds sample=${sampleMs}ms crop=${cropMs}ms acc=$acc total=$total")
        assertTrue("采样计算 $rounds 次耗时 ${sampleMs}ms，疑似数量级退化", sampleMs < 2000)
        assertTrue("裁切几何 $rounds 次耗时 ${cropMs}ms，疑似数量级退化", cropMs < 2000)
    }
}
