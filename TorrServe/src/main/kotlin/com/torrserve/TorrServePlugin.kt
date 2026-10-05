package com.torrserve

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@CloudstreamPlugin
class TorrServePlugin : Plugin() {

    override fun load(context: Context) {
        val provider = TorrServeProvider(context)
        registerMainAPI(provider)

        // Automatically pre-warm and monitor TorrServer to auto-apply user settings on cold launch!
        TorrServeManager.startAutoConfigDaemon(context)

        openSettings = { ctx ->
            showModernSettingsDialog(ctx)
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

    private fun showModernSettingsDialog(ctx: Context) {
        var currentCacheMb = TorrServeManager.getCacheSizeMb(ctx)
        var currentPreload = TorrServeManager.getPreloadPercent(ctx)
        var currentReadAhead = TorrServeManager.getReadAheadPercent(ctx)
        var currentUseDisk = TorrServeManager.getUseDisk(ctx)
        var currentDisableUpload = TorrServeManager.getDisableUpload(ctx)

        val rootScroll = ScrollView(ctx).apply {
            isFillViewport = true
            background = roundedDrawable(ctx, Color.parseColor("#11151E"), radiusDp = 16)
            setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 20))
        }

        val mainLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        rootScroll.addView(mainLayout)

        // --- 1. HEADER & STATUS ---
        val headerLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(ctx, 12))
        }

        val titleView = TextView(ctx).apply {
            text = "TorrServer Inbuilt Configuration"
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F8FAFC"))
        }

        val subtitleView = TextView(ctx).apply {
            text = "Tune CloudStream's BitTorrent streaming engine"
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, dp(ctx, 2), 0, dp(ctx, 8))
        }

        val statusBadge = TextView(ctx).apply {
            text = "● Checking engine status..."
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F59E0B"))
            background = roundedDrawable(ctx, Color.parseColor("#262010"), radiusDp = 8)
            setPadding(dp(ctx, 10), dp(ctx, 5), dp(ctx, 10), dp(ctx, 5))
        }

        CoroutineScope(Dispatchers.IO).launch {
            val serverUrl = runCatching { TorrServeManager.getOrStartServer(ctx) }.getOrNull()
            withContext(Dispatchers.Main) {
                if (serverUrl != null) {
                    statusBadge.text = "● Engine Online: $serverUrl"
                    statusBadge.setTextColor(Color.parseColor("#10B981"))
                    statusBadge.background = roundedDrawable(ctx, Color.parseColor("#064E3B"), radiusDp = 8)
                } else {
                    statusBadge.text = "○ Engine Standby (Will start on stream play)"
                    statusBadge.setTextColor(Color.parseColor("#94A3B8"))
                    statusBadge.background = roundedDrawable(ctx, Color.parseColor("#1E2433"), radiusDp = 8)
                }
            }
        }

        headerLayout.addView(titleView)
        headerLayout.addView(subtitleView)
        headerLayout.addView(statusBadge)
        mainLayout.addView(headerLayout)

        // --- 2. QUICK PROFILES SECTION ---
        val profilesHeader = TextView(ctx).apply {
            text = "QUICK PROFILES"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#818CF8"))
            setPadding(0, dp(ctx, 6), 0, dp(ctx, 6))
        }
        mainLayout.addView(profilesHeader)

        val profileScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 0, 0, dp(ctx, 12))
        }
        val profileRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        profileScroll.addView(profileRow)
        mainLayout.addView(profileScroll)

        // Forward declarations for updating UI
        var updateUiCallback: (() -> Unit)? = null

        fun addProfileBtn(label: String, cache: Long, preload: Int, readAhead: Int, disk: Boolean, noSeed: Boolean) {
            val btn = TextView(ctx).apply {
                text = label
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#E2E8F0"))
                background = roundedDrawable(ctx, Color.parseColor("#1E2433"), radiusDp = 10, strokeColor = Color.parseColor("#2E384D"), strokeWidthDp = 1)
                setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8))
                isFocusable = true
                val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                params.setMargins(0, 0, dp(ctx, 8), 0)
                layoutParams = params

                setOnClickListener {
                    currentCacheMb = cache
                    currentPreload = preload
                    currentReadAhead = readAhead
                    currentUseDisk = disk
                    currentDisableUpload = noSeed
                    updateUiCallback?.invoke()
                    Toast.makeText(ctx, "Loaded profile: $label", Toast.LENGTH_SHORT).show()
                }
            }
            profileRow.addView(btn)
        }

        addProfileBtn("⚡ Instant Start (0%)", 128L, 0, 95, false, true)
        addProfileBtn("📺 FireStick / TV", 48L, 15, 85, true, true)
        addProfileBtn("🎬 4K Cinema", 384L, 15, 95, false, true)
        addProfileBtn("🔄 Stock Default", 64L, 50, 95, false, false)

        // --- Helper for creating styled Card containers ---
        fun createCard(): LinearLayout {
            return LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedDrawable(ctx, Color.parseColor("#1A202C"), radiusDp = 12)
                setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
                val params = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                params.setMargins(0, 0, 0, dp(ctx, 10))
                layoutParams = params
            }
        }

        // --- 3. CARD: BUFFER SIZE ---
        val cacheCard = createCard()
        val cacheTitle = TextView(ctx).apply {
            text = "STREAM BUFFER SIZE"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#94A3B8"))
        }
        val cacheValueLabel = TextView(ctx).apply {
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#60A5FA"))
            setPadding(0, dp(ctx, 2), 0, dp(ctx, 2))
        }
        val cacheDescLabel = TextView(ctx).apply {
            text = "Total memory/disk allocated for torrent pieces"
            textSize = 11f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, 0, 0, dp(ctx, 8))
        }

        val cacheChipsScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
        }
        val cacheChipsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        cacheChipsScroll.addView(cacheChipsRow)

        val presetSizes = listOf(32L, 48L, 64L, 128L, 200L, 256L, 512L, 1024L)
        val chipViews = mutableListOf<TextView>()

        fun refreshBufferChips() {
            cacheValueLabel.text = "$currentCacheMb MB"
            chipViews.forEach { chip ->
                val sizeTag = chip.tag as? Long
                if (sizeTag == currentCacheMb) {
                    chip.background = roundedDrawable(ctx, Color.parseColor("#3B82F6"), radiusDp = 8)
                    chip.setTextColor(Color.WHITE)
                } else {
                    chip.background = roundedDrawable(ctx, Color.parseColor("#262F40"), radiusDp = 8)
                    chip.setTextColor(Color.parseColor("#CBD5E1"))
                }
            }
        }

        presetSizes.forEach { size ->
            val chip = TextView(ctx).apply {
                text = if (size >= 1024L) "1 GB" else "$size MB"
                tag = size
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(ctx, 10), dp(ctx, 6), dp(ctx, 10), dp(ctx, 6))
                isFocusable = true
                val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                params.setMargins(0, 0, dp(ctx, 6), 0)
                layoutParams = params
                setOnClickListener {
                    currentCacheMb = size
                    refreshBufferChips()
                }
            }
            chipViews.add(chip)
            cacheChipsRow.addView(chip)
        }

        // Custom size chip
        val customChip = TextView(ctx).apply {
            text = "✏️ Custom..."
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#93C5FD"))
            background = roundedDrawable(ctx, Color.parseColor("#1E2A42"), radiusDp = 8, strokeColor = Color.parseColor("#3B82F6"), strokeWidthDp = 1)
            setPadding(dp(ctx, 10), dp(ctx, 6), dp(ctx, 10), dp(ctx, 6))
            isFocusable = true
            setOnClickListener {
                val input = EditText(ctx).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER
                    setText(currentCacheMb.toString())
                    setHint("Size in MB")
                    setSelection(text.length)
                }
                val container = FrameLayout(ctx).apply {
                    setPadding(dp(ctx, 20), dp(ctx, 10), dp(ctx, 20), dp(ctx, 10))
                    addView(input)
                }
                AlertDialog.Builder(ctx)
                    .setTitle("Custom Buffer Size (MB)")
                    .setView(container)
                    .setPositiveButton("Set") { _, _ ->
                        val parsed = input.text.toString().trim().toLongOrNull()
                        if (parsed != null && parsed > 0) {
                            currentCacheMb = parsed
                            refreshBufferChips()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        cacheChipsRow.addView(customChip)

        cacheCard.addView(cacheTitle)
        cacheCard.addView(cacheValueLabel)
        cacheCard.addView(cacheDescLabel)
        cacheCard.addView(cacheChipsScroll)
        mainLayout.addView(cacheCard)

        // --- 4. CARD: PRELOAD CACHE % ---
        val preloadCard = createCard()
        val preloadTitle = TextView(ctx).apply {
            text = "INITIAL PRELOAD BUFFER"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#94A3B8"))
        }
        val preloadValueLabel = TextView(ctx).apply {
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#34D399"))
            setPadding(0, dp(ctx, 2), 0, dp(ctx, 2))
        }
        val preloadDescLabel = TextView(ctx).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, 0, 0, dp(ctx, 6))
        }

        val preloadSeekBar = SeekBar(ctx).apply {
            max = 70
            isFocusable = true
            setPadding(0, dp(ctx, 8), 0, dp(ctx, 8))
        }

        fun updatePreloadLabels(percent: Int) {
            val clamped = percent.coerceIn(0, 70)
            preloadValueLabel.text = if (clamped == 0) "0% (Instant Start)" else "$clamped%"
            preloadDescLabel.text = when {
                clamped == 0 -> "Instant Start: Zero pre-buffering! Playback starts immediately as data arrives"
                clamped <= 10 -> "Ultra Fast: Starts playback in ~1-2 seconds (Requires fast seeds)"
                clamped <= 20 -> "Recommended: Starts playback in ~3-5 seconds with good stability"
                clamped <= 35 -> "Safe: Buffers moderately before starting"
                else -> "CloudStream Default: Fills half buffer before playing (Waits longer)"
            }
        }

        preloadSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress.coerceIn(0, 70)
                currentPreload = value
                updatePreloadLabels(value)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Quick preload chips
        val preloadChipsScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 0, 0, dp(ctx, 6))
        }
        val preloadChipsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        preloadChipsScroll.addView(preloadChipsRow)

        listOf(0, 5, 10, 15, 25, 50).forEach { p ->
            val chip = TextView(ctx).apply {
                text = if (p == 0) "⚡ 0% Instant" else "$p%"
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#CBD5E1"))
                background = roundedDrawable(ctx, Color.parseColor("#262F40"), radiusDp = 8)
                setPadding(dp(ctx, 10), dp(ctx, 5), dp(ctx, 10), dp(ctx, 5))
                isFocusable = true
                val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                params.setMargins(0, 0, dp(ctx, 6), 0)
                layoutParams = params
                setOnClickListener {
                    currentPreload = p
                    preloadSeekBar.progress = p
                    updatePreloadLabels(p)
                }
            }
            preloadChipsRow.addView(chip)
        }

        preloadCard.addView(preloadTitle)
        preloadCard.addView(preloadValueLabel)
        preloadCard.addView(preloadDescLabel)
        preloadCard.addView(preloadChipsScroll)
        preloadCard.addView(preloadSeekBar)
        mainLayout.addView(preloadCard)

        // --- 5. CARD: READ-AHEAD WINDOW % ---
        val readAheadCard = createCard()
        val readAheadTitle = TextView(ctx).apply {
            text = "FORWARD READ-AHEAD WINDOW"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#94A3B8"))
        }
        val readAheadValueLabel = TextView(ctx).apply {
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#A78BFA"))
            setPadding(0, dp(ctx, 2), 0, dp(ctx, 2))
        }
        val readAheadDescLabel = TextView(ctx).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, 0, 0, dp(ctx, 6))
        }

        val readAheadSeekBar = SeekBar(ctx).apply {
            max = 100
            isFocusable = true
            setPadding(0, dp(ctx, 8), 0, dp(ctx, 8))
        }

        fun updateReadAheadLabels(percent: Int) {
            val clamped = percent.coerceIn(0, 100)
            readAheadValueLabel.text = if (clamped == 0) "0% (No Lookahead)" else "$clamped%"
            readAheadDescLabel.text = when {
                clamped == 0 -> "Zero Lookahead: Does not pre-buffer chunks ahead of current position"
                clamped < 50 -> "Low Lookahead: Only buffers a minimal window ahead"
                clamped < 80 -> "Balanced: Evenly distributes buffer ahead and behind"
                clamped < 95 -> "High Forward Window: Good buffer cushion for high bitrates"
                else -> "Recommended: Maximizes forward chunk buffer for smooth seeking & stability"
            }
        }

        readAheadSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress.coerceIn(0, 100)
                currentReadAhead = value
                updateReadAheadLabels(value)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Quick read-ahead chips
        val readAheadChipsScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 0, 0, dp(ctx, 6))
        }
        val readAheadChipsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        readAheadChipsScroll.addView(readAheadChipsRow)

        listOf(0, 25, 50, 75, 90, 95, 100).forEach { p ->
            val chip = TextView(ctx).apply {
                text = if (p == 95) "⭐ 95%" else if (p == 0) "0%" else "$p%"
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#CBD5E1"))
                background = roundedDrawable(ctx, Color.parseColor("#262F40"), radiusDp = 8)
                setPadding(dp(ctx, 10), dp(ctx, 5), dp(ctx, 10), dp(ctx, 5))
                isFocusable = true
                val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                params.setMargins(0, 0, dp(ctx, 6), 0)
                layoutParams = params
                setOnClickListener {
                    currentReadAhead = p
                    readAheadSeekBar.progress = p
                    updateReadAheadLabels(p)
                }
            }
            readAheadChipsRow.addView(chip)
        }

        readAheadCard.addView(readAheadTitle)
        readAheadCard.addView(readAheadValueLabel)
        readAheadCard.addView(readAheadDescLabel)
        readAheadCard.addView(readAheadChipsScroll)
        readAheadCard.addView(readAheadSeekBar)
        mainLayout.addView(readAheadCard)

        // --- 6. CARD: STORAGE & NETWORK TOGGLES ---
        val togglesCard = createCard()
        val togglesTitle = TextView(ctx).apply {
            text = "STORAGE & BANDWIDTH CONTROLS"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 0, 0, dp(ctx, 8))
        }
        togglesCard.addView(togglesTitle)

        // Seeding toggle row
        val seedRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 4), 0, dp(ctx, 8))
        }
        val seedTextLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val seedLabel = TextView(ctx).apply {
            text = "Disable Seeding (Leech Only)"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F1F5F9"))
        }
        val seedSubLabel = TextView(ctx).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#64748B"))
        }
        seedTextLayout.addView(seedLabel)
        seedTextLayout.addView(seedSubLabel)

        val seedSwitch = Switch(ctx).apply {
            isFocusable = true
        }

        fun updateSeedUi(disabled: Boolean) {
            seedSwitch.isChecked = disabled
            seedSubLabel.text = if (disabled) {
                "Upload disabled: Saves mobile data, avoids seeding overhead"
            } else {
                "Upload enabled: Shares downloaded chunks with other peers"
            }
        }
        seedSwitch.setOnCheckedChangeListener { _, isChecked ->
            currentDisableUpload = isChecked
            updateSeedUi(isChecked)
        }

        seedRow.addView(seedTextLayout)
        seedRow.addView(seedSwitch)
        togglesCard.addView(seedRow)

        // Divider
        val divider = View(ctx).apply {
            background = ColorDrawable(Color.parseColor("#263043"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1)).apply {
                setMargins(0, dp(ctx, 4), 0, dp(ctx, 8))
            }
        }
        togglesCard.addView(divider)

        // Disk Storage toggle row
        val diskRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 4), 0, dp(ctx, 4))
        }
        val diskTextLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val diskLabel = TextView(ctx).apply {
            text = "Store Cache on Disk"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F1F5F9"))
        }
        val diskSubLabel = TextView(ctx).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#64748B"))
        }
        diskTextLayout.addView(diskLabel)
        diskTextLayout.addView(diskSubLabel)

        val diskSwitch = Switch(ctx).apply {
            isFocusable = true
        }

        fun updateDiskUi(useDisk: Boolean) {
            diskSwitch.isChecked = useDisk
            diskSubLabel.text = if (useDisk) {
                "Using Disk/Storage: Protects low RAM on FireStick/Android TV"
            } else {
                "Using RAM: Instant seek speed and zero flash storage wear"
            }
        }
        diskSwitch.setOnCheckedChangeListener { _, isChecked ->
            currentUseDisk = isChecked
            updateDiskUi(isChecked)
        }

        diskRow.addView(diskTextLayout)
        diskRow.addView(diskSwitch)
        togglesCard.addView(diskRow)

        mainLayout.addView(togglesCard)

        // Full UI refresh lambda (for profiles)
        updateUiCallback = {
            refreshBufferChips()
            preloadSeekBar.progress = currentPreload
            updatePreloadLabels(currentPreload)
            readAheadSeekBar.progress = currentReadAhead
            updateReadAheadLabels(currentReadAhead)
            updateSeedUi(currentDisableUpload)
            updateDiskUi(currentUseDisk)
        }
        updateUiCallback?.invoke()

        // --- 7. ACTION BUTTONS (BOTTOM) ---
        val actionsLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(ctx, 12), 0, 0)
        }

        val alertDialog = AlertDialog.Builder(ctx)
            .setView(rootScroll)
            .create()

        val cancelBtn = TextView(ctx).apply {
            text = "Cancel"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#94A3B8"))
            background = roundedDrawable(ctx, Color.parseColor("#1E2433"), radiusDp = 10)
            setPadding(dp(ctx, 18), dp(ctx, 12), dp(ctx, 18), dp(ctx, 12))
            isFocusable = true
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.setMargins(0, 0, dp(ctx, 10), 0)
            layoutParams = params
            setOnClickListener {
                alertDialog.dismiss()
            }
        }

        val applyBtn = TextView(ctx).apply {
            text = "Save & Apply Settings"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = roundedDrawable(ctx, Color.parseColor("#2563EB"), radiusDp = 10)
            setPadding(dp(ctx, 22), dp(ctx, 12), dp(ctx, 22), dp(ctx, 12))
            isFocusable = true
            setOnClickListener {
                TorrServeManager.setCacheSizeMb(ctx, currentCacheMb)
                TorrServeManager.setPreloadPercent(ctx, currentPreload)
                TorrServeManager.setReadAheadPercent(ctx, currentReadAhead)
                TorrServeManager.setUseDisk(ctx, currentUseDisk)
                TorrServeManager.setDisableUpload(ctx, currentDisableUpload)

                TorrServeManager.applySettingsAsync(ctx) { success, url ->
                    if (success) {
                        Toast.makeText(ctx, "✓ Applied: ${currentCacheMb}MB buffer, ${currentPreload}% preload to engine ($url)", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(ctx, "✓ Saved: Settings will activate automatically on playback", Toast.LENGTH_LONG).show()
                    }
                }
                alertDialog.dismiss()
            }
        }

        actionsLayout.addView(cancelBtn)
        actionsLayout.addView(applyBtn)
        mainLayout.addView(actionsLayout)

        alertDialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        alertDialog.show()
    }
}
