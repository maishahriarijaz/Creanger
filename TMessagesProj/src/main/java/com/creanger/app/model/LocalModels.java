package com.creanger.app.model;

import com.creanger.app.tgnet.TLRPC;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Canonical local/application models for preserved Telegram UI.
 * Plain data, no TLRPC inheritance, no constructor IDs, no TL serialization, no RPC.
 * UI adapters translate these → existing rendering code.
 */
public final class LocalModels {
    private LocalModels() {}

    /** Peer discriminator — replaces TLRPC.Peer / PeerUser/PeerChat/PeerChannel */
    public static final class LocalPeer {
        public enum Kind { USER, CHAT, CHANNEL }
        public final Kind kind;
        public final String id; // UUID string (Creanger)

        public LocalPeer(Kind kind, String id) { this.kind = kind; this.id = id; }

        public static LocalPeer user(String userId) { return new LocalPeer(Kind.USER, userId); }
        public static LocalPeer chat(String chatId) { return new LocalPeer(Kind.CHAT, chatId); }
        public static LocalPeer channel(String channelId) { return new LocalPeer(Kind.CHANNEL, channelId); }
    }

    /** Dialog — replaces TLRPC.Dialog (peer + top_message + unread) */
    public static final class LocalDialog {
        public final String id; // same as chatId
        public final LocalPeer peer;
        @Nullable public final String topMessageId;
        public final int unreadCount;
        public final boolean isPinned;
        public final int lastMessageDate; // epoch seconds
        public final int folderId;

        public LocalDialog(String id, LocalPeer peer, @Nullable String topMessageId, int unreadCount, boolean isPinned, int lastMessageDate, int folderId) {
            this.id = id; this.peer = peer; this.topMessageId = topMessageId; this.unreadCount = unreadCount; this.isPinned = isPinned; this.lastMessageDate = lastMessageDate; this.folderId = folderId;
        }
    }

    /** Photo size for renderer — only w/h/url needed, no volume_id/secret/dc */
    public static final class LocalPhotoSize {
        public final String type; // s, m, x, y
        public final int w;
        public final int h;
        @Nullable public final String url;
        public final int size;

        public LocalPhotoSize(String type, int w, int h, @Nullable String url, int size) {
            this.type = type; this.w = w; this.h = h; this.url = url; this.size = size;
        }
    }

    /** Message media — renderer-facing */
    public static final class LocalMessageMedia {
        public enum Kind { EMPTY, PHOTO, DOCUMENT, WEBPAGE, POLL, CONTACT, GEO, STORY }
        public final Kind kind;
        @Nullable public final List<LocalPhotoSize> photoSizes;
        @Nullable public final LocalDocument document;
        @Nullable public final LocalWebPage webpage;
        @Nullable public final LocalPoll poll;

        private LocalMessageMedia(Kind kind, @Nullable List<LocalPhotoSize> photoSizes, @Nullable LocalDocument document, @Nullable LocalWebPage webpage, @Nullable LocalPoll poll) {
            this.kind = kind; this.photoSizes = photoSizes; this.document = document; this.webpage = webpage; this.poll = poll;
        }
        public static LocalMessageMedia empty() { return new LocalMessageMedia(Kind.EMPTY, null, null, null, null); }
        public static LocalMessageMedia photo(List<LocalPhotoSize> sizes) { return new LocalMessageMedia(Kind.PHOTO, sizes, null, null, null); }
        public static LocalMessageMedia document(LocalDocument doc) { return new LocalMessageMedia(Kind.DOCUMENT, null, doc, null, null); }
        public static LocalMessageMedia webpage(LocalWebPage wp) { return new LocalMessageMedia(Kind.WEBPAGE, null, null, wp, null); }
        public static LocalMessageMedia poll(LocalPoll p) { return new LocalMessageMedia(Kind.POLL, null, null, null, p); }
    }

    public static final class LocalDocument {
        public final String id;
        @Nullable public final String mimeType;
        public final long size;
        public final int w;
        public final int h;
        public final int duration; // seconds
        @Nullable public final String publicUrl;
        @Nullable public final String thumbnailUrl;

        public LocalDocument(String id, @Nullable String mimeType, long size, int w, int h, int duration, @Nullable String publicUrl, @Nullable String thumbnailUrl) {
            this.id = id; this.mimeType = mimeType; this.size = size; this.w = w; this.h = h; this.duration = duration; this.publicUrl = publicUrl; this.thumbnailUrl = thumbnailUrl;
        }
    }

    public static final class LocalWebPage {
        @Nullable public final String url;
        @Nullable public final String displayUrl;
        @Nullable public final String title;
        @Nullable public final String description;
        @Nullable public final String siteName;
        @Nullable public final String photoUrl;
        public final int duration;

        public LocalWebPage(@Nullable String url, @Nullable String displayUrl, @Nullable String title, @Nullable String description, @Nullable String siteName, @Nullable String photoUrl, int duration) {
            this.url = url; this.displayUrl = displayUrl; this.title = title; this.description = description; this.siteName = siteName; this.photoUrl = photoUrl; this.duration = duration;
        }
    }

    public static final class LocalReplyMarkup {
        public final boolean isInline;
        public final List<List<LocalKeyboardButton>> rows;

        public LocalReplyMarkup(boolean isInline, List<List<LocalKeyboardButton>> rows) {
            this.isInline = isInline; this.rows = rows != null ? rows : Collections.emptyList();
        }
    }

    public static final class LocalKeyboardButton {
        public final String text;
        @Nullable public final String url;

        public LocalKeyboardButton(String text, @Nullable String url) { this.text = text; this.url = url; }
    }

    public static final class LocalPoll {
        public final String id;
        public final String question;
        public final List<LocalPollAnswer> answers;
        public final boolean isClosed;
        public final boolean canVote;
        public final int totalVoters;

        public LocalPoll(String id, String question, List<LocalPollAnswer> answers, boolean isClosed, boolean canVote, int totalVoters) {
            this.id = id; this.question = question; this.answers = answers != null ? answers : new ArrayList<>(); this.isClosed = isClosed; this.canVote = canVote; this.totalVoters = totalVoters;
        }
    }

    public static final class LocalPollAnswer {
        public final String text;
        public final String option; // opaque option id
        public final int votersCount;
        public final boolean chosen;

        public LocalPollAnswer(String text, String option, int votersCount, boolean chosen) {
            this.text = text; this.option = option; this.votersCount = votersCount; this.chosen = chosen;
        }
    }

    public static final class LocalReaction {
        public final String emoji;
        public final int count;
        public final boolean chosen;
        @Nullable public final String customEmojiId;

        public LocalReaction(String emoji, int count, boolean chosen, @Nullable String customEmojiId) {
            this.emoji = emoji; this.count = count; this.chosen = chosen; this.customEmojiId = customEmojiId;
        }
    }

    public static final class LocalMediaArea {
        public enum Kind { CHANNEL_POST, VENUE, REACTION }
        public final Kind kind;
        @Nullable public final String channelId;
        public final int msgId;
        @Nullable public final Double lat;
        @Nullable public final Double lng;
        @Nullable public final String title;

        private LocalMediaArea(Kind kind, @Nullable String channelId, int msgId, @Nullable Double lat, @Nullable Double lng, @Nullable String title) {
            this.kind = kind; this.channelId = channelId; this.msgId = msgId; this.lat = lat; this.lng = lng; this.title = title;
        }
        public static LocalMediaArea channelPost(String channelId, int msgId) { return new LocalMediaArea(Kind.CHANNEL_POST, channelId, msgId, null, null, null); }
        public static LocalMediaArea venue(double lat, double lng, String title) { return new LocalMediaArea(Kind.VENUE, null, 0, lat, lng, title); }
    }

    public static final class LocalStickerSet {
        public final String id;
        public final String shortName;
        public final String title;
        public final int count;
        @Nullable public final java.util.ArrayList<LocalStickerItem> documents;
        @Nullable public final LocalStickerItem cover;
        @Nullable public final java.util.ArrayList<LocalStickerItem> covers;

        public LocalStickerSet(String id, String shortName, String title, int count) {
            this(id, shortName, title, count, null, null, null);
        }

        public LocalStickerSet(String id, String shortName, String title, int count,
                               @Nullable java.util.ArrayList<LocalStickerItem> documents,
                               @Nullable LocalStickerItem cover,
                               @Nullable java.util.ArrayList<LocalStickerItem> covers) {
            this.id = id; this.shortName = shortName; this.title = title; this.count = count;
            this.documents = documents; this.cover = cover; this.covers = covers;
        }
    }

    public static final class LocalTodoItem {
        public final String id;
        public final String title;
        public final boolean isCompleted;
        @Nullable public final String assigneeId;

        public LocalTodoItem(String id, String title, boolean isCompleted, @Nullable String assigneeId) {
            this.id = id; this.title = title; this.isCompleted = isCompleted; this.assigneeId = assigneeId;
        }
    }

    public static final class LocalStory {
        public final String id;
        public final String peerId;
        @Nullable public final String mediaUrl;
        public final int date;
        public final boolean isPinned;

        public LocalStory(String id, String peerId, @Nullable String mediaUrl, int date, boolean isPinned) {
            this.id = id; this.peerId = peerId; this.mediaUrl = mediaUrl; this.date = date; this.isPinned = isPinned;
        }
    }

    public static final class LocalVideoSize {
        public final String type;
        public final int w;
        public final int h;
        public final int size;

        public LocalVideoSize(String type, int w, int h, int size) { this.type = type; this.w = w; this.h = h; this.size = size; }
    }

    /** Individual sticker/item in a sticker set */
    public static final class LocalStickerItem {
        public final String id;
        public final String documentId;
        public final String type; // "regular", "mask", "animated", "video"
        public final int w;
        public final int h;
        @Nullable public final String url;
        public final int size;
        public final List<LocalStickerItemAttribute> attributes;

        public LocalStickerItem(String id, String documentId, String type, int w, int h, @Nullable String url, int size) {
            this(id, documentId, type, w, h, url, size, Collections.emptyList());
        }

        public LocalStickerItem(String id, String documentId, String type, int w, int h, @Nullable String url, int size, List<LocalStickerItemAttribute> attributes) {
            this.id = id; this.documentId = documentId; this.type = type; this.w = w; this.h = h; this.url = url; this.size = size;
            this.attributes = attributes != null ? attributes : Collections.emptyList();
        }
    }

    /** Renderer-facing sticker metadata attached to a local sticker item. */
    public static final class LocalStickerItemAttribute {
        @Nullable public final LocalStickerSet stickerset;
        public final int w;
        public final int h;

        public LocalStickerItemAttribute(@Nullable LocalStickerSet stickerset, int w, int h) {
            this.stickerset = stickerset;
            this.w = w;
            this.h = h;
        }
    }

    /** Lightweight featured-sticker entry; network identity stays outside this model. */
    public static final class LocalStickerSetCovered {
        public final LocalStickerSet set;
        @Nullable public final List<LocalStickerItem> covers;
        @Nullable public final List<LocalStickerItem> documents;

        public LocalStickerSetCovered(LocalStickerSet set, @Nullable List<LocalStickerItem> covers, @Nullable List<LocalStickerItem> documents) {
            this.set = set;
            this.covers = covers;
            this.documents = documents;
        }
    }

    /** Sticker set type discriminator */
    public static final class LocalStickerSetType {
        public enum Kind { FULL_COVERED, NO_COVERED, DOCUMENTS_ONLY }
        public final Kind kind;

        private LocalStickerSetType(Kind kind) { this.kind = kind; }
        public static LocalStickerSetType fullCovered() { return new LocalStickerSetType(Kind.FULL_COVERED); }
        public static LocalStickerSetType noCovered() { return new LocalStickerSetType(Kind.NO_COVERED); }
        public static LocalStickerSetType documentsOnly() { return new LocalStickerSetType(Kind.DOCUMENTS_ONLY); }
    }

    /** Emoji status replacement */
    public static final class LocalEmojiStatus {
        public final String emoji;
        public final int date;

        public LocalEmojiStatus(String emoji, int date) { this.emoji = emoji; this.date = date; }
    }
}
