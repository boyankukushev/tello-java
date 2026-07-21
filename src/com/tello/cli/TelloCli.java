package com.tello.cli;

import com.tello.core.FlipDirection;
import com.tello.core.Tello;
import com.tello.core.TelloException;
import com.tello.core.TelloStateReceiver;
import com.tello.video.TelloVideoRelay;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Interactive command-line console for controlling a DJI/Ryze Tello over Wi-Fi, following the
 * Tello SDK 1.3 command set, plus a video relay so the live stream can be viewed with ffplay.
 */
public final class TelloCli {

    private static final int DEFAULT_VIDEO_RELAY_PORT = 11112;
    private static final Duration IDLE_KEEPALIVE_THRESHOLD = Duration.ofSeconds(10);

    public static void main(String[] args) throws Exception {
        int videoRelayPort = DEFAULT_VIDEO_RELAY_PORT;
        String videoRelayHost = "127.0.0.1";
        boolean recordVideo = false;
        String uiMode = "auto";

        for (String arg : args) {
            if (arg.startsWith("--video-relay-port=")) {
                videoRelayPort = Integer.parseInt(arg.substring("--video-relay-port=".length()));
            } else if (arg.startsWith("--video-relay-host=")) {
                videoRelayHost = arg.substring("--video-relay-host=".length());
            } else if (arg.equals("--record-video")) {
                recordVideo = true;
            } else if (arg.startsWith("--ui=")) {
                uiMode = arg.substring("--ui=".length());
            }
        }

        Tello tello = new Tello();
        tello.startIdleKeepAlive(IDLE_KEEPALIVE_THRESHOLD);
        TelloStateReceiver stateReceiver = new TelloStateReceiver();
        TelloVideoRelay[] videoRelay = new TelloVideoRelay[1];

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutting down...");
            if (videoRelay[0] != null) {
                videoRelay[0].close();
            }
            stateReceiver.close();
            tello.close();
        }));

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

        boolean wantDashboard = "dashboard".equals(uiMode) || ("auto".equals(uiMode) && System.console() != null);
        if (wantDashboard) {
            var size = AnsiTerminal.detectSize();
            if (size.isPresent() && size.get().rows() >= Dashboard.MIN_ROWS && size.get().cols() >= Dashboard.MIN_COLS) {
                new Dashboard(tello, stateReceiver, videoRelay, videoRelayHost, videoRelayPort, recordVideo, in,
                        size.get()).run();
            } else {
                System.out.println("Terminal too small or size undetectable for the dashboard (need at least "
                        + Dashboard.MIN_ROWS + "x" + Dashboard.MIN_COLS + "), using the plain console.");
                runPlainConsole(tello, videoRelay, videoRelayHost, videoRelayPort, recordVideo, in);
            }
        } else {
            runPlainConsole(tello, videoRelay, videoRelayHost, videoRelayPort, recordVideo, in);
        }

        if (videoRelay[0] != null) {
            videoRelay[0].close();
        }
        stateReceiver.close();
        tello.close();
        System.out.println("Bye.");
    }

    private static void runPlainConsole(Tello tello, TelloVideoRelay[] videoRelay,
            String videoRelayHost, int videoRelayPort, boolean recordVideo, BufferedReader in) throws Exception {
        printBanner(videoRelayHost, videoRelayPort);

        String line;
        System.out.print("> ");
        while ((line = in.readLine()) != null) {
            String input = line.trim();
            if (input.isEmpty()) {
                System.out.print("> ");
                continue;
            }
            if (input.equalsIgnoreCase("end") || input.equalsIgnoreCase("exit") || input.equalsIgnoreCase("quit")) {
                break;
            }

            try {
                switch (input.toLowerCase(Locale.ROOT)) {
                    case "video", "streamon" -> {
                        if (videoRelay[0] == null) {
                            FileOutputStream recording = recordVideo
                                    ? new FileOutputStream("tello-video-" + System.currentTimeMillis() + ".h264")
                                    : null;
                            InetSocketAddress target = TelloVideoRelay.target(videoRelayHost, videoRelayPort);
                            videoRelay[0] = new TelloVideoRelay(TelloVideoRelay.DEFAULT_VIDEO_PORT, List.of(target), recording);
                            System.out.println("In another terminal, start:");
                            System.out.println("  ffplay -f h264 -fflags nobuffer -flags low_delay -i udp://@:"
                                    + videoRelayPort);
                            System.out.println("Or: vlc udp://@:" + videoRelayPort + " :demux=h264");
                            System.out.println("Tello sends its stream header only once, so ffplay/vlc needs to "
                                    + "already be listening before streaming starts, or it may show a garbled "
                                    + "picture until the next resync.");
                            System.out.print("Press Enter once ffplay/vlc is running and connected... ");
                            in.readLine();
                        }
                        tello.streamOn();
                        System.out.println("Streaming.");
                    }
                    case "streamoff" -> {
                        tello.streamOff();
                        if (videoRelay[0] != null) {
                            videoRelay[0].close();
                            videoRelay[0] = null;
                        }
                    }
                    default -> {
                        String result = dispatchCommand(tello, input);
                        if (!result.isEmpty()) {
                            System.out.println(result);
                        }
                    }
                }
            } catch (IllegalArgumentException | TelloException | IOException e) {
                System.out.println("Error: " + e.getMessage());
            }
            System.out.print("> ");
        }
    }

    /** Executes an ordinary SDK command and returns its result text ({@code ""} for fire-and-forget commands). */
    static String dispatchCommand(Tello tello, String input) {
        String[] parts = input.split("\\s+");
        String cmd = parts[0].toLowerCase(Locale.ROOT);
        return switch (cmd) {
            case "command" -> {
                tello.connect();
                yield "ok";
            }
            case "takeoff" -> {
                tello.takeOff();
                yield "ok";
            }
            case "land" -> {
                tello.land();
                yield "ok";
            }
            case "up" -> {
                tello.up(intArg(parts, 1));
                yield "ok";
            }
            case "down" -> {
                tello.down(intArg(parts, 1));
                yield "ok";
            }
            case "left" -> {
                tello.left(intArg(parts, 1));
                yield "ok";
            }
            case "right" -> {
                tello.right(intArg(parts, 1));
                yield "ok";
            }
            case "forward" -> {
                tello.forward(intArg(parts, 1));
                yield "ok";
            }
            case "back" -> {
                tello.back(intArg(parts, 1));
                yield "ok";
            }
            case "cw" -> {
                tello.clockwise(intArg(parts, 1));
                yield "ok";
            }
            case "ccw" -> {
                tello.counterClockwise(intArg(parts, 1));
                yield "ok";
            }
            case "flip" -> {
                tello.flip(flipArg(parts, 1));
                yield "ok";
            }
            case "go" -> {
                tello.go(intArg(parts, 1), intArg(parts, 2), intArg(parts, 3), intArg(parts, 4));
                yield "ok";
            }
            case "curve" -> {
                tello.curve(intArg(parts, 1), intArg(parts, 2), intArg(parts, 3),
                        intArg(parts, 4), intArg(parts, 5), intArg(parts, 6), intArg(parts, 7));
                yield "ok";
            }
            case "speed" -> {
                tello.setSpeed(intArg(parts, 1));
                yield "ok";
            }
            case "wifi" -> {
                tello.setWifi(stringArg(parts, 1), stringArg(parts, 2));
                yield "ok";
            }
            case "speed?" -> String.valueOf(tello.getSpeed());
            case "battery?" -> String.valueOf(tello.getBattery());
            case "time?" -> tello.getFlightTime();
            case "height?" -> String.valueOf(tello.getHeight());
            case "temp?" -> tello.getTemperature();
            case "attitude?" -> String.valueOf(tello.getAttitude());
            case "baro?" -> String.valueOf(tello.getBarometer());
            case "acceleration?" -> String.valueOf(tello.getAcceleration());
            case "tof?" -> String.valueOf(tello.getTof());
            case "wifi?" -> tello.getWifiSnr();
            default -> "Unknown command: " + cmd;
        };
    }

    private static int intArg(String[] parts, int index) {
        if (index >= parts.length) {
            throw new IllegalArgumentException("Missing argument at position " + index);
        }
        return Integer.parseInt(parts[index]);
    }

    private static String stringArg(String[] parts, int index) {
        if (index >= parts.length) {
            throw new IllegalArgumentException("Missing argument at position " + index);
        }
        return parts[index];
    }

    private static FlipDirection flipArg(String[] parts, int index) {
        if (index >= parts.length) {
            throw new IllegalArgumentException("Missing flip direction (l, r, f or b)");
        }
        String code = parts[index].toLowerCase(Locale.ROOT);
        return switch (code) {
            case "l" -> FlipDirection.LEFT;
            case "r" -> FlipDirection.RIGHT;
            case "f" -> FlipDirection.FORWARD;
            case "b" -> FlipDirection.BACK;
            default -> throw new IllegalArgumentException("Unknown flip direction: " + code);
        };
    }

    private static void printBanner(String videoRelayHost, int videoRelayPort) {
        System.out.println("""
                Tello Java Console
                Type 'command' to enter SDK mode, then 'takeoff', 'land', etc.
                Type 'video' or 'streamon' to start the video relay for ffplay/vlc (%s:%d).
                Type 'end' to quit.
                """.formatted(videoRelayHost, videoRelayPort));
    }
}
