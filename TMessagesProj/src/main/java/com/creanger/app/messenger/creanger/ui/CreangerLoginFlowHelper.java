package com.creanger.app.messenger.creanger.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import com.creanger.app.messenger.UserConfig;
import com.creanger.app.messenger.creanger.api.CreangerApiException;
import com.creanger.app.messenger.creanger.model.AuthModels.CreangerUser;
import com.creanger.app.tgnet.TLRPC;
import com.creanger.app.ui.LaunchActivity;

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
            try {
                com.creanger.app.messenger.MessagesController mc =
                        com.creanger.app.messenger.MessagesController.getInstance(account);
                if (mc != null && mc.getUser(self.id) == null) {
                    mc.putUser(self, true);
                }
            } catch (Exception ignored) {
            }
        } catch (Exception ignored) {
        }
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
