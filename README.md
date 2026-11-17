# IKEv2 Manager — Android MVP

**Version:** 0.1.1-build-fix  
**Platform:** Android 13+ (API 33+)  
**Language/UI:** Kotlin + Jetpack Compose  
**VPN engine in this build:** Android platform IKEv2 (`VpnManager`)

A small, local-first Android app for importing IKEv2 gateway hostnames, running a quick DNS/ICMP screen, and testing real IKEv2 connections sequentially against the best candidates.

## Current MVP features

- Paste a list of hostnames or import TXT/CSV.
- Deduplicate and validate hostname-like entries.
- Resolve DNS and run a short ICMP ping scan with bounded concurrency.
- Sort DNS-resolved hosts by ICMP response time; hosts that do not answer ICMP are not automatically treated as dead.
- Store the username/password encrypted with an AES-GCM key held by Android Keystore.
- Provision an Android platform IKEv2 profile using EAP-MSCHAPv2.
- Request Android VPN consent when required.
- Test up to five best candidates sequentially, wait for the platform VPN state, measure tunnel setup time, and try an HTTPS check while the VPN is active.
- Stop the test tunnel after each candidate. A manually selected connection remains active until disconnected or replaced.
- Offer a per-server strongSwan `.sswan` profile handoff after a native failure.

## Important limitations

1. **Native authentication is EAP-MSCHAPv2 only.** Android's `Ikev2VpnProfile.Builder.setAuthUsernamePassword()` configures EAP-MSCHAPv2. If the provider uses EAP-MD5, EAP-GTC, certificates, a custom server identity, or non-default IKE/IPsec proposals, this native path may fail even with correct credentials.
2. **The strongSwan fallback is an external-app handoff, not an embedded engine in this alpha.** After a native failure, the app can create a standards-compatible `.sswan` profile and offer it to the installed strongSwan app. A fully embedded strongSwan engine requires its native libraries, Android NDK/JNI integration, and additional testing. The exported profile includes the password in cleartext because the strongSwan import format is not encrypted; delete the exported file from Downloads after importing it.
3. **ICMP is only a first-pass signal.** Some gateways block ping. DNS success plus no ICMP reply is not proof that IKEv2 is unavailable.
4. **The IKEv2 test temporarily takes over the device VPN.** Disconnect any other VPN before running the real connection test. The app tests one gateway at a time and tears down each test tunnel.
5. **Server identity defaults to the hostname.** If the provider gives a separate IKE server identity/FQDN, that needs to be added as an advanced field before using such gateways.
6. The HTTPS check measures a request made while the Android VPN transport is active. It is a useful tunnel check, not a standardized speed test.
7. This source project has not been compiled or exercised on a physical device in the generation environment. An APK produced by CI is a build artifact, not proof that a specific provider's server, certificate identity, or EAP configuration is compatible. Verify the imported profile on the phone before relying on it for connectivity.

## Build without Android Studio (GitHub Actions)

This repository includes `.github/workflows/build-apk.yml`. To build in GitHub's cloud:

1. Create a new repository on GitHub (a private repository is recommended because your server list and future configuration may be sensitive).
2. Upload the contents of this `IKEv2Manager` folder to the repository root. Keep the `.github/workflows/build-apk.yml` path intact.
3. Open the repository's **Actions** tab and enable GitHub Actions if prompted.
4. Push to the `main` branch, or open **Actions → Build Android APK → Run workflow**.
5. When the run finishes successfully, open that run and download the `IKEv2Manager-debug-APK` artifact.
6. Extract the downloaded artifact ZIP; it contains `app-debug.apk`. Transfer it to the Android phone and install it. You may need to allow installation from the file manager you use.

GitHub Actions uses `android-actions/setup-android@v4` (the current action generation) and explicitly installs Android SDK Platform 35 and Build-Tools 35. The workflow does not request the obsolete `tools` SDK package, which Google no longer serves. It also installs JDK 17 and Gradle 8.9 in the cloud. No Android Studio, local Android SDK, or local Gradle installation is required. The workflow creates a **debug APK**, not a Play Store release build.

The first Android VPN start may display a system permission dialog. Approve it to let the app create platform IKEv2 sessions.

## Local build (optional)

If you later want to build locally, use Android Studio with JDK 17 and Android SDK Platform 35, or use the included Gradle bootstrap script with a configured Android SDK.

## Input format

One hostname or IP address per line. Lines beginning with `#` are ignored. CSV rows are accepted by taking the first comma-separated field.

```text
# Example only — replace with your provider's gateways
de1.example.net
nl1.example.net
fr1.example.net
```

## Security notes

- Credentials are encrypted locally with AES-GCM; the AES key is non-exportable in Android Keystore.
- Android backup is disabled so the encrypted credential blob is not copied through app backup.
- Credentials are not sent to any third-party service by this app.
- Do not share diagnostic screenshots that expose your server list or account identifier.

## Next milestones

- Add explicit server identity / remote ID and optional CA certificate settings.
- Implement an embedded strongSwan engine adapter (NDK/JNI) for EAP methods and proposals not supported by Android's platform profile API.
- Add UDP/IKE_SA_INIT probing as a more meaningful pre-filter than ICMP alone.
- Add CSV export, history, repeatability metrics, and configurable top-N testing.
- Add a Windows companion tool after the Android engine and result model are validated.

## Build helper

`gradlew` and `gradlew.bat` are lightweight bootstrap scripts. On first use they download the official Gradle 8.9 distribution into the user's Gradle cache, then run the build. Android Studio can also use its configured Gradle/JDK installation.
