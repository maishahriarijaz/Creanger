package com.creanger.app.messenger.creanger.realtime;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Random;

/**
 * Minimal, dependency-free RFC 6455 frame codec used by
 * {@link SocketMessageRealtimeTransport}. Pure-JVM and fully unit-testable:
 * frame encode/decode (masking, length encoding, close codes) and the
 * WebSocket opening-handshake request/response helpers.
 *
 * Only the subset of the protocol the Realtime client needs is implemented:
 * text/ping/pong/close frames, client-side masking, and a streaming-friendly
 * decode that reports exactly how many more bytes are required.
 */
public final class WebSocketFrameCodec {

    public static final int OP_CONTINUATION = 0x0;
    public static final int OP_TEXT = 0x1;
    public static final int OP_BINARY = 0x2;
    public static final int OP_CLOSE = 0x8;
    public static final int OP_PING = 0x9;
    public static final int OP_PONG = 0xA;

    public static final int CLOSE_NORMAL = 1000;
    /** Supabase Realtime: REST unauthorized (join rejected). */
    public static final int CLOSE_UNAUTHORIZED = 4003;
    /** Supabase Realtime: REST forbidden (row-level security). */
    public static final int CLOSE_FORBIDDEN = 4007;

    private static final Random MASK = new SecureRandom();

    private WebSocketFrameCodec() {
    }

    public static final class Frame {
        public final int opcode;
        public final byte[] payload;
        /** Valid when {@code opcode == OP_CLOSE}. */
        public final int closeCode;
        /** Total bytes consumed from the buffer by this frame. */
        public final int consumed;

        Frame(int opcode, byte[] payload, int closeCode, int consumed) {
            this.opcode = opcode;
            this.payload = payload;
            this.closeCode = closeCode;
            this.consumed = consumed;
        }
    }

    /** Thrown when more bytes are needed before a frame can be decoded. */
    public static final class NotEnoughData extends Exception {
        public final int needed;

        NotEnoughData(int needed) {
            this.needed = needed;
        }
    }

    public static final class MalformedFrame extends Exception {
        MalformedFrame(String message) {
            super(message);
        }
    }

    // ---- encode (client → server frames are always masked) ----

    /** Encodes a masked client frame (RFC 6455 §5.3). */
    public static byte[] clientFrame(int opcode, byte[] payload) {
        return encode(opcode, payload, true);
    }

    /** Encodes an unmasked frame — used by tests for server-side frames. */
    public static byte[] serverFrame(int opcode, byte[] payload) {
        return encode(opcode, payload, false);
    }

    /** Encodes a close frame carrying {@code closeCode}. */
    public static byte[] closeFrame(int closeCode) {
        return encode(OP_CLOSE, new byte[]{(byte) (closeCode >> 8), (byte) closeCode}, true);
    }

    private static byte[] encode(int opcode, byte[] payload, boolean masked) {
        int len = payload == null ? 0 : payload.length;
        int headSize = 2;
        if (len > 65535) {
            headSize += 8;
        } else if (len > 125) {
            headSize += 2;
        }
        if (masked) {
            headSize += 4;
        }
        byte[] out = new byte[headSize + len];
        out[0] = (byte) (0x80 | opcode);
        int idx = 1;
        if (len <= 125) {
            out[idx++] = (byte) (len | (masked ? 0x80 : 0));
        } else if (len <= 65535) {
            out[idx++] = (byte) (126 | (masked ? 0x80 : 0));
            out[idx++] = (byte) (len >>> 8);
            out[idx++] = (byte) len;
        } else {
            out[idx++] = (byte) (127 | (masked ? 0x80 : 0));
            for (int i = 7; i >= 0; i--) {
                out[idx++] = (byte) (len >>> (8 * i));
            }
        }
        int maskKey = 0;
        if (masked && len > 0) {
            maskKey = MASK.nextInt();
            out[idx++] = (byte) (maskKey >>> 24);
            out[idx++] = (byte) (maskKey >>> 16);
            out[idx++] = (byte) (maskKey >>> 8);
            out[idx++] = (byte) maskKey;
        } else if (masked) {
            maskKey = MASK.nextInt();
            out[idx++] = (byte) (maskKey >>> 24);
            out[idx++] = (byte) (maskKey >>> 16);
            out[idx++] = (byte) (maskKey >>> 8);
            out[idx++] = (byte) maskKey;
        }
        for (int i = 0; i < len; i++) {
            out[headSize + i] = masked ? (byte) (payload[i] ^ maskByte(maskKey, i)) : payload[i];
        }
        return out;
    }

    private static byte maskByte(int maskKey, int i) {
        return (byte) (maskKey >> (8 * (3 - (i & 3))));
    }

    // ---- decode ----

    /**
     * Decodes exactly one frame from {@code buf[offset .. offset+available)}.
     * Throws {@link NotEnoughData} (with the number of bytes needed) when the
     * frame is incomplete so a streaming reader can buffer and retry.
     */
    public static Frame decode(byte[] buf, int offset, int available)
            throws NotEnoughData, MalformedFrame {
        if (offset < 0 || available < 0 || offset + available > buf.length) {
            throw new MalformedFrame("bad buffer bounds");
        }
        if (available < 2) {
            throw new NotEnoughData(2);
        }
        int opcode = buf[offset] & 0x0F;
        int lenByte = buf[offset + 1] & 0x7F;
        boolean masked = (buf[offset + 1] & 0x80) != 0;
        int extra = 0;
        long payloadLen = lenByte;
        if (lenByte == 126) {
            extra = 2;
            payloadLen = 0;
        } else if (lenByte == 127) {
            extra = 8;
            payloadLen = 0;
        }
        int maskSize = masked ? 4 : 0;
        int headSize = 2 + extra + maskSize;
        if (available < headSize) {
            throw new NotEnoughData(headSize);
        }
        int idx = 2;
        if (lenByte == 126) {
            payloadLen = ((buf[idx] & 0xFF) << 8) | (buf[idx + 1] & 0xFF);
            idx += 2;
        } else if (lenByte == 127) {
            long v = 0;
            for (int i = 0; i < 8; i++) {
                v = (v << 8) | (buf[idx + i] & 0xFF);
            }
            payloadLen = v;
            idx += 8;
        }
        if (payloadLen > Integer.MAX_VALUE - headSize) {
            throw new MalformedFrame("frame too large");
        }
        int total = headSize + (int) payloadLen;
        if (available < total) {
            throw new NotEnoughData(total);
        }
        int maskKey = 0;
        if (masked) {
            maskKey = ((buf[idx] & 0xFF) << 24)
                    | ((buf[idx + 1] & 0xFF) << 16)
                    | ((buf[idx + 2] & 0xFF) << 8)
                    | (buf[idx + 3] & 0xFF);
            idx += 4;
        }
        byte[] payload = new byte[(int) payloadLen];
        for (int i = 0; i < payloadLen; i++) {
            payload[i] = masked ? (byte) (buf[idx + i] ^ maskByte(maskKey, i)) : buf[idx + i];
        }
        int closeCode = opcode == OP_CLOSE && payloadLen >= 2
                ? ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF)
                : 0;
        return new Frame(opcode, payload, closeCode, total);
    }

    // ---- opening handshake ----

    /** Random RFC 6455 Sec-WebSocket-Key (base64 of 16 random bytes). */
    public static String generateKey() {
        byte[] raw = new byte[16];
        MASK.nextBytes(raw);
        return Base64.getEncoder().encodeToString(raw);
    }

    /** Builds an HTTP Upgrade request for the Realtime endpoint. */
    public static String handshakeRequest(String host, String path, String key,
                                          Map<String, String> extraHeaders) {
        StringBuilder sb = new StringBuilder();
        sb.append("GET ").append(path).append(" HTTP/1.1\r\n");
        sb.append("Host: ").append(host).append("\r\n");
        sb.append("Upgrade: websocket\r\n");
        sb.append("Connection: Upgrade\r\n");
        sb.append("Sec-WebSocket-Key: ").append(key).append("\r\n");
        sb.append("Sec-WebSocket-Version: 13\r\n");
        if (extraHeaders != null) {
            for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
                }
            }
        }
        sb.append("\r\n");
        return sb.toString();
    }

    /** Parses the status code of a handshake response head (e.g. 101). */
    public static int responseStatus(String head) {
        if (head == null) {
            return -1;
        }
        int sp1 = head.indexOf(' ');
        if (sp1 < 0) {
            return -1;
        }
        int sp2 = head.indexOf(' ', sp1 + 1);
        if (sp2 < 0) {
            sp2 = head.indexOf('\r', sp1 + 1);
        }
        if (sp2 < 0) {
            sp2 = head.length();
        }
        try {
            return Integer.parseInt(head.substring(sp1 + 1, sp2).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Convenience: encodes a text frame for the given payload. */
    public static byte[] textFrame(String payload) {
        return clientFrame(OP_TEXT, payload.getBytes(StandardCharsets.UTF_8));
    }
}