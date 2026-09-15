package com.creanger.app.messenger.creanger.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;

import com.creanger.app.messenger.UserConfig;
import com.creanger.app.messenger.creanger.api.CreangerApiException;
import com.creanger.app.messenger.creanger.api.SupabaseAuthClient;
import com.creanger.app.messenger.creanger.model.AuthModels.CreangerUser;
import com.creanger.app.tgnet.TLRPC;
import com.creanger.app.tgnet.tl.TL_account;
import com.creanger.app.ui.LaunchActivity;

import java.util.Locale;

/**
 * Shared helper for completing Creanger auth and launching the main app.
 * Extracted so Login / Register / OTP / Google screens all share the same
 * "proceed to main UI" logic without touching messaging/MTProto internals.
 */
public final class CreangerLoginFlowHelper {

    private CreangerLoginFlowHelper() {}

    /**
     * Completes Creanger auth and launches the main UI.
     *
     * @return true when navigation was started, false when the caller must
     *         surface an error instead (null user/activity, finishing activity,
     *         bad account index, or a persistence/navigation failure). Never
     *         throws — callers rely on the boolean to avoid silent stuck screens.
     */
    static boolean proceedWithUser(CreangerUser user, Activity activity) {
        if (user == null || activity == null || activity.isFinishing()) return false;

        try {
            TLRPC.TL_user self = new TLRPC.TL_user();
            self.id = creangerUserIdToLong(user);
            // For Google-provisioned accounts username/displayName may live only
            // in the profiles table, not in user_metadata. Derive a sane display
            // so the UI never shows literal "null"/"@null".
            String effectiveUsername = user.username;
            if (effectiveUsername == null || effectiveUsername.isEmpty() || "null".equals(effectiveUsername)) {
                if (user.email != null && user.email.contains("@")) {
                    effectiveUsername = user.email.substring(0, user.email.indexOf('@'));
                }
            }
            if (effectiveUsername == null || effectiveUsername.isEmpty() || "null".equals(effectiveUsername)) {
                effectiveUsername = "user";
            }
            String effectiveDisplay = user.displayName;
            if (effectiveDisplay == null || effectiveDisplay.isEmpty() || "null".equals(effectiveDisplay)) {
                effectiveDisplay = effectiveUsername;
            }
            self.first_name = effectiveDisplay;
            self.username = effectiveUsername;
            self.flags |= (1 << 0) | (1 << 1);

            int account = UserConfig.selectedAccount;
            if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
                account = 0;
            }
            UserConfig.getInstance(account).clearConfig();
            UserConfig.getInstance(account).setCurrentUser(self);
            UserConfig.getInstance(account).saveConfig(true);
            ensureSelfPublished(activity, account);

            com.creanger.app.messenger.NotificationCenter.getInstance(account)
                    .postNotificationName(com.creanger.app.messenger.NotificationCenter.mainUserInfoChanged);
        } catch (Exception e) {
            return false;
        }

        try {
            Intent intent = new Intent(activity, LaunchActivity.class);
            // LaunchActivity is singleTask — start-as-same-task with CLEAR_TOP
            // plus finish() destroys the task (seen as CLOSE→launcher). Use
            // CLEAR_TASK|NEW_TASK to recreate a fresh task with LaunchActivity
            // on top so onCreate() re-evaluates UserConfig and shows main UI.
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            activity.startActivity(intent);
            // CLEAR_TASK already clears the old task — no explicit finish needed,
            // but keep a safe finish attempt for pre-CLEAR_TASK fallback.
            try {
                activity.finish();
            } catch (Exception ignored) {
            }
            return true;
        } catch (Exception e) {
            try {
                activity.finish();
            } catch (Exception ignored) {
            }
            return false;
        }
    }

    /**
     * Publishes the persisted synthetic self user into MessagesController so
     * userId-based lookups (ProfileActivity self page, avatar/name resolution)
     * succeed. Safe to call on every entry to the main UI: it is a no-op when
     * Creanger is off, when no user is stored, or when already published.
     * Covers cold restarts, where only UserConfig prefs (not the in-memory
     * users map) survive.
     */
    public static void ensureSelfPublished(Activity activity, int account) {
        try {
            if (activity == null || !com.creanger.app.messenger.BuildConfig.USE_CREANGER_AUTH) {
                return;
            }
            if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
                account = 0;
            }
            TLRPC.TL_user self = null;
            try {
                com.creanger.app.messenger.UserConfig cfg =
                        com.creanger.app.messenger.UserConfig.getInstance(account);
                if (cfg != null && cfg.getCurrentUser() instanceof TLRPC.TL_user) {
                    self = (TLRPC.TL_user) cfg.getCurrentUser();
                }
            } catch (Exception ignored) {
            }
            if (self == null) {
                return;
            }
            // Stored synthetic users may predate the username backfill (or a
            // display-name change); refresh missing fields from the session so
            // userId-based screens (self Profile, avatars) resolve correctly.
            boolean userChanged = false;
            try {
                com.creanger.app.messenger.creanger.CreangerAuth auth =
                        com.creanger.app.messenger.creanger.CreangerAuth.getInstance(
                                activity.getApplicationContext());
                com.creanger.app.messenger.creanger.model.AuthModels.CreangerUser sessionUser = null;
                try {
                    if (auth != null && auth.getCurrentUserRepository() != null) {
                        sessionUser = auth.getCurrentUserRepository().getCachedUser();
                    }
                } catch (Exception ignored) {
                }
                if (sessionUser != null) {
                    String username = sessionUser.username;
                    if ((username == null || username.isEmpty() || "null".equals(username))
                            && sessionUser.email != null && sessionUser.email.contains("@")) {
                        username = sessionUser.email.substring(0, sessionUser.email.indexOf('@'));
                    }
                    if (username != null && !username.isEmpty() && !"null".equals(username)
                            && (self.username == null || self.username.isEmpty())) {
                        self.username = username;
                        userChanged = true;
                    }
                    String display = sessionUser.displayName;
                    if (display == null || display.isEmpty() || "null".equals(display)) {
                        display = username;
                    }
                    if (display != null && !display.isEmpty() && !"null".equals(display)
                            && (self.first_name == null || self.first_name.isEmpty())) {
                        self.first_name = display;
                        userChanged = true;
                    }
                }
                if (userChanged) {
                    try {
                        com.creanger.app.messenger.UserConfig cfg =
                                com.creanger.app.messenger.UserConfig.getInstance(account);
                        if (cfg != null) {
                            cfg.saveConfig(true);
                        }
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
            try {
                com.creanger.app.messenger.MessagesController mc =
                        com.creanger.app.messenger.MessagesController.getInstance(account);
                if (mc != null && (mc.getUser(self.id) == null || userChanged)) {
                    mc.putUser(self, true);
                }
            } catch (Exception ignored) {
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Loads the caller's own full profile row (bio + birthday). Blocking —
     * must run off the main thread. Returns null when unauthenticated, when
     * Creanger auth is off, or when the row is missing/unreachable.
     */
    @Nullable
    public static SupabaseAuthClient.ProfileRow fetchOwnProfileDetail(Context context) {
        try {
            if (context == null || !com.creanger.app.messenger.BuildConfig.USE_CREANGER_AUTH) {
                return null;
            }
            com.creanger.app.messenger.creanger.CreangerAuth auth =
                    com.creanger.app.messenger.creanger.CreangerAuth.getInstance(
                            context.getApplicationContext());
            if (auth == null || auth.getEngine() == null) {
                return null;
            }
            return auth.getEngine().getOwnProfileDetail();
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Builds a synthetic full-user for the Creanger self profile so the
     * existing ProfileActivity rows (bio, birthday) render without MTProto.
     */
    public static TLRPC.TL_userFull buildOwnUserFull(long uid, TLRPC.User user,
            SupabaseAuthClient.ProfileRow row) {
        TLRPC.TL_userFull full = new TLRPC.TL_userFull();
        full.id = uid;
        if (user != null) {
            full.user = user;
        }
        if (row != null) {
            if (row.bio != null && !row.bio.isEmpty()) {
                full.about = row.bio;
            }
            TL_account.TL_birthday birthday = toBirthday(row.birthday);
            if (birthday != null) {
                full.birthday = birthday;
            }
        }
        return full;
    }

    /** Parses an ISO yyyy-MM-dd date into a TL birthday (with year). */
    @Nullable
    public static TL_account.TL_birthday toBirthday(@Nullable String isoDate) {
        if (isoDate == null) {
            return null;
        }
        try {
            String[] parts = isoDate.trim().split("-", -1);
            if (parts.length != 3) {
                return null;
            }
            int year = Integer.parseInt(parts[0]);
            int month = Integer.parseInt(parts[1]);
            int day = Integer.parseInt(parts[2]);
            if (year < 1900 || year > 2100 || month < 1 || month > 12 || day < 1 || day > 31) {
                return null;
            }
            TL_account.TL_birthday birthday = new TL_account.TL_birthday();
            birthday.flags |= 1;
            birthday.year = year;
            birthday.month = month;
            birthday.day = day;
            return birthday;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Formats a TL birthday as ISO yyyy-MM-dd for the profiles API. */
    @Nullable
    public static String fromBirthday(@Nullable TL_account.TL_birthday birthday) {
        if (birthday == null || birthday.year <= 0) {
            return null;
        }
        return String.format(Locale.US, "%04d-%02d-%02d", birthday.year, birthday.month, birthday.day);
    }

    static long creangerUserIdToLong(CreangerUser user) {
        String id = user.id != null ? user.id : "0";
        long hash = 1125899906842597L;
        for (int i = 0; i < id.length(); i++) {
            hash = 31 * hash + id.charAt(i);
        }
        return hash & 0x7fffffffffffffffL;
    }

    static String safeErrorMessage(CreangerApiException error, Throwable ioError) {
        if (error != null && error.error != null && error.error.message != null
                && !error.error.message.isEmpty()) {
            return error.error.message;
        }
        if (ioError != null && ioError.getMessage() != null) {
            return ioError.getMessage();
        }
        return "Something went wrong. Please try again.";
    }
}
