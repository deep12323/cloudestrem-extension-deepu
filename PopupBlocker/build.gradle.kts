import java.io.File

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
}

version = 3

cloudstream {
    description = "Universal shield that suppresses and blocks donation popups, announcement modals, and support dialogs across all CloudStream extensions (AllForU, CNCVerse, Raghav, Phisher Repo, etc.)"
    authors = listOf("deepu2135")
    status = 1
    tvTypes = listOf("Others")
    requiresResources = false
    language = "en"
    iconUrl = "https://raw.githubusercontent.com/google/material-design-icons/master/png/action/shield/materialicons/48dp/2x/baseline_shield_white_48dp.png"
}

android {
    namespace = "com.cleanstream.blocker"
    defaultConfig {
        consumerProguardFiles("proguard-rules.pro")
    }
}

afterEvaluate {
    tasks.findByName("make")?.apply {
        doLast {
            val cs3File = file("build/PopupBlocker.cs3")
            if (cs3File.exists()) {
                val rootBuildsFile = rootProject.file("builds/${cs3File.name}")
                rootBuildsFile.parentFile.mkdirs()
                cs3File.copyTo(rootBuildsFile, overwrite = true)
                println("Updated cs3 file successfully copied to root builds: ${rootBuildsFile.absolutePath}")
            }
        }
    }
}
