package com.creanger.app.messenger.creanger;

import android.content.Context;

import com.creanger.app.messenger.creanger.api.SupabaseAuthClient;
import com.creanger.app.messenger.creanger.api.CreangerChatApiClient;
import com.creanger.app.messenger.creanger.api.CreangerMediaUploadClient;
import com.creanger.app.messenger.creanger.api.HttpsUrlConnectionTransport;
import com.creanger.app.messenger.creanger.auth.CreangerAuthAsync;
import com.creanger.app.messenger.creanger.auth.CreangerAuthEngine;
import com.creanger.app.messenger.creanger.auth.AuthState;
import com.creanger.app.messenger.creanger.data.CurrentUserRepository;
import com.creanger.app.messenger.creanger.data.ChatRepository;
import com.creanger.app.messenger.creanger.data.MessageRepository;
import com.creanger.app.messenger.creanger.device.CreangerDeviceIdentity;
import com.creanger.app.messenger.creanger.storage.CreangerTokenStore;
import com.creanger.app.messenger.creanger.storage.KeystoreTokenStore;

/**
 * Public entry point of the Creanger Android Auth Foundation.
 *
 * {@code com.creanger.app.messenger.creanger} — fully isolated from MTProto and the
 * Telegram login stack. Gated by {@link CreangerAuthConfig#isEnabled()};
 * when the {@code USE_CREANGER_AUTH} BuildConfig flag is false (the default)
 * this facade is inert.
 */
public final class CreangerAuth {

    public enum AuthMode {
        /** Disabled - the Telegram/MTProto login path is used unchanged. */
        DISABLED,
        /** Enabled but not yet ready (e.g. no configured Supabase URL). */
        NOT_CONFIGURED,
        /** Supabase Auth (GoTrue) password/Google/email-OTP flows. */
        EMAIL_OTP
    }

    private static volatile CreangerAuth instance;

    private final Context context;
    private final CreangerAuthConfig config;
    private final CreangerDeviceIdentity deviceIdentity;
    private final CreangerTokenStore tokenStore;
    private final CreangerAuthEngine engine;
    private final CreangerAuthAsync async;
    private final CurrentUserRepository currentUserRepository;
    private final ChatRepository chatRepository;
    private final MessageRepository messageRepository;
    private final com.creanger.app.messenger.creanger.api.CreangerChatApiClient chatApiClient;

    private CreangerAuth(Context context) {
        this.context = context.getApplicationContext();
        this.config = CreangerAuthConfig.getInstance(context);
        this.deviceIdentity = new CreangerDeviceIdentity(context);
        this.tokenStore = new KeystoreTokenStore(context);
        SupabaseAuthClient api = new SupabaseAuthClient(new HttpsUrlConnectionTransport(config));
        this.engine = new CreangerAuthEngine(api, tokenStore);
        this.async = new CreangerAuthAsync(engine);
        this.currentUserRepository = new CurrentUserRepository(engine, tokenStore);
        HttpsUrlConnectionTransport chatHttp = new HttpsUrlConnectionTransport(config, config.getChatBaseUrl());
        this.chatApiClient = new CreangerChatApiClient(chatHttp);
        // Media uploads go to the Creanger Media API (ImageBB/Cloudinary are
        // reached server-side by that API). Supabase remains the DB +
        // Realtime layer only; Supabase Storage is NOT used.
        CreangerMediaUploadClient mediaUploadClient =
                new CreangerMediaUploadClient(new HttpsUrlConnectionTransport(config));
        this.chatRepository = new ChatRepository(engine, chatApiClient);
        this.messageRepository = new MessageRepository(engine, chatApiClient, mediaUploadClient);
    }

    public static CreangerAuth getInstance(Context context) {
        CreangerAuth local = instance;
        if (local == null) {
            synchronized (CreangerAuth.class) {
                local = instance;
                if (local == null) {
                    instance = local = new CreangerAuth(context);
                }
            }
        }
        return local;
    }

    public AuthMode getMode() {
        if (!config.isEnabled()) {
            return AuthMode.DISABLED;
        }
        if (!config.hasBaseUrl()) {
            return AuthMode.NOT_CONFIGURED;
        }
        return AuthMode.EMAIL_OTP;
    }

    public boolean isEnabled() {
        return getMode() == AuthMode.EMAIL_OTP;
    }

    public CreangerAuthConfig getConfig() {
        return config;
    }

    public CreangerDeviceIdentity getDeviceIdentity() {
        return deviceIdentity;
    }

    public CreangerAuthEngine getEngine() {
        return engine;
    }

    public CreangerAuthAsync getAsync() {
        return async;
    }

    public CurrentUserRepository getCurrentUserRepository() {
        return currentUserRepository;
    }

    public ChatRepository getChatRepository() {
        return chatRepository;
    }

    public MessageRepository getMessageRepository() {
        return messageRepository;
    }

    /** Direct data-plane client access (presence RPCs, targeted reads). */
    public com.creanger.app.messenger.creanger.api.CreangerChatApiClient getChatApiClient() {
        return chatApiClient;
    }

    public AuthState getState() {
        return engine.getState();
    }

    public AuthState restoreIfAvailable() {
        // Engine constructor already loads the persisted refresh token.
        return engine.getState();
    }

    /** Base URL override for dev/test (HTTPS only). */
    public void setBaseUrlOverride(String url) {
        config.setBaseUrlOverride(url);
        // Re-init engine? No: engine holds no connection; transport reads config
        // lazily per request, so the new URL applies on the next call.
    }
}