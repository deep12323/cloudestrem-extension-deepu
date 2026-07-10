# Telegram Cloudstream Plugin

A powerful, native [Cloudstream 3](https://github.com/recloudstream/cloudstream) plugin that transforms your Telegram account into an on-demand streaming service.

## 🚀 Features

- **Dual Provider System**: 
  - **TelegramProvider**: Browse and stream media directly from your configured custom Telegram channels.
  - **TeleflixProvider**: A beautiful Netflix-like Cinemeta catalogue showing worldwide trending Movies and TV Shows. When you click one, it automatically multi-searches your Telegram channels for the episodes and streams them instantly!
- **Progressive Streaming**: Stream large media files (movies, shows, videos) instantly without waiting for the entire file to download.
- **Zero Permanent Caching (Streaming-Only Mode)**: The plugin safely cleans up and instantly deletes chunks of the media file from your device the second you stop watching, completely preventing Telegram from eating up your phone's storage.
- **Smart Series Matching**: Advanced search algorithms that automatically translate official TV show episode metadata into Telegram channel naming conventions (e.g. converting S01E01 to 1x01, Season 1 Episode 1, etc.) to ensure a 99% match rate.
- **Custom Catalogue Channels**: Add your favorite Telegram channels (e.g. `@MyMovieChannel` or `-1001234567`) in the plugin settings to have them appear directly on your Cloudstream homepage!
- **Native Thumbnails & Metadata**: Automatically fetches official Telegram thumbnails for raw files, and uses TMDB/Cinemeta for gorgeous HD posters, backgrounds, and cast details.
- **Diagnostic Settings UI**: Easily configure your channels, authenticate, and monitor logs directly from the Cloudstream extensions page.
- **Auto-Updates**: Built with GitHub Actions to seamlessly deliver OTA updates directly to your Cloudstream app.

## ⚙️ Installation & Setup

1. **Install the Plugin Repo**: Open Cloudstream > Settings > Extensions > Add Repository, and paste this URL:
   ```text
   https://raw.githubusercontent.com/deepu2135/TestPlugins/builds/repo.json
   ```
2. **Download the Plugin**: Install the **Telegram** extension. This will automatically install two providers: `Telegram` and `Teleflix`.
3. **Authenticate**: Go to the extension settings (gear icon) and log into your Telegram account using your phone number or QR code.
4. **Add Channels**: In the settings, enter a comma-separated list of your favorite movie channels (e.g., `@movie_channel, @series_channel`).
5. **Watch**: Go back to the Cloudstream homepage! Use the `Teleflix` provider for a beautiful UI of trending movies/series, or use the `Telegram` provider to browse your raw channels directly!

## 🛠️ Architecture

This plugin runs a robust local proxy server inside Cloudstream. It uses the official native **TDLib (Telegram Database Library)** to communicate with Telegram's servers. 
- The proxy intercepts video requests and uses HTTP byte-range chunks to deliver progressive media to Cloudstream's video player.
- The built-in auto-cleaner constantly monitors socket connections to ensure media is instantly deleted when playback is closed.

## 📄 License

This repository is released into the public domain. You may use it however you want.
