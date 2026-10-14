#!/bin/sh
# Privacy guarantees that must never regress. Fails the build if any of them breaks.
#  1. The app does not ask for internet access, so Android itself blocks every network
#     connection: health data cannot leave the phone through the app.
#  2. No networking code or third party SDKs (analytics, crash reporters, ads) in the source.
#  3. The built APK (when present) also lacks the permission, checked in the binary itself.
set -e
cd "$(dirname "$0")/.."
fail=0
MANIFEST=app/src/main/AndroidManifest.xml
if grep -q 'android.permission.INTERNET\|ACCESS_NETWORK_STATE' "$MANIFEST"; then
  echo "FAIL  the manifest asks for network access"; fail=1
else
  echo "ok    no internet permission in the manifest"
fi
if grep -rlE 'java\.net\.(URL|Socket|HttpURLConnection)|javax\.net|okhttp|retrofit|firebase|crashlytics|sentry|admob|analytics' app/src/main/java; then
  echo "FAIL  networking or tracking code found in the files above"; fail=1
else
  echo "ok    no networking, analytics, ads or crash reporting code"
fi
if [ -f build/OpenRing.apk ]; then
  AAPT=${AAPT:-$(command -v aapt || ls "${ANDROID_HOME:-/nonexistent}"/build-tools/*/aapt 2>/dev/null | tail -1)}
  if [ -n "$AAPT" ]; then
    perms=$("$AAPT" dump permissions build/OpenRing.apk)
    if echo "$perms" | grep -q 'android.permission.INTERNET'; then
      echo "FAIL  the built APK asks for internet access"; fail=1
    else
      echo "ok    the built APK has no internet permission:"
      echo "$perms" | sed -n "s/.*name='\([^']*\)'.*/        \1/p"
    fi
  fi
fi
exit $fail
