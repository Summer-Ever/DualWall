package com.dualwall.core

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/** 源图坐标系下的整数像素矩形，右/下为开区间。 */
data class IntRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** 统一缩放的落位结果，等价于 Matrix.setScale(s, s) + postTranslate(dx, dy)。 */
data class Placement(val scale: Float, val dx: Float, val dy: Float)

/**
 * 壁纸裁切几何。
 *
 * 设计原则（决定「不位移」的三条）：
 * 1. 裁切框宽高比与目标视口 **严格** 相等（用整数比例单元，不用 float 反推）；
 * 2. 裁切框始终落在源图内，边界用整数像素计算，不产生 0.5px 累积误差；
 * 3. 落位时两个轴的缩放因子是同一个数，因此系统/画布不会再做一次 cover 裁切。
 *
 * 纯 Kotlin，无 Android 依赖，可直接在 JVM 上跑单测。
 */
object Crop {

    fun gcd(a: Int, b: Int): Int {
        var x = abs(a)
        var y = abs(b)
        while (y != 0) {
            val t = x % y
            x = y
            y = t
        }
        return if (x == 0) 1 else x
    }

    fun clamp01(v: Float): Float = when {
        v.isNaN() -> 0.5f
        v < 0f -> 0f
        v > 1f -> 1f
        else -> v
    }

    /**
     * 源图上的裁切框，宽高比严格等于 [dstW]:[dstH]。
     *
     * @param anchorX 0=贴左边，0.5=居中，1=贴右边
     * @param anchorY 0=贴上边，0.5=居中，1=贴下边
     * @param zoom    >=1，越大裁得越紧（放大画面）
     */
    fun cropRect(
        srcW: Int,
        srcH: Int,
        dstW: Int,
        dstH: Int,
        anchorX: Float,
        anchorY: Float,
        zoom: Float = 1f,
    ): IntRect {
        require(srcW > 0 && srcH > 0 && dstW > 0 && dstH > 0) { "尺寸必须为正数" }

        val g = gcd(dstW, dstH)
        val uw = dstW / g
        val uh = dstH / g

        // 源图连一个比例单元都放不下（极端小图 / 极端比例视口）：
        // 严格比例在整数像素下无解，退回整张图，由调用方按 cover 处理。
        val maxK = minOf(srcW / uw, srcH / uh)
        if (maxK < 1) return IntRect(0, 0, srcW, srcH)

        val z = if (zoom.isNaN() || zoom < 1f) 1f else zoom
        val k = floor(maxK / z.toDouble()).toInt().coerceIn(1, maxK)

        val cw = uw * k
        val ch = uh * k
        val left = ((srcW - cw) * clamp01(anchorX)).roundToInt().coerceIn(0, srcW - cw)
        val top = ((srcH - ch) * clamp01(anchorY)).roundToInt().coerceIn(0, srcH - ch)
        return IntRect(left, top, left + cw, top + ch)
    }

    /**
     * 把 [srcW]x[srcH] 的图按统一缩放落到 [dstW]x[dstH] 画布。
     * 当两者宽高比相等时，scale 在两轴上同值且 dx=dy=0（即零裁切、零位移）。
     */
    fun placement(
        srcW: Int,
        srcH: Int,
        dstW: Int,
        dstH: Int,
        anchorX: Float,
        anchorY: Float,
    ): Placement {
        val s = max(dstW.toDouble() / srcW, dstH.toDouble() / srcH).toFloat()
        return Placement(
            scale = s,
            dx = (dstW - srcW * s) * clamp01(anchorX),
            dy = (dstH - srcH * s) * clamp01(anchorY),
        )
    }

    /**
     * 把 [srcW]x[srcH] 按统一缩放 cover 到 [dstW]x[dstH] 画布，返回画布坐标系下的矩形。
     * 宽高比完全一致时返回整块画布；不一致时居中溢出（超出部分被裁掉），**永不拉伸**。
     */
    fun coverRect(srcW: Int, srcH: Int, dstW: Int, dstH: Int): IntRect {
        require(srcW > 0 && srcH > 0 && dstW > 0 && dstH > 0) { "尺寸必须为正数" }
        if (srcW.toLong() * dstH == srcH.toLong() * dstW) return IntRect(0, 0, dstW, dstH)

        val s = max(dstW.toDouble() / srcW, dstH.toDouble() / srcH)
        val w = max(Math.ceil(srcW * s).toInt(), dstW)
        val h = max(Math.ceil(srcH * s).toInt(), dstH)
        val left = (dstW - w) / 2
        val top = (dstH - h) / 2
        return IntRect(left, top, left + w, top + h)
    }

    /** 画布上拖动 (dragX, dragY) 像素后新的归一化锚点。拖不动时保持原值。 */
    fun anchorAfterDrag(
        srcW: Int,
        srcH: Int,
        dstW: Int,
        dstH: Int,
        anchorX: Float,
        anchorY: Float,
        zoom: Float,
        dragX: Float,
        dragY: Float,
    ): Pair<Float, Float> {
        val r = cropRect(srcW, srcH, dstW, dstH, anchorX, anchorY, zoom)
        val slackX = srcW - r.width
        val slackY = srcH - r.height
        // 该轴没有余量时锚点无意义，原样保留，避免出现莫名跳动
        val nx = if (slackX <= 0) clamp01(anchorX) else clamp01((r.left - dragX * r.width / dstW) / slackX)
        val ny = if (slackY <= 0) clamp01(anchorY) else clamp01((r.top - dragY * r.height / dstH) / slackY)
        return nx to ny
    }

    /**
     * 以画布归一化焦点 (fx, fy) 为中心缩放，返回新的锚点。
     * 焦点下的源像素在缩放前后落在同一个画布位置，捏合手感才对。
     */
    fun anchorAfterZoom(
        srcW: Int,
        srcH: Int,
        dstW: Int,
        dstH: Int,
        anchorX: Float,
        anchorY: Float,
        oldZoom: Float,
        newZoom: Float,
        fx: Float,
        fy: Float,
    ): Pair<Float, Float> {
        val r0 = cropRect(srcW, srcH, dstW, dstH, anchorX, anchorY, oldZoom)
        val r1 = cropRect(srcW, srcH, dstW, dstH, anchorX, anchorY, newZoom)
        if (r0.width == r1.width && r0.height == r1.height) return anchorX to anchorY

        val px = r0.left + clamp01(fx) * r0.width
        val py = r0.top + clamp01(fy) * r0.height
        val slackX = srcW - r1.width
        val slackY = srcH - r1.height
        val nx = if (slackX <= 0) clamp01(anchorX) else clamp01((px - fx * r1.width) / slackX)
        val ny = if (slackY <= 0) clamp01(anchorY) else clamp01((py - fy * r1.height) / slackY)
        return nx to ny
    }
}
