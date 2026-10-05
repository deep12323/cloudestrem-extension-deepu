import java.io.File

version = 1

cloudstream {
    description = "TorrServer BitTorrent streaming engine, buffer tuner & seeding controls"
    authors = listOf("deepu2135")
    status = 1
    tvTypes = listOf("Torrent", "Movie", "TvSeries")
    requiresResources = false
    language = "en"
    iconUrl = "https://raw.githubusercontent.com/YouROK/TorrServer/master/server/web/public/favicon.ico"
}

android {
    defaultConfig {
        consumerProguardFiles("proguard-rules.pro")
    }
}

afterEvaluate {
    tasks.findByName("make")?.apply {
        doLast {
            val cs3File = file("build/TorrServe.cs3")
            if (cs3File.exists()) {
                val rootBuildsFile = rootProject.file("builds/${cs3File.name}")
                rootBuildsFile.parentFile.mkdirs()
                cs3File.copyTo(rootBuildsFile, overwrite = true)
                println("Updated cs3 file successfully copied to root builds: ${rootBuildsFile.absolutePath}")
            }
        }
    }
}
