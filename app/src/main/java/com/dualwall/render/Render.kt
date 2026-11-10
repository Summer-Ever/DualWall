package com.dualwall.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.util.Log
import android.util.Size
import android.view.Display
import android.view.WindowManager
import androidx.core.graphics.createBitmap
import com.dualwall.core.Crop
import com.dualwall.data.Layer

/**
 * 视口尺寸。壁纸覆盖的是整块屏幕（含状态栏/导航栏/挖孔区），
 * 所以不能用 Activity 的 window 尺寸，要用整块 display 的 bounds。
 *
 * 这里做了三层兜底：maximumWindowMetrics → currentWindowMetrics → DisplayMetrics。
 * 任何一层抛异常都不会让界面起不来（PC 模式、平行视窗这类状态下
 * WindowManager 拿不準是常事，宁可退化成物理分辨率也不能崩）。
 */
object Viewport {

    fun size(context: Context, landscape: Boolean): Size {
        val bounds = readBounds(context)
        val w = if (bounds.width() > 0) bounds.width() else 1080
        val h = if (bounds.height() > 0) bounds.height() else 1920
        val long = maxOf(w, h)
        val short = minOf(w, h)
        return if (landscape) Size(long, short) else Size(short, long)
    }

    /**
     * 优先用 display 的真实像素尺寸：它会跟随当前旋转，
     * 而且不受「一视界 / 平行视窗」把 Activity 窗口改小的干扰。
     */
    private fun readBounds(context: Context): Rect {
        val real = runCatching {
            val display = context.display
                ?: context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            display?.getRealMetrics(dm)
            Rect(0, 0, dm.widthPixels, dm.heightPixels)
        }.getOrNull()
        if (real != null && real.width() > 0 && real.height() > 0) return real

        val wm = runCatching { context.getSystemService(WindowManager::class.java) }.getOrNull()

        val max = runCatching { wm?.maximumWindowMetrics?.bounds }.getOrNull()
        if (max != null && max.width() > 0 && max.height() > 0) return max

        val current = runCatching { wm?.currentWindowMetrics?.bounds }.getOrNull()
        if (current != null && current.width() > 0 && current.height() > 0) return current

        Log.w("DualWall", "WindowMetrics 均不可用，退回 DisplayMetrics")
        return runCatching {
            val dm = context.resources.displayMetrics
            Rect(0, 0, dm.widthPixels, dm.heightPixels)
        }.getOrDefault(Rect(0, 0, 1080, 1920))
    }
}

object Cropper {

    /**
     * 把裁切框一次绘制到目标尺寸的位图上，只分配一张输出位图。
     * 不再产生 createBitmap 中间副本与 createScaledBitmap 第二副本，
     * 也避开 AOSP createBitmap 在整图裁切时原样返回 source 带来的所有权混淆。
     *
     * [source] 的所有权完全归调用方，本函数不会回收它。
     */
    fun render(source: Bitmap, layer: Layer, dstW: Int, dstH: Int): Bitmap {
        if (dstW <= 0 || dstH <= 0) error("目标尺寸非法：${dstW}×$dstH")
        val r = Crop.cropRect(
            source.width, source.height, dstW, dstH,
            layer.anchorX, layer.anchorY, layer.zoom,
        )
        val left = r.left.coerceIn(0, source.width - 1)
        val top = r.top.coerceIn(0, source.height - 1)
        val src = Rect(
            left,
            top,
            r.right.coerceIn(left + 1, source.width),
            r.bottom.coerceIn(top + 1, source.height),
        )
        val out = createBitmap(dstW, dstH)
        try {
            val canvas = Canvas(out)
            canvas.drawBitmap(source, src, Rect(0, 0, dstW, dstH), Paint(Paint.FILTER_BITMAP_FLAG))
            canvas.setBitmap(null)
            return out
        } catch (t: Throwable) {
            out.recycle()
            throw t
        }
    }
}
