# 🦷 Orca Browser

A lightweight, AMOLED-black Android browser built for low-RAM tablets.
Optimized for Lenovo Tab M10 HD (2GB RAM).

## Features
- 🏴 True AMOLED black UI inspired by iPad Safari
- 🛡️ Multi-layer ad blocker (hosts, URL rules, YouTube & cosmetic filtering) + redirect prompt
- 🕶️ Normal, Incognito & self-contained Tor browsing modes (with custom bridge support)
- 💤 Automatic tab hibernation (3-min inactive)
- 📱 Session persistence (Normal mode)
- 🔐 Seamless Google sign-in support
- 🖥️ Desktop/Mobile mode toggle
- ⚡ One-tap JavaScript toggle
- 🔍 Find in Page
- 📏 Optimized for tablets (landscape + portrait)

## Build
Push to GitHub and the Actions workflow will build the APK automatically.
Or build locally: `./gradlew assembleDebug`

## Requirements
- Android 7.0+ (API 24+, built against SDK 34)
- Embedded Tor (`tor-android`, `jtorctl`, `IPtProxy`) is bundled directly — no Orbot required
