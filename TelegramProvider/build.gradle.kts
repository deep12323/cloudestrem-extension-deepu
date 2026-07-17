import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

dependencies {
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}

// Use an integer for version numbers
version = 79

cloudstream {
    // All of these properties are optional, you can safely remove any of them.

    description = "Stream any media from your Telegram chats and channels on-demand"
    authors = listOf("Antigravity")

    /**
    * Status int as one of the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta-only
    * **/
    status = 1 // Will be 3 if unspecified

    tvTypes = listOf("Movie")

    requiresResources = false // Set to false since we build UI dynamically in Kotlin
    language = "en"

    // Telegram icon URL
    iconUrl = "https://upload.wikimedia.org/wikipedia/commons/8/82/Telegram_logo.svg"
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
    tasks.findByName("compileDex")?.apply {
        dependsOn("compileDebugJavaWithJavac")
        doFirst {
            val javaClassesDir = file("build/intermediates/javac/debug/compileDebugJavaWithJavac/classes")
            val kotlinClassesDir = file("build/tmp/kotlin-classes/debug")
            if (javaClassesDir.exists()) {
                println("Copying Java compiled classes from ${javaClassesDir.absolutePath} to ${kotlinClassesDir.absolutePath}...")
                javaClassesDir.copyRecursively(kotlinClassesDir, overwrite = true)
            }
        }
    }

    tasks.findByName("make")?.apply {
        doLast {
            val cs3File = file("build/TelegramProvider.cs3")
            if (cs3File.exists()) {
                println("Appending JNI libraries to ${cs3File.absolutePath}...")

                val tempFile = File(cs3File.parent, cs3File.name + ".tmp")
                val zipOutputStream = ZipOutputStream(tempFile.outputStream())
                val zipInputStream = ZipInputStream(cs3File.inputStream())

                var entry = zipInputStream.nextEntry
                while (entry != null) {
                    zipOutputStream.putNextEntry(ZipEntry(entry.name))
                    zipInputStream.copyTo(zipOutputStream)
                    zipOutputStream.closeEntry()
                    entry = zipInputStream.nextEntry
                }
                zipInputStream.close()

                val jniLibsDir = file("src/main/jniLibs")
                if (jniLibsDir.exists()) {
                    jniLibsDir.walkTopDown().filter { it.isFile }.forEach { file ->
                        val relativePath = file.relativeTo(jniLibsDir).path.replace('\\', '/')
                        val zipEntryName = "lib/$relativePath"
                        println("Adding to zip: $zipEntryName")
                        zipOutputStream.putNextEntry(ZipEntry(zipEntryName))
                        file.inputStream().use { it.copyTo(zipOutputStream) }
                        zipOutputStream.closeEntry()
                    }
                }

                zipOutputStream.close()
                cs3File.delete()
                tempFile.renameTo(cs3File)
                println("JNI libraries successfully appended to cs3 file!")

                // Copy the updated cs3 to the root builds/ directory
                val rootBuildsFile = rootProject.file("builds/${cs3File.name}")
                cs3File.copyTo(rootBuildsFile, overwrite = true)
                println("Updated cs3 file successfully copied to root builds: ${rootBuildsFile.absolutePath}")
            } else {
                println("WARNING: cs3 file not found at ${cs3File.absolutePath}")
            }
        }
    }
}