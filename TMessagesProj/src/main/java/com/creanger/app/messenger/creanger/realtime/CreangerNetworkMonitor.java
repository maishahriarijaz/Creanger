package com.creanger.app.messenger.creanger.realtime;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import androidx.annotation.Nullable;

/**
 * Connectivity watcher for the Creanger layer. Telegram's legacy
 * {@code ApplicationLoader} receiver only feeds the old MTProto stack; the
 * Creanger plane (REST + realtime sockets) had no network awareness at all,
 * so a dead network surfaced as raw errors and a live network was ignored by
 * the realtime backoff (up to 30 s of avoidable wait).
 *
 * <p>The monitor is a thin Android shell around a pure decision core:
 * <ul>
 *   <li>{@link #isNetworkAvailable()} — is there a usable (internet-capable,
 *       validated on M+) network right now;</li>
 *   <li>{@link Listener#onNetworkAvailable()} — fired the moment any network
 *       becomes usable, so a pending realtime reconnect can retry instantly
 *       instead of waiting out the backoff;</li>
 *   <li>{@link #isOfflineNow(Context)} — best-effort snapshot used to turn
 *       raw request failures into a friendly "you are offline" message.</li>
 * </ul>
 *
 * <p>Callbacks are delivered on the framework thread; the
 * {@link MessageRealtimeClient} and {@link DialogListRealtimeWatcher}
 * listeners re-dispatch onto their own executors.
 */
public class CreangerNetworkMonitor {

    /** Fired when a usable network becomes available. */
    public interface Listener {
        void onNetworkAvailable();
    }

    private static final AtomicBoolean sharedShutdown = new AtomicBoolean(false);
    @Nullable
    private static volatile CreangerNetworkMonitor sharedInstance;

    /**
     * App-scoped monitor for components that never outlive the process.
     * Returns {@code null} in JVM unit tests (no Android framework) or after
     * {@link #shutdownShared()} (logout / instrumentation teardown).
     */
    @Nullable
    public static CreangerNetworkMonitor shared() {
        if (sharedShutdown.get()) {
            return null;
        }
        CreangerNetworkMonitor instance = sharedInstance;
        if (instance == null) {
            synchronized (CreangerNetworkMonitor.class) {
                instance = sharedInstance;
                if (instance == null) {
                    instance = create();
                    if (instance == null) {
                        // No Android runtime (JVM test): remember the failure so
                        // repeated calls stay cheap.
                        sharedShutdown.set(true);
                        return null;
                    }
                    sharedInstance = instance;
                }
            }
        }
        return instance;
    }

    /**
     * Drops the app-scoped monitor (listeners are detached). Mainly for
     * tests; safe to call repeatedly.
     */
    public static void shutdownShared() {
        sharedShutdown.set(true);
        CreangerNetworkMonitor instance = sharedInstance;
        sharedInstance = null;
        if (instance != null) {
            instance.unregister();
        }
    }

    @Nullable
    private static CreangerNetworkMonitor create() {
        try {
            Context appContext = com.creanger.app.messenger.ApplicationLoader.applicationContext;
            if (appContext == null) {
                return null;
            }
            ConnectivityManager cm = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            return cm != null ? new CreangerNetworkMonitor(cm) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private final ConnectivityManager connectivityManager;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean registered = new AtomicBoolean(false);
    private volatile boolean lastKnownAvailable = true;

    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@Nullable Network network) {
            lastKnownAvailable = true;
            notifyAvailable();
        }

        @Override
        public void onLost(@Nullable Network network) {
            recomputeFromActiveNetwork();
        }

        @Override
        public void onCapabilitiesChanged(@Nullable Network network, @Nullable NetworkCapabilities capabilities) {
            recomputeFromActiveNetwork();
        }
    };

    public CreangerNetworkMonitor(@Nullable ConnectivityManager connectivityManager) {
        this.connectivityManager = connectivityManager;
    }

    /** For test fakes only — a monitor with no framework behind it. */
    protected CreangerNetworkMonitor() {
        this.connectivityManager = null;
    }

    /** Starts delivering {@code onAvailable} events (idempotent). */
    public void register() {
        if (connectivityManager == null || !registered.compareAndSet(false, true)) {
            return;
        }
        try {
            NetworkRequest request = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();
            connectivityManager.registerNetworkCallback(request, networkCallback);
        } catch (Throwable t) {
            registered.set(false);
        }
    }

    /** Detaches the framework callback and all listeners. */
    public void unregister() {
        listeners.clear();
        if (connectivityManager == null || !registered.compareAndSet(true, false)) {
            return;
        }
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback);
        } catch (Throwable ignore) {
        }
    }

    /**
     * Adds a listener; when registration is live the listener immediately
     * receives an event if the network is currently usable — this is what
     * makes a pending reconnect retry without waiting for a state change.
     *
     * @return false when there is nothing to listen on (no framework).
     */
    public boolean addListener(Listener listener) {
        if (listener == null || connectivityManager == null) {
            return false;
        }
        listeners.add(listener);
        register();
        if (isNetworkAvailable()) {
            try {
                listener.onNetworkAvailable();
            } catch (Throwable ignore) {
            }
        }
        return true;
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /**
     * True when there is currently a usable network. Deliberately optimistic
     * ({@code true}) until the first probe fails so a flaky API does not
     * block sends on a stale snapshot.
     */
    public boolean isNetworkAvailable() {
        if (connectivityManager == null) {
            return true;
        }
        try {
            Network active = connectivityManager.getActiveNetwork();
            if (active == null) {
                lastKnownAvailable = false;
                return false;
            }
            NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(active);
            boolean usable = caps != null && isUsable(
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                    Build.VERSION.SDK_INT);
            lastKnownAvailable = usable;
            return usable;
        } catch (Throwable t) {
            return lastKnownAvailable;
        }
    }

    private void recomputeFromActiveNetwork() {
        boolean usable = isNetworkAvailable();
        if (usable) {
            notifyAvailable();
        }
    }

    private void notifyAvailable() {
        for (Listener listener : listeners) {
            try {
                listener.onNetworkAvailable();
            } catch (Throwable ignore) {
            }
        }
    }

    /**
     * Pure decision core: a network is usable when it advertises internet
     * access and — from Android M on — the OS actually validated it
     * (captive portals report internet but fail validation).
     */
    public static boolean isUsable(boolean hasInternet, boolean validated, int sdkInt) {
        if (!hasInternet) {
            return false;
        }
        return sdkInt < Build.VERSION_CODES.M || validated;
    }

    /**
     * Fastest reconnect wait after a known-good connection age: short drops
     * deserve an instant retry, long ones a small courtesy delay.
     */
    public static long initialBackoffForMs(long connectionAgeMs) {
        if (connectionAgeMs < 5_000L) {
            return 1_000L;
        }
        if (connectionAgeMs < 15_000L) {
            return 2_000L;
        }
        return 5_000L;
    }

    /**
     * Best-effort offline snapshot for error message wording. Conservative
     * (returns false without a framework) so real errors keep their detail.
     */
    public static boolean isOfflineNow(@Nullable Context context) {
        try {
            if (context == null) {
                return false;
            }
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return false;
            }
            Network active = cm.getActiveNetwork();
            if (active == null) {
                return true;
            }
            NetworkCapabilities caps = cm.getNetworkCapabilities(active);
            return caps == null || !isUsable(
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                    Build.VERSION.SDK_INT);
        } catch (Throwable t) {
            return false;
        }
    }
}
