package com.dialogueboost

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DialogueBoostPlugin : Plugin() {

    override fun load(context: Context) {
        // Enforce settings immediately when Cloudstream boots
        if (DialogueBoostManager.isAlwaysEnabled(context)) {
            DialogueBoostManager.enforceCompressorSettings(context)
        }

        // Start background auto-enforce daemon
        DialogueBoostManager.startAutoEnforceDaemon(context)

        // Register settings gear icon
        openSettings = { ctx ->
            showSettingsDialog(ctx)
        }
    }

    private fun dp(ctx: Context, value: Int): Int {
        return (value * ctx.resources.displayMetrics.density).toInt()
    }

    private fun roundedDrawable(
        ctx: Context,
        bgColor: Int,
        radiusDp: Int = 12,
        strokeColor: Int = 0,
        strokeWidthDp: Int = 0
    ): GradientDrawable {
        val rPx = radiusDp * ctx.resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(bgColor)
            cornerRadius = rPx
            if (strokeWidthDp > 0) {
                setStroke((strokeWidthDp * ctx.resources.displayMetrics.density).toInt(), strokeColor)
            }
        }
    }

    private fun showSettingsDialog(ctx: Context) {
        var isAlwaysOn = DialogueBoostManager.isAlwaysEnabled(ctx)
        var selectedPreset = DialogueBoostManager.getPreset(ctx)
        var currentThreshold = DialogueBoostManager.getThreshold(ctx)
        var currentMakeup = DialogueBoostManager.getMakeup(ctx)
        var currentRatio = DialogueBoostManager.getRatio(ctx)

        // Root container with dark aesthetic
        val rootLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 20), dp(ctx, 20), dp(ctx, 20), dp(ctx, 20))
            background = roundedDrawable(ctx, Color.parseColor("#0F172A"), 16, Color.parseColor("#334155"), 1)
        }

        // Scroll container for TV / small screens
        val scrollView = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            isVerticalScrollBarEnabled = false
        }
        val contentLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }

        // --- 1. HEADER ---
        val headerLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(ctx, 16))
        }

        val headerTextLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val titleView = TextView(ctx).apply {
            text = "🎙️ Dialogue Boost"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#F8FAFC"))
        }

        val subtitleView = TextView(ctx).apply {
            text = "Dynamic range compressor for clear speech & tamed action volume"
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
        }

        headerTextLayout.addView(titleView)
        headerTextLayout.addView(subtitleView)
        headerLayout.addView(headerTextLayout)
        contentLayout.addView(headerLayout)

        // --- 2. MASTER TOGGLE CARD ---
        val masterCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14))
            background = roundedDrawable(ctx, Color.parseColor("#1E293B"), 12, Color.parseColor("#334155"), 1)
        }

        val masterTextLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val masterTitle = TextView(ctx).apply {
            text = "Always Enable Compressor"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#F8FAFC"))
        }

        val masterSub = TextView(ctx).apply {
            text = "Forces Dialogue Boost active on every video from the first frame"
            textSize = 11f
            setTextColor(Color.parseColor("#38BDF8"))
        }

        masterTextLayout.addView(masterTitle)
        masterTextLayout.addView(masterSub)

        val masterSwitch = Switch(ctx).apply {
            isChecked = isAlwaysOn
        }

        masterCard.addView(masterTextLayout)
        masterCard.addView(masterSwitch)
        contentLayout.addView(masterCard)

        // --- 3. PRESETS SECTION ---
        val presetHeader = TextView(ctx).apply {
            text = "COMPRESSION PRESETS"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, dp(ctx, 18), 0, dp(ctx, 8))
        }
        contentLayout.addView(presetHeader)

        val presetButtonsLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }

        val presetDescView = TextView(ctx).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#E2E8F0"))
            setPadding(dp(ctx, 4), dp(ctx, 8), dp(ctx, 4), dp(ctx, 14))
        }

        // Custom sliders container
        val customSlidersContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (selectedPreset == DialogueBoostManager.PRESET_CUSTOM) View.VISIBLE else View.GONE
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
            background = roundedDrawable(ctx, Color.parseColor("#1E293B"), 12)
        }

        // Sliders
        val threshLabel = TextView(ctx).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#F8FAFC"))
        }
        val threshSeekBar = SeekBar(ctx).apply {
            max = 30 // -30 dB to 0 dB
            progress = (currentThreshold + 30f).toInt().coerceIn(0, 30)
        }

        val makeupLabel = TextView(ctx).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#F8FAFC"))
        }
        val makeupSeekBar = SeekBar(ctx).apply {
            max = 24 // 0 dB to 24 dB
            progress = currentMakeup.toInt().coerceIn(0, 24)
        }

        fun updateSliderLabels() {
            threshLabel.text = "Threshold: ${currentThreshold.toInt()} dB (quieter sounds pass through, louder get reduced)"
            makeupLabel.text = "Makeup Gain: +${currentMakeup.toInt()} dB (boosts overall loudness & quiet dialogue)"
        }
        updateSliderLabels()

        threshSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    currentThreshold = (progress - 30).toFloat()
                    updateSliderLabels()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        makeupSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    currentMakeup = progress.toFloat()
                    updateSliderLabels()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        customSlidersContainer.addView(threshLabel)
        customSlidersContainer.addView(threshSeekBar)
        customSlidersContainer.addView(makeupLabel)
        customSlidersContainer.addView(makeupSeekBar)

        val presetButtons = mutableListOf<Button>()

        fun updatePresetSelection(preset: String) {
            selectedPreset = preset
            when (preset) {
                DialogueBoostManager.PRESET_DIALOGUE -> {
                    currentThreshold = -24f
                    currentMakeup = 12f
                    currentRatio = 8f
                    presetDescView.text = "🎬 Dialogue Boost (-24 dB / +12 dB, 8:1 ratio)\nRecommended. Heavily boosts quiet speech while transparently suppressing sudden sound spikes."
                    customSlidersContainer.visibility = View.GONE
                }
                DialogueBoostManager.PRESET_NIGHT -> {
                    currentThreshold = -30f
                    currentMakeup = 16f
                    currentRatio = 12f
                    presetDescView.text = "🌙 Night Mode (-30 dB / +16 dB, 12:1 ratio)\nMaximum action limiting. Whispers and dialogues stay loud; explosions are clamped so you don't wake family or neighbors."
                    customSlidersContainer.visibility = View.GONE
                }
                DialogueBoostManager.PRESET_LIGHT -> {
                    currentThreshold = -18f
                    currentMakeup = 4f
                    currentRatio = 4f
                    presetDescView.text = "🔈 Light Compression (-18 dB / +4 dB, 4:1 ratio)\nSubtle, natural dynamic leveling suitable for headphones or balanced stereo setups."
                    customSlidersContainer.visibility = View.GONE
                }
                DialogueBoostManager.PRESET_CUSTOM -> {
                    presetDescView.text = "🎛️ Custom Tuning\nManual threshold and makeup gain adjustments."
                    customSlidersContainer.visibility = View.VISIBLE
                    updateSliderLabels()
                }
            }

            presetButtons.forEach { btn ->
                val isSelected = btn.tag == preset
                if (isSelected) {
                    btn.background = roundedDrawable(ctx, Color.parseColor("#0284C7"), 8)
                    btn.setTextColor(Color.WHITE)
                    btn.setTypeface(null, Typeface.BOLD)
                } else {
                    btn.background = roundedDrawable(ctx, Color.parseColor("#1E293B"), 8, Color.parseColor("#334155"), 1)
                    btn.setTextColor(Color.parseColor("#94A3B8"))
                    btn.setTypeface(null, Typeface.NORMAL)
                }
            }
        }

        val presets = listOf(
            Triple(DialogueBoostManager.PRESET_DIALOGUE, "🎬 Dialogue Boost (Recommended)", "Optimal clarity for movies & TV series"),
            Triple(DialogueBoostManager.PRESET_NIGHT, "🌙 Night Mode / Action Limiter", "Tames loud explosions; amplifies quiet voices"),
            Triple(DialogueBoostManager.PRESET_LIGHT, "🔈 Light Compression", "Gentle leveling for headphones"),
            Triple(DialogueBoostManager.PRESET_CUSTOM, "🎛️ Custom Configuration", "Adjust threshold and gain manually")
        )

        presets.forEach { (key, title, subtitle) ->
            val btn = Button(ctx).apply {
                tag = key
                text = "$title\n$subtitle"
                textSize = 12f
                isAllCaps = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(dp(ctx, 14), dp(ctx, 10), dp(ctx, 14), dp(ctx, 10))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, dp(ctx, 8))
                }
                layoutParams = lp
                setOnClickListener {
                    updatePresetSelection(key)
                }
            }
            presetButtons.add(btn)
            presetButtonsLayout.addView(btn)
        }

        contentLayout.addView(presetButtonsLayout)
        contentLayout.addView(presetDescView)
        contentLayout.addView(customSlidersContainer)

        // --- 4. ACTION BUTTONS ---
        val actionsLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(ctx, 20), 0, 0)
        }

        var alertDialog: AlertDialog? = null

        val cancelBtn = Button(ctx).apply {
            text = "Cancel"
            setTextColor(Color.parseColor("#94A3B8"))
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { alertDialog?.dismiss() }
        }

        val applyBtn = Button(ctx).apply {
            text = "✓ Apply & Enforce"
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            background = roundedDrawable(ctx, Color.parseColor("#0284C7"), 8)
            setPadding(dp(ctx, 16), dp(ctx, 10), dp(ctx, 16), dp(ctx, 10))
            setOnClickListener {
                isAlwaysOn = masterSwitch.isChecked
                DialogueBoostManager.setAlwaysEnabled(ctx, isAlwaysOn)
                DialogueBoostManager.setPreset(ctx, selectedPreset)

                if (selectedPreset == DialogueBoostManager.PRESET_CUSTOM) {
                    DialogueBoostManager.setCustomThreshold(ctx, currentThreshold)
                    DialogueBoostManager.setCustomMakeup(ctx, currentMakeup)
                    DialogueBoostManager.setCustomRatio(ctx, currentRatio)
                }

                val success = DialogueBoostManager.enforceCompressorSettings(ctx)
                if (success) {
                    val summary = if (isAlwaysOn) {
                        "✓ Active: ${currentThreshold.toInt()} dB threshold, +${currentMakeup.toInt()} dB makeup gain"
                    } else {
                        "✓ Dialogue Boost compressor disabled"
                    }
                    Toast.makeText(ctx, summary, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(ctx, "Settings saved successfully", Toast.LENGTH_SHORT).show()
                }
                alertDialog?.dismiss()
            }
        }

        actionsLayout.addView(cancelBtn)
        actionsLayout.addView(applyBtn)
        contentLayout.addView(actionsLayout)

        scrollView.addView(contentLayout)
        rootLayout.addView(scrollView)

        updatePresetSelection(selectedPreset)

        alertDialog = AlertDialog.Builder(ctx)
            .setView(rootLayout)
            .create()

        alertDialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        alertDialog.show()
    }
}
