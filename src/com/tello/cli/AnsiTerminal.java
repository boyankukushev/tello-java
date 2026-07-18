package com.tello.cli;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.Optional;

/** ANSI escape-code helpers and terminal size detection for the dashboard console. */
final class AnsiTerminal {

    static final String RESET = "[0m";
    static final String BOLD = "[1m";
    static final String DIM = "[2m";
    static final String CYAN = "[36m";
    static final String GREEN = "[32m";
    static final String RED = "[31m";
    static final String YELLOW = "[33m";
    static final String CLEAR_SCREEN = "[2J[H";
    static final String SAVE_CURSOR = "7";
    static final String RESTORE_CURSOR = "8";

    private AnsiTerminal() {
    }

    static String moveTo(int row, int col) {
        return "[" + row + ";" + col + "H";
    }

    /** Pads or truncates plain (uncolored) text to exactly {@code width} visible columns. */
    static String pad(String text, int width) {
        if (text.length() >= width) {
            return text.substring(0, width);
        }
        return text + " ".repeat(width - text.length());
    }

    record Size(int rows, int cols) {
    }

    /** Detects the controlling terminal's size via {@code stty size}; empty if it can't be determined. */
    static Optional<Size> detectSize() {
        try {
            ProcessBuilder pb = new ProcessBuilder("stty", "size");
            pb.redirectInput(new File("/dev/tty"));
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            boolean finished = process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return Optional.empty();
            }
            if (process.exitValue() != 0) {
                return Optional.empty();
            }
            String[] parts = output.trim().split("\\s+");
            if (parts.length != 2) {
                return Optional.empty();
            }
            return Optional.of(new Size(Integer.parseInt(parts[0]), Integer.parseInt(parts[1])));
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        }
    }

    static String repeat(String s, int width) {
        if (s.isEmpty() || width <= 0) {
            return "";
        }
        return s.repeat(width / s.length() + 1).substring(0, width);
    }

    static String time() {
        return java.time.LocalTime.now().withNano(0).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT));
    }
}
