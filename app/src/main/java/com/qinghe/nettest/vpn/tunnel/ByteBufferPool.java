package com.qinghe.nettest.vpn.tunnel;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class ByteBufferPool {
    private static final int BUFFER_SIZE = 32767;
    private static final ConcurrentLinkedQueue<ByteBuffer> POOL = new ConcurrentLinkedQueue<>();

    private ByteBufferPool() {
    }

    public static ByteBuffer acquire() {
        ByteBuffer buffer = POOL.poll();
        return buffer != null ? buffer : ByteBuffer.allocateDirect(BUFFER_SIZE);
    }

    public static void release(ByteBuffer buffer) {
        if (buffer == null) {
            return;
        }
        buffer.clear();
        POOL.offer(buffer);
    }

    public static void clear() {
        POOL.clear();
    }
}
