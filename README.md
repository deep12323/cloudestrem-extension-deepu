# Cloudstream Extensions Repo

A collection of powerful, native [Cloudstream 3](https://github.com/recloudstream/cloudstream) plugins designed to transform your personal cloud accounts and torrents into on-demand streaming services.

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

### 3. TorrServe Provider & Engine Tuner
A native BitTorrent streaming engine and buffer management utility integrated directly into Cloudstream.
* **Instant BitTorrent Streaming**: Search or paste any magnet link, `.torrent` file, or info hash to stream video content immediately over local HTTP without waiting for full downloads.
* **⚡ 0% Preload (Instant Start)**: Break free from standard CloudStream 50% pre-buffering! Set preload buffer down to `0%` to start video playback immediately as the first chunks arrive.
* **⏩ Forward Read-Ahead Customization**: Tune the forward read-ahead window (up to 95–100%) to cushion against network spikes and ensure seamless seeking.
* **💾 Memory & Disk Cache Modes**: Adjust cache from 32MB up to 1GB+; choose between RAM for ultra-fast seeking or Disk storage to safeguard low-memory devices (Android TV / FireStick).
* **🚫 Seeding Control (Leech Only)**: Toggle off background uploading to conserve mobile data, reduce battery consumption, and prevent bandwidth saturation.
* **🤖 Auto-Applying Settings Daemon**: Custom buffer size, preload %, lookahead %, and upload rules persist permanently across app restarts and are auto-applied on cold boot and stream initialization.
* **Multi-Episode & Subtitles**: Automatically unpacks multi-file torrents into episodes and extracts external/embedded subtitles on the fly.

---

## ⚙️ Installation & Setup

1. **Install the Plugin Repo**: Open Cloudstream > Settings > Extensions > Add Repository, and paste this URL:
   ```text
   https://raw.githubusercontent.com/deep12323/cloudestrem-extension-deepu/builds/repo.json
   ```
2. **Download the Plugins**: Install the **TorrServe**, **Telegram**, or **Google Drive** extension from the repository list.

---

## 🎙️ Dialogue Boost Configuration Guide

A dedicated audio enhancement extension that brings **always-on dynamic range compression** to Cloudstream:

1. **Install Dialogue Boost**: Install the `Dialogue Boost` plugin from this repository in Cloudstream.
2. **Access Settings**: Tap the gear icon next to `Dialogue Boost` under **Settings > Extensions**.
3. **Features & Presets**:
   - **Always Enable Compressor**: Forces dynamic range compression to activate automatically for every video from the first frame.
   - **🎬 Dialogue Boost (Recommended)**: -24 dB threshold, +12 dB makeup gain, 8:1 ratio — dramatically amplifies quiet whispers and dialogues while transparently leveling loud scenes.
   - **🌙 Night Mode / Action Limiter**: -30 dB threshold, +16 dB makeup gain, 12:1 ratio — heavy compression preventing volume spikes during late-night viewing.
   - **🔈 Light**: -18 dB threshold, +4 dB makeup gain, 4:1 ratio — subtle leveling tailored for headphones.
   - **🎛️ Custom Tuning**: Fine-tune threshold and makeup gain sliders to your personal preference.

---

## ⚡ TorrServe Configuration Guide

1. **Install TorrServe**: Install the `TorrServe` plugin from this repository in Cloudstream.
2. **Access Settings**: Click the gear icon next to `TorrServe` under Settings > Extensions.
3. **Choose a Profile or Customize**:
   - **⚡ Instant Start (0%)**: 128MB buffer, 0% preload, 95% lookahead, leech-only — starts streaming instantly.
   - **📺 FireStick / TV**: 48MB buffer, 15% preload, 85% lookahead, disk cache enabled — optimal for devices with low RAM.
   - **🎬 4K Cinema**: 384MB buffer, 15% preload, 95% lookahead, RAM cache — optimal buffer cushion for high bitrate movies.
   - **Custom Tuning**: Adjust stream buffer size, initial preload buffer (0–70%), forward read-ahead window (0–100%), and disable/enable upload seeding.
4. **Automatic Persistence**: Settings saved here are automatically maintained and applied by the background daemon whenever Cloudstream launches or a torrent stream starts.

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

### Step 2: Add to Cloudstream
1. Open Cloudstream and click the Gear Icon next to the Google Drive plugin.
2. Paste your **Client ID** and **Client Secret** into the settings.
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
