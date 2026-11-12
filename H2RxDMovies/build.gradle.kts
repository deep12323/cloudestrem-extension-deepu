import java.io.File

version = 2

cloudstream {
    description = "Stream movies & TV series from H2RxDMovies (HubCloud direct R2 & Google CDN streams)"
    authors = listOf("deepu2135")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    requiresResources = false
    language = "en"
    iconUrl = "https://h2r-xdmovies.pages.dev/favicon.svg"
}

android {
    namespace = "com.h2rxdmovies"
    defaultConfig {
        consumerProguardFiles("proguard-rules.pro")
    }
}

afterEvaluate {
    tasks.findByName("make")?.apply {
        doLast {
            val cs3File = file("build/H2RxDMovies.cs3")
            if (cs3File.exists()) {
                val rootBuildsFile = rootProject.file("builds/${cs3File.name}")
                rootBuildsFile.parentFile.mkdirs()
                cs3File.copyTo(rootBuildsFile, overwrite = true)
                println("Updated cs3 file successfully copied to root builds: ${rootBuildsFile.absolutePath}")
            }
        }
    }
}
