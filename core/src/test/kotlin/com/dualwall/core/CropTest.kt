package com.dualwall.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.min

class CropTest {

    /** 平板常见视口 */
    private val viewports = listOf(
        2560 to 1600,   // 小新 Pad Pro 12.7 横屏
        1600 to 2560,   // 竖屏
        3072 to 1920,   // YOGA Pad Pro 14.5 横屏
        1920 to 3072,
        2800 to 1840,   // 3:2 类
        2000 to 1200,
        1 to 1,
        1000 to 1,      // 极端宽高比，验证不崩
    )

    private val sources = listOf(
        4000 to 3000,
        3840 to 2160,
        1080 to 1920,
        4032 to 3024,
        6000 to 3000,
        1920 to 1080,
    )

    /** 源图得先能放下一个比例单元，严格比例才有整数解 */
    private fun hasExactSolution(sw: Int, sh: Int, dw: Int, dh: Int): Boolean {
        val g = Crop.gcd(dw, dh)
        return min(sw / (dw / g), sh / (dh / g)) >= 1
    }

    @Test
    fun `裁切框比例与视口严格一致`() {
        var checked = 0
        for ((dw, dh) in viewports) for ((sw, sh) in sources) {
            if (!hasExactSolution(sw, sh, dw, dh)) continue
            val r = Crop.cropRect(sw, sh, dw, dh, 0.5f, 0.5f)
            assertEquals(
                "比例不一致 src=${sw}x$sh dst=${dw}x$dh -> $r",
                r.width.toLong() * dh.toLong(),
                r.height.toLong() * dw.toLong(),
            )
            checked++
        }
        assertTrue("没有覆盖到样本", checked > 30)
    }

    @Test
    fun `裁切框永远落在源图内`() {
        val anchors = listOf(0f, 0.13f, 0.5f, 0.87f, 1f)
        val zooms = listOf(1f, 1.3f, 2f, 4.7f, 9f, 40f)
        for ((dw, dh) in viewports) for ((sw, sh) in sources) {
            for (ax in anchors) for (ay in anchors) for (z in zooms) {
                val r = Crop.cropRect(sw, sh, dw, dh, ax, ay, z)
                assertTrue("越界 $r src=${sw}x$sh dst=${dw}x$dh", r.left >= 0 && r.top >= 0)
                assertTrue("越界 $r src=${sw}x$sh dst=${dw}x$dh", r.right <= sw && r.bottom <= sh)
                assertTrue("空框 $r", r.width > 0 && r.height > 0)
            }
        }
    }

    @Test
    fun `源图小于一个比例单元时优雅退化为整图`() {
        // 500x5000 的图去填 1000x1 的视口：整数像素下无严格比例解
        val r = Crop.cropRect(500, 5000, 1000, 1, 0.5f, 0.5f)
        assertEquals(IntRect(0, 0, 500, 5000), r)
    }

    @Test
    fun `相同输入永远得到相同输出`() {
        val first = Crop.cropRect(4000, 3000, 2560, 1600, 0.37f, 0.62f, 1.4f)
        repeat(50) {
            assertEquals(first, Crop.cropRect(4000, 3000, 2560, 1600, 0.37f, 0.62f, 1.4f))
        }
    }

    @Test
    fun `锚点贴边行为正确`() {
        val sw = 4000
        val sh = 3000
        val dw = 1600
        val dh = 1000
        assertEquals(0, Crop.cropRect(sw, sh, dw, dh, 0f, 0.5f).left)
        assertEquals(sw, Crop.cropRect(sw, sh, dw, dh, 1f, 0.5f).right)
        assertEquals(0, Crop.cropRect(sw, sh, dw, dh, 0.5f, 0f).top)
        assertEquals(sh, Crop.cropRect(sw, sh, dw, dh, 0.5f, 1f).bottom)
        val mid = Crop.cropRect(sw, sh, dw, dh, 0.5f, 0.5f)
        assertTrue(abs((mid.left + mid.right) / 2 - sw / 2) <= 1)
    }

    @Test
    fun `zoom 越大裁切框越小且不越界`() {
        var last = Int.MAX_VALUE
        for (z in listOf(1f, 1.2f, 1.5f, 2f, 3f, 6f, 12f)) {
            val r = Crop.cropRect(4000, 3000, 1600, 1000, 0.5f, 0.5f, z)
            assertTrue("zoom=$z 未收紧", r.width <= last)
            last = r.width
        }
        val r = Crop.cropRect(4000, 3000, 1600, 1000, 0.5f, 0.5f, 999f)
        assertEquals(r.width.toLong() * 1000, r.height.toLong() * 1600)
    }

    @Test
    fun `比例相同时落位为恒等缩放`() {
        // 源图 3200x2000，视口 2560x1600，同为 8:5
        val p = Crop.placement(3200, 2000, 2560, 1600, 0.5f, 0.5f)
        assertEquals(0.8f, p.scale, 1e-6f)
        assertEquals(0f, p.dx, 1e-3f)
        assertEquals(0f, p.dy, 1e-3f)
    }

    @Test
    fun `落位后不会露出画布边缘`() {
        val anchors = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        for ((dw, dh) in viewports) for ((sw, sh) in sources) {
            for (ax in anchors) for (ay in anchors) {
                val p = Crop.placement(sw, sh, dw, dh, ax, ay)
                assertTrue("左侧留白", p.dx <= 1e-3f)
                assertTrue("上侧留白", p.dy <= 1e-3f)
                assertTrue("右侧留白", p.dx + sw * p.scale >= dw - 1e-3f)
                assertTrue("下侧留白", p.dy + sh * p.scale >= dh - 1e-3f)
            }
        }
    }

    @Test
    fun `拖动方向与边界钳制`() {
        // 源 6000x3000 填 1600x1000：横向有余量（1200px），纵向已顶满
        val sw = 6000
        val sh = 3000
        val dw = 1600
        val dh = 1000

        val (ax, ay) = Crop.anchorAfterDrag(sw, sh, dw, dh, 0.5f, 0.5f, 1f, 120f, 0f)
        assertTrue("向右拖动应减小 anchorX，实际 $ax", ax < 0.5f)
        assertEquals("纵向无余量，锚点不应变化", 0.5f, ay, 1e-4f)

        val (a0, _) = Crop.anchorAfterDrag(sw, sh, dw, dh, 0.5f, 0.5f, 1f, 99999f, 0f)
        assertEquals(0f, a0, 1e-4f)
        val (a1, _) = Crop.anchorAfterDrag(sw, sh, dw, dh, 0.5f, 0.5f, 1f, -99999f, 0f)
        assertEquals(1f, a1, 1e-4f)
    }

    @Test
    fun `两轴都顶满时拖动不改变构图`() {
        // 4000x3000 填 1600x1000：横向正好顶满，只有纵向可动
        val (ax, ay) = Crop.anchorAfterDrag(4000, 3000, 1600, 1000, 0.5f, 0.5f, 1f, 300f, 0f)
        assertEquals(0.5f, ax, 1e-4f)
        assertEquals(0.5f, ay, 1e-4f)
    }

    @Test
    fun `缩放时焦点像素不动`() {
        val sw = 6000
        val sh = 3000
        val dw = 1600
        val dh = 1000
        val fx = 0.31f
        val fy = 0.72f
        val r0 = Crop.cropRect(sw, sh, dw, dh, 0.5f, 0.5f, 1f)
        val (ax, ay) = Crop.anchorAfterZoom(sw, sh, dw, dh, 0.5f, 0.5f, 1f, 2f, fx, fy)
        val r1 = Crop.cropRect(sw, sh, dw, dh, ax, ay, 2f)

        val px0 = r0.left + fx * r0.width
        val px1 = r1.left + fx * r1.width
        val py0 = r0.top + fy * r0.height
        val py1 = r1.top + fy * r1.height
        assertTrue("X 焦点漂移 ${abs(px0 - px1)}px", abs(px0 - px1) <= 1f)
        assertTrue("Y 焦点漂移 ${abs(py0 - py1)}px", abs(py0 - py1) <= 1f)
    }

    @Test
    fun `输出缩放为均匀缩放`() {
        // 裁切框缩放到视口时，两轴比例必须相等，否则画面会被拉伸
        for ((dw, dh) in viewports) for ((sw, sh) in sources) {
            if (!hasExactSolution(sw, sh, dw, dh)) continue
            val r = Crop.cropRect(sw, sh, dw, dh, 0.5f, 0.5f)
            assertEquals("横纵缩放不一致", dw.toDouble() / r.width, dh.toDouble() / r.height, 1e-9)
        }
    }

    @Test
    fun `比例一致的矩形 cover 到整块画布`() {
        // 1875x3000（5:8）落到 1600x2560（5:8）应严格铺满
        assertEquals(IntRect(0, 0, 1600, 2560), Crop.coverRect(1875, 3000, 1600, 2560))
    }

    @Test
    fun `比例不一致时居中溢出而不是拉伸`() {
        // 退化场景：1000x750 的图塞进 1201x1925 的画布
        val d = Crop.coverRect(1000, 750, 1201, 1925)
        val s = 1925.0 / 750
        assertTrue("宽度未按统一比例", abs(d.width - 1000 * s) <= 1.0)
        assertTrue("高度未按统一比例", abs(d.height - 750 * s) <= 1.0)
        assertTrue("未铺满画布", d.width >= 1201 && d.height >= 1925)
        assertTrue("未居中", abs((d.left + d.right) / 2 - 1201 / 2) <= 1)
    }

    @Test
    fun `cover 在任何组合下都不小于画布`() {
        for ((dw, dh) in viewports) for ((sw, sh) in sources) {
            val r = Crop.cropRect(sw, sh, dw, dh, 0.5f, 0.5f)
            val d = Crop.coverRect(r.width, r.height, dw, dh)
            assertTrue("$d 覆盖不足 ${dw}x$dh", d.width >= dw && d.height >= dh)
        }
    }
}
