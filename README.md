<p align="center">
  <img src="docs/banner.png" alt="YouGlideTube banner" width="100%">
</p>

<p align="center">
  <img src="docs/logo.png" alt="YouGlideTube logo" width="128">
</p>

<h1 align="center">YouGlideTube</h1>

<p align="center"><b>Buttery smooth. Truly yours.</b></p>

<p align="center">
  <a href="https://github.com/shivytyahoo/YouGlideTube/releases/tag/v1.0.2"><img src="https://img.shields.io/badge/version-1.0.2-blue?style=flat-square" alt="Version 1.0.2"></a>
  <a href="https://github.com/shivytyahoo/YouGlideTube/releases"><img src="https://img.shields.io/github/v/release/shivytyahoo/YouGlideTube?style=flat-square" alt="Latest release"></a>
  <img src="https://img.shields.io/badge/platform-Android%208.0%2B-3DDC84?style=flat-square" alt="Android 8.0+">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-blue?style=flat-square" alt="GPL-3.0"></a>
</p>

## About

**YouGlideTube** is a fast, buttery-smooth YouTube client for Android by
[Shivyt](https://github.com/shivytyahoo) — built for instant playback,
background play, downloads and a clean, distraction-free interface.

The current look is fresh and Morphe-inspired: a dark-first theme with a
blue-to-teal brand, a floating glass navigation pill, a soft drifting
circles background on every screen, and video cards coloured from their
own thumbnails, with proper gaps between them.

It is a fork of [Flow 3.0.0](https://github.com/A-EDev/Flow), rebranded
and extended end to end: the player foundation builds on open-source work
licensed under the GNU General Public License v3.0, and this project is
distributed under the same license. See [LICENSE](LICENSE) for the full
text.

| | |
|---|---|
| **App** | `shivyt.android.glidetube` |
| **Latest release** | [1.0.2](https://github.com/shivytyahoo/YouGlideTube/releases/tag/v1.0.2) |
| **Requires** | Android 8.0 (API 26) or newer |
| **License** | GPL-3.0 |

## Features

### Playback

- **Instant, buttery playback** — fast direct streams with adjacent
  preloading, so the next video is ready before you tap it
- **Videos start buffered before you press play**, and quality steps up
  smoothly as the stream settles — even on slow 4G
- **Automatic quality upgrades** as the stream settles
- **Background audio** and **picture-in-picture**
- **Equalizer** for tuned sound
- **Shorts** with the same smooth player — neighbours pre-buffer ahead,
  so scrolling never shows a loading pause

### Feed previews

- **Videos preview as you scroll** the home feed and other lists
- **Tapping a preview continues from the preview position**, with an
  unmute button right on the preview
- Shorts shelves preview too

### Your account, done right

- **Sign in with your Google account** right in the app, with
  **multiple accounts**
- No companion services app is needed for sign-in
- Your sign-in cookie stays on this device, is never backed up, and is
  only sent to YouTube
- **Watch history syncs with your YouTube account** — videos watched in
  YouGlideTube appear in YouTube history on every device, and the other
  way round
- **Library and History keep your latest watches and likes on top**,
  across restarts
- **Subscription notifications** — the app checks your subscriptions
  every 6 hours and tells you about new uploads

### Comments and live chat

- **Comments that work** — post, reply, edit, delete and like, including
  replies inside threads
- **Live chat** — send messages and remove your own

### Downloads

- **Offline downloads** with quality and format choice
- Download location and cache controls in Settings

### Discovery and music

- Home feed, subscriptions, search, categories, channels and playlists
- **Music mode with lyrics** — synced lyrics, source choice and
  auto-romanization
- **ListenBrainz scrobbling**
- **Discord rich presence**

### Doubles as a media player

YouGlideTube is not only a YouTube client — it works as a media player
for what is already on your phone:

- **Library → Local media** scans your device and plays its videos and
  music in the same player
- **Downloads play offline**, no connection needed
- The same player extras carry over: **background audio**,
  **picture-in-picture** and the **equalizer**

How it works: grant media access once, and the app finds the videos and
music stored on your device and lists them under Library → Local media.
Tap any of them and it plays in the same Media3 player used for YouTube,
with background play, PiP and the equalizer. Downloads are stored on the
phone itself, so they play offline too. Rescan anytime from Settings to
pick up new files.

### Community enhancements

- **SponsorBlock** — skip sponsored segments and more
- **DeArrow** — crowdsourced titles and thumbnails
- **Return YouTube Dislike** — dislike counts, back where they belong

### Make it yours

- Themes, fonts, app icon choices and navigation-bar order
- Date and time formats, home layout and widgets
- Local media, wellbeing tools and in-app diagnostics
- **In-app update checks** — the app fetches a new APK from the release
  page when one exists

### TV layout

- A big-screen layout for Android TV, with the same account feed,
  subscriptions and music

### Privacy

- **Local recommendations** — a real home feed with no account needed
- Only downloads are stored on the phone; everything else comes from
  your account

## Differences from Flow

YouGlideTube is a fork of Flow 3.0.0, modified in 2026. The player
foundation, Media3 playback engine and InnerTube client come from Flow
(under GPL-3.0, with credit above); everything below was changed,
added or removed in this fork:

**Rebrand**

- New name, launcher icon, splash and About screens — nothing visible
  still says Flow
- New application ID: `shivyt.android.glidetube`
- New look: dark-first theme, blue-to-teal brand, glass navigation pill,
  floating circles background, and video cards coloured from their
  thumbnails
- Own versioning, starting at 1.0.0, with its own updater and release
  channel

**Removed**

- Donations
- Explorer tab
- "Your taste" feature (the engine still runs inside)

**Added**

- Google sign-in, built from scratch, with multiple accounts (upstream
  Flow had no sign-in)
- Watch-history sync with your YouTube account, both ways
- Full comment actions — post, reply, edit, delete and like, including
  replies inside threads
- Live chat — send messages and remove your own
- Library and History that keep your latest watches and likes on top,
  across restarts
- Settings order with Google Account at the top
- Subscription notifications (checks every 6 hours)
- Feed previews with tap-to-continue and an unmute button
- Shorts pre-buffering for pause-free scrolling
- Slow-network playback tuning — videos buffer before play, quality
  steps up smoothly
- Glass navigation pill, circles background and redesigned video cards
- In-app update checks with same-version rebuild detection
- TV layout support

**Kept from Flow**

- The buttery-smooth playback engine and workflow
- SponsorBlock, DeArrow and Return YouTube Dislike
- Local media playback, lyrics, downloads and Material 3 interface

## Screenshots

Fresh device screenshots of the new look are being added here.

<!-- Screenshots (fill in as they arrive):
<p align="center">
  <img src="docs/screenshots/home.png" width="30%">
  <img src="docs/screenshots/player.png" width="30%">
  <img src="docs/screenshots/comments.png" width="30%">
</p>
<p align="center">
  <img src="docs/screenshots/library.png" width="30%">
  <img src="docs/screenshots/downloads.png" width="30%">
  <img src="docs/screenshots/settings.png" width="30%">
</p>
-->

## Install

1. Download the APK from
   [Releases](https://github.com/shivytyahoo/YouGlideTube/releases).
2. Install it on your Android phone (Android 8.0+).
3. Open the app and sign in from Settings, or skip and sign in later.

## Build from source

```sh
git clone https://github.com/shivytyahoo/YouGlideTube.git
cd YouGlideTube
./gradlew :app:assembleGithubRelease
```

## Changelog

See [CHANGELOG.md](CHANGELOG.md) and
[Releases](https://github.com/shivytyahoo/YouGlideTube/releases).

## License

YouGlideTube is distributed under the **GNU General Public License v3.0**.
See [LICENSE](LICENSE) for the full text.
