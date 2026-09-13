package com.creanger.app.network.auth;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import com.creanger.app.messenger.ApplicationLoader;
import com.creanger.app.messenger.FileLog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableEntryException;
import java.security.cert.CertificateException;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Secure token storage using Android Keystore + EncryptedSharedPreferences.
 * Stores access token, refresh token, and user credentials securely.
 */
public class SecureTokenStore {

    private static final String TAG = "SecureTokenStore";
    private static final String KEY_ALIAS = "creanger_auth_key";
    private static final String PREFS_NAME = "creanger_secure_tokens";
    private static final String KEY_ACCESS_TOKEN = "access_token";
    private static final String KEY_REFRESH_TOKEN = "refresh_token";
    private static final String KEY_USER_ID = "user_id";
    private static final String KEY_TOKEN_EXPIRY = "token_expiry";
    private static final String KEY_AUTH_METHOD = "auth_method";

    private static volatile SecureTokenStore instance;
    private final SharedPreferences encryptedPrefs;
    private final SecretKey secretKey;

    private SecureTokenStore(Context context) {
        // Initialize EncryptedSharedPreferences (API 23+)
        this.encryptedPrefs = createEncryptedPrefs(context);
        this.secretKey = initKey();
    }

    public static SecureTokenStore getInstance() {
        if (instance == null) {
            synchronized (SecureTokenStore.class) {
                if (instance == null) {
                    instance = new SecureTokenStore(ApplicationLoader.applicationContext);
                }
            }
        }
        return instance;
    }

    private SharedPreferences createEncryptedPrefs(Context context) {
        try {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        } catch (Exception e) {
            FileLog.e("Failed to create encrypted prefs, using fallback", e);
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        }
    }

    private SecretKey initKey() {
        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);

            if (!keyStore.containsAlias(KEY_ALIAS)) {
                KeyGenerator keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
                keyGenerator.init(new KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setUserAuthenticationRequired(false)
                        .build());
                keyGenerator.generateKey();
            }

            KeyStore.SecretKeyEntry secretKeyEntry = (KeyStore.SecretKeyEntry) keyStore.getEntry(KEY_ALIAS, null);
            return secretKeyEntry.getSecretKey();
        } catch (Exception e) {
            FileLog.e("Failed to init key for secure storage", e);
            return null;
        }
    }

    // ==================== Public API ====================

    public void saveTokens(String accessToken, String refreshToken, long userId, long expiresInSeconds) {
        try {
            long expiry = System.currentTimeMillis() + (expiresInSeconds * 1000L);

            String encryptedAccess = encrypt(accessToken);
            String encryptedRefresh = refreshToken != null ? encrypt(refreshToken) : null;

            encryptedPrefs.edit()
                    .putString(KEY_ACCESS_TOKEN, encryptedAccess)
                    .putString(KEY_REFRESH_TOKEN, encryptedRefresh)
                    .putLong(KEY_USER_ID, userId)
                    .putLong(KEY_TOKEN_EXPIRY, expiry)
                    .apply();

            FileLog.d("Tokens saved securely, expires in " + expiresInSeconds + "s");
        } catch (Exception e) {
            FileLog.e("Failed to save tokens", e);
        }
    }

    public String getAccessToken() {
        String encrypted = encryptedPrefs.getString(KEY_ACCESS_TOKEN, null);
        if (encrypted == null) return null;

        try {
            return decrypt(encrypted);
        } catch (Exception e) {
            FileLog.e("Failed to decrypt access token", e);
            return null;
        }
    }

    public String getRefreshToken() {
        String encrypted = encryptedPrefs.getString(KEY_REFRESH_TOKEN, null);
        if (encrypted == null) return null;

        try {
            return decrypt(encrypted);
        } catch (Exception e) {
            FileLog.e("Failed to decrypt refresh token", e);
            return null;
        }
    }

    public long getUserId() {
        return encryptedPrefs.getLong(KEY_USER_ID, 0);
    }

    public boolean isTokenExpired() {
        long expiry = encryptedPrefs.getLong(KEY_TOKEN_EXPIRY, 0);
        return expiry > 0 && System.currentTimeMillis() >= expiry;
    }

    public long getTokenExpiryTime() {
        return encryptedPrefs.getLong(KEY_TOKEN_EXPIRY, 0);
    }

    public void clearTokens() {
        encryptedPrefs.edit()
                .remove(KEY_ACCESS_TOKEN)
                .remove(KEY_REFRESH_TOKEN)
                .remove(KEY_USER_ID)
                .remove(KEY_TOKEN_EXPIRY)
                .remove(KEY_AUTH_METHOD)
                .apply();
        FileLog.d("Tokens cleared");
    }

    public boolean hasValidSession() {
        return getAccessToken() != null && !isTokenExpired();
    }

    // ==================== Encryption Helpers ====================

    private String encrypt(String plaintext) throws Exception {
        if (secretKey == null) {
            throw new IllegalStateException("SecureTokenStore: secretKey not initialized - cannot encrypt without Android Keystore");
        }

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey);
        byte[] iv = cipher.getIV();
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

        // Combine IV + ciphertext
        byte[] combined = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);

        return Base64.encodeToString(combined, Base64.NO_WRAP);
    }

    private String decrypt(String encrypted) throws Exception {
        if (secretKey == null) {
            throw new IllegalStateException("SecureTokenStore: secretKey not initialized - cannot decrypt without Android Keystore");
        }

        byte[] combined = Base64.decode(encrypted, Base64.NO_WRAP);
        int ivSize = 12; // GCM standard IV size

        byte[] iv = new byte[ivSize];
        byte[] ciphertext = new byte[combined.length - ivSize];
        System.arraycopy(combined, 0, iv, 0, ivSize);
        System.arraycopy(combined, ivSize, ciphertext, 0, ciphertext.length);

        GCMParameterSpec spec = new GCMParameterSpec(128, iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);

        byte[] plaintext = cipher.doFinal(ciphertext);
        return new String(plaintext, StandardCharsets.UTF_8);
    }

    // ==================== Migration Helpers ====================

    public void migrateFromLegacyPrefs(Context context) {
        SharedPreferences legacy = context.getSharedPreferences("creanger_tokens", Context.MODE_PRIVATE);
        String access = legacy.getString("access_token", null);
        String refresh = legacy.getString("refresh_token", null);
        long userId = legacy.getLong("user_id", 0);
        long expiry = legacy.getLong("token_expiry", 0);

        if (access != null) {
            long now = System.currentTimeMillis();
            long remaining = expiry > 0 ? (expiry - now) / 1000 : 3600;
            saveTokens(access, refresh, userId, Math.max(remaining, 60));
            legacy.edit().clear().apply();
            FileLog.d("Migrated tokens from legacy storage");
        }
    }
}