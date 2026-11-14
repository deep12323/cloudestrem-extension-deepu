import java.io.File

version = 1

cloudstream {
    description = "Live TV & Sports channels from Allinone Live TV (JioTV Plus, SonyLiv, Sports TV, Fancode)"
    authors = listOf("deepu2135")
    status = 1
    tvTypes = listOf("Live")
    requiresResources = false
    language = "en"
    iconUrl = "https://widespread-plum-gffbkraoo7-deis8y3f44.edgeone.dev/channels4_profile.jpg"
}

android {
    namespace = "com.allinonelivetv"
    defaultConfig {
        consumerProguardFiles("proguard-rules.pro")
    }
}

afterEvaluate {
    tasks.findByName("make")?.apply {
        doLast {
            val cs3File = file("build/AllInOneLiveTV.cs3")
            if (cs3File.exists()) {
                val rootBuildsFile = rootProject.file("builds/${cs3File.name}")
                rootBuildsFile.parentFile.mkdirs()
                cs3File.copyTo(rootBuildsFile, overwrite = true)
                println("Updated cs3 file successfully copied to root builds: ${rootBuildsFile.absolutePath}")
            }
        }
    }
}
