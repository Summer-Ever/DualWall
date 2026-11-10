package com.dualwall.apply

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
     *
     * 本函数创建的 source / output 一律在 finally 中回收，
     * 能力校验、解码失败、系统拒绝、写入异常等所有路径都不留位图。
     * 解码尺寸按真实视口推导并带降档重试，不再固定全尺寸解码。
     *
     * [exportDump] 默认关闭；需要像素级验收时显式打开才会写校验图。
     */
    fun applyLock(
        context: Context,
        layer: Layer,
        landscape: Boolean,
        exportDump: Boolean = false,
    ): String {
        if (!layer.ready) return "请先为当前方向选择图片"

        // 先做系统能力校验，避免白解码一张大图
        val wm = WallpaperManager.getInstance(context)
        if (!wm.isWallpaperSupported) return "当前用户不支持壁纸"
        if (!wm.isSetWallpaperAllowed) return "系统策略禁止第三方应用设置壁纸"

        val viewport = Viewport.size(context, landscape)
        val limit = maxOf(viewport.width, viewport.height)

        var source: Bitmap? = null
        var output: Bitmap? = null
        try {
            val src = ImageStore.loadWithFallback(layer.imagePath!!, limit)
                ?: return "图片解码失败，请换一张尺寸更小的图片重试"
            source = src
            val out = Cropper.render(src, layer, viewport.width, viewport.height)
            output = out
            // 宽高在回收前取好，后续校验不再读取位图对象
            val outW = out.width
            val outH = out.height

            val dump = if (exportDump) File(context.cacheDir, "lock_${outW}x${outH}.png") else null
            dump?.let { file ->
                runCatching {
                    file.outputStream().use { os -> out.compress(Bitmap.CompressFormat.PNG, 100, os) }
                }
            }

            val id = wm.setBitmap(out, Rect(0, 0, outW, outH), false, WallpaperManager.FLAG_LOCK)

            if (id == 0) return "系统拒绝了锁屏壁纸写入（ZUI 可能不允许独立锁屏壁纸）"

            // 输出尺寸严格等于视口，系统不改写就不会产生位移。
            // 这里不再回读系统壁纸文件：该接口要求系统级读权限，第三方应用调用必然被拒，
            // 旧实现只会得到「回读不可用」，却额外付出一次跨进程 IO。
            val verdict = "输出 ${outW}×${outH}，等于当前视口"
            return if (dump != null) {
                "锁屏已设置 · $verdict · 校验图 ${dump.absolutePath}"
            } else {
                "锁屏已设置 · $verdict"
            }
        } finally {
            output?.takeIf { !it.isRecycled }?.recycle()
            source?.takeIf { !it.isRecycled }?.recycle()
        }
    }
}
