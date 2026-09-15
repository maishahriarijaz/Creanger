package com.creanger.app.messenger.creanger.data;

import com.creanger.app.messenger.MessageObject;
import com.creanger.app.messenger.creanger.model.MessageModels.MediaAttachment;
import com.creanger.app.messenger.creanger.model.MessageModels.ReactionSummary;
import com.creanger.app.tgnet.TLRPC;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Android-specific adapter: converts {@link CreangerMessageUiModel} → {@link MessageObject}
 * using the pure Java {@link CreangerMessageMapping} core.
 *
 * This is a compatibility layer for TEXT and MEDIA messages. Creanger UUIDs
 * remain authoritative for all backend communication. Synthetic Long IDs are
 * VIEW-ONLY, generated deterministically from UUIDs.
 *
 * Reply rendering reuses the existing Telegram reply banner end to end: when a
 * message carries {@code replyToMessageId} and the replied-to message is in the
 * same loaded list, the adapter fills {@code TLRPC.Message.reply_to} (a
 * {@code TL_messageReplyHeader} carrying the target's synthetic Long id) and
 * {@code TLRPC.Message.replyMessage} so {@link MessageObject} derives the reply
 * preview through the stock quota banner path (no layout or cell changes). A
 * missing / deleted / not-yet-loaded target renders no banner (graceful); the
 * authoritative Creanger UUID relationship is always preserved in the model.
 */
public final class CreangerMessageObjectAdapter {

    private final CreangerMessageMapping mapping;
    private Long currentUserSyntheticId;
    private Long currentChatSyntheticId;
    @Nullable
    private String currentUserDisplayName;

    public CreangerMessageObjectAdapter(String ownerCreangerId) {
        this.mapping = new CreangerMessageMapping(ownerCreangerId);
    }

    /** Sets the current chat's synthetic ID (called when chat opens) */
    public void setCurrentChat(String chatUuid) {
        this.currentChatSyntheticId = mapping.getOrCreateSyntheticId(chatUuid);
    }

    /** Sets the current user's synthetic ID (for out/in determination) */
    public void setCurrentUser(String userUuid) {
        this.currentUserSyntheticId = mapping.getOrCreateSyntheticId(userUuid);
    }

    /**
     * Best-effort display name of the current user, used as the author label of
     * a reply banner when the replied-to message is the user's own. Optional —
     * without it "You" is used.
     */
    public void setCurrentUserDisplayName(@Nullable String name) {
        this.currentUserDisplayName = name;
    }

    /**
     * Converts a list of CreangerMessageUiModel to MessageObject list for the
     * adapter. Two passes: every message is materialized first (so its
     * synthetic id exists), then reply banners are attached for the ones whose
     * replied-to message is present in the same list.
     */
    public List<MessageObject> toMessageObjects(List<CreangerMessageUiModel> uiModels, int currentAccount) {
        List<MessageObject> result = new ArrayList<>(uiModels.size());
        List<TLRPC.Message> msgs = new ArrayList<>(uiModels.size());
        Map<Long, TLRPC.Message> bySyntheticId = new HashMap<>();
        Map<Long, CreangerMessageUiModel> uiBySyntheticId = new HashMap<>();
        for (CreangerMessageUiModel m : uiModels) {
            TLRPC.Message msg = createTlrpcMessage(m);
            msgs.add(msg);
            long syntheticId = mapping.getOrCreateSyntheticId(m.id);
            bySyntheticId.put(syntheticId, msg);
            uiBySyntheticId.put(syntheticId, m);
            result.add(new MessageObject(currentAccount, msg, true, false));
        }
        for (int i = 0; i < uiModels.size(); i++) {
            CreangerMessageUiModel m = uiModels.get(i);
            if (m.replyToMessageId == null) {
                continue;
            }
            // The replied-to message must be in the loaded list; a missing,
            // deleted or not-yet-loaded target fails gracefully (no banner).
            Long targetSyntheticId = mapping.getSyntheticId(m.replyToMessageId);
            TLRPC.Message target = targetSyntheticId != null ? bySyntheticId.get(targetSyntheticId) : null;
            if (target == null) {
                continue;
            }
            TLRPC.Message msg = msgs.get(i);
            attachReply(msg, target);
            MessageObject mo = new MessageObject(currentAccount, msg, true, false);
            CreangerMessageUiModel targetUi = uiBySyntheticId.get(targetSyntheticId);
            mo.customReplyName = CreangerMessageMapping.replyDisplayName(
                    targetUi != null ? targetUi.senderId : null,
                    mapping.getOwnerCreangerId(),
                    currentUserDisplayName);
            result.set(i, mo);
        }
        return result;
    }

    /**
     * Fills the existing Telegram reply fields from the replied-to message's
     * synthetic view id. {@code messageController.replyMessage} lets the stock
     * {@link MessageObject} constructor derive {@code replyMessageObject}, so
     * {@code ChatMessageCell} draws the same reply banner (name + quoted text)
     * it draws for MTProto replies — no UI changes.
     */
    private static void attachReply(TLRPC.Message msg, TLRPC.Message replyTarget) {
        TLRPC.TL_messageReplyHeader replyHeader = new TLRPC.TL_messageReplyHeader();
        replyHeader.reply_to_msg_id = replyTarget.id;
        msg.reply_to = replyHeader;
        msg.replyMessage = replyTarget;
        msg.flags = (msg.flags | TLRPC.MESSAGE_FLAG_REPLY);
    }

    /** Converts a single CreangerMessageUiModel to MessageObject */
    public MessageObject toMessageObject(CreangerMessageUiModel m, int currentAccount) {
        return new MessageObject(currentAccount, toTlrpcMessage(m), true, false);
    }

    /**
     * Converts a single {@code CreangerMessageUiModel} to the raw
     * {@code TLRPC.Message} the adapter renders. Kept public so the media/photo
     * mapping is unit-testable without constructing a {@link MessageObject}.
     */
    public TLRPC.Message toTlrpcMessage(CreangerMessageUiModel m) {
        return createTlrpcMessage(m);
    }

    /** Creates a minimal TLRPC.Message populated from CreangerMessageUiModel */
    private TLRPC.Message createTlrpcMessage(CreangerMessageUiModel m) {
        TLRPC.TL_message msg = new TLRPC.TL_message();

        // ---- IDENTITY (synthetic, view-only) ----
        long syntheticId = mapping.getOrCreateSyntheticId(m.id);
        msg.id = (int) syntheticId; // truncated to int for MTProto compatibility

        // ---- OUT/IN (bubble alignment) ----
        msg.out = m.out;

        // ---- SENDER (from_id) ----
        long senderSyntheticId = mapping.getOrCreateSyntheticId(m.senderId);
        TLRPC.TL_peerUser fromPeer = new TLRPC.TL_peerUser();
        fromPeer.user_id = (int) senderSyntheticId;
        msg.from_id = fromPeer;

        // ---- CHAT PEER (peer_id) ----
        TLRPC.TL_peerChannel peer = new TLRPC.TL_peerChannel();
        peer.channel_id = (int) (currentChatSyntheticId != null ? currentChatSyntheticId : mapping.getOrCreateSyntheticId(m.chatId));
        msg.peer_id = peer;

        // ---- DATE (unix timestamp seconds) ----
        msg.date = mapping.parseIso8601ToUnix(m.createdAt);

        // ---- TEXT CONTENT ----
        msg.message = m.content != null ? m.content : "";

        // ---- ENTITIES (formatting) - empty for now ----
        msg.entities = new ArrayList<>();

        // ---- MEDIA (mapped from the messageType + attachment metadata; text
        // messages keep the empty placeholder) ----
        msg.media = buildMedia(m);

        // ---- SEND STATE (pending/failed/sent) ----
        msg.send_state = mapping.getSendState(m.status);
        if (mapping.shouldHaveNegativeId(m.status) && msg.id > 0) {
            msg.id = -Math.abs(msg.id);
        }

        // ---- DELIVERED / READ CHECK TINT ----
        // createStatusDrawableParams draws grey msgOutCheck when unread and blue
        // msgOutCheckRead once read (both are isSent/out); incoming never show.
        msg.unread = mapping.isUnread(m.status, m.out);

        // ---- REPLY MARKUP / FWD / VIA_BOT - null ----
        msg.reply_markup = null;
        msg.fwd_from = null;
        msg.via_bot_id = 0;
        msg.reply_to = null;
        msg.edit_date = 0;
        msg.post_author = null;
        msg.grouped_id = 0;
        msg.reactions = buildReactions(m.reactions);
        msg.restriction_reason = new ArrayList<>();
        msg.ttl_period = 0;
        msg.quick_reply_shortcut_id = 0;
        msg.effect = 0;
        msg.factcheck = null;
        msg.paid_message_stars = 0;
        msg.suggested_post = null;
        msg.schedule_repeat_period = 0;
        msg.summary_from_language = null;
        msg.rich_message = null;

        // ---- FLAGS ----
        msg.flags = mapping.computeFlags(m.out);
        // FLAG_20: has_reactions — only set when the message actually carries
        // reaction data, so the existing Telegram reaction surface stays inert
        // for messages without reactions.
        if (msg.reactions != null) {
            msg.flags = mapping.setFlag(msg.flags, 20, true);
        }
        // FLAG_5: media_unread — set when the message carries media metadata,
        // so MessageObject/ChatMessageCell lanes treat it as a media bubble.
        if (msg.media != null && !(msg.media instanceof TLRPC.TL_messageMediaEmpty)) {
            msg.flags = mapping.setFlag(msg.flags, 5, true);
        }
        msg.flags2 = 0;
        msg.flags2 = mapping.setFlag(msg.flags2, 1, false); // FLAG_1: offline
        msg.flags2 = mapping.setFlag(msg.flags2, 4, false); // FLAG_4: video_processing_pending
        msg.flags2 = mapping.setFlag(msg.flags2, 8, false); // FLAG_8: paid_suggested_post_stars
        msg.flags2 = mapping.setFlag(msg.flags2, 9, false); // FLAG_9: paid_suggested_post_ton

        // ---- VIEWS / FORWARDS / REPLIES ----
        msg.views = 0;
        msg.forwards = 0;
        msg.replies = null;

        // ---- EDIT / TTL ----
        msg.edit_hide = false;
        msg.ttl = 0;

        // Store Creanger UUID in params map for reverse lookup
        msg.params = mapping.buildParams(m);

        return msg;
    }

    /**
     * Maps the Creanger reaction summaries onto Telegram's reaction chips
     * ({@code TLRPC.Message.reactions}, the ONLY surface the existing UI reads)
     * so the stock bubbles/counters render without any UI changes. Only
     * non-custom emoji reactions are carried in this phase — custom emoji
     * require media/document loading that is explicitly out of scope, so those
     * rows are skipped rather than rendered as broken chips.
     *
     * @return a {@code TL_messageReactions}, or null when the message has no
     *         renderable reactions (the field stays null and the flag stays off,
     *         exactly like messages without reactions)
     */
    private static TLRPC.TL_messageReactions buildReactions(List<ReactionSummary> reactions) {
        if (reactions == null || reactions.isEmpty()) {
            return null;
        }
        TLRPC.TL_messageReactions out = new TLRPC.TL_messageReactions();
        out.flags = 0;
        ArrayList<TLRPC.ReactionCount> results = new ArrayList<>();
        int chosenOrder = 0;
        for (ReactionSummary s : reactions) {
            if (s == null || s.reaction == null || s.isCustomEmoji) {
                continue; // no media/custom-emoji rendering in this phase
            }
            TLRPC.TL_reactionCount count = new TLRPC.TL_reactionCount();
            TLRPC.TL_reactionEmoji reaction = new TLRPC.TL_reactionEmoji();
            reaction.emoticon = s.reaction;
            count.reaction = reaction;
            count.count = Math.max(s.count, 1);
            count.chosen = s.chosen;
            count.flags = count.chosen ? 1 : 0; // FLAG_0: chosen
            if (count.chosen) {
                count.chosen_order = chosenOrder++;
            }
            results.add(count);
        }
        if (results.isEmpty()) {
            return null;
        }
        out.results = results;
        return out;
    }

    // ---- media (data-plane metadata → TLRPC.Photo / TLRPC.Document) ----

    /**
     * Maps the messageType + attachment metadata onto the TLRPC media slots the
     * existing chat UI already renders. Images become a {@code TL_messageMediaPhoto}
     * (with a single dimension-carrying photo size, type "m"); video/audio/voice/
     * document become a {@code TL_messageMediaDocument} with the matching
     * {@code DocumentAttribute} mirroring the attachment's duration/dimensions.
     *
     * For IMAGES the photo size additionally carries the HTTPS source URL chosen
     * by {@link CreangerMessageMapping#imageSourceUrl}: the existing photo bubble,
     * progress indicator and {@code PhotoViewer} load it through the stock
     * {@code ImageLoader} HTTP/cache path (Life Media Phase 2). A message without
     * a usable URL renders the media bubble with nothing to load (safe no-op).
     * Video/audio/voice/document remain metadata-only (storage/thumbnail/
     * download is explicitly out of this phase). Text messages (and media rows
     * that somehow lost their attachments) keep the empty placeholder.
     */
    private TLRPC.MessageMedia buildMedia(CreangerMessageUiModel m) {
        if (!m.isMedia() || m.attachments.isEmpty()) {
            return new TLRPC.TL_messageMediaEmpty();
        }
        MediaAttachment att = firstUsableAttachment(m);
        if (att == null) {
            return new TLRPC.TL_messageMediaEmpty();
        }
        String kind = CreangerMessageMapping.mediaRenderKind(m.messageType);
        if ("photo".equals(kind)) {
            return buildPhotoMedia(m, att);
        }
        return buildDocumentMedia(m, att, kind);
    }

    /** First attachment with a usable storage key (metadata-bearing); fallback to position 0. */
    @androidx.annotation.Nullable
    private static MediaAttachment firstUsableAttachment(CreangerMessageUiModel m) {
        MediaAttachment fallback = null;
        for (MediaAttachment a : m.attachments) {
            if (a == null) {
                continue;
            }
            if (fallback == null) {
                fallback = a;
            }
            if (a.storageKey != null && !a.storageKey.isEmpty()) {
                return a;
            }
        }
        return fallback;
    }

    private TLRPC.MessageMedia buildPhotoMedia(CreangerMessageUiModel m, MediaAttachment att) {
        TLRPC.TL_messageMediaPhoto media = new TLRPC.TL_messageMediaPhoto();
        TLRPC.TL_photo photo = new TLRPC.TL_photo();
        photo.id = mapping.getOrCreateSyntheticId(att.id != null ? att.id : att.storageKey);
        photo.access_hash = 0;
        photo.file_reference = new byte[0];
        photo.date = mapping.parseIso8601ToUnix(m.createdAt);
        photo.dc_id = 0;
        photo.flags = 0;
        photo.sizes = new ArrayList<>();
        // Full Telegram-style s/m/x/y ladder: same HTTPS source URL on every
        // rung (ImageLoader HTTP/cache path), scaled layout dims per rung so
        // FileLoader.getClosestPhotoSizeWithSize picks the right rung per
        // density. Null url = safe no-op (nothing to load, no MTProto probe).
        for (CreangerMessageMapping.PhotoSizeSpec spec : CreangerMessageMapping.photoSizeLadder(att)) {
            TLRPC.TL_photoSize size = new TLRPC.TL_photoSize();
            size.type = spec.type;
            size.w = spec.w;
            size.h = spec.h;
            size.size = spec.size;
            size.url = spec.url;
            photo.sizes.add(size);
        }
        media.photo = photo;
        media.flags = 1; // FLAG_0: photo present
        return media;
    }

    private TLRPC.MessageMedia buildDocumentMedia(CreangerMessageUiModel m, MediaAttachment att, String kind) {
        TLRPC.TL_messageMediaDocument media = new TLRPC.TL_messageMediaDocument();
        TLRPC.TL_document document = new TLRPC.TL_document();
        document.id = mapping.getOrCreateSyntheticId(att.id != null ? att.id : att.storageKey);
        document.access_hash = 0;
        document.file_reference = new byte[0];
        document.date = mapping.parseIso8601ToUnix(m.createdAt);
        document.mime_type = att.mimeType;
        document.size = att.sizeBytes;
        document.dc_id = 0;
        document.thumbs = new ArrayList<>();
        document.video_thumbs = new ArrayList<>();
        document.attributes = new ArrayList<>();

        int durationSeconds = att.duration != null ? att.duration / 1000 : 0;
        if ("video".equals(kind)) {
            TLRPC.TL_documentAttributeVideo video = new TLRPC.TL_documentAttributeVideo();
            video.duration = durationSeconds;
            video.w = intOrZero(att.width);
            video.h = intOrZero(att.height);
            video.round_message = false;
            video.supports_streaming = true;
            video.nosound = false;
            video.flags = 2; // FLAG_1: supports_streaming
            document.attributes.add(video);
            media.video = true;
            // Poster frame for the video bubble. Confirmed videos carry the
            // server-side thumbnail URL; pending (unsent) rows carry a local
            // poster still (image path) so the preview renders immediately.
            // The URL rides on a synthetic photo size and is routed through the
            // ImageLoader HTTP/path seam in ImageLocation.getForDocument.
            String posterSource = att.thumbnailUrl != null && !att.thumbnailUrl.isEmpty()
                    ? att.thumbnailUrl : att.localPath;
            if (posterSource != null && !posterSource.isEmpty()) {
                TLRPC.TL_photoSize poster = new TLRPC.TL_photoSize();
                poster.type = "m";
                poster.w = intOrZero(att.width);
                poster.h = intOrZero(att.height);
                poster.size = (int) Math.min(att.sizeBytes, Integer.MAX_VALUE);
                poster.url = posterSource;
                document.thumbs.add(poster);
            }
        } else if ("audio".equals(kind) || "voice".equals(kind)) {
            TLRPC.TL_documentAttributeAudio audio = new TLRPC.TL_documentAttributeAudio();
            audio.duration = durationSeconds;
            audio.voice = "voice".equals(kind);
            audio.flags = audio.voice ? 1024 : 0; // FLAG_10: voice
            document.attributes.add(audio);
            media.voice = audio.voice;
        } else {
            TLRPC.TL_documentAttributeFilename fileName = new TLRPC.TL_documentAttributeFilename();
            fileName.file_name = att.storageKey;
            document.attributes.add(fileName);
        }

        media.document = document;
        media.flags = 1; // FLAG_0: document present
        return media;
    }

    private static int intOrZero(@Nullable Integer value) {
        return value != null ? value : 0;
    }

    // ---- Delegation to mapping core ----

    /** Reverse lookup: synthetic ID → Creanger UUID */
    public String getCreangerUuid(long syntheticId) {
        return mapping.getCreangerUuid(syntheticId);
    }

    /** Reverse lookup: Creanger UUID → synthetic ID */
    public Long getSyntheticId(String creangerUuid) {
        return mapping.getSyntheticId(creangerUuid);
    }

    /** Expose mapping for testing */
    CreangerMessageMapping getMapping() {
        return mapping;
    }
}