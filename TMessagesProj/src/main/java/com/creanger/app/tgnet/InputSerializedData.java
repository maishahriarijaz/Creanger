package com.creanger.app.tgnet;

import java.io.IOException;
import java.io.InputStream;

/**
 * Minimal input stream for deserialization compatibility.
 */
public class InputSerializedData {
    private byte[] buffer;
    private int position;
    private int limit;
    
    public InputSerializedData(byte[] data) {
        this(data, 0, data.length);
    }
    
    public InputSerializedData(byte[] data, int offset, int length) {
        buffer = data;
        position = offset;
        limit = offset + length;
    }
    
    public int readInt32(boolean exception) {
        if (position + 4 > limit) {
            if (exception) throw new RuntimeException("Buffer underflow");
            return 0;
        }
        int value = buffer[position] & 0xFF |
                   (buffer[position + 1] & 0xFF) << 8 |
                   (buffer[position + 2] & 0xFF) << 16 |
                   (buffer[position + 3] & 0xFF) << 24;
        position += 4;
        return value;
    }
    
    public long readInt64(boolean exception) {
        if (position + 8 > limit) {
            if (exception) throw new RuntimeException("Buffer underflow");
            return 0;
        }
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value |= ((long) (buffer[position + i] & 0xFF)) << (i * 8);
        }
        position += 8;
        return value;
    }
    
    public String readString(boolean exception) {
        int length = readInt32(exception);
        if (length < 0 || position + length > limit) {
            if (exception) throw new RuntimeException("Invalid string length");
            return "";
        }
        String result = new String(buffer, position, length);
        position += length;
        // Skip padding
        int padding = (4 - (length % 4)) % 4;
        position += padding;
        return result;
    }
    
    public byte[] readByteArray(boolean exception) {
        int length = readInt32(exception);
        if (length < 0 || position + length > limit) {
            if (exception) throw new RuntimeException("Invalid byte array length");
            return new byte[0];
        }
        byte[] result = new byte[length];
        System.arraycopy(buffer, position, result, 0, length);
        position += length;
        // Skip padding
        int padding = (4 - (length % 4)) % 4;
        position += padding;
        return result;
    }
    
    public byte readByte(boolean exception) {
        if (position >= limit) {
            if (exception) throw new RuntimeException("Buffer underflow");
            return 0;
        }
        return buffer[position++];
    }

    public boolean readBool(boolean exception) {
        return readInt32(exception) != 0;
    }

    public float readFloat(boolean exception) {
        if (position + 4 > limit) {
            if (exception) throw new RuntimeException("Buffer underflow");
            return 0;
        }
        int bits = readInt32(exception);
        return Float.intBitsToFloat(bits);
    }

    public double readDouble(boolean exception) {
        if (position + 8 > limit) {
            if (exception) throw new RuntimeException("Buffer underflow");
            return 0;
        }
        long bits = readInt64(exception);
        return Double.longBitsToDouble(bits);
    }

    public byte[] readData(int count, boolean exception) {
        if (position + count > limit) {
            if (exception) throw new RuntimeException("Buffer underflow");
            return new byte[0];
        }
        byte[] result = new byte[count];
        System.arraycopy(buffer, position, result, 0, count);
        position += count;
        return result;
    }

    public NativeByteBuffer readByteBuffer(boolean exception) {
        int length = readInt32(exception);
        if (length < 0 || position + length > limit) {
            if (exception) throw new RuntimeException("Invalid byte buffer length");
            return null;
        }
        position += length;
        return null;
    }

    public void readBytes(byte[] b, boolean exception) {
        readBytes(b, 0, b.length, exception);
    }

    public void readBytes(byte[] b, int offset, int count, boolean exception) {
        if (position + count > limit) {
            if (exception) throw new RuntimeException("Buffer underflow");
            return;
        }
        System.arraycopy(buffer, position, b, offset, count);
        position += count;
    }

    public int remaining() {
        return limit - position;
    }
    
    public int getPosition() {
        return position;
    }
    
    public void setPosition(int pos) {
        position = pos;
    }
}