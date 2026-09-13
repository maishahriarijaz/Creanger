package com.creanger.app.network.model;

import com.creanger.app.tgnet.TLRPC;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Backend-neutral message model.
 * Maps to/from TLRPC.Message for UI compatibility.
 */
public class MessageModel {

    public enum State {
        PENDING,      // Created locally, not sent to backend
        SENDING,      // Sent to backend, awaiting ack
        SENT,         // Acknowledged by backend
        DELIVERED,    // Delivered to recipient (if supported)
        READ,         // Read by recipient (if supported)
        FAILED        // Send failed
    }

    public enum MediaType {
        NONE,
        PHOTO,
        VIDEO,
        AUDIO,
        VOICE,
        DOCUMENT,
        STICKER,
        GIF,
        CONTACT,
        LOCATION,
        POLL
    }

    @NonNull
    public final String id;                    // Backend message ID (or local temp ID for pending)
    @NonNull
    public final String chatId;                // Chat/conversation ID
    @NonNull
    public final String senderId;              // Sender user ID
    @Nullable
    public final String replyToMessageId;      // Reply target
    @NonNull
    public final String text;                  // Message text (can be empty for media)
    @Nullable
    public final List<EntityModel> entities;   // Text entities (bold, links, mentions)
    @Nullable
    public final MediaModel media;             // Media attachment
    @NonNull
    public final State state;
    public final long date;                    // Message timestamp (server time)
    public final long localDate;               // Local creation timestamp
    public final int views;                    // View count (for channels)
    public final int forwards;                 // Forward count
    @Nullable
    public final String forwardFromChatId;     // Forward source chat
    @Nullable
    public final String forwardFromUserId;     // Forward source user
    public final boolean isEdited;             // Edited flag
    public final long editedAt;                // Edit timestamp
    public final boolean isPinned;             // Pinned in chat
    public final boolean isSilent;             // Silent notification
    public final boolean isOutgoing;           // Sent by current user
    @Nullable
    public final Map<String, Object> extra;    // Backend-specific extra fields

    public MessageModel(@NonNull Builder builder) {
        this.id = builder.id;
        this.chatId = builder.chatId;
        this.senderId = builder.senderId;
        this.replyToMessageId = builder.replyToMessageId;
        this.text = builder.text != null ? builder.text : "";
        this.entities = builder.entities != null ? Collections.unmodifiableList(builder.entities) : Collections.emptyList();
        this.media = builder.media;
        this.state = builder.state;
        this.date = builder.date;
        this.localDate = builder.localDate;
        this.views = builder.views;
        this.forwards = builder.forwards;
        this.forwardFromChatId = builder.forwardFromChatId;
        this.forwardFromUserId = builder.forwardFromUserId;
        this.isEdited = builder.isEdited;
        this.editedAt = builder.editedAt;
        this.isPinned = builder.isPinned;
        this.isSilent = builder.isSilent;
        this.isOutgoing = builder.isOutgoing;
        this.extra = builder.extra != null ? Collections.unmodifiableMap(builder.extra) : Collections.emptyMap();
    }

    public boolean isPending() {
        return state == State.PENDING || state == State.SENDING;
    }

    public boolean isFailed() {
        return state == State.FAILED;
    }

    public boolean hasMedia() {
        return media != null;
    }

    public static class Builder {
        @NonNull String id;
        @NonNull String chatId;
        @NonNull String senderId;
        @Nullable String replyToMessageId;
        @NonNull String text = "";
        @Nullable List<EntityModel> entities;
        @Nullable MediaModel media;
        State state = State.PENDING;
        long date = 0;
        long localDate = System.currentTimeMillis();
        int views = 0;
        int forwards = 0;
        @Nullable String forwardFromChatId;
        @Nullable String forwardFromUserId;
        boolean isEdited = false;
        long editedAt = 0;
        boolean isPinned = false;
        boolean isSilent = false;
        boolean isOutgoing = false;
        @Nullable Map<String, Object> extra;

        public Builder(@NonNull String id, @NonNull String chatId, @NonNull String senderId) {
            this.id = id;
            this.chatId = chatId;
            this.senderId = senderId;
        }

        public Builder replyToMessageId(@Nullable String id) { this.replyToMessageId = id; return this; }
        public Builder text(@NonNull String text) { this.text = text; return this; }
        public Builder entities(@Nullable List<EntityModel> entities) { this.entities = entities; return this; }
        public Builder media(@Nullable MediaModel media) { this.media = media; return this; }
        public Builder state(@NonNull State state) { this.state = state; return this; }
        public Builder date(long date) { this.date = date; return this; }
        public Builder localDate(long localDate) { this.localDate = localDate; return this; }
        public Builder views(int views) { this.views = views; return this; }
        public Builder forwards(int forwards) { this.forwards = forwards; return this; }
        public Builder forwardFromChatId(@Nullable String id) { this.forwardFromChatId = id; return this; }
        public Builder forwardFromUserId(@Nullable String id) { this.forwardFromUserId = id; return this; }
        public Builder edited(boolean edited, long editedAt) { this.isEdited = edited; this.editedAt = editedAt; return this; }
        public Builder pinned(boolean pinned) { this.isPinned = pinned; return this; }
        public Builder silent(boolean silent) { this.isSilent = silent; return this; }
        public Builder outgoing(boolean outgoing) { this.isOutgoing = outgoing; return this; }
        public Builder extra(@Nullable Map<String, Object> extra) { this.extra = extra; return this; }

        public MessageModel build() {
            return new MessageModel(this);
        }
    }

    @Override
    @NonNull
    public String toString() {
        return "MessageModel{id='" + id + "', chatId='" + chatId + "', state=" + state + ", text='" + (text.length() > 30 ? text.substring(0, 30) + "..." : text) + "'}";
    }
}