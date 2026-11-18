# kiwi-app

The Android app of the Kiwi Network: what the control page and the kiwi-server desktop app do,
from a phone. It is a front end for the network's control node.

So far it joins a fleet with an invite link and logs in; the four screens are still empty. The
phone's key rests wrapped by the Android Keystore and opens with the screen lock.

## Build

    container/gradlew assembleDebug lint test

That is `./gradlew` in a podman container with JDK 21 and the Android SDK (platform 37); the
first run builds the image, which accepts the Android SDK licence. With both installed on the
host, `./gradlew` does the same. `:core` holds what needs no Android and is tested alone:

    KIWI_BUILD_TARGET=jdk container/gradlew :core:test
