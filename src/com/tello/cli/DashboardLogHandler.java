package com.tello.cli;

import java.util.function.BiConsumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * Routes {@code java.util.logging} records into the dashboard's own LOG panel instead of the
 * JVM's default {@code ConsoleHandler}, which would otherwise write raw, unpositioned text over
 * the dashboard's fixed-row alt-screen layout.
 */
final class DashboardLogHandler extends Handler {

    private final BiConsumer<String, String> sink;

    DashboardLogHandler(BiConsumer<String, String> sink) {
        this.sink = sink;
        setLevel(Level.INFO);
    }

    @Override
    public void publish(LogRecord record) {
        if (!isLoggable(record)) {
            return;
        }
        String color = record.getLevel().intValue() >= Level.WARNING.intValue()
                ? AnsiTerminal.RED
                : AnsiTerminal.DIM;
        String message = record.getMessage();
        if (record.getThrown() != null) {
            message += ": " + record.getThrown().getMessage();
        }
        sink.accept(message, color);
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }
}
