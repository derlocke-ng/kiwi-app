# kiwi-app

The Android app of the Kiwi Network: what the control page and the kiwi-server desktop app do,
from a phone. It is a front end for the network's control node.

So far it joins a fleet with an invite link and logs in; the four screens are still empty.

The phone's key rests wrapped by the Android Keystore. The owner chooses what opens it, under
More: a password, fingerprint or face alone, the phone's screen lock, or several of them. The
app locks again after five minutes out of sight.

## Install

The newest build of main, signed with the release key:
<https://github.com/derlocke-ng/kiwi-app/releases/download/latest/kiwi-app.apk>

## Build

    container/gradlew assembleDebug lint test

That is `./gradlew` in a podman container with JDK 21 and the Android SDK (platform 37); the
first run builds the image, which accepts the Android SDK licence. With both installed on the
host, `./gradlew` does the same. `:core` holds what needs no Android and is tested alone:

    KIWI_BUILD_TARGET=jdk container/gradlew :core:test

## Releases

`.github/workflows/release.yml` builds a signed APK: every push to main replaces the
pre-release `latest`, a tag `v*` makes a release of its own. It needs the release key in the
repository's secrets, once:

    keytool -genkeypair -keystore release.jks -alias kiwi -keyalg RSA -keysize 4096 -validity 20000
    base64 -w0 release.jks | gh secret set KIWI_KEYSTORE
    gh secret set KIWI_KEYSTORE_PASSWORD
    gh secret set KIWI_KEY_PASSWORD
    gh secret set KIWI_KEY_ALIAS --body kiwi

Keep `release.jks` and its passwords somewhere safe, never in the repository: Android takes an
update only when it is signed with the same key.
