#!/bin/bash
sed -i '/override var mainUrl/a \    override var logo = "https://upload.wikimedia.org/wikipedia/commons/8/82/Telegram_logo.svg"' /root/cloudestrem-extension-deepu/TelegramProvider/src/main/kotlin/com/telegram/TelegramProvider.kt
./gradlew assembleDebug
