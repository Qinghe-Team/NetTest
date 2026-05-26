package com.qinghe.nettest.vpn.tunnel;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;

public class Packet {
    public static final int IP4_HEADER_SIZE = 20;
    public static final int TCP_HEADER_SIZE = 20;
    public static final int UDP_HEADER_SIZE = 8;

    public final IP4Header ip4Header;
    public final TCPHeader tcpHeader;
    public final UDPHeader udpHeader;
    public ByteBuffer backingBuffer;

    private final boolean tcp;
    private final boolean udp;

    public Packet(ByteBuffer buffer) throws UnknownHostException {
        ip4Header = new IP4Header(buffer);
        if (ip4Header.protocol == IP4Header.TransportProtocol.TCP) {
            tcpHeader = new TCPHeader(buffer);
            udpHeader = null;
            tcp = true;
            udp = false;
        } else if (ip4Header.protocol == IP4Header.TransportProtocol.UDP) {
            tcpHeader = null;
            udpHeader = new UDPHeader(buffer);
            tcp = false;
            udp = true;
        } else {
            tcpHeader = null;
            udpHeader = null;
            tcp = false;
            udp = false;
        }
        backingBuffer = buffer;
    }

    public boolean isTCP() {
        return tcp;
    }

    public boolean isUDP() {
        return udp;
    }

    public int payloadSize() {
        return backingBuffer.limit() - backingBuffer.position();
    }

    public void swapSourceAndDestination() {
        InetAddress sourceAddress = ip4Header.sourceAddress;
        ip4Header.sourceAddress = ip4Header.destinationAddress;
        ip4Header.destinationAddress = sourceAddress;

        if (udp) {
            int sourcePort = udpHeader.sourcePort;
            udpHeader.sourcePort = udpHeader.destinationPort;
            udpHeader.destinationPort = sourcePort;
        } else if (tcp) {
            int sourcePort = tcpHeader.sourcePort;
            tcpHeader.sourcePort = tcpHeader.destinationPort;
            tcpHeader.destinationPort = sourcePort;
        }
    }

    public void updateTCPBuffer(ByteBuffer buffer, byte flags, long sequenceNumber, long acknowledgementNumber, int payloadSize) {
        buffer.position(0);
        fillHeader(buffer);
        backingBuffer = buffer;

        tcpHeader.flags = flags;
        backingBuffer.put(IP4_HEADER_SIZE + 13, flags);

        tcpHeader.sequenceNumber = sequenceNumber;
        backingBuffer.putInt(IP4_HEADER_SIZE + 4, (int) sequenceNumber);

        tcpHeader.acknowledgementNumber = acknowledgementNumber;
        backingBuffer.putInt(IP4_HEADER_SIZE + 8, (int) acknowledgementNumber);

        byte dataOffset = (byte) (TCP_HEADER_SIZE << 2);
        tcpHeader.dataOffsetAndReserved = dataOffset;
        backingBuffer.put(IP4_HEADER_SIZE + 12, dataOffset);

        updateTCPChecksum(payloadSize);

        int totalLength = IP4_HEADER_SIZE + TCP_HEADER_SIZE + payloadSize;
        backingBuffer.putShort(2, (short) totalLength);
        ip4Header.totalLength = totalLength;
        updateIP4Checksum();
    }

    public void updateUDPBuffer(ByteBuffer buffer, int payloadSize) {
        buffer.position(0);
        fillHeader(buffer);
        backingBuffer = buffer;

        int udpLength = UDP_HEADER_SIZE + payloadSize;
        backingBuffer.putShort(IP4_HEADER_SIZE + 4, (short) udpLength);
        udpHeader.length = udpLength;
        backingBuffer.putShort(IP4_HEADER_SIZE + 6, (short) 0);
        udpHeader.checksum = 0;

        int totalLength = IP4_HEADER_SIZE + udpLength;
        backingBuffer.putShort(2, (short) totalLength);
        ip4Header.totalLength = totalLength;
        updateIP4Checksum();
    }

    private void fillHeader(ByteBuffer buffer) {
        ip4Header.fillHeader(buffer);
        if (tcp) {
            tcpHeader.fillHeader(buffer);
        } else if (udp) {
            udpHeader.fillHeader(buffer);
        }
    }

    private void updateIP4Checksum() {
        ByteBuffer buffer = backingBuffer.duplicate();
        buffer.position(0);
        buffer.putShort(10, (short) 0);

        int length = ip4Header.headerLength;
        int sum = 0;
        while (length > 0) {
            sum += unsignedShort(buffer.getShort());
            length -= 2;
        }
        while ((sum >> 16) > 0) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        sum = ~sum;
        ip4Header.headerChecksum = sum;
        backingBuffer.putShort(10, (short) sum);
    }

    private void updateTCPChecksum(int payloadSize) {
        int tcpLength = TCP_HEADER_SIZE + payloadSize;
        int sum = 0;

        ByteBuffer addressBuffer = ByteBuffer.wrap(ip4Header.sourceAddress.getAddress());
        sum += unsignedShort(addressBuffer.getShort()) + unsignedShort(addressBuffer.getShort());

        addressBuffer = ByteBuffer.wrap(ip4Header.destinationAddress.getAddress());
        sum += unsignedShort(addressBuffer.getShort()) + unsignedShort(addressBuffer.getShort());

        sum += IP4Header.TransportProtocol.TCP.getNumber() + tcpLength;

        ByteBuffer buffer = backingBuffer.duplicate();
        buffer.putShort(IP4_HEADER_SIZE + 16, (short) 0);
        buffer.position(IP4_HEADER_SIZE);

        int remaining = tcpLength;
        while (remaining > 1) {
            sum += unsignedShort(buffer.getShort());
            remaining -= 2;
        }
        if (remaining > 0) {
            sum += unsignedByte(buffer.get()) << 8;
        }

        while ((sum >> 16) > 0) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        sum = ~sum;
        tcpHeader.checksum = sum;
        backingBuffer.putShort(IP4_HEADER_SIZE + 16, (short) sum);
    }

    private static short unsignedByte(byte value) {
        return (short) (value & 0xFF);
    }

    private static int unsignedShort(short value) {
        return value & 0xFFFF;
    }

    private static long unsignedInt(int value) {
        return value & 0xFFFFFFFFL;
    }

    public static final class IP4Header {
        public byte version;
        public byte ihl;
        public int headerLength;
        public short typeOfService;
        public int totalLength;
        public int identificationAndFlagsAndFragmentOffset;
        public short ttl;
        private short protocolNumber;
        public TransportProtocol protocol;
        public int headerChecksum;
        public InetAddress sourceAddress;
        public InetAddress destinationAddress;

        public enum TransportProtocol {
            TCP(6),
            UDP(17),
            OTHER(0xFF);

            private final int number;

            TransportProtocol(int number) {
                this.number = number;
            }

            public int getNumber() {
                return number;
            }

            public static TransportProtocol fromNumber(int number) {
                if (number == 6) {
                    return TCP;
                }
                if (number == 17) {
                    return UDP;
                }
                return OTHER;
            }
        }

        private IP4Header(ByteBuffer buffer) throws UnknownHostException {
            byte versionAndIhl = buffer.get();
            version = (byte) (versionAndIhl >> 4);
            ihl = (byte) (versionAndIhl & 0x0F);
            headerLength = ihl << 2;
            typeOfService = unsignedByte(buffer.get());
            totalLength = unsignedShort(buffer.getShort());
            identificationAndFlagsAndFragmentOffset = buffer.getInt();
            ttl = unsignedByte(buffer.get());
            protocolNumber = unsignedByte(buffer.get());
            protocol = TransportProtocol.fromNumber(protocolNumber);
            headerChecksum = unsignedShort(buffer.getShort());

            byte[] addressBytes = new byte[4];
            buffer.get(addressBytes, 0, 4);
            sourceAddress = InetAddress.getByAddress(addressBytes);
            buffer.get(addressBytes, 0, 4);
            destinationAddress = InetAddress.getByAddress(addressBytes);
        }

        private void fillHeader(ByteBuffer buffer) {
            buffer.put((byte) (version << 4 | ihl));
            buffer.put((byte) typeOfService);
            buffer.putShort((short) totalLength);
            buffer.putInt(identificationAndFlagsAndFragmentOffset);
            buffer.put((byte) ttl);
            buffer.put((byte) protocol.getNumber());
            buffer.putShort((short) headerChecksum);
            buffer.put(sourceAddress.getAddress());
            buffer.put(destinationAddress.getAddress());
        }
    }

    public static final class TCPHeader {
        public static final int FIN = 0x01;
        public static final int SYN = 0x02;
        public static final int RST = 0x04;
        public static final int PSH = 0x08;
        public static final int ACK = 0x10;

        public int sourcePort;
        public int destinationPort;
        public long sequenceNumber;
        public long acknowledgementNumber;
        public byte dataOffsetAndReserved;
        public int headerLength;
        public byte flags;
        public int window;
        public int checksum;
        public int urgentPointer;

        private TCPHeader(ByteBuffer buffer) {
            sourcePort = unsignedShort(buffer.getShort());
            destinationPort = unsignedShort(buffer.getShort());
            sequenceNumber = unsignedInt(buffer.getInt());
            acknowledgementNumber = unsignedInt(buffer.getInt());
            dataOffsetAndReserved = buffer.get();
            headerLength = (dataOffsetAndReserved & 0xF0) >> 2;
            flags = buffer.get();
            window = unsignedShort(buffer.getShort());
            checksum = unsignedShort(buffer.getShort());
            urgentPointer = unsignedShort(buffer.getShort());

            int optionsLength = headerLength - TCP_HEADER_SIZE;
            if (optionsLength > 0) {
                buffer.position(buffer.position() + optionsLength);
            }
        }

        public boolean isSYN() {
            return (flags & SYN) == SYN;
        }

        public boolean isACK() {
            return (flags & ACK) == ACK;
        }

        public boolean isFIN() {
            return (flags & FIN) == FIN;
        }

        public boolean isRST() {
            return (flags & RST) == RST;
        }

        private void fillHeader(ByteBuffer buffer) {
            buffer.putShort((short) sourcePort);
            buffer.putShort((short) destinationPort);
            buffer.putInt((int) sequenceNumber);
            buffer.putInt((int) acknowledgementNumber);
            buffer.put(dataOffsetAndReserved);
            buffer.put(flags);
            buffer.putShort((short) window);
            buffer.putShort((short) checksum);
            buffer.putShort((short) urgentPointer);
        }
    }

    public static final class UDPHeader {
        public int sourcePort;
        public int destinationPort;
        public int length;
        public int checksum;

        private UDPHeader(ByteBuffer buffer) {
            sourcePort = unsignedShort(buffer.getShort());
            destinationPort = unsignedShort(buffer.getShort());
            length = unsignedShort(buffer.getShort());
            checksum = unsignedShort(buffer.getShort());
        }

        private void fillHeader(ByteBuffer buffer) {
            buffer.putShort((short) sourcePort);
            buffer.putShort((short) destinationPort);
            buffer.putShort((short) length);
            buffer.putShort((short) checksum);
        }
    }
}
