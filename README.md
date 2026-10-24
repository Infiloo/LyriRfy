# LyriRfy

Live Spotify lyrics inside Minecraft, with three independent display modes and a full
in-game editor for every effect.

Built for **Minecraft 26.2 (Fabric)**.

---

## What it does

LyriRfy reads what you are currently playing on Spotify and turns it into:

1. **3D world lyrics** – the current line floats in the world in front of you, far enough
   away to stay readable without covering the action. Lines fade in, hold, then fade out
   smoothly. Optional **Fixed 3D** mode pins a line to the spot where it appeared until it
   has been sung, instead of following your head.
2. **Now playing HUD** – song title, artist, album artwork and a progress bar. Fully
   movable and scalable.
3. **2D screen lyrics** – the same synced line as a flat overlay, also movable and
   scalable with a drag-and-drop editor.

Plus **screen shake**: the HUD and 2D lyrics get a short, decaying shake when you jump,
land, fall or move the camera quickly. Every aspect (strength, jump sensitivity, camera
sensitivity, decay, frequency, maximum offset, which elements shake) is a slider in the
graphics menu.

All three main features can be enabled in any combination, or all at once.

Lyrics are time-synced and come from **[LRCLIB](https://lrclib.net)** – a free, open lyric
database. Spotify's Web API does not expose lyrics, so LRCLIB is used for the text and
timing while Spotify supplies the playback position, track metadata and artwork.
Results are cached in `config/lyrirfy-cache/lyrics`, so replaying a song works offline.

---

## Requirements

| Component | Version |
| --- | --- |
| Minecraft | 26.2 |
| Fabric Loader | 0.20.1 or newer |
| Fabric API | 0.161.0+26.2 (any 26.2 build) |
| Java | 25 |

The mod is client-side only. It works in singleplayer and on servers (nothing is sent to
the server).

---

## Installation

1. Install Fabric Loader for Minecraft 26.2.
2. Put **Fabric API** and **`LyriRfy-1.0.0-mc26.2.jar`** (from the `release/` folder) into
   your `.minecraft/mods` folder.
3. Start the game and press **F9** to open the LyriRfy menu.

---

## Spotify setup (one time)

1. Open <https://developer.spotify.com/dashboard> and create an app.
2. In the app settings, add this exact **Redirect URI**:

   ```
   https://gravatar.me/infiloo
   ```

   This page is only used as a landing page after you approve the login. Nothing is sent
   to it by the mod – the authorization code stays in your browser's address bar and you
   paste it into the game yourself. The mod exchanges it directly with Spotify.

   You can use any other HTTPS URL you control instead, or a local
   `http://127.0.0.1:8888/callback` URI (plain HTTP on loopback is still accepted by
   Spotify and is captured automatically by the mod). Edit the URI in the Spotify tab to
   match whatever you registered.
3. Copy the app's **Client ID**. The **Client Secret** is optional (leave it empty for a
   public PKCE app; fill it in if your app requires a secret).
4. While the app is in *Development Mode*, add your Spotify account under
   **User Management → Add user**, otherwise Spotify returns `403`.
5. In game: **F9 → Spotify**, paste the Client ID, press **Connect with Spotify** and
   approve the login in your browser.
6. After approving, you land on `https://gravatar.me/infiloo?code=…&state=…`. Copy the
   **entire URL** from the address bar, paste it into **“Already approved? Paste the full
   redirect URL here”** and press **Finish login**.

That's it – the Spotify tab shows the current track and the overlays appear in game.
Tokens are stored in `config/lyrirfy-tokens.json` and refreshed automatically.

> **Note on HTTPS:** Spotify no longer accepts plain `http://` redirect URIs for public
> hosts. The default HTTPS landing page plus the paste step works without running any
> server, no ports need to be forwarded, and no third-party service receives your token.
> If you prefer the fully automatic flow, register
> `http://127.0.0.1:8888/callback` in the Spotify dashboard and set the same value in the
> mod; the built-in loopback listener completes the login by itself.

---

## Using the overlays

Press **F9** at any time. The menu has six tabs:

* **Spotify** – credentials, redirect URI, connect/disconnect, paste-redirect login.
* **3D lyrics** – enable, fixed mode, face-player, wall avoidance, distance, height,
  left/right offset, text size, opacity, fade in/out, line hold time, line width,
  background darkness, float amount/speed, next-line preview.
* **HUD** – enable, artwork, progress bar, time, scale, opacity, artwork size, panel
  darkness, **Move HUD…**.
* **2D lyrics** – enable, background, next line, scale, opacity, max width, background
  strength, fade in/out, line hold, **Move 2D lyrics…**.
* **Shake** – enable, strength, jump/fall sensitivity, camera-speed sensitivity, decay,
  frequency, max offset, whether it affects the HUD and/or 2D lyrics.
* **General** – master switch, show while paused, poll rate, reset everything, open the
  config folder.

**Drag editors** (opened from the HUD and 2D lyrics tabs):

* Drag the element with the mouse. It snaps to the edges and to the horizontal centre.
* Arrow keys nudge it; **Shift + arrows** move in bigger steps.
* Scroll to resize.
* **Reset position** restores the default; **Center** (2D lyrics) snaps horizontally.
* **Done** returns to the config screen.

Positions are stored as screen fractions, so they survive window resizes and GUI scale
changes.

Settings are saved automatically (shortly after you stop changing something) and when the
screen closes or the game stops.

---

## Configuration files

All inside `.minecraft/config/`:

| File | Contents |
| --- | --- |
| `lyrirfy.json` | All settings, including Spotify Client ID/secret and the redirect URI. |
| `lyrirfy-tokens.json` | OAuth access/refresh tokens (owner-only permissions where supported). |
| `lyrirfy-cache/lyrics/` | Cached `.lrc` files and “not found” markers. |

Delete `lyrirfy-tokens.json` (or press **Disconnect**) to log out.

---

## Building from source

```bash
cd lyrirfy
./gradlew build        # or: gradle build
```

The jar is written to `lyrirfy/build/libs/lyrirfy-1.0.0.jar`.
Requires **Java 25** and **Gradle 9.8.2** (the wrapper pins the right version).

Toolchain: Loom 1.19.3, Minecraft 26.2 with Mojang mappings, Fabric Loader 0.20.1,
Fabric API 0.161.0+26.2.

---

## Limitations

* Lyrics availability depends on LRCLIB. Instrumentals, very new releases or obscure
  tracks may have no synced lyrics; the HUD still works.
* Podcasts/episodes have no lyrics (the HUD still shows the episode).
* Spotify playback must happen on some device (phone, desktop, web) – the mod is a
  remote display, it does not play audio itself.
* A development-mode Spotify app is limited to the users you explicitly allow. For
  public release, request an extension in the Spotify dashboard.
* The mod targets Minecraft **26.2** specifically. Newer game versions may need updates.

---

## License

MIT – see [LICENSE](LICENSE).
