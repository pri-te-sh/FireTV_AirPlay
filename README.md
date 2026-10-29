# AirPlay for Fire TV

An open-source AirPlay receiver for Amazon Fire TV. Send your iPhone, iPad or Mac to the TV:

- **Screen Mirroring** with sound, in portrait or landscape.
- **Video from apps**: tap AirPlay in a video app and the TV plays the video itself, while your
  phone stays the remote (pause, seek, stop).
- **Music**, with a Now Playing screen: artwork, title, artist and progress.

It shows up in Control Center → Screen Mirroring under your Fire TV's name. No account, no ads,
and nothing leaves your home network.

## Install

**Download:** [AirPlay.apk (latest release)](https://github.com/pri-te-sh/FireTV_AirPlay/releases/latest/download/AirPlay.apk)

### On the Fire TV (no computer)

1. On the Fire TV, go to **Settings → My Fire TV → Developer Options** and turn on
   **Install unknown apps** for **Downloader**. If you don't see Developer Options, open
   **Settings → My Fire TV → About** and click the device name 7 times.
2. Install **Downloader** (by AFTVnews) from the Amazon Appstore.
3. In Downloader, enter this address and install:
   `https://github.com/pri-te-sh/FireTV_AirPlay/releases/latest/download/AirPlay.apk`
4. Open **AirPlay** once. From then on it starts by itself when the TV boots.

### With adb (from a computer)

```bash
adb connect <fire-tv-ip>:5555
adb install AirPlay.apk
adb shell appops set com.pritesh.airplay SYSTEM_ALERT_WINDOW allow
```

The last line is optional but recommended. It lets the player **open on its own** when you start
AirPlay on your phone. Fire OS has no settings screen for this permission, so it needs adb.
Without it, everything still works: start AirPlay on the phone, then open the AirPlay app on the TV.

## Use it

- **Mirror:** Control Center → Screen Mirroring → your Fire TV.
- **Video:** tap AirPlay in a video app (Photos, Safari and apps that use Apple's standard player).
- **Music:** Control Center or the Music app → AirPlay → your Fire TV.

On the TV remote:
- **Back** stops mirroring or video.
- **Select / Play-Pause** pauses app video.
- **Left / Right** skip 10 seconds.

On Now Playing, Back leaves the music playing.

## Limits

- Apps that use their own video player (rather than Apple's) send **sound only**. Use Screen
  Mirroring for those.
- DRM-protected services (Apple TV+, Netflix, Disney+ …) only AirPlay to Apple devices.
- App video uses Fire OS's built-in player. MP4 and standard HLS streams are tested; other
  formats depend on Fire OS.
- No password or PIN pairing. Anyone on your Wi-Fi can AirPlay to the TV, as with an Apple TV
  set to "Anyone on the same network".
- If the Fire TV runs a VPN and your phone can't find it, exclude this app in the VPN's
  split-tunneling settings.

## Build from source

No Gradle: `build.sh` runs aapt2 → javac → d8 → zipalign → apksigner directly.

You need:
- an Android SDK with a platform and build-tools;
- JDK 11 or newer;
- macOS or Linux with zsh.

```bash
ANDROID_SDK=~/Library/Android/sdk ./build.sh                 # → build/AirPlay.apk
FIRETV=192.168.1.20:5555 ./build.sh --install                # build, install, grant, open
```

Optional settings: `BUILD_TOOLS` (version) and `PLATFORM` (default `android-33`).

The first build creates `keystore.jks` to sign the APK. It's git-ignored. Keep it, because
Android only installs updates signed with the same key. `KEYSTORE` / `KEYSTORE_PASS` point
the build at your own key.

## How it works

| Piece | File |
|---|---|
| mDNS (`_airplay._tcp`, `_raop._tcp`) via Android's NsdManager | `Bonjour.java` |
| RTSP control: `/info`, pairing, FairPlay, SETUP/TEARDOWN, volume, metadata | `Receiver.java`, `Rtsp.java` |
| Mirror stream: decrypt (AES-CTR), avcC → Annex-B, hardware H.264 to a Surface | `MirrorStream.java`, `VideoPlayer.java` |
| Audio: RTP/UDP, decrypt (AES-CBC), ALAC (Java) or AAC/AAC-ELD (MediaCodec) → AudioTrack | `AudioStream.java`, `AudioPlayer.java` |
| Video from apps: `/play`, `/rate`, `/scrub`, `/playback-info`, the reverse `/event` channel, and YouTube-style `mlhls://` playlists fetched from the phone (FCUP) and served at `/fcup` | `Cast.java`, `Receiver.java` |
| Player screen (mirror / app video / Now Playing) and the app's status screen | `PlayerActivity.java`, `MainActivity.java` |
| Stable identity (pairing key, device id) | `Identity.java` |

## Credits and licenses

This project is MIT-licensed (see `LICENSE`). It includes code from:
- [java-airplay](https://github.com/serezhka/java-airplay) by Sergei Fedorov (MIT):
  FairPlay (SAP) and pairing, in `third_party/com/github/serezhka`. It was ported to Java 8 for
  Android, with Lombok/SLF4J replaced by a small `Log` shim, `PairingIdentity.fromSeed` added and
  unused parts removed.
- Peter McQuillan's [Java ALAC decoder](https://github.com/soiaf/Java-Apple-Lossless-decoder)
  (BSD), in `third_party/com/beatofthedrum`. Fire OS has no ALAC codec.
- [Bouncy Castle](https://www.bouncycastle.org) (MIT): X25519 key agreement, in
  `third_party/org/bouncycastle`.
- `libs/`: [dd-plist](https://github.com/3breadt/dd-plist) 1.28 (MIT) and
  [ed25519-java](https://github.com/str4d/ed25519-java) 0.3.0 (CC0).

License texts are in `third_party/LICENSE-*`.

AirPlay, iPhone, iPad and Mac are trademarks of Apple Inc. Fire TV is a trademark of
Amazon.com, Inc. This project isn't affiliated with or endorsed by Apple or Amazon.
