package com.creanger.app.messenger.creanger.auth;

import android.os.Handler;
import android.os.Looper;

import com.creanger.app.messenger.creanger.api.CreangerApiException;
import com.creanger.app.messenger.creanger.model.AuthModels.AuthSession;
import com.creanger.app.messenger.creanger.model.AuthModels.CreangerUser;
import com.creanger.app.messenger.creanger.model.AuthModels.ForgotPasswordResult;
import com.creanger.app.messenger.creanger.model.AuthModels.GoogleAuthResult;
import com.creanger.app.messenger.creanger.model.AuthModels.SignupResult;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Android-facing wrapper around {@link CreangerAuthEngine} (Supabase Auth).
 * Runs the blocking engine on a background executor and returns every callback
 * on the main thread, so the auth screens can drive it without threading
 * boilerplate.
 */
public final class CreangerAuthAsync {

    public interface AuthCallback<T> {
        void onSuccess(T result);

        void onError(@Nullable CreangerApiException error, @Nullable Throwable ioError);
    }

    private final CreangerAuthEngine engine;
    private final ExecutorService executor;
    private final Handler mainHandler;

    public CreangerAuthAsync(CreangerAuthEngine engine) {
        this(engine, Executors.newSingleThreadExecutor(), new Handler(Looper.getMainLooper()));
    }

    CreangerAuthAsync(CreangerAuthEngine engine, ExecutorService executor, Handler mainHandler) {
        this.engine = engine;
        this.executor = executor;
        this.mainHandler = mainHandler;
    }

    public AuthState getState() {
        return engine.getState();
    }

    // ---- registration (email + username + password -> email verification) ----

    public void registerWithPassword(@NonNull String email, @NonNull String username,
                                     @NonNull String password,
                                     @NonNull AuthCallback<SignupResult> cb) {
        run(() -> engine.registerWithPassword(email, username, password), cb);
    }

    public void verifyRegister(@NonNull String email, @NonNull String otp,
                               @NonNull AuthCallback<AuthSession> cb) {
        run(() -> engine.verifyRegister(email, otp), cb);
    }

    // ---- login (username or email + password) ----

    public void loginWithPassword(@NonNull String identifier, @NonNull String password,
                                  @NonNull AuthCallback<AuthSession> cb) {
        run(() -> engine.loginWithPassword(identifier, password), cb);
    }

    // ---- Google ----

    public void googleAuth(@NonNull String googleIdToken,
                           @NonNull AuthCallback<GoogleAuthResult> cb) {
        run(() -> engine.googleAuth(googleIdToken), cb);
    }

    /** Completes a new-Google-account setup on the already-active session. */
    public void googleAuthComplete(@NonNull String username, @Nullable String displayName,
                                   @Nullable String password,
                                   @NonNull AuthCallback<AuthSession> cb) {
        run(() -> engine.googleAuthComplete(username, displayName, password), cb);
    }

    // ---- forgot / reset password ----

    public void forgotPassword(@NonNull String email, @NonNull AuthCallback<ForgotPasswordResult> cb) {
        run(() -> engine.forgotPassword(email), cb);
    }

    public void resetPassword(@NonNull String email, @NonNull String otp,
                              @NonNull String newPassword, @NonNull AuthCallback<Void> cb) {
        run(() -> {
            engine.resetPassword(email, otp, newPassword);
            return null;
        }, cb);
    }

    // ---- session ----

    public void refresh(@NonNull AuthCallback<AuthSession> cb) {
        run(() -> engine.refreshSession(), cb);
    }

    public void fetchMe(@NonNull AuthCallback<CreangerUser> cb) {
        run(() -> engine.fetchMe(), cb);
    }

    public void loadCurrentUser(@NonNull AuthCallback<CreangerUser> cb) {
        run(() -> {
            CreangerUser cached = engine.currentUser();
            if (cached != null) {
                return cached;
            }
            return engine.fetchMe();
        }, cb);
    }

    public void getAccessToken(@NonNull AuthCallback<String> cb) {
        run(() -> engine.requireAccessToken(), cb);
    }

    public void logout(@NonNull AuthCallback<Void> cb) {
        run(() -> {
            engine.logout();
            return null;
        }, cb);
    }

    public void clearLocalSession() {
        engine.clearLocalSession();
    }

    // ---- username availability ----

    public void checkUsername(@NonNull String normalizedUsername, @NonNull AuthCallback<Boolean> cb) {
        run(() -> engine.checkUsername(normalizedUsername), cb);
    }

    // ---- resend signup OTP ----

    public void resendOtp(@NonNull String email, @NonNull AuthCallback<Void> cb) {
        run(() -> {
            engine.resendOtp(email);
            return null;
        }, cb);
    }

    @Nullable
    public CreangerUser currentUser() {
        return engine.currentUser();
    }

    private <T> void run(final ThrowingSupplier<T> supplier, final AuthCallback<T> cb) {
        executor.execute(() -> {
            T result;
            CreangerApiException apiError = null;
            Throwable ioError = null;
            try {
                result = supplier.get();
            } catch (CreangerApiException e) {
                result = null;
                apiError = e;
            } catch (Throwable t) {
                result = null;
                ioError = t;
            }
            final T fResult = result;
            final CreangerApiException fApiError = apiError;
            final Throwable fIoError = ioError;
            mainHandler.post(() -> {
                if (fApiError == null && fIoError == null) {
                    cb.onSuccess(fResult);
                } else {
                    cb.onError(fApiError, fIoError);
                }
            });
        });
    }

    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
