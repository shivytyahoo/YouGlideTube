<p align="center">
  <img src="docs/banner.png" alt="YouGlideTube banner" width="100%">
</p>

<p align="center">
  <img src="docs/logo.png" alt="YouGlideTube logo" width="128">
</p>

<h1 align="center">YouGlideTube</h1>

<p align="center"><b>Buttery smooth. Truly yours.</b></p>

<p align="center">
  <a href="https://github.com/shivytyahoo/YouGlideTube/releases/tag/v1.0.6"><img src="https://img.shields.io/badge/version-1.0.6-blue?style=flat-square" alt="Version 1.0.6"></a>
  <a href="https://github.com/shivytyahoo/YouGlideTube/releases"><img src="https://img.shields.io/github/v/release/shivytyahoo/YouGlideTube?style=flat-square" alt="Latest release"></a>
  <img src="https://img.shields.io/badge/platform-Android%208.0%2B-3DDC84?style=flat-square" alt="Android 8.0+">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-blue?style=flat-square" alt="GPL-3.0"></a>
</p>

<p align="center">
  <a href="https://github.com/shivytyahoo/YouGlideTube/releases/tag/v1.0.6"><b>⬇️ Download YouGlideTube 1.0.6</b></a><br>
  <sub><code>YouGlideTube-1.0.6.apk</code> · universal APK — one file for every device · 27,691,375 bytes · installs over 1.0.5 · Android 8.0 (API 26) or newer</sub>
</p>

## About

**YouGlideTube** is a fast, buttery-smooth YouTube client for Android by
[Shivyt](https://github.com/shivytyahoo) — built for instant playback,
background play, downloads and a clean, distraction-free interface,
on your phone and on your Android TV.

It is a fork of [Flow 3.0.0](https://github.com/A-EDev/Flow), rebranded
and extended end to end: the player foundation builds on open-source work
licensed under the GNU General Public License v3.0, and this project is
distributed under the same license. See [LICENSE](LICENSE) for the full
text.

| | |
|---|---|
| **App** | `shivyt.android.glidetube` |
| **Latest release** | [1.0.6](https://github.com/shivytyahoo/YouGlideTube/releases/tag/v1.0.6) |
| **Requires** | Android 8.0 (API 26) or newer |
| **TV** | Android TV layout included — same app, big-screen interface |
| **License** | GPL-3.0 |

## 🎨 Look and feel

Open YouGlideTube and the first thing you notice is that it does not
look like a clone of anything. The interface is dark-first and calm: a
deep `#1A1C1E` canvas, a blue-to-teal brand (`#1E5AA8` → `#00AFAE`), and
warm saffron and coral accents on the mark and the player.

- **Floating glass navigation pill** — the bottom bar is a compact,
  centred glass pill with a sliding indicator that glides between tabs,
  instead of a flat strip eating the bottom of the screen.
- **Drifting circles background** — small, soft circles drift slowly
  behind every screen, so the app feels alive without ever shouting.
- **Cards coloured from their own thumbnails** — every video card picks
  its colours from its thumbnail, so the feed feels hand-made for what
  you are looking at, not stamped from a template.
- **Boxy cards with real gaps** — cards sit in their own space with
  proper 12dp gaps on every screen, Home, Search, Categories, Channel
  search, everywhere. Nothing ever looks stuck together.
- **Smooth by design** — a scratch launch animation, Glide-style loading
  and pull-to-refresh, and transitions that stay out of your way.
- **Same look on the big screen** — the dark-first theme and the
  blue-to-teal brand carry over to Android TV, so the app feels like
  itself wherever you open it.

The result is an app that feels considered rather than assembled: quiet
where it should be quiet, colourful exactly where your videos are, and
fast everywhere. It is the kind of interface you enjoy opening, not just
tolerating.

## ⚡ What's new in 1.0.6

This release folds in everything since 1.0.5 — thumbnail fixes across
Liked, Library and channel Releases, plus a new way to keep post photos.

- **🔒 Deep Glide Mode blocks server history too** — a private watch stays off YouTube's servers as well as your phone, and pausing watch history does the same.
- **📸 Post photos save to your Gallery** — open any photo in a channel
  post and tap the download button; it saves to Pictures/YouGlideTube.
- **💿 Channel Releases show their covers** — the playlist cards on a
  channel's Releases tab were blank grey; they now show the release artwork.
- **❤️ Liked videos cover follows your sorting** — the cover shows the top
  video's picture after any sort, and a broken saved picture falls back to
  the video's own thumbnail.
- **🎵 Liked music cover follows your sorting too** — the cover shows the top
  song after any sort, and songs with a broken saved picture show their own
  thumbnail.
- **📚 Library is tidier** — the Liked videos card no longer repeats on the
  shelf, and pulling to refresh actually refreshes.
- **🔄 Re-tapping a tab refreshes it** — Home, Music, Subscriptions, Library,
  Search, Explore and Shorts all refresh when you tap their tab again.
- **📺 TV player spinner fixed** — the loading spinner no longer spins over a
  video that is already playing.
- **🕘 Library History shelf matches full History** — the shelf now shows your
  latest watch first, in the same order as the full History screen.

## ⚡ What's new in 1.0.4

This release folds in everything since 1.0.2 — eight rounds of fixes and
features, in one APK.

- **🎬 Live videos play again** — live streams were blocked on every
  player; they now load through your signed-in session.
- **🧹 History, finally fixed** — Clear All wipes your whole YouTube
  watch history in one go, and it stays gone after refresh.
- **🗑️ Delete and Delete Shorts now remove videos from your server
  history too**, not just the local list.
- **📈 Video quality is Auto by default** — videos climb to the best
  quality your network can handle instead of staying pinned to 480p on
  mobile data.
- **📋 Playlists that actually load** — your playlist list was coming
  back empty; it now shows all of them.
- **⏰ Watch Later works** — adding a video to Watch Later now saves it
  on the server.
- **📚 Library stays live** — counts and thumbnails update the moment
  you add, remove, or delete a playlist.
- **🗑️ Deleting a playlist sticks** — it no longer comes back after you
  leave the screen.
- **💾 Player Save button flips to Saved** the instant you save a video.
- **🔖 Save and unsave fixed** — saving someone else's playlist, then
  removing it, works now instead of failing with a permission error.
- **✂️ Download a clip** — pick a start and end time and download just
  that part, no need to download the whole video.
- **⚡ Clip downloads are much faster now** — only the clip's own
  segments are fetched, for MP4 and WebM (VP9/AV1) alike.
- **🎵 Multi-audio downloads** — tick Hindi and English and the download
  carries both audio tracks.
- **📶 Smoother on slow networks** — videos keep a bigger buffer and step
  up quality more slowly, so mid-video stalls are rarer.
- **🎨 Animated backgrounds** — choose from 30 festival looks, Diwali
  diyas and 29 more, in Settings → Appearance → Layout.
- **📺 TV catches up with mobile** — matching banner, circles and
  backgrounds on TV, visit a channel from any feed card or the player,
  previews follow the remote, and a clear focus ring.
- **🔋 Battery saver in Settings** — switches off animations, glass
  blur, feed previews and Shorts pre-loading for a lighter, faster app.
- **▶️ Autoplay no longer crashes** at the end of the queue when there
  is nothing next.
- **🃏 Fixed a thin outline** that appeared around cards on phones.

## ⚡ What's new in 1.0.3

- **🪔 Diwali diyas** — six glowing diyas float over the home
  background; turn them off in Settings → Appearance → Layout.
- **📺 TV catches up with mobile** — matching banner, circles and diyas
  on TV, visit a channel from any feed card or the player, previews
  follow the remote, and a clear focus ring.
- **🔋 Battery saver in Settings** — switches off animations, glass
  blur, feed previews and Shorts pre-loading for a lighter, faster app.
- **📶 Smoother on slow networks** — videos keep a bigger buffer and step
  up quality more slowly, so mid-video stalls are rarer.
- **✂️ Download a clip** — pick a start and end time and download just
  that part, no need to download the whole video.
- **⚡ Clip downloads are much faster now** — only the clip's own
  segments are fetched, for MP4 and WebM (VP9/AV1) alike.
- **🃏 Fixed a thin outline** that appeared around cards on phones.

## ⚡ What's new in 1.0.2

This release folds in everything since 1.0.1 — five rounds of fixes and
features, in one APK.

- **🔔 Notifications, inside the app** — a bell with an unread badge on
  every screen, an inbox grouped as New / Last 7 days / Earlier, pull to
  refresh, clear all and dismiss. The check interval you pick in
  Settings now actually reschedules the check (15 minutes to 24 hours,
  6 hours by default).
- **⚙️ Settings that were listed but did nothing now work** — Pause
  watch history freezes what the server shows, Downloads and General
  notification switches, search history survives restarts, bedtime
  reminders repeat every night and survive a reboot, and watch time is
  counted from a real ledger.
- **🎬 Preview audio fixed** — unmuting a preview no longer leaves its
  audio playing over the video you open from it.
- **🃏 Cards never touch each other now** — proper gaps on Search,
  Categories and Channel search, like Home.
- **🔍 Shorts search fixed** — the Shorts filter in search now shows
  Shorts. They were arriving in shelves the tab used to drop.
- **🔄 Honest update checks** — Check for update now tells you when the
  check itself fails, instead of claiming you are up to date.

See [CHANGELOG.md](CHANGELOG.md) for the full list.

## Features

### ⚡ Playback

- **Instant, buttery playback** — fast direct streams with adjacent
  preloading, so the next video is ready before you tap it
- **Videos start buffered before you press play**, and quality steps up
  smoothly as the stream settles — even on slow 4G
- **Automatic quality upgrades** as the stream settles
- **Quality is Auto by default** — videos climb to the best your network
  can handle, instead of staying pinned to one height
- **Live streams play** through your signed-in session
- **Background audio** and **picture-in-picture**
- **Equalizer** for tuned sound
- **Shorts** with the same smooth player — neighbours pre-buffer ahead,
  so scrolling never shows a loading pause

### 🎬 Feed previews

- **Videos preview as you scroll** the home feed and other lists
- **Tapping a preview continues from the preview position**, with an
  unmute button right on the preview
- Shorts shelves preview too

### 🔔 Notifications

- **In-app inbox** for new uploads from your subscriptions, with an
  unread badge on the bell
- **System notifications** for new videos, on a check interval you
  choose — 15 minutes to 24 hours
- Pull to refresh the inbox any time; dismiss one video or clear them all

### 👤 Your account, done right

- **Sign in with your Google account** right in the app, with
  **multiple accounts**
- No companion services app is needed for sign-in
- Your sign-in cookie stays on this device, is never backed up, and is
  only sent to YouTube
- **Watch history syncs with your YouTube account** — videos watched in
  YouGlideTube appear in YouTube history on every device, and the other
  way round
- **Clear All and Delete remove videos from your server history too**,
  not just the local list — and they stay gone after refresh
- **Playlists that actually load** — your full list, Watch Later that
  saves on the server, and a Library whose counts and thumbnails update
  the moment you change anything
- **Library and History keep your latest watches and likes on top**,
  across restarts

### 💬 Comments and live chat

- **Comments that work** — post, reply, edit, delete and like, including
  replies inside threads
- **Live chat** — send messages and remove your own

### 🔍 Search that finds things

- Search across videos, Shorts, live streams, channels and playlists,
  with filters for type, duration, upload date and sort order
- **The Shorts filter shows Shorts**, the Videos filter shows videos —
  each filter returns what it promises
- Search history is saved on the device and survives restarts

### 📥 Downloads

- **Offline downloads** with quality and format choice
- **Download a clip** — pick a start and end time and download just
  that part, fast, for MP4 and WebM (VP9/AV1) alike
- **Multi-audio downloads** — tick more than one audio track, Hindi and
  English together, and the download carries both
- Download location and cache controls in Settings

### 🎵 Discovery and music

- Home feed, subscriptions, search, categories, channels and playlists
- **Music mode with lyrics** — synced lyrics, source choice and
  auto-romanization
- **ListenBrainz scrobbling**
- **Discord rich presence**

### 📱 Doubles as a media player

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

### 🧩 Community enhancements

- **SponsorBlock** — skip sponsored segments and more
- **DeArrow** — crowdsourced titles and thumbnails
- **Return YouTube Dislike** — dislike counts, back where they belong

### 🎨 Make it yours

- Themes, fonts, app icon choices and navigation-bar order
- **Animated backgrounds** — 30 festival looks, from Diwali diyas to 29
  more, in Settings → Appearance → Layout
- **Battery saver** — switches off animations, glass blur, feed previews
  and Shorts pre-loading for a lighter, faster app
- Date and time formats, home layout and widgets
- Local media, wellbeing tools and in-app diagnostics
- **In-app update checks** — the app fetches a new APK from the release
  page when one exists, and tells you honestly when the check fails

### 📺 Android TV

YouGlideTube is a proper TV app, not the phone app stretched wide. The
same APK installs on Android TV, with a big-screen layout built for the
remote.

- **Navigation rail made for D-pad** — move with the remote, with focus
  that always lands where you expect it; a remote guide is built in
- **Same feed, subscriptions and music as mobile** — TV uses the same
  account feed, the same subscriptions and the same music as your phone
- **Home, Search, Library, History and Downloads** — Search is always on
  TV, and your playlists, channels and local media are there too
- **Shorts on the big screen** — its own D-pad Shorts tab, swiping with
  the remote
- **Full TV player** — D-pad controls, scrubbing, an Up Next rail and
  sheets, on the same playback engine
- **Visit a channel from any feed card or the player**, with previews
  that follow the remote and a clear focus ring
- **Same dark-first look** — the app's own dark theme and blue-to-teal
  brand, not the TV's default

### 🔒 Privacy

- **Local recommendations** — a real home feed with no account needed
- Only downloads are stored on the phone; everything else comes from
  your account

## Why you will enjoy it

YouGlideTube is built around one idea: **you should never wait for the
app**. Videos start before you press play. Shorts glide past without a
single loading pause. Previews let you peek at a video without committing
to it, and tapping one picks up exactly where the preview left off. Your
subscriptions tell you when something new is up, your history follows you
across devices, and the whole thing looks and feels like it was designed
— because it was, screen by screen, gap by gap, right down to the Shorts
tab icon sitting centred on its mark.

It is a serious project, built with care over many late nights, and it
shows in the small things: the pill that slides instead of jumping, the
cards that never touch, the update check that would rather admit a failure
than pretend everything is fine. And when you move to the couch, it is
there too: the same app on Android TV, with the same feed and the same
look, built for the remote.

## Differences from Flow

YouGlideTube is a fork of Flow 3.0.0, modified in 2026. The player
foundation, Media3 playback engine and InnerTube client come from Flow
(under GPL-3.0, with credit below); everything below was changed,
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
- Subscription notifications — in-app inbox plus system notifications
  on a schedule you choose
- Feed previews with tap-to-continue and an unmute button
- Shorts pre-buffering for pause-free scrolling
- Slow-network playback tuning — videos buffer before play, quality
  steps up smoothly
- Glass navigation pill, circles background and redesigned video cards
- In-app update checks with same-version rebuild detection
- Android TV support — big-screen layout with a D-pad navigation rail,
  Shorts tab, full TV player and the same feed, subscriptions and music
- Live streams that play through your signed-in session, with Auto
  quality by default
- History Clear All and Delete that remove videos from the server
  history, not just the local list
- Playlists that load in full, Watch Later that saves on the server,
  and a Library that updates live
- Clip downloads and multi-audio downloads
- Animated backgrounds — 30 festival looks — and Battery saver

**Kept from Flow**

- The buttery-smooth playback engine and workflow
- SponsorBlock, DeArrow and Return YouTube Dislike
- Local media playback, lyrics, downloads and Material 3 interface

## 🙏 Credits and thanks

YouGlideTube stands on the shoulders of open source. None of this exists
without the projects and people below, and this project is grateful to
every one of them.

- **[Flow](https://github.com/A-EDev/Flow)** — the project this is
  forked from. The playback engine, the native InnerTube client, the
  Material 3 foundation and the whole workflow come from Flow and its
  maintainers. Thank you for building it in the open under GPL-3.0.
- **[NewPipe](https://github.com/TeamNewPipe/NewPipe)** — the pioneering
  open-source YouTube client. Flow's early versions ran on NewPipe's
  extractor, and the whole independent-client ecosystem owes NewPipe and
  its contributors a debt for proving this could be done, in the open.
- **[Morphe Manager](https://github.com/MorpheApp/morphe-manager)** —
  design inspiration for this interface. The dark-first surfaces,
  blue-to-teal brand and glass cards take cues from Morphe Manager's
  look; its name and logo are not used here.
- **The InnerTube open-source community** — the researchers and
  developers behind YouTube.js, yt-dlp and the many independent clients
  whose protocol work makes projects like this possible at all.
- **The libraries that carry the app** — Media3 (ExoPlayer), Jetpack
  Compose and Material 3, Hilt, Room, DataStore, WorkManager, Paging 3,
  Coil, OkHttp, Ktor and kotlinx.serialization.
- **SponsorBlock, DeArrow and Return YouTube Dislike** — community
  projects that make YouTube better for everyone, kept working here.
- **Shivyt ([shivytyahoo](https://github.com/shivytyahoo))** — design,
  development and every late night in between.

If this project makes your day a little better, the credit belongs to all
of the above.

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

**One APK for every device** — there is nothing to choose. The release
APK is universal: it carries native code for all four Android ABIs, and
each device installs the one that fits it.

| Architecture | Devices |
|---|---|
| `arm64-v8a` | 64-bit ARM — almost every phone today |
| `armeabi-v7a` | 32-bit ARM — older phones |
| `x86_64` and `x86` | Intel devices, tablets, Chromebooks and emulators |

So whether your phone is 32-bit or 64-bit, ARM or Intel, the same file
works. It needs Android 8.0 (API 26) or newer.

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

## Disclaimer

YouGlideTube is not affiliated with, endorsed by, or sponsored by Google
or YouTube. YouTube is a trademark of Google LLC.

## License

YouGlideTube is distributed under the **GNU General Public License v3.0**.
See [LICENSE](LICENSE) for the full text.
