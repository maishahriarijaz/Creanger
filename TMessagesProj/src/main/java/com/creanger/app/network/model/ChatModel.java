package com.creanger.app.network.model;

import com.creanger.app.tgnet.TLRPC;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Backend-neutral chat/conversation model.
 * Maps to/from TLRPC.Chat for UI compatibility.
 */
public class ChatModel {

    public enum Type {
        PRIVATE,      // 1-on-1 chat
        GROUP,        // Group chat
        CHANNEL,      // Broadcast channel
        SUPERGROUP,   // Large group (Telegram-style)
        FORUM         // Forum-style with topics
    }

    @NonNull
    public final String id;                    // Backend chat ID
    @NonNull
    public final Type type;
    @Nullable
    public final String title;                 // Chat title (for groups/channels)
    @Nullable
    public final String username;              // Public username (for channels)
    @Nullable
    public final String description;           // Description/bio
    @Nullable
    public final String avatarUrl;             // Chat photo URL
    @Nullable
    public final String inviteLink;            // Invite link
    public final long createdAt;               // Creation timestamp
    public final int participantCount;         // Member count
    public final boolean isVerified;           // Verified badge
    public final boolean isRestricted;         // Content restrictions
    public final boolean isScam;               // Scam flag
    public final boolean isFake;               // Fake flag
    @Nullable
    public final MessageModel lastMessage;     // Last message preview
    public final int unreadCount;              // Unread message count
    public final int unreadMentionsCount;      // Unread mentions count
    public final boolean isPinned;             // Pinned in chat list
    public final boolean isMuted;              // Muted notifications
    public final long mutedUntil;              // Mute expiry timestamp (0 = forever)
    @Nullable
    public final Map<String, Object> extra;    // Backend-specific extra fields

    public ChatModel(@NonNull Builder builder) {
        this.id = builder.id;
        this.type = builder.type;
        this.title = builder.title;
        this.username = builder.username;
        this.description = builder.description;
        this.avatarUrl = builder.avatarUrl;
        this.inviteLink = builder.inviteLink;
        this.createdAt = builder.createdAt;
        this.participantCount = builder.participantCount;
        this.isVerified = builder.isVerified;
        this.isRestricted = builder.isRestricted;
        this.isScam = builder.isScam;
        this.isFake = builder.isFake;
        this.lastMessage = builder.lastMessage;
        this.unreadCount = builder.unreadCount;
        this.unreadMentionsCount = builder.unreadMentionsCount;
        this.isPinned = builder.isPinned;
        this.isMuted = builder.isMuted;
        this.mutedUntil = builder.mutedUntil;
        this.extra = builder.extra != null ? Collections.unmodifiableMap(builder.extra) : Collections.emptyMap();
    }

    public boolean isGroup() {
        return type == Type.GROUP || type == Type.SUPERGROUP || type == Type.FORUM;
    }

    public boolean isChannel() {
        return type == Type.CHANNEL;
    }

    public boolean isPrivate() {
        return type == Type.PRIVATE;
    }

    public boolean isMuteActive() {
        return isMuted && (mutedUntil == 0 || mutedUntil > System.currentTimeMillis());
    }

    public static class Builder {
        @NonNull String id;
        @NonNull Type type;
        @Nullable String title;
        @Nullable String username;
        @Nullable String description;
        @Nullable String avatarUrl;
        @Nullable String inviteLink;
        long createdAt = 0;
        int participantCount = 0;
        boolean isVerified = false;
        boolean isRestricted = false;
        boolean isScam = false;
        boolean isFake = false;
        @Nullable MessageModel lastMessage;
        int unreadCount = 0;
        int unreadMentionsCount = 0;
        boolean isPinned = false;
        boolean isMuted = false;
        long mutedUntil = 0;
        @Nullable Map<String, Object> extra;

        public Builder(@NonNull String id, @NonNull Type type) {
            this.id = id;
            this.type = type;
        }

        public Builder title(@Nullable String title) { this.title = title; return this; }
        public Builder username(@Nullable String username) { this.username = username; return this; }
        public Builder description(@Nullable String description) { this.description = description; return this; }
        public Builder avatarUrl(@Nullable String avatarUrl) { this.avatarUrl = avatarUrl; return this; }
        public Builder inviteLink(@Nullable String inviteLink) { this.inviteLink = inviteLink; return this; }
        public Builder createdAt(long createdAt) { this.createdAt = createdAt; return this; }
        public Builder participantCount(int participantCount) { this.participantCount = participantCount; return this; }
        public Builder verified(boolean verified) { this.isVerified = verified; return this; }
        public Builder restricted(boolean restricted) { this.isRestricted = restricted; return this; }
        public Builder scam(boolean scam) { this.isScam = scam; return this; }
        public Builder fake(boolean fake) { this.isFake = fake; return this; }
        public Builder lastMessage(@Nullable MessageModel lastMessage) { this.lastMessage = lastMessage; return this; }
        public Builder unreadCount(int unreadCount) { this.unreadCount = unreadCount; return this; }
        public Builder unreadMentionsCount(int unreadMentionsCount) { this.unreadMentionsCount = unreadMentionsCount; return this; }
        public Builder pinned(boolean pinned) { this.isPinned = pinned; return this; }
        public Builder muted(boolean muted, long mutedUntil) { this.isMuted = muted; this.mutedUntil = mutedUntil; return this; }
        public Builder extra(@Nullable Map<String, Object> extra) { this.extra = extra; return this; }

        public ChatModel build() {
            return new ChatModel(this);
        }
    }

    @Override
    @NonNull
    public String toString() {
        return "ChatModel{id='" + id + "', type=" + type + ", title='" + title + "', unread=" + unreadCount + '}';
    }
}