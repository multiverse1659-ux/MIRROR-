# Android ↔ Mac screen mirror + file transfer

Two apps that talk to each other over Wi-Fi — Android mirrors its screen to
the Mac, and files can be pushed either direction. This repo also includes
a GitHub Actions workflow that **builds both apps automatically** — you
never need to open Xcode or Android Studio.

## One-time setup (~5 minutes)

1. Create a free GitHub account if you don't have one, then create a new
   **repository** (public is easiest — it gets free, unlimited build
   minutes, including for the macOS build).
2. Upload everything in this folder to that repo — either drag-and-drop
   through GitHub's web uploader, or:
   ```
   git init
   git add .
   git commit -m "Initial commit"
   git branch -M main
   git remote add origin https://github.com/YOUR_USERNAME/YOUR_REPO.git
   git push -u origin main
   ```
   The `.github/workflows/build.yml` file is what does the building — make
   sure it's included.
3. On your repo's GitHub page: **Releases → Draft a new release**. Type a
   tag like `v1.0`, then click **Publish release**.
4. This automatically triggers the build. Click the **Actions** tab to
   watch it run — the Android build takes a couple of minutes, the Mac
   build a bit longer (~5–10 min total). GitHub is doing the actual
   compiling, on a real macOS machine and a real Android build environment.
5. Once it's done, refresh your release page — `MirrorSender.apk` and
   `MacMirror.dmg` will be attached to it, permanently downloadable.
6. Open `docs/index.html`, find `GITHUB_OWNER` and `GITHUB_REPO` near the
   top of the `<script>` tag, and fill in your username and repo name.
   That's the only edit needed anywhere.
7. (Optional, to get a real shareable link) In your repo's **Settings →
   Pages**, set the source to the `main` branch, `/docs` folder. GitHub
   gives you a URL like `https://YOUR_USERNAME.github.io/YOUR_REPO/` —
   that's your download page, live on the internet.

From then on, every time you push a new code change and publish a new
release, both apps rebuild automatically. No local build tools, ever.

## Using the apps once installed

- **Android → Mac mirroring**: open the phone app, type your Mac's local
  IP address (System Settings → Wi-Fi → Details, or `ipconfig getifaddr en0`
  in Terminal), tap **Start mirroring**, accept the capture permission.
- **File transfer**: phone → Mac via the **Send file to Mac** button
  (lands in `~/Downloads`); Mac → phone by dragging a file onto the Mac
  app's drop zone (lands in the phone's app-private Downloads folder).
  File transfer needs mirroring connected at least once first, since
  that's how the Mac learns the phone's address.
- On first launch, macOS will block the unsigned app — right-click it and
  choose **Open** once to bypass Gatekeeper. Android will ask you to allow
  installs from this source, since it isn't from the Play Store.

Both devices need to be on the same Wi-Fi network.

## If you'd rather build manually instead

Totally fine too — open `mac-mirror-receiver/Sources/*.swift` in a new
Xcode macOS SwiftUI project (add the Incoming/Outgoing Connections and
Downloads-folder entitlements from `MacMirror.entitlements`), and open
`android-mirror-sender/` directly in Android Studio and hit Run. No CI
needed for that path.

## Where to go from here

1. **Smoother video** — swap JPEG-per-frame for real H.264 (`MediaCodec`
   on Android, `VideoToolbox` on Mac).
2. **Control back-channel** — mouse/keyboard on the Mac replayed as
   touch/key events on Android.
3. **Auto-discovery** — Bonjour/mDNS instead of typing an IP.
4. **Reconnect handling** — auto-retry if the phone locks or the app is
   killed.

Happy to help build any of these next — just say which one.
