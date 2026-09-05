package com.creanger.app.network.mapper;

import com.creanger.app.network.model.EntityModel;
import com.creanger.app.tgnet.TLRPC;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Maps between backend EntityModel and TLRPC.MessageEntity.
 */
public class EntityModelMapper {

    public static EntityModel fromJSON(JSONObject json) throws JSONException {
        String typeStr = json.getString("type");
        EntityModel.Type type = EntityModel.Type.valueOf(typeStr.toUpperCase());

        EntityModel.Builder builder = new EntityModel.Builder(type)
                .offset(json.getInt("offset"))
                .length(json.getInt("length"));

        if (json.has("url") && !json.isNull("url")) {
            builder.url(json.getString("url"));
        }
        if (json.has("user_id") && !json.isNull("user_id")) {
            builder.userId(json.getString("user_id"));
        }
        if (json.has("language") && !json.isNull("language")) {
            builder.language(json.getString("language"));
        }
        if (json.has("custom_emoji_id") && !json.isNull("custom_emoji_id")) {
            builder.customEmojiId(json.getString("custom_emoji_id"));
        }

        return builder.build();
    }

    public static EntityModel fromTLRPCEntity(TLRPC.MessageEntity entity) {
        EntityModel.Type type;

        if (entity instanceof TLRPC.TL_messageEntityBold) {
            type = EntityModel.Type.BOLD;
        } else if (entity instanceof TLRPC.TL_messageEntityItalic) {
            type = EntityModel.Type.ITALIC;
        } else if (entity instanceof TLRPC.TL_messageEntityUnderline) {
            type = EntityModel.Type.UNDERLINE;
        } else if (entity instanceof TLRPC.TL_messageEntityStrike) {
            type = EntityModel.Type.STRIKETHROUGH;
        } else if (entity instanceof TLRPC.TL_messageEntityCode) {
            type = EntityModel.Type.CODE;
        } else if (entity instanceof TLRPC.TL_messageEntityPre) {
            type = EntityModel.Type.PRE;
        } else if (entity instanceof TLRPC.TL_messageEntityTextUrl) {
            type = EntityModel.Type.TEXT_LINK;
        } else if (entity instanceof TLRPC.TL_messageEntityMentionName) {
            type = EntityModel.Type.TEXT_MENTION;
        } else if (entity instanceof TLRPC.TL_messageEntityHashtag) {
            type = EntityModel.Type.HASHTAG;
        } else if (entity instanceof TLRPC.TL_messageEntityCashtag) {
            type = EntityModel.Type.CASHTAG;
        } else if (entity instanceof TLRPC.TL_messageEntityBotCommand) {
            type = EntityModel.Type.BOT_COMMAND;
        } else if (entity instanceof TLRPC.TL_messageEntityUrl) {
            type = EntityModel.Type.URL;
        } else if (entity instanceof TLRPC.TL_messageEntityEmail) {
            type = EntityModel.Type.EMAIL;
        } else if (entity instanceof TLRPC.TL_messageEntityPhone) {
            type = EntityModel.Type.PHONE_NUMBER;
        } else if (entity instanceof TLRPC.TL_messageEntityCustomEmoji) {
            type = EntityModel.Type.CUSTOM_EMOJI;
        } else {
            type = EntityModel.Type.BOLD; // Default
        }

        EntityModel.Builder builder = new EntityModel.Builder(type)
                .offset(entity.offset)
                .length(entity.length);

        if (entity instanceof TLRPC.TL_messageEntityTextUrl) {
            builder.url(((TLRPC.TL_messageEntityTextUrl) entity).url);
        } else if (entity instanceof TLRPC.TL_messageEntityMentionName) {
            builder.userId(String.valueOf(((TLRPC.TL_messageEntityMentionName) entity).user_id));
        } else if (entity instanceof TLRPC.TL_messageEntityPre) {
            builder.language(((TLRPC.TL_messageEntityPre) entity).language);
        } else if (entity instanceof TLRPC.TL_messageEntityCustomEmoji) {
            builder.customEmojiId(String.valueOf(((TLRPC.TL_messageEntityCustomEmoji) entity).document_id));
        }

        return builder.build();
    }

    public static TLRPC.MessageEntity toTLRPCEntity(EntityModel model) {
        TLRPC.MessageEntity entity;

        switch (model.type) {
            case BOLD:
                entity = new TLRPC.TL_messageEntityBold();
                break;
            case ITALIC:
                entity = new TLRPC.TL_messageEntityItalic();
                break;
            case UNDERLINE:
                entity = new TLRPC.TL_messageEntityUnderline();
                break;
            case STRIKETHROUGH:
                entity = new TLRPC.TL_messageEntityStrike();
                break;
            case CODE:
                entity = new TLRPC.TL_messageEntityCode();
                break;
            case PRE:
                TLRPC.TL_messageEntityPre pre = new TLRPC.TL_messageEntityPre();
                pre.language = model.language != null ? model.language : "";
                entity = pre;
                break;
            case TEXT_LINK:
                TLRPC.TL_messageEntityTextUrl textUrl = new TLRPC.TL_messageEntityTextUrl();
                textUrl.url = model.url != null ? model.url : "";
                entity = textUrl;
                break;
            case TEXT_MENTION:
                TLRPC.TL_messageEntityMentionName mention = new TLRPC.TL_messageEntityMentionName();
                try {
                    mention.user_id = Integer.parseInt(model.userId);
                } catch (NumberFormatException e) {
                    mention.user_id = model.userId.hashCode();
                }
                entity = mention;
                break;
            case HASHTAG:
                entity = new TLRPC.TL_messageEntityHashtag();
                break;
            case CASHTAG:
                entity = new TLRPC.TL_messageEntityCashtag();
                break;
            case BOT_COMMAND:
                entity = new TLRPC.TL_messageEntityBotCommand();
                break;
            case URL:
                entity = new TLRPC.TL_messageEntityUrl();
                break;
            case EMAIL:
                entity = new TLRPC.TL_messageEntityEmail();
                break;
            case PHONE_NUMBER:
                entity = new TLRPC.TL_messageEntityPhone();
                break;
            case CUSTOM_EMOJI:
                TLRPC.TL_messageEntityCustomEmoji emoji = new TLRPC.TL_messageEntityCustomEmoji();
                try {
                    emoji.document_id = Long.parseLong(model.customEmojiId);
                } catch (NumberFormatException e) {
                    emoji.document_id = 0;
                }
                entity = emoji;
                break;
            default:
                entity = new TLRPC.TL_messageEntityBold();
        }

        entity.offset = model.offset;
        entity.length = model.length;
        return entity;
    }
}