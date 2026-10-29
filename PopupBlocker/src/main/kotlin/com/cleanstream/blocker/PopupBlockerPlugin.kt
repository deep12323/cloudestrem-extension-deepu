package com.cleanstream.blocker

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class PopupBlockerPlugin : Plugin() {

    override fun load(context: Context) {
        PopupBlockerManager.init(context)

        openSettings = { ctx ->
            showMinimalSettingsDialog(ctx)
        }
    }

    override fun beforeUnload() {
        PopupBlockerManager.cleanup()
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

    private fun showMinimalSettingsDialog(ctx: Context) {
        val rootLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 20), dp(ctx, 20), dp(ctx, 20), dp(ctx, 16))
            background = roundedDrawable(ctx, Color.parseColor("#0F172A"), 16, Color.parseColor("#1E293B"), 1)
        }

        // --- TITLE ---
        val titleView = TextView(ctx).apply {
            text = "🛡️ Popup & Donation Blocker"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#F8FAFC"))
            setPadding(0, 0, 0, dp(ctx, 4))
        }
        rootLayout.addView(titleView)

        val descView = TextView(ctx).apply {
            text = "Silently hides intrusive donation popups & ad banners (CNCVerse, etc.)"
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 0, 0, dp(ctx, 14))
        }
        rootLayout.addView(descView)

        fun createToggleRow(
            title: String,
            subtitle: String,
            initialState: Boolean,
            onChanged: (Boolean) -> Unit
        ): LinearLayout {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
                background = roundedDrawable(ctx, Color.parseColor("#1E293B"), 10)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, dp(ctx, 10))
                }
                layoutParams = lp
            }

            val textLayout = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val titleTv = TextView(ctx).apply {
                text = title
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#F1F5F9"))
            }

            val subTv = TextView(ctx).apply {
                text = subtitle
                textSize = 11f
                setTextColor(Color.parseColor("#94A3B8"))
                setPadding(0, dp(ctx, 2), 0, 0)
            }

            textLayout.addView(titleTv)
            textLayout.addView(subTv)

            val toggle = Switch(ctx).apply {
                isChecked = initialState
                setOnCheckedChangeListener { _, isChecked ->
                    onChanged(isChecked)
                }
            }

            row.addView(textLayout)
            row.addView(toggle)
            return row
        }

        // Toggle 1: Block Donation Dialogs
        val donationRow = createToggleRow(
            title = "Block Donation Popups",
            subtitle = "Intercept & dismiss 'Keep CNCVerse Alive' dialogs",
            initialState = PopupBlockerManager.isBlockDonationDialogsEnabled(ctx)
        ) { enabled ->
            PopupBlockerManager.setBlockDonationDialogsEnabled(ctx, enabled)
        }
        rootLayout.addView(donationRow)

        // Toggle 2: Proactive Date Immunization
        val immRow = createToggleRow(
            title = "Proactive Date Shield",
            subtitle = "Pre-authorizes donation check to prevent API stats calls",
            initialState = PopupBlockerManager.isImmunizePrefsEnabled(ctx)
        ) { enabled ->
            PopupBlockerManager.setImmunizePrefsEnabled(ctx, enabled)
        }
        rootLayout.addView(immRow)

        // Toggle 3: Auto-Dismiss Ad Banners
        val adRow = createToggleRow(
            title = "Suppress Ad Banners",
            subtitle = "Auto-dismiss popup cards containing ad & sponsor links",
            initialState = PopupBlockerManager.isAutoDismissAdsEnabled(ctx)
        ) { enabled ->
            PopupBlockerManager.setAutoDismissAdsEnabled(ctx, enabled)
        }
        rootLayout.addView(adRow)

        // --- STATUS BADGE ---
        val statusBadge = TextView(ctx).apply {
            text = "● Shield Active — 0 popups allowed"
            textSize = 11f
            setTextColor(Color.parseColor("#10B981"))
            setPadding(0, dp(ctx, 4), 0, dp(ctx, 10))
            gravity = Gravity.CENTER
        }
        rootLayout.addView(statusBadge)

        // --- DONE / CLOSE BUTTON ---
        var alertDialog: AlertDialog? = null

        val closeBtn = TextView(ctx).apply {
            text = "Done"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
            gravity = Gravity.CENTER
            setPadding(0, dp(ctx, 10), 0, dp(ctx, 4))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            layoutParams = lp
            isFocusable = true
            setOnClickListener {
                alertDialog?.dismiss()
            }
        }
        rootLayout.addView(closeBtn)

        alertDialog = AlertDialog.Builder(ctx)
            .setView(rootLayout)
            .create()

        alertDialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        alertDialog.show()
    }
}
