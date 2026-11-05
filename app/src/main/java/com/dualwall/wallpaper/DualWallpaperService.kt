package com.dualwall.wallpaper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import com.dualwall.core.Crop
import com.dualwall.data.ImageStore
import com.dualwall.data.Layer
import com.dualwall.data.Profile
import com.dualwall.data.RecipeBus
import com.dualwall.data.RecipeStore

/**
 * 横竖屏各用一套图 / 一套裁切的双面壁纸。
 *
 * 三条保证：
 * 1. 画布尺寸只认 onSurfaceChanged 给的 width/height，旋转、PC 模式、分辨率变更全由它驱动重算；
 * 2. 配方存归一化锚点 + 缩放，任何分辨率下构图一致；
 * 3. onOffsetsChanged 空实现 —— 桌面翻页不产生任何位移。
 *
 * 绘制走 drawBitmap(src, srcRect, dstRect)：srcRect 严格是视口比例，
 * dstRect 是整块画布，因此缩放均匀、没有第二次裁切。
 */
class DualWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = DualEngine()
    private inner class DualEngine : Engine() {

        private val store = RecipeStore(this@DualWallpaperService)
        private val handler = Handler(Looper.getMainLooper())
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        private val srcRect = Rect()
        private val dstRect = RectF()
        private val onRecipeChanged: () -> Unit = { handler.post { reload() } }

        private var surfaceW = 0
        private var surfaceH = 0
        private var applied: Layer? = null
        private var bitmap: Bitmap? = null

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            // ⚠️ 绝对不要在这里调用 setTouchEventsEnabled / setOffsetNotificationsEnabled。
            // 它们在 AOSP 里会走 Engine.updateSurface()，而 updateSurface 在 surface 新建时
            // 又会回调 onSurfaceCreated —— 直接变成自我递归，几百次后 StackOverflowError，
            // 整个进程挂掉（表现就是「点应用到桌面，应用直接闪退」）。
            // 这两个开关默认就是关闭的，我们的 onOffsetsChanged 也是空实现，不设也一样。
            RecipeBus.add(onRecipeChanged)
            Log.d(TAG, "onSurfaceCreated")
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceW = width
            surfaceH = height
            Log.d(TAG, "onSurfaceChanged ${width}x${height}")
            reload()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            Log.d(TAG, "onVisibilityChanged visible=$visible")
            if (visible) draw()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            RecipeBus.remove(onRecipeChanged)
            bitmap?.recycle()
            bitmap = null
            applied = null
            super.onSurfaceDestroyed(holder)
        }

        /** 翻页视差：故意什么都不做。 */
        override fun onOffsetsChanged(
            xOffset: Float,
            yOffset: Float,
            xOffsetStep: Float,
            yOffsetStep: Float,
            xPixelOffset: Int,
            yPixelOffset: Int,
        ) = Unit

        private fun reload() {
            try {
                reloadInternal()
            } catch (t: Throwable) {
                // 壁纸引擎崩掉会被系统反复重启，表现就是「桌面一直闪 / 应用总崩溃」
                Log.e(TAG, "reload 失败，退回纯黑", t)
            }
        }

        private fun reloadInternal() {
            if (surfaceW <= 0 || surfaceH <= 0) return
            val profile = if (surfaceW >= surfaceH) Profile.LANDSCAPE else Profile.PORTRAIT
            val layer = store.recipe.of(profile).sanitized()

            // 只改了锚点 / 缩放时保留已解码的位图，避免每次调参都重新读盘解码
            val sameImage = layer.imagePath != null && layer.imagePath == applied?.imagePath
            applied = layer
            if (sameImage && bitmap != null) {
                draw()
                return
            }

            bitmap?.takeIf { !it.isRecycled }?.recycle()
            bitmap = null

            if (layer.ready) {
                bitmap = decode(layer.imagePath!!, maxOf(surfaceW, surfaceH))
            }
            Log.d(TAG, "reload ready=${layer.ready} bmp=${bitmap?.width}x${bitmap?.height}")
            draw()
        }

        /** 解码失败或首轮就 OOM 时降一档再试，宁可糊一点也不要黑屏。 */
        private fun decode(path: String, target: Int): Bitmap? {
            val first = runCatching { ImageStore.load(path, target) }.getOrNull()
            if (first != null) return first
            Log.w(TAG, "按 $target 解码失败，降一半重试")
            return runCatching { ImageStore.load(path, target / 2) }.getOrNull()
        }

        private fun draw() {
            val holder = surfaceHolder
            if (!holder.surface.isValid) return
            val canvas: Canvas = holder.lockCanvas() ?: run {
                Log.w(TAG, "lockCanvas 返回 null")
                return
            }
            try {
                canvas.drawColor(Color.BLACK)
                val bmp = bitmap ?: return
                val layer = applied ?: return

                val crop = Crop.cropRect(
                    bmp.width, bmp.height, surfaceW, surfaceH,
                    layer.anchorX, layer.anchorY, layer.zoom,
                )
                // coverRect：比例一致时就是整块画布，永远等比，不会拉伸
                val dst = Crop.coverRect(crop.width, crop.height, surfaceW, surfaceH)
                srcRect.set(crop.left, crop.top, crop.right, crop.bottom)
                dstRect.set(dst.left.toFloat(), dst.top.toFloat(), dst.right.toFloat(), dst.bottom.toFloat())
                canvas.drawBitmap(bmp, srcRect, dstRect, paint)
            } catch (t: Throwable) {
                Log.e(TAG, "draw 失败", t)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }
    }

    private companion object {
        const val TAG = "DualWall"
    }
}
