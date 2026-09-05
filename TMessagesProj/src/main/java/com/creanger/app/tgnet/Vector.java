package com.creanger.app.tgnet;

import com.creanger.app.tgnet.TLObject;
import java.util.ArrayList;

/**
 * Minimal Vector shim — local list wrapper, not Telegram wire format.
 */
public class Vector<T> extends TLObject {
    public ArrayList<T> objects = new ArrayList<>();
    public static <T> Vector<T> deserialize(InputSerializedData stream, Deserializer<T> deserializer, boolean exception) {
        return new Vector<>();
    }

    public interface Deserializer<T> {
        T deserialize(InputSerializedData stream, int constructor, boolean exception);
    }
    public interface TLDeserializer<T> extends Deserializer<T> {}
}