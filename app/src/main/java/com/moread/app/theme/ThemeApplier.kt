package com.moread.app.theme

import android.animation.ValueAnimator
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.ColorUtils

/**
 * 主题切换动画与静态换色工具（SPEC §1.6）。
 * 200ms 颜色插值，不 recreate、不闪白；RecyclerView 内容由 adapter payload 独立刷新。
 */
object ThemeApplier {

    fun applyWindow(activity: android.app.Activity, palette: ColorPalette) {
        activity.window.statusBarColor = palette.background
        activity.window.navigationBarColor = palette.background
        activity.window.decorView.setBackgroundColor(palette.background)
    }

    /**
     * 遍历视图树，将颜色与 [oldPalette] 匹配的 TextView 文本色 / ColorDrawable 背景色
     * 在 200ms 内插值到 [newPalette] 对应色值。
     */
    private enum class TargetKind { BACKGROUND, TEXT }

    private data class Target(
        val view: View,
        val kind: TargetKind,
        val from: Int,
        val to: Int,
    )

    fun animate(root: View, oldPalette: ColorPalette, newPalette: ColorPalette, onEnd: () -> Unit = {}) {
        val oldMap = paletteColors(oldPalette)
        val newMap = paletteColors(newPalette)
        val targets = ArrayList<Target>()
        collectTargets(root, oldMap, newMap, targets)
        if (targets.isEmpty()) {
            root.setBackgroundColor(newPalette.background)
            onEnd()
            return
        }
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = 200
        animator.addUpdateListener { va ->
            val fraction = va.animatedFraction
            targets.forEach { target ->
                val value = blend(target.from, target.to, fraction)
                when (target.kind) {
                    TargetKind.BACKGROUND -> target.view.setBackgroundColor(value)
                    TargetKind.TEXT -> (target.view as TextView).setTextColor(value)
                }
            }
        }
        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                targets.forEach { target ->
                    when (target.kind) {
                        TargetKind.BACKGROUND -> target.view.setBackgroundColor(target.to)
                        TargetKind.TEXT -> (target.view as TextView).setTextColor(target.to)
                    }
                }
                onEnd()
            }
        })
        animator.start()
    }

    private fun collectTargets(
        view: View,
        oldMap: Map<Int, Int>,
        newMap: Map<Int, Int>,
        out: MutableList<Target>,
    ) {
        val bg = view.background
        if (bg is ColorDrawable && oldMap.containsKey(bg.color)) {
            out.add(Target(view, TargetKind.BACKGROUND, bg.color, newMap.getValue(bg.color)))
        }
        if (view is TextView && oldMap.containsKey(view.currentTextColor)) {
            out.add(Target(view, TargetKind.TEXT, view.currentTextColor, newMap.getValue(view.currentTextColor)))
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                collectTargets(view.getChildAt(i), oldMap, newMap, out)
            }
        }
    }

    private fun paletteColors(palette: ColorPalette): Map<Int, Int> = mapOf(
        palette.background to palette.background,
        palette.card to palette.card,
        palette.textPrimary to palette.textPrimary,
        palette.textSecondary to palette.textSecondary,
        palette.accent to palette.accent,
        palette.divider to palette.divider,
        palette.codeText to palette.codeText,
    )

    private fun blend(from: Int, to: Int, fraction: Float): Int {
        val a = ColorUtils.blendARGB(from, to, fraction)
        return a
    }
}
