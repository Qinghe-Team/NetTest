package com.qinghe.nettest.vpn.tunnel;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Map;

public class TcpControlBlock {
    public enum Status {
        SYN_SENT,
        SYN_RECEIVED,
        ESTABLISHED,
        CLOSE_WAIT,
        LAST_ACK
    }

    private static final int MAX_CACHE_SIZE = 64;
    private static final LruCache<String, TcpControlBlock> CACHE = new LruCache<>(MAX_CACHE_SIZE,
        eldest -> eldest.getValue().closeChannel());

    public final String key;
    public long localSequenceNumber;
    public long remoteSequenceNumber;
    public long localAcknowledgementNumber;
    public long remoteAcknowledgementNumber;
    public Status status;
    public Packet referencePacket;
    public SocketChannel channel;
    public boolean waitingForNetworkData;
    public SelectionKey selectionKey;

    public TcpControlBlock(String key, long localSequenceNumber, long remoteSequenceNumber,
                           long localAcknowledgementNumber, long remoteAcknowledgementNumber,
                           SocketChannel channel, Packet referencePacket) {
        this.key = key;
        this.localSequenceNumber = localSequenceNumber;
        this.remoteSequenceNumber = remoteSequenceNumber;
        this.localAcknowledgementNumber = localAcknowledgementNumber;
        this.remoteAcknowledgementNumber = remoteAcknowledgementNumber;
        this.channel = channel;
        this.referencePacket = referencePacket;
    }

    public static TcpControlBlock get(String key) {
        synchronized (CACHE) {
            return CACHE.get(key);
        }
    }

    public static void put(String key, TcpControlBlock value) {
        synchronized (CACHE) {
            CACHE.put(key, value);
        }
    }

    public static void close(TcpControlBlock value) {
        if (value == null) {
            return;
        }
        value.closeChannel();
        synchronized (CACHE) {
            CACHE.remove(value.key);
        }
    }

    public static void closeAll() {
        synchronized (CACHE) {
            Iterator<Map.Entry<String, TcpControlBlock>> iterator = CACHE.entrySet().iterator();
            while (iterator.hasNext()) {
                iterator.next().getValue().closeChannel();
                iterator.remove();
            }
        }
    }

    private void closeChannel() {
        try {
            if (selectionKey != null) {
                selectionKey.cancel();
            }
            channel.close();
        } catch (IOException ignored) {
        }
    }
}
