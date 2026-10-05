package com.externalplayers

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.lagradost.cloudstream3.actions.VideoClickAction
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.UiText
import com.lagradost.cloudstream3.utils.txt

class OneDmAction : VideoClickAction() {
    override val name: UiText = txt("Play in 1DM")
    override val isPlayer: Boolean = true
    override val oneSource: Boolean = true
    override val sourceTypes: Set<ExtractorLinkType> = setOf(
        ExtractorLinkType.VIDEO,
        ExtractorLinkType.DASH,
        ExtractorLinkType.M3U8,
        ExtractorLinkType.TORRENT,
        ExtractorLinkType.MAGNET
    )

    companion object {
        const val PKG_1DM_PLUS = "idm.internet.download.manager.plus"
        const val PKG_1DM_NORMAL = "idm.internet.download.manager"
        const val PKG_1DM_LITE = "idm.internet.download.manager.adm.lite"
        const val ACTIVITY_NAME = "idm.internet.download.manager.Downloader"
    }

    private fun getInstalledPackage(context: Context?): String? {
        if (context == null) return null
        val pm = context.packageManager
        for (pkg in listOf(PKG_1DM_PLUS, PKG_1DM_NORMAL, PKG_1DM_LITE)) {
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
        val targetPkg = getInstalledPackage(context) ?: PKG_1DM_PLUS
        val streamUrl = (if (index != null) result.links.getOrNull(index)?.url else result.links.firstOrNull()?.url) ?: return

        val intent = Intent(Intent.ACTION_VIEW).apply {
            component = ComponentName(targetPkg, ACTIVITY_NAME)
            setPackage(targetPkg)
            setDataAndType(Uri.parse(streamUrl), "video/*")

            putExtra("secure_uri", true)
            putExtra("extra_filename", video.name)

            val linkObj = if (index != null) result.links.getOrNull(index) else result.links.firstOrNull()
            if (linkObj != null && linkObj.headers.isNotEmpty()) {
                val headersBundle = Bundle()
                for ((k, v) in linkObj.headers) {
                    headersBundle.putString(k, v)
                    if (k.equals("referer", ignoreCase = true)) {
                        putExtra("extra_referer", v)
                    }
                    if (k.equals("user-agent", ignoreCase = true)) {
                        putExtra("extra_useragent", v)
                    }
                    if (k.equals("cookie", ignoreCase = true)) {
                        putExtra("extra_cookies", v)
                    }
                }
                putExtra("extra_headers", headersBundle)
            }
        }

        launch(intent)
    }
}
