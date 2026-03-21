# Session Context: Audio Sink Disconnect Investigation

## What We're Doing
Investigating and fixing periodic playback disconnects in AdagioStream Android app. The app is an audio streaming app using ExoPlayer (Media3 1.5.1) for HLS/MPEG-TS live streams.

## The Problem
Playback stalls periodically (~30 minutes, sometimes sooner). Audio stops, then reconnects — but the listener loses content because the reconnect jumps to the live edge of the stream.

## Root Cause Found
`DefaultAudioSink.UnexpectedDiscontinuityException` — ExoPlayer's audio sink detects a timestamp discontinuity (hardware AudioTrack timestamp resets to exactly `1000000000000` ns) and panics. This does NOT fire `onPlayerError` — it's swallowed internally by `MediaCodecAudioRenderer`, which just logs and dispatches to `AnalyticsListener.onAudioSinkError()`.

Happens on both Samsung A16 (Exynos) and Moto G Power 2024 (Snapdragon) — not hardware-specific. Related issue: https://github.com/google/ExoPlayer/issues/11012

## Key Discovery
`DefaultAudioSink` already has self-healing: when the discontinuity is detected, it sets `startMediaTimeUsNeedsSync=true`, drains pending audio, and resyncs on the next buffer. The exception is NOT thrown — it's only reported to a listener. The audio data is fine; it's just a timestamp bookkeeping error.

## Build History
- **Build 0026**: Added diagnostic logging to all ExoPlayer callbacks (`AdagioPlayer` tag). Confirmed `onPlayerError` never fires. Stall detector (9s delay) was the only recovery path.
- **Build 0027**: Added `onAudioSinkError` handler with immediate `seekToDefaultPosition() + prepare() + play()`. Faster recovery (~2-3s) but still loses content by jumping to live edge.
- **Build 0028** (CURRENT, on device): Changed `onAudioSinkError` to just log and let DefaultAudioSink self-heal. Stall detector remains as safety net. This is the test — if self-healing works, there's zero gap and zero content loss.

## What's Being Tested Right Now
Build 0028 is installed on the Samsung A16. Logcat is capturing via:
```bash
adb logcat -v time 'AdagioPlayer:*' 'MediaCodecAudioRenderer:*' 'AudioTrack:*' 'ExoPlayerImpl:*' '*:S' > /tmp/adagio-logcat.txt
```

**Expected outcomes:**
- If self-healing works: `onAudioSinkError: ... — letting sink self-heal` in logs, then NO stall detector entries. Playback continues seamlessly.
- If self-healing fails: stall detector fires after ~6s, does full re-prepare (loses content).

## Next Steps Based on Results
- **If self-heal works**: Clean up diagnostic logging, commit, push. Done.
- **If self-heal fails**: Implement `ForwardingAudioSink` via custom `DefaultRenderersFactory` to intercept the discontinuity at the audio sink level and prevent it from disrupting the pipeline. The API supports this:
  - `ForwardingAudioSink` exists in Media3 1.5.x (wraps any `AudioSink`)
  - `DefaultRenderersFactory.buildAudioSink()` is `protected` and overridable
  - Pass custom factory to `ExoPlayer.Builder.setRenderersFactory()`

## Key Files
- `app/src/main/java/com/adagiostream/android/service/player/ExoPlayerWrapper.kt` — Main player wrapper with all recovery logic
- `app/src/main/java/com/adagiostream/android/service/player/AudioPlaybackService.kt` — Media3 foreground service
- `app/src/main/java/com/adagiostream/android/di/NetworkModule.kt` — OkHttp config

## Unpushed Changes
Builds 0026-0028 are NOT pushed to git. User wants to test before pushing. Changes on `dev` branch.

## Device Setup
- Samsung A16 connected via USB, USB debugging enabled
- Device ID: R5GL151MF4Z
- ADB path: `~/Library/Android/sdk/platform-tools/adb`
- Log filter tag: `AdagioPlayer`
