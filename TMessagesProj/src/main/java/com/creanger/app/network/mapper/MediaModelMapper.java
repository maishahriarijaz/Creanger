package com.creanger.app.network.mapper;

import com.creanger.app.network.model.EntityModel;
import com.creanger.app.network.model.MediaModel;
import com.creanger.app.network.model.MessageModel;
import com.creanger.app.tgnet.TLRPC;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps between backend MediaModel and TLRPC message media.
 */
public class MediaModelMapper {

    public static MediaModel fromJSON(JSONObject json) throws JSONException {
        String id = json.getString("id");
        String typeStr = json.getString("type");
        MessageModel.MediaType type = MessageModel.MediaType.valueOf(typeStr.toUpperCase());

        MediaModel.Builder builder = new MediaModel.Builder(id, type);

        if (json.has("url") && !json.isNull("url")) {
            builder.url(json.getString("url"));
        }
        if (json.has("thumbnail_url") && !json.isNull("thumbnail_url")) {
            builder.thumbnailUrl(json.getString("thumbnail_url"));
        }
        if (json.has("file_name") && !json.isNull("file_name")) {
            builder.fileName(json.getString("file_name"));
        }
        if (json.has("mime_type") && !json.isNull("mime_type")) {
            builder.mimeType(json.getString("mime_type"));
        }
        if (json.has("file_size")) {
            builder.fileSize(json.getLong("file_size"));
        }
        if (json.has("width")) {
            builder.dimensions(json.getInt("width"), json.getInt("height"));
        }
        if (json.has("duration")) {
            builder.duration(json.getInt("duration"));
        }
        if (json.has("caption") && !json.isNull("caption")) {
            builder.caption(json.getString("caption"));
        }
        if (json.has("caption_entities") && !json.isNull("caption_entities")) {
            List<EntityModel> entities = new ArrayList<>();
            JSONArray entitiesArray = json.getJSONArray("caption_entities");
            for (int i = 0; i < entitiesArray.length(); i++) {
                entities.add(EntityModelMapper.fromJSON(entitiesArray.getJSONObject(i)));
            }
            builder.captionEntities(entities);
        }

        return builder.build();
    }

    public static MediaModel fromTLRPCMedia(TLRPC.MessageMedia media) {
        if (media instanceof TLRPC.TL_messageMediaPhoto) {
            TLRPC.TL_messageMediaPhoto photoMedia = (TLRPC.TL_messageMediaPhoto) media;
            TLRPC.Photo photo = photoMedia.photo;

            MediaModel.Builder builder = new MediaModel.Builder(
                    String.valueOf(photo.id),
                    MessageModel.MediaType.PHOTO
            );

            if (photo.sizes != null && !photo.sizes.isEmpty()) {
                TLRPC.PhotoSize largest = photo.sizes.get(photo.sizes.size() - 1);
                builder.dimensions(largest.w, largest.h);
            }

            // Caption is on the message, not the media
            return builder.build();
        }

        if (media instanceof TLRPC.TL_messageMediaDocument) {
            TLRPC.TL_messageMediaDocument docMedia = (TLRPC.TL_messageMediaDocument) media;
            TLRPC.Document doc = docMedia.document;

            MessageModel.MediaType type;
            if (doc.mime_type != null) {
                if (doc.mime_type.startsWith("video/")) {
                    type = MessageModel.MediaType.VIDEO;
                } else if (doc.mime_type.startsWith("audio/")) {
                    type = MessageModel.MediaType.AUDIO;
                } else if (doc.mime_type.equals("image/gif") || doc.mime_type.equals("image/webp")) {
                    type = MessageModel.MediaType.GIF;
                } else if (DOC_ATTR_STICKER != null && hasAttribute(doc, DOC_ATTR_STICKER)) {
                    type = MessageModel.MediaType.STICKER;
                } else {
                    type = MessageModel.MediaType.DOCUMENT;
                }
            } else {
                type = MessageModel.MediaType.DOCUMENT;
            }

            MediaModel.Builder builder = new MediaModel.Builder(
                    String.valueOf(doc.id),
                    type
            );

            builder.fileName(doc.file_name)
                    .mimeType(doc.mime_type)
                    .fileSize(doc.size);
                    // Caption is on the message, not the media

            if (doc.thumbs != null && !doc.thumbs.isEmpty()) {
                TLRPC.PhotoSize thumb = doc.thumbs.get(0);
                builder.thumbnailUrl(""); // Would need to generate URL
                builder.dimensions(thumb.w, thumb.h);
            }

            if (doc.attributes != null) {
                for (Object attr : doc.attributes) {
                    if (attr instanceof TLRPC.TL_documentAttributeVideo) {
                        TLRPC.TL_documentAttributeVideo videoAttr = (TLRPC.TL_documentAttributeVideo) attr;
                        builder.duration((int) videoAttr.duration);
                        builder.dimensions(videoAttr.w, videoAttr.h);
                    } else if (attr instanceof TLRPC.TL_documentAttributeAudio) {
                        TLRPC.TL_documentAttributeAudio audioAttr = (TLRPC.TL_documentAttributeAudio) attr;
                        builder.duration((int) audioAttr.duration);
                    }
                }
            }

            return builder.build();
        }

        return null;
    }

    // Use correct TLRPC class names with TL_ prefix
    private static final Class<?> DOC_ATTR_STICKER = getTLRPCClass("TL_documentAttributeSticker");
    private static final Class<?> DOC_ATTR_ANIMATED = getTLRPCClass("TL_documentAttributeAnimated");
    private static final Class<?> DOC_ATTR_VIDEO = getTLRPCClass("TL_documentAttributeVideo");
    private static final Class<?> DOC_ATTR_AUDIO = getTLRPCClass("TL_documentAttributeAudio");

    private static Class<?> getTLRPCClass(String className) {
        try {
            return Class.forName("com.creanger.app.tgnet.TLRPC$" + className);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private static boolean hasAttribute(TLRPC.Document doc, Class<?> attrClass) {
        if (doc.attributes == null) return false;
        for (Object attr : doc.attributes) {
            if (attrClass.isInstance(attr)) return true;
        }
        return false;
    }

    public static JSONObject toJSON(MediaModel model) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", model.id);
        json.put("type", model.type.name().toLowerCase());
        if (model.url != null) json.put("url", model.url);
        if (model.thumbnailUrl != null) json.put("thumbnail_url", model.thumbnailUrl);
        if (model.fileName != null) json.put("file_name", model.fileName);
        if (model.mimeType != null) json.put("mime_type", model.mimeType);
        if (model.fileSize > 0) json.put("file_size", model.fileSize);
        if (model.width > 0) json.put("width", model.width);
        if (model.height > 0) json.put("height", model.height);
        if (model.duration > 0) json.put("duration", model.duration);
        if (model.caption != null) json.put("caption", model.caption);

        if (model.captionEntities != null && !model.captionEntities.isEmpty()) {
            JSONArray entities = new JSONArray();
            for (EntityModel e : model.captionEntities) {
                entities.put(entityToJSON(e));
            }
            json.put("caption_entities", entities);
        }

        return json;
    }

    private static JSONObject entityToJSON(EntityModel e) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("type", e.type.name().toLowerCase());
        json.put("offset", e.offset);
        json.put("length", e.length);
        if (e.url != null) json.put("url", e.url);
        if (e.userId != null) json.put("user_id", e.userId);
        if (e.language != null) json.put("language", e.language);
        if (e.customEmojiId != null) json.put("custom_emoji_id", e.customEmojiId);
        return json;
    }
}