# WalkieTalkie — LAN multicast push-to-talk app

A complete Android Studio project implementing the architecture discussed
earlier: every device joins a UDP multicast group on the LAN, announces
itself, and can push-to-talk to everyone else at once — no server, no
per-recipient unicast fan-out.

- **Transport:** UDP multicast, `239.48.48.1:5004`
- **Audio path:** Mic → PCM 16kHz mono → AAC-LC (~32 kbps, via Android's
  built-in `MediaCodec`) → custom 11-byte packet header → UDP multicast
- **Discovery:** every device broadcasts a small "announce" packet every
  2 seconds with its name; devices that haven't been heard from in 6
  seconds drop off the list automatically
- **UI:** editable device name, live list of who's online (with a
  "speaking" indicator), a volume slider, and a big hold-to-talk button

Why AAC instead of Opus: Android guarantees AAC encode *and* decode on
every device via `MediaCodec` with zero external dependencies. Opus
*decoding* is standard on modern Android, but Opus *encoding* support is
inconsistent across OEMs, so AAC is the more reliable choice for a
drop-in build. The packet framing is intentionally simple (not full
RTP) but easy to extend if you want sequence-based jitter buffering or
loss concealment later — there's already a sequence number and
timestamp in every audio packet, just unused for now.

## How to build the APK

1. Install [Android Studio](https://developer.android.com/studio) if you
   don't have it (free).
2. Unzip this project, then in Android Studio: **File → Open**, and
   select the `WalkieTalkie` folder.
3. Android Studio will start a Gradle sync automatically. If it prompts
   you about a missing Gradle wrapper, accept the fix it offers (this
   project ships without the wrapper's binary jar since it can't be
   generated as plain text; Android Studio regenerates it automatically
   on first open, using its own bundled Gradle to bootstrap).
4. Once sync finishes: **Build → Build App Bundle(s) / APK(s) → Build
   APK(s)**.
5. When it finishes, click the "locate" link in the notification, or
   find it at `app/build/outputs/apk/debug/app-debug.apk`.
6. Copy that APK to your Android phone (email, USB, Drive, etc.) and
   install it. You'll need to allow "install from unknown sources" for
   whichever app you use to open it, since it's not from the Play
   Store.

Do this on every phone you want on the channel.

## Running it

- Every device needs **Wi-Fi enabled and connected to the same LAN**
  (same router/AP, no client isolation). It does not need internet
  access — just LAN connectivity.
- On first launch, grant the microphone permission when asked.
- Type a name at the top (saved automatically).
- Hold the big button to talk; release to stop. Everyone else on the
  LAN with the app open will hear you and see "🔊 speaking" next to
  your name.
- The volume slider controls playback volume for all incoming streams.

## Known network caveats (matches what we discussed earlier)

- **Router/AP support matters.** Cheap consumer routers sometimes block
  or mishandle multicast, especially on the 5GHz/6GHz bands or with
  "AP/client isolation" enabled — turn that off if audio doesn't reach
  other devices.
- **IGMP snooping**, if your router/switch supports and enables it,
  makes multicast delivery efficient instead of flooding it like
  broadcast. Most home routers snoop by default; managed switches may
  need it turned on explicitly.
- **Wi-Fi multicast is less reliable than wired Ethernet.** Multicast
  frames on Wi-Fi are sent unacknowledged at a low basic rate, so with
  many devices or a noisy RF environment you may hear dropouts. This is
  a general Wi-Fi limitation, not a bug in the app.
- If devices are on different VLANs/subnets, plain multicast (TTL=1
  default) won't cross that boundary without multicast routing (PIM)
  configured on your network gear — keep everyone on one subnet for
  simplicity.

## Project layout

```
app/src/main/java/com/multicast/walkietalkie/
  Protocol.kt              wire format: packet types, header layout, constants
  DeviceInfo.kt             data class for a device seen on the channel
  RemoteAudioStream.kt      per-sender AAC decoder + AudioTrack playback
  AudioMulticastService.kt  the foreground service: socket, threads, PTT, encode
  MainActivity.kt           permissions, service binding, UI wiring
  DeviceAdapter.kt          RecyclerView adapter for the device list
app/src/main/res/           layouts, strings, colors, drawables
```

## Things you may want to extend later

- **Jitter buffer / packet-loss concealment** — the sequence number and
  timestamp are already in every audio packet but currently unused;
  a small reorder/jitter buffer in `RemoteAudioStream` would help on
  noisier Wi-Fi.
- **Mute individual devices** — `RemoteAudioStream.setVolume()` already
  supports per-stream volume; wiring a per-row mute button in
  `DeviceAdapter` is a small addition.
- **Switch codec to Opus** if you add a JNI Opus encoder library and
  are OK with it only working on devices where that library builds for
  the target ABI — the packet framing doesn't care what's inside the
  payload.
