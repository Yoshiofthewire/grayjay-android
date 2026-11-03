# Grayjay for Android TV (unofficial)

A lean-back interface for [Grayjay](https://grayjay.app) on Android TV and Google TV, driven entirely by the
remote, with a phone page for anything that needs typing.

> **Unofficial, modified build.** This is a modified version of Grayjay by FUTO Holdings, Inc. It is not made,
> reviewed or endorsed by FUTO. It is distributed free of charge for non-commercial use under the
> [Source First License 1.1](LICENSE.md), the same terms as Grayjay itself. The changes are listed under
> [What this fork changes](#what-this-fork-changes).

## What you get

- **Home** with an *Across your sources* row that mixes every source you use, then one shelf per source.
  *Continue watching* appears when you have unfinished videos.
- **Subscriptions** with the newest videos first, a shelf per source, and the creators you follow.
  New uploads carry a blue dot until you have looked at them.
- **Search** across all your sources, with creators and a shelf per source.
- **Channel pages** with a Follow button.
- **Full-screen player**: OK plays and pauses, Left and Right skip 10 seconds, *Go to channel*, and an
  *Up next* countdown.
- **Use your phone**: scan a QR code on the TV to open a page on your phone. From there you can follow
  creators, search, send a link to play, or add a source (paste its link or scan its QR code).
- **First-run setup**: pick your sources from the official list at
  [plugins.grayjay.app](https://plugins.grayjay.app), then connect your phone.

## Download

Get the APK from the [Releases](https://github.com/Yoshiofthewire/grayjay-android/releases) page.

| File | For |
| --- | --- |
| `grayjay-tv-…-armeabi-v7a.apk` | Most Google TV and Android TV devices, including Chromecast with Google TV, which run 32-bit Android even on 64-bit chips |
| `grayjay-tv-…-arm64-v8a.apk` | TVs and boxes running 64-bit Android |

Not sure which one? Try armeabi-v7a. If the TV says the app isn't compatible, try the other. With adb you
can check directly: `adb shell getprop ro.product.cpu.abilist`.

Check the download against `SHA256SUMS` on the release page. Every APK is signed with this certificate:

```
SHA-256: 27:1A:A5:3E:BA:F1:78:33:8C:C2:2C:1B:F9:A9:43:A7:5D:76:22:7B:EF:73:4B:EF:42:41:17:F3:6D:3E:3D:D0
```

`apksigner verify --print-certs <file>.apk` shows it.

## Install

1. On the TV, go to *Settings › System › About* and press *Android TV OS build* seven times to turn on
   Developer options.
2. Either:
   - **With adb** (most reliable): turn on *USB debugging* in Developer options, then from a computer on the
     same network run `adb connect <tv-ip>:5555` and `adb install grayjay-tv-…-armeabi-v7a.apk`.
   - **Without adb**: copy the APK into the TV's internal *Download* folder (a USB stick or *Send files to
     TV*), then open it with a file manager and allow that app to *install unknown apps*. Copy it to
     internal storage first; installing straight from a USB stick or cloud drive can fail with
     "There was a problem parsing the package".
3. Open Grayjay from the TV home screen.

Things to know before installing:

- **It replaces official Grayjay.** This build uses the same app ID as Grayjay, so the two can't be installed
  side by side. If official Grayjay is on the TV, uninstall it first; that deletes its data on the TV.
- **Don't accept Grayjay's update prompt.** The app's built-in updater offers FUTO's official version.
  Accepting it replaces this build.
- **Updates.** Install a newer APK from Releases over the old one; your sources and follows are kept.

## First run

1. **Choose your sources.** Pick with the remote and press *Add*. Each source from plugins.grayjay.app is
   checked to be signed by FUTO before it installs. Sources marked *Built in* ship inside the app.
2. **Use your phone.** Scan the QR code with your phone camera, or press *Start watching* to skip.

## The phone page

- It only runs while Grayjay is on the TV screen. It stops when you leave the app, press *Stop sharing*,
  or after 15 minutes without use.
- The QR code holds a one-time secret, and every request from the phone needs it.
- It is reachable only on your local network, over plain HTTP. Someone watching the same Wi-Fi during a
  session could reuse the secret. Even then, nothing that runs code installs without your OK on the TV.
- **Add source** on the phone: paste a link (`https://…Config.json`, `vfuto://…` or
  `grayjay://plugin/…`) or tap *Scan a QR code*. Your phone camera takes a photo and the TV reads the
  code. The TV then shows who made the source, its warnings and what it can connect to, and waits for you
  to confirm with the remote.
- The TV refuses a source whose signature doesn't match its code. It also refuses a replacement for an
  installed source that comes from a different signer.

## Logging in to a source

Sources that need an account show **Log in** on their shelf. This opens the source's own login page on
the TV. Type with the remote, or with the keyboard in the Google TV app on your phone. The session stays
on the TV.

## Not on the TV yet

- Source settings, removing sources, library, playlists, downloads, comments and sync are still only in the
  phone app.
- Captions, quality and playback speed controls in the player.
- Links and shares opened on the TV use the phone interface.

## What this fork changes

Compared with upstream Grayjay:

- Adds the TV interface (`app/src/main/java/com/futo/platformplayer/tv/`, `res/layout/tv_*`) and lists it
  in the TV launcher. A plain launch on a TV opens it, while phones keep the phone interface.
- Marks touchscreen and portrait screens as optional, so TVs accept the app.
- Adds the phone page (`app/src/main/assets/tv/companion.html`) and an optional bind address to
  `ManagedHttpServer`.
- Bundles FUTO's published, signed YouTube source (`app/src/stable/assets/sources-signed/`), because release
  builds only run YouTube when FUTO signed it. It also bundles Yoshiofthewire's Floatplane source, switched
  off by default.
- Lets the source login screen use landscape on TVs.

Grayjay's payment features are unchanged and remain in the phone interface.

## Building

```sh
git clone https://github.com/Yoshiofthewire/grayjay-android.git
cd grayjay-android
# GitHub's submodule links point at repos that don't exist there; fetch them from FUTO's GitLab.
git -c url.https://gitlab.futo.org/videostreaming/plugins/.insteadOf=https://github.com/futo-org/plugins/ \
    -c url.https://gitlab.futo.org/videostreaming/futopayclientlibraries.git.insteadOf=https://github.com/futo-org/futopayclientlibraries.git \
    submodule update --init --recursive
# app/aar/ffmpeg-kit.aar is a Git LFS pointer; GitHub doesn't host the object, FUTO's GitLab does.
OID=$(sed -n 's/^oid sha256://p' app/aar/ffmpeg-kit.aar)
curl -fL -o /tmp/ffmpeg-kit.aar "https://gitlab.futo.org/videostreaming/grayjay.git/gitlab-lfs/objects/$OID"
echo "$OID  /tmp/ffmpeg-kit.aar" | sha256sum -c && cp /tmp/ffmpeg-kit.aar app/aar/ffmpeg-kit.aar
echo "sdk.dir=$ANDROID_HOME" > local.properties
JAVA_HOME=/path/to/jdk-21 ./gradlew assembleStableRelease \
  -Pandroid.injected.signing.store.file=$HOME/.android/debug.keystore \
  -Pandroid.injected.signing.store.password=android \
  -Pandroid.injected.signing.key.alias=androiddebugkey \
  -Pandroid.injected.signing.key.password=android
```

The APKs land in `app/build/outputs/apk/stable/release/`. Releases on this repo are then re-signed with the
fork's own key using `apksigner sign`. A build you make yourself has a different signature, so it can't
update a release install without uninstalling first.

Unit tests for the TV code: `./gradlew testStableDebugUnitTest --tests 'com.futo.platformplayer.tv.*'`.

## License

[Source First License 1.1](LICENSE.md). You may use, copy and share this software free of charge for
non-commercial purposes; read the license for the full terms.
