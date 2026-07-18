package com.tello.core;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Passive UDP listener for Tello's state broadcast (port 8890, SDK section 2): once SDK mode is
 * active, the aircraft pushes a telemetry string on this port continuously without being asked.
 */
public final class TelloStateReceiver implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(TelloStateReceiver.class.getName());
    public static final int DEFAULT_STATE_PORT = 8890;
    private static final int RECEIVE_BUFFER_SIZE = 1024;

    private final DatagramSocket socket;
    private final Thread receiverThread;
    private volatile boolean running = true;
    private volatile TelloState latestState;
    private volatile Consumer<TelloState> listener;

    public TelloStateReceiver() throws SocketException {
        this(DEFAULT_STATE_PORT);
    }

    public TelloStateReceiver(int port) throws SocketException {
        this.socket = new DatagramSocket(port);
        this.receiverThread = new Thread(this::receiveLoop, "tello-state-receiver");
        this.receiverThread.setDaemon(true);
        this.receiverThread.start();
    }

    /** Registers a callback invoked with every new state snapshot as it arrives. */
    public void onStateUpdate(Consumer<TelloState> listener) {
        this.listener = listener;
    }

    /** Returns the most recently received state, or {@code null} if none has arrived yet. */
    public TelloState latestState() {
        return latestState;
    }

    private void receiveLoop() {
        byte[] buffer = new byte[RECEIVE_BUFFER_SIZE];
        while (running) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
                String raw = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                TelloState state = TelloState.parse(raw);
                latestState = state;
                Consumer<TelloState> currentListener = listener;
                if (currentListener != null) {
                    currentListener.accept(state);
                }
            } catch (IOException e) {
                if (running) {
                    LOG.log(Level.FINE, "state receive loop stopped", e);
                }
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "failed to parse state packet", e);
            }
        }
    }

    @Override
    public void close() {
        running = false;
        socket.close();
    }
}
