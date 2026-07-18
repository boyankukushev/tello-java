# tello-java

A dependency-free Java client for the DJI/Ryze Tello SDK 1.3: send flight commands, receive
telemetry, and view the live video stream with `ffplay`. Pure JDK, no external libraries, no build
tool beyond `javac`/`java` (tested with JDK 25).

## How Tello's Wi-Fi protocol works

Tello exposes three independent UDP channels once you're connected to its Wi-Fi access point
(the aircraft is always `192.168.10.1`):

| Channel  | Direction                          | Port  | Purpose                                              |
|----------|-------------------------------------|-------|-------------------------------------------------------|
| Command  | PC ⇄ Tello                          | 8889  | Send text commands, receive `ok`/`error`/values       |
| State    | Tello → PC                          | 8890  | Continuous telemetry push once SDK mode is active     |
| Video    | Tello → PC                          | 11111 | Raw H.264 elementary stream, pushed after `streamon`   |

The first command sent must always be `command`, which puts the aircraft into SDK mode.
If no command is received for 15 seconds, Tello lands automatically as a safety feature.

### Idle keep-alive (avoiding the 15-second auto-land)

Both consoles start a background thread (`Tello.startIdleKeepAlive`, called once in
`TelloCli.main`) that watches how long it's been since *any* command was last sent to the
aircraft. If it's been flying (since a successful `takeoff`, until `land`/`emergency`) with no
command — manual or automatic — for 10 seconds, it sends a small alternating `cw`/`ccw` rotation
nudge (1 degree each way) to reset Tello's own 15-second timer, leaving a 5-second safety margin.
Alternating direction each time keeps net rotation close to zero over each pair rather than
drifting one way. 1 degree is the SDK's actual minimum for `cw`/`ccw`, so no substitution is needed
here (unlike a movement-based nudge, which would need at least 20cm — Tello's documented minimum
for `left`/`right`/etc.). Each nudge is logged and, in the dashboard, also appended to the command
history in yellow so it's clearly distinguishable from something you typed.

### Why there's a video relay instead of pointing a player straight at Tello

Only one process can bind a given UDP port. Since this application is the one listening on
11111 to receive frames from the aircraft, a media player can't also bind 11111. Instead,
`tello-java` binds 11111 itself and immediately re-sends every received datagram, unchanged, to a
second local port (11112 by default) that the player listens on instead — a plain byte-for-byte
relay, no re-encoding.

## Build and run

```
./build.sh
./run.sh
```

`run.sh` builds automatically if `out/` doesn't exist yet. Useful flags:

```
./run.sh --video-relay-port=11112 --video-relay-host=127.0.0.1 --record-video --ui=auto
```

- `--video-relay-port` / `--video-relay-host`: where the video relay forwards frames (default
  `127.0.0.1:11112`).
- `--record-video`: additionally saves the raw stream to `tello-video-<timestamp>.h264` in the
  current directory (playable/convertible later with `ffplay`/`ffmpeg`).
- `--ui=auto|dashboard|plain`: console mode (default `auto` — see "Dashboard console" below).

Connect your computer to the Tello's Wi-Fi network before running.

## Viewing the video stream

**Why not VLC:** since VLC 3.0, its `udp://` input reads raw (non-MPEG-TS) UDP in fixed
1316-byte (7×188) chunks — a hardcoded MPEG-TS-over-UDP convention with no supported override.
Tello's H.264 packets run close to Ethernet MTU (~1460 bytes), so nearly every packet gets
truncated, corrupting NAL units. This shows up as `udp stream error: ... packet truncated` and
`h26x demux error: this doesn't look like a h264 ES stream`, and forces the decoder to wait for
the next keyframe to resync after almost every packet — the usual cause of multi-second stalls
or, with an aggressively low `--network-caching`, a full buffer-deadlock collapse. None of this is
fixable from the VLC command line for a raw elementary stream, so this project uses `ffplay`
(bundled with ffmpeg) instead, which doesn't have this limitation. Install it with
`apt install ffmpeg` / `brew install ffmpeg` if you don't already have it.

1. In the console, type `command` then `video` (or `streamon`). The console will print the
   `ffplay` command and then **wait for you to press Enter** before actually telling Tello to
   start streaming:

   ```
   ffplay -f h264 -fflags nobuffer -flags low_delay -i udp://@:11112
   ```

   Run that in another terminal *first*, then come back and press Enter. This ordering matters:
   Tello sends its stream header (the H.264 parameter sets every frame after it depends on)
   essentially once, right when streaming starts. If `ffplay` isn't already listening on the relay
   port at that moment, it misses that header for good (UDP has no replay) and has to wait for
   Tello's next periodic resync point, which is what a multi-second initial delay and
   `non-existing PPS referenced` / `decode_slice_header error` messages in ffplay's log mean.

   - `-f h264` tells ffmpeg definitively that this is a raw H.264 Annex-B byte stream, skipping
     generic container auto-probing (the source of "Could not find codec parameters" / "consider
     increasing probesize" messages) entirely.
   - `-fflags nobuffer -flags low_delay` are the standard low-latency flags for a raw live feed.

   If the picture stutters or you see buffer-overrun warnings (common on a busy Wi-Fi link,
   especially since Tello's onboard radio shares bandwidth between the video and command
   channels), give ffmpeg's UDP input more slack instead:

   ```
   ffplay -f h264 -fflags nobuffer -flags low_delay -i "udp://@:11112?fifo_size=1000000&overrun_nonfatal=1"
   ```

   `fifo_size`/`overrun_nonfatal` enlarge ffmpeg's own UDP receive queue and stop it from treating
   a momentary overrun as fatal, at the cost of a little more latency under load.

## Console commands

```
Control:   command takeoff land emergency streamon streamoff
Movement:  up x | down x | left x | right x | forward x | back x   (x: 20-500 cm)
Rotation:  cw x | ccw x                                            (x: 1-3600 deg)
Flip:      flip l|r|f|b
Go/Curve:  go x y z speed | curve x1 y1 z1 x2 y2 z2 speed
Set:       speed x (10-100) | rc a b c d (-100..100) | wifi ssid pass
Read:      speed? battery? time? height? temp? attitude? baro? acceleration? tof? wifi?
Extra:     video (start ffplay relay) | state (print telemetry) | help | end
```

`rc a b c d` (left/right, forward/backward, up/down, yaw, each -100..100) is fire-and-forget by
design: real RC control is a continuous stream, so waiting for a response per packet isn't
practical.

## Dashboard console

When run in a real, interactive terminal at least 32 rows by 90 columns, `tello-java` switches
automatically to a full-screen dashboard: an ASCII "TELLO" banner and current configuration
(addresses/ports) at the top, a command box on the left (colored history: cyan for the command
you typed, green for success, yellow for an unrecognized command, red for errors), and a live
flight-data panel on the right, refreshing twice a second, with a static command reference below
it. Piped/redirected input (including how this project's own testing has been done throughout)
automatically falls back to the plain scrolling console instead — no ANSI codes, no terminal size
requirement — so nothing about scripting or automation changes.

No raw terminal mode is used anywhere: the command box is a completely normal, line-buffered
prompt (backspace and line editing behave exactly as your terminal already handles them). Only the
right-hand flight panel updates on its own timer, using ANSI cursor save/move/restore so it never
touches whatever you're mid-typing in the command box.

The dashboard runs in the terminal's *alternate screen buffer* (the same mechanism vim/htop/less
use), entered on start and exited on quit. This gives it a stable, scroll-isolated canvas: without
it, a real scroll event (window resize, a mouse-wheel scroll, or any other interaction that shifts
the terminal's actual scrollback) would desync the dashboard's fixed row positions from what's
physically on screen, permanently, for the rest of the session — which is what caused the flight
data panel to drift down over a long session and progressively overwrite the command help section.
On exit, the terminal's original content and cursor position are restored automatically, exactly as
they were before the dashboard started.

Force one mode or the other with `--ui=dashboard` or `--ui=plain` (default `--ui=auto`, i.e. the
terminal-detection behavior above). If the dashboard is requested but the terminal is smaller than
32x90 (or its size can't be detected), `tello-java` prints a notice and falls back to the plain
console rather than drawing a broken layout.

The `video`/`streamon` confirm-before-streaming step (see above) works the same way in the
dashboard: the `ffplay` command appears in the command box's history, and you press Enter there
once it's running, exactly as in the plain console.

## Project layout

```
src/com/tello/core/    Command connection, state receiver, the Tello facade, response parsing
src/com/tello/video/   TelloVideoRelay: receives from Tello on 11111, forwards to ffplay
src/com/tello/cli/     TelloCli (main class, plain console), Dashboard (full-screen console),
                       AnsiTerminal (ANSI escape-code helpers, terminal size detection)
```

## Troubleshooting

**Commands feel slow, or you see a `No response yet for '...' (attempt N/3), retrying...`
warning**: Tello has a single, bandwidth-limited onboard Wi-Fi radio shared by the command,
telemetry, and video channels — streaming video can measurably slow down command responses. The
client waits up to 7 seconds per attempt (3 attempts) before giving up on a command, and now logs
a warning each time it has to retry so a slow response doesn't look like a silent hang. This is
intentionally not made more aggressive: Tello has no per-command request IDs, so shortening the
timeout would increase the odds of resending a flight command while the original is still being
executed, which is worse than an occasional slow reply.

**`Error: Command failed: error No valid imu`**: this is the aircraft's own firmware response,
relayed as-is — not a bug in this client. It means Tello's IMU wasn't ready for that command,
typically because a flight command was sent too soon after `command`, or the aircraft was moved or
sitting on an uneven surface during its startup calibration. Give it a moment after entering SDK
mode before flight commands, keep it still and level, and power-cycle it if the error persists.

## Using it as a library

```java
try (Tello tello = new Tello()) {
    tello.connect();
    tello.takeOff();
    tello.forward(50);
    tello.land();
}
```

`Tello` throws `TelloException` on command failures/timeouts and `IllegalArgumentException` on
out-of-range parameters, validated client-side against the documented limits before anything is
sent to the aircraft.
