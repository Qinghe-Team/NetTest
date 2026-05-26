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
import com.qinghe.nettest.vpn.tunnel.ByteBufferPool;
import com.qinghe.nettest.vpn.tunnel.LruCache;
import com.qinghe.nettest.vpn.tunnel.Packet;
import com.qinghe.nettest.vpn.tunnel.TcpControlBlock;
import com.qinghe.nettest.vpn.tunnel.TrafficShaper;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.FileChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class QingheVpnService extends VpnService {
    private static final String CHANNEL_ID = "qinghe_vpn_channel";
    private static final int NOTIFICATION_ID = 1;
    public static final String ACTION_START = "com.qinghe.nettest.VPN_START";
    public static final String ACTION_STOP = "com.qinghe.nettest.VPN_STOP";
    public static final String EXTRA_CONFIG_ID = "config_id";

    private static final AtomicBoolean SERVICE_ACTIVE = new AtomicBoolean(false);
    private static QingheVpnService instance;

    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private ParcelFileDescriptor vpnInterface;
    private ExecutorService executorService;
    private Selector udpSelector;
    private Selector tcpSelector;
    private ConcurrentLinkedQueue<Packet> deviceToNetworkUdpQueue;
    private ConcurrentLinkedQueue<Packet> deviceToNetworkTcpQueue;
    private ConcurrentLinkedQueue<ByteBuffer> networkToDeviceQueue;
    private volatile NetworkConfig currentConfig;
    private TrafficShaper trafficShaper;

    public static QingheVpnService getInstance() {
        return instance;
    }

    public static boolean isServiceRunning() {
        return SERVICE_ACTIVE.get() || (instance != null && instance.isRunning.get());
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            SERVICE_ACTIVE.set(false);
            getSharedPreferences("qinghe_prefs", MODE_PRIVATE).edit()
                .putBoolean("service_active", false)
                .apply();
            stopVpn();
            stopSelf();
            return START_NOT_STICKY;
        }

        SERVICE_ACTIVE.set(true);
        getSharedPreferences("qinghe_prefs", MODE_PRIVATE).edit()
            .putBoolean("service_active", true)
            .apply();

        int configId = -1;
        if (intent != null) {
            configId = intent.getIntExtra(EXTRA_CONFIG_ID, -1);
        }
        if (configId == -1) {
            SharedPreferences prefs = getSharedPreferences("qinghe_prefs", MODE_PRIVATE);
            configId = prefs.getInt("active_config_id", -1);
        }

        final int finalConfigId = configId;
        new Thread(() -> {
            if (finalConfigId != -1) {
                currentConfig = AppDatabase.getInstance(QingheVpnService.this).networkConfigDao().getById(finalConfigId);
            }
            if (currentConfig == null) {
                currentConfig = new NetworkConfig();
            }
            startVpn();
        }).start();

        return START_STICKY;
    }

    public void updateConfig(int configId) {
        getSharedPreferences("qinghe_prefs", MODE_PRIVATE).edit()
            .putInt("active_config_id", configId)
            .apply();

        new Thread(() -> {
            NetworkConfig config = AppDatabase.getInstance(this).networkConfigDao().getById(configId);
            currentConfig = config != null ? config : new NetworkConfig();
        }).start();
    }

    private synchronized void startVpn() {
        if (isRunning.get()) {
            return;
        }

        SharedPreferences prefs = getSharedPreferences("qinghe_prefs", MODE_PRIVATE);
        String selectedPackage = prefs.getString("selected_package", null);

        Builder builder = new Builder()
            .setSession("Qinghe")
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8");

        if (selectedPackage != null) {
            try {
                builder.addAllowedApplication(selectedPackage);
            } catch (Exception ignored) {
            }
        }

        vpnInterface = builder.establish();
        if (vpnInterface == null) {
            SERVICE_ACTIVE.set(false);
            getSharedPreferences("qinghe_prefs", MODE_PRIVATE).edit()
                .putBoolean("service_active", false)
                .apply();
            stopSelf();
            return;
        }

        try {
            udpSelector = Selector.open();
            tcpSelector = Selector.open();
        } catch (IOException e) {
            SERVICE_ACTIVE.set(false);
            getSharedPreferences("qinghe_prefs", MODE_PRIVATE).edit()
                .putBoolean("service_active", false)
                .apply();
            cleanup();
            stopSelf();
            return;
        }

        deviceToNetworkUdpQueue = new ConcurrentLinkedQueue<>();
        deviceToNetworkTcpQueue = new ConcurrentLinkedQueue<>();
        networkToDeviceQueue = new ConcurrentLinkedQueue<>();
        trafficShaper = new TrafficShaper(() -> currentConfig);

        startForeground(NOTIFICATION_ID, createNotification());
        isRunning.set(true);

        executorService = Executors.newFixedThreadPool(5);
        executorService.submit(new UdpInputRunnable());
        executorService.submit(new UdpOutputRunnable());
        executorService.submit(new TcpInputRunnable());
        executorService.submit(new TcpOutputRunnable());
        executorService.submit(new VpnRunnable(vpnInterface.getFileDescriptor()));
    }

    private synchronized void stopVpn() {
        isRunning.set(false);
        SERVICE_ACTIVE.set(false);
        getSharedPreferences("qinghe_prefs", MODE_PRIVATE).edit()
            .putBoolean("service_active", false)
            .apply();

        if (executorService != null) {
            executorService.shutdownNow();
            executorService = null;
        }

        TcpControlBlock.closeAll();
        cleanup();
        stopForeground(true);
    }

    @Override
    public void onDestroy() {
        stopVpn();
        instance = null;
        super.onDestroy();
    }

    private void cleanup() {
        deviceToNetworkTcpQueue = null;
        deviceToNetworkUdpQueue = null;
        networkToDeviceQueue = null;
        ByteBufferPool.clear();
        closeResources(udpSelector, tcpSelector, vpnInterface);
        udpSelector = null;
        tcpSelector = null;
        vpnInterface = null;
    }

    private static void closeResources(Closeable... resources) {
        for (Closeable resource : resources) {
            if (resource == null) {
                continue;
            }
            try {
                resource.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Qinghe VPN Service", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CHANNEL_ID)
            : new Notification.Builder(this);

        return builder
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText(getString(R.string.vpn_notification_content))
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .build();
    }

    private final class VpnRunnable implements Runnable {
        private final FileDescriptor vpnFileDescriptor;

        private VpnRunnable(FileDescriptor vpnFileDescriptor) {
            this.vpnFileDescriptor = vpnFileDescriptor;
        }

        @Override
        public void run() {
            FileChannel vpnInput = new FileInputStream(vpnFileDescriptor).getChannel();
            FileChannel vpnOutput = new FileOutputStream(vpnFileDescriptor).getChannel();

            try {
                ByteBuffer bufferToNetwork = null;
                boolean dataSent = true;
                boolean dataReceived;

                while (isRunning.get() && !Thread.currentThread().isInterrupted()) {
                    if (dataSent) {
                        bufferToNetwork = ByteBufferPool.acquire();
                    } else if (bufferToNetwork != null) {
                        bufferToNetwork.clear();
                    }

                    int readBytes = bufferToNetwork != null ? vpnInput.read(bufferToNetwork) : 0;
                    if (readBytes > 0) {
                        dataSent = true;
                        bufferToNetwork.flip();
                        try {
                            Packet packet = new Packet(bufferToNetwork);
                            if (packet.isUDP()) {
                                deviceToNetworkUdpQueue.offer(packet);
                            } else if (packet.isTCP()) {
                                deviceToNetworkTcpQueue.offer(packet);
                            } else {
                                ByteBufferPool.release(bufferToNetwork);
                                dataSent = false;
                            }
                        } catch (UnknownHostException e) {
                            ByteBufferPool.release(bufferToNetwork);
                            dataSent = false;
                        }
                    } else {
                        dataSent = false;
                    }

                    ByteBuffer bufferFromNetwork = networkToDeviceQueue.poll();
                    if (bufferFromNetwork != null) {
                        bufferFromNetwork.flip();
                        while (bufferFromNetwork.hasRemaining()) {
                            vpnOutput.write(bufferFromNetwork);
                        }
                        dataReceived = true;
                        ByteBufferPool.release(bufferFromNetwork);
                    } else {
                        dataReceived = false;
                    }

                    if (!dataSent && !dataReceived) {
                        Thread.sleep(10);
                    }
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
            } finally {
                closeResources(vpnInput, vpnOutput);
            }
        }
    }

    private final class UdpOutputRunnable implements Runnable {
        private static final int MAX_CACHE_SIZE = 64;
        private final LruCache<String, DatagramChannel> channelCache = new LruCache<>(MAX_CACHE_SIZE,
            eldest -> closeChannel(eldest.getValue()));

        @Override
        public void run() {
            try {
                while (isRunning.get() && !Thread.currentThread().isInterrupted()) {
                    Packet packet = deviceToNetworkUdpQueue.poll();
                    if (packet == null) {
                        Thread.sleep(10);
                        continue;
                    }

                    int payloadSize = packet.payloadSize();
                    TrafficShaper.Decision decision = trafficShaper.evaluate(TrafficShaper.Direction.UPSTREAM, packet, payloadSize);
                    if (decision.shouldDrop()) {
                        ByteBufferPool.release(packet.backingBuffer);
                        continue;
                    }
                    trafficShaper.applyDelay(decision);

                    InetAddress destinationAddress = packet.ip4Header.destinationAddress;
                    int destinationPort = packet.udpHeader.destinationPort;
                    int sourcePort = packet.udpHeader.sourcePort;
                    String key = destinationAddress.getHostAddress() + ":" + destinationPort + ":" + sourcePort;

                    DatagramChannel outputChannel = channelCache.get(key);
                    if (outputChannel == null) {
                        outputChannel = DatagramChannel.open();
                        protect(outputChannel.socket());
                        outputChannel.connect(new InetSocketAddress(destinationAddress, destinationPort));
                        outputChannel.configureBlocking(false);
                        packet.swapSourceAndDestination();
                        udpSelector.wakeup();
                        outputChannel.register(udpSelector, SelectionKey.OP_READ, packet);
                        channelCache.put(key, outputChannel);
                    }

                    while (packet.backingBuffer.hasRemaining()) {
                        outputChannel.write(packet.backingBuffer);
                    }
                    ByteBufferPool.release(packet.backingBuffer);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
            } finally {
                closeAll();
            }
        }

        private void closeAll() {
            Iterator<Map.Entry<String, DatagramChannel>> iterator = channelCache.entrySet().iterator();
            while (iterator.hasNext()) {
                closeChannel(iterator.next().getValue());
                iterator.remove();
            }
        }

        private void closeChannel(DatagramChannel channel) {
            try {
                channel.close();
            } catch (IOException ignored) {
            }
        }
    }

    private final class UdpInputRunnable implements Runnable {
        private static final int HEADER_SIZE = Packet.IP4_HEADER_SIZE + Packet.UDP_HEADER_SIZE;

        @Override
        public void run() {
            try {
                while (isRunning.get() && !Thread.currentThread().isInterrupted()) {
                    int readyChannels = udpSelector.select();
                    if (readyChannels == 0) {
                        Thread.sleep(10);
                        continue;
                    }

                    Set<SelectionKey> keys = udpSelector.selectedKeys();
                    Iterator<SelectionKey> iterator = keys.iterator();
                    while (iterator.hasNext() && !Thread.currentThread().isInterrupted()) {
                        SelectionKey key = iterator.next();
                        iterator.remove();
                        if (!key.isValid() || !key.isReadable()) {
                            continue;
                        }

                        ByteBuffer receiveBuffer = ByteBufferPool.acquire();
                        receiveBuffer.position(HEADER_SIZE);

                        DatagramChannel inputChannel = (DatagramChannel) key.channel();
                        int readBytes = inputChannel.read(receiveBuffer);
                        if (readBytes <= 0) {
                            ByteBufferPool.release(receiveBuffer);
                            continue;
                        }

                        Packet referencePacket = (Packet) key.attachment();
                        TrafficShaper.Decision decision = trafficShaper.evaluate(TrafficShaper.Direction.DOWNSTREAM, referencePacket, readBytes);
                        if (decision.shouldDrop()) {
                            ByteBufferPool.release(receiveBuffer);
                            continue;
                        }
                        trafficShaper.applyDelay(decision);

                        referencePacket.updateUDPBuffer(receiveBuffer, readBytes);
                        receiveBuffer.position(HEADER_SIZE + readBytes);
                        networkToDeviceQueue.offer(receiveBuffer);
                    }
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
            }
        }
    }

    private final class TcpOutputRunnable implements Runnable {
        private final Random random = new Random();

        @Override
        public void run() {
            try {
                while (isRunning.get() && !Thread.currentThread().isInterrupted()) {
                    Packet packet = deviceToNetworkTcpQueue.poll();
                    if (packet == null) {
                        Thread.sleep(10);
                        continue;
                    }

                    ByteBuffer payloadBuffer = packet.backingBuffer;
                    packet.backingBuffer = null;
                    ByteBuffer responseBuffer = ByteBufferPool.acquire();

                    InetAddress destinationAddress = packet.ip4Header.destinationAddress;
                    Packet.TCPHeader tcpHeader = packet.tcpHeader;
                    String key = destinationAddress.getHostAddress() + ":" + tcpHeader.destinationPort + ":" + tcpHeader.sourcePort;
                    TcpControlBlock tcb = TcpControlBlock.get(key);

                    if (tcb == null) {
                        initializeConnection(key, destinationAddress, packet, payloadBuffer, responseBuffer);
                    } else if (tcpHeader.isSYN()) {
                        processDuplicateSyn(tcb, payloadBuffer, responseBuffer);
                    } else if (tcpHeader.isRST()) {
                        closeCleanly(tcb, responseBuffer);
                    } else if (tcpHeader.isFIN()) {
                        processFin(tcb, packet, payloadBuffer, responseBuffer);
                    } else if (tcpHeader.isACK()) {
                        processAck(tcb, packet, payloadBuffer, responseBuffer);
                    }

                    if (responseBuffer.position() == 0) {
                        ByteBufferPool.release(responseBuffer);
                    }
                    ByteBufferPool.release(payloadBuffer);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
            } finally {
                TcpControlBlock.closeAll();
            }
        }

        private void initializeConnection(String key, InetAddress destinationAddress, Packet packet,
                                          ByteBuffer payloadBuffer, ByteBuffer responseBuffer) throws IOException, InterruptedException {
            packet.swapSourceAndDestination();
            Packet.TCPHeader tcpHeader = packet.tcpHeader;
            if (!tcpHeader.isSYN()) {
                packet.updateTCPBuffer(responseBuffer, (byte) Packet.TCPHeader.RST, 0, tcpHeader.sequenceNumber + 1, 0);
                networkToDeviceQueue.offer(responseBuffer);
                return;
            }

            TrafficShaper.Decision decision = trafficShaper.evaluate(TrafficShaper.Direction.UPSTREAM, packet, payloadBuffer.limit() - payloadBuffer.position());
            if (decision.shouldDrop()) {
                return;
            }
            trafficShaper.applyDelay(decision);

            SocketChannel outputChannel = SocketChannel.open();
            outputChannel.configureBlocking(false);
            protect(outputChannel.socket());

            TcpControlBlock tcb = new TcpControlBlock(
                key,
                random.nextInt(Short.MAX_VALUE + 1),
                tcpHeader.sequenceNumber,
                tcpHeader.sequenceNumber + 1,
                tcpHeader.acknowledgementNumber,
                outputChannel,
                packet);
            TcpControlBlock.put(key, tcb);

            try {
                outputChannel.connect(new InetSocketAddress(destinationAddress, tcpHeader.destinationPort));
                if (outputChannel.finishConnect()) {
                    tcb.status = TcpControlBlock.Status.SYN_RECEIVED;
                    packet.updateTCPBuffer(responseBuffer, (byte) (Packet.TCPHeader.SYN | Packet.TCPHeader.ACK),
                        tcb.localSequenceNumber, tcb.localAcknowledgementNumber, 0);
                    tcb.localSequenceNumber++;
                    networkToDeviceQueue.offer(responseBuffer);
                } else {
                    tcb.status = TcpControlBlock.Status.SYN_SENT;
                    tcpSelector.wakeup();
                    tcb.selectionKey = outputChannel.register(tcpSelector, SelectionKey.OP_CONNECT, tcb);
                }
            } catch (IOException e) {
                packet.updateTCPBuffer(responseBuffer, (byte) Packet.TCPHeader.RST, 0, tcb.localAcknowledgementNumber, 0);
                networkToDeviceQueue.offer(responseBuffer);
                TcpControlBlock.close(tcb);
            }
        }

        private void processDuplicateSyn(TcpControlBlock tcb, ByteBuffer payloadBuffer, ByteBuffer responseBuffer) throws InterruptedException {
            synchronized (tcb) {
                if (tcb.status == TcpControlBlock.Status.SYN_SENT) {
                    return;
                }
                TrafficShaper.Decision decision = trafficShaper.evaluate(TrafficShaper.Direction.UPSTREAM, tcb.referencePacket, payloadBuffer.limit() - payloadBuffer.position());
                if (decision.shouldDrop()) {
                    return;
                }
                trafficShaper.applyDelay(decision);
            }
            sendRst(tcb, 1, responseBuffer);
        }

        private void processFin(TcpControlBlock tcb, Packet packet, ByteBuffer payloadBuffer, ByteBuffer responseBuffer) throws InterruptedException {
            TrafficShaper.Decision decision = trafficShaper.evaluate(TrafficShaper.Direction.UPSTREAM, packet, payloadBuffer.limit() - payloadBuffer.position());
            if (decision.shouldDrop()) {
                return;
            }
            trafficShaper.applyDelay(decision);

            synchronized (tcb) {
                Packet referencePacket = tcb.referencePacket;
                tcb.localAcknowledgementNumber = packet.tcpHeader.sequenceNumber + 1;
                tcb.remoteAcknowledgementNumber = packet.tcpHeader.acknowledgementNumber;

                if (tcb.waitingForNetworkData) {
                    tcb.status = TcpControlBlock.Status.CLOSE_WAIT;
                    referencePacket.updateTCPBuffer(responseBuffer, (byte) Packet.TCPHeader.ACK,
                        tcb.localSequenceNumber, tcb.localAcknowledgementNumber, 0);
                } else {
                    tcb.status = TcpControlBlock.Status.LAST_ACK;
                    referencePacket.updateTCPBuffer(responseBuffer, (byte) (Packet.TCPHeader.FIN | Packet.TCPHeader.ACK),
                        tcb.localSequenceNumber, tcb.localAcknowledgementNumber, 0);
                    tcb.localSequenceNumber++;
                }
            }
            networkToDeviceQueue.offer(responseBuffer);
        }

        private void processAck(TcpControlBlock tcb, Packet packet, ByteBuffer payloadBuffer, ByteBuffer responseBuffer) throws IOException, InterruptedException {
            int payloadSize = payloadBuffer.limit() - payloadBuffer.position();

            synchronized (tcb) {
                if (tcb.status == TcpControlBlock.Status.SYN_RECEIVED) {
                    tcb.status = TcpControlBlock.Status.ESTABLISHED;
                    tcpSelector.wakeup();
                    tcb.selectionKey = tcb.channel.register(tcpSelector, SelectionKey.OP_READ, tcb);
                    tcb.waitingForNetworkData = true;
                } else if (tcb.status == TcpControlBlock.Status.LAST_ACK) {
                    closeCleanly(tcb, responseBuffer);
                    return;
                }

                if (payloadSize == 0) {
                    return;
                }

                TrafficShaper.Decision decision = trafficShaper.evaluate(TrafficShaper.Direction.UPSTREAM, packet, payloadSize);
                if (decision.shouldDrop()) {
                    return;
                }
                trafficShaper.applyDelay(decision);

                if (!tcb.waitingForNetworkData && tcb.selectionKey != null) {
                    tcpSelector.wakeup();
                    tcb.selectionKey.interestOps(SelectionKey.OP_READ);
                    tcb.waitingForNetworkData = true;
                }

                while (payloadBuffer.hasRemaining()) {
                    tcb.channel.write(payloadBuffer);
                }

                tcb.localAcknowledgementNumber = packet.tcpHeader.sequenceNumber + payloadSize;
                tcb.remoteAcknowledgementNumber = packet.tcpHeader.acknowledgementNumber;
                tcb.referencePacket.updateTCPBuffer(responseBuffer, (byte) Packet.TCPHeader.ACK,
                    tcb.localSequenceNumber, tcb.localAcknowledgementNumber, 0);
            }
            networkToDeviceQueue.offer(responseBuffer);
        }

        private void sendRst(TcpControlBlock tcb, int previousPayloadSize, ByteBuffer buffer) {
            tcb.referencePacket.updateTCPBuffer(buffer, (byte) Packet.TCPHeader.RST,
                0, tcb.localAcknowledgementNumber + previousPayloadSize, 0);
            networkToDeviceQueue.offer(buffer);
            TcpControlBlock.close(tcb);
        }

        private void closeCleanly(TcpControlBlock tcb, ByteBuffer buffer) {
            ByteBufferPool.release(buffer);
            TcpControlBlock.close(tcb);
        }
    }

    private final class TcpInputRunnable implements Runnable {
        private static final int HEADER_SIZE = Packet.IP4_HEADER_SIZE + Packet.TCP_HEADER_SIZE;

        @Override
        public void run() {
            try {
                while (isRunning.get() && !Thread.currentThread().isInterrupted()) {
                    int readyChannels = tcpSelector.select();
                    if (readyChannels == 0) {
                        Thread.sleep(10);
                        continue;
                    }

                    Set<SelectionKey> keys = tcpSelector.selectedKeys();
                    Iterator<SelectionKey> iterator = keys.iterator();
                    while (iterator.hasNext() && !Thread.currentThread().isInterrupted()) {
                        SelectionKey key = iterator.next();
                        if (!key.isValid()) {
                            iterator.remove();
                            continue;
                        }
                        if (key.isConnectable()) {
                            processConnect(key, iterator);
                        } else if (key.isReadable()) {
                            processInput(key, iterator);
                        } else {
                            iterator.remove();
                        }
                    }
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
            }
        }

        private void processConnect(SelectionKey key, Iterator<SelectionKey> iterator) {
            TcpControlBlock tcb = (TcpControlBlock) key.attachment();
            Packet referencePacket = tcb.referencePacket;
            try {
                if (tcb.channel.finishConnect()) {
                    iterator.remove();
                    tcb.status = TcpControlBlock.Status.SYN_RECEIVED;

                    ByteBuffer responseBuffer = ByteBufferPool.acquire();
                    referencePacket.updateTCPBuffer(responseBuffer, (byte) (Packet.TCPHeader.SYN | Packet.TCPHeader.ACK),
                        tcb.localSequenceNumber, tcb.localAcknowledgementNumber, 0);
                    networkToDeviceQueue.offer(responseBuffer);
                    tcb.localSequenceNumber++;
                    key.interestOps(SelectionKey.OP_READ);
                }
            } catch (IOException e) {
                ByteBuffer responseBuffer = ByteBufferPool.acquire();
                referencePacket.updateTCPBuffer(responseBuffer, (byte) Packet.TCPHeader.RST,
                    0, tcb.localAcknowledgementNumber, 0);
                networkToDeviceQueue.offer(responseBuffer);
                TcpControlBlock.close(tcb);
            }
        }

        private void processInput(SelectionKey key, Iterator<SelectionKey> iterator) throws InterruptedException {
            iterator.remove();
            ByteBuffer receiveBuffer = ByteBufferPool.acquire();
            receiveBuffer.position(HEADER_SIZE);

            TcpControlBlock tcb = (TcpControlBlock) key.attachment();
            synchronized (tcb) {
                Packet referencePacket = tcb.referencePacket;
                SocketChannel inputChannel = (SocketChannel) key.channel();
                int readBytes;
                try {
                    readBytes = inputChannel.read(receiveBuffer);
                } catch (IOException e) {
                    referencePacket.updateTCPBuffer(receiveBuffer, (byte) Packet.TCPHeader.RST,
                        0, tcb.localAcknowledgementNumber, 0);
                    networkToDeviceQueue.offer(receiveBuffer);
                    TcpControlBlock.close(tcb);
                    return;
                }

                if (readBytes == -1) {
                    key.interestOps(0);
                    tcb.waitingForNetworkData = false;

                    if (tcb.status != TcpControlBlock.Status.CLOSE_WAIT) {
                        ByteBufferPool.release(receiveBuffer);
                        return;
                    }

                    tcb.status = TcpControlBlock.Status.LAST_ACK;
                    referencePacket.updateTCPBuffer(receiveBuffer, (byte) Packet.TCPHeader.FIN,
                        tcb.localSequenceNumber, tcb.localAcknowledgementNumber, 0);
                    tcb.localSequenceNumber++;
                } else {
                    TrafficShaper.Decision decision = trafficShaper.evaluate(TrafficShaper.Direction.DOWNSTREAM, referencePacket, readBytes);
                    if (decision.shouldDrop()) {
                        Thread.sleep(Math.max(250, decision.getDelayMs()));
                    } else {
                        trafficShaper.applyDelay(decision);
                    }

                    referencePacket.updateTCPBuffer(receiveBuffer, (byte) (Packet.TCPHeader.PSH | Packet.TCPHeader.ACK),
                        tcb.localSequenceNumber, tcb.localAcknowledgementNumber, readBytes);
                    tcb.localSequenceNumber += readBytes;
                    receiveBuffer.position(HEADER_SIZE + readBytes);
                }
            }
            networkToDeviceQueue.offer(receiveBuffer);
        }
    }
}
