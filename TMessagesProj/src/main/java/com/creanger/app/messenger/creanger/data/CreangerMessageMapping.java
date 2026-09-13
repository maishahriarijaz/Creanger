package com.creanger.app.messenger.creanger.data;

import com.creanger.app.tgnet.TLRPC;
import com.creanger.app.messenger.creanger.model.MessageModels.MediaAttachment;
import com.creanger.app.messenger.creanger.model.MessageModels.MessageStatus;
import com.creanger.app.messenger.creanger.model.MessageModels.MessageType;
import com.creanger.app.tgnet.tl.TL_iv;
import org.json.JSONObject;
import org.json.JSONException;
import org.json.JSONArray;

import androidx.annotation.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pure Java core of the Creanger→Telegram compatibility mapping.
 * Contains all logic that can be tested on JVM without Android classes.
 *
 * The Android-specific {@link CreangerMessageObjectAdapter} wraps this
 * and adds TLRPC/MessageObject construction.
 */
public final class CreangerMessageMapping {

    /**
     * Reply-banner author label used when the replied-to sender is not the
     * current user and no display-name lookup exists in the Creanger layer yet.
     * Deterministic so the rendered banner never falls back to Telegram's
     * "Loading" placeholder.
     */
    public static final String UNKNOWN_REPLY_SENDER = "Creanger user";

    /** Bidirectional map: synthetic UI Long ID ↔ Creanger UUID String */
    private final Map<Long, String> syntheticToCreanger = new ConcurrentHashMap<>();
    private final Map<String, Long> creangerToSynthetic = new ConcurrentHashMap<>();

    /** Next synthetic ID (negative to avoid collision with positive MTProto IDs) */
    private final AtomicLong nextSyntheticId = new AtomicLong(-1_000_000_000_000L);

    /** Owner's Creanger user ID (for 'out' determination) */
    private final String ownerCreangerId;

    public CreangerMessageMapping(String ownerCreangerId) {
        this.ownerCreangerId = ownerCreangerId;
    }

    /** Gets or creates a synthetic Long ID for a Creanger UUID */
    public long getOrCreateSyntheticId(String creangerUuid) {
        if (creangerUuid == null) {
            return nextSyntheticId.getAndDecrement();
        }
        return creangerToSynthetic.computeIfAbsent(creangerUuid, uuid -> {
            long id = nextSyntheticId.getAndDecrement();
            syntheticToCreanger.put(id, uuid);
            return id;
        });
    }

    /** Reverse lookup: synthetic ID → Creanger UUID */
    public String getCreangerUuid(long syntheticId) {
        return syntheticToCreanger.get(syntheticId);
    }

    /** Reverse lookup: Creanger UUID → synthetic ID */
    public Long getSyntheticId(String creangerUuid) {
        return creangerToSynthetic.get(creangerUuid);
    }

    /** Parse ISO8601 timestamp to unix seconds; fallback to now */
    public int parseIso8601ToUnix(String iso) {
        if (iso == null) {
            return (int) (System.currentTimeMillis() / 1000);
        }
        try {
            java.time.Instant instant = java.time.Instant.parse(iso);
            return (int) instant.getEpochSecond();
        } catch (Exception e) {
            return (int) (System.currentTimeMillis() / 1000);
        }
    }

    /** Determine send state from Creanger status */
    public int getSendState(String status) {
        if (MessageStatus.PENDING.equals(status)) {
            return 1; // MESSAGE_SEND_STATE_SENDING
        } else if (MessageStatus.FAILED.equals(status)) {
            return 2; // MESSAGE_SEND_STATE_SEND_ERROR
        } else {
            return 0; // MESSAGE_SEND_STATE_SENT
        }
    }

    /**
     * Drives the Telegram delivered/read check tint: an outbound message shows
     * the grey {@code msgOutCheck} until it is {@code read}, then the blue
     * {@code msgOutCheckRead} (ChatMessageCell.createStatusDrawableParams keys
     * {@code drawCheck1} off {@code MessageObject.isUnread()} =
     * {@code messageOwner.unread}). Incoming messages never show checks.
     */
    public boolean isUnread(String status, boolean out) {
        return out && !MessageStatus.READ.equals(status);
    }

    /** Determine if message ID should be negative (pending/failed) */
    public boolean shouldHaveNegativeId(String status) {
        return MessageStatus.PENDING.equals(status) || MessageStatus.FAILED.equals(status);
    }

    /** Build params map for reverse lookup */
    public java.util.HashMap<String, String> buildParams(CreangerMessageUiModel m) {
        java.util.HashMap<String, String> params = new java.util.HashMap<>();
        params.put("creanger_uuid", m.id);
        params.put("creanger_type", m.messageType != null ? m.messageType : MessageType.TEXT);
        params.put("client_message_id", m.clientMessageId != null ? m.clientMessageId : "");
        params.put("chat_seq", m.chatSeq != null ? String.valueOf(m.chatSeq) : "");
        params.put("is_local", String.valueOf(m.isLocal));
        // Store the first attachment's media_id for edit-media operations
        if (m.attachments != null) {
            for (MediaAttachment a : m.attachments) {
                if (a != null && a.mediaId != null && !a.mediaId.isEmpty()) {
                    params.put("creanger_media_id", a.mediaId);
                    break;
                }
            }
        }
        // Playback/poster seams for Creanger videos: the plain HTTPS source URL
        // (the renderer has no MTProto file reference to stream), plus the
        // thumbnail/poster URL. Only populated for confirmed videos whose
        // attachment actually carries a public URL (pending rows have none).
        if (MessageType.VIDEO.equals(m.messageType) && m.attachments != null) {
            for (MediaAttachment a : m.attachments) {
                if (a == null) {
                    continue;
                }
                if (a.publicUrl != null && !a.publicUrl.isEmpty()) {
                    params.put("creanger_video_url", a.publicUrl);
                }
                if (a.thumbnailUrl != null && !a.thumbnailUrl.isEmpty()) {
                    params.put("creanger_poster_url", a.thumbnailUrl);
                }
                if (!params.containsKey("creanger_video_url")) {
                    break;
                }
            }
        }
        // Audio/voice/document HTTPS URL seams for confirmed media (pending rows have none).
        if ((MessageType.AUDIO.equals(m.messageType) || MessageType.VOICE.equals(m.messageType) || MessageType.DOCUMENT.equals(m.messageType)) && m.attachments != null) {
            for (MediaAttachment a : m.attachments) {
                if (a == null) {
                    continue;
                }
                if (a.publicUrl != null && !a.publicUrl.isEmpty()) {
                    if (MessageType.AUDIO.equals(m.messageType)) {
                        params.put("creanger_audio_url", a.publicUrl);
                    } else if (MessageType.VOICE.equals(m.messageType)) {
                        params.put("creanger_voice_url", a.publicUrl);
                    } else if (MessageType.DOCUMENT.equals(m.messageType)) {
                        params.put("creanger_document_url", a.publicUrl);
                    }
                }
                if (params.containsKey("creanger_audio_url") || params.containsKey("creanger_voice_url") || params.containsKey("creanger_document_url")) {
                    break;
                }
            }
        }
        // Rich text entities (Telegram's TL_iv.RichText format)
        if (m.richText != null && m.richText.length() > 0) {
            try {
                params.put("creanger_rich_text", m.richText.toString());
            } catch (Exception e) {
                // Ignore JSON serialization errors
            }
        }
        return params;
    }

    /**
     * Convert TL_iv.RichText to Creanger JSON format.
     * The Creanger format uses a JSON object compatible with Telegram's TL_iv.RichText format.
     */
    public static String richTextToJson(TL_iv.RichText richText) {
        if (richText == null) {
            return null;
        }
        try {
            return richTextToJsonObject(richText).toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Convert Creanger rich text JSON to TL_iv.RichText.
     */
    public static TL_iv.RichText jsonToRichText(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            JSONObject jsonObj = new JSONObject(json);
            return jsonToRichText(jsonObj);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Convert Creanger rich text JSON object to TL_iv.RichText.
     */
    private static TL_iv.RichText jsonToRichText(JSONObject jsonObj) throws JSONException {
        if (jsonObj == null) {
            return new TL_iv.textEmpty();
        }
        
        // Handle different types of RichText nodes
        if (jsonObj.has("type")) {
            String type = jsonObj.getString("type");
            switch (type) {
                case "textPlain":
                    TL_iv.textPlain plain = new TL_iv.textPlain();
                    plain.text = nullToEmpty(jsonObj.optString("text", null));
                    return plain;
                case "textBold":
                    TL_iv.textBold bold = new TL_iv.textBold();
                    bold.text = jsonToRichText(jsonObj.getJSONObject("text"));
                    return bold;
                case "textItalic":
                    TL_iv.textItalic italic = new TL_iv.textItalic();
                    italic.text = jsonToRichText(jsonObj.getJSONObject("text"));
                    return italic;
                case "textUnderline":
                    TL_iv.textUnderline underline = new TL_iv.textUnderline();
                    underline.text = jsonToRichText(jsonObj.getJSONObject("text"));
                    return underline;
                case "textStrike":
                    TL_iv.textStrike strike = new TL_iv.textStrike();
                    strike.text = jsonToRichText(jsonObj.getJSONObject("text"));
                    return strike;
                case "textFixed":
                    TL_iv.textFixed fixed = new TL_iv.textFixed();
                    fixed.text = jsonToRichText(jsonObj.getJSONObject("text"));
                    return fixed;
                case "textSubscript":
                    TL_iv.textSubscript subscript = new TL_iv.textSubscript();
                    subscript.text = jsonToRichText(jsonObj.getJSONObject("text"));
                    return subscript;
                case "textSuperscript":
                    TL_iv.textSuperscript superscript = new TL_iv.textSuperscript();
                    superscript.text = jsonToRichText(jsonObj.getJSONObject("text"));
                    return superscript;
                case "textSpoiler":
                    TL_iv.textSpoiler spoiler = new TL_iv.textSpoiler();
                    spoiler.text = jsonToRichText(jsonObj.getJSONObject("text"));
                    return spoiler;
                case "textMarked":
                    TL_iv.textMarked marked = new TL_iv.textMarked();
                    marked.text = jsonToRichText(jsonObj.getJSONObject("text"));
                    return marked;
                case "textUrl":
                    TL_iv.textUrl url = new TL_iv.textUrl();
                    url.text = jsonToRichText(jsonObj.getJSONObject("text"));
                    url.url = nullToEmpty(jsonObj.optString("url", null));
                    return url;
                case "textConcat":
                    TL_iv.textConcat concat = new TL_iv.textConcat();
                    JSONArray texts = jsonObj.getJSONArray("texts");
                    java.util.ArrayList<TL_iv.RichText> textsList = new java.util.ArrayList<>();
                    for (int i = 0; i < texts.length(); i++) {
                        textsList.add(jsonToRichText(texts.getJSONObject(i)));
                    }
                    concat.texts = textsList;
                    return concat;
                case "textEmpty":
                default:
                    return new TL_iv.textEmpty();
            }
        }
        // Fallback for plain text
        return new TL_iv.textPlain();
    }

    /**
     * Convert TL_iv.RichText to JSON object.
     */
    private static JSONObject richTextToJsonObject(TL_iv.RichText richText) throws JSONException {
        if (richText instanceof TL_iv.textEmpty) {
            JSONObject json = new JSONObject();
            json.put("type", "textEmpty");
            return json;
        }
        if (richText instanceof TL_iv.textPlain) {
            TL_iv.textPlain plain = (TL_iv.textPlain) richText;
            JSONObject json = new JSONObject();
            json.put("type", "textPlain");
            json.put("text", plain.text);
            return json;
        }
        if (richText instanceof TL_iv.textBold) {
            TL_iv.textBold bold = (TL_iv.textBold) richText;
            JSONObject json = new JSONObject();
            json.put("type", "textBold");
            json.put("text", richTextToJsonObject(bold.text));
            return json;
        }
        if (richText instanceof TL_iv.textItalic) {
            TL_iv.textItalic italic = (TL_iv.textItalic) richText;
            JSONObject json = new JSONObject();
            json.put("type", "textItalic");
            json.put("text", richTextToJsonObject(italic.text));
            return json;
        }
        if (richText instanceof TL_iv.textUnderline) {
            TL_iv.textUnderline underline = (TL_iv.textUnderline) richText;
            JSONObject json = new JSONObject();
            json.put("type", "textUnderline");
            json.put("text", richTextToJsonObject(underline.text));
            return json;
        }
        if (richText instanceof TL_iv.textStrike) {
            TL_iv.textStrike strike = (TL_iv.textStrike) richText;
            JSONObject json = new JSONObject();
            json.put("type", "textStrike");
            json.put("text", richTextToJsonObject(strike.text));
            return json;
        }
        if (richText instanceof TL_iv.textFixed) {
            TL_iv.textFixed fixed = (TL_iv.textFixed) richText;
            JSONObject json = new JSONObject();
            json.put("type", "textFixed");
            json.put("text", richTextToJsonObject(fixed.text));
            return json;
        }
        if (richText instanceof TL_iv.textSubscript) {
            TL_iv.textSubscript subscript = (TL_iv.textSubscript) richText;
            JSONObject json = new JSONObject();
            json.put("type", "textSubscript");
            json.put("text", richTextToJsonObject(subscript.text));
            return json;
        }
        if (richText instanceof TL_iv.textSuperscript) {
            TL_iv.textSuperscript superscript = (TL_iv.textSuperscript) richText;
            JSONObject json = new JSONObject();
            json.put("type", "textSuperscript");
            json.put("text", richTextToJsonObject(superscript.text));
            return json;
        }
        if (richText instanceof TL_iv.textSpoiler) {
            TL_iv.textSpoiler spoiler = (TL_iv.textSpoiler) richText;
            JSONObject json = new JSONObject();
            json.put("type", "textSpoiler");
            json.put("text", richTextToJsonObject(spoiler.text));
            return json;
        }
        if (richText instanceof TL_iv.textMarked) {
            TL_iv.textMarked marked = (TL_iv.textMarked) richText;
            JSONObject json = new JSONObject();
            json.put("type", "textMarked");
            json.put("text", richTextToJsonObject(marked.text));
            return json;
        }
        if (richText instanceof TL_iv.textUrl) {
            TL_iv.textUrl url = (TL_iv.textUrl) richText;
            JSONObject json = new JSONObject();
            json.put("type", "textUrl");
            json.put("text", richTextToJsonObject(url.text));
            json.put("url", url.url);
            return json;
        }
        if (richText instanceof TL_iv.textConcat) {
            TL_iv.textConcat concat = (TL_iv.textConcat) richText;
            JSONObject json = new JSONObject();
            json.put("type", "textConcat");
            JSONArray texts = new JSONArray();
            for (TL_iv.RichText child : concat.texts) {
                texts.put(richTextToJsonObject(child));
            }
            json.put("texts", texts);
            return json;
        }
        // Default fallback
        JSONObject json = new JSONObject();
        json.put("type", "textEmpty");
        return json;
    }

    /**
     * Check if a CreangerMessageUiModel has rich text formatting.
     */
    public static boolean hasRichText(CreangerMessageUiModel m) {
        return m.richText != null && m.richText.length() > 0;
    }

    /**
     * Get the rich text as a JSONObject from a CreangerMessageUiModel.
     */
    @Nullable
    public static JSONObject getRichTextJson(CreangerMessageUiModel m) {
        if (m.richText != null && m.richText.length() > 0) {
            try {
                return new JSONObject(m.richText);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Set the rich text on a CreangerMessageUiModel from a TL_iv.RichText.
     */
    public static void setRichText(CreangerMessageUiModel m, TL_iv.RichText richText) {
        try {
            m.richText = richTextToJsonObject(richText).toString();
        } catch (Exception e) {
            m.richText = null;
        }
    }

    /**
     * Render kind of a media message, derived purely from its {@code messageType}
     * (never from the mime type, which can lie or be absent for a document).
     * The Android adapter picks the TLRPC representation from this: {@code
     * photo} maps to {@code TLRPC.Photo} (TL_messageMediaPhoto), the rest map
     * to {@code TLRPC.Document}-backed media (TL_messageMediaDocument). Kept
     * pure-JVM so the mapping is unit-testable.
     */
    public static String mediaRenderKind(String messageType) {
        if (MessageType.IMAGE.equals(messageType)) {
            return "photo";
        }
        if (MessageType.VIDEO.equals(messageType)) {
            return "video";
        }
        if (MessageType.AUDIO.equals(messageType)) {
            return "audio";
        }
        if (MessageType.VOICE.equals(messageType)) {
            return "voice";
        }
        return "document";
    }

    /**
     * The plain HTTPS source URL the existing Telegram image pipeline loads for
     * a received Creanger image (Media Phase 2). The {@code publicUrl} is the
     * stable direct-access link and is preferred; a signed {@code deliveryUrl}
     * (which can expire) is the fallback. For pending (unsent) images, the
     * {@code localPath} (a local file path or URI) is used so the preview
     * renders immediately while the upload proceeds. {@code null} when neither
     * exists or both are blank — the renderer then shows the bubble safely with
     * nothing to load (no MTProto probe, no provider client). Pure-JVM so the
     * URL selection and cache-path contract is unit-testable.
     */
    public static String imageSourceUrl(@Nullable MediaAttachment attachment) {
        if (attachment == null) {
            return null;
        }
        String publicUrl = trimToNull(attachment.publicUrl);
        if (publicUrl != null) {
            return publicUrl;
        }
        String deliveryUrl = trimToNull(attachment.deliveryUrl);
        if (deliveryUrl != null) {
            return deliveryUrl;
        }
        return trimToNull(attachment.localPath);
    }

    private static String trimToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Bit flag helper */
    public int setFlag(int flags, int bit, boolean value) {
        return value ? (flags | (1 << bit)) : (flags & ~(1 << bit));
    }

    /** Compute flags for a text message */
    public int computeFlags(boolean out) {
        int flags = 0;
        flags = setFlag(flags, 1, out);          // FLAG_1: out
        flags = setFlag(flags, 4, false);        // FLAG_4: mentioned
        flags = setFlag(flags, 5, false);        // FLAG_5: media_unread
        flags = setFlag(flags, 13, false);       // FLAG_13: silent
        flags = setFlag(flags, 14, false);       // FLAG_14: post
        flags = setFlag(flags, 18, false);       // FLAG_18: from_scheduled
        flags = setFlag(flags, 19, false);       // FLAG_19: legacy
        flags = setFlag(flags, 21, false);       // FLAG_21: edit_hide
        flags = setFlag(flags, 24, false);       // FLAG_24: pinned
        flags = setFlag(flags, 26, false);       // FLAG_26: noforwards
        flags = setFlag(flags, 27, false);       // FLAG_27: invert_media
        return flags;
    }

    /**
     * Author label for a reply banner derived purely from ids (the Creanger
     * layer has no general display-name cache yet). Own messages resolve to
     * {@code ownerDisplayName} (falling back to "You"); any other sender gets
     * the deterministic {@link #UNKNOWN_REPLY_SENDER} label instead of
     * Telegram's "Loading" placeholder. Never leaks the Creanger UUID.
     */
    public static String replyDisplayName(@Nullable String targetSenderId,
                                          @Nullable String ownerId,
                                          @Nullable String ownerDisplayName) {
        if (targetSenderId != null && targetSenderId.equals(ownerId)) {
            return ownerDisplayName != null ? ownerDisplayName : "You";
        }
        return UNKNOWN_REPLY_SENDER;
    }

    /**
     * Whether a reply banner can render for {@code replyToMessageId}: the target
     * must be a non-null authoritative UUID and must be present in the currently
     * loaded window ({@code loaded}), so the banner routes through the stable
     * synthetic view id rather than guessing a Telegram id. Pure-JVM so the
     * render-decision is unit-testable.
     */
    public boolean canRenderReply(@Nullable List<CreangerMessageUiModel> loaded,
                                  @Nullable String replyToMessageId) {
        if (replyToMessageId == null || loaded == null) {
            return false;
        }
        for (CreangerMessageUiModel m : loaded) {
            if (m != null && replyToMessageId.equals(m.id)) {
                return true;
            }
        }
        return false;
    }

    /** Owner's Creanger user ID used for 'out' determination */
    public String getOwnerCreangerId() {
        return ownerCreangerId;
    }

    /**
     * Null-safe string for UI text: Android's org.json yields the literal
     * "null" for JSON null — map it (and real null) to "".
     */
    private static String nullToEmpty(String value) {
        return value == null || value.isEmpty() || "null".equals(value) ? "" : value;
    }
}
