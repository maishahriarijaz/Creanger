package com.creanger.app.tgnet;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Minimal SerializedData shim — local buffer, not Telegram wire format.
 */
public class SerializedData extends AbstractSerializedData {
    private ByteBuffer buffer;

    public SerializedData() {
        this(1024);
    }

    public SerializedData(int size) {
        buffer = ByteBuffer.allocate(size);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
    }

    public SerializedData(byte[] data) {
        buffer = ByteBuffer.wrap(data);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
    }

    public void writeInt32(int v) { buffer.putInt(v); }
    public void writeInt64(long v) { buffer.putLong(v); }
    public void writeFloat(float v) { buffer.putFloat(v); }
    public void writeDouble(double v) { buffer.putDouble(v); }
    public void writeBool(boolean v) { buffer.putInt(v ? 1 : 0); }
    public void writeByte(int v) { buffer.put((byte) v); }
    public void writeString(String s) { /* stub */ }
    public void writeByteArray(byte[] b) { /* stub */ }
    public void writeByteArray(byte[] b, int offset, int length) { /* stub */ }
    public void writeByteBuffer(NativeByteBuffer b) { /* stub */ }
    public int readInt32(boolean exception) { return 0; }
    public long readInt64(boolean exception) { return 0; }
    public float readFloat(boolean exception) { return 0; }
    public double readDouble(boolean exception) { return 0; }
    public boolean readBool(boolean exception) { return false; }
    public byte readByte(boolean exception) { return 0; }
    public String readString(boolean exception) { return ""; }
    public byte[] readByteArray(boolean exception) { return new byte[0]; }
    public byte[] readData(int count, boolean exception) { return new byte[0]; }
    public NativeByteBuffer readByteBuffer(boolean exception) { return null; }
    public void readBytes(byte[] b, boolean exception) {}
    public void readBytes(byte[] b, int offset, int count, boolean exception) {}
    public int length() { return buffer.position(); }
    public byte[] toByteArray() { return buffer.array(); }
    public void cleanup() {}
    public int getPosition() { return buffer.position(); }
}