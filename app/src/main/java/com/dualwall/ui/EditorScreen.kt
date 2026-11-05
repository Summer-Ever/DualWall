package com.dualwall.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dualwall.CrashLog
import com.dualwall.apply.WallpaperApplier
import com.dualwall.core.Crop
import com.dualwall.data.ImageStore
import com.dualwall.data.Layer
import com.dualwall.data.Profile
import com.dualwall.data.RecipeStore
import com.dualwall.render.Viewport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun EditorScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { RecipeStore(context) }

    var recipe by remember { mutableStateOf(store.recipe) }
    var profile by rememberSaveable { mutableStateOf(Profile.LANDSCAPE) }
    var status by remember { mutableStateOf("拖动调整构图，双指缩放") }
    var busy by remember { mutableStateOf(false) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var crash by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { crash = CrashLog.read(context) }

    val layer = recipe.of(profile)
    // 旋转、PC 模式、分屏都会改 Configuration —— 必须跟着重算，否则视口会停在旧方向
    val configuration = LocalConfiguration.current
    val viewport = remember(profile, configuration) {
        Viewport.size(context, profile == Profile.LANDSCAPE)
    }

    LaunchedEffect(layer.imagePath) {
        bitmap = withContext(Dispatchers.IO) {
            layer.imagePath?.let { path ->
                runCatching { ImageStore.load(path, maxLongSide = 2400) }.getOrNull()
            }
        }
    }

    fun commit(next: Layer) {
        recipe = recipe.with(profile, next)
    }

    // 拖拽时每帧都会 commit；落盘做 250ms 防抖，否则动态壁纸会被反复要求重载位图
    LaunchedEffect(Unit) {
        snapshotFlow { recipe }.collectLatest { latest ->
            delay(250)
            store.recipe = latest
        }
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) { runCatching { ImageStore.import(context, uri) } }
            busy = false
            result.onSuccess { img ->
                commit(
                    layer.copy(
                        imagePath = img.path,
                        srcW = img.width,
                        srcH = img.height,
                        anchorX = 0.5f,
                        anchorY = 0.5f,
                        zoom = 1f,
                    ),
                )
                status = "已导入 ${img.width} × ${img.height}"
            }.onFailure { status = "导入失败：${it.message}" }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        crash?.let { text ->
            CrashBanner(text) {
                CrashLog.clear(context)
                crash = null
            }
            Spacer(Modifier.height(8.dp))
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeButton("横屏", profile == Profile.LANDSCAPE, Modifier.weight(1f)) {
                profile = Profile.LANDSCAPE
            }
            ModeButton("竖屏", profile == Profile.PORTRAIT, Modifier.weight(1f)) {
                profile = Profile.PORTRAIT
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = "视口 ${viewport.width} × ${viewport.height} · 已就绪 ${layer.ready}",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))
        BoxWithConstraints(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            val ratio = viewport.width.toFloat() / viewport.height.toFloat()
            val width = minOf(maxWidth, maxHeight * ratio)
            val height = width / ratio
            CropCanvas(
                bitmap = bitmap,
                layer = layer,
                viewportW = viewport.width,
                viewportH = viewport.height,
                modifier = Modifier.size(width, height),
            ) { ax, ay, z ->
                commit(layer.copy(anchorX = ax, anchorY = ay, zoom = z))
            }
        }

        Spacer(Modifier.height(2.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("缩放", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = layer.zoom,
                onValueChange = { z ->
                    if (layer.ready && layer.srcW > 0 && layer.srcH > 0) {
                        // 拖动条以画面中心为焦点缩放，避免画面乱跑
                        val (ax, ay) = Crop.anchorAfterZoom(
                            layer.srcW, layer.srcH, viewport.width, viewport.height,
                            layer.anchorX, layer.anchorY, layer.zoom, z, 0.5f, 0.5f,
                        )
                        commit(layer.copy(anchorX = ax, anchorY = ay, zoom = z))
                    }
                },
                valueRange = Layer.MIN_ZOOM..Layer.MAX_ZOOM,
                enabled = layer.ready,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
            )
            Text(
                text = String.format("%.2f×", layer.zoom),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = status,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    runCatching { pickImage.launch("image/*") }
                        .onFailure { status = "无法打开图片选择器：${it.message}" }
                },
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) { Text("选图") }

            OutlinedButton(
                onClick = {
                    if (layer.ready) {
                        commit(layer.copy(anchorX = 0.5f, anchorY = 0.5f, zoom = 1f))
                        status = "已居中"
                    } else {
                        status = "请先为当前方向选择图片"
                    }
                },
                modifier = Modifier.weight(1f),
            ) { Text("居中") }
        }

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                if (!layer.ready) {
                    status = "请先为当前方向选择图片"
                } else {
                    // 逐个候选 action 尝试，全部不可用时给出可操作的提示，绝不抛异常出去
                    status = runCatching { WallpaperApplier.openLiveWallpaper(context) }
                        .getOrElse { "打开壁纸选择器失败：${it.message}" }
                        .ifEmpty { "已打开系统壁纸设置，确认后回到桌面查看效果" }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("应用到桌面（动态壁纸）") }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                if (!layer.ready) {
                    status = "请先为当前方向选择图片"
                } else {
                    scope.launch {
                        busy = true
                        val msg = withContext(Dispatchers.IO) {
                            runCatching {
                                WallpaperApplier.applyLock(
                                    context, layer, profile == Profile.LANDSCAPE,
                                )
                            }.getOrElse { "锁屏设置失败：${it.message}" }
                        }
                        busy = false
                        status = msg
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("单独设置锁屏（静态）") }

        Spacer(Modifier.height(6.dp))
        Text(
            text = "动态壁纸负责桌面，锁屏走静态通道 —— Engine 无法区分 home / lock。",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ModeButton(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) { Text(text) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(text) }
    }
}

/** 上次进程崩溃的现场：默认折叠 5 行，可展开、可复制、可清除。 */
@Composable
private fun CrashBanner(text: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF4A1418))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            text = "上次运行崩溃了 · 复制堆栈发我就能定位",
            color = Color(0xFFFFD8DA),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = text,
            color = Color(0xFFFFECEC),
            fontSize = 10.sp,
            maxLines = if (expanded) Int.MAX_VALUE else 5,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp)
                .verticalScroll(rememberScrollState()),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "收起" else "展开", fontSize = 12.sp)
            }
            TextButton(
                onClick = {
                    runCatching {
                        context.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("DualWall crash", text))
                    }
                },
            ) { Text("复制", fontSize = 12.sp) }
            TextButton(onClick = onDismiss) { Text("忽略", fontSize = 12.sp) }
        }
    }
}
