package com.dualwall.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.util.UUID

data class ImportedImage(val path: String, val width: Int, val height: Int)

/**
 * 图片一律复制进应用私有目录，避免 SAF / 相册授权过期后壁纸变黑。
 */
object ImageStore {

    private fun dir(context: Context): File =
        File(context.filesDir, "images").apply { mkdirs() }

    fun import(context: Context, uri: Uri): ImportedImage {
        val target = File(dir(context), "${UUID.randomUUID()}.img")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { out -> input.copyTo(out) }
        } ?: error("无法读取所选图片")

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(target.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            target.delete()
            error("不是有效的图片文件")
        }
        return ImportedImage(target.absolutePath, bounds.outWidth, bounds.outHeight)
    }

    /**
     * 按 2 的幂采样解码，使长边**最接近** [maxLongSide]（允许略低）。
     *
     * 旧条件是「解码后仍 ≥ limit 才继续降」，对 4000×3000 + limit 2560 这种
     * 「超出需求 56%」的情况完全不动，白白吃下 48MB。现在改成向最近的 2 幂靠拢：
     * 4000 → 2000（0.78×，内存 1/4），4000 配 1200 → 1000（0.83×）。
     * 下限设在 0.7× 就停，宁可多一点也不要欠采样到发虚；
     * 最终显示前的缩放由高质量 filter 补齐，视觉差异极小。
     */
    fun load(path: String, maxLongSide: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) error("解码失败：$path")

        val limit = if (maxLongSide <= 0) 2048 else maxLongSide
        var sample = 1
        // 降到下一个 2 幂仍 ≥ 0.7×limit 就降，否则停
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= (limit * 0.7).toInt()) {
            sample *= 2
        }

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(path, opts) ?: error("解码失败：$path")
    }
}
