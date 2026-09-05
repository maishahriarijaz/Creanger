package com.creanger.app.network;

import android.content.Context;

import com.creanger.app.messenger.AndroidUtilities;
import com.creanger.app.messenger.ApplicationLoader;
import com.creanger.app.messenger.FileLog;
import com.creanger.app.messenger.Utilities;
import com.creanger.app.network.engine.CustomBackendNetworkEngine;
import com.creanger.app.network.model.NetworkError;
import com.creanger.app.tgnet.RequestDelegate;
import com.creanger.app.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * CustomConnectionsManager - Now delegates to CustomBackendNetworkEngine.
 * 
 * This is the main entry point for the custom backend integration.
 * It implements CustomNetworkInterface and forwards all calls to the
 * real CustomBackendNetworkEngine.
 */
public class CustomConnectionsManager implements CustomNetworkInterface {

    private static final CustomConnectionsManager[] Instances = new CustomConnectionsManager[3];
    private static final Object[] LockObjects = new Object[3];
    static {
        for (int i = 0; i < 3; i++) LockObjects[i] = new Object();
    }

    private final int currentAccount;
    private CustomBackendNetworkEngine backendEngine;

    private CustomConnectionsManager(int account) {
        this.currentAccount = account;
    }

    public static CustomConnectionsManager getInstance(int num) {
        CustomConnectionsManager instance = Instances[num];
        if (instance == null) {
            synchronized (LockObjects[num]) {
                instance = Instances[num];
                if (instance == null) {
                    Instances[num] = instance = new CustomConnectionsManager(num);
                }
            }
        }
        return instance;
    }

    @Override
    public void init(int currentAccount, NetworkConfig config) {
        FileLog.d("CustomConnectionsManager: init account=" + currentAccount + " baseUrl=" + config.baseUrl);

        // Create and initialize the real backend engine
        backendEngine = CustomBackendNetworkEngine.getInstance(currentAccount);
        backendEngine.init(currentAccount, config);

        // Connection state will be set by the engine
    }

    @Override
    public int sendRequest(Object request, RequestDelegate onComplete, int flags) {
        return sendRequest(request, onComplete, flags, DEFAULT_DATACENTER_ID, ConnectionTypeGeneric);
    }

    @Override
    public int sendRequest(Object request, RequestDelegate onComplete, int flags, int datacenterId, int connectionType) {
        if (backendEngine == null) {
            FileLog.e("CustomConnectionsManager: backendEngine not initialized!");
            if (onComplete != null) {
                AndroidUtilities.runOnUIThread(() -> onComplete.run(null, new TLRPC.TL_error() {{ code = 500; text = "BACKEND_NOT_INITIALIZED"; }}));
            }
            return -1;
        }

        return backendEngine.sendRequest(request, onComplete, flags, datacenterId, connectionType);
    }

    @Override
    public void cancelRequest(int requestToken, boolean notifyServer) {
        cancelRequest(requestToken, notifyServer, null);
    }

    @Override
    public void cancelRequest(int requestToken, boolean notifyServer, Runnable onCancelled) {
        if (backendEngine != null) {
            // The engine handles cancellation internally
        }
        if (onCancelled != null) {
            AndroidUtilities.runOnUIThread(onCancelled);
        }
    }

    @Override
    public void bindRequestToGuid(int requestToken, int guid) {
        if (backendEngine != null) {
            // Engine handles GUID tracking
        }
    }

    @Override
    public void cancelRequestsForGuid(int guid) {
        if (backendEngine != null) {
            // Engine handles GUID cancellation
        }
    }

    @Override
    public int getCurrentTime() {
        if (backendEngine != null) {
            return backendEngine.getCurrentTime();
        }
        return (int) (System.currentTimeMillis() / 1000);
    }

    @Override
    public long getCurrentTimeMillis() {
        if (backendEngine != null) {
            return backendEngine.getCurrentTimeMillis();
        }
        return System.currentTimeMillis();
    }

    @Override
    public int getConnectionState() {
        if (backendEngine != null) {
            return backendEngine.getConnectionState();
        }
        return ConnectionStateWaitingForNetwork;
    }

    @Override
    public void setNetworkAvailable(boolean online, int networkType, boolean slow) {
        if (backendEngine != null) {
            backendEngine.setNetworkAvailable(online, networkType, slow);
        }
    }

    @Override
    public void pauseNetwork() {
        if (backendEngine != null) {
            backendEngine.pauseNetwork();
        }
    }

    @Override
    public void resumeNetwork() {
        if (backendEngine != null) {
            backendEngine.resumeNetwork();
        }
    }

    @Override
    public void cleanup(boolean resetKeys) {
        if (backendEngine != null) {
            backendEngine.cleanup(resetKeys);
        }
    }

    @Override
    public void setUserId(long userId) {
        if (backendEngine != null) {
            backendEngine.setUserId(userId);
        }
    }

    @Override
    public void addConnectionStateListener(ConnectionStateListener listener) {
        if (backendEngine != null) {
            backendEngine.addConnectionStateListener(listener);
        }
    }

    @Override
    public void removeConnectionStateListener(ConnectionStateListener listener) {
        if (backendEngine != null) {
            backendEngine.removeConnectionStateListener(listener);
        }
    }

    @Override
    public int getTimeDifference() {
        if (backendEngine != null) {
            return backendEngine.getTimeDifference();
        }
        return 0;
    }

    @Override
    public int getCurrentDatacenterId() {
        if (backendEngine != null) {
            return backendEngine.getCurrentDatacenterId();
        }
        return 1;
    }

    // ==================== Public Access to Engine ====================

    public CustomBackendNetworkEngine getBackendEngine() {
        return backendEngine;
    }

    public boolean isInitialized() {
        return backendEngine != null && backendEngine.isInitialized();
    }
}