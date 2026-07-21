package com.tello.core;

import java.net.SocketException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * High-level facade over the Tello SDK 1.3 command set (see the SDK documentation, section 4).
 * One method per documented command; movement/rotation/speed arguments are validated against the
 * documented ranges before anything is sent over the network.
 */
public final class Tello implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(Tello.class.getName());

    private static final int MIN_DISTANCE = 20;
    private static final int MAX_DISTANCE = 500;
    private static final int MIN_DEGREE = 1;
    private static final int MAX_DEGREE = 3600;
    private static final int MIN_SPEED = 10;
    private static final int MAX_SPEED = 100;
    private static final int MIN_CURVE_SPEED = 10;
    private static final int MAX_CURVE_SPEED = 60;
    private static final int CURVE_DEAD_ZONE = 20;

    private final TelloConnection connection;
    private volatile boolean flying = false;
    private Thread keepAliveThread;

    public Tello() throws SocketException, UnknownHostException {
        this(new TelloConnection());
    }

    public Tello(TelloConnection connection) {
        this.connection = connection;
    }

    /** Enters SDK mode. Must be sent before any other command. */
    public void connect() {
        expectOk(connection.sendCommandAndWait("command"));
    }

    public void takeOff() {
        expectOk(connection.sendCommandAndWait("takeoff"));
        flying = true;
    }

    public void land() {
        expectOk(connection.sendCommandAndWait("land"));
        flying = false;
    }

    public void streamOn() {
        expectOk(connection.sendCommandAndWait("streamon"));
    }

    public void streamOff() {
        expectOk(connection.sendCommandAndWait("streamoff"));
    }

    /** Whether {@link #takeOff()} has succeeded more recently than {@link #land()}. */
    public boolean isFlying() {
        return flying;
    }

    public void up(int cm) {
        expectOk(connection.sendCommandAndWait("up " + requireDistance(cm)));
    }

    public void down(int cm) {
        expectOk(connection.sendCommandAndWait("down " + requireDistance(cm)));
    }

    public void left(int cm) {
        expectOk(connection.sendCommandAndWait("left " + requireDistance(cm)));
    }

    public void right(int cm) {
        expectOk(connection.sendCommandAndWait("right " + requireDistance(cm)));
    }

    public void forward(int cm) {
        expectOk(connection.sendCommandAndWait("forward " + requireDistance(cm)));
    }

    public void back(int cm) {
        expectOk(connection.sendCommandAndWait("back " + requireDistance(cm)));
    }

    public void clockwise(int degrees) {
        expectOk(connection.sendCommandAndWait("cw " + requireDegree(degrees)));
    }

    public void counterClockwise(int degrees) {
        expectOk(connection.sendCommandAndWait("ccw " + requireDegree(degrees)));
    }

    public void flip(FlipDirection direction) {
        expectOk(connection.sendCommandAndWait("flip " + direction.code()));
    }

    public void go(int x, int y, int z, int speed) {
        requireRange(x, -MAX_DISTANCE, MAX_DISTANCE, "x");
        requireRange(y, -MAX_DISTANCE, MAX_DISTANCE, "y");
        requireRange(z, -MAX_DISTANCE, MAX_DISTANCE, "z");
        requireRange(speed, MIN_SPEED, MAX_SPEED, "speed");
        expectOk(connection.sendCommandAndWait(String.format(Locale.ROOT, "go %d %d %d %d", x, y, z, speed)));
    }

    public void curve(int x1, int y1, int z1, int x2, int y2, int z2, int speed) {
        requireRange(speed, MIN_CURVE_SPEED, MAX_CURVE_SPEED, "speed");
        requireNotAllWithinDeadZone(x1, y1, z1);
        requireNotAllWithinDeadZone(x2, y2, z2);
        expectOk(connection.sendCommandAndWait(
                String.format(Locale.ROOT, "curve %d %d %d %d %d %d %d", x1, y1, z1, x2, y2, z2, speed)));
    }

    public void setSpeed(int cmPerSecond) {
        requireRange(cmPerSecond, MIN_SPEED, MAX_SPEED, "speed");
        expectOk(connection.sendCommandAndWait("speed " + cmPerSecond));
    }

    public void setWifi(String ssid, String password) {
        expectOk(connection.sendCommandAndWait("wifi " + ssid + " " + password));
    }

    public double getSpeed() {
        return Double.parseDouble(connection.sendCommandAndWait("speed?"));
    }

    public int getBattery() {
        return Integer.parseInt(connection.sendCommandAndWait("battery?").trim());
    }

    public String getFlightTime() {
        return connection.sendCommandAndWait("time?");
    }

    public int getHeight() {
        return parseLeadingInt(connection.sendCommandAndWait("height?"));
    }

    public String getTemperature() {
        return connection.sendCommandAndWait("temp?");
    }

    public Attitude getAttitude() {
        return Attitude.parse(connection.sendCommandAndWait("attitude?"));
    }

    public double getBarometer() {
        return Double.parseDouble(connection.sendCommandAndWait("baro?").trim());
    }

    public Acceleration getAcceleration() {
        return Acceleration.parse(connection.sendCommandAndWait("acceleration?"));
    }

    public int getTof() {
        return parseLeadingInt(connection.sendCommandAndWait("tof?"));
    }

    public String getWifiSnr() {
        return connection.sendCommandAndWait("wifi?");
    }

    private static void expectOk(String response) {
        if (!"ok".equalsIgnoreCase(response)) {
            throw new TelloException("Command failed: " + response);
        }
    }

    private static int requireDistance(int value) {
        return requireRange(value, MIN_DISTANCE, MAX_DISTANCE, "distance");
    }

    private static int requireDegree(int value) {
        return requireRange(value, MIN_DEGREE, MAX_DEGREE, "degree");
    }

    private static int requireRange(int value, int min, int max, String name) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " must be between " + min + " and " + max + " but was " + value);
        }
        return value;
    }

    private static void requireNotAllWithinDeadZone(int x, int y, int z) {
        if (Math.abs(x) <= CURVE_DEAD_ZONE && Math.abs(y) <= CURVE_DEAD_ZONE && Math.abs(z) <= CURVE_DEAD_ZONE) {
            throw new IllegalArgumentException("x, y and z can't all be between -20 and 20 at the same time");
        }
    }

    private static int parseLeadingInt(String response) {
        StringBuilder digits = new StringBuilder();
        for (char c : response.trim().toCharArray()) {
            if (Character.isDigit(c) || (c == '-' && digits.isEmpty())) {
                digits.append(c);
            } else {
                break;
            }
        }
        if (digits.isEmpty()) {
            throw new TelloException("Unrecognized numeric response: " + response);
        }
        return Integer.parseInt(digits.toString());
    }

    /** Starts a daemon thread that resends {@code command} if the aircraft is flying and idle. */
    public synchronized void startIdleKeepAlive(Duration idleThreshold) {
        if (keepAliveThread != null) {
            return;
        }
        keepAliveThread = new Thread(() -> runIdleKeepAlive(idleThreshold), "tello-idle-keepalive");
        keepAliveThread.setDaemon(true);
        keepAliveThread.start();
    }

    private void runIdleKeepAlive(Duration idleThreshold) {
        while (true) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!flying || connection.timeSinceLastCommand().compareTo(idleThreshold) < 0) {
                continue;
            }
            try {
                connect();
                LOG.fine("No command for " + idleThreshold.toSeconds() + "s while flying; resent 'command' as keep-alive.");
            } catch (TelloException e) {
                LOG.log(Level.WARNING, "idle keep-alive resend failed", e);
            }
        }
    }

    @Override
    public void close() {
        if (keepAliveThread != null) {
            keepAliveThread.interrupt();
        }
        connection.close();
    }
}
