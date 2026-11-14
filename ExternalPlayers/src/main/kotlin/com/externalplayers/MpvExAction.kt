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

class MpvExAction : VideoClickAction() {
    override val name: UiText = txt("Play in mpvEx")
    override val isPlayer: Boolean = true
    override val oneSource: Boolean = true
    override val sourceTypes: Set<ExtractorLinkType> = ExtractorLinkType.entries.toSet()

    companion object {
        const val PKG_PLAYSTORE = "app.adityanarvekar.mpvex"
        const val PKG_PLAYSTORE_DEBUG = "app.adityanarvekar.mpvex.debug"
        const val PKG_GITHUB = "app.marlboroadvance.mpvex"
        const val PKG_GITHUB_PREVIEW = "app.marlboroadvance.mpvex.preview"
        const val PKG_GITHUB_DEBUG = "app.marlboroadvance.mpvex.debug"
        const val DEFAULT_ACTIVITY_NAME = "app.marlboroadvance.mpvex.ui.player.PlayerActivity"
    }

    private fun getInstalledPackage(context: Context?): String? {
        if (context == null) return null
        val pm = context.packageManager
        for (pkg in listOf(
            PKG_PLAYSTORE,
            PKG_PLAYSTORE_DEBUG,
            PKG_GITHUB,
            PKG_GITHUB_PREVIEW,
            PKG_GITHUB_DEBUG
        )) {
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

    private fun resolvePlayerComponent(context: Context, targetPkg: String, streamUri: Uri): ComponentName {
        try {
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setPackage(targetPkg)
                setDataAndType(streamUri, "video/*")
            }
            val resolveInfo = context.packageManager.queryIntentActivities(viewIntent, 0)
            val matched = resolveInfo.firstOrNull { it.activityInfo?.packageName == targetPkg }?.activityInfo
            if (matched != null) {
                return ComponentName(matched.packageName, matched.name)
            }
        } catch (_: Throwable) {}
        return ComponentName(targetPkg, DEFAULT_ACTIVITY_NAME)
    }

    override suspend fun runAction(
        context: Context?,
        video: ResultEpisode,
        result: LinkLoadingResult,
        index: Int?
    ) {
        if (context == null) return
        val targetPkg = getInstalledPackage(context) ?: PKG_PLAYSTORE
        val streamUrl = (if (index != null) result.links.getOrNull(index)?.url else result.links.firstOrNull()?.url) ?: return
        val streamUri = Uri.parse(streamUrl)

        val intent = Intent(Intent.ACTION_VIEW).apply {
            component = resolvePlayerComponent(context, targetPkg, streamUri)
            setPackage(targetPkg)

            setDataAndType(streamUri, "video/*")

            // Pass external subtitles
            val subsArray = result.subs.map { Uri.parse(it.url) }.toTypedArray()
            if (subsArray.isNotEmpty()) {
                putExtra("subs", subsArray)
                putExtra("subs.enable", subsArray)
            }

            // Title & Filename
            putExtra("title", video.name)
            putExtra("filename", video.name)

            // Resume playback position (in milliseconds)
            val savedPos = getViewPos(video.id)?.position
            if (savedPos != null && savedPos > 0) {
                putExtra("position", savedPos.toInt())
            }

            // Headers formatted for mpvEx (User-Agent at index 0..1, then key/value pairs)
            val linkObj = if (index != null) result.links.getOrNull(index) else result.links.firstOrNull()
            if (linkObj != null && linkObj.headers.isNotEmpty()) {
                val headerList = mutableListOf<String>()
                val userAgent = linkObj.headers.entries.firstOrNull { it.key.equals("user-agent", ignoreCase = true) }?.value ?: ""
                headerList.add("User-Agent")
                headerList.add(userAgent)
                for ((k, v) in linkObj.headers) {
                    if (!k.equals("user-agent", ignoreCase = true)) {
                        headerList.add(k)
                        headerList.add(v)
                    }
                }
                putExtra("headers", headerList.toTypedArray())
            }

            putExtra("secure_uri", true)
        }

        setKey("last_opened", video)
        launch(intent)
    }

    fun onResult(activity: Activity, intent: Intent?) {
        val position = intent?.getIntExtra("position", -1)?.takeIf { it > 0 }?.toLong()
            ?: intent?.getLongExtra("position", -1L)?.takeIf { it > 0 } ?: -1L
        val duration = intent?.getIntExtra("duration", -1)?.takeIf { it > 0 }?.toLong()
            ?: intent?.getLongExtra("duration", -1L)?.takeIf { it > 0 } ?: -1L
        if (position > 0 && duration > 0) {
            updateDurationAndPosition(position, duration)
        }
    }
}
