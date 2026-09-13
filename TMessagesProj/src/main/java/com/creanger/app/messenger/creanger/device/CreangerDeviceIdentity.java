package com.creanger.app.messenger.creanger.device;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.text.TextUtils;

import com.creanger.app.messenger.creanger.model.AuthModels.DeviceInfo;
import com.creanger.app.messenger.creanger.model.AuthModels.Fingerprint;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

/**
 * Device identity for the Creanger auth layer.
 *
 * Privacy requirements from the phase spec: NO IMEI, MAC, serial number or any
 * hardware identifier is read. The identity is a randomly generated UUID that
 * persists on first use (so it is stable across app restarts for this install);
 * the locale/timezone fingerprint is derived from system settings only.
 */
public final class CreangerDeviceIdentity {

    private static final String PREFS_NAME = "creanger_device";
    private static final String KEY_ID = "device_id";
    private static final String KEY_NAME = "device_name";

    private final Context context;

    public CreangerDeviceIdentity(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Stable per-install anonymous identifier (UUID, generated once). */
    public String getDeviceId() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String id = prefs.getString(KEY_ID, null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            prefs.edit().putString(KEY_ID, id).apply();
        }
        return id;
    }

    /** Human-readable device name (model, no privacy-sensitive IDs). */
    public String getDeviceName() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String name = prefs.getString(KEY_NAME, null);
        if (name == null) {
            String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
            String model = Build.MODEL == null ? "" : Build.MODEL.trim();
            StringBuilder sb = new StringBuilder();
            if (!manufacturer.isEmpty()) {
                sb.append(characterCleanse(manufacturer));
            }
            if (!model.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(characterCleanse(model));
            }
            if (sb.length() == 0) {
                sb.append("Creanger Device");
            }
            name = sb.toString();
            prefs.edit().putString(KEY_NAME, name).apply();
        }
        return name;
    }

    /**
     * The {@code fingerprint} object expected by the Auth Server's
     * {@code validateFingerprint}: platform + locale + timezone strings.
     */
    public Fingerprint getFingerprint() {
        return new Fingerprint(
                "android",
                Locale.getDefault().toString(),
                TimeZone.getDefault().getID());
    }

    /** Full {@code device} object for register/login verify calls. */
    public DeviceInfo getDeviceInfo() {
        return new DeviceInfo("android", getDeviceName(), getFingerprint());
    }

    /**
     * A stable, compact identifier for the request "device" concept that is
     * derived from the anonymous account UUID — never from hardware. We ship it
     * optionally; the server treats the fingerprint object as authoritative.
     */
    public String getFingerprintHash() {
        String id = getDeviceId();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(("creanger-device-id:" + id).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    sb.append('0');
                }
                sb.append(hex);
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return id;
        }
    }

    private static String characterCleanse(String value) {
        // Strip control chars (validators reject them server-side too).
        StringBuilder sb = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            if (c >= 0x20 && c != 0x7F) {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}