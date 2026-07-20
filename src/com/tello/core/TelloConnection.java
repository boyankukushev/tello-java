package com.tello.core;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Low-level UDP command channel to the Tello aircraft (command port 8889), per SDK section 2:
 * the PC sends text commands and Tello replies on the same socket.
 */
public final class TelloConnection implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(TelloConnection.class.getName());

    public static final String DEFAULT_TELLO_HOST = "192.168.10.1";
    public static final int DEFAULT_TELLO_PORT = 8889;
    public static final int DEFAULT_LOCAL_PORT = 8889;

    private static final int RECEIVE_BUFFER_SIZE = 1518;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(7);
    private static final int DEFAULT_MAX_RETRIES = 3;

    private final DatagramSocket socket;
    private final InetSocketAddress telloAddress;
    private final BlockingQueue<String> responses = new ArrayBlockingQueue<>(16);
    private final ReentrantLock commandLock = new ReentrantLock();
    private final Thread receiverThread;
    private volatile boolean running = true;

    public TelloConnection() throws SocketException, UnknownHostException {
        this(DEFAULT_LOCAL_PORT, DEFAULT_TELLO_HOST, DEFAULT_TELLO_PORT);
    }

    public TelloConnection(int localPort, String telloHost, int telloPort) throws SocketException, UnknownHostException {
        this.socket = new DatagramSocket(localPort);
        this.telloAddress = new InetSocketAddress(InetAddress.getByName(telloHost), telloPort);
        this.receiverThread = new Thread(this::receiveLoop, "tello-command-receiver");
        this.receiverThread.setDaemon(true);
        this.receiverThread.start();
    }

    private void receiveLoop() {
        byte[] buffer = new byte[RECEIVE_BUFFER_SIZE];
        while (running) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
                String message = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8).trim();
                LOG.fine(() -> "<< " + message);
                responses.offer(message);
            } catch (IOException e) {
                if (running) {
                    LOG.log(Level.FINE, "command receive loop stopped", e);
                }
            }
        }
    }

    /** Sends a command and waits for the aircraft's response, retrying on timeout. */
    public String sendCommandAndWait(String command) {
        return sendCommandAndWait(command, DEFAULT_TIMEOUT, DEFAULT_MAX_RETRIES);
    }

    public String sendCommandAndWait(String command, Duration timeout, int maxRetries) {
        commandLock.lock();
        try {
            TelloException lastFailure = null;
            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                responses.clear();
                send(command);
                try {
                    String response = responses.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
                    if (response != null) {
                        return response;
                    }
                    lastFailure = new TelloException(
                            "Timed out waiting for response to '" + command + "' (attempt " + attempt + "/" + maxRetries + ")");
                    int attemptNumber = attempt;
                    LOG.warning(() -> "No response yet for '" + command + "' (attempt " + attemptNumber + "/" + maxRetries
                            + "), retrying...");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new TelloException("Interrupted while waiting for response to '" + command + "'", e);
                }
            }
            throw lastFailure;
        } finally {
            commandLock.unlock();
        }
    }

    /**
     * Fire-and-forget send, used for high-frequency commands such as {@code rc} that are streamed
     * continuously; waiting for a per-command response would make joystick-style control unusable.
     */
    public void sendCommand(String command) {
        send(command);
    }

    private void send(String command) {
        byte[] data = command.getBytes(StandardCharsets.UTF_8);
        DatagramPacket packet = new DatagramPacket(data, data.length, telloAddress);
        try {
            LOG.fine(() -> ">> " + command);
            socket.send(packet);
        } catch (IOException e) {
            throw new TelloException("Failed to send command '" + command + "'", e);
        }
    }

    @Override
    public void close() {
        running = false;
        socket.close();
    }
}
