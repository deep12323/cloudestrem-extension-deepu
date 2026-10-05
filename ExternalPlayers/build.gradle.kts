import java.io.File

version = 1

cloudstream {
    description = "External player integrations: MPV Infinity (Mpv∞) and 1DM Video Downloader & Player"
    authors = listOf("deepu2135")
    status = 1
    tvTypes = listOf("Others")
    requiresResources = false
    language = "en"
    iconUrl = "https://raw.githubusercontent.com/google/material-design-icons/master/png/av/play_circle_filled/materialicons/48dp/2x/baseline_play_circle_filled_white_48dp.png"
}

android {
    namespace = "com.externalplayers"
    defaultConfig {
        consumerProguardFiles("proguard-rules.pro")
    }
}

afterEvaluate {
    tasks.findByName("make")?.apply {
        doLast {
            val cs3File = file("build/ExternalPlayers.cs3")
            if (cs3File.exists()) {
                val rootBuildsFile = rootProject.file("builds/${cs3File.name}")
                rootBuildsFile.parentFile.mkdirs()
                cs3File.copyTo(rootBuildsFile, overwrite = true)
                println("Updated cs3 file successfully copied to root builds: ${rootBuildsFile.absolutePath}")
            }
        }
    }
}
