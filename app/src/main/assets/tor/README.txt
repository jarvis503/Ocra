IMPORTANT: You need to add the Tor binary for your target ABI.
Download from: https://github.com/nickaein/tor-binary-android/releases
Or from: https://github.com/nickaein/tor-builds
Place the binary as:
- tor.arm64-v8a (for 64-bit ARM — covers most modern Android devices)
- tor.armeabi-v7a (for 32-bit ARM — covers Lenovo Tab M10 HD)
- tor.x86_64 (for x86 emulators)
The app will auto-detect the correct binary on startup.
Alternative: Instead of bundling the binary, the app can download it on first Tor use.
This keeps the APK size smaller.

Note on Orca Browser's default embedded runtime:
app/build.gradle.kts includes info.guardianproject:tor-android:0.4.8.16.1 (libtor.so + TorService),
info.guardianproject:jtorctl:0.4.5.7, and com.netzarchitekten:IPtProxy:5.7.0 (obfs4, meek_lite, snowflake).
EmbeddedTorManager uses the bundled JNI TorService runtime automatically so Tor and bridges work
out of the box with no external Orbot app; if you place tor.{abi} files in this directory,
EmbeddedTorManager will also detect and extract them via extractTorBinary(context).
