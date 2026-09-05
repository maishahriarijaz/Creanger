package com.creanger.app.tgnet;

import com.creanger.app.tgnet.TLObject;
/**
 * Minimal TLMethod shim — no RPC execution.
 */
public abstract class TLMethod<T extends TLObject> extends TLObject {
    public abstract T deserializeResponse(InputSerializedData stream, int constructor, boolean exception);
}