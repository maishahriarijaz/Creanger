package com.creanger.app.tgnet;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Base64;

import androidx.annotation.Keep;

import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.gms.tasks.Task;
import com.google.android.play.core.integrity.IntegrityManager;
import com.google.android.play.core.integrity.IntegrityManagerFactory;
import com.google.android.play.core.integrity.IntegrityTokenRequest;
import com.google.android.play.core.integrity.IntegrityTokenResponse;
import com.google.firebase.remoteconfig.FirebaseRemoteConfig;

import org.json.JSONArray;
import org.json.JSONObject;
import com.creanger.app.messenger.AccountInstance;
import com.creanger.app.messenger.AndroidUtilities;
import com.creanger.app.messenger.ApplicationLoader;
import com.creanger.app.messenger.BaseController;
import com.creanger.app.messenger.BuildVars;
import com.creanger.app.messenger.CaptchaController;
import com.creanger.app.messenger.EmuDetector;
import com.creanger.app.messenger.FileLoadOperation;
import com.creanger.app.messenger.FileLoader;
import com.creanger.app.messenger.FileLog;
import com.creanger.app.messenger.FileUploadOperation;
import com.creanger.app.messenger.KeepAliveJob;
import com.creanger.app.messenger.LocaleController;
import com.creanger.app.messenger.MessagesController;
import com.creanger.app.messenger.NotificationCenter;
import com.creanger.app.messenger.PushListenerController;
import com.creanger.app.messenger.SharedConfig;
import com.creanger.app.messenger.StatsController;
import com.creanger.app.messenger.UserConfig;
import com.creanger.app.messenger.Utilities;
import com.creanger.app.ui.Components.VideoPlayer;
import com.creanger.app.ui.LoginActivity;

import com.creanger.app.network.CustomNetworkInterface;
import com.creanger.app.network.CustomConnectionsManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.TimeZone;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLException;

/**
 * 🔌 ConnectionsManager - DELEGATE to CustomNetworkInterface
 *
 * This class now acts as a compatibility wrapper.
 * All MTProto/Telegram server logic is REMOVED.
 * Network operations delegate to CustomConnectionsManager (stub).
 * UI and local storage (MessagesStorage) remain 100% intact.
 *
 * 🎯 REPLACE CustomConnectionsManager with your REST/WebSocket/gRPC implementation.
 */
@Keep
public class ConnectionsManager extends BaseController {

    // 📦 Constants (unchanged for API compatibility)
    public final static int ConnectionTypeGeneric = 1;
    public final static int ConnectionTypeDownload = 2;
    public final static int ConnectionTypeUpload = 4;
    public final static int ConnectionTypePush = 8;
    public final static int ConnectionTypeDownload2 = ConnectionTypeDownload | (1 << 16);

    public final static int FileTypePhoto = 0x01000000;
    public final static int FileTypeVideo = 0x02000000;
    public final static int FileTypeAudio = 0x03000000;
    public final static int FileTypeFile = 0x04000000;

    public final static int RequestFlagEnableUnauthorized = 1;
    public final static int RequestFlagFailOnServerErrors = 2;
    public final static int RequestFlagCanCompress = 4;
    public final static int RequestFlagWithoutLogin = 8;
    public final static int RequestFlagTryDifferentDc = 16;
    public final static int RequestFlagForceDownload = 32;
    public final static int RequestFlagInvokeAfter = 64;
    public final static int RequestFlagNeedQuickAck = 128;
    public final static int RequestFlagDoNotWaitFloodWait = 1024;
    public final static int RequestFlagListenAfterCancel = 2048;
    public final static int RequestFlagFailOnServerErrorsExceptFloodWait = 65536;

    public final static int ConnectionStateConnecting = 1;
    public final static int ConnectionStateWaitingForNetwork = 2;
    public final static int ConnectionStateConnected = 3;
    public final static int ConnectionStateConnectingToProxy = 4;
    public final static int ConnectionStateUpdating = 5;

    public final static byte USE_IPV4_ONLY = 0;
    public final static byte USE_IPV6_ONLY = 1;
    public final static byte USE_IPV4_IPV6_RANDOM = 2;

    // 🔧 Compatibility fields (accessed by external code)
    public static long lastPremiumFloodWaitShown = 0;
    public static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();

    public final static int DEFAULT_DATACENTER_ID = Integer.MAX_VALUE;

    // 🏗️ Instance management
    private static final ConnectionsManager[] Instance = new ConnectionsManager[UserConfig.MAX_ACCOUNT_COUNT];
    private static int lastClassGuid = 1;

    public static ConnectionsManager getInstance(int num) {
        ConnectionsManager localInstance = Instance[num];
        if (localInstance == null) {
            synchronized (ConnectionsManager.class) {
                localInstance = Instance[num];
                if (localInstance == null) {
                    Instance[num] = localInstance = new ConnectionsManager(num);
                }
            }
        }
        return localInstance;
    }

    // 🔧 Per-instance state
    private final int currentAccount;
    private int connectionState = ConnectionStateConnected; // Default to connected for offline-first
    private boolean isUpdating = false;
    private final AtomicInteger lastRequestToken = new AtomicInteger(1);
    private long lastPauseTime = System.currentTimeMillis();
    private boolean appPaused = true;
    private int appResumeCount = 0;
    private boolean forceTryIpV6 = false;
    private boolean pushConnectionEnabled = true;

    // 🎭 Delegate to custom network interface
    private final CustomNetworkInterface customNetwork;

    private ConnectionsManager(int instance) {
        super(instance);
        this.currentAccount = instance;
        this.customNetwork = CustomConnectionsManager.getInstance(instance);
        
        // Initialize custom network with basic config
        CustomNetworkInterface.NetworkConfig config = new CustomNetworkInterface.NetworkConfig();
        config.baseUrl = ""; // To be set by app
        config.apiKey = "";
        config.deviceModel = Build.MANUFACTURER + " " + Build.MODEL;
        config.systemVersion = "SDK " + Build.VERSION.SDK_INT;
        config.appVersion = getAppVersion();
        config.langCode = LocaleController.getLocaleStringIso639().toLowerCase();
        config.systemLangCode = LocaleController.getSystemLocaleStringIso639().toLowerCase();
        config.configPath = ApplicationLoader.getFilesDirFixed().getAbsolutePath();
        config.logPath = FileLog.getNetworkLogPath();
        config.pushToken = SharedConfig.pushString;
        config.fingerprint = AndroidUtilities.getCertificateSHA256Fingerprint();
        config.timezoneOffset = (TimeZone.getDefault().getRawOffset() + TimeZone.getDefault().getDSTSavings()) / 1000;
        config.userId = UserConfig.getInstance(instance).getClientUserId();
        config.userPremium = getUserConfig().getCurrentUser() != null && getUserConfig().getCurrentUser().premium;
        config.enablePushConnection = isPushConnectionEnabled();
        config.hasNetwork = ApplicationLoader.isNetworkOnline();
        config.networkType = ApplicationLoader.getCurrentNetworkType();
        config.performanceClass = SharedConfig.measureDevicePerformanceClass();
        config.buildVersion = SharedConfig.buildVersion();
        config.layer = 0; // No MTProto layer
        config.apiId = BuildVars.APP_ID;
        
        customNetwork.init(instance, config);
        customNetwork.addConnectionStateListener(state -> {
            connectionState = state;
            AndroidUtilities.runOnUIThread(() -> 
                AccountInstance.getInstance(currentAccount).getNotificationCenter().postNotificationName(NotificationCenter.didUpdateConnectionState)
            );
        });
    }

    private String getAppVersion() {
        try {
            PackageInfo pInfo = ApplicationLoader.applicationContext.getPackageManager()
                .getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0);
            String version = pInfo.versionName + " (" + pInfo.versionCode + ")";
            if (BuildVars.DEBUG_PRIVATE_VERSION) version += " pbeta";
            else if (BuildVars.DEBUG_VERSION) version += " beta";
            return version;
        } catch (Exception e) {
            return "Unknown";
        }
    }

    // ════════════════════════════════════════════════════════
    // 🔌 PUBLIC API - Delegates to CustomNetworkInterface

    public int getCurrentTime() {
        return customNetwork.getCurrentTime();
    }

    public long getCurrentTimeMillis() {
        return customNetwork.getCurrentTimeMillis();
    }

    public int sendRequest(Object object, RequestDelegate completionBlock) {
        return sendRequest(object, completionBlock, 0);
    }

    public int sendRequest(Object object, RequestDelegate completionBlock, int flags) {
        return sendRequest(object, completionBlock, flags, DEFAULT_DATACENTER_ID, ConnectionTypeGeneric);
    }

    public int sendRequest(Object object, RequestDelegate completionBlock, int flags, int connectionType) {
        return sendRequest(object, completionBlock, flags, DEFAULT_DATACENTER_ID, connectionType);
    }

    public int sendRequest(Object object, RequestDelegate completionBlock, int flags, int datacenterId, int connectionType) {
        return customNetwork.sendRequest(object, completionBlock, flags, datacenterId, connectionType);
    }

    public int sendRequest(Object object, RequestDelegate completionBlock, Object quickAckBlock, int flags) {
        return sendRequest(object, completionBlock, flags, DEFAULT_DATACENTER_ID, ConnectionTypeGeneric);
    }

    public int sendRequest(Object object, RequestDelegate completionBlock, Object quickAckBlock, int flags, int datacenterId, int connectionType) {
        return customNetwork.sendRequest(object, completionBlock, flags, datacenterId, connectionType);
    }

    public int sendRequest(Object object, RequestDelegate completionBlock, Object quickAckBlock, int flags, int datacenterId, int connectionType, boolean immediate) {
        return customNetwork.sendRequest(object, completionBlock, flags, datacenterId, connectionType);
    }

    public int sendRequest(final Object object, final RequestDelegate onComplete, final Object onQuickAck, final Object onWriteToSocket, final int flags, final int datacenterId, final int connectionType, final boolean immediate) {
        return customNetwork.sendRequest(object, onComplete, flags, datacenterId, connectionType);
    }

    public int sendRequestSync(final Object object, final RequestDelegate onComplete, final Object onQuickAck, final Object onWriteToSocket, final int flags, final int datacenterId, final int connectionType, final boolean immediate) {
        return customNetwork.sendRequest(object, onComplete, flags, datacenterId, connectionType);
    }

    public void cancelRequest(int token, boolean notifyServer) {
        cancelRequest(token, notifyServer, null);
    }

    public void cancelRequest(int token, boolean notifyServer, Runnable onCancelled) {
        customNetwork.cancelRequest(token, notifyServer, onCancelled);
    }

    public void cleanup(boolean resetKeys) {
        customNetwork.cleanup(resetKeys);
    }

    public void cancelRequestsForGuid(int guid) {
        customNetwork.cancelRequestsForGuid(guid);
    }

    public void bindRequestToGuid(int requestToken, int guid) {
        if (guid != 0) {
            customNetwork.bindRequestToGuid(requestToken, guid);
        }
    }

    public int getConnectionState() {
        int state = customNetwork.getConnectionState();
        if (state == ConnectionStateConnected && isUpdating) {
            return ConnectionStateUpdating;
        }
        return state;
    }

    public void setUserId(long id) {
        customNetwork.setUserId(id);
    }

    public void setForceTryIpV6(boolean forceTryIpV6) {
        this.forceTryIpV6 = forceTryIpV6;
    }

    public int getTimeDifference() {
        return customNetwork.getTimeDifference();
    }

    public int getCurrentDatacenterId() {
        return customNetwork.getCurrentDatacenterId();
    }

    public long getCurrentAuthKeyId() {
        return 0; // No MTProto auth keys
    }

    public int getCurrentPingTime() {
        return 0; // No MTProto ping
    }

    public static void setLangCode(String langCode) {
        // Compatibility stub
    }

    public static void setSystemLangCode(String langCode) {
        // Compatibility stub
    }

    public static void setRegId(String token, int type, String status) {
        // Compatibility stub
    }

    public static void setProxySettings(boolean enabled, String address, int port, String user, String password, String secret) {
        // Compatibility stub
    }

    public void checkProxy(String address, int port, String user, String password, String secret, Utilities.Callback<Long> callback) {
        if (callback != null) {
            callback.run(0L);
        }
    }

    public void resumeNetworkMaybe() {
        customNetwork.resumeNetwork();
    }

    public boolean isPushConnectionEnabled() {
        return pushConnectionEnabled;
    }

    public void setPushConnectionEnabled(boolean enabled) {
        this.pushConnectionEnabled = enabled;
    }

    public void setIsUpdating(boolean updating) {
        this.isUpdating = updating;
    }

    public long getPauseTime() {
        return lastPauseTime;
    }

    public void updateDcSettings() {
        // Stub
    }

    public void failNotRunningRequest(int token) {
        // Stub
    }

    public void discardConnection(int dcId, int type) {
        // Stub
    }

    public void setAppPaused(boolean paused, boolean byScreen) {
        appPaused = paused;
        if (paused) {
            customNetwork.pauseNetwork();
        } else {
            customNetwork.resumeNetwork();
        }
    }

    public void switchBackend(boolean test) {
        // Stub
    }

    public void setDefaultDatacenterId(int id) {
        // Stub
    }

    public void checkConnection() {
        // Stub
    }

    public void applyDatacenterAddress(int dc, String ip, int port) {
    }

    // ═══════════════════════════════════════════════════════
    // 🔌 EXTENDED PUBLIC API - Additional delegates for compatibility

    public int sendRequestTyped(Object method, Utilities.Callback2<Object, Object> completionBlock) {
        return sendRequestTyped(method, null, completionBlock);
    }

    public int sendRequestTyped(Object method, Executor executor, Utilities.Callback2<Object, Object> completionBlock) {
        return sendRequestTyped(method, executor, completionBlock, DEFAULT_DATACENTER_ID, 0);
    }

    public int sendRequestTyped(Object method, Executor executor, Utilities.Callback2<Object, Object> completionBlock, int requestFlags) {
        return sendRequestTyped(method, executor, completionBlock, DEFAULT_DATACENTER_ID, requestFlags);
    }

    public int sendRequestTyped(Object method, Executor executor, Utilities.Callback2<Object, Object> completionBlock, int dcId, int requestFlags) {
        return sendRequest(method, (res, err) -> {
            if (executor != null) {
                executor.execute(() -> completionBlock.run(res, err));
            } else {
                completionBlock.run(res, err);
            }
        }, requestFlags, dcId, ConnectionTypeGeneric, true);
    }

    public int sendRequestTypedAndProcessUpdates(Object method, Executor executor, Utilities.Callback2<Object, Object> completionBlock) {
        return sendRequestTypedAndProcessUpdates(method, executor, completionBlock, DEFAULT_DATACENTER_ID, 0);
    }

    public int sendRequestTypedAndProcessUpdates(Object method, Executor executor, Utilities.Callback2<Object, Object> completionBlock, int dcId, int requestFlags) {
        return sendRequestTyped(method, null, (result, err) -> {
            if (result != null) {
                getMessagesController().processUpdates(result, false);
            }
            if (executor != null) {
                executor.execute(() -> completionBlock.run(result, err));
            } else {
                completionBlock.run(result, err);
            }
        }, dcId, requestFlags);
    }

    public static int generateClassGuid() {
        return lastClassGuid++;
    }

    public boolean isTestBackend() {
        return false;
    }

    public static int getInitFlags() {
        int flags = 0;
        EmuDetector detector = EmuDetector.with(ApplicationLoader.applicationContext);
        if (detector.detect()) {
            if (BuildVars.LOGS_ENABLED) FileLog.d("detected emu");
            flags |= 1024;
        }
        return flags;
    }

    // ═══════════════════════════════════════════════════════
    // 🧱 NATIVE METHODS - Required for JNI Registration

    @Keep
    public static native long native_getCurrentTimeMillis(int instance);
    @Keep
    public static native int native_getCurrentTime(int instance);
    @Keep
    public static native int native_getCurrentPingTime(int instance);
    @Keep
    public static native int native_getCurrentDatacenterId(int instance);
    @Keep
    public static native long native_getCurrentAuthKeyId(int instance);
    @Keep
    public static native int native_isTestBackend(int instance);
    @Keep
    public static native int native_getTimeDifference(int instance);
    @Keep
    public static native void native_sendRequest(int instance, long object, int flags, int datacenterId, int connectionType, boolean immediate, int token);
    @Keep
    public static native void native_cancelRequest(int instance, int token, boolean notifyServer);
    @Keep
    public static native void native_cleanUp(int instance, boolean resetKeys);
    @Keep
    public static native void native_cancelRequestsForGuid(int instance, int guid);
    @Keep
    public static native void native_bindRequestToGuid(int instance, int requestToken, int guid);
    @Keep
    public static native void native_applyDatacenterAddress(int instance, int datacenterId, String ipAddress, int port);
    @Keep
    public static native void native_setProxySettings(int instance, String address, int port, String username, String password, String secret);
    @Keep
    public static native int native_getConnectionState(int instance);
    @Keep
    public static native void native_setUserId(int instance, long id);
    @Keep
    public static native void native_init(int instance, int version, int layer, int apiId, String deviceModel, String systemVersion, String appVersion, String langCode, String systemLangCode, String configPath, String logPath, String regId, String cFingerprint, String installerId, String packageId, int timezoneOffset, long userId, boolean userPremium, boolean enablePushConnection, boolean hasNetwork, int networkType, int performanceClass);
    @Keep
    public static native void native_setLangCode(int instance, String langCode);
    @Keep
    public static native void native_setRegId(int instance, String regId);
    @Keep
    public static native void native_setSystemLangCode(int instance, String langCode);
    @Keep
    public static native void native_switchBackend(int instance, boolean restart);
    @Keep
    public static native void native_pauseNetwork(int instance);
    @Keep
    public static native void native_resumeNetwork(int instance, boolean partial);
    @Keep
    public static native void native_updateDcSettings(int instance);
    @Keep
    public static native void native_moveDatacenter(int instance, int datacenterId);
    @Keep
    public static native void native_setIpStrategy(int instance, byte value);
    @Keep
    public static native void native_setNetworkAvailable(int instance, boolean value, int networkType, boolean slow);
    @Keep
    public static native void native_setPushConnectionEnabled(int instance, boolean value);
    @Keep
    public static native void native_setJava(boolean useJavaByteBuffers);
    @Keep
    public static native void native_applyDnsConfig(int instance, long address, String phone, int date);
    @Keep
    public static native long native_checkProxy(int instance, String address, int port, String username, String password, String secret, Object requestTimeFunc);
    @Keep
    public static native void native_onHostNameResolved(String host, long address, String ip);
    @Keep
    public static native void native_discardConnection(int instance, int datacenterId, int connectionType);
    @Keep
    public static native void native_failNotRunningRequest(int instance, int token);
    @Keep
    public static native void native_receivedIntegrityCheckClassic(int instance, int requestToken, String nonce, String token);
    @Keep
    public static native void native_receivedCaptchaResult(int instance, int[] requestTokens, String token);
    @Keep
    public static native boolean native_isGoodPrime(byte[] prime, int g);

    // ═══════════════════════════════════════════════════════
    // 🎭 CALLBACKS FROM NATIVE - Required for JNI Registration

    @Keep
    public static void onRequestClear(int token, int instanceNum, boolean notifyServer) {
    }

    @Keep
    public static void onRequestComplete(int token, int instanceNum, long responseAddress, int errorType, String errorText, int datacenterId, long requestStartTime, long responseStartTime, int networkType) {
    }

    @Keep
    public static void onRequestWriteToSocket(int token, int instanceNum) {
    }

    @Keep
    public static void onRequestQuickAck(int token, int instanceNum) {
    }

    @Keep
    public static void onUnparsedMessageReceived(long address, int instanceNum, long messageId) {
    }

    @Keep
    public static void onUpdate(int instanceNum) {
    }

    @Keep
    public static void onSessionCreated(int instanceNum) {
    }

    @Keep
    public static void onLogout(int instanceNum) {
    }

    @Keep
    public static void onConnectionStateChanged(int state, int instanceNum) {
    }

    @Keep
    public static void onInternalPushReceived(int account) {
    }

    @Keep
    public static void onUpdateConfig(long address, int instanceNum) {
    }

    @Keep
    public static void onBytesSent(int amount, int networkType, int instanceNum) {
    }

    @Keep
    public static void onBytesReceived(int amount, int networkType, int instanceNum) {
    }

    @Keep
    public static void onRequestNewServerIpAndPort(int datacenterId, int instanceNum) {
    }

    @Keep
    public static void onProxyError() {
    }

    @Keep
    public static void getHostByName(String host, long address) {
    }

    @Keep
    public static void onPremiumFloodWait(int token, int instanceNum, boolean notifyServer) {
    }

    @Keep
    public static void onIntegrityCheckClassic(int token, int instanceNum, String nonce, String integrityToken) {
    }

    @Keep
    public static void onCaptchaCheck(int token, int instanceNum, String url, String captchaSum) {
    }
}