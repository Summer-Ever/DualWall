package com.dualwall.apply

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import com.dualwall.data.ImageStore
import com.dualwall.data.Layer
import com.dualwall.render.Cropper
import com.dualwall.render.Viewport
import com.dualwall.wallpaper.DualWallpaperService
import java.io.File

object WallpaperApplier {

    /**
     * 拉起系统的动态壁纸设置页，按「最精确 → 最通用」逐级降级。
     *
     * 这里逐个 try 的原因：国内 ROM（含 ZUI 部分版本）把 AOSP 的壁纸选择器换成了自家实现，
     * ACTION_CHANGE_LIVE_WALLPAPER / ACTION_LIVE_WALLPAPER_CHOOSER 可能一个都解析不到，
     * 直接 startActivity 会抛 ActivityNotFoundException —— 表现就是「点一下应用就闪退」。
     *
     * @return 空串表示已成功拉起；非空串是给用户看的失败说明。
     */
    fun openLiveWallpaper(context: Context): String {
        val pm = context.packageManager
        val component = ComponentName(context, DualWallpaperService::class.java)

        val candidates = listOf(
            Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
            },
            Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER),
            Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER),
        )

        for (intent in candidates) {
            val resolvable = runCatching { intent.resolveActivity(pm) }.getOrNull() != null
            if (!resolvable) continue
            val started = runCatching {
                context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (started) return ""
        }

        return "这台设备没有可用的动态壁纸选择器，请到「设置 → 显示 → 壁纸」里选择「DualWall 双面壁纸」"
    }

    /**
     * 单独设置锁屏静态壁纸（动态壁纸只负责桌面，Engine 无法区分 home / lock）。
     * 顺带把输出导出到 cacheDir，方便做像素级验收。
     */
    fun applyLock(context: Context, layer: Layer, landscape: Boolean): String {
        if (!layer.ready) return "请先为当前方向选择图片"

        val viewport = Viewport.size(context, landscape)
        val source: Bitmap = ImageStore.load(layer.imagePath!!, maxLongSide = 4096)
        val output = try {
            Cropper.render(source, layer, viewport.width, viewport.height)
        } finally {
            if (!source.isRecycled) source.recycle()
        }

        val wm = WallpaperManager.getInstance(context)
        if (!wm.isWallpaperSupported) return "当前用户不支持壁纸"
        if (!wm.isSetWallpaperAllowed) return "系统策略禁止第三方应用设置壁纸"

        val id = wm.setBitmap(
            output,
            Rect(0, 0, output.width, output.height),
            false,
            WallpaperManager.FLAG_LOCK,
        )

        val dump = File(context.cacheDir, "lock_${output.width}x${output.height}.png")
        runCatching {
            dump.outputStream().use { output.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }

        val readBack = runCatching {
            wm.getWallpaperFile(WallpaperManager.FLAG_LOCK)?.use { pfd ->
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, opts)
                "${opts.outWidth}×${opts.outHeight}"
            }
        }.getOrNull()

        if (id == 0) return "系统拒绝了锁屏壁纸写入（ZUI 可能不允许独立锁屏壁纸）"

        val verdict = when {
            readBack == null -> "回读不可用，请直接看锁屏"
            readBack == "${output.width}×${output.height}" -> "尺寸校验通过"
            else -> "尺寸被系统改写：期望 ${output.width}×${output.height}，实际 $readBack（会产生位移）"
        }
        return "锁屏已设置 · $verdict · 校验图 ${dump.absolutePath}"
    }
}
