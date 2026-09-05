package com.creanger.app.network.engine;

import com.creanger.app.messenger.AndroidUtilities;
import com.creanger.app.messenger.ApplicationLoader;
import com.creanger.app.messenger.FileLog;
import com.creanger.app.messenger.Utilities;
import com.creanger.app.messenger.creanger.CreangerAuth;
import com.creanger.app.messenger.creanger.api.CreangerApiException;
import com.creanger.app.messenger.creanger.api.CreangerChatApiClient;
import com.creanger.app.messenger.creanger.data.ChatRepository;
import com.creanger.app.network.CustomNetworkInterface;
import com.creanger.app.messenger.creanger.model.MessageModels;
import com.creanger.app.messenger.creanger.model.ChatModels;
import com.creanger.app.messenger.creanger.model.ChatModels.CreangerChat;
import com.creanger.app.tgnet.RequestDelegate;
import com.creanger.app.tgnet.TLRPC;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Custom network engine - a delegate facade over the REAL Creanger backend
 * stack (Supabase GoTrue + PostgREST + Realtime).
 *
 * This class owns NO transport of its own. All operations are delegated to
 * the existing, tested data plane:
 *   - auth/session:      {@link CreangerAuth} (SupabaseAuthClient + KeystoreTokenStore)
 *   - chats/messages:    {@link ChatRepository}, bridge-level send with pending/
 *                        failed state and idempotent realtime apply
 *   - presence:          {@code set_presence} RPC (migration 036) via
 *                        {@link CreangerChatApiClient#setPresence}
 *
 * Legacy TLRPC-shaped requests arriving through {@link #sendRequest} are no longer supported.
 * The Creanger backend is now accessed directly via repositories.
 */
public class CustomBackendNetworkEngine implements CustomNetworkInterface {

    private static final int UserConfigCount = 3;
    private static final CustomBackendNetworkEngine[] Instances = new CustomBackendNetworkEngine[UserConfigCount];
    private static final Object[] LockObjects = new Object[UserConfigCount];

    static {
        for (int i = 0; i < UserConfigCount; i++) {
            LockObjects[i] = new Object();
        }
    }

    private final int currentAccount;
    private volatile int connectionState = ConnectionStateWaitingForNetwork;
    private final AtomicInteger requestTokenGenerator = new AtomicInteger(1);
    private final ConcurrentHashMap<Integer, RequestDelegate> pendingCallbacks = new ConcurrentHashMap<>();
    private final List<ConnectionStateListener> stateListeners = Collections.synchronizedList(new ArrayList<>());

    /** dialogId (long, negative synthetic space) -> Creanger chat UUID. */
    private final Map<Long, String> chatUuidByDialogId = new ConcurrentHashMap<>();
    private final Map<String, Long> dialogIdByChatUuid = new ConcurrentHashMap<>();

    private CreangerAuth auth;
    private CreangerChatBridgeRef bridgeRef;
    private volatile boolean initialized;

    /** Minimal indirection so tests can stub sending without Android. */
    interface CreangerChatBridgeRef {
        boolean isReady();
        String sendText(String chatUuid, String clientMessageId, String content) throws Exception;
        List<CreangerChat> refreshChats() throws Exception;
        List<MessageModels.CreangerMessage> loadMessages(String chatUuid, int limit) throws Exception;
        void setPresence(String status, boolean touchLastSeen) throws Exception;
        String currentUserId();
        void signOut() throws Exception;
    }

    public static CustomBackendNetworkEngine getInstance(int num) {
        if (num < 0 || num >= UserConfigCount) {
            return null;
        }
        CustomBackendNetworkEngine instance = Instances[num];
        if (instance == null) {
            synchronized (LockObjects[num]) {
                instance = Instances[num];
                if (instance == null) {
                    Instances[num] = instance = new CustomBackendNetworkEngine(num);
                }
            }
        }
        return instance;
    }

    private CustomBackendNetworkEngine(int account) {
        this.currentAccount = account;
    }

    @Override
    public void init(int account, NetworkConfig config) {
        if (initialized) {
            return;
        }
        auth = CreangerAuth.getInstance(ApplicationLoader.applicationContext);
        if (!auth.isEnabled()) {
            FileLog.d("backend engine: creanger auth disabled");
            setConnectionState(ConnectionStateWaitingForNetwork);
            initialized = true;
            return;
        }

        auth.restoreIfAvailable();

        final CreangerAuth authRef = this.auth;
        this.bridgeRef = new CreangerChatBridgeRef() {
            @Override
            public boolean isReady() {
                return authRef.getState() == com.creanger.app.messenger.creanger.auth.AuthState.AUTHENTICATED;
            }

            @Override
            public String sendText(String chatUuid, String clientMessageId, String content) throws Exception {
                return authRef.getMessageRepository().sendTextMessage(chatUuid, clientMessageId, content);
            }

            @Override
            public List<CreangerChat> refreshChats() throws Exception {
                return authRef.getChatRepository().refreshChats();
            }

            @Override
            public List<MessageModels.CreangerMessage> loadMessages(String chatUuid, int limit) throws Exception {
                List<MessageModels.CreangerMessage> cached =
                        new ArrayList<>(authRef.getMessageRepository().getCachedMessages(chatUuid));
                // newest-first from cache; trim to limit for the facade contract
                if (limit > 0 && cached.size() > limit) {
                    cached = cached.subList(0, limit);
                }
                return cached;
            }

            @Override
            public void setPresence(String status, boolean touchLastSeen) throws Exception {
                String token = authRef.getEngine().requireAccessToken(); // refreshes on expiry
                authRef.getChatApiClient().setPresence(token, status, touchLastSeen);
            }

            @Override
            public String currentUserId() {
                com.creanger.app.messenger.creanger.model.AuthModels.CreangerUser u = authRef.getEngine().currentUser();
                return u != null ? u.id : null;
            }

            @Override
            public void signOut() throws Exception {
                try {
                    authRef.getEngine().logout();
                } finally {
                    authRef.getEngine().clearLocalSession();
                }
            }
        };

        setConnectionState(bridgeRef.isReady() ? ConnectionStateConnected : ConnectionStateWaitingForNetwork);
        initialized = true;
        Utilities.stageQueue.postRunnable(this::refreshChatMapping);
    }

    private void refreshChatMapping() {
        try {
            List<CreangerChat> chats = bridgeRef != null ? bridgeRef.refreshChats() : null;
            if (chats == null) {
                return;
            }
            long synthetic = -2_000_000_000_000L; // stable negative dialog-id space
            for (CreangerChat c : chats) {
                Long existing = dialogIdByChatUuid.get(c.id);
                if (existing == null) {
                    existing = synthetic--;
                    dialogIdByChatUuid.put(c.id, existing);
                }
                chatUuidByDialogId.put(existing, c.id);
            }
        } catch (Exception e) {
            FileLog.e("chat mapping refresh failed: " + e.getMessage());
        }
    }

    // ==================== CustomNetworkInterface ====================

    @Override
    public int sendRequest(Object request, RequestDelegate onComplete, int flags) {
        return sendRequest(request, onComplete, flags, DEFAULT_DATACENTER_ID, ConnectionTypeGeneric);
    }

    @Override
    public int sendRequest(final Object request, final RequestDelegate onComplete, int flags, int datacenterId, int connectionType) {
        final int token = requestTokenGenerator.getAndIncrement();
        if (onComplete != null) {
            pendingCallbacks.put(token, onComplete);
        }
        // Legacy TLRPC requests are no longer supported - delegate directly to Creanger
        Utilities.stageQueue.postRunnable(() -> {
            if (onComplete != null) {
                AndroidUtilities.runOnUIThread(() -> onComplete.run(null, new TLRPC.TL_error() {{ code = 400; text = "Legacy TLRPC not supported"; }}));
            }
            pendingCallbacks.remove(token);
        });
        return token;
    }

    @Override
    public void cancelRequest(int requestToken, boolean notifyServer) {
        cancelRequest(requestToken, notifyServer, null);
    }

    @Override
    public void cancelRequest(int requestToken, boolean notifyServer, Runnable onCancelled) {
        RequestDelegate cb = pendingCallbacks.remove(requestToken);
        if (cb != null) {
            AndroidUtilities.runOnUIThread(() -> cb.run(null, new TLRPC.TL_error() {{ code = 0; text = "CANCELLED"; }}));
        }
        if (onCancelled != null) {
            onCancelled.run();
        }
    }

    @Override
    public void bindRequestToGuid(int requestToken, int guid) {
        // No-op for Creanger backend
    }

    @Override
    public void cancelRequestsForGuid(int guid) {
        // No-op for Creanger backend
    }

    @Override
    public int getCurrentTime() {
        return (int) (System.currentTimeMillis() / 1000);
    }

    @Override
    public long getCurrentTimeMillis() {
        return System.currentTimeMillis();
    }

    @Override
    public int getConnectionState() {
        return connectionState;
    }

    @Override
    public void setNetworkAvailable(boolean online, int networkType, boolean slow) {
        // Handled by CreangerAuth
    }

    @Override
    public void pauseNetwork() {
        // Handled by CreangerAuth
    }

    @Override
    public void resumeNetwork() {
        // Handled by CreangerAuth
    }

    @Override
    public void cleanup(boolean resetKeys) {
        if (auth != null) {
            try {
                auth.getEngine().clearLocalSession();
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        initialized = false;
        setConnectionState(ConnectionStateWaitingForNetwork);
    }

    @Override
    public void setUserId(long userId) {
        // Handled by CreangerAuth
    }

    @Override
    public void addConnectionStateListener(ConnectionStateListener listener) {
        stateListeners.add(listener);
    }

    @Override
    public void removeConnectionStateListener(ConnectionStateListener listener) {
        stateListeners.remove(listener);
    }

    @Override
    public int getTimeDifference() {
        return 0;
    }

    @Override
    public int getCurrentDatacenterId() {
        return DEFAULT_DATACENTER_ID;
    }

    private void setConnectionState(int state) {
        connectionState = state;
        for (ConnectionStateListener l : stateListeners) {
            l.onConnectionStateChanged(state);
        }
    }
}