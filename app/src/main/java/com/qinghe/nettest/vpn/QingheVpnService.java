package com.qinghe.nettest.vpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import com.qinghe.nettest.MainActivity;
import com.qinghe.nettest.R;
import com.qinghe.nettest.db.AppDatabase;
import com.qinghe.nettest.model.NetworkConfig;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class QingheVpnService extends VpnService {
    private static final String CHANNEL_ID = "qinghe_vpn_channel";
    private static final int NOTIFICATION_ID = 1;
    public static final String ACTION_START = "com.qinghe.nettest.VPN_START";
    public static final String ACTION_STOP = "com.qinghe.nettest.VPN_STOP";
    public static final String EXTRA_CONFIG_ID = "config_id";

    // TCP flag constants
    private static final int FLAG_FIN = 0x01;
    private static final int FLAG_SYN = 0x02;
    private static final int FLAG_RST = 0x04;
    private static final int FLAG_PSH = 0x08;
    private static final int FLAG_ACK = 0x10;

    private ParcelFileDescriptor vpnInterface;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private Thread vpnThread;
    private volatile NetworkConfig currentConfig;
    private static QingheVpnService instance;

    private final Random random = new Random();
    // Single lock for all writes to the TUN output stream
    private final Object tunWriteLock = new Object();

    // Active session tables
    private final Map<String, TcpSession> tcpSessions = new ConcurrentHashMap<>();
    private final Map<String, UdpSession> udpSessions = new ConcurrentHashMap<>();

    // Continuous-loss state – upstream
    private long upContLossStart = 0;
    private boolean upContLossDropping = false;
    // Continuous-loss state – downstream
    private long downContLossStart = 0;
    private boolean downContLossDropping = false;

    public static QingheVpnService getInstance() { return instance; }
    public static boolean isServiceRunning() { return instance != null && instance.isRunning.get(); }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopVpn();
            stopSelf();
            return START_NOT_STICKY;
        }

        int configId = -1;
        if (intent != null) configId = intent.getIntExtra(EXTRA_CONFIG_ID, -1);
        if (configId == -1) {
            SharedPreferences prefs = getSharedPreferences("qinghe_prefs", MODE_PRIVATE);
            configId = prefs.getInt("active_config_id", -1);
        }

        final int finalConfigId = configId;
        if (finalConfigId != -1) {
            new Thread(() -> {
                currentConfig = AppDatabase.getInstance(QingheVpnService.this)
                        .networkConfigDao().getById(finalConfigId);
                startVpn();
            }).start();
        } else {
            currentConfig = new NetworkConfig();
            startVpn();
        }
        return START_STICKY;
    }

    public void updateConfig(int configId) {
        new Thread(() -> {
            currentConfig = AppDatabase.getInstance(this).networkConfigDao().getById(configId);
            getSharedPreferences("qinghe_prefs", MODE_PRIVATE).edit()
                    .putInt("active_config_id", configId).apply();
            resetContLossState();
        }).start();
    }

    private synchronized void resetContLossState() {
        upContLossStart = 0;
        upContLossDropping = false;
        downContLossStart = 0;
        downContLossDropping = false;
    }

    private void startVpn() {
        if (isRunning.get()) return;

        SharedPreferences prefs = getSharedPreferences("qinghe_prefs", MODE_PRIVATE);
        String selectedPackage = prefs.getString("selected_package", null);

        Builder builder = new Builder()
                .setSession("Qinghe")
                .addAddress("10.0.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8");

        if (selectedPackage != null) {
            try { builder.addAllowedApplication(selectedPackage); }
            catch (Exception e) { e.printStackTrace(); }
        }

        vpnInterface = builder.establish();
        if (vpnInterface == null) { stopSelf(); return; }

        isRunning.set(true);
        startForeground(NOTIFICATION_ID, createNotification());

        vpnThread = new Thread(this::runVpnLoop);
        vpnThread.start();
    }

    // ──────────────────────────── Main packet loop ────────────────────────────

    private void runVpnLoop() {
        try (FileInputStream in = new FileInputStream(vpnInterface.getFileDescriptor())) {
            FileOutputStream out = new FileOutputStream(vpnInterface.getFileDescriptor());
            ByteBuffer buffer = ByteBuffer.allocate(32767);

            while (isRunning.get()) {
                try {
                    buffer.clear();
                    int length = in.read(buffer.array());
                    if (length <= 0) { Thread.sleep(10); continue; }

                    if (length < 20) continue;
                    byte[] packet = Arrays.copyOf(buffer.array(), length);

                    // Only handle IPv4
                    if (((packet[0] >> 4) & 0xF) != 4) continue;

                    int ipHdrLen = (packet[0] & 0x0F) * 4;
                    int proto = packet[9] & 0xFF;

                    if (proto == 6 && length >= ipHdrLen + 20) {
                        handleTcpPacket(packet, ipHdrLen, out);
                    } else if (proto == 17 && length >= ipHdrLen + 8) {
                        handleUdpPacket(packet, ipHdrLen, out);
                    }
                    // Other protocols are silently dropped (ICMP etc.)

                } catch (InterruptedException e) {
                    break;
                } catch (IOException e) {
                    if (isRunning.get()) e.printStackTrace();
                    break;
                }
            }
        } catch (IOException e) {
            if (isRunning.get()) e.printStackTrace();
        }
    }

    // ──────────────────────────── UDP handling ────────────────────────────────

    private void handleUdpPacket(byte[] packet, int ipHdrLen, FileOutputStream tunOut) {
        int srcIP  = readInt(packet, 12);
        int dstIP  = readInt(packet, 16);
        int srcPort = readShort(packet, ipHdrLen);
        int dstPort = readShort(packet, ipHdrLen + 2);
        int udpLen  = readShort(packet, ipHdrLen + 4);
        int payLen  = udpLen - 8;
        if (payLen <= 0 || ipHdrLen + 8 + payLen > packet.length) return;

        byte[] payload = Arrays.copyOfRange(packet, ipHdrLen + 8, ipHdrLen + 8 + payLen);
        String key = srcIP + ":" + srcPort + ">" + dstIP + ":" + dstPort;

        UdpSession session = udpSessions.get(key);
        if (session == null || !session.active) {
            session = new UdpSession(srcIP, dstIP, srcPort, dstPort, tunOut);
            udpSessions.put(key, session);
            if (!session.start()) { udpSessions.remove(key); return; }
        }
        session.sendUpstream(payload);
    }

    // ──────────────────────────── TCP handling ────────────────────────────────

    private void handleTcpPacket(byte[] packet, int ipHdrLen, FileOutputStream tunOut) {
        int srcIP   = readInt(packet, 12);
        int dstIP   = readInt(packet, 16);
        int srcPort = readShort(packet, ipHdrLen);
        int dstPort = readShort(packet, ipHdrLen + 2);
        long seqNum = readUInt(packet, ipHdrLen + 4);
        int tcpHdrLen = ((packet[ipHdrLen + 12] & 0xF0) >> 4) * 4;
        int flags   = packet[ipHdrLen + 13] & 0xFF;
        String key  = srcIP + ":" + srcPort + ">" + dstIP + ":" + dstPort;

        if ((flags & FLAG_SYN) != 0 && (flags & FLAG_ACK) == 0) {
            TcpSession old = tcpSessions.get(key);
            if (old != null) old.close();
            TcpSession session = new TcpSession(srcIP, dstIP, srcPort, dstPort, seqNum, tunOut);
            tcpSessions.put(key, session);
            session.handleSyn();
        } else if ((flags & FLAG_RST) != 0) {
            TcpSession s = tcpSessions.remove(key);
            if (s != null) s.close();
        } else if ((flags & FLAG_FIN) != 0) {
            TcpSession s = tcpSessions.remove(key);
            if (s != null) s.handleFin(seqNum);
        } else if ((flags & FLAG_ACK) != 0) {
            TcpSession s = tcpSessions.get(key);
            if (s == null || !s.active) { tcpSessions.remove(key); return; }
            int dataStart = ipHdrLen + tcpHdrLen;
            int dataLen   = packet.length - dataStart;
            byte[] data   = dataLen > 0 ? Arrays.copyOfRange(packet, dataStart, packet.length) : null;
            s.handleAck(seqNum, data);
        }
    }

    // ────────────────────── Effect application helpers ────────────────────────

    /** Returns true if the packet should pass, false if it should be dropped. */
    boolean applyUpstreamEffects(boolean isTcp, int dataLen) throws InterruptedException {
        NetworkConfig cfg = currentConfig;
        if (cfg == null) return true;

        int proto = cfg.getProtocol();
        boolean applies = (proto == 3) || (proto == 1 && isTcp) || (proto == 2 && !isTcp);
        if (!applies) return true;

        // Random packet loss
        if (cfg.getUpPacketLoss() > 0 && random.nextInt(100) < cfg.getUpPacketLoss()) return false;

        // Continuous burst loss
        if (cfg.getUpContinuousLossPassTime() > 0 && cfg.getUpContinuousLossDropTime() > 0) {
            if (checkContLoss(true, cfg)) return false;
        }

        // Delay + jitter
        int delay = cfg.getUpDelay();
        if (cfg.getUpJitter() > 0) delay += random.nextInt(cfg.getUpJitter());
        if (delay > 0) Thread.sleep(delay);

        // Bandwidth throttle
        if (cfg.getUpBandwidth() > 0) {
            double bytesPerMs = (cfg.getUpBandwidth() * 1000.0 / 8.0) / 1000.0;
            if (bytesPerMs > 0) {
                int sleep = (int)(dataLen / bytesPerMs);
                if (sleep > 0) Thread.sleep(sleep);
            }
        }
        return true;
    }

    /** Returns true if the packet should pass, false if it should be dropped. */
    boolean applyDownstreamEffects(boolean isTcp, int dataLen) throws InterruptedException {
        NetworkConfig cfg = currentConfig;
        if (cfg == null) return true;

        int proto = cfg.getProtocol();
        boolean applies = (proto == 3) || (proto == 1 && isTcp) || (proto == 2 && !isTcp);
        if (!applies) return true;

        // Random packet loss
        if (cfg.getDownPacketLoss() > 0 && random.nextInt(100) < cfg.getDownPacketLoss()) return false;

        // Continuous burst loss
        if (cfg.getDownContinuousLossPassTime() > 0 && cfg.getDownContinuousLossDropTime() > 0) {
            if (checkContLoss(false, cfg)) return false;
        }

        // Delay + jitter
        int delay = cfg.getDownDelay();
        if (cfg.getDownJitter() > 0) delay += random.nextInt(cfg.getDownJitter());
        if (delay > 0) Thread.sleep(delay);

        // Bandwidth throttle
        if (cfg.getDownBandwidth() > 0) {
            double bytesPerMs = (cfg.getDownBandwidth() * 1000.0 / 8.0) / 1000.0;
            if (bytesPerMs > 0) {
                int sleep = (int)(dataLen / bytesPerMs);
                if (sleep > 0) Thread.sleep(sleep);
            }
        }
        return true;
    }

    private synchronized boolean checkContLoss(boolean upstream, NetworkConfig cfg) {
        long now = System.currentTimeMillis();
        int passTime = upstream ? cfg.getUpContinuousLossPassTime() : cfg.getDownContinuousLossPassTime();
        int dropTime = upstream ? cfg.getUpContinuousLossDropTime() : cfg.getDownContinuousLossDropTime();

        if (upstream) {
            if (upContLossStart == 0) upContLossStart = now;
            long elapsed = now - upContLossStart;
            if (!upContLossDropping && elapsed >= passTime) {
                upContLossDropping = true; upContLossStart = now; return true;
            } else if (upContLossDropping && elapsed >= dropTime) {
                upContLossDropping = false; upContLossStart = now; return false;
            }
            return upContLossDropping;
        } else {
            if (downContLossStart == 0) downContLossStart = now;
            long elapsed = now - downContLossStart;
            if (!downContLossDropping && elapsed >= passTime) {
                downContLossDropping = true; downContLossStart = now; return true;
            } else if (downContLossDropping && elapsed >= dropTime) {
                downContLossDropping = false; downContLossStart = now; return false;
            }
            return downContLossDropping;
        }
    }

    // ──────────────────────────── TcpSession ──────────────────────────────────

    private class TcpSession {
        final int clientIP, serverIP, clientPort, serverPort;
        final long clientISN;
        long ourISN;
        // Guarded by 'this'
        long clientSeq;
        long ourSeq;
        final FileOutputStream tunOut;
        Socket realSocket;
        OutputStream toServer;
        InputStream fromServer;
        volatile boolean active = true;
        volatile boolean handshakeDone = false;

        TcpSession(int clientIP, int serverIP, int clientPort, int serverPort,
                   long clientISN, FileOutputStream tunOut) {
            this.clientIP   = clientIP;
            this.serverIP   = serverIP;
            this.clientPort = clientPort;
            this.serverPort = serverPort;
            this.clientISN  = clientISN;
            this.clientSeq  = (clientISN + 1) & 0xFFFFFFFFL;
            this.tunOut     = tunOut;
        }

        void handleSyn() {
            ourISN = (long)(random.nextInt()) & 0xFFFFFFFFL;
            new Thread(() -> {
                try {
                    realSocket = new Socket();
                    protect(realSocket);
                    realSocket.connect(
                            new InetSocketAddress(intToInetAddress(serverIP), serverPort), 10000);
                    realSocket.setSoTimeout(30000);
                    toServer   = realSocket.getOutputStream();
                    fromServer = realSocket.getInputStream();

                    // Send SYN-ACK to client
                    sendToClient(null, FLAG_SYN | FLAG_ACK, ourISN, (clientISN + 1) & 0xFFFFFFFFL);
                    synchronized (TcpSession.this) { ourSeq = (ourISN + 1) & 0xFFFFFFFFL; }
                    handshakeDone = true;
                    startDownstream();
                } catch (IOException e) {
                    synchronized (TcpSession.this) {
                        sendToClient(null, FLAG_RST, 0, clientSeq);
                    }
                    active = false;
                }
            }).start();
        }

        void handleAck(long seqNum, byte[] data) {
            if (!handshakeDone || !active) return;
            if (data == null || data.length == 0) return; // pure ACK – nothing to forward

            new Thread(() -> {
                try {
                    if (!applyUpstreamEffects(true, data.length)) {
                        // Packet dropped – don't ACK; client's TCP will retransmit
                        return;
                    }
                    synchronized (TcpSession.this) {
                        if (!active) return;
                        toServer.write(data);
                        toServer.flush();
                        clientSeq = (clientSeq + data.length) & 0xFFFFFFFFL;
                    }
                    long ackVal;
                    long seqVal;
                    synchronized (TcpSession.this) { ackVal = clientSeq; seqVal = ourSeq; }
                    sendToClient(null, FLAG_ACK, seqVal, ackVal);
                } catch (IOException | InterruptedException e) {
                    close();
                }
            }).start();
        }

        void handleFin(long seqNum) {
            long ackVal, seqVal;
            synchronized (this) {
                clientSeq = (clientSeq + 1) & 0xFFFFFFFFL;
                ackVal = clientSeq;
                seqVal = ourSeq;
                ourSeq = (ourSeq + 1) & 0xFFFFFFFFL;
            }
            sendToClient(null, FLAG_FIN | FLAG_ACK, seqVal, ackVal);
            try { if (realSocket != null) realSocket.shutdownOutput(); } catch (IOException ignored) {}
            active = false;
        }

        private void startDownstream() {
            new Thread(() -> {
                byte[] buf = new byte[4096];
                while (active) {
                    try {
                        int len = fromServer.read(buf);
                        if (len < 0) {
                            if (active) {
                                long seqVal, ackVal;
                                synchronized (TcpSession.this) { seqVal = ourSeq; ackVal = clientSeq; ourSeq = (ourSeq + 1) & 0xFFFFFFFFL; }
                                sendToClient(null, FLAG_FIN | FLAG_ACK, seqVal, ackVal);
                            }
                            break;
                        }
                        if (len == 0) continue;
                        byte[] chunk = Arrays.copyOf(buf, len);
                        if (!applyDownstreamEffects(true, len)) continue; // dropped
                        long seqVal, ackVal;
                        synchronized (TcpSession.this) {
                            seqVal = ourSeq;
                            ackVal = clientSeq;
                            ourSeq = (ourSeq + len) & 0xFFFFFFFFL;
                        }
                        sendToClient(chunk, FLAG_PSH | FLAG_ACK, seqVal, ackVal);
                    } catch (SocketTimeoutException e) {
                        // Keep looping to check active flag
                    } catch (IOException | InterruptedException e) {
                        break;
                    }
                }
                active = false;
            }).start();
        }

        void sendToClient(byte[] data, int flags, long seq, long ack) {
            byte[] srcIPB = intToBytes(serverIP);
            byte[] dstIPB = intToBytes(clientIP);
            byte[] tcp = buildTCPSegment(serverPort, clientPort, seq, ack, flags, 65535, data, srcIPB, dstIPB);
            byte[] ip  = buildIPv4Header(serverIP, clientIP, 6, tcp.length);
            writeTun(concat(ip, tcp), tunOut);
        }

        void close() {
            active = false;
            try { if (realSocket != null) realSocket.close(); } catch (IOException ignored) {}
        }
    }

    // ──────────────────────────── UdpSession ──────────────────────────────────

    private class UdpSession {
        final int clientIP, serverIP, clientPort, serverPort;
        final FileOutputStream tunOut;
        DatagramSocket socket;
        volatile boolean active = false;

        UdpSession(int clientIP, int serverIP, int clientPort, int serverPort,
                   FileOutputStream tunOut) {
            this.clientIP   = clientIP;
            this.serverIP   = serverIP;
            this.clientPort = clientPort;
            this.serverPort = serverPort;
            this.tunOut     = tunOut;
        }

        boolean start() {
            try {
                socket = new DatagramSocket();
                protect(socket);
                active = true;
            } catch (IOException e) { return false; }

            new Thread(() -> {
                byte[] buf = new byte[32767];
                while (active) {
                    try {
                        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                        socket.setSoTimeout(30000);
                        socket.receive(pkt);
                        int len = pkt.getLength();
                        if (len <= 0) continue;
                        byte[] data = Arrays.copyOf(pkt.getData(), len);
                        if (!applyDownstreamEffects(false, len)) continue; // dropped
                        writeTun(buildUDPPacket(serverIP, clientIP, serverPort, clientPort, data), tunOut);
                    } catch (SocketTimeoutException e) {
                        break; // idle timeout – session expired
                    } catch (IOException | InterruptedException e) {
                        break;
                    }
                }
                active = false;
            }).start();
            return true;
        }

        void sendUpstream(byte[] payload) {
            new Thread(() -> {
                try {
                    if (!applyUpstreamEffects(false, payload.length)) return; // dropped
                    InetAddress addr = intToInetAddress(serverIP);
                    if (addr == null) return;
                    socket.send(new DatagramPacket(payload, payload.length, addr, serverPort));
                } catch (IOException | InterruptedException e) {
                    active = false;
                }
            }).start();
        }
    }

    // ──────────────────────────── Stop / lifecycle ────────────────────────────

    private void stopVpn() {
        isRunning.set(false);
        for (TcpSession s : tcpSessions.values()) s.close();
        tcpSessions.clear();
        for (UdpSession s : udpSessions.values()) s.active = false;
        udpSessions.clear();
        if (vpnThread != null) { vpnThread.interrupt(); vpnThread = null; }
        if (vpnInterface != null) {
            try { vpnInterface.close(); } catch (IOException e) { e.printStackTrace(); }
            vpnInterface = null;
        }
        stopForeground(true);
    }

    @Override
    public void onDestroy() {
        stopVpn();
        instance = null;
        super.onDestroy();
    }

    // ──────────────────────────── TUN write helper ────────────────────────────

    private void writeTun(byte[] data, FileOutputStream out) {
        try {
            synchronized (tunWriteLock) { out.write(data); }
        } catch (IOException ignored) {}
    }

    // ──────────────────────── Packet-building utilities ───────────────────────

    static int readInt(byte[] d, int off) {
        return ((d[off] & 0xFF) << 24) | ((d[off+1] & 0xFF) << 16)
             | ((d[off+2] & 0xFF) << 8) | (d[off+3] & 0xFF);
    }
    static int readShort(byte[] d, int off) {
        return ((d[off] & 0xFF) << 8) | (d[off+1] & 0xFF);
    }
    static long readUInt(byte[] d, int off) { return (long) readInt(d, off) & 0xFFFFFFFFL; }

    static byte[] intToBytes(int v) {
        return new byte[]{ (byte)(v>>24), (byte)(v>>16), (byte)(v>>8), (byte)v };
    }
    static InetAddress intToInetAddress(int ip) {
        try { return InetAddress.getByAddress(intToBytes(ip)); }
        catch (UnknownHostException e) { return null; }
    }
    static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    /** One's-complement checksum over a byte range. */
    private static int onesComplement(byte[] data, int off, int len) {
        long sum = 0;
        for (int i = off; i < off + len - 1; i += 2)
            sum += ((data[i] & 0xFF) << 8) | (data[i+1] & 0xFF);
        if (len % 2 != 0) sum += (data[off + len - 1] & 0xFF) << 8;
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return (int)(~sum & 0xFFFF);
    }

    /** Transport-layer checksum (TCP or UDP) using IPv4 pseudo-header. */
    private static int transportChecksum(byte[] srcIP, byte[] dstIP, int proto, byte[] seg) {
        int len = seg.length;
        long sum = 0;
        for (int i = 0; i < 4; i += 2) sum += ((srcIP[i] & 0xFF) << 8) | (srcIP[i+1] & 0xFF);
        for (int i = 0; i < 4; i += 2) sum += ((dstIP[i] & 0xFF) << 8) | (dstIP[i+1] & 0xFF);
        sum += proto;
        sum += len;
        for (int i = 0; i < len - 1; i += 2) sum += ((seg[i] & 0xFF) << 8) | (seg[i+1] & 0xFF);
        if (len % 2 != 0) sum += (seg[len-1] & 0xFF) << 8;
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return (int)(~sum & 0xFFFF);
    }

    static byte[] buildIPv4Header(int srcIP, int dstIP, int proto, int payloadLen) {
        byte[] h = new byte[20];
        h[0] = 0x45;
        int total = 20 + payloadLen;
        h[2] = (byte)(total >> 8); h[3] = (byte)total;
        h[6] = 0x40; // DF flag
        h[8] = 64;   // TTL
        h[9] = (byte)proto;
        System.arraycopy(intToBytes(srcIP), 0, h, 12, 4);
        System.arraycopy(intToBytes(dstIP), 0, h, 16, 4);
        int cs = onesComplement(h, 0, 20);
        h[10] = (byte)(cs >> 8); h[11] = (byte)cs;
        return h;
    }

    static byte[] buildTCPSegment(int srcPort, int dstPort, long seq, long ack,
                                   int flags, int window, byte[] data,
                                   byte[] srcIPB, byte[] dstIPB) {
        int dLen = (data != null) ? data.length : 0;
        byte[] s = new byte[20 + dLen];
        s[0] = (byte)(srcPort >> 8); s[1] = (byte)srcPort;
        s[2] = (byte)(dstPort >> 8); s[3] = (byte)dstPort;
        s[4] = (byte)(seq>>24); s[5] = (byte)(seq>>16); s[6] = (byte)(seq>>8); s[7] = (byte)seq;
        s[8] = (byte)(ack>>24); s[9] = (byte)(ack>>16); s[10]= (byte)(ack>>8); s[11]= (byte)ack;
        s[12] = 0x50; // data offset=5, reserved=0
        s[13] = (byte)flags;
        s[14] = (byte)(window >> 8); s[15] = (byte)window;
        if (data != null) System.arraycopy(data, 0, s, 20, dLen);
        int cs = transportChecksum(srcIPB, dstIPB, 6, s);
        s[16] = (byte)(cs >> 8); s[17] = (byte)cs;
        return s;
    }

    static byte[] buildUDPPacket(int srcIP, int dstIP, int srcPort, int dstPort, byte[] payload) {
        byte[] srcIPB = intToBytes(srcIP);
        byte[] dstIPB = intToBytes(dstIP);
        int udpLen = 8 + payload.length;
        byte[] s = new byte[udpLen];
        s[0] = (byte)(srcPort >> 8); s[1] = (byte)srcPort;
        s[2] = (byte)(dstPort >> 8); s[3] = (byte)dstPort;
        s[4] = (byte)(udpLen >> 8);  s[5] = (byte)udpLen;
        System.arraycopy(payload, 0, s, 8, payload.length);
        int cs = transportChecksum(srcIPB, dstIPB, 17, s);
        s[6] = (byte)(cs >> 8); s[7] = (byte)cs;
        byte[] ip = buildIPv4Header(srcIP, dstIP, 17, udpLen);
        return concat(ip, s);
    }

    // ──────────────────────────── Notification ────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Qinghe VPN Service", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification createNotification() {
        Intent ni = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, ni, PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setContentTitle(getString(R.string.vpn_notification_title))
                .setContentText(getString(R.string.vpn_notification_content))
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(pi)
                .build();
    }
}
