package com.creanger.app.network;

import com.creanger.app.tgnet.TLRPC;
import android.os.Handler;
import android.os.Looper;

import com.creanger.app.messenger.Utilities;
import com.creanger.app.tgnet.RequestDelegate;

import java.util.concurrent.Executor;

/**
 * 🔌 Custom Network Interface
 * 
 * Abstract interface for pluggable custom backend (REST/WebSocket/gRPC).
 * Replaces MTProto/ConnectionsManager with clean abstraction.
 * UI and local storage remain untouched - only network transport changes.
 */
public interface CustomNetworkInterface {

    // 📡 Connection State Constants (mirror ConnectionsManager for compatibility)
    int ConnectionStateConnecting = 1;
    int ConnectionStateWaitingForNetwork = 2;
    int ConnectionStateConnected = 3;
    int ConnectionStateConnectingToProxy = 4;
    int ConnectionStateUpdating = 5;

    // 📦 Request Flags (subset needed by UI)
    int RequestFlagEnableUnauthorized = 1;
    int RequestFlagFailOnServerErrors = 2;
    int RequestFlagCanCompress = 4;
    int RequestFlagWithoutLogin = 8;
    int RequestFlagTryDifferentDc = 16;
    int RequestFlagForceDownload = 32;
    int RequestFlagInvokeAfter = 64;
    int RequestFlagNeedQuickAck = 128;
    int RequestFlagDoNotWaitFloodWait = 1024;
    int RequestFlagListenAfterCancel = 2048;
    int RequestFlagFailOnServerErrorsExceptFloodWait = 65536;

    // 🌐 Connection Types
    int ConnectionTypeGeneric = 1;
    int ConnectionTypeDownload = 2;
    int ConnectionTypeUpload = 4;
    int ConnectionTypePush = 8;

    int DEFAULT_DATACENTER_ID = Integer.MAX_VALUE;

    // 🔑 Core Network Operations

    /**
     * Initialize network layer with account config
     */
    void init(int currentAccount, NetworkConfig config);

    /**
     * Send request to custom backend
     * @param request request object (can be JSON/Protobuf internally)
     * @param onComplete Completion callback
     * @param flags Request flags
     * @return Request token for cancellation
     */
    int sendRequest(Object request, RequestDelegate onComplete, int flags);

    /**
     * Send request with full options
     */
    int sendRequest(Object request, RequestDelegate onComplete, int flags, int datacenterId, int connectionType);

    /**
     * Cancel pending request
     */
    void cancelRequest(int requestToken, boolean notifyServer);

    /**
     * Cancel request with completion callback
     */
    void cancelRequest(int requestToken, boolean notifyServer, Runnable onCancelled);

    /**
     * Bind request to GUID for grouped cancellation
     */
    void bindRequestToGuid(int requestToken, int guid);

    /**
     * Cancel all requests for GUID
     */
    void cancelRequestsForGuid(int guid);

    /**
     * Get current server time (seconds)
     */
    int getCurrentTime();

    /**
     * Get current server time (milliseconds)
     */
    long getCurrentTimeMillis();

    /**
     * Get current connection state
     */
    int getConnectionState();

    /**
     * Check/set network availability
     */
    void setNetworkAvailable(boolean online, int networkType, boolean slow);

    /**
     * Pause network (app background)
     */
    void pauseNetwork();

    /**
     * Resume network (app foreground)
     */
    void resumeNetwork();

    /**
     * Cleanup network resources
     */
    void cleanup(boolean resetKeys);

    /**
     * Set user ID for this account
     */
    void setUserId(long userId);

    /**
     * Register for connection state changes
     */
    void addConnectionStateListener(ConnectionStateListener listener);

    /**
     * Unregister connection state listener
     */
    void removeConnectionStateListener(ConnectionStateListener listener);

    /**
     * Get time difference (local vs server)
     */
    int getTimeDifference();

    /**
     * Get current datacenter ID
     */
    int getCurrentDatacenterId();

    /**
     * Connection state listener
     */
    interface ConnectionStateListener {
        void onConnectionStateChanged(int state);
    }

    /**
     * Network configuration
     */
    class NetworkConfig {
        public String baseUrl;           // Custom backend base URL
        public String apiKey;            // API key for auth
        public String deviceModel;
        public String systemVersion;
        public String appVersion;
        public String langCode;
        public String systemLangCode;
        public String configPath;
        public String logPath;
        public String pushToken;
        public String fingerprint;
        public int timezoneOffset;
        public long userId;
        public boolean userPremium;
        public boolean enablePushConnection;
        public boolean hasNetwork;
        public int networkType;
        public int performanceClass;
        public int buildVersion;
        public int layer;                // Protocol version (for compatibility)
        public int apiId;                // App ID (for compatibility)

        public NetworkConfig() {}
    }

    /**
     * Request builder for custom backend
     */
    class RequestBuilder {
        private final Object request;
        private RequestDelegate callback;
        private int flags = 0;
        private int datacenterId = DEFAULT_DATACENTER_ID;
        private int connectionType = ConnectionTypeGeneric;
        private Executor executor;

        public RequestBuilder(Object request) {
            this.request = request;
        }

        public RequestBuilder callback(RequestDelegate callback) {
            this.callback = callback;
            return this;
        }

        public RequestBuilder flags(int flags) {
            this.flags = flags;
            return this;
        }

        public RequestBuilder datacenterId(int datacenterId) {
            this.datacenterId = datacenterId;
            return this;
        }

        public RequestBuilder connectionType(int connectionType) {
            this.connectionType = connectionType;
            return this;
        }

        public RequestBuilder executor(Executor executor) {
            this.executor = executor;
            return this;
        }

        public int execute(CustomNetworkInterface network) {
            if (executor != null && callback != null) {
                RequestDelegate wrapped = (response, error) -> executor.execute(() -> callback.run(response, (TLRPC.TL_error) error));
                return network.sendRequest(request, wrapped, flags, datacenterId, connectionType);
            }
            return network.sendRequest(request, callback, flags, datacenterId, connectionType);
        }
    }

    // 🛠️ Static helpers for compatibility

    /**
     * Create request builder (fluent API)
     */
    static RequestBuilder newRequest(Object request) {
        return new RequestBuilder(request);
    }

    /**
     * Post to UI thread (compatibility with ConnectionsManager patterns)
     */
    static void runOnUIThread(Runnable runnable) {
        new Handler(Looper.getMainLooper()).post(runnable);
    }

    /**
     * Post to background thread
     */
    static void runOnBackgroundThread(Runnable runnable) {
        Utilities.stageQueue.postRunnable(runnable);
    }
}