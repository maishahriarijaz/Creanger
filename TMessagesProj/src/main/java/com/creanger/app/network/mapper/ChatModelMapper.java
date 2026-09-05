package com.creanger.app.network.mapper;

import com.creanger.app.network.model.ChatModel;
import com.creanger.app.network.model.MessageModel;
import com.creanger.app.tgnet.TLRPC;
import com.creanger.app.tgnet.Vector;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Maps between backend ChatModel and TLRPC.Chat/TL_messages_dialogs for UI compatibility.
 * Simplified version for compilation.
 */
public class ChatModelMapper {

    public static TLRPC.Chat toTLRPCChat(ChatModel model) {
        TLRPC.Chat chat;
        
        if (model.type == ChatModel.Type.CHANNEL) {
            TLRPC.TL_channel channel = new TLRPC.TL_channel();
            channel.broadcast = true;
            channel.megagroup = false;
            chat = channel;
        } else if (model.type == ChatModel.Type.SUPERGROUP || model.type == ChatModel.Type.FORUM) {
            TLRPC.TL_channel channel = new TLRPC.TL_channel();
            channel.megagroup = true;
            channel.broadcast = false;
            chat = channel;
        } else if (model.type == ChatModel.Type.GROUP) {
            TLRPC.TL_chat chatObj = new TLRPC.TL_chat();
            chatObj.megagroup = false;
            chat = chatObj;
        } else {
            TLRPC.TL_chat chatObj = new TLRPC.TL_chat();
            chat = chatObj;
        }
        
        chat.id = (int) Long.parseLong(model.id);
        chat.title = model.title != null ? model.title : "";
        chat.username = model.username != null ? model.username : "";
        chat.creator = false;
        chat.verified = model.isVerified;
        chat.restricted = model.isRestricted;
        chat.scam = model.isScam;
        chat.fake = model.isFake;
        chat.participants_count = model.participantCount;
        chat.date = (int) (model.createdAt / 1000);

        return chat;
    }

    public static ChatModel fromTLRPCChat(TLRPC.Chat chat) {
        ChatModel.Type type;
        if (chat.broadcast) {
            type = ChatModel.Type.CHANNEL;
        } else if (chat.megagroup) {
            type = ChatModel.Type.SUPERGROUP;
        } else {
            type = ChatModel.Type.GROUP;
        }

        ChatModel.Builder builder = new ChatModel.Builder(String.valueOf(chat.id), type)
                .title(chat.title)
                .username(chat.username)
                .verified(chat.verified)
                .restricted(chat.restricted)
                .scam(chat.scam)
                .fake(chat.fake)
                .createdAt(chat.date * 1000L)
                .participantCount(chat.participants_count);

        return builder.build();
    }

    public static TLRPC.TL_messages_dialogs toTLRPCDialogs(List<ChatModel> models) {
        TLRPC.TL_messages_dialogs dialogs = new TLRPC.TL_messages_dialogs();
        dialogs.dialogs = new ArrayList<>();
        dialogs.messages = new ArrayList<>();
        dialogs.chats = new ArrayList<>();
        dialogs.users = new ArrayList<>();

        for (ChatModel model : models) {
            TLRPC.Chat chat = toTLRPCChat(model);
            dialogs.chats.add(chat);

            TLRPC.TL_dialog dialog = new TLRPC.TL_dialog();
            dialog.id = chat.id;
            dialog.peer = new TLRPC.TL_peerChat();
            dialog.peer.chat_id = chat.id;
            dialog.unread_count = model.unreadCount;
            dialog.unread_mentions_count = model.unreadMentionsCount;
            dialog.pinned = model.isPinned;
            dialog.top_message = 0;
            dialog.read_inbox_max_id = 0;
            dialog.read_outbox_max_id = 0;
            dialog.unread_mark = false;
            dialogs.dialogs.add(dialog);
        }

        return dialogs;
    }

    public static List<ChatModel> fromTLRPCDialogs(TLRPC.TL_messages_dialogs dialogs) {
        List<ChatModel> models = new ArrayList<>();

        for (Object obj : dialogs.chats) {
            if (obj instanceof TLRPC.Chat) {
                models.add(fromTLRPCChat((TLRPC.Chat) obj));
            }
        }

        return models;
    }

    public static List<ChatModel> fromTLRPCChats(List<TLRPC.Chat> chats) {
        List<ChatModel> models = new ArrayList<>();
        for (TLRPC.Chat chat : chats) {
            models.add(fromTLRPCChat(chat));
        }
        return models;
    }

    public static ChatModel fromJSON(JSONObject json) throws JSONException {
        if (json == null) {
            return null;
        }

        String id = json.optString("id", null);
        if (id == null) {
            return null;
        }

        String typeStr = json.optString("type", "group");
        ChatModel.Type type;
        try {
            type = ChatModel.Type.valueOf(typeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            type = ChatModel.Type.GROUP;
        }

        ChatModel.Builder builder = new ChatModel.Builder(id, type);

        if (json.has("title") && !json.isNull("title")) {
            builder.title(json.getString("title"));
        }
        if (json.has("username") && !json.isNull("username")) {
            builder.username(json.getString("username"));
        }
        if (json.has("description") && !json.isNull("description")) {
            builder.description(json.getString("description"));
        }
        if (json.has("avatar_url") && !json.isNull("avatar_url")) {
            builder.avatarUrl(json.getString("avatar_url"));
        }
        if (json.has("invite_link") && !json.isNull("invite_link")) {
            builder.inviteLink(json.getString("invite_link"));
        }
        if (json.has("created_at")) {
            builder.createdAt(json.optLong("created_at", 0));
        }
        if (json.has("participant_count")) {
            builder.participantCount(json.optInt("participant_count", 0));
        }
        if (json.has("verified")) {
            builder.verified(json.optBoolean("verified", false));
        }
        if (json.has("restricted")) {
            builder.restricted(json.optBoolean("restricted", false));
        }
        if (json.has("scam")) {
            builder.scam(json.optBoolean("scam", false));
        }
        if (json.has("fake")) {
            builder.fake(json.optBoolean("fake", false));
        }
        if (json.has("unread_count")) {
            builder.unreadCount(json.optInt("unread_count", 0));
        }
        if (json.has("unread_mentions_count")) {
            builder.unreadMentionsCount(json.optInt("unread_mentions_count", 0));
        }
        if (json.has("pinned")) {
            builder.pinned(json.optBoolean("pinned", false));
        }
        if (json.has("muted")) {
            builder.muted(json.optBoolean("muted", false), json.optLong("muted_until", 0));
        }
        if (json.has("last_message") && !json.isNull("last_message")) {
            try {
                builder.lastMessage(MessageModelMapper.fromJSON(json.getJSONObject("last_message")));
            } catch (Exception ignored) {
                // last message preview is optional
            }
        }

        return builder.build();
    }
}