# Cloudstream Extensions Repo (Telegram & Google Drive)

A collection of powerful, native [CloudStream 3](https://github.com/recloudstream/cloudstream) plugins designed to transform your personal cloud accounts and Telegram feeds into on-demand streaming services.

> [!NOTE]
> Additional utility and streaming plugins (TorrServe, ExternalPlayers, SkipTorrentWarning, PopupBlocker, H2RxDMovies, AllInOneLiveTV, MoviesDrive, NextUp) have been moved to their dedicated repository:
> 👉 [deep12323/cloudstream-plugins](https://github.com/deep12323/cloudstream-plugins)

## 🔌 Available Plugins

### 1. Telegram & Teleflix Provider
A powerful plugin that streams media directly from your Telegram channels on-demand without downloading files permanently.
* **Teleflix**: A Netflix-style Cinemeta catalogue displaying 20 trending categories (Popular Movies, Popular TV Shows, Featured Top Rated, 2026 Releases, and Action, Sci-Fi, Comedy, Horror, Animation, Thriller, Romance, & Documentary genres), which multi-searches your Telegram channels for streams.
* **Telegram**: Browse and stream media directly from your raw Telegram channels and topic feeds.
* **📦 ZIP File Streaming**: Stream uncompressed video entries directly from inside `.zip` files (including Zip64 files larger than 4GB) on-demand!
* **🔗 Multi-Part Split File Virtual Merging**: Automatically detects and groups split files (`.001`, `.part1`, `.z01`, etc.) into a single unified card. Virtual HTTP byte-range streaming seamlessly stitches parts together during playback across file boundaries!

### 2. Google Drive Provider
A fully-featured plugin to stream videos and audio directly from your personal Google Drive and Shared Drives!
* **Recursive Folder Scanning**: Flattens nested folders into a single episode list for easy binge-watching.
* **Media-Only Filtering**: Automatically hides documents and zip files, only showing valid videos and audio files.
* **Dynamic Posters**: Automatically uses the first video's thumbnail as the cover art for folders.
* **Shared Drive Support**: Full access to your Shared Drives.

---

## ⚙️ Installation & Setup

1. **Install the Plugin Repo**: Open CloudStream > **Settings** > **Extensions** > **Add Repository**, and paste this URL:
   ```text
   https://raw.githubusercontent.com/deep12323/cloudestrem-extension-deepu/builds/repo.json
   ```
2. **Download the Plugins**: Install the **Telegram** or **Google Drive** extension from the repository list.

---

## 📁 Google Drive Configuration Guide

To use the Google Drive plugin, you must configure it with your own Google Cloud API credentials. Since you are accessing your personal drive, you need your own `Client ID` and `Client Secret`.

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

### Step 2: Add to CloudStream
1. Open CloudStream and click the Gear Icon next to the Google Drive plugin.
2. Paste your **Client ID** and **Client Secret** into the settings.
3. You're done! Return to the home page and your Google Drive will load instantly.

---

## ✈️ Telegram Configuration Guide

1. **Authenticate**: Go to the extension settings (gear icon) and log into your Telegram account using your phone number or QR code.
2. **Add Channels**: In the settings, enter a comma-separated list of your favorite movie channels (e.g., `@movie_channel, @series_channel`).
3. **Watch**: Go back to the CloudStream homepage! Use the `Teleflix` provider for a beautiful UI of trending movies/series, or use the `Telegram` provider to browse your raw channels directly!
