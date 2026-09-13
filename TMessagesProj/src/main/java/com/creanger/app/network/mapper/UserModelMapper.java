package com.creanger.app.network.mapper;

import com.creanger.app.network.model.UserModel;
import com.creanger.app.tgnet.TLRPC;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps between backend UserModel and TLRPC.User for UI compatibility.
 */
public class UserModelMapper {

    public static TLRPC.User toTLRPCUser(UserModel model) {
        TLRPC.TL_user user = new TLRPC.TL_user();
        try {
            user.id = (int) Long.parseLong(model.id);
        } catch (NumberFormatException e) {
            user.id = model.id.hashCode();
        }
        user.username = model.username;
        user.first_name = model.displayName != null ? model.displayName : "";
        user.last_name = "";
        user.phone = model.phoneNumber != null ? model.phoneNumber : "";
        user.verified = model.isVerified;
        user.premium = model.isPremium;
        user.bot = model.isBot;
        user.deleted = model.isDeleted;
        user.scam = model.isScam;
        user.fake = model.isFake;
        user.access_hash = 0;

        // Status - map lastSeen to TLRPC.UserStatus
        if (model.lastSeen != null) {
            if ("online".equalsIgnoreCase(model.lastSeen)) {
                TLRPC.TL_userStatusOnline status = new TLRPC.TL_userStatusOnline();
                status.expires = (int) (System.currentTimeMillis() / 1000) + 30;
                user.status = status;
            } else {
                try {
                    long lastSeenTime = java.time.Instant.parse(model.lastSeen).toEpochMilli() / 1000;
                    TLRPC.TL_userStatusOffline status = new TLRPC.TL_userStatusOffline();
                    status.expires = (int) lastSeenTime;
                    user.status = status;
                } catch (Exception ignored) {
                }
            }
        } else {
            TLRPC.TL_userStatusOffline status = new TLRPC.TL_userStatusOffline();
            status.expires = (int) (System.currentTimeMillis() / 1000);
            user.status = status;
        }

        return user;
    }

    public static UserModel fromTLRPCUser(TLRPC.User user) {
        String displayName = user.first_name;
        if (user.last_name != null && !user.last_name.isEmpty()) {
            displayName += " " + user.last_name;
        }

        String lastSeen = "online";
        if (user.status instanceof TLRPC.TL_userStatusOffline) {
            TLRPC.TL_userStatusOffline offline = (TLRPC.TL_userStatusOffline) user.status;
            lastSeen = java.time.Instant.ofEpochSecond(offline.expires).toString();
        } else if (user.status instanceof TLRPC.TL_userStatusOnline) {
            lastSeen = "online";
        }

        UserModel.Builder builder = new UserModel.Builder(String.valueOf(user.id), user.username)
                .displayName(displayName)
                .phoneNumber(user.phone)
                .verified(user.verified)
                .premium(user.premium)
                .bot(user.bot)
                .deleted(user.deleted)
                .scam(user.scam)
                .fake(user.fake)
                .lastSeen(lastSeen);

        if (user.status instanceof TLRPC.TL_userStatusOnline) {
            builder.lastSeen("online");
        }

        return builder.build();
    }

    public static List<TLRPC.User> toTLRPCUsers(List<UserModel> models) {
        List<TLRPC.User> users = new ArrayList<>();
        for (UserModel model : models) {
            users.add(toTLRPCUser(model));
        }
        return users;
    }

    public static List<UserModel> fromTLRPCUsers(List<TLRPC.User> users) {
        List<UserModel> models = new ArrayList<>();
        for (TLRPC.User user : users) {
            models.add(fromTLRPCUser(user));
        }
        return models;
    }

    public static UserModel fromJSON(JSONObject json) throws JSONException {
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
}