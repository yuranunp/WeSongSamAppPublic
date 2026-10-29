<h1 align="center"><b>WeSongSam Mobile</b></h1>
<h4 align="center">A simple music player for Android, forked from Auxio.</h4>

## About

WeSongSam Mobile (Thai: *WeSongSam มือถือ*) is a simple, practical music player
for Android. It is a fork of
[Auxio](https://github.com/OxygenCobalt/Auxio) by
[OxygenCobalt](https://github.com/OxygenCobalt), with changes made for the
WeSongSam project:

- Renamed to **WeSongSam Mobile** / **WeSongSam มือถือ** with a new application ID
  (`com.yuranunp.wesongsam`).
- A new, simple launcher icon.
- Updated About screen pointing to this fork's source repository and NOTICE.
- This repository and `NOTICE.md` documenting the fork.

The original Auxio project, its author, and all original copyright notices are
fully preserved. This fork is released under the **GNU General Public License
version 3** (or, at your option, any later version), same as the original.

- Original project: [Auxio by OxygenCobalt](https://github.com/OxygenCobalt/Auxio)
- This fork's source: https://github.com/yuranunp/WeSongSamAppPublic
- License: [GPLv3](/LICENSE)
- Fork details: [NOTICE.md](/NOTICE.md)

## Features

Inherited from Auxio:

- A rational, tag-focused music library.
- High-quality metadata loading, supporting many uncommon tags.
- A full-featured playback experience with gapless playback, fast seeking,
  per-song equalizer presets, and replay gain support.
- Error-tolerant playlist parsing (M3U, M3U8, PLS, and more).
- A refined, unique user interface with dynamic color and extensive
  customization options.

## Permissions

- `INTERNET`: Needed to fetch artist images from streaming services.
- `MEDIA Content`: Used to load the music library.
- `SERVICE`: Used to keep audio playing in the background.

## Building

Build from source with Gradle (requires JDK 21 and the Android SDK):

```bash
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/`.

### Building on Windows

The FFmpeg step of the `media` submodule only builds on Linux or macOS. On Windows:

1. Build the FFmpeg libraries for the four ABIs once in WSL with `build_ffmpeg.sh`,
   as described in `media/libraries/decoder_ffmpeg/README.md`.
2. Apply the patch that lets Gradle use those prebuilt libraries:

   ```bash
   git -C media apply ../patches/media-windows-prebuilt-ffmpeg.patch
   ```

3. Put Git's `usr\bin` on `PATH` (the patched step runs `true`) and use JDK 21, for
   example the `jbr` folder of Android Studio.

## License

WeSongSam Mobile is licensed under the
[GNU General Public License v3](/LICENSE), the same license as the original
Auxio project. See `LICENSE` and `NOTICE.md` for details.
