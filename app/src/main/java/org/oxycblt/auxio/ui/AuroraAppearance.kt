/*
 * Copyright (c) 2026 Auxio Project
 * AuroraAppearance.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.oxycblt.auxio.ui

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.dynamicanimation.animation.SpringForce
import com.google.android.material.R as MR
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.oxycblt.auxio.R

/**
 * Applies the persisted NOTLI AURORA design tokens to every native screen.
 *
 * MainActivity registers recursive fragment lifecycle callbacks, and RecyclerView rows are styled
 * as they attach, so screens created after navigation receive the same design system.
 */
object AuroraAppearance {
    private fun densityScale(settings: UISettings): Float =
        when (settings.auroraDensity) {
            0 -> 0.90f
            2 -> 1.12f
            else -> 1f
        }

    fun apply(root: View, settings: UISettings) {
        val primary =
            settings.auroraColor(UISettings.AURORA_PRIMARY)
                ?: MaterialColors.getColor(root, MR.attr.colorPrimary)
        val secondary =
            settings.auroraColor(UISettings.AURORA_SECONDARY)
                ?: MaterialColors.getColor(root, MR.attr.colorSecondary)
        val surface = settings.auroraColor(UISettings.AURORA_SURFACE)
        val background = settings.auroraColor(UISettings.AURORA_BACKGROUND)

        background?.let { root.setBackgroundColor(it) }
        applyNode(
            root = root,
            settings = settings,
            primary = primary,
            secondary = secondary,
            surface = surface,
            density = densityScale(settings),
            isRoot = true,
        )
    }

    private fun applyNode(
        root: View,
        settings: UISettings,
        primary: Int,
        secondary: Int,
        surface: Int?,
        density: Float,
        isRoot: Boolean = false,
    ) {
        if (root is TextView) {
            val original =
                (root.getTag(R.id.aurora_base_text_size) as? Float)
                    ?: root.textSize.also { root.setTag(R.id.aurora_base_text_size, it) }
            root.setTextSize(TypedValue.COMPLEX_UNIT_PX, original * settings.auroraFontScale)
        }

        if (root is MaterialButton) {
            root.backgroundTintList = ColorStateList.valueOf(primary)
            root.setTextColor(MaterialColors.getColor(root, MR.attr.colorOnPrimary))
        }

        if (root is FloatingActionButton) {
            root.backgroundTintList = ColorStateList.valueOf(primary)
            root.imageTintList =
                ColorStateList.valueOf(MaterialColors.getColor(root, MR.attr.colorOnPrimary))
        } else if (root is ImageButton) {
            root.imageTintList = ColorStateList.valueOf(primary)
        }

        if (root is MaterialCardView) {
            surface?.let { root.setCardBackgroundColor(it) }
            root.strokeColor = secondary
            if (root.strokeWidth == 0) {
                root.strokeWidth = root.resources.displayMetrics.density.toInt().coerceAtLeast(1)
            }
        }

        // Keep the activity/fragment root padding intact: it may contain system-bar insets.
        if (!isRoot) {
            val basePadding =
                (root.getTag(R.id.aurora_base_padding) as? IntArray)
                    ?: intArrayOf(
                        root.paddingLeft,
                        root.paddingTop,
                        root.paddingRight,
                        root.paddingBottom,
                    ).also { root.setTag(R.id.aurora_base_padding, it) }
            val padding =
                intArrayOf(
                    (basePadding[0] * density).toInt(),
                    (basePadding[1] * density).toInt(),
                    (basePadding[2] * density).toInt(),
                    (basePadding[3] * density).toInt(),
                )
            if (
                root.paddingLeft != padding[0] ||
                    root.paddingTop != padding[1] ||
                    root.paddingRight != padding[2] ||
                    root.paddingBottom != padding[3]
            ) {
                root.setPadding(padding[0], padding[1], padding[2], padding[3])
            }

            val params = root.layoutParams
            if (params is ViewGroup.MarginLayoutParams) {
                val baseMargins =
                    (root.getTag(R.id.aurora_base_margins) as? IntArray)
                        ?: intArrayOf(
                            params.leftMargin,
                            params.topMargin,
                            params.rightMargin,
                            params.bottomMargin,
                        ).also { root.setTag(R.id.aurora_base_margins, it) }
                val margins =
                    intArrayOf(
                        (baseMargins[0] * density).toInt(),
                        (baseMargins[1] * density).toInt(),
                        (baseMargins[2] * density).toInt(),
                        (baseMargins[3] * density).toInt(),
                    )
                if (
                    params.leftMargin != margins[0] ||
                        params.topMargin != margins[1] ||
                        params.rightMargin != margins[2] ||
                        params.bottomMargin != margins[3]
                ) {
                    params.setMargins(margins[0], margins[1], margins[2], margins[3])
                    root.layoutParams = params
                }
            }
        }

        if (root is RecyclerView && root.getTag(R.id.aurora_recycler_hook) != true) {
            root.setTag(R.id.aurora_recycler_hook, true)
            root.addOnChildAttachStateChangeListener(
                object : RecyclerView.OnChildAttachStateChangeListener {
                    override fun onChildViewAttachedToWindow(child: View) {
                        applyNode(child, settings, primary, secondary, surface, density)
                    }

                    override fun onChildViewDetachedFromWindow(child: View) = Unit
                },
            )
        }

        if (root is ImageView && (root is ImageButton || root.contentDescription != null)) {
            val params = root.layoutParams
            if (params != null && params.width > 0 && params.height > 0) {
                val baseSize =
                    (root.getTag(R.id.aurora_base_icon_size) as? IntArray)
                        ?: intArrayOf(params.width, params.height).also {
                            root.setTag(R.id.aurora_base_icon_size, it)
                        }
                val width = (baseSize[0] * settings.auroraIconScale).toInt()
                val height = (baseSize[1] * settings.auroraIconScale).toInt()
                if (params.width != width || params.height != height) {
                    params.width = width
                    params.height = height
                    root.layoutParams = params
                }
            }
        }

        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                applyNode(root.getChildAt(index), settings, primary, secondary, surface, density)
            }
        }
    }
}

/** Motion preferences shared by view transitions and spring-based interaction helpers. */
object AuroraMotion {
    private fun preferences(context: Context): SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    private fun mode(preferences: SharedPreferences): Int =
        (preferences.getString(UISettings.AURORA_MOTION, "2")?.toIntOrNull() ?: 2).coerceIn(0, 2)

    private fun speed(preferences: SharedPreferences): Float =
        preferences.getInt(UISettings.AURORA_MOTION_SPEED, 100).coerceIn(50, 150) / 100f

    fun duration(context: Context, baseMillis: Long): Long {
        val preferences = preferences(context)
        val mode = mode(preferences)
        if (mode == 0) return 0L
        val style = if (mode == 1) 0.65f else 1f
        return (baseMillis * style / speed(preferences)).toLong().coerceAtLeast(1L)
    }

    fun spring(context: Context, force: SpringForce): SpringForce {
        val preferences = preferences(context)
        val mode = mode(preferences)
        val speed = speed(preferences)
        return when (mode) {
            0 -> force.setStiffness(10000f).setDampingRatio(1f)
            1 ->
                force.setStiffness((force.stiffness * 1.5f * speed).coerceIn(50f, 10000f))
                    .setDampingRatio(0.95f)
            else -> force.setStiffness((force.stiffness * speed).coerceIn(50f, 10000f))
        }
    }
}
