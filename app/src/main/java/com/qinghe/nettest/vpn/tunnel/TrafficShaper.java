package com.qinghe.nettest.vpn.tunnel;

import android.os.SystemClock;

import com.qinghe.nettest.model.NetworkConfig;

import java.util.Random;
import java.util.function.Supplier;

public class TrafficShaper {
    public enum Direction {
        UPSTREAM,
        DOWNSTREAM
    }

    public static final class Decision {
        private final boolean drop;
        private final int delayMs;

        public Decision(boolean drop, int delayMs) {
            this.drop = drop;
            this.delayMs = delayMs;
        }

        public boolean shouldDrop() {
            return drop;
        }

        public int getDelayMs() {
            return delayMs;
        }
    }

    private final Supplier<NetworkConfig> configSupplier;
    private final Random random = new Random();

    public TrafficShaper(Supplier<NetworkConfig> configSupplier) {
        this.configSupplier = configSupplier;
    }

    public Decision evaluate(Direction direction, Packet packet, int payloadSize) {
        NetworkConfig config = configSupplier.get();
        if (config == null || packet == null || !matchesProtocol(config, packet)) {
            return new Decision(false, 0);
        }

        int delay = direction == Direction.UPSTREAM ? config.getUpDelay() : config.getDownDelay();
        int jitter = direction == Direction.UPSTREAM ? config.getUpJitter() : config.getDownJitter();
        int bandwidth = direction == Direction.UPSTREAM ? config.getUpBandwidth() : config.getDownBandwidth();
        int packetLoss = direction == Direction.UPSTREAM ? config.getUpPacketLoss() : config.getDownPacketLoss();
        int passTime = direction == Direction.UPSTREAM ? config.getUpContinuousLossPassTime() : config.getDownContinuousLossPassTime();
        int dropTime = direction == Direction.UPSTREAM ? config.getUpContinuousLossDropTime() : config.getDownContinuousLossDropTime();

        if (isContinuousDrop(passTime, dropTime)) {
            return new Decision(true, 0);
        }

        if (packetLoss > 0 && random.nextInt(100) < Math.min(packetLoss, 100)) {
            return new Decision(true, 0);
        }

        int totalDelay = Math.max(delay, 0);
        if (jitter > 0) {
            totalDelay += random.nextInt(jitter + 1);
        }
        if (bandwidth > 0 && payloadSize > 0) {
            long bytesPerSecond = Math.max(1L, bandwidth * 1000L / 8L);
            totalDelay += (int) Math.ceil(payloadSize * 1000d / bytesPerSecond);
        }
        return new Decision(false, totalDelay);
    }

    public void applyDelay(Decision decision) throws InterruptedException {
        if (decision != null && decision.getDelayMs() > 0) {
            Thread.sleep(decision.getDelayMs());
        }
    }

    private boolean matchesProtocol(NetworkConfig config, Packet packet) {
        int protocol = config.getProtocol();
        if (protocol == 3) {
            return true;
        }
        return (protocol == 1 && packet.isTCP()) || (protocol == 2 && packet.isUDP());
    }

    private boolean isContinuousDrop(int passTimeMs, int dropTimeMs) {
        if (passTimeMs <= 0 || dropTimeMs <= 0) {
            return false;
        }
        long cycle = (long) passTimeMs + dropTimeMs;
        long point = SystemClock.elapsedRealtime() % cycle;
        return point >= passTimeMs;
    }
}
