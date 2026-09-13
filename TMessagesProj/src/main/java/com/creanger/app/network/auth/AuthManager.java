package com.creanger.app.network.auth;

import android.content.Context;

import com.creanger.app.messenger.FileLog;
import com.creanger.app.messenger.Utilities;
import com.creanger.app.network.engine.CustomHttpClient;
import com.creanger.app.network.model.AuthResult;
import com.creanger.app.network.model.NetworkError;
import com.creanger.app.network.model.UserModel;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicReference;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Authentication manager for custom backend.
 * Handles:
 * - Login (username/password, phone, email)
 * - Registration
 * - Token refresh
 * - Logout
 * - Session restoration
 */
public class AuthManager {

    public interface AuthCallback {
        void onSuccess(AuthResult result);
        void onError(NetworkError error);
    }

    public interface LogoutCallback {
        void onSuccess();
        void onError(NetworkError error);
    }

    public interface UserProfileCallback {
        void onSuccess(UserModel user);
        void onError(NetworkError error);
    }

    private final CustomHttpClient httpClient;
    private final SecureTokenStore tokenStore;
    private final Context context;

    // Auth state
    private final AtomicReference<AuthCallback> pendingRefreshCallback = new AtomicReference<>();

    public AuthManager(Context context, CustomHttpClient httpClient) {
        this.context = context;
        this.httpClient = httpClient;
        this.tokenStore = SecureTokenStore.getInstance();
        this.tokenStore.migrateFromLegacyPrefs(context);
    }

    // ==================== Login Methods ====================

    public void loginWithPassword(String username, String password, AuthCallback callback) {
        Map<String, String> body = new HashMap<>();
        body.put("username", username);
        body.put("password", password);
        body.put("grant_type", "password");

        httpClient.post("/api/v1/auth/login", body, JSONObject.class, new CustomHttpClient.RequestCallback<JSONObject>() {
            @Override
            public void onSuccess(JSONObject response) {
                handleAuthResponse(response, callback);
            }

            @Override
            public void onError(NetworkError error) {
                FileLog.e("Login failed: " + error);
                Utilities.stageQueue.postRunnable(() -> callback.onError(error));
            }
        });
    }

    public void loginWithPhone(String phone, String code, AuthCallback callback) {
        Map<String, String> body = new HashMap<>();
        body.put("phone", phone);
        body.put("code", code);
        body.put("grant_type", "phone");

        httpClient.post("/api/v1/auth/login", body, JSONObject.class, new CustomHttpClient.RequestCallback<JSONObject>() {
            @Override
            public void onSuccess(JSONObject response) {
                handleAuthResponse(response, callback);
            }

            @Override
            public void onError(NetworkError error) {
                Utilities.stageQueue.postRunnable(() -> callback.onError(error));
            }
        });
    }

    public void loginWithEmail(String email, String password, AuthCallback callback) {
        Map<String, String> body = new HashMap<>();
        body.put("email", email);
        body.put("password", password);
        body.put("grant_type", "email");

        httpClient.post("/api/v1/auth/login", body, JSONObject.class, new CustomHttpClient.RequestCallback<JSONObject>() {
            @Override
            public void onSuccess(JSONObject response) {
                handleAuthResponse(response, callback);
            }

            @Override
            public void onError(NetworkError error) {
                Utilities.stageQueue.postRunnable(() -> callback.onError(error));
            }
        });
    }

    // ==================== Registration ====================

    public void register(String username, String email, String phone, String password, AuthCallback callback) {
        Map<String, String> body = new HashMap<>();
        body.put("username", username);
        if (email != null) body.put("email", email);
        if (phone != null) body.put("phone", phone);
        body.put("password", password);

        httpClient.post("/api/v1/auth/register", body, JSONObject.class, new CustomHttpClient.RequestCallback<JSONObject>() {
            @Override
            public void onSuccess(JSONObject response) {
                handleAuthResponse(response, callback);
            }

            @Override
            public void onError(NetworkError error) {
                Utilities.stageQueue.postRunnable(() -> callback.onError(error));
            }
        });
    }

    // ==================== Token Refresh ====================

    public void refreshAccessToken(AuthCallback callback) {
        String refreshToken = tokenStore.getRefreshToken();
        if (refreshToken == null) {
            Utilities.stageQueue.postRunnable(() -> callback.onError(NetworkError.authError("No refresh token available", 401)));
            return;
        }

        // Use atomic reference to handle concurrent refresh attempts
        AuthCallback existingCallback = pendingRefreshCallback.getAndSet(callback);
        if (existingCallback != null) {
            // Another refresh is in progress, queue this callback
            return;
        }

        Map<String, String> body = new HashMap<>();
        body.put("refresh_token", tokenStore.getRefreshToken());
        body.put("grant_type", "refresh_token");

        httpClient.post("/api/v1/auth/refresh", body, JSONObject.class, new CustomHttpClient.RequestCallback<JSONObject>() {
            @Override
            public void onSuccess(JSONObject response) {
                // Get and clear the pending callback
                AuthCallback cb = pendingRefreshCallback.getAndSet(null);
                handleAuthResponse(response, callback);
                if (cb != null && cb != callback) {
                    handleAuthResponse(response, cb);
                }
            }

            @Override
            public void onError(NetworkError error) {
                AuthCallback cb = pendingRefreshCallback.getAndSet(null);
                // Refresh token expired or invalid - force logout
                if (error.isAuthError()) {
                    clearSession();
                }
                Utilities.stageQueue.postRunnable(() -> callback.onError(error));
                if (cb != null && cb != callback) {
                    Utilities.stageQueue.postRunnable(() -> cb.onError(error));
                }
            }
        });
    }

    // ==================== Get Current User ====================

    public void getCurrentUser(UserProfileCallback callback) {
        String token = tokenStore.getAccessToken();
        if (token == null) {
            Utilities.stageQueue.postRunnable(() -> callback.onError(NetworkError.authError("No access token", 401)));
            return;
        }

        httpClient.get("/api/v1/users/me", UserModel.class, new CustomHttpClient.RequestCallback<UserModel>() {
            @Override
            public void onSuccess(UserModel user) {
                Utilities.stageQueue.postRunnable(() -> callback.onSuccess(user));
            }

            @Override
            public void onError(NetworkError error) {
                // Check if a refresh is already in progress using atomic reference
                if (error.isAuthError() && pendingRefreshCallback.get() == null) {
                    // Try to refresh token and retry
                    refreshAccessToken(new AuthCallback() {
                        @Override
                        public void onSuccess(AuthResult result) {
                            getCurrentUser(callback);
                        }

                        @Override
                        public void onError(NetworkError err) {
                            Utilities.stageQueue.postRunnable(() -> callback.onError(err));
                        }
                    });
                } else {
                    Utilities.stageQueue.postRunnable(() -> callback.onError(error));
                }
            }
        });
    }

    public void getUserById(String userId, UserProfileCallback callback) {
        httpClient.get("/api/v1/users/" + userId, UserModel.class, new CustomHttpClient.RequestCallback<UserModel>() {
            @Override
            public void onSuccess(UserModel user) {
                Utilities.stageQueue.postRunnable(() -> callback.onSuccess(user));
            }

            @Override
            public void onError(NetworkError error) {
                Utilities.stageQueue.postRunnable(() -> callback.onError(error));
            }
        });
    }

    // ==================== Update Profile ====================

    public void updateProfile(Map<String, Object> updates, UserProfileCallback callback) {
        httpClient.patch("/api/v1/users/me", updates, UserModel.class, new CustomHttpClient.RequestCallback<UserModel>() {
            @Override
            public void onSuccess(UserModel user) {
                Utilities.stageQueue.postRunnable(() -> callback.onSuccess(user));
            }

            @Override
            public void onError(NetworkError error) {
                Utilities.stageQueue.postRunnable(() -> callback.onError(error));
            }
        });
    }

    // ==================== Logout ====================

    public void logout(LogoutCallback callback) {
        String refreshToken = tokenStore.getRefreshToken();

        // Call backend logout endpoint if we have a refresh token
        if (refreshToken != null) {
            Map<String, String> body = new HashMap<>();
            body.put("refresh_token", refreshToken);

            httpClient.post("/api/v1/auth/logout", body, JSONObject.class, new CustomHttpClient.RequestCallback<JSONObject>() {
                @Override
                public void onSuccess(JSONObject response) {
                    clearSession();
                    Utilities.stageQueue.postRunnable(callback::onSuccess);
                }

                @Override
                public void onError(NetworkError error) {
                    // Even if backend logout fails, clear local session
                    clearSession();
                    Utilities.stageQueue.postRunnable(callback::onSuccess);
                }
            });
        } else {
            clearSession();
            Utilities.stageQueue.postRunnable(callback::onSuccess);
        }
    }

    public void logoutEverywhere(LogoutCallback callback) {
        String refreshToken = tokenStore.getRefreshToken();

        if (refreshToken != null) {
            Map<String, String> body = new HashMap<>();
            body.put("refresh_token", refreshToken);
            body.put("everywhere", "true");

            httpClient.post("/api/v1/auth/logout", body, JSONObject.class, new CustomHttpClient.RequestCallback<JSONObject>() {
                @Override
                public void onSuccess(JSONObject response) {
                    clearSession();
                    Utilities.stageQueue.postRunnable(callback::onSuccess);
                }

                @Override
                public void onError(NetworkError error) {
                    // Even if backend logout fails, clear local session
                    clearSession();
                    Utilities.stageQueue.postRunnable(callback::onSuccess);
                }
            });
        } else {
            clearSession();
            Utilities.stageQueue.postRunnable(callback::onSuccess);
        }
    }

    // ==================== Session Management ====================

    public boolean hasValidSession() {
        return tokenStore.hasValidSession();
    }

    public String getCurrentAccessToken() {
        return tokenStore.getAccessToken();
    }

    public String getCurrentRefreshToken() {
        return tokenStore.getRefreshToken();
    }

    public long getCurrentUserId() {
        return tokenStore.getUserId();
    }

    public void clearSession() {
        tokenStore.clearTokens();
        FileLog.d("Session cleared");
    }

    public void restoreSession() {
        if (hasValidSession()) {
            FileLog.d("Restored valid session for user " + getCurrentUserId());
        } else {
            FileLog.d("No valid session to restore");
            clearSession();
        }
    }

    // ==================== Internal Helpers ====================

    public void handleAuthResponse(JSONObject response, AuthCallback callback) {
        try {
            String accessToken = response.getString("access_token");
            String refreshToken = response.optString("refresh_token", null);
            long expiresIn = response.getLong("expires_in");
            long userId = response.getLong("user_id");

            JSONObject userJson = response.getJSONObject("user");
            UserModel user = parseUser(userJson);

            // Save tokens securely
            tokenStore.saveTokens(accessToken, refreshToken, userId, expiresIn);

            AuthResult result = new AuthResult(accessToken, refreshToken, user, expiresIn);
            FileLog.d("Auth successful for user: " + userId);

            Utilities.stageQueue.postRunnable(() -> callback.onSuccess(result));

        } catch (JSONException e) {
            FileLog.e("Failed to parse auth response", e);
            Utilities.stageQueue.postRunnable(() -> callback.onError(NetworkError.parseError("Invalid auth response: " + e.getMessage())));
        }
    }

    private UserModel parseUser(JSONObject json) throws JSONException {
        UserModel.Builder builder = new UserModel.Builder(
                json.getString("id"),
                json.getString("username")
        );

        if (json.has("display_name") && !json.isNull("display_name")) {
            builder.displayName(json.getString("display_name"));
        }
        if (json.has("phone") && !json.isNull("phone")) {
            builder.phoneNumber(json.getString("phone"));
        }
        if (json.has("email") && !json.isNull("email")) {
            builder.email(json.getString("email"));
        }
        if (json.has("avatar_url") && !json.isNull("avatar_url")) {
            builder.avatarUrl(json.getString("avatar_url"));
        }
        if (json.has("bio") && !json.isNull("bio")) {
            builder.bio(json.getString("bio"));
        }
        if (json.has("verified")) {
            builder.verified(json.getBoolean("verified"));
        }
        if (json.has("premium")) {
            builder.premium(json.getBoolean("premium"));
        }
        if (json.has("bot")) {
            builder.bot(json.getBoolean("bot"));
        }
        if (json.has("deleted")) {
            builder.deleted(json.getBoolean("deleted"));
        }
        if (json.has("scam")) {
            builder.scam(json.getBoolean("scam"));
        }
        if (json.has("fake")) {
            builder.fake(json.getBoolean("fake"));
        }
        if (json.has("created_at")) {
            builder.createdAt(json.getLong("created_at"));
        }
        if (json.has("last_seen") && !json.isNull("last_seen")) {
            builder.lastSeen(json.getString("last_seen"));
        }

        return builder.build();
    }

    // ==================== Synchronous Helpers for Testing ====================

    public AuthResult loginWithPasswordSync(String username, String password) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        final AuthResult[] result = new AuthResult[1];
        final NetworkError[] error = new NetworkError[1];

        loginWithPassword(username, password, new AuthCallback() {
            @Override
            public void onSuccess(AuthResult authResult) {
                result[0] = authResult;
                latch.countDown();
            }

            @Override
            public void onError(NetworkError err) {
                error[0] = err;
                latch.countDown();
            }
        });

        if (!latch.await(30, TimeUnit.SECONDS)) {
            throw new Exception("Login timeout");
        }

        if (error[0] != null) {
            throw new Exception("Login failed: " + error[0].getMessage());
        }

        return result[0];
    }
}