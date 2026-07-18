import java.io.File

dependencies {
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}

version = 2

cloudstream {
    description = "Stream video and audio from your Google Drive"
    authors = listOf("deepu2135")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    requiresResources = false
    language = "en"
    iconUrl = "https://raw.githubusercontent.com/deepu2135/cloudestrem-extension-deepu/master/google_drive_icon.png"
}

android {
    defaultConfig {
        consumerProguardFiles("proguard-rules.pro")
    }
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}

afterEvaluate {
    tasks.findByName("make")?.apply {
        doLast {
            val cs3File = file("build/GoogleDriveProvider.cs3")
            if (cs3File.exists()) {
                val rootBuildsFile = rootProject.file("builds/${cs3File.name}")
                rootBuildsFile.parentFile.mkdirs()
                cs3File.copyTo(rootBuildsFile, overwrite = true)
                println("Updated cs3 file successfully copied to root builds: ${rootBuildsFile.absolutePath}")
            }
        }
    }
}
