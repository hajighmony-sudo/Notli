/*
 * Copyright (c) 2023 Auxio Project
 * UIPreferenceFragment.kt is part of Auxio.
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
 
package org.oxycblt.auxio.settings.categories

import androidx.navigation.fragment.findNavController
import android.graphics.Color
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.oxycblt.auxio.R
import org.oxycblt.auxio.settings.BasePreferenceFragment
import org.oxycblt.auxio.settings.ui.WrappedDialogPreference
import org.oxycblt.auxio.ui.UISettings
import org.oxycblt.auxio.util.isNight
import org.oxycblt.auxio.util.navigateSafe
import timber.log.Timber as L

/**
 * Display preferences.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@AndroidEntryPoint
class UIPreferenceFragment : BasePreferenceFragment(R.xml.preferences_ui) {
    @Inject lateinit var uiSettings: UISettings

    override fun onOpenDialogPreference(preference: WrappedDialogPreference) {
        if (preference.key == getString(R.string.set_key_accent)) {
            L.d("Navigating to accent dialog")
            findNavController().navigateSafe(UIPreferenceFragmentDirections.accentSettings())
        }
    }

    override fun onSetupPreference(preference: Preference) {
        when (preference.key) {
            getString(R.string.set_key_theme) -> {
                L.d("Configuring theme setting")
                preference.onPreferenceChangeListener =
                    Preference.OnPreferenceChangeListener { _, value ->
                        L.d("Theme changed, recreating")
                        requireActivity().recreate()
                        true
                    }
            }
            getString(R.string.set_key_accent) -> {
                L.d("Configuring accent setting")
                preference.summary = getString(uiSettings.accent.name)
            }
            UISettings.AURORA_PRIMARY,
            UISettings.AURORA_SECONDARY,
            UISettings.AURORA_SURFACE,
            UISettings.AURORA_BACKGROUND -> {
                preference.summary = uiSettings.auroraColorHex(preference.key) ?: getString(R.string.aurora_pick_color)
                preference.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                    showAuroraColorPicker(preference.key, preference.title)
                    true
                }
            }
            "aurora_reset_colors" -> preference.onPreferenceClickListener =
                Preference.OnPreferenceClickListener {
                    uiSettings.resetAuroraColors()
                    requireActivity().recreate()
                    true
                }
            "aurora_reset_layout" -> preference.onPreferenceClickListener =
                Preference.OnPreferenceClickListener {
                    uiSettings.resetAuroraLayout()
                    requireActivity().recreate()
                    true
                }
            "aurora_reset_motion" -> preference.onPreferenceClickListener =
                Preference.OnPreferenceClickListener {
                    uiSettings.resetAuroraMotion()
                    requireActivity().recreate()
                    true
                }
            UISettings.AURORA_FONT_SCALE,
            UISettings.AURORA_ICON_SCALE,
            UISettings.AURORA_DENSITY -> {
                preference.onPreferenceChangeListener =
                    Preference.OnPreferenceChangeListener { _, _ ->
                        val activity = requireActivity()
                        activity.window.decorView.post { activity.recreate() }
                        true
                    }
            }
            getString(R.string.set_key_black_theme) -> {
                L.d("Configuring black theme setting")
                preference.onPreferenceChangeListener =
                    Preference.OnPreferenceChangeListener { _, _ ->
                        val activity = requireActivity()
                        if (activity.isNight) {
                            L.d("Black theme changed in night mode, recreating")
                            activity.recreate()
                        }

                        true
                    }
            }
        }
    }

    private fun showAuroraColorPicker(key: String, title: CharSequence) {
        val fallback = when (key) {
            UISettings.AURORA_PRIMARY -> "#63E6F2"
            UISettings.AURORA_SECONDARY -> "#9A7BFF"
            UISettings.AURORA_SURFACE -> "#171D2D"
            else -> "#0B1020"
        }
        val initial = uiSettings.auroraColor(key) ?: Color.parseColor(fallback)
        val context = requireContext()
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        val preview = View(context).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(initial)
            }
        }
        content.addView(preview, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
        ))
        val hex = EditText(context).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "#63E6F2"
            setText(String.format("#%06X", 0xFFFFFF and initial))
        }
        content.addView(hex, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })

        val channels = intArrayOf(Color.red(initial), Color.green(initial), Color.blue(initial))
        val sliders = ArrayList<SeekBar>()
        val names = listOf("Red", "Green", "Blue")
        fun refreshColor() {
            val color = Color.rgb(channels[0], channels[1], channels[2])
            preview.setBackgroundColor(color)
            val value = String.format("#%06X", 0xFFFFFF and color)
            hex.setText(value)
            hex.setSelection(value.length)
        }
        names.forEachIndexed { index, name ->
            content.addView(TextView(context).apply {
                text = "$name · ${channels[index]}"
                textSize = 13f
            })
            val slider = SeekBar(context).apply {
                max = 255
                progress = channels[index]
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                        channels[index] = value
                        refreshColor()
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                })
            }
            sliders.add(slider)
            content.addView(slider)
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val normalized = hex.text.toString().trim().let {
                    if (it.startsWith("#")) it else "#$it"
                }
                val valid = normalized.matches(Regex("#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})"))
                if (!valid) {
                    hex.error = getString(R.string.aurora_color_invalid)
                } else {
                    runCatching { Color.parseColor(normalized) }
                        .onSuccess {
                            uiSettings.setAuroraColor(key, normalized)
                            requireActivity().recreate()
                            dialog.dismiss()
                        }
                        .onFailure { hex.error = getString(R.string.aurora_color_invalid) }
                }
            }
        }
        dialog.show()
    }
}
