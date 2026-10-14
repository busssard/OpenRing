# Security and privacy

## What the app can and cannot do
* **No internet access.** The app does not request Android's internet permission, so Android itself
  blocks every network connection from it. Your health data cannot be sent anywhere by the app.
  A check in every build (`tests/check_privacy.sh`) fails if that permission or any networking,
  analytics, advertising or crash reporting code ever appears.
* **Bluetooth only to talk to your ring.** Location access is only requested on Android 11 and
  older, where Android requires it for Bluetooth scans; the app never reads your location.
* **Data stays on the phone** in the app's private storage. It leaves only when you export a file
  or save a backup yourself.
* **The ring key** is stored encrypted with a key from the Android Keystore.

## Checking a download
Every release is built by GitHub Actions from the public source, after the tests and the privacy
check passed. The release notes list the APK's SHA-256, the signing certificate and a link to the
exact build. You can verify the build provenance with the GitHub CLI:

```
gh attestation verify OpenRing-X.Y.apk -R busssard/OpenRing
```

All releases are signed with the same key. Android refuses to install an update over the app if it
was signed by anyone else.

## Reporting a problem
Please open a [private security advisory](https://github.com/busssard/OpenRing/security/advisories/new)
rather than a public issue for anything that could put users at risk.
