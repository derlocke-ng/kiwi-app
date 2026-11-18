# kiwi-app

The Android app of the Kiwi Network: what the control page and the kiwi-server desktop app do,
from a phone. It is a front end for the network's control node.

So far it joins a fleet with an invite's QR code or link, logs in, and shows: how the network
is, what needs a look, the machines and their state at the control node's last look, the
services, the control node's activity, and a machine's logins with their passwords (once the
control node hands those to a controller). It changes nothing yet.

The phone's key rests wrapped by the Android Keystore. The owner chooses what opens it, under
More: a password, fingerprint or face alone, the phone's screen lock, or several of them. The
app locks again after five minutes out of sight.

## Install

With [Obtainium](https://github.com/ImranR98/Obtainium): add
`https://github.com/derlocke-ng/kiwi-app` as an app. It installs the newest release and keeps
it up to date.

By hand, the newest release, signed with the release key:
<https://github.com/derlocke-ng/kiwi-app/releases/latest/download/kiwi-app.apk>

## Build

    container/gradlew assembleDebug lint test

That is `./gradlew` in a podman container with JDK 21 and the Android SDK (platform 37); the
first run builds the image, which accepts the Android SDK licence. With both installed on the
host, `./gradlew` does the same. `:core` holds what needs no Android and is tested alone:

    KIWI_BUILD_TARGET=jdk container/gradlew :core:test

Pictures of the screens with a made-up fleet, without a phone, into `app/build/pictures`:

    container/gradlew :app:testDebugUnitTest -Ppictures

## Releases

Every push to main is a release (`.github/workflows/release.yml`): `v0.1.<build>`, where the
build is the number of commits. The app's `versionName` is the tag without the `v`, its
`versionCode` the build, so each release installs over the one before. The first two parts are
`kiwi.version` in `gradle.properties`.

`container/gradlew` hands the same build number in, so a build of your own has the version of
the commit it is made from.

The workflow needs the release key in the repository's secrets, once:

    keytool -genkeypair -keystore release.jks -alias kiwi -keyalg RSA -keysize 4096 -validity 20000
    base64 -w0 release.jks | gh secret set KIWI_KEYSTORE
    gh secret set KIWI_KEYSTORE_PASSWORD
    gh secret set KIWI_KEY_PASSWORD
    gh secret set KIWI_KEY_ALIAS --body kiwi

Keep `release.jks` and its passwords somewhere safe, never in the repository: Android takes an
update only when it is signed with the same key.
