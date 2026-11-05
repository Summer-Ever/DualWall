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
     * 按 2 的幂采样解码，保持在 [maxLongSide] 以内。
     * 幂次采样对宽高做同一整数除法，比例基本不变；即便差 1px，
     * 后续裁切用的是解码后的真实尺寸，仍然自洽。
     */
    fun load(path: String, maxLongSide: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) error("解码失败：$path")

        val limit = if (maxLongSide <= 0) 2048 else maxLongSide
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= limit) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(path, opts) ?: error("解码失败：$path")
    }
}
