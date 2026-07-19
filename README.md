# Cloudstream Extensions Repo

A collection of powerful, native [Cloudstream 3](https://github.com/recloudstream/cloudstream) plugins designed to transform your personal cloud accounts into on-demand streaming services.

## 🔌 Available Plugins

### 1. Telegram & Teleflix Provider
A plugin that streams media directly from your configured custom Telegram channels without downloading the files permanently.
* **Teleflix**: A beautiful Netflix-like Cinemeta catalogue showing worldwide trending Movies and TV Shows, which multi-searches your Telegram channels for streams.
* **Telegram**: Browse and stream media directly from your raw Telegram channels.

### 2. Google Drive Provider
A fully-featured plugin to stream videos and audio directly from your personal Google Drive and Shared Drives!
* **Recursive Folder Scanning**: Flattens nested folders into a single episode list for easy binge-watching.
* **Media-Only Filtering**: Automatically hides documents and zip files, only showing valid videos and audio files.
* **Dynamic Posters**: Automatically uses the first video's thumbnail as the cover art for folders.
* **Shared Drive Support**: Full access to your Shared Drives.

---

## ⚙️ Installation & Setup

1. **Install the Plugin Repo**: Open Cloudstream > Settings > Extensions > Add Repository, and paste this URL:
   ```text
   https://raw.githubusercontent.com/deepu2135/cloudestrem-extension-deepu/builds/repo.json
   ```
2. **Download the Plugins**: Install the **Telegram** or **Google Drive** extension from the repository list.

---

## 📁 Google Drive Configuration Guide

To use the Google Drive plugin, you must configure it with your own Google Cloud API credentials. Since you are accessing your personal drive, you need your own `Client ID`, `Client Secret`, and `Refresh Token`.

### Step 1: Create Google Cloud Credentials
1. Go to the [Google Cloud Console](https://console.cloud.google.com/) and create a new project.
2. Navigate to **APIs & Services > Library**, search for **Google Drive API**, and click **Enable**.
3. Go to **APIs & Services > OAuth consent screen**:
   - Choose **External** user type and create.
   - Fill in the required App information (names and emails).
   - Under **Test users**, add your personal Google email address.
4. Go to **APIs & Services > Credentials**:
   - Click **Create Credentials > OAuth client ID**.
   - Select **Web application** as the application type.
   - Add the following **Authorized redirect URI**: `https://developers.google.com/oauthplayground`
   - Click Create and copy your **Client ID** and **Client Secret**.

### Step 2: Generate a Refresh Token
1. Go to the [Google OAuth 2.0 Playground](https://developers.google.com/oauthplayground/).
2. Click the **Gear Icon (Settings)** on the top right corner.
3. Check **"Use your own OAuth credentials"**.
4. Paste your **OAuth Client ID** and **OAuth Client secret**.
5. Close the settings. On the left sidebar under "Step 1", scroll down to **Drive API v3** and check `https://www.googleapis.com/auth/drive.readonly`.
6. Click **Authorize APIs** and sign into your Google account (ignore the "unverified app" warning, it's your own app).
7. Under "Step 2", click **"Exchange authorization code for tokens"**.
8. Copy your generated **Refresh token**.

### Step 3: Add to Cloudstream
1. Open Cloudstream and click the Gear Icon next to the Google Drive plugin.
2. Paste your **Client ID**, **Client Secret**, and **Refresh Token** into the settings.
3. You're done! Return to the home page and your Google Drive will load instantly.

---

## ✈️ Telegram Configuration Guide

1. **Authenticate**: Go to the extension settings (gear icon) and log into your Telegram account using your phone number or QR code.
2. **Add Channels**: In the settings, enter a comma-separated list of your favorite movie channels (e.g., `@movie_channel, @series_channel`).
3. **Watch**: Go back to the Cloudstream homepage! Use the `Teleflix` provider for a beautiful UI of trending movies/series, or use the `Telegram` provider to browse your raw channels directly!

---

## 💡 Troubleshooting Playback Issues

If a video fails to play, buffers endlessly, or has no audio, it is likely encoded in a format that Cloudstream's built-in ExoPlayer struggles to decode natively (e.g. heavy HEVC/x265 `.mkv` files).
**Fix**: Simply tap the **"Play in external player"** icon in Cloudstream and choose a robust external player like **MPV (or MPVEX)** or **VLC**—they have much broader codec support and will play the stream flawlessly!

---

## 📄 License
This repository is released into the public domain. You may use it however you want.
