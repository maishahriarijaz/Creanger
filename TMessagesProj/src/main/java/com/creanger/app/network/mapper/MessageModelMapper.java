package com.creanger.app.network.mapper;

import com.creanger.app.network.model.EntityModel;
import com.creanger.app.network.model.MediaModel;
import com.creanger.app.network.model.MessageModel;
import com.creanger.app.tgnet.TLRPC;
import com.creanger.app.tgnet.Vector;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Maps between backend MessageModel and TLRPC.Message for UI compatibility.
 * Simplified version - only essential mappings for UI compatibility.
 */
public class MessageModelMapper {

    public static TLRPC.Message toTLRPCMessage(MessageModel model) {
        TLRPC.Message message = new TLRPC.Message();

        // ID - TLRPC uses int, backend uses String
        try {
            message.id = Integer.parseInt(model.id);
        } catch (NumberFormatException e) {
            message.id = model.id.hashCode();
        }

        // Peer ID
        message.peer_id = new TLRPC.TL_peerChat();
        try {
            message.peer_id.chat_id = Integer.parseInt(model.chatId);
        } catch (NumberFormatException e) {
            message.peer_id.chat_id = model.chatId.hashCode();
        }

        // From ID
        message.from_id = new TLRPC.TL_peerUser();
        try {
            message.from_id.user_id = Integer.parseInt(model.senderId);
        } catch (NumberFormatException e) {
            message.from_id.user_id = model.senderId.hashCode();
        }

        // Date
        message.date = (int) (model.date / 1000);

        // Text
        message.message = model.text != null ? model.text : "";

        // Entities
        if (model.entities != null && !model.entities.isEmpty()) {
            message.entities = new ArrayList<>();
            for (EntityModel entity : model.entities) {
                message.entities.add(toTLRPCEntity(entity));
            }
        }

        // Media - simplified
        if (model.media != null) {
            message.media = toTLRPCMedia(model.media);
        }

        // Flags
        message.out = model.isOutgoing;
        message.mentioned = false;
        message.media_unread = false;
        message.silent = model.isSilent;
        message.post = false;
        message.post_author = null;
        message.reply_to = null;
        // Reply
        if (model.replyToMessageId != null) {
            message.reply_to = new TLRPC.TL_messageReplyHeader();
            try {
                message.reply_to.reply_to_msg_id = Integer.parseInt(model.replyToMessageId);
            } catch (NumberFormatException e) {
                message.reply_to.reply_to_msg_id = model.replyToMessageId.hashCode();
            }
        }
        message.ttl = 0;
        message.via_bot_id = 0;
        message.edit_hide = false;
        message.pinned = model.isPinned;
        message.noforwards = false;
        message.invert_media = false;
        message.offline = model.isPending();
        message.from_scheduled = false;
        message.legacy = false;
        message.edit_date = model.isEdited ? (int) (model.editedAt / 1000) : 0;

        // Views and forwards
        message.views = model.views;
        message.forwards = model.forwards;

        // Reply markup
        message.reply_markup = null;

        // TTL
        message.ttl = 0;

        return message;
    }

    private static TLRPC.MessageEntity toTLRPCEntity(EntityModel entity) {
        TLRPC.MessageEntity tlEntity;

        switch (entity.type) {
            case BOLD:
                tlEntity = new TLRPC.TL_messageEntityBold();
                break;
            case ITALIC:
                tlEntity = new TLRPC.TL_messageEntityItalic();
                break;
            case UNDERLINE:
                tlEntity = new TLRPC.TL_messageEntityUnderline();
                break;
            case STRIKETHROUGH:
                tlEntity = new TLRPC.TL_messageEntityStrike();
                break;
            case CODE:
                tlEntity = new TLRPC.TL_messageEntityCode();
                break;
            case PRE:
                TLRPC.TL_messageEntityPre pre = new TLRPC.TL_messageEntityPre();
                pre.language = entity.language != null ? entity.language : "";
                tlEntity = pre;
                break;
            case TEXT_LINK:
                TLRPC.TL_messageEntityTextUrl textUrl = new TLRPC.TL_messageEntityTextUrl();
                textUrl.url = entity.url != null ? entity.url : "";
                tlEntity = textUrl;
                break;
            case TEXT_MENTION:
                TLRPC.TL_messageEntityMentionName mention = new TLRPC.TL_messageEntityMentionName();
                try {
                    mention.user_id = Integer.parseInt(entity.userId);
                } catch (NumberFormatException e) {
                    mention.user_id = entity.userId.hashCode();
                }
                tlEntity = mention;
                break;
            case HASHTAG:
                tlEntity = new TLRPC.TL_messageEntityHashtag();
                break;
            case CASHTAG:
                tlEntity = new TLRPC.TL_messageEntityCashtag();
                break;
            case BOT_COMMAND:
                tlEntity = new TLRPC.TL_messageEntityBotCommand();
                break;
            case URL:
                tlEntity = new TLRPC.TL_messageEntityUrl();
                break;
            case EMAIL:
                tlEntity = new TLRPC.TL_messageEntityEmail();
                break;
            case PHONE_NUMBER:
                tlEntity = new TLRPC.TL_messageEntityPhone();
                break;
            case CUSTOM_EMOJI:
                TLRPC.TL_messageEntityCustomEmoji emoji = new TLRPC.TL_messageEntityCustomEmoji();
                try {
                    emoji.document_id = Long.parseLong(entity.customEmojiId);
                } catch (NumberFormatException e) {
                    emoji.document_id = 0;
                }
                tlEntity = emoji;
                break;
            default:
                tlEntity = new TLRPC.TL_messageEntityBold();
        }

        tlEntity.offset = entity.offset;
        tlEntity.length = entity.length;
        return tlEntity;
    }

    private static TLRPC.MessageMedia toTLRPCMedia(MediaModel media) {
        // Simplified implementation - return empty media
        // Full implementation would require proper TLRPC class instantiation
        return new TLRPC.TL_messageMediaEmpty();
    }

    public static MessageModel fromJSON(JSONObject json) throws Exception {
        MessageModel.Builder builder = new MessageModel.Builder(
                json.getString("id"),
                json.getString("chat_id"),
                json.getString("sender_id")
        );

        if (json.has("text")) {
            builder.text(json.getString("text"));
        }

        if (json.has("date")) {
            builder.date(json.getLong("date"));
        }

        if (json.has("local_date")) {
            builder.localDate(json.getLong("local_date"));
        }

        if (json.has("state")) {
            builder.state(MessageModel.State.valueOf(json.getString("state").toUpperCase()));
        }

        if (json.has("outgoing")) {
            builder.outgoing(json.getBoolean("outgoing"));
        }

        if (json.has("reply_to_message_id") && !json.isNull("reply_to_message_id")) {
            builder.replyToMessageId(json.getString("reply_to_message_id"));
        }

        // Entities
        if (json.has("entities") && !json.isNull("entities")) {
            List<EntityModel> entities = new ArrayList<>();
            JSONArray entitiesArray = json.getJSONArray("entities");
            for (int i = 0; i < entitiesArray.length(); i++) {
                entities.add(EntityModelMapper.fromJSON(entitiesArray.getJSONObject(i)));
            }
            builder.entities(entities);
        }

        // Media
        if (json.has("media") && !json.isNull("media")) {
            builder.media(MediaModelMapper.fromJSON(json.getJSONObject("media")));
        }

        // Additional fields
        if (json.has("views")) builder.views(json.getInt("views"));
        if (json.has("forwards")) builder.forwards(json.getInt("forwards"));
        if (json.has("edited")) builder.edited(json.getBoolean("edited"), json.getLong("edited_at"));
        if (json.has("pinned")) builder.pinned(json.getBoolean("pinned"));
        if (json.has("silent")) builder.silent(json.getBoolean("silent"));

        return builder.build();
    }

    public static MessageModel fromTLRPCMessage(TLRPC.Message message) {
        MessageModel.Builder builder = new MessageModel.Builder(
                String.valueOf(message.id),
                String.valueOf(message.peer_id != null ? message.peer_id.chat_id : 0),
                String.valueOf(message.from_id != null ? message.from_id.user_id : 0)
        );

        builder.text(message.message);
        builder.date(message.date * 1000L);
        builder.outgoing(message.out);
        builder.edited(message.edit_date != 0, message.edit_date * 1000L);
        builder.pinned(message.pinned);
        builder.silent(message.silent);

        if (message.entities != null) {
            List<EntityModel> entities = new ArrayList<>();
            for (Object obj : message.entities) {
                if (obj instanceof TLRPC.MessageEntity) {
                    entities.add(EntityModelMapper.fromTLRPCEntity((TLRPC.MessageEntity) obj));
                }
            }
            builder.entities(entities);
        }

        if (message.media != null) {
            builder.media(MediaModelMapper.fromTLRPCMedia(message.media));
        }

        builder.state(message.out ? MessageModel.State.SENT : MessageModel.State.PENDING);

        return builder.build();
    }
}