package com.tello.cli;

import com.tello.core.Tello;
import com.tello.core.TelloConnection;
import com.tello.core.TelloException;
import com.tello.core.TelloState;
import com.tello.core.TelloStateReceiver;
import com.tello.video.TelloVideoRelay;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Full-screen live console: a header banner and configuration summary, a command box (left,
 * normal line-buffered input so terminal line editing works unmodified) and a flight-data panel
 * (right) that refreshes every 3 seconds. No raw terminal mode is used: the refresh thread only
 * ever touches the right panel's own rows/columns via ANSI save/move/write/restore-cursor, which
 * puts the cursor back exactly where the terminal's own line editor left it, so it never
 * interferes with whatever the user is mid-typing in the command box.
 */
final class Dashboard {

    static final int MIN_ROWS = 32;
    static final int MIN_COLS = 90;

    private static final String[] GLYPH_T = {"#####", "  #  ", "  #  ", "  #  ", "  #  "};
    private static final String[] GLYPH_E = {"#####", "#    ", "#### ", "#    ", "#####"};
    private static final String[] GLYPH_L = {"#    ", "#    ", "#    ", "#    ", "#####"};
    private static final String[] GLYPH_O = {" ### ", "#   #", "#   #", "#   #", " ### "};

    private static final String[][] COMMAND_HELP = {
            {"command", "enter SDK mode"},
            {"takeoff / land", "auto takeoff / land"},
            {"emergency", "stop all motors now"},
            {"up/down/left/right x", "move x cm (20-500)"},
            {"forward/back x", "move x cm (20-500)"},
            {"cw/ccw x", "rotate x deg (1-3600)"},
            {"flip l/r/f/b", "flip in place"},
            {"go x y z speed", "fly to point (10-100)"},
            {"curve ...", "fly a curve (speed 10-60)"},
            {"speed x", "set speed cm/s (10-100)"},
            {"rc a b c d", "RC control (-100..100)"},
            {"wifi ssid pass", "change Wi-Fi"},
            {"speed?", "get speed"},
            {"battery?", "get battery %"},
            {"time?", "get flight time"},
            {"height?", "get height"},
            {"temp?", "get temperature"},
            {"attitude?", "get pitch/roll/yaw"},
            {"baro?", "get barometer"},
            {"acceleration?", "get acceleration"},
            {"tof?", "get ToF distance"},
            {"wifi?", "get Wi-Fi SNR"},
            {"video / streamon", "start ffplay relay"},
            {"streamoff", "stop video"},
            {"state", "flight data (this panel)"},
            {"help", "this list"},
            {"end/exit/quit", "quit"},
    };

    private final Tello tello;
    private final TelloStateReceiver stateReceiver;
    private final TelloVideoRelay[] videoRelay;
    private final String videoRelayHost;
    private final int videoRelayPort;
    private final boolean recordVideo;
    private final BufferedReader in;

    private final int rows;
    private final int cols;
    private final int leftWidth;
    private final int rightWidth;
    private final int rightStartCol;
    private final int panelTop;
    private final int rightStatsTop;
    private final int rightHelpTop;
    private final int dividerBeforePromptRow;
    private final int promptRow;
    private final int historyTop;
    private final int historyBottom;
    private final int historyHeight;

    private final Deque<String> history = new ArrayDeque<>();
    private final Object screenLock = new Object();
    private volatile boolean running = true;

    Dashboard(Tello tello, TelloStateReceiver stateReceiver, TelloVideoRelay[] videoRelay,
            String videoRelayHost, int videoRelayPort, boolean recordVideo, BufferedReader in,
            AnsiTerminal.Size size) {
        this.tello = tello;
        this.stateReceiver = stateReceiver;
        this.videoRelay = videoRelay;
        this.videoRelayHost = videoRelayHost;
        this.videoRelayPort = videoRelayPort;
        this.recordVideo = recordVideo;
        this.in = in;

        this.rows = size.rows();
        this.cols = size.cols();
        this.leftWidth = (int) ((cols - 3) * 0.65);
        this.rightWidth = cols - 3 - leftWidth;
        this.rightStartCol = leftWidth + 4;
        this.panelTop = 11;
        this.rightStatsTop = panelTop;
        this.rightHelpTop = rightStatsTop + 1 + 11 + 1 + 1; // title, 11 stats, blank, "COMMANDS" title
        this.promptRow = rows - 1;
        this.dividerBeforePromptRow = promptRow - 1;
        this.historyTop = panelTop;
        this.historyBottom = dividerBeforePromptRow - 1;
        this.historyHeight = Math.max(1, historyBottom - historyTop + 1);
    }

    void run() throws IOException {
        tello.onIdleKeepAlive(message -> {
            appendHistory(message, AnsiTerminal.YELLOW);
            redrawLeftPanel();
        });

        System.out.print(AnsiTerminal.ENTER_ALT_SCREEN);
        System.out.flush();

        drawStatic();
        renderStats(stateReceiver.latestState());
        redrawLeftPanel();

        Thread refresher = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (running) {
                    renderStats(stateReceiver.latestState());
                }
            }
        }, "dashboard-refresh");
        refresher.setDaemon(true);
        refresher.start();

        try {
            mainLoop();
        } finally {
            running = false;
            refresher.interrupt();
            System.out.print(AnsiTerminal.EXIT_ALT_SCREEN);
            System.out.flush();
        }
    }

    private void mainLoop() throws IOException {
        String line;
        while ((line = in.readLine()) != null) {
            String input = line.trim();
            if (input.isEmpty()) {
                redrawLeftPanel();
                continue;
            }
            if (input.equalsIgnoreCase("end") || input.equalsIgnoreCase("exit") || input.equalsIgnoreCase("quit")) {
                return;
            }

            appendHistory("> " + input, AnsiTerminal.CYAN);
            try {
                switch (input.toLowerCase(Locale.ROOT)) {
                    case "help" -> appendHistory("See the command list in the right panel.", AnsiTerminal.GREEN);
                    case "state" -> appendHistory(
                            "See flight data in the right panel (updates every 3s).", AnsiTerminal.GREEN);
                    case "video", "streamon" -> handleVideoOn();
                    case "streamoff" -> {
                        tello.streamOff();
                        if (videoRelay[0] != null) {
                            videoRelay[0].close();
                            videoRelay[0] = null;
                        }
                        appendHistory("Streaming stopped.", AnsiTerminal.GREEN);
                    }
                    default -> {
                        String result = TelloCli.dispatchCommand(tello, input);
                        if (!result.isEmpty()) {
                            String color = result.startsWith("Unknown command:") ? AnsiTerminal.YELLOW : AnsiTerminal.GREEN;
                            appendHistory(result, color);
                        }
                    }
                }
            } catch (IllegalArgumentException | TelloException | IOException e) {
                appendHistory("Error: " + e.getMessage(), AnsiTerminal.RED);
            }
            redrawLeftPanel();
        }
    }

    private void handleVideoOn() throws IOException {
        if (videoRelay[0] == null) {
            FileOutputStream recording = recordVideo
                    ? new FileOutputStream("tello-video-" + System.currentTimeMillis() + ".h264")
                    : null;
            InetSocketAddress target = TelloVideoRelay.target(videoRelayHost, videoRelayPort);
            videoRelay[0] = new TelloVideoRelay(TelloVideoRelay.DEFAULT_VIDEO_PORT, List.of(target), recording);
            appendHistory("Run: ffplay -f h264 -fflags nobuffer -flags low_delay -i udp://@:" + videoRelayPort,
                    AnsiTerminal.YELLOW);
            appendHistory("Start ffplay BEFORE continuing, or it may miss the stream header.", AnsiTerminal.YELLOW);
            redrawLeftPanel();
            waitForEnter("Press Enter once ffplay is running and connected... ");
        }
        tello.streamOn();
        appendHistory("Streaming.", AnsiTerminal.GREEN);
    }

    private void waitForEnter(String prompt) throws IOException {
        synchronized (screenLock) {
            System.out.print(AnsiTerminal.moveTo(promptRow, 1) + AnsiTerminal.pad(prompt, leftWidth));
            System.out.flush();
        }
        in.readLine();
    }

    private void appendHistory(String plainText, String color) {
        synchronized (screenLock) {
            history.addLast(color + AnsiTerminal.pad(plainText, leftWidth) + AnsiTerminal.RESET);
            while (history.size() > historyHeight) {
                history.removeFirst();
            }
        }
    }

    private void redrawLeftPanel() {
        synchronized (screenLock) {
            StringBuilder sb = new StringBuilder();
            List<String> lines = new ArrayList<>(history);
            for (int i = 0; i < historyHeight; i++) {
                int row = historyTop + i;
                String content = i < lines.size() ? lines.get(i) : AnsiTerminal.pad("", leftWidth);
                sb.append(AnsiTerminal.moveTo(row, 1)).append(content);
            }
            sb.append(AnsiTerminal.moveTo(dividerBeforePromptRow, 1))
                    .append(AnsiTerminal.DIM)
                    .append(AnsiTerminal.pad("-".repeat(leftWidth), leftWidth))
                    .append(AnsiTerminal.RESET);
            sb.append(AnsiTerminal.moveTo(promptRow, 1)).append(AnsiTerminal.pad("> ", leftWidth));
            sb.append(AnsiTerminal.moveTo(promptRow, 3));
            System.out.print(sb);
            System.out.flush();
        }
    }

    private void renderStats(TelloState state) {
        synchronized (screenLock) {
            StringBuilder sb = new StringBuilder();
            sb.append(AnsiTerminal.SAVE_CURSOR);
            int row = rightStatsTop;
            sb.append(AnsiTerminal.moveTo(row++, rightStartCol))
                    .append(AnsiTerminal.BOLD).append(AnsiTerminal.CYAN)
                    .append(AnsiTerminal.pad("FLIGHT DATA  (" + AnsiTerminal.time() + ")", rightWidth))
                    .append(AnsiTerminal.RESET);
            row++; // blank separator row, left untouched
            for (String[] field : statRows(state)) {
                String formatted = String.format(Locale.ROOT, "%-9s%s", field[0], field[1]);
                sb.append(AnsiTerminal.moveTo(row++, rightStartCol)).append(AnsiTerminal.pad(formatted, rightWidth));
            }
            sb.append(AnsiTerminal.RESTORE_CURSOR);
            System.out.print(sb);
            System.out.flush();
        }
    }

    private static List<String[]> statRows(TelloState s) {
        if (s == null) {
            return List.of(
                    new String[] {"Battery", "--"}, new String[] {"Time", "--"}, new String[] {"ToF", "--"},
                    new String[] {"Temp", "--"}, new String[] {"Baro", "--"}, new String[] {"Speed", "--"},
                    new String[] {"Accel", "--"}, new String[] {"Height", "--"}, new String[] {"Pitch", "--"},
                    new String[] {"Roll", "--"}, new String[] {"Yaw", "--"});
        }
        return List.of(
                new String[] {"Battery", s.bat() + "%"},
                new String[] {"Time", s.time() + "s"},
                new String[] {"ToF", s.tof() + "cm"},
                new String[] {"Temp", s.templ() + "-" + s.temph() + "C"},
                new String[] {"Baro", String.format(Locale.ROOT, "%.2f", s.baro())},
                new String[] {"Speed", String.format(Locale.ROOT, "(%d,%d,%d)", s.vgx(), s.vgy(), s.vgz())},
                new String[] {"Accel", String.format(Locale.ROOT, "(%.2f,%.2f,%.2f)", s.agx(), s.agy(), s.agz())},
                new String[] {"Height", s.h() + "cm"},
                new String[] {"Pitch", String.valueOf(s.pitch())},
                new String[] {"Roll", String.valueOf(s.roll())},
                new String[] {"Yaw", String.valueOf(s.yaw())});
    }

    private void drawStatic() {
        StringBuilder sb = new StringBuilder();
        sb.append(AnsiTerminal.CLEAR_SCREEN);

        String[] banner = banner();
        for (int i = 0; i < banner.length; i++) {
            sb.append(AnsiTerminal.moveTo(1 + i, 1)).append(AnsiTerminal.BOLD).append(AnsiTerminal.CYAN)
                    .append(banner[i]).append(AnsiTerminal.RESET);
        }

        String configLine1 = String.format(Locale.ROOT,
                "Tello %s:%d   local cmd :%d   state :%d",
                TelloConnection.DEFAULT_TELLO_HOST, TelloConnection.DEFAULT_TELLO_PORT,
                TelloConnection.DEFAULT_LOCAL_PORT, TelloStateReceiver.DEFAULT_STATE_PORT);
        String configLine2 = String.format(Locale.ROOT,
                "Video drone->relay :%d   relay->ffplay %s:%d",
                TelloVideoRelay.DEFAULT_VIDEO_PORT, videoRelayHost, videoRelayPort);
        sb.append(AnsiTerminal.moveTo(7, 1)).append(AnsiTerminal.pad(configLine1, cols));
        sb.append(AnsiTerminal.moveTo(8, 1)).append(AnsiTerminal.pad(configLine2, cols));
        sb.append(AnsiTerminal.moveTo(10, 1)).append(AnsiTerminal.DIM)
                .append(AnsiTerminal.repeat("-", cols)).append(AnsiTerminal.RESET);

        int row = rightHelpTop;
        sb.append(AnsiTerminal.moveTo(row++, rightStartCol)).append(AnsiTerminal.BOLD).append(AnsiTerminal.CYAN)
                .append(AnsiTerminal.pad("COMMANDS", rightWidth)).append(AnsiTerminal.RESET);
        int helpRowsAvailable = Math.max(0, historyBottom - row + 1);
        for (int i = 0; i < COMMAND_HELP.length && i < helpRowsAvailable; i++) {
            String formatted = String.format(Locale.ROOT, "%-18s%s", COMMAND_HELP[i][0], COMMAND_HELP[i][1]);
            sb.append(AnsiTerminal.moveTo(row++, rightStartCol)).append(AnsiTerminal.pad(formatted, rightWidth));
        }

        System.out.print(sb);
        System.out.flush();
    }

    private static String[] banner() {
        String[][] glyphs = {GLYPH_T, GLYPH_E, GLYPH_L, GLYPH_L, GLYPH_O};
        String[] result = new String[5];
        for (int r = 0; r < 5; r++) {
            StringBuilder sb = new StringBuilder();
            for (int g = 0; g < glyphs.length; g++) {
                if (g > 0) {
                    sb.append(' ');
                }
                sb.append(glyphs[g][r]);
            }
            result[r] = sb.toString();
        }
        return result;
    }
}
