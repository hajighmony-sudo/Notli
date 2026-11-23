/*
 * Copyright (c) 2026 Notli Project
 * Licensed under the GNU General Public License v3.0 or later.
 */
package org.oxycblt.auxio.ui

import android.content.Context
import android.content.SharedPreferences
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import android.widget.TextView
import androidx.preference.PreferenceManager
import com.google.android.material.R as MR
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.oxycblt.auxio.R

/**
 * Applies the persisted NOTLI AURORA design tokens to every native screen.
 * Fragments are handled by MainActivity's recursive FragmentLifecycleCallbacks, so newly
 * navigated destinations receive the same design tokens as the initial screen.
 */
object AuroraAppearance {
    private fun densityScale(settings: UISettings) =
        when (settings.auroraDensity) {
            0 -> 0.90f
            2 -> 1.12f
            else -> 1f
        }

    fun apply(root: View, settings: UISettings) {
        val primary = settings.auroraColor(UISettings.AURORA_PRIMARY)
            ?: MaterialColors.getColor(root, MR.attr.colorPrimary)
        val secondary = settings.auroraColor(UISettings.AURORA_SECONDARY)
            ?: MaterialColors.getColor(root, MR.attr.colorSecondary)
        val surface = settings.auroraColor(UISettings.AURORA_SURFACE)
        val background = settings.auroraColor(UISettings.AURORA_BACKGROUND)

        background?.let(root::setBackgroundColor)
        applyNode(root, settings, primary, secondary, surface, densityScale(settings), isRoot = true)
    }

    private fun applyNode(
        view: View,
        settings: UISettings,
        primary: Int,
        secondary: Int,
        surface: Int?,
        density: Float,
        isRoot: Boolean = false,
    ) {
        if (view is TextView) {
            val original = (view.getTag(R.id.aurora_base_text_size) as? Float) ?: view.textSize.also {
                view.setTag(R.id.aurora_base_text_size, it)
            }
            view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, original * settings.auroraFontScale)
        }

        if (view is MaterialButton) {
            view.backgroundTintList = android.content.res.ColorStateList.valueOf(primary)
            view.setTextColor(MaterialColors.getColor(view, MR.attr.colorOnPrimary))
        }
        if (view is FloatingActionButton) {
            view.backgroundTintList = android.content.res.ColorStateList.valueOf(primary)
            view.imageTintList = android.content.res.ColorStateList.valueOf(
                MaterialColors.getColor(view, MR.attr.colorOnPrimary)
            )
        } else if (view is ImageButton) {
            view.imageTintList = android.content.res.ColorStateList.valueOf(primary)
        }
        if (view is MaterialCardView) {
            surface?.let(view::setCardBackgroundColor)
            view.strokeColor = secondary
            if (view.strokeWidth == 0) view.strokeWidth = (view.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        }

        // Keep the activity/fragment root padding intact: it may contain system-bar insets.
        if (!isRoot) {
            val basePadding = (view.getTag(R.id.aurora_base_padding) as? IntArray)
                ?: intArrayOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom).also {
                    view.setTag(R.id.aurora_base_padding, it)
                }
            view.setPadding(
                (basePadding[0] * density).toInt(),
                (basePadding[1] * density).toInt(),
                (basePadding[2] * density).toInt(),
                (basePadding[3] * density).toInt(),
            )
            val params = view.layoutParams
            if (params is ViewGroup.MarginLayoutParams) {
                val baseMargins = (view.getTag(R.id.aurora_base_margins) as? IntArray)
                    ?: intArrayOf(params.leftMargin, params.topMargin, params.rightMargin, params.bottomMargin)
                        .also { view.setTag(R.id.aurora_base_margins, it) }
                params.setMargins(
                    (baseMargins[0] * density).toInt(),
                    (baseMargins[1] * density).toInt(),
                    (baseMargins[2] * density).toInt(),
                    (baseMargins[3] * density).toInt(),
                )
                view.layoutParams = params
            }
        }

        if (view is RecyclerView && view.getTag(R.id.aurora_recycler_hook) != true) {
            view.setTag(R.id.aurora_recycler_hook, true)
            view.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(child: View) {
                    applyNode(child, settings, primary, secondary, surface, density)
                }
                override fun onChildViewDetachedFromWindow(child: View) = Unit
            })
        }

        if (view is ImageView && (view is ImageButton || view.contentDescription != null)) {
            val lp = view.layoutParams
            if (lp != null && lp.width > 0 && lp.height > 0) {
                val base = (view.getTag(R.id.aurora_base_icon_size) as? IntArray)
                    ?: intArrayOf(lp.width, lp.height).also { view.setTag(R.id.aurora_base_icon_size, it) }
                val width = (base[0] * settings.auroraIconScale).toInt()
                val height = (base[1] * settings.auroraIconScale).toInt()
                if (lp.width != width || lp.height != height) {
                    lp.width = width
                    lp.height = height
                    view.layoutParams = lp
                }
            }
        }

        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                applyNode(view.getChildAt(index), settings, primary, secondary, surface, density)
            }
        }
    }
}

/** Motion preferences shared by view transitions and spring-based interaction helpers. */
object AuroraMotion {
    private fun preferences(context: Context): SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    fun duration(context: Context, baseMillis: Long): Long {
        val prefs = preferences(context)
        val mode = (prefs.getString(UISettings.AURORA_MOTION, "2")?.toIntOrNull() ?: 2).coerceIn(0, 2)
        if (mode == 0) return 0L
        val speed = prefs.getInt(UISettings.AURORA_MOTION_SPEED, 100).coerceIn(50, 150) / 100f
        val style = if (mode == 1) 0.65f else 1f
        return (baseMillis * style / speed).toLong().coerceAtLeast(1L)
    }

    fun spring(context: Context, force: androidx.dynamicanimation.animation.SpringForce):
        androidx.dynamicanimation.animation.SpringForce {
        val prefs = preferences(context)
        val mode = (prefs.getString(UISettings.AURORA_MOTION, "2")?.toIntOrNull() ?: 2).coerceIn(0, 2)
        val speed = prefs.getInt(UISettings.AURORA_MOTION_SPEED, 100).coerceIn(50, 150) / 100f
        return when (mode) {
            0 -> force.setStiffness(10000f).setDampingRatio(1f)
            1 -> force.setStiffness((force.stiffness * 1.5f * speed).coerceIn(50f, 10000f))
                .setDampingRatio(0.95f)
            else -> force.setStiffness((force.stiffness * speed).coerceIn(50f, 10000f))
        }
    }
}
