package com.creanger.app.messenger.creanger.storage;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.creanger.app.messenger.creanger.model.AuthModels.AuthSession;
import com.creanger.app.messenger.creanger.model.AuthModels.CreangerUser;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Keystore-backed token store for the currently authenticated Creanger account.
 *
 * Security model (matches the phase spec):
 *  - The refresh token is encrypted with an AES-256-GCM key material that never
 *    leaves the Android Keystore ({@code AndroidKeyStore}). The ciphertext+IV
 *    lives in SharedPreferences, i.e. the durable secret is never stored in
 *    plaintext anywhere on disk.
 *  - The access token is memory-only by design: it lives on the
 *    {@link AuthSession} held by the engine and is discarded on restart, then
 *    re-obtained via {@code POST /auth/v1/token?grant_type=refresh_token}.
 *
 * Account scoping (fix for the single-unkeyed-slot issue):
 *  - The persisted slot is bound to the owning account identity
 *    ({@code owner_user_id}, the Custom Auth Server {@code auth.users.id} of the
 *    session's {@code CreangerUser}). The store keeps exactly one ACTIVE
 *    account (the app's single authenticated session) but never silently
 *    overwrites another account's slot:
 *      - {@link #store} refuses a write whose owner differs from the stored
 *        owner. Switching accounts REQUIRES {@link #clear()} (logout) first, so
 *        account B can never clobber or adopt account A's refresh token /
 *        cached profile.
 *      - {@link #load} treats an owner/profile mismatch as corruption: it clears
 *        the slot and reports null rather than serving cross-account data.
 *  - A session without a resolvable {@code CreangerUser.id} cannot be verified
 *    as a specific account; its write is accepted but the OWNER label is left
 *    untouched and any previous account's cached profile is dropped, so an
 *    unattributed session never carries another account's profile forward.
 *
 * Thread-safety: callers must not invoke store/load/clear concurrently from
 * multiple threads; the engine serializes with a dedicated lock.
 */
public class KeystoreTokenStore implements CreangerTokenStore {

    private static final String ANDROID_KEY_STORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "creanger_auth_token_aes_key_v1";
    private static final String PREFS_NAME = "creanger_auth_tokens";

    private static final String KEY_CIPHERTEXT = "refresh_token_cipher";
    private static final String KEY_IV = "refresh_token_iv";
    private static final String KEY_USER_JSON = "user_json";
    private static final String KEY_CHECKSUM = "token_checksum";
    private static final String KEY_OWNER_USER = "owner_user_id";

    private static final String GCM_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String LEGACY_KEY_BYTES = "legacy_aes_key_bytes";
    private static final int LEGACY_KEY_SIZE_BYTES = 32;

    private final Context context;
    private final Seal seal;
    private final Kvs kv;

    /**
     * Identity-scoping test seam: injects the crypto (Seal) and the opaque
     * key-value backend (Kvs) so the ownership/guard logic can be exercised on
     * the JVM without Android types. Not for production wiring.
     */
    public KeystoreTokenStore(Seal seal, Kvs kv) {
        this.context = null;
        this.seal = seal;
        this.kv = kv;
    }

    public KeystoreTokenStore(Context context) {
        this.context = context.getApplicationContext();
        this.kv = new SharedPreferencesKvs();
        this.seal = new KeystoreSeal();
    }

    @Override
    public synchronized AuthSession load() {
        try {
            if (kv.getBytes(KEY_CIPHERTEXT) == null) {
                return null;
            }
            byte[] sealed = kv.getBytes(KEY_CIPHERTEXT);
            byte[] iv = kv.getBytes(KEY_IV);
            if (sealed == null || iv == null) {
                return null;
            }
            byte[] raw = seal.decrypt(sealed, iv);
            if (raw == null) {
                return null;
            }
            String refreshToken = new String(raw, StandardCharsets.UTF_8);
            if (!verifyChecksum(refreshToken)) {
                kv.clearAll();
                return null;
            }
            String userJson = kv.getString(KEY_USER_JSON);
            CreangerUser user = userJson != null ? UserJson.fromJson(userJson) : null;
            String owner = kv.getString(KEY_OWNER_USER);
            if (owner != null && user != null && user.id != null && !owner.equals(user.id)) {
                // Corrupted/forged slot: never serve one account's tokens under
                // another account's profile.
                kv.clearAll();
                return null;
            }
            // No access token here on purpose: memory-only.
            return new AuthSession("", refreshToken, user, 0L);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public synchronized void store(AuthSession session) {
        if (session == null || session.refreshToken == null) {
            return;
        }
        try {
            String newOwner = session.user != null ? session.user.id : null;
            String storedOwner = kv.getString(KEY_OWNER_USER);
            // Cross-account guard: an owned slot may only be written by the SAME
            // account. A different account must log out first ({@link #clear}).
            if (storedOwner != null && newOwner != null && !storedOwner.equals(newOwner)) {
                return;
            }
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            byte[] sealed = seal.encrypt(session.refreshToken, iv);
            kv.putBytes(KEY_CIPHERTEXT, sealed);
            kv.putBytes(KEY_IV, iv);
            kv.putString(KEY_CHECKSUM, checksum(session.refreshToken));
            if (newOwner != null) {
                kv.putString(KEY_OWNER_USER, newOwner);
            }
            // A session without a profile must never keep the PREVIOUS
            // account's cached user_json alive (account-switch leak guard).
            if (session.user != null) {
                kv.putString(KEY_USER_JSON, UserJson.toJson(session.user));
            } else {
                kv.remove(KEY_USER_JSON);
            }
        } catch (Exception e) {
            // Keystore failures must not crash the Auth engine; store is
            // best-effort, engine falls back to memory-only session.
        }
    }

    @Override
    public synchronized void clear() {
        kv.clearAll();
    }

    // ---- pluggable crypto / storage ----

    /**
     * {@code encrypt} returns {@code iv || ciphertext} prefixed (the durable
     * byte layout is preserved across upgrades). {@code decrypt} takes the
     * same pre-fixed array and the separately persisted {@code iv}.
     */
    public interface Seal {
        byte[] encrypt(String plaintext, byte[] iv) throws Exception;

        byte[] decrypt(byte[] sealed, byte[] iv) throws Exception;
    }

    /** Opaque key-value backend; byte keys are base64 strings on Android. */
    public interface Kvs {
        byte[] getBytes(String key);

        void putBytes(String key, byte[] value);

        String getString(String key);

        void putString(String key, String value);

        void remove(String key);

        void clearAll();
    }

    private final class SharedPreferencesKvs implements Kvs {
        @Override
        public byte[] getBytes(String key) {
            String b64 = getPrefs().getString(key, null);
            return b64 != null ? Base64.decode(b64, Base64.NO_WRAP) : null;
        }

        @Override
        public void putBytes(String key, byte[] value) {
            getPrefs().edit().putString(key, Base64.encodeToString(value, Base64.NO_WRAP)).apply();
        }

        @Override
        public String getString(String key) {
            return getPrefs().getString(key, null);
        }

        @Override
        public void putString(String key, String value) {
            getPrefs().edit().putString(key, value).apply();
        }

        @Override
        public void remove(String key) {
            getPrefs().edit().remove(key).apply();
        }

        @Override
        public void clearAll() {
            getPrefs().edit().clear().apply();
        }
    }

    private final class KeystoreSeal implements Seal {
        @Override
        public byte[] encrypt(String plaintext, byte[] iv) throws Exception {
            Cipher cipher = Cipher.getInstance(GCM_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
            byte[] cipherBytes = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + cipherBytes.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(cipherBytes, 0, out, iv.length, cipherBytes.length);
            return out;
        }

        @Override
        public byte[] decrypt(byte[] sealed, byte[] iv) throws Exception {
            byte[] cipherBytes = Arrays.copyOfRange(sealed, iv.length, sealed.length);
            Cipher cipher = Cipher.getInstance(GCM_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
            return cipher.doFinal(cipherBytes);
        }
    }

    private SecretKey getOrCreateKey() throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return getOrCreateKeystoreKey();
        }
        return getOrCreateLegacyKey();
    }

    /** API 23+: AES-256-GCM key material held by the Android Keystore. */
    @RequiresApi(api = Build.VERSION_CODES.M)
    private SecretKey getOrCreateKeystoreKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
        keyStore.load(null);
        Key key = keyStore.getKey(KEY_ALIAS, null);
        if (key instanceof SecretKey) {
            return (SecretKey) key;
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE);
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    /**
     * API 21-22 fallback: {@code AndroidKeyStore} on these builds lacks AES
     * key support, so we pin a random per-install key inside SharedPreferences
     * (documented trade-off: weaker than keystore material, but never exported
     * or synced, and only used on legacy devices).
     */
    @NonNull
    private SecretKey getOrCreateLegacyKey() throws NoSuchAlgorithmException {
        SharedPreferences prefs = getPrefs();
        String b64 = prefs.getString(LEGACY_KEY_BYTES, null);
        byte[] bytes;
        if (b64 != null) {
            try {
                bytes = Base64.decode(b64, Base64.NO_WRAP);
                if (bytes.length == LEGACY_KEY_SIZE_BYTES) {
                    return new SecretKeySpec(bytes, "AES");
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        bytes = new byte[LEGACY_KEY_SIZE_BYTES];
        new SecureRandom().nextBytes(bytes);
        prefs.edit().putString(LEGACY_KEY_BYTES, Base64.encodeToString(bytes, Base64.NO_WRAP)).apply();
        return new SecretKeySpec(bytes, "AES");
    }

    private static String checksum(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        int h = Arrays.hashCode(bytes);
        return Integer.toHexString(h) + ":" + bytes.length;
    }

    private boolean verifyChecksum(String value) {
        String expected = kv.getString(KEY_CHECKSUM);
        if (expected == null) {
            return false;
        }
        return Objects.equals(expected, checksum(value));
    }

    private SharedPreferences getPrefs() {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Minimal JSON (de)serialization for the user object, avoiding full Gson. */
    private static final class UserJson {
        private static String toJson(CreangerUser user) {
            StringBuilder sb = new StringBuilder();
            sb.append('{');
            appendEntry(sb, "id", user.id);
            sb.append(',');
            appendEntry(sb, "username", user.username);
            sb.append(',');
            appendEntry(sb, "email", user.email);
            sb.append(',');
            appendEntry(sb, "displayName", user.displayName);
            sb.append(',');
            appendEntry(sb, "avatarUrl", user.avatarUrl);
            sb.append(',');
            appendEntry(sb, "emailVerified", String.valueOf(user.emailVerified));
            sb.append('}');
            return sb.toString();
        }

        private static void appendEntry(StringBuilder sb, String key, String value) {
            sb.append('"').append(key).append("\":");
            if (value == null) {
                sb.append("null");
            } else {
                sb.append('"').append(value.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
            }
        }

        private static CreangerUser fromJson(String json) {
            try {
                org.json.JSONObject o = new org.json.JSONObject(json);
                return new CreangerUser(
                        o.optString("id", null),
                        o.isNull("username") ? null : o.getString("username"),
                        o.isNull("email") ? null : o.getString("email"),
                        o.optBoolean("emailVerified", false),
                        o.isNull("displayName") ? null : o.getString("displayName"),
                        o.isNull("avatarUrl") ? null : o.getString("avatarUrl"),
                        (String) null);
            } catch (Exception e) {
                return null;
            }
        }
    }
}