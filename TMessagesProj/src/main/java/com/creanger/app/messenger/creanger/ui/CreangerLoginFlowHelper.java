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
final class CreangerLoginFlowHelper {

    private CreangerLoginFlowHelper() {}

    static void proceedWithUser(CreangerUser user, Activity activity) {
        if (user == null || activity == null || activity.isFinishing()) return;

        TLRPC.TL_user self = new TLRPC.TL_user();
        self.id = creangerUserIdToLong(user);
        self.first_name = user.displayName != null ? user.displayName
                : (user.username != null ? user.username : "User");
        if (user.username != null) {
            self.username = user.username;
        }
        self.flags |= (1 << 0) | (1 << 1);

        int account = UserConfig.selectedAccount;
        UserConfig.getInstance(account).clearConfig();
        UserConfig.getInstance(account).setCurrentUser(self);
        UserConfig.getInstance(account).saveConfig(true);

        com.creanger.app.messenger.NotificationCenter.getInstance(account)
                .postNotificationName(com.creanger.app.messenger.NotificationCenter.mainUserInfoChanged);

        try {
            Intent intent = new Intent(activity, LaunchActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            activity.startActivity(intent);
            activity.finish();
        } catch (Exception e) {
            activity.finish();
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
