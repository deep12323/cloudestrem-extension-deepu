package com.externalplayers

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.actions.VideoClickAction
import com.lagradost.cloudstream3.actions.updateDurationAndPosition
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.DataStoreHelper.getViewPos
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.UiText
import com.lagradost.cloudstream3.utils.txt

class MpvInfinityAction : VideoClickAction() {
    override val name: UiText = txt("Play in Mpv Infinity")
    override val isPlayer: Boolean = true
    override val oneSource: Boolean = true
    override val sourceTypes: Set<ExtractorLinkType> = ExtractorLinkType.entries.toSet()

    companion object {
        const val PKG_MAIN = "app.infinity.mpvz"
        const val PKG_DEBUG = "app.infinity.mpvz.debug"
        const val ACTIVITY_NAME = "app.infinity.mpvz.ui.player.PlayerActivity"
    }

    private fun getInstalledPackage(context: Context?): String? {
        if (context == null) return null
        val pm = context.packageManager
        for (pkg in listOf(PKG_MAIN, PKG_DEBUG)) {
            try {
                pm.getPackageInfo(pkg, 0)
                return pkg
            } catch (_: Throwable) {}
        }
        return null
    }

    override fun shouldShow(context: Context?, video: ResultEpisode?): Boolean {
        // Show in settings/dialog if installed or context is null
        if (context == null) return true
        return getInstalledPackage(context) != null
    }

    override suspend fun runAction(
        context: Context?,
        video: ResultEpisode,
        result: LinkLoadingResult,
        index: Int?
    ) {
        if (context == null) return
        val targetPkg = getInstalledPackage(context) ?: PKG_MAIN
        val streamUrl = (if (index != null) result.links.getOrNull(index)?.url else result.links.firstOrNull()?.url) ?: return

        val intent = Intent(Intent.ACTION_VIEW).apply {
            component = ComponentName(targetPkg, ACTIVITY_NAME)
            setPackage(targetPkg)

            setDataAndType(Uri.parse(streamUrl), "video/*")

            // Pass external subtitles
            val subsArray = result.subs.map { Uri.parse(it.url) }.toTypedArray()
            if (subsArray.isNotEmpty()) {
                putExtra("subs", subsArray)
                putExtra("subs.enable", subsArray)
            }

            // Title
            putExtra("title", video.name)

            // Resume playback position
            val savedPos = getViewPos(video.id)?.position
            if (savedPos != null && savedPos > 0) {
                putExtra("position", savedPos.toInt())
            }

            // Headers
            val linkObj = if (index != null) result.links.getOrNull(index) else result.links.firstOrNull()
            if (linkObj != null && linkObj.headers.isNotEmpty()) {
                val headerStrings = linkObj.headers.map { "${it.key}: ${it.value}" }.toTypedArray()
                putExtra("headers", headerStrings)
            }

            putExtra("secure_uri", true)
        }

        setKey("last_opened", video)
        launch(intent)
    }

    fun onResult(activity: Activity, intent: Intent?) {
        val position = intent?.getIntExtra("position", -1) ?: -1
        val duration = intent?.getIntExtra("duration", -1) ?: -1
        if (position > 0 && duration > 0) {
            updateDurationAndPosition(position.toLong(), duration.toLong())
        }
    }
}
