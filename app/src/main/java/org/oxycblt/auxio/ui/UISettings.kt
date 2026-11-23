/*
 * Copyright (c) 2023 Auxio Project
 * UISettings.kt is part of Auxio.
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
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import org.oxycblt.auxio.R
import org.oxycblt.auxio.settings.Settings
import org.oxycblt.auxio.ui.accent.Accent
import timber.log.Timber as L

/**
 * User configuration for the general app UI.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
interface UISettings : Settings<UISettings.Listener> {
    /** The current theme. Represented by the AppCompatDelegate constants. */
    val theme: Int
    /** Whether to use a black background when a dark theme is currently used. */
    val useBlackTheme: Boolean
    /** The current [Accent] (Color Scheme). */
    var accent: Accent
    /** Whether to round additional UI elements that require album covers to be rounded. */
    val roundMode: Boolean

    /** User-defined Aurora palette overrides. Null means use the theme default. */
    fun auroraColor(key: String): Int?
    fun auroraColorHex(key: String): String?
    fun setAuroraColor(key: String, hex: String)
    fun resetAuroraColors()

    /** Global layout controls. */
    val auroraFontScale: Float
    val auroraIconScale: Float
    val auroraDensity: Int
    fun resetAuroraLayout()

    /** Motion profile: 0 = off, 1 = soft, 2 = expressive. */
    val auroraMotion: Int
    val auroraMotionSpeed: Int
    fun resetAuroraMotion()

    companion object {
        const val AURORA_PRIMARY = "notli_aurora_primary"
        const val AURORA_SECONDARY = "notli_aurora_secondary"
        const val AURORA_SURFACE = "notli_aurora_surface"
        const val AURORA_BACKGROUND = "notli_aurora_background"
        const val AURORA_FONT_SCALE = "notli_aurora_font_scale"
        const val AURORA_ICON_SCALE = "notli_aurora_icon_scale"
        const val AURORA_DENSITY = "notli_aurora_density"
        const val AURORA_MOTION = "notli_aurora_motion"
        const val AURORA_MOTION_SPEED = "notli_aurora_motion_speed"
    }

    interface Listener {
        /** Called when [roundMode] changes. */
        fun onRoundModeChanged()
    }
}

class UISettingsImpl @Inject constructor(@ApplicationContext context: Context) :
    Settings.Impl<UISettings.Listener>(context), UISettings {
    override val theme: Int
        get() =
            sharedPreferences.getInt(
                getString(R.string.set_key_theme),
                AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
            )

    override val useBlackTheme: Boolean
        get() = sharedPreferences.getBoolean(getString(R.string.set_key_black_theme), false)

    override var accent: Accent
        get() =
            Accent.from(
                sharedPreferences.getInt(getString(R.string.set_key_accent), Accent.DEFAULT),
            )
        set(value) {
            sharedPreferences.edit {
                putInt(getString(R.string.set_key_accent), value.index)
                apply()
            }
        }

    override val roundMode: Boolean
        get() = sharedPreferences.getBoolean(getString(R.string.set_key_round_mode), true)

    override fun auroraColor(key: String): Int? =
        auroraColorHex(key)?.let { runCatching { android.graphics.Color.parseColor(it) }.getOrNull() }

    override fun auroraColorHex(key: String): String? =
        if (key in AURORA_COLOR_KEYS) sharedPreferences.getString(key, null) else null

    override fun setAuroraColor(key: String, hex: String) {
        require(key in AURORA_COLOR_KEYS)
        val normalized = hex.trim().let { if (it.startsWith("#")) it else "#$it" }
        require(normalized.matches(Regex("#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})")))
        sharedPreferences.edit { putString(key, normalized.uppercase()) }
    }

    override fun resetAuroraColors() {
        sharedPreferences.edit { AURORA_COLOR_KEYS.forEach { remove(it) } }
    }

    override val auroraFontScale: Float
        get() = sharedPreferences.getInt(UISettings.AURORA_FONT_SCALE, 100).coerceIn(80, 140) / 100f

    override val auroraIconScale: Float
        get() = sharedPreferences.getInt(UISettings.AURORA_ICON_SCALE, 100).coerceIn(75, 150) / 100f

    override val auroraDensity: Int
        get() = (sharedPreferences.getString(UISettings.AURORA_DENSITY, "1")?.toIntOrNull() ?: 1).coerceIn(0, 2)

    override fun resetAuroraLayout() {
        sharedPreferences.edit {
            remove(UISettings.AURORA_FONT_SCALE)
            remove(UISettings.AURORA_ICON_SCALE)
            remove(UISettings.AURORA_DENSITY)
        }
    }

    override val auroraMotion: Int
        get() = (sharedPreferences.getString(UISettings.AURORA_MOTION, "2")?.toIntOrNull() ?: 2).coerceIn(0, 2)

    override val auroraMotionSpeed: Int
        get() = sharedPreferences.getInt(UISettings.AURORA_MOTION_SPEED, 100).coerceIn(50, 150)

    override fun resetAuroraMotion() {
        sharedPreferences.edit {
            remove(UISettings.AURORA_MOTION)
            remove(UISettings.AURORA_MOTION_SPEED)
        }
    }

    override fun migrate() {
        if (sharedPreferences.contains(OLD_KEY_ACCENT3)) {
            L.d("Migrating $OLD_KEY_ACCENT3")

            var accent = sharedPreferences.getInt(OLD_KEY_ACCENT3, 5)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Accents were previously frozen as soon as the OS was updated to android
                // twelve, as dynamic colors were enabled by default. This is no longer the
                // case, so we need to re-update the setting to dynamic colors here.
                accent = 16
            }

            sharedPreferences.edit {
                putInt(getString(R.string.set_key_accent), accent)
                remove(OLD_KEY_ACCENT3)
                apply()
            }
        }
    }

    override fun onSettingChanged(key: String, listener: UISettings.Listener) {
        if (key == getString(R.string.set_key_round_mode)) {
            L.d("Dispatching round mode setting change")
            listener.onRoundModeChanged()
        }
    }

    private companion object {
        const val OLD_KEY_ACCENT3 = "auxio_accent"
        val AURORA_COLOR_KEYS =
            setOf(UISettings.AURORA_PRIMARY, UISettings.AURORA_SECONDARY, UISettings.AURORA_SURFACE, UISettings.AURORA_BACKGROUND)
    }
}
