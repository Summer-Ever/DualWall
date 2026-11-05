package com.dualwall.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.dualwall.core.Crop
import com.dualwall.data.Layer

/**
 * 构图预览 + 手势编辑。
 *
 * 关键约定：**裁切永远以真实视口为基准**，画布只负责等比显示。
 * 把画布像素尺寸当成裁切目标会出事：预览图是降采样过的，凑不出比例单元时
 * cropRect 会退化成整图 —— 表现就是画面被压扁、而且两轴都没有余量拖不动。
 *
 * 另外所有几何计算都包了 try/catch：预览画错顶多黑一下，
 * 绝不能让一个显示问题把整个界面带崩。
 */
@Composable
fun CropCanvas(
    bitmap: Bitmap?,
    layer: Layer,
    viewportW: Int,
    viewportH: Int,
    modifier: Modifier = Modifier,
    onTransform: (anchorX: Float, anchorY: Float, zoom: Float) -> Unit,
) {
    val current by rememberUpdatedState(layer)
    val emit by rememberUpdatedState(onTransform)
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    // 位图若已被回收，asImageBitmap 会抛异常 —— 失败就当没图
    val image = remember(bitmap) { bitmap?.let { runCatching { it.asImageBitmap() }.getOrNull() } }

    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF07080A))
            .onSizeChanged { canvasSize = it }
            .pointerInput(bitmap, viewportW, viewportH, canvasSize) {
                detectTransformGestures { centroid, pan, gestureZoom, _ ->
                    val bmp = bitmap ?: return@detectTransformGestures
                    val cw = canvasSize.width
                    val ch = canvasSize.height
                    if (cw <= 0 || ch <= 0 || viewportW <= 0 || viewportH <= 0) {
                        return@detectTransformGestures
                    }
                    val l = current

                    val nextZoom = (l.zoom * gestureZoom).coerceIn(Layer.MIN_ZOOM, Layer.MAX_ZOOM)
                    val (ax1, ay1) = Crop.anchorAfterZoom(
                        bmp.width, bmp.height, viewportW, viewportH,
                        l.anchorX, l.anchorY, l.zoom, nextZoom,
                        centroid.x / cw, centroid.y / ch,
                    )
                    // 画布像素 → 视口像素，两个轴分别换算
                    val (ax2, ay2) = Crop.anchorAfterDrag(
                        bmp.width, bmp.height, viewportW, viewportH,
                        ax1, ay1, nextZoom,
                        pan.x * viewportW / cw,
                        pan.y * viewportH / ch,
                    )
                    emit(ax2, ay2, nextZoom)
                }
            },
    ) {
        if (image != null && canvasSize.width > 0 && canvasSize.height > 0) {
            Canvas(Modifier.fillMaxSize()) {
                runCatching { drawPreview(image, layer, viewportW, viewportH, canvasSize) }
            }
        } else {
            Text(
                text = "点「选图」导入当前方向的壁纸",
                color = Color.White.copy(alpha = 0.55f),
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

private fun DrawScope.drawPreview(
    image: ImageBitmap,
    layer: Layer,
    viewportW: Int,
    viewportH: Int,
    canvasSize: IntSize,
) {
    if (image.width <= 0 || image.height <= 0 || viewportW <= 0 || viewportH <= 0) return

    val r = Crop.cropRect(
        image.width, image.height, viewportW, viewportH,
        layer.anchorX, layer.anchorY, layer.zoom,
    )
    if (r.width <= 0 || r.height <= 0) return
    val d = Crop.coverRect(r.width, r.height, canvasSize.width, canvasSize.height)

    drawImage(
        image = image,
        srcOffset = IntOffset(r.left, r.top),
        srcSize = IntSize(r.width, r.height),
        dstOffset = IntOffset(d.left, d.top),
        dstSize = IntSize(d.width, d.height),
        filterQuality = FilterQuality.High,
    )

    val grid = Color.White.copy(alpha = 0.13f)
    for (i in 1..2) {
        val x = canvasSize.width * i / 3f
        val y = canvasSize.height * i / 3f
        drawLine(grid, Offset(x, 0f), Offset(x, canvasSize.height.toFloat()), 1f)
        drawLine(grid, Offset(0f, y), Offset(canvasSize.width.toFloat(), y), 1f)
    }
}
