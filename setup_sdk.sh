#!/bin/bash
set -e

SDK_DIR="$HOME/android-sdk"
mkdir -p "$SDK_DIR"

echo "Downloading Android Command Line Tools..."
wget -q --show-progress https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O cmdline-tools.zip

echo "Extracting tools..."
mkdir -p "$SDK_DIR/cmdline-tools"
unzip -q cmdline-tools.zip -d "$SDK_DIR/cmdline-tools"
rm cmdline-tools.zip

# The tool unzip puts everything in cmdline-tools/cmdline-tools.
# We need to organize it under cmdline-tools/latest for sdkmanager to work properly.
mkdir -p "$SDK_DIR/cmdline-tools/latest"
mv "$SDK_DIR/cmdline-tools/cmdline-tools/"* "$SDK_DIR/cmdline-tools/latest/"
rm -rf "$SDK_DIR/cmdline-tools/cmdline-tools"

echo "Accepting licenses..."
yes | "$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" --licenses

echo "Installing platforms and build-tools (Android 35)..."
"$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" "platforms;android-35" "build-tools;35.0.0" "platform-tools"

echo "Configuring local.properties..."
echo "sdk.dir=$SDK_DIR" > "$(pwd)/local.properties"

echo "Android SDK setup completed successfully!"
