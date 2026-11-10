package com.dualwall.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.dualwall.core.DecodeSample
import java.io.File
import java.util.UUID

data class ImportedImage(val path: String, val width: Int, val height: Int)

/**
 * 图片一律复制进应用私有目录，避免 SAF / 相册授权过期后壁纸变黑。
 * 目录内只保留当前配方仍在引用的文件，换图与启动时都会清理历史副本。
 */
object ImageStore {

    private const val SUBDIR = "images"
    private const val SUFFIX = ".img"

    private fun dir(context: Context): File =
        File(context.applicationContext.filesDir, SUBDIR).apply { mkdirs() }

    /** 只允许操作本目录内由 import 生成的文件，用 canonical path 挡住路径穿越。 */
    private fun isOwnedFile(dir: File, file: File): Boolean = runCatching {
        val root = dir.canonicalPath
        val target = file.canonicalPath
        target.startsWith(root + File.separator) && file.name.endsWith(SUFFIX)
    }.getOrDefault(false)

    fun import(context: Context, uri: Uri): ImportedImage {
        val target = File(dir(context), "${UUID.randomUUID()}$SUFFIX")
        try {
            val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { out -> input.copyTo(out) }
                true
            } ?: false
            if (!copied) error("无法读取所选图片")

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(target.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("不是有效的图片文件")
            return ImportedImage(target.absolutePath, bounds.outWidth, bounds.outHeight)
        } catch (t: Throwable) {
            runCatching { target.delete() }
            throw t
        }
    }

    /**
     * 按目标长边采样解码。采样系数由 core 的纯 JVM 函数计算，
     * 同时受画质下限与像素预算约束，避免大图 / 长条图占用过量 native 内存。
     */
    fun load(path: String, maxLongSide: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("解码失败：$path")

        val opts = BitmapFactory.Options().apply {
            inSampleSize = DecodeSample.inSampleSize(bounds.outWidth, bounds.outHeight, maxLongSide)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(path, opts) ?: error("解码失败：$path")
    }

    /** 解码失败或内存不足时降一档再试，宁可糊一点也不要黑屏或把异常抛给用户。 */
    fun loadWithFallback(path: String, maxLongSide: Int): Bitmap? {
        val first = runCatching { load(path, maxLongSide) }.getOrNull()
        if (first != null) return first
        return runCatching { load(path, maxOf(1, maxLongSide / 2)) }.getOrNull()
    }

    /** 换图后删掉不再被配方引用的旧副本。 */
    fun deleteUnused(context: Context, path: String?) {
        if (path.isNullOrEmpty()) return
        val dir = dir(context)
        val file = File(path)
        if (isOwnedFile(dir, file)) runCatching { file.delete() }
    }

    /** 清理历史遗留副本，只保留当前配方仍在用的路径；扫描范围仅限私有 images 目录。 */
    fun prune(context: Context, keepPaths: Set<String>) {
        val dir = dir(context)
        val keep = keepPaths.mapNotNull { p ->
            runCatching { File(p).canonicalPath }.getOrNull()
        }.toSet()
        dir.listFiles()?.forEach { file ->
            if (!file.isFile || !isOwnedFile(dir, file)) return@forEach
            val canonical = runCatching { file.canonicalPath }.getOrNull() ?: return@forEach
            if (canonical !in keep) runCatching { file.delete() }
        }
    }
}
