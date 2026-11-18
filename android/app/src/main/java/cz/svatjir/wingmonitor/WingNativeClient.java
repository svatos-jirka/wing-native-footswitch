package cz.svatjir.wingmonitor;

import android.util.Base64;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Behringer WING Native binary protocol client over WebSocket (TCP/2222).
 *
 * Uses the same WebSocket framing, 32-bit node hashes, 0xdf channel escaping,
 * float32 (0xd5) level writes and int16 (0xd3) on/mute writes as WING-Q.
 * Writes are limited to the player's Aux mix: channel sends into the Aux
 * (ch.N.send.B.lvl/on) and the Aux master (bus.B.fdr/mute). Preamp gain and
 * the main mix are never touched.
 */
final class WingNativeClient {
    static final int PORT = 2222;
    static final String EMULATOR_HOST = "10.0.2.2";
    static final int MAX_STAGE_CHANNELS = 40;
    static final int MIX_BUS_COUNT = 16;
    /** Fixed UDP port for meters, next to WING-Q's 1240; the emulator needs `redir add udp:1241:1241`. */
    static final int METER_PORT = 1241;
    private static final int METER_GROUP_CHANNEL = 0xAB;
    private static final int METER_VALUES_PER_CHANNEL = 11;
    private static final long METER_KEEPALIVE_MS = 4900L;
    private static final long PING_INTERVAL_MS = 5500L;

    interface Listener {
        void onConnectionState(boolean connected, String host, String consoleName, String detail);
        void onRemoteSendLevel(int wingChannel, int auxBus, float levelDb);
        void onRemoteSendOn(int wingChannel, int auxBus, boolean on);
        void onChannelName(int wingChannel, String name, boolean activeName);
        void onChannelColor(int wingChannel, int color, boolean activeColor);
        void onBusFader(int auxBus, float levelDb);
        void onBusMute(int auxBus, boolean muted);
        void onBusName(int auxBus, String name, boolean activeName);
        /** Input level per WING channel (index 1..40), in dBFS, from the console's meter stream. */
        void onMeters(float[] inputLevelDb);
    }

    private static final int SEND_LVL = 0;
    private static final int SEND_ON = 1;
    private static final int CH_NAME = 2;
    private static final int CH_ACT_NAME = 3;
    private static final int CH_COL = 4;
    private static final int CH_ACT_COL = 5;
    private static final int BUS_FDR = 6;
    private static final int BUS_MUTE = 7;
    private static final int BUS_NAME = 8;
    private static final int BUS_ACT_NAME = 9;

    private static final class TrackedNode {
        final int kind;
        final int first;
        final int second;

        TrackedNode(int kind, int first, int second) {
            this.kind = kind;
            this.first = first;
            this.second = second;
        }
    }

    private final Listener listener;
    private final boolean emulatorDefault;
    private final Map<Integer, TrackedNode> hashLookup = new HashMap<>();
    private final int consoleNameHash = wingHash("$syscfg.consolename");
    private final int watchdogHash = wingHash("ch.2.main.1.lvl");
    private final LinkedBlockingQueue<byte[]> outboundQueue = new LinkedBlockingQueue<>(512);
    private final SecureRandom random = new SecureRandom();

    private volatile boolean running;
    private volatile boolean connected;
    private volatile String configuredHost;
    private volatile String activeHost = "";
    private volatile String consoleName = "WING";
    private Thread workerThread;
    private volatile Socket currentSocket;
    private volatile DatagramSocket meterSocket;
    private volatile int meterSerial;

    WingNativeClient(Listener listener, boolean emulatorDefault, String initialHost) {
        this.listener = listener;
        this.emulatorDefault = emulatorDefault;
        this.configuredHost = initialHost == null ? "" : initialHost.trim();
        for (int ch = 1; ch <= MAX_STAGE_CHANNELS; ch++) {
            track("ch." + ch + ".name", CH_NAME, ch, 0);
            track("ch." + ch + ".$name", CH_ACT_NAME, ch, 0);
            track("ch." + ch + ".col", CH_COL, ch, 0);
            track("ch." + ch + ".$col", CH_ACT_COL, ch, 0);
            for (int bus = 1; bus <= MIX_BUS_COUNT; bus++) {
                track("ch." + ch + ".send." + bus + ".lvl", SEND_LVL, ch, bus);
                track("ch." + ch + ".send." + bus + ".on", SEND_ON, ch, bus);
            }
        }
        for (int bus = 1; bus <= MIX_BUS_COUNT; bus++) {
            track("bus." + bus + ".fdr", BUS_FDR, bus, 0);
            track("bus." + bus + ".mute", BUS_MUTE, bus, 0);
            track("bus." + bus + ".name", BUS_NAME, bus, 0);
            track("bus." + bus + ".$name", BUS_ACT_NAME, bus, 0);
        }
    }

    private void track(String path, int kind, int first, int second) {
        hashLookup.put(wingHash(path), new TrackedNode(kind, first, second));
    }

    synchronized void start() {
        if (running) return;
        running = true;
        workerThread = new Thread(this::connectionLoop, "WingNativeClient");
        workerThread.setDaemon(true);
        workerThread.start();
    }

    synchronized void stop() {
        running = false;
        connected = false;
        closeQuietly(currentSocket);
        DatagramSocket meters = meterSocket;
        meterSocket = null;
        if (meters != null) meters.close();
        if (workerThread != null) {
            workerThread.interrupt();
            workerThread = null;
        }
    }

    /** Lets already queued writes (e.g. restoring auto-muted sends) reach the console, then stops. */
    void stopAfterFlush(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (connected && !outboundQueue.isEmpty() && System.currentTimeMillis() < deadline) {
            sleepQuietly(20L);
        }
        stop();
    }

    void setConfiguredHostAndReconnect(String host) {
        configuredHost = host == null ? "" : host.trim();
        outboundQueue.clear();
        closeQuietly(currentSocket);
    }

    String getConfiguredHost() {
        return configuredHost;
    }

    boolean isConnected() {
        return connected;
    }

    boolean sendChannelSendLevel(int wingChannel, int auxBus, float levelDb) {
        if (!validChannel(wingChannel) || !validBus(auxBus)) return false;
        return enqueueFrame(encodeFloatFrame("ch." + wingChannel + ".send." + auxBus + ".lvl",
                clampLevel(levelDb)));
    }

    boolean sendChannelSendOn(int wingChannel, int auxBus, boolean on) {
        if (!validChannel(wingChannel) || !validBus(auxBus)) return false;
        return enqueueFrame(encodeIntFrame("ch." + wingChannel + ".send." + auxBus + ".on", on ? 1 : 0));
    }

    boolean sendBusFader(int auxBus, float levelDb) {
        if (!validBus(auxBus)) return false;
        return enqueueFrame(encodeFloatFrame("bus." + auxBus + ".fdr", clampLevel(levelDb)));
    }

    boolean sendBusMute(int auxBus, boolean muted) {
        if (!validBus(auxBus)) return false;
        return enqueueFrame(encodeIntFrame("bus." + auxBus + ".mute", muted ? 1 : 0));
    }

    private static boolean validChannel(int wingChannel) {
        return wingChannel >= 1 && wingChannel <= MAX_STAGE_CHANNELS;
    }

    private static boolean validBus(int auxBus) {
        return auxBus >= 1 && auxBus <= MIX_BUS_COUNT;
    }

    private static float clampLevel(float levelDb) {
        return Math.max(-144.0f, Math.min(10.0f, levelDb));
    }

    private boolean enqueueFrame(byte[] frame) {
        if (!running || !connected) return false;
        if (!outboundQueue.offer(frame)) {
            outboundQueue.poll();
            outboundQueue.offer(frame);
        }
        return true;
    }

    /** Exact 32-bit WING node hash matching WING-Q utils.js hashgen(). */
    static int wingHash(String path) {
        long n = 0;
        long r = 3407988414L;
        int idx = 0;
        int len = path.length();
        while (idx < len) {
            long word = 0;
            int packed = 0;
            while (packed < 4 && idx < len) {
                char ch = path.charAt(idx++);
                if (ch >= 'a' && ch <= 'z') {
                    ch = (char) (ch - 32);
                }
                if (ch != ' ') {
                    word = ((word << 8) | (ch & 0xFFL)) & 0xFFFFFFFFL;
                    packed++;
                    n++;
                }
            }
            word = (word * 3432918353L) & 0xFFFFFFFFL;
            word = (((word << 15) | (word >>> 17)) & 0xFFFFFFFFL);
            word = (word * 461845907L) & 0xFFFFFFFFL;
            r = (r ^ word) & 0xFFFFFFFFL;
            r = (((r << 13) | (r >>> 19)) & 0xFFFFFFFFL);
            r = (r * 5L + 3864292196L) & 0xFFFFFFFFL;
        }
        return (int) ((r ^ n) & 0xFFFFFFFFL);
    }

    /** Exact 0xdf channel stream escaping matching WING-Q strinter.js armorwith0xdf(). */
    static byte[] armorWith0xdf(byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(payload.length + 4);
        boolean prevDf = false;
        for (byte b : payload) {
            int u = b & 0xFF;
            if (u == 0xDF) {
                prevDf = true;
            } else {
                if (prevDf && u >= 0xD0 && u <= 0xDE) {
                    out.write(0xDE);
                }
                prevDf = false;
            }
            out.write(u);
        }
        if (prevDf) {
            out.write(0xDE);
        }
        return out.toByteArray();
    }

    static byte[] wrapChannel1Frame(byte[] payload) {
        return wrapChannelFrame(payload, 1);
    }

    /** Channel 1 carries node data, channel 3 the meter subscription (as in WING-Q). */
    static byte[] wrapChannelFrame(byte[] payload, int channel) {
        byte[] armored = armorWith0xdf(payload);
        byte[] frame = new byte[2 + armored.length];
        frame[0] = (byte) 0xDF;
        frame[1] = (byte) (0xD0 + channel);
        System.arraycopy(armored, 0, frame, 2, armored.length);
        return frame;
    }

    /**
     * WING-Q metermonger.js: "d3 <udp port> d4 <serial> dc ab <ch-1> ... de" on channel 3.
     * Without the dc…de part it is the keep-alive that must be repeated every ~5 s.
     */
    static byte[] encodeMeterRequestFrame(int udpPort, int serial, boolean subscribe) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(96);
        out.write(0xD3);
        out.write((udpPort >>> 8) & 0xFF);
        out.write(udpPort & 0xFF);
        out.write(0xD4);
        out.write((serial >>> 24) & 0xFF);
        out.write((serial >>> 16) & 0xFF);
        out.write((serial >>> 8) & 0xFF);
        out.write(serial & 0xFF);
        if (subscribe) {
            out.write(0xDC);
            for (int ch = 0; ch < MAX_STAGE_CHANNELS; ch++) {
                out.write(METER_GROUP_CHANNEL);
                out.write(ch);
            }
            out.write(0xDE);
        }
        return wrapChannelFrame(out.toByteArray(), 3);
    }

    /**
     * Meter packet: 4-byte serial, then per channel 11 signed int16 values in 1/256 dB
     * (il, ir, ol, or, gate…, dyn…). Returns the input level max(il, ir) per channel.
     */
    static float[] decodeMeterPacket(byte[] data, int length, int expectedSerial) {
        if (length < 4) return null;
        int serial = ((data[0] & 0xFF) << 24) | ((data[1] & 0xFF) << 16)
                | ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
        if (serial != expectedSerial) return null;
        float[] levels = new float[MAX_STAGE_CHANNELS + 1];
        java.util.Arrays.fill(levels, -144.0f);
        for (int ch = 1; ch <= MAX_STAGE_CHANNELS; ch++) {
            int base = 4 + (ch - 1) * METER_VALUES_PER_CHANNEL * 2;
            if (base + 4 > length) break;
            short left = (short) (((data[base] & 0xFF) << 8) | (data[base + 1] & 0xFF));
            short right = (short) (((data[base + 2] & 0xFF) << 8) | (data[base + 3] & 0xFF));
            levels[ch] = Math.max(left, right) / 256.0f;
        }
        return levels;
    }

    /** Matches WING-Q writeNode(path, float) for fader/level nodes. */
    static byte[] encodeFloatFrame(String path, float value) {
        int hash = wingHash(path);
        int bits = Float.floatToRawIntBits(value);
        byte[] cmd = new byte[]{
                (byte) 0xD7,
                (byte) (hash >>> 24), (byte) (hash >>> 16), (byte) (hash >>> 8), (byte) hash,
                (byte) 0xD5,
                (byte) (bits >>> 24), (byte) (bits >>> 16), (byte) (bits >>> 8), (byte) bits
        };
        return wrapChannel1Frame(cmd);
    }

    /** Matches WING-Q writeNode(path, int) for on/mute nodes, using the 0xd3 int16 form. */
    static byte[] encodeIntFrame(String path, int value) {
        int hash = wingHash(path);
        byte[] cmd = new byte[]{
                (byte) 0xD7,
                (byte) (hash >>> 24), (byte) (hash >>> 16), (byte) (hash >>> 8), (byte) hash,
                (byte) 0xD3,
                (byte) (value >>> 8), (byte) value
        };
        return wrapChannel1Frame(cmd);
    }

    static byte[] encodeDumpRequestFrame() {
        return wrapChannel1Frame(new byte[]{(byte) 0xDA, (byte) 0xDC});
    }

    private byte[] encodeWatchdogFrame() {
        byte[] cmd = new byte[]{
                (byte) 0xD7,
                (byte) (watchdogHash >>> 24),
                (byte) (watchdogHash >>> 16),
                (byte) (watchdogHash >>> 8),
                (byte) watchdogHash,
                (byte) 0xDC
        };
        return wrapChannel1Frame(cmd);
    }

    private void connectionLoop() {
        while (running) {
            String host = resolveTargetHost();
            if (host == null || host.isEmpty()) {
                listener.onConnectionState(false, "", consoleName, "Nenalezen WING pult v síti");
                sleepQuietly(3000L);
                continue;
            }

            Socket socket = null;
            Thread writer = null;
            try {
                socket = new Socket();
                currentSocket = socket;
                socket.setTcpNoDelay(true);
                socket.connect(new InetSocketAddress(host, PORT), 2500);
                socket.setSoTimeout(15000);

                InputStream in = new BufferedInputStream(socket.getInputStream(), 8192);
                OutputStream out = new BufferedOutputStream(socket.getOutputStream(), 4096);
                performWebSocketHandshake(host, in, out);

                outboundQueue.clear();
                activeHost = host;
                connected = true;
                listener.onConnectionState(true, host, consoleName, "Připojeno");

                // Request the full mixer state just like WING-Q on connect: [0xdf, 0xd1, 0xda, 0xdc].
                outboundQueue.offer(encodeDumpRequestFrame());
                DatagramSocket meters = ensureMeterSocket();
                if (meters != null) {
                    meterSerial = 5000 + random.nextInt(4000);
                    outboundQueue.offer(encodeMeterRequestFrame(meters.getLocalPort(), meterSerial, true));
                }

                final Socket activeSocket = socket;
                writer = new Thread(() -> writerLoop(activeSocket, out), "WingNativeWriter");
                writer.setDaemon(true);
                writer.start();

                readWebSocketLoop(in, out);
            } catch (Exception error) {
                if (running) {
                    listener.onConnectionState(false, host, consoleName,
                            "Odpojeno (" + error.getClass().getSimpleName() + ")");
                }
            } finally {
                boolean wasConnected = connected;
                connected = false;
                closeQuietly(socket);
                if (writer != null) writer.interrupt();
                if (wasConnected && running) {
                    listener.onConnectionState(false, host, consoleName, "Odpojeno");
                }
            }

            if (running) {
                sleepQuietly(2500L);
            }
        }
    }

    private String resolveTargetHost() {
        String explicit = configuredHost;
        if (explicit != null && !explicit.isEmpty()) {
            return explicit;
        }
        String discovered = probeUdpDiscovery();
        if (discovered != null && !discovered.isEmpty()) {
            return discovered;
        }
        if (emulatorDefault) {
            return EMULATOR_HOST;
        }
        return "";
    }

    private String probeUdpDiscovery() {
        try (DatagramSocket udp = new DatagramSocket()) {
            udp.setBroadcast(true);
            udp.setSoTimeout(700);
            byte[] probe = "WING?".getBytes(StandardCharsets.US_ASCII);
            InetAddress target = InetAddress.getByName(emulatorDefault ? EMULATOR_HOST : "255.255.255.255");
            udp.send(new DatagramPacket(probe, probe.length, target, PORT));
            byte[] buf = new byte[512];
            DatagramPacket reply = new DatagramPacket(buf, buf.length);
            udp.receive(reply);
            String text = new String(reply.getData(), 0, reply.getLength(), StandardCharsets.UTF_8);
            if (text.startsWith("WING,")) {
                String[] parts = text.split(",");
                if (parts.length >= 3) {
                    consoleName = parts[2];
                }
                return reply.getAddress().getHostAddress();
            }
        } catch (IOException ignored) {
            // Fall back cleanly if broadcast discovery does not get a response.
        }
        return null;
    }

    private void writerLoop(Socket socket, OutputStream out) {
        long lastSentAt = System.currentTimeMillis();
        long lastMeterKeepaliveAt = lastSentAt;
        try {
            while (running && connected && !socket.isClosed()) {
                byte[] payload = outboundQueue.poll(500, TimeUnit.MILLISECONDS);
                long now = System.currentTimeMillis();
                if (payload != null) {
                    writeWebSocketFrame(out, 0x2, payload);
                    lastSentAt = now;
                } else if (now - lastSentAt >= PING_INTERVAL_MS) {
                    writeWebSocketFrame(out, 0x2, encodeWatchdogFrame());
                    lastSentAt = now;
                }
                DatagramSocket meters = meterSocket;
                if (meters != null && now - lastMeterKeepaliveAt >= METER_KEEPALIVE_MS) {
                    writeWebSocketFrame(out, 0x2,
                            encodeMeterRequestFrame(meters.getLocalPort(), meterSerial, false));
                    lastMeterKeepaliveAt = now;
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            closeQuietly(socket);
        }
    }

    private synchronized DatagramSocket ensureMeterSocket() {
        if (meterSocket != null) return meterSocket;
        DatagramSocket socket;
        try {
            socket = new DatagramSocket(METER_PORT);
        } catch (IOException fixedPortBusy) {
            try {
                socket = new DatagramSocket();
            } catch (IOException error) {
                return null;
            }
        }
        meterSocket = socket;
        final DatagramSocket boundSocket = socket;
        Thread reader = new Thread(() -> meterLoop(boundSocket), "WingMeterReader");
        reader.setDaemon(true);
        reader.start();
        return socket;
    }

    private void meterLoop(DatagramSocket socket) {
        byte[] buffer = new byte[8192];
        while (running && !socket.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                float[] levels = decodeMeterPacket(packet.getData(), packet.getLength(), meterSerial);
                if (levels != null) listener.onMeters(levels);
            } catch (IOException error) {
                if (socket.isClosed()) return;
            }
        }
    }

    private void performWebSocketHandshake(String host, InputStream in, OutputStream out)
            throws IOException {
        byte[] keyBytes = new byte[16];
        random.nextBytes(keyBytes);
        String wsKey = Base64.encodeToString(keyBytes, Base64.NO_WRAP);
        String request = "GET / HTTP/1.1\r\n"
                + "Host: " + host + ":" + PORT + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + wsKey + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n";
        out.write(request.getBytes(StandardCharsets.US_ASCII));
        out.flush();

        ByteArrayOutputStream headerBuf = new ByteArrayOutputStream(256);
        int state = 0;
        while (headerBuf.size() < 8192) {
            int b = in.read();
            if (b < 0) throw new EOFException("EOF during WebSocket handshake");
            headerBuf.write(b);
            if ((state == 0 && b == '\r') || (state == 2 && b == '\r')) state++;
            else if ((state == 1 && b == '\n') || (state == 3 && b == '\n')) state++;
            else state = (b == '\r') ? 1 : 0;
            if (state == 4) break;
        }
        String headers = new String(headerBuf.toByteArray(), StandardCharsets.ISO_8859_1);
        if (!headers.startsWith("HTTP/1.1 101")) {
            throw new IOException("WebSocket upgrade rejected: " + headers);
        }
    }

    private synchronized void writeWebSocketFrame(OutputStream out, int opcode, byte[] payload)
            throws IOException {
        byte[] mask = new byte[4];
        random.nextBytes(mask);
        out.write(0x80 | (opcode & 0x0F));
        int len = payload.length;
        if (len < 126) {
            out.write(0x80 | len);
        } else if (len < 65536) {
            out.write(0x80 | 126);
            out.write((len >>> 8) & 0xFF);
            out.write(len & 0xFF);
        } else {
            out.write(0x80 | 127);
            for (int shift = 56; shift >= 0; shift -= 8) {
                out.write((int) (((long) len >>> shift) & 0xFF));
            }
        }
        out.write(mask);
        byte[] masked = new byte[len];
        for (int i = 0; i < len; i++) {
            masked[i] = (byte) (payload[i] ^ mask[i & 3]);
        }
        out.write(masked);
        out.flush();
    }

    private void readWebSocketLoop(InputStream in, OutputStream out) throws IOException {
        ChannelStreamParser streamParser = new ChannelStreamParser();
        ByteArrayOutputStream fragmentBuffer = new ByteArrayOutputStream();
        int messageOpcode = -1;

        while (running && connected) {
            int b0 = in.read();
            if (b0 < 0) throw new EOFException("WebSocket closed");
            int b1 = readByteExact(in);
            boolean fin = (b0 & 0x80) != 0;
            int opcode = b0 & 0x0F;
            boolean masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7F;
            if (len == 126) {
                len = ((readByteExact(in) << 8) | readByteExact(in)) & 0xFFFFL;
            } else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) {
                    len = (len << 8) | readByteExact(in);
                }
            }
            if (len < 0 || len > 4 * 1024 * 1024) {
                throw new IOException("Oversized WebSocket frame: " + len);
            }
            byte[] mask = null;
            if (masked) {
                mask = new byte[4];
                readFully(in, mask);
            }
            byte[] payload = new byte[(int) len];
            readFully(in, payload);
            if (masked) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] ^= mask[i & 3];
                }
            }

            if (opcode == 0x8) {
                break;
            } else if (opcode == 0x9) {
                writeWebSocketFrame(out, 0xA, payload);
                continue;
            } else if (opcode == 0xA) {
                continue;
            }

            if (messageOpcode < 0) {
                messageOpcode = opcode;
            }
            fragmentBuffer.write(payload);
            if (fin) {
                if (messageOpcode == 0x2 || messageOpcode == 0x1) {
                    streamParser.feed(fragmentBuffer.toByteArray());
                }
                fragmentBuffer.reset();
                messageOpcode = -1;
            }
        }
    }

    private final class ChannelStreamParser {
        private int currentChannel = -1;
        private boolean escape;
        private final ByteArrayOutputStream ch1 = new ByteArrayOutputStream(4096);
        private int currentHash;

        void feed(byte[] frame) {
            for (byte rawByte : frame) {
                int b = rawByte & 0xFF;
                if (b == 0xDF && !escape) {
                    escape = true;
                    continue;
                }
                if (escape && b != 0xDF) {
                    escape = false;
                    if (b == 0xDE) {
                        b = 0xDF;
                    } else if (b >= 0xD0 && b <= 0xDD) {
                        currentChannel = b - 0xD0;
                        continue;
                    } else if (currentChannel == 1) {
                        ch1.write(0xDF);
                    }
                }
                if (currentChannel == 1) {
                    ch1.write(b);
                }
            }
            parseChannel1Commands();
        }

        private void parseChannel1Commands() {
            byte[] data = ch1.toByteArray();
            int idx = 0;
            int n = data.length;
            while (idx < n) {
                int cmd = data[idx] & 0xFF;
                if (cmd == 0xD7) {
                    if (idx + 5 > n) break;
                    currentHash = ((data[idx + 1] & 0xFF) << 24)
                            | ((data[idx + 2] & 0xFF) << 16)
                            | ((data[idx + 3] & 0xFF) << 8)
                            | (data[idx + 4] & 0xFF);
                    idx += 5;
                } else if (cmd <= 0x3F) {
                    onIntValue(currentHash, cmd);
                    idx += 1;
                } else if (cmd >= 0x80 && cmd <= 0xBF) {
                    int slen = cmd - 0x80 + 1;
                    if (idx + 1 + slen > n) break;
                    onStringValue(currentHash,
                            new String(data, idx + 1, slen, StandardCharsets.UTF_8));
                    idx += 1 + slen;
                } else if (cmd >= 0xC0 && cmd <= 0xCF) {
                    int slen = cmd - 0xC0 + 1;
                    if (idx + 1 + slen > n) break;
                    onStringValue(currentHash,
                            new String(data, idx + 1, slen, StandardCharsets.UTF_8));
                    idx += 1 + slen;
                } else if (cmd == 0xD0) {
                    onStringValue(currentHash, "");
                    idx += 1;
                } else if (cmd == 0xD1) {
                    if (idx + 2 > n) break;
                    int slen = (data[idx + 1] & 0xFF) + 1;
                    if (idx + 2 + slen > n) break;
                    onStringValue(currentHash,
                            new String(data, idx + 2, slen, StandardCharsets.UTF_8));
                    idx += 2 + slen;
                } else if (cmd == 0xD2) {
                    if (idx + 3 > n) break;
                    idx += 3;
                } else if (cmd == 0xD3) {
                    if (idx + 3 > n) break;
                    short v = (short) (((data[idx + 1] & 0xFF) << 8) | (data[idx + 2] & 0xFF));
                    onIntValue(currentHash, v);
                    idx += 3;
                } else if (cmd == 0xD4) {
                    if (idx + 5 > n) break;
                    int v = ((data[idx + 1] & 0xFF) << 24)
                            | ((data[idx + 2] & 0xFF) << 16)
                            | ((data[idx + 3] & 0xFF) << 8)
                            | (data[idx + 4] & 0xFF);
                    onIntValue(currentHash, v);
                    idx += 5;
                } else if (cmd == 0xD5 || cmd == 0xD6) {
                    if (idx + 5 > n) break;
                    int bits = ((data[idx + 1] & 0xFF) << 24)
                            | ((data[idx + 2] & 0xFF) << 16)
                            | ((data[idx + 3] & 0xFF) << 8)
                            | (data[idx + 4] & 0xFF);
                    onFloatValue(currentHash, Float.intBitsToFloat(bits));
                    idx += 5;
                } else if (cmd == 0xD9) {
                    if (idx + 2 > n) break;
                    idx += 2;
                } else {
                    idx += 1;
                }
            }
            ch1.reset();
            if (idx < n) {
                ch1.write(data, idx, n - idx);
            }
        }
    }

    private void onStringValue(int hash, String value) {
        if (hash == consoleNameHash && value != null && !value.isEmpty()) {
            consoleName = value;
            listener.onConnectionState(connected, activeHost, consoleName, "Připojeno");
            return;
        }
        TrackedNode node = hashLookup.get(hash);
        if (node == null) return;
        String text = value == null ? "" : value.trim();
        if (node.kind == CH_NAME || node.kind == CH_ACT_NAME) {
            listener.onChannelName(node.first, text, node.kind == CH_ACT_NAME);
        } else if (node.kind == BUS_NAME || node.kind == BUS_ACT_NAME) {
            listener.onBusName(node.first, text, node.kind == BUS_ACT_NAME);
        }
    }

    private void onFloatValue(int hash, float value) {
        TrackedNode node = hashLookup.get(hash);
        if (node == null) return;
        if (node.kind == SEND_LVL) {
            listener.onRemoteSendLevel(node.first, node.second, value);
        } else if (node.kind == BUS_FDR) {
            listener.onBusFader(node.first, value);
        }
    }

    private void onIntValue(int hash, int value) {
        TrackedNode node = hashLookup.get(hash);
        if (node == null) return;
        switch (node.kind) {
            case SEND_ON:
                listener.onRemoteSendOn(node.first, node.second, value != 0);
                break;
            case CH_COL:
            case CH_ACT_COL:
                listener.onChannelColor(node.first, value, node.kind == CH_ACT_COL);
                break;
            case BUS_MUTE:
                listener.onBusMute(node.first, value != 0);
                break;
            case SEND_LVL:
                listener.onRemoteSendLevel(node.first, node.second, value);
                break;
            case BUS_FDR:
                listener.onBusFader(node.first, value);
                break;
            default:
                break;
        }
    }

    private static int readByteExact(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) throw new EOFException();
        return b;
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) throw new EOFException();
            off += n;
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
