package com.dualwall.data

/** 屏幕方向维度的壁纸配方索引。 */
enum class Profile { PORTRAIT, LANDSCAPE }

/**
 * 单张壁纸的「配方」。只存原图路径 + 归一化参数，不存烘焙好的位图。
 * apply 时按当时的真实视口重算，换设备 / 改分辨率 / 进出 PC 模式都不会错位。
 */
data class Layer(
    val imagePath: String? = null,
    val srcW: Int = 0,
    val srcH: Int = 0,
    val anchorX: Float = 0.5f,
    val anchorY: Float = 0.5f,
    val zoom: Float = 1f,
) {
    val ready: Boolean get() = !imagePath.isNullOrEmpty() && srcW > 0 && srcH > 0

    /** 把外部来源（旧版本存档、手改的 JSON）里的非法值收进合法区间。 */
    fun sanitized(): Layer = copy(
        srcW = srcW.coerceAtLeast(0),
        srcH = srcH.coerceAtLeast(0),
        anchorX = if (anchorX.isNaN()) 0.5f else anchorX.coerceIn(0f, 1f),
        anchorY = if (anchorY.isNaN()) 0.5f else anchorY.coerceIn(0f, 1f),
        zoom = if (zoom.isNaN()) 1f else zoom.coerceIn(MIN_ZOOM, MAX_ZOOM),
    )

    companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 4f
    }
}

data class Recipe(
    val portrait: Layer = Layer(),
    val landscape: Layer = Layer(),
    val lockIndependent: Boolean = false,
) {
    fun of(profile: Profile): Layer = if (profile == Profile.LANDSCAPE) landscape else portrait

    fun with(profile: Profile, layer: Layer): Recipe =
        if (profile == Profile.LANDSCAPE) copy(landscape = layer) else copy(portrait = layer)
}

/** 配方变更广播（同进程内）。动态壁纸引擎靠它热更新，无需重启壁纸。 */
object RecipeBus {
    private val listeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()

    fun add(listener: () -> Unit) = listeners.add(listener)

    fun remove(listener: () -> Unit) = listeners.remove(listener)

    fun notifyChanged() = listeners.forEach { runCatching { it() } }
}
