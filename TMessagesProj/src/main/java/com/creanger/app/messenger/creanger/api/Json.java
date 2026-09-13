package com.creanger.app.messenger.creanger.api;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Map;

/**
 * Unchecked wrapper over {@link JSONObject} building. JSONException originates
 * only from invalid types passed to put() — a programming error here — so it is
 * rethrown as {@link IllegalStateException} rather than threading checked
 * exceptions through the whole auth layer.
 */
public final class Json {

    private Json() {
    }

    public static JSONObject obj() {
        return new JSONObject();
    }

    public static JSONObject put(JSONObject o, String key, Object value) {
        try {
            return o.put(key, value);
        } catch (JSONException e) {
            throw new IllegalStateException("JSON serialization failed for key '" + key + "'", e);
        }
    }
}