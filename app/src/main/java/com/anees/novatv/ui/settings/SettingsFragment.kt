package com.anees.novatv.ui.settings

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.anees.novatv.BuildConfig
import com.anees.novatv.R
import com.anees.novatv.data.local.LocalStore
import com.anees.novatv.data.local.Settings
import com.anees.novatv.ui.common.ActionSheet
import com.anees.novatv.ui.common.TabPage

/** Apple TV Settings look: a centred list, label on the left, value on the right. */
class SettingsFragment : Fragment(), TabPage {

    private lateinit var list: LinearLayout

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val ctx = requireContext()
        list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        return ScrollView(ctx).apply {
            setBackgroundColor(0xFF0B0B0D.toInt())
            isVerticalScrollBarEnabled = false
            val dp = resources.displayMetrics.density
            setPadding(0, (84 * dp).toInt(), 0, (40 * dp).toInt())
            clipToPadding = false
            addView(LinearLayout(ctx).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                addView(list, LinearLayout.LayoutParams((560 * dp).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
            })
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) = render()

    private fun render() {
        list.removeAllViews()
        val s = LocalStore.settings()
        val langs = listOf("hi" to "Hindi", "en" to "English", "ur" to "Urdu", "orig" to "Original")
        header("Playback")
        row("Audio Language", langs.first { it.first == s.audioLang }.second) {
            pick("Audio Language", langs.map { it.second }, langs.indexOfFirst { it.first == s.audioLang }) { i -> save(s.copy(audioLang = langs[i].first)) }
        }
        row("Subtitles", if (s.subtitlesOn) "On" else "Off") { save(s.copy(subtitlesOn = !s.subtitlesOn)) }
        val sizes = listOf("Small", "Medium", "Large")
        row("Subtitle Size", sizes[s.subtitleSize]) { pick("Subtitle Size", sizes, s.subtitleSize) { save(s.copy(subtitleSize = it)) } }
        val boosts = listOf("Off", "+6 dB", "+10 dB", "+15 dB")
        row("Volume Boost", boosts[s.volumeBoost]) { pick("Volume Boost", boosts, s.volumeBoost) { save(s.copy(volumeBoost = it)) } }
        row("Autoplay Next Episode", if (s.autoplayNext) "On" else "Off") { save(s.copy(autoplayNext = !s.autoplayNext)) }
        header("Video")
        row("Match Frame Rate", if (s.matchFrameRate) "On" else "Off") { save(s.copy(matchFrameRate = !s.matchFrameRate)) }
        row("Low-CPU Playback", if (s.lowCpuMode) "On" else "Off") { save(s.copy(lowCpuMode = !s.lowCpuMode)) }
        note("Match Frame Rate switches the TV to 24Hz for films (less judder). Low-CPU Playback is smoother on this box but some streams may not start — turn it off if that happens.")
        header("Library")
        row("Clear Watch History", "") {
            ActionSheet.show(requireContext(), "Clear watch history?", listOf(
                ActionSheet.Item("Clear") { LocalStore.clearHistory(); Toast.makeText(requireContext(), "History cleared", Toast.LENGTH_SHORT).show() },
                ActionSheet.Item("Cancel")
            ))
        }
        header("About")
        row("Version", BuildConfig.VERSION_NAME) {}
        row("Content", "Family-safe (18+ removed)") {}
    }

    private fun save(s: Settings) {
        val focused = list.indexOfChild(list.focusedChild)
        LocalStore.saveSettings(s)
        render()
        list.getChildAt(focused)?.requestFocus()
    }

    private fun pick(title: String, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
        val focused = list.indexOfChild(list.focusedChild)
        ActionSheet.show(requireContext(), title, options.mapIndexed { i, o -> ActionSheet.Item(o, checked = i == selected) { onPick(i) } }) {
            list.getChildAt(focused)?.requestFocus()
        }
    }

    private fun header(text: String) {
        val dp = resources.displayMetrics.density
        list.addView(TextView(requireContext()).apply {
            this.text = text.uppercase()
            textSize = 11f
            letterSpacing = 0.08f
            setTextColor(0x80FFFFFF.toInt())
            setPadding((16 * dp).toInt(), (22 * dp).toInt(), 0, (6 * dp).toInt())
        })
    }

    private fun note(text: String) {
        val dp = resources.displayMetrics.density
        list.addView(TextView(requireContext()).apply {
            this.text = text
            textSize = 12f
            setTextColor(0x66FFFFFF)
            setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), 0)
        })
    }

    private fun row(label: String, value: String, onClick: () -> Unit) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        list.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * dp).toInt(), 0, (16 * dp).toInt(), 0)
            setBackgroundResource(R.drawable.settings_row)
            isFocusable = true
            setOnClickListener { onClick() }
            setOnFocusChangeListener { v, has -> v.animate().scaleX(if (has) 1.03f else 1f).scaleY(if (has) 1.03f else 1f).setDuration(120).start() }
            addView(TextView(ctx).apply {
                text = label; textSize = 15f; isDuplicateParentStateEnabled = true
                setTextColor(ctx.getColorStateList(R.color.btn_overlay_text))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(ctx).apply {
                text = value; textSize = 14f; isDuplicateParentStateEnabled = true
                setTextColor(ctx.getColorStateList(R.color.settings_value))
                typeface = Typeface.DEFAULT
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (48 * dp).toInt()).apply { topMargin = (4 * dp).toInt() })
    }
}
