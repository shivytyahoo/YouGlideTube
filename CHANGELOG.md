# Changelog

## 1.1.3 — 2026-11-27

- Update prompt: when a newer version is out, the app now shows a dialog with what's new on every open — Update now, Skip this version, or Later
- What's new after an update: the first time the app opens after an update, it shows what's new in that version, with All releases and Close
- Videos buffer far ahead now: tapping a video fills the buffer well ahead within seconds, and quality only climbs after 30 seconds is banked, so playback doesn't stall on a slow connection
- Home feed without sign-in, rebuilt: a personal feed built on your device from what you watch — with a greeting, Continue watching, Your interests and shelves for you
- Discover and More: a Discover chip with fresh videos outside what you already like, and a More menu with Music, News, Sports, Learning, Gaming and Fashion & beauty, each with its own real feed

## 1.1.2 — 2026-11-24

- Faster playback: the next video's stream is prepared while you browse, the two fastest players race and the winner plays, and reopening a video reuses its last stream instead of fetching it again
- Fixed videos failing to load when the current session is blocked: the app now gets a fresh session before retrying, instead of retrying with the blocked one
- Playback no longer gets stuck at a quality your connection can't sustain: after repeated buffering it steps down one quality level and keeps playing
- Update prompt fixed: the update page now comes back on every app open until you download or skip that version — backing out no longer silences it
- Update check now runs every time you open the app, not just the first time after install
- Fixed a video getting stuck in the middle when the session is blocked: the app now fetches the same video from another source instead of replaying the blocked one
- Home feed without sign-in: the home feed now loads videos even when you are signed out, just like in a browser — no more sign-in screen on Home
- Fixed everything going blank after signing out: when YouTube blocks this device's anonymous session, the app now gets a fresh one by itself — the home feed, search and playback recover instead of staying empty
- Anonymous sessions stay anonymous: the app no longer opens YouTube's homepage at startup to prepare its session, and signing out gives the guest session a fresh identity instead of the old mixed one
- Home feed never ends now: keep scrolling and more videos keep coming, even without signing in — the feed is seeded from your searches and trending music when YouTube sends no home feed
- Every Home tap shows fresh videos: the feed rotates its seeds and leaves out the videos you just saw, so the same list doesn't repeat
- Shorts keep coming too: the reel feed now finds more shorts from your searches and trending music channels instead of stopping
- Signed-out home has search and sign-in in the middle: search any video and it lands on your feed and plays, no login needed
- Videos no longer stop suddenly when the session is blocked: the app switches to a fresh session right away instead of waiting, and stops wasting time on the unavailable signature service
- Some videos start faster: when YouTube refuses a video on the fast sources, the app now tries the web and TV sources at the same time instead of one after the other
- Fixed web playback for videos whose player script is missing: their formats are now solved against the decoder's latest player instead of being dropped

## 1.1.1 — 2026-11-19

- Feed preview quality: pick the preview quality for videos and Shorts, separately on Wi-Fi and mobile data — Auto up to 8K for videos and up to 4K for Shorts — so previews use less data and don't stall
- Read ahead on disk: the video is written up to 10 minutes ahead into the cache while you watch, so playback keeps going even when the connection drops — turn it off in Settings → Playback → Buffer settings
- Shorts warm-ahead increased: more of the next Shorts is preloaded, so swiping to the next Short starts instantly

## 1.1.0 — 2026-11-18

- Music crossfade: songs fade into each other for up to 12 seconds, with fade curves to choose from; album tracks stay gapless
- Customizable mini player: pick up to three buttons, a cover shape and motion, a progress style, and a background
- Lyrics upgrade: new Cadence animation, YouTube timed lyrics first, more lyric sources, faster lookups, and shareable lyric cards
- TV improvements: seeking shows only the progress bar, Up Next sits on its own panel, Now Playing appears in the rail, and remote keys work better
- Lock screen lyrics: the sung line shows on the lock screen and the system media card while music plays, with a full lock-screen player when the screen turns off

## 1.0.9 — 2026-11-17

- Content country now works: changing the country refreshes the Home feed for that country, just like changing the language
- View counts follow the country: India shows lakh/crore (10L, 1Cr), the US shows million/billion (1M, 1B) — and the format switches as soon as you change the country
- Music tags removed from the Music tab: the feed always shows everything now, just like "All"
- Mixes play now: tapping a mix plays its songs instead of failing to load
- Shorts like count now updates: liking a short adds 1 to the count, unliking takes it back


## 1.0.8 — 2026-11-17

- TV tabs refresh properly now: tapping Home, Library, Subscriptions or Music again refreshes the feed on TV, just like on mobile
- Music tags work now: tapping a tag in the Music tab filters the feed instead of doing nothing
- Smoother playback: faster, steadier streaming with improved buffering, including Shorts
- Single universal GitHub build now: no separate FOSS build, and no Discord or Reddit integration in the app


## 1.0.7 — 2026-11-16

- Smoother playback on slow connections: videos start at a lower quality and climb only when the connection proves fast, and a stall steps the quality down instead of looping
- Your manual quality choice is now always respected: a quality you pick never changes on its own, even if the video keeps buffering
- 8K (4320p) quality option added where the video offers it
- Sign-in stability improvements
- Updated the playback engine to Media3 1.11.1

## 1.0.5 — 2026-11-11

- Liked music is back: the Liked music page was showing 0 songs even though your liked songs were right there — it now shows them, only the songs you hearted in the music player.
- Comments sort properly now: Oldest shows comments oldest-to-newest across the whole app — player, Shorts, and channel posts — instead of showing the newest first.
- Animated backgrounds are off by default now: the app opens with a clean background instead of Diwali diyas — pick any festival look in Settings → Appearance → Layout.

## 1.0.4 — 2026-11-10

- Animated backgrounds: choose from 30 festival looks — Diwali diyas, and 29 more — in Settings → Appearance → Layout.
- Live videos play again: live streams were blocked on every player; they now load through your signed-in session.
- History, finally fixed: Clear All wipes your whole YouTube watch history in one go, and it stays gone after refresh.
- Delete and Delete Shorts now remove videos from your server history too, not just the local list.
- Video quality is Auto by default: videos climb to the best quality your network can handle instead of staying pinned to 480p on mobile data.
- Playlists that actually load: your playlist list was coming back empty — it now shows all of them.
- Watch Later works: adding a video to Watch Later now saves it on the server.
- Library stays live: counts and thumbnails update the moment you add, remove, or delete a playlist.
- Deleting a playlist sticks: it no longer comes back after you leave the screen.
- Player Save button flips to Saved the instant you save a video.
- Save and unsave fixed: saving someone else's playlist, then removing it, works now instead of failing with a permission error.
- Download a clip: pick a start and end time and download just that part — no need to download the whole video.
- Clip downloads are much faster now: only the clip's own segments are fetched, for MP4 and WebM (VP9/AV1) alike.
- Multi-audio downloads: tick Hindi and English and the download carries both audio tracks.
- Smoother on slow networks: videos keep a bigger buffer and step up quality more slowly, so mid-video stalls are rarer.
- TV catches up with mobile: matching banner, circles and backgrounds on TV, visit a channel from any feed card or the player, previews follow the remote, and a clear focus ring.
- Battery saver in Settings: switches off animations, glass blur, feed previews and Shorts pre-loading for a lighter, faster app.
- Autoplay no longer crashes at the end of the queue when there is nothing next.
- Fixed a thin outline that appeared around cards on phones.

## 1.0.2 — 2026-11-05

- Notifications, in the app: a bell with an unread badge on every screen, New / Last 7 days / Earlier groups, pull to refresh, clear all, and dismiss — and the check interval you pick in Settings now actually reschedules the check.
- Settings that were listed but did nothing now work: Pause watch history freezes what the server shows, Downloads and General notification switches, search history survives restarts, bedtime reminders repeat every night and survive a reboot, and watch time is counted from a real ledger.
- Preview audio no longer keeps playing over the video you open from it.
- Cards never touch each other now: proper gaps on Search, Categories, and Channel search, like Home.
- The Shorts filter in search now shows Shorts — they were arriving in shelves the tab used to drop.
- Check for update now tells you when the check itself fails, instead of claiming you are up to date.

## 1.0.1 — 2026-11-04

- Fresh Morphe-style look: blue-to-teal brand, dark-first theme, glass navigation pill, and a floating circles background on every screen.
- Video cards redesigned: colours picked from each thumbnail, glass look, and proper gaps between cards everywhere.
- Faster on slow networks: videos start buffered before you press play, Shorts pre-buffer ahead while you scroll, and quality steps up smoothly.
- Feed previews: videos preview as you scroll, tapping one continues from the preview, with an unmute button on the preview.
- Shorts scroll without loading pauses, and the Shorts tab indicator now sits centred on its icon.
- Fixes: logo background, Settings cards, and spacing between up-next suggestions.

## 1.0.0 — 2026-11-01 (Initial release)

- Watch history syncs with your YouTube account: videos watched in YouGlideTube appear in YouTube history on every device, and the other way round.
- Buttery-smooth playback: fast direct streams with automatic quality upgrades, background play, and downloads.
- Comments that work: post, reply, edit, delete, and like — including replies inside threads.
- Live chat: send messages and remove your own.
- Library and History keep your latest watches and likes on top, across restarts.
- Sign in with your Google account, with multiple accounts.
