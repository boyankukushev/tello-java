package com.tello.video;

import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Receives Tello's raw H.264 video stream (UDP port 11111 by default, SDK section 2) and relays
 * each datagram, unmodified, to one or more local destinations.
 *
 * <p>Only one process can bind the port that Tello sends video to, so a media player such as VLC
 * cannot listen on 11111 directly while this server is also receiving from the aircraft there.
 * Instead, this relay re-sends every received datagram byte-for-byte to a second local port
 * (e.g. 11112) that VLC is pointed at instead, with no re-encoding involved.
 */
public final class TelloVideoRelay implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(TelloVideoRelay.class.getName());
    public static final int DEFAULT_VIDEO_PORT = 11111;
    private static final int RECEIVE_BUFFER_SIZE = 2048;

    private final DatagramSocket receiveSocket;
    private final DatagramSocket relaySocket;
    private final List<InetSocketAddress> relayTargets;
    private final FileOutputStream recordingFile;
    private final Thread relayThread;
    private volatile boolean running = true;
    private volatile long packetsReceived;

    public TelloVideoRelay(int listenPort, List<InetSocketAddress> relayTargets, FileOutputStream recordingFile)
            throws SocketException {
        this.receiveSocket = new DatagramSocket(listenPort);
        this.relaySocket = new DatagramSocket();
        this.relayTargets = List.copyOf(relayTargets);
        this.recordingFile = recordingFile;
        this.relayThread = new Thread(this::relayLoop, "tello-video-relay");
        this.relayThread.setDaemon(true);
        this.relayThread.start();
        LOG.info(() -> "Video relay listening on UDP " + listenPort + ", forwarding to " + this.relayTargets);
    }

    private void relayLoop() {
        byte[] buffer = new byte[RECEIVE_BUFFER_SIZE];
        while (running) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                receiveSocket.receive(packet);
            } catch (IOException e) {
                if (running) {
                    LOG.log(Level.FINE, "video relay loop stopped", e);
                }
                continue;
            }
            packetsReceived++;

            for (InetSocketAddress target : relayTargets) {
                try {
                    relaySocket.send(new DatagramPacket(packet.getData(), packet.getLength(), target));
                } catch (IOException e) {
                    LOG.log(Level.FINE, "failed to relay a frame to " + target, e);
                }
            }

            if (recordingFile != null) {
                try {
                    recordingFile.write(packet.getData(), 0, packet.getLength());
                } catch (IOException e) {
                    LOG.log(Level.WARNING, "failed to write frame to recording file", e);
                }
            }
        }
    }

    /** Number of video datagrams received from the aircraft so far. */
    public long packetsReceived() {
        return packetsReceived;
    }

    @Override
    public void close() {
        running = false;
        receiveSocket.close();
        relaySocket.close();
        if (recordingFile != null) {
            try {
                recordingFile.close();
            } catch (IOException e) {
                LOG.log(Level.WARNING, "failed to close recording file", e);
            }
        }
    }

    public static InetSocketAddress target(String host, int port) {
        try {
            return new InetSocketAddress(InetAddress.getByName(host), port);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid relay target " + host + ":" + port, e);
        }
    }
}
