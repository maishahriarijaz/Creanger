package com.creanger.app.tgnet;

/**
 * Minimal abstract serialized data — local buffer base.
 */
public abstract class AbstractSerializedData {
    public int remaining() { return 0; }
    public int getPosition() { return 0; }
    public void setPosition(int pos) {}
}