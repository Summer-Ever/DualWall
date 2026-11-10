package com.dualwall.core

/**
 * 位图解码采样系数。纯 JVM，与 Android 解耦，便于单测覆盖极端输入。
 *
 * 采样后同时满足两条约束：
 * 1. 长边不低于 0.7 倍目标长边，避免欠采样发虚（源图本身更小则不受限）；
 * 2. 解码总像素数不超过目标长边的平方，避免超宽 / 超高图一次吃掉几十 MB。
 *
 * 循环有明确的终止条件与上限，任何输入都会在有限步内返回 2 的幂。
 */
object DecodeSample {

    /** 目标长边的绝对上限，防止异常大的视口值直接推高解码尺寸。 */
    const val MAX_LONG_SIDE = 4096

    private const val MAX_SAMPLE = 1 shl 16
    private const val QUALITY_PERCENT = 70

    fun inSampleSize(srcW: Int, srcH: Int, requestedLongSide: Int): Int {
        require(srcW > 0 && srcH > 0) { "源图尺寸必须为正数" }

        val limit = requestedLongSide.coerceIn(1, MAX_LONG_SIDE)
        val threshold = maxOf(1, limit * QUALITY_PERCENT / 100)
        val pixelBudget: Long = limit.toLong() * limit
        val longSide = maxOf(srcW, srcH)

        var sample = 1
        while (sample < MAX_SAMPLE && sample * 2 <= longSide) {
            val w = srcW / sample
            val h = srcH / sample
            val overBudget = w.toLong() * h > pixelBudget
            val canDrop = longSide / (sample * 2) >= threshold
            if (!overBudget && !canDrop) break
            sample *= 2
        }
        return sample
    }
}
