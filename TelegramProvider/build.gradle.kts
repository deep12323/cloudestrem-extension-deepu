dependencies {
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}

// Use an integer for version numbers
version = 1

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
    **/
    status = 1 // Will be 3 if unspecified

    tvTypes = listOf("Movie")

    requiresResources = false // Set to false since we build UI dynamically in Kotlin
    language = "en"

    // Telegram icon URL
    iconUrl = "https://upload.wikimedia.org/wikipedia/commons/8/82/Telegram_logo.svg"
}

android {
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}