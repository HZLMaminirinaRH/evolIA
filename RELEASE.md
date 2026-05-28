# evolIA v0.2.1 Release Guide

## Pre-release Checklist

- [x] CLAUDE.md updated with monetary policy design principles
- [x] Feature implementation complete (Transfer, Receive, guide, sanitization)
- [x] Localization in English, French, Malagasy
- [x] Version bumped to 0.2.1 (versionCode 6)
- [x] F-Droid metadata created in `fastlane/metadata/android/`

## Building the APK

Since the Android SDK is not available in CI, build locally in Android Studio:

1. **Pull the latest from the release branch:**
   ```bash
   git fetch origin claude/claude-md-docs-f9aQz
   git checkout claude/claude-md-docs-f9aQz
   ```

2. **Open Android Studio:**
   - File → Open → navigate to `/android/`

3. **Build the unsigned release APK:**
   - Build → Build Bundle(s) / APK(s) → Build APK(s)
   - Or: Shift+Ctrl+A and search "Build APK"
   - Output: `android/app/build/outputs/apk/release/app-release-unsigned.apk`

4. **For F-Droid submission:**
   - F-Droid will sign the APK with their own key
   - Submit `app-release-unsigned.apk` (NOT a pre-signed APK)
   - F-Droid metadata is in `fastlane/metadata/android/` (en-US, fr, mg locales)

## F-Droid Submission

1. **Gather submission files:**
   - APK: `android/app/build/outputs/apk/release/app-release-unsigned.apk`
   - Changelog: Already in `fastlane/metadata/android/*/whatsnew.txt`
   - Description: Already in `fastlane/metadata/android/*/full_description.txt`
   - Short description: Already in `fastlane/metadata/android/*/short_description.txt`

2. **Create GitLab MR on F-Droid repo:**
   - Fork or clone: https://gitlab.com/fdroid/fdroiddata
   - Add/update metadata in `metadata/com.evolia.app.yml`:
     ```yaml
     Categories:
       - System
       - Internet
       - Communication
     License: AGPL-3.0-only
     SourceCode: https://github.com/HZLMaminirinaRH/evolIA
     IssueTracker: https://github.com/HZLMaminirinaRH/evolIA/issues
     ```
   - Add release entry in `builds:` with git tag/commit
   - F-Droid CI will build and sign automatically

3. **Overwriting previous F-Droid release:**
   - Use the same `com.evolia.app` package ID (✓ already consistent)
   - F-Droid will detect new versionCode (6) and update automatically
   - No separate submission needed for patch versions on same package

## IzzyOnDroid Submission

IzzyOnDroid mirrors F-Droid but can include unsigned APKs. Options:

- **Option A (recommended):** Use the same F-Droid metadata + the built APK
  - IzzyOnDroid accepts F-Droid format
  - Submit via https://gitlab.com/IzzyOnDroid/repo

- **Option B:** Host APK directly on GitHub Releases
  - Tag: `v0.2.1`
  - Attach `app-release-unsigned.apk` to release notes

## Version Info

- **versionCode:** 6
- **versionName:** 0.2.1
- **minSdk:** 26 (Android 8.0)
- **targetSdk:** 34 (Android 14)
- **Signing:** Debug key included (debug.keystore, standard Android credentials)

## What's New in v0.2.1

### New Features
- **Transfer button:** Send earned BTC-e to peers with owner-auth gating and on-chain settlement
- **Receive button:** Share address for incoming BTC-e with automatic balance updates
- **Exchange guide:** Step-by-step explanation of RPC, gas, and external funding
- **Anti-linkification:** Chat rendering is inert; no auto-links, no text injection

### Improvements
- Clipboard-only sharing (removed external intents)
- Chat sanitization (control chars and bidi-spoof characters stripped)
- Cursive italic font on chat log
- Copyright footer on all screens

### Localization
- English (en-US)
- French (fr)
- Malagasy (mg)

## Testing Locally

Before submission, test on a real device or emulator:

```bash
adb install android/app/build/outputs/apk/debug/app-debug.apk
```

Test checklist:
- [ ] Transfer flow (auth → enter recipient → confirm → on-chain settlement)
- [ ] Receive flow (see address, copy to clipboard)
- [ ] Guide opens from dashboard (*)
- [ ] Guide renders correctly in all 3 locales
- [ ] Chat is inert (no auto-links)
- [ ] Clipboard copy works (Receive, chat identity)
- [ ] Copyright footer visible on all screens

## Notes

- The Rust, Go, and Python services run on Termux (not on Android app directly for v0.2.1)
- The Android app supervises the Go mesh-sync binary and provides UI for on-chain operations
- All on-chain operations require a configured RPC endpoint (evolia_chain_config.json)
- BTC-e transfers are stored locally in evolia_transfer_history.jsonl and synced to peers

## Next Steps

After v0.2.1 ships:
- Monitor F-Droid and IzzyOnDroid mirrors for successful builds
- Collect user feedback on the guide and transfer UX
- Plan Bluetooth Classic RFCOMM phase (Phase 2, in-progress)
