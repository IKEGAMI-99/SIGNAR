# SIGNAR 📡

**See the Invisible.** Android Wi-Fi / BLE / Bluetooth signal explorer, radar and experimental ARCore radio-source overlay.

## Features
- Wi-Fi APs: BSSID, SSID, frequency, RSSI (subject to Android scan throttling)
- BLE scan and classic Bluetooth discovery (only discoverable/transmitting devices)
- Radar with transparent position uncertainty, not fabricated bearings
- Pedestrian dead reckoning from rotation vector and step detection, with accelerometer fallback
- Optional ARCore camera preview. ARCore position is used to collect radio signal measurements; estimated positions are overlaid in the camera.
- Spatial location is experimental. RSSI multipath, floors and walls can create errors far larger than the displayed model radius. An estimate is omitted until spatially separated observations exist.
- Checks GitHub Releases, downloads only **signar.apk**, validates GitHub's SHA-256 release asset digest **and** the installed app's package signing certificate before launching Android's system installer.
- Foreground-only discovery. No remote telemetry, no server backend.

## Developer builds
JDK 17 and Android SDK 35, Gradle 8.9 required.

```sh
gradle :app:assembleDebug
```

The GitHub Action on `main` builds and uploads `SIGNAR-debug` as a workflow artifact. Debug has a different package ID and signing identity; **do not use the debug APK for self-updating**.

## Fixed signing: set up ONCE, keep the key for life
Do **not** commit the signing keystore or credentials. To generate your own key locally:

```sh
keytool -genkeypair -v -keystore signar-release.jks -storetype PKCS12 \
  -alias signar -keyalg RSA -keysize 3072 -validity 10000
base64 -w 0 signar-release.jks > signar-release.b64  # Linux
# macOS: base64 < signar-release.jks | tr -d '\n' > signar-release.b64
```

Repository Settings → Secrets and variables → Actions: add these **four** repository secrets:
- `SIGNING_KEYSTORE_BASE64`: one-line contents of signar-release.b64
- `SIGNING_STORE_PASSWORD`: keystore password
- `SIGNING_KEY_PASSWORD`: key password (can be same)
- `SIGNING_KEY_ALIAS`: signar

**Back up the keystore and passwords privately.** If the key is lost or replaced, Android will reject updates of an installed SIGNAR. GitHub does not expose those secrets to the app or release artifacts.

## Publish a release (after the secrets exist)
```sh
git tag v0.1.0
git push origin v0.1.0
```
The workflow builds with the **same permanent signing key**, uploads `signar.apk` and its SHA-256 file to the GitHub Release. Future tags `v0.1.1`, `v0.2.0`, etc. automatically increase the Android versionCode derived from the semantic version. Do not reuse/rewrite release tags. Keep one stable `applicationId` (`com.ikegami99.signar`).

Install the **release APK**, grant nearby devices/location permissions, and separately grant camera permission for AR. For updates: `更新 → アップデートを確認 → ダウンロードして更新`. Android will request "install unknown apps" permission once and show the normal install confirmation. Silent install is not available to ordinary apps.

## Technical limitations
- Android OS Wi-Fi scan throttle: typically up to four foreground scans in two minutes.
- RSSI is **not** direction-of-arrival data; devices do not have a measured bearing, and estimates need multi-position samples.
- Step-length model is approximate (0.72m/step), and PDR drifts. ARCore may improve camera position tracking on supported phones.
- ARCore is optional; unsupported devices retain the scanner/radar UI.
- Radio-source altitude is unobservable from flat-floor measurements and drawn at measurement height.
- Only discoverable or transmitting Bluetooth devices, and visible Wi-Fi access points, can be found.
- No privileged monitoring of Wi-Fi clients; no MAC spoofing or active network intrusion.

The app is built for personal use. Please follow local privacy rules when scanning third-party devices.
