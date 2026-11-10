package com.dualwall.wallpaper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.HandlerThread
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
 * 解码全部在 Service 唯一的后台线程执行；主线程只做绘制，且同一时刻
 * 每个 Engine 只保留一个待解码请求。壁纸隐藏时立刻释放位图，回到桌面再按需解码。
 */
class DualWallpaperService : WallpaperService() {

    // 所有 Engine 共享一条解码线程：空闲时阻塞在 Looper 上，不产生轮询与唤醒。
    private val decodeThread = HandlerThread("DualWallDecode").apply { start() }
    private val decodeHandler = Handler(decodeThread.looper)

    override fun onDestroy() {
        decodeHandler.removeCallbacksAndMessages(null)
        decodeThread.quitSafely()
        super.onDestroy()
    }

    override fun onCreateEngine(): Engine = DualEngine()

    private inner class DualEngine : Engine() {

        private val store = RecipeStore(this@DualWallpaperService)
        private val mainHandler = Handler(Looper.getMainLooper())
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val srcRect = Rect()
        private val dstRect = RectF()
        private val onRecipeChanged: () -> Unit = { mainHandler.post { reload() } }

        private var surfaceW = 0
        private var surfaceH = 0
        private var pendingDecode: Runnable? = null
        private var applied: Layer? = null
        private var bitmap: Bitmap? = null

        @Volatile private var destroyed = false

        @Volatile private var generation = 0L

        @Volatile private var releasedByHide = false

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            // 注意：这里不要调用 setTouchEventsEnabled / setOffsetNotificationsEnabled。
            // 它们在 AOSP 里会走 Engine.updateSurface()，而 updateSurface 在 surface 新建时
            // 又会回调 onSurfaceCreated，形成自我递归直至栈溢出。这两个开关默认关闭，
            // onOffsetsChanged 也是空实现，不设置同样满足需求。
            RecipeBus.add(onRecipeChanged)
            Log.d(TAG, "onSurfaceCreated")
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            val changed = width != surfaceW || height != surfaceH
            surfaceW = width
            surfaceH = height
            Log.d(TAG, "onSurfaceChanged ${width}x${height}")
            if (!changed && bitmap != null) {
                draw()
                return
            }
            reload()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            Log.d(TAG, "onVisibilityChanged visible=$visible")
            if (visible) {
                releasedByHide = false
                reload()
            } else {
                // 息屏 / 离开桌面：作废旧请求并释放常驻位图
                releasedByHide = true
                generation++
                releaseBitmap()
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            generation++
            RecipeBus.remove(onRecipeChanged)
            releaseBitmap()
            applied = null
            super.onSurfaceDestroyed(holder)
        }

        /** Engine 销毁时兜底解绑，避免全局单例长期持有 Engine 与 Service。 */
        override fun onDestroy() {
            destroyed = true
            generation++
            RecipeBus.remove(onRecipeChanged)
            mainHandler.removeCallbacksAndMessages(null)
            pendingDecode?.let { decodeHandler.removeCallbacks(it) }
            pendingDecode = null
            releaseBitmap()
            super.onDestroy()
        }

        /** 翻页视差：故意什么都不做，省电且零位移。 */
        override fun onOffsetsChanged(
            xOffset: Float,
            yOffset: Float,
            xOffsetStep: Float,
            yOffsetStep: Float,
            xPixelOffset: Int,
            yPixelOffset: Int,
        ) = Unit

        private fun releaseBitmap() {
            bitmap?.takeIf { !it.isRecycled }?.recycle()
            bitmap = null
        }

        /** 主线程只做配方读取与绘制；真正的读盘解码交给 Service 的解码线程。 */
        private fun reload() {
            if (destroyed || releasedByHide || surfaceW <= 0 || surfaceH <= 0) return
            val gen = ++generation
            val w = surfaceW
            val h = surfaceH
            val profile = if (w >= h) Profile.LANDSCAPE else Profile.PORTRAIT
            val layer = runCatching { store.recipe.of(profile).sanitized() }
                .getOrDefault(Layer())

            // 只改了锚点 / 缩放时复用已解码位图，避免每次调参都重新读盘
            val reusable = layer.imagePath != null && layer.imagePath == applied?.imagePath && bitmap != null
            if (reusable) {
                applied = layer
                draw()
                return
            }

            // 同一 Engine 只保留最新一次解码请求，队列不会随快速调参无限增长
            pendingDecode?.let { decodeHandler.removeCallbacks(it) }
            val task = Runnable {
                if (destroyed || gen != generation || releasedByHide) return@Runnable
                val decoded = if (layer.ready) {
                    ImageStore.loadWithFallback(layer.imagePath!!, maxOf(w, h))
                } else {
                    null
                }
                if (destroyed || gen != generation || releasedByHide) {
                    decoded?.takeIf { !it.isRecycled }?.recycle()
                } else {
                    mainHandler.post {
                        if (destroyed || gen != generation || releasedByHide) {
                            decoded?.takeIf { !it.isRecycled }?.recycle()
                        } else {
                            releaseBitmap()
                            bitmap = decoded
                            applied = layer
                            draw()
                        }
                    }
                }
            }
            pendingDecode = task
            decodeHandler.post(task)
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
