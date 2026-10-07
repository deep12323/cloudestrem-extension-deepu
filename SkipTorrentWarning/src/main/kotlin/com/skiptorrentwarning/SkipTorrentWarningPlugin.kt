package com.skiptorrentwarning

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SkipTorrentWarningPlugin : Plugin() {

    override fun load(context: Context) {
        // Initialize proactive session bypass & background monitoring
        SkipTorrentWarningManager.init(context)

        openSettings = { ctx ->
            showSettingsDialog(ctx)
        }
    }

    override fun beforeUnload() {
        SkipTorrentWarningManager.cleanup()
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
        var isBypassEnabled = SkipTorrentWarningManager.isAutoBypassEnabled(ctx)

        val rootLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 20), dp(ctx, 20), dp(ctx, 20), dp(ctx, 20))
            background = roundedDrawable(ctx, Color.parseColor("#0F172A"), 16, Color.parseColor("#334155"), 1)
        }

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
            text = "🛡️ Skip Torrent Warning"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#F8FAFC"))
        }

        val subtitleView = TextView(ctx).apply {
            text = "Proactive session bypass for seamless torrent streaming"
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
        }

        headerTextLayout.addView(titleView)
        headerTextLayout.addView(subtitleView)
        headerLayout.addView(headerTextLayout)
        contentLayout.addView(headerLayout)

        // --- 2. LIVE DIAGNOSTIC BADGE ---
        val statusBadge = TextView(ctx).apply {
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8))
        }

        fun updateStatusBadge() {
            val sessionAccepted = SkipTorrentWarningManager.getCurrentTorrentSessionState(ctx)
            if (isBypassEnabled && sessionAccepted == true) {
                statusBadge.text = "● Status: Proactive Bypass ACTIVE (Popup Hidden)"
                statusBadge.setTextColor(Color.parseColor("#34D399"))
                statusBadge.background = roundedDrawable(ctx, Color.parseColor("#064E3B"), 8, Color.parseColor("#059669"), 1)
            } else if (isBypassEnabled) {
                statusBadge.text = "● Status: Bypass Enabled (Ready for Next Stream)"
                statusBadge.setTextColor(Color.parseColor("#38BDF8"))
                statusBadge.background = roundedDrawable(ctx, Color.parseColor("#0C4A6E"), 8, Color.parseColor("#0284C7"), 1)
            } else {
                statusBadge.text = "○ Status: Bypass Disabled (Stock Warning Prompts Active)"
                statusBadge.setTextColor(Color.parseColor("#94A3B8"))
                statusBadge.background = roundedDrawable(ctx, Color.parseColor("#1E293B"), 8, Color.parseColor("#475569"), 1)
            }
        }
        updateStatusBadge()
        contentLayout.addView(statusBadge)

        val spacer1 = LinearLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 14))
        }
        contentLayout.addView(spacer1)

        // --- 3. MASTER TOGGLE CARD ---
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
            text = "Auto-Suppress Warning Popup"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#F8FAFC"))
        }

        val masterSub = TextView(ctx).apply {
            text = "Permanently bypasses the 'Stream Torrent' dialog on all torrent streams"
            textSize = 11f
            setTextColor(Color.parseColor("#38BDF8"))
        }

        masterTextLayout.addView(masterTitle)
        masterTextLayout.addView(masterSub)

        val masterSwitch = Switch(ctx).apply {
            isChecked = isBypassEnabled
            setOnCheckedChangeListener { _, isChecked ->
                isBypassEnabled = isChecked
                updateStatusBadge()
            }
        }

        masterCard.addView(masterTextLayout)
        masterCard.addView(masterSwitch)
        contentLayout.addView(masterCard)

        // --- 4. EXPLANATION & PRIVACY CARD ---
        val infoCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
            background = roundedDrawable(ctx, Color.parseColor("#111827"), 10, Color.parseColor("#1F2937"), 1)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dp(ctx, 14), 0, 0)
            }
            layoutParams = lp
        }

        val infoTitle = TextView(ctx).apply {
            text = "ℹ️ How Proactive Session Bypass Works"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#CBD5E1"))
            setPadding(0, 0, 0, dp(ctx, 4))
        }

        val infoBody = TextView(ctx).apply {
            text = "CloudStream normally stores your torrent confirmation only in temporary memory (Torrent.hasAcceptedTorrentForThisSession) and resets it every time the app closes.\n\n" +
                    "This plugin automatically initializes the session acceptance flag the instant CloudStream starts, preventing the 'Stream Torrent' popup from ever appearing.\n\n" +
                    "🔒 Privacy Note: Torrent streaming still connects to P2P swarms. A VPN is recommended if your network or ISP monitors torrenting."
            textSize = 11f
            setTextColor(Color.parseColor("#94A3B8"))
        }

        infoCard.addView(infoTitle)
        infoCard.addView(infoBody)
        contentLayout.addView(infoCard)

        // --- 5. ACTION BUTTONS ---
        var alertDialog: AlertDialog? = null

        val actionsLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(ctx, 16), 0, 0)
        }

        val forceBypassBtn = Button(ctx).apply {
            text = "⚡ Force Bypass Now"
            textSize = 12f
            isAllCaps = false
            setTextColor(Color.parseColor("#38BDF8"))
            background = roundedDrawable(ctx, Color.parseColor("#1E293B"), 8, Color.parseColor("#0284C7"), 1)
            setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8))
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(ctx, 8)
            }
            layoutParams = lp
            setOnClickListener {
                val ok = SkipTorrentWarningManager.enforceProactiveBypass(ctx)
                updateStatusBadge()
                if (ok) {
                    Toast.makeText(ctx, "✓ Proactive torrent bypass successfully enforced!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(ctx, "Bypass applied to active runtime.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val saveBtn = Button(ctx).apply {
            text = "✓ Save & Close"
            textSize = 12f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            background = roundedDrawable(ctx, Color.parseColor("#0284C7"), 8)
            setPadding(dp(ctx, 16), dp(ctx, 8), dp(ctx, 16), dp(ctx, 8))
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = lp
            setOnClickListener {
                val newState = masterSwitch.isChecked
                SkipTorrentWarningManager.setAutoBypassEnabled(ctx, newState)
                if (newState) {
                    SkipTorrentWarningManager.enforceProactiveBypass(ctx)
                    Toast.makeText(ctx, "✓ Torrent popup bypass enabled", Toast.LENGTH_SHORT).show()
                } else {
                    SkipTorrentWarningManager.resetSessionState(ctx)
                    Toast.makeText(ctx, "Torrent popup warnings restored", Toast.LENGTH_SHORT).show()
                }
                alertDialog?.dismiss()
            }
        }

        actionsLayout.addView(forceBypassBtn)
        actionsLayout.addView(saveBtn)
        contentLayout.addView(actionsLayout)

        scrollView.addView(contentLayout)
        rootLayout.addView(scrollView)

        alertDialog = AlertDialog.Builder(ctx)
            .setView(rootLayout)
            .create()

        alertDialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        alertDialog.show()
    }
}
