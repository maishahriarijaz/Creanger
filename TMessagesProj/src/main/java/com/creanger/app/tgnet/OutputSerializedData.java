package com.creanger.app.tgnet;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Minimal output stream for serialization compatibility.
 */
public class OutputSerializedData {
    private byte[] buffer;
    private int position;
    
    public OutputSerializedData() {
        this(1024);
    }
    
    public OutputSerializedData(int size) {
        buffer = new byte[size];
        position = 0;
    }
    
    public void writeInt32(int value) {
        ensureCapacity(4);
        buffer[position++] = (byte) value;
        buffer[position++] = (byte) (value >> 8);
        buffer[position++] = (byte) (value >> 16);
        buffer[position++] = (byte) (value >> 24);
    }
    
    public void writeInt64(long value) {
        ensureCapacity(8);
        for (int i = 0; i < 8; i++) {
            buffer[position++] = (byte) (value >> (i * 8));
        }
    }
    
    public void writeByteArray(byte[] data) {
        writeByteArray(data, 0, data.length);
    }
    
    public void writeByteArray(byte[] data, int offset, int length) {
        writeInt32(length);
        ensureCapacity(length);
        System.arraycopy(data, offset, buffer, position, length);
        position += length;
    }
    
    public void writeFloat(float value) {
        writeInt32(Float.floatToIntBits(value));
    }

    public void writeDouble(double value) {
        writeInt64(Double.doubleToLongBits(value));
    }

    public void writeBool(boolean value) {
        writeInt32(value ? 1 : 0);
    }

    public void writeByte(int value) {
        ensureCapacity(1);
        buffer[position++] = (byte) value;
    }

    public void writeString(String value) {
        if (value == null) {
            writeInt32(-1);
        } else {
            byte[] bytes = value.getBytes();
            writeByteArray(bytes);
        }
    }

    public void writeByteBuffer(NativeByteBuffer b) {
        if (b != null) {
            byte[] data = new byte[b.length()];
            writeByteArray(data);
        }
    }

    private void ensureCapacity(int needed) {
        if (position + needed > buffer.length) {
            byte[] newBuffer = new byte[Math.max(buffer.length * 2, position + needed)];
            System.arraycopy(buffer, 0, newBuffer, 0, position);
            buffer = newBuffer;
        }
    }
    
    public byte[] toByteArray() {
        byte[] result = new byte[position];
        System.arraycopy(buffer, 0, result, 0, position);
        return result;
    }
    
    public int length() {
        return position;
    }
}