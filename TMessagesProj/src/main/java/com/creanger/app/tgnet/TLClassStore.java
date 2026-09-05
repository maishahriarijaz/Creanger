package com.creanger.app.tgnet;

import com.creanger.app.tgnet.TLObject;
public class TLClassStore {
    private static TLClassStore instance;
    public static TLClassStore Instance() {
        if (instance == null) instance = new TLClassStore();
        return instance;
    }
    public TLObject TLdeserialize(InputSerializedData stream, int constructor, boolean exception) { return null; }
}