# BitChord Cast receiver

`index.html` is the page a Chromecast or Google TV loads when BitChord casts to
it. It plays the audio the phone serves, shows the artwork and track, and
scrolls synced lyrics (word-by-word where the lyrics have word timing).

Without it, BitChord still casts, using Media3's stock receiver. That receiver
shows the artwork and titles under an "ExoPlayer Default Receiver" heading and
has no lyrics.

## Setting it up

1. **Host the page over HTTPS.** GitHub Pages works: in the repository go to
   *Settings → Pages*, set *Source* to *Deploy from a branch*, pick `main` and
   `/docs`. The receiver is then at
   `https://<user>.github.io/<repo>/cast-receiver/`.
2. **Register it.** Sign in to the
   [Google Cast SDK Developer Console](https://cast.google.com/publish) (a
   one-time US$5 registration). Choose *Add new application → Custom Receiver*,
   paste the URL from step 1, and save. The console shows an **Application ID**,
   which is 8 characters long, such as `1A2B3C4D`.
3. **Allow your TV to run it.** An unpublished receiver only runs on devices
   registered for testing. Under *Cast Receiver Devices*, add your Chromecast or
   Google TV's serial number, then restart the device (it can take about 15
   minutes to take effect). Publishing the application in the console removes
   this step for everyone.
4. **Build the app with the ID.** Either add it to `local.properties`:

   ```properties
   CAST_RECEIVER_APP_ID=1A2B3C4D
   ```

   or, for GitHub Actions builds, add a repository secret named
   `CAST_RECEIVER_APP_ID` with that value. Nothing else changes: the app
   launches this receiver instead of the stock one, and sends it lyrics.

## How it talks to the app

- **Audio.** The audio is a plain HTTP URL on the phone's local network, the
  same as with the stock receiver. The media's `contentId` is the track's
  BitChord id.
- **Lyrics.** Lyrics arrive on the custom channel
  `urn:x-cast:com.music.bitchord` as one JSON message per track:
  `{"type":"lyrics","mediaId":…,"synced":bool,"lines":[{"t":ms,"text":…,"align":"end"?,"words":[{"s":ms,"text":…}]?}]}`.
  The receiver times them against its own playback clock, so no per-line
  traffic crosses the Wi-Fi.
