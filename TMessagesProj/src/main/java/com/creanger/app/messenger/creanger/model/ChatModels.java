package com.creanger.app.messenger.creanger.model;

import androidx.annotation.Nullable;

/**
 * Chat foundation models mirrored from the Creanger Supabase schema
 * ({@code supabase/migrations/003_chat_model.sql} + 016/021). The PostgREST
 * REST API is the data plane: rows arrive as raw JSON objects whose snake_case
 * columns map 1:1 onto the fields below.
 *
 * Identifiers are Creanger UUID strings, deliberately kept separate from
 * Telegram Long IDs (no TLRPC types leak in here).
 */
public final class ChatModels {

    private ChatModels() {
    }

    public static final class ChatType {
        public static final String DIRECT = "direct";
        public static final String GROUP = "group";
        public static final String CHANNEL = "channel";

        private ChatType() {
        }
    }

    public static final class ChatMemberRole {
        public static final String MEMBER = "member";
        public static final String ADMIN = "admin";
        public static final String OWNER = "owner";

        private ChatMemberRole() {
        }
    }

    /**
     * Root conversation entity ({@code chats}). All id-bearing fields are
     * UUIDs. For direct chats {@code title} is null by schema constraint.
     */
    public static final class CreangerChat {
        public final String id;
        public final String type; // ChatType.DIRECT | GROUP | CHANNEL
        @Nullable
        public final String title;
        @Nullable
        public final String username;
        @Nullable
        public final String description;
        @Nullable
        public final String avatarMediaId;
        @Nullable
        public final String ownerId;
        public final boolean isVerified;
        public final boolean isPublic;
        public final boolean isArchived;
        public final String createdAt;
        public final String updatedAt;
        @Nullable
        public final String deletedAt;

        public CreangerChat(String id, String type, @Nullable String title, @Nullable String username,
                            @Nullable String description, @Nullable String avatarMediaId, @Nullable String ownerId,
                            boolean isVerified, boolean isPublic, boolean isArchived,
                            String createdAt, String updatedAt, @Nullable String deletedAt) {
            this.id = id;
            this.type = type;
            this.title = title;
            this.username = username;
            this.description = description;
            this.avatarMediaId = avatarMediaId;
            this.ownerId = ownerId;
            this.isVerified = isVerified;
            this.isPublic = isPublic;
            this.isArchived = isArchived;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.deletedAt = deletedAt;
        }

        public boolean isDirect() {
            return ChatType.DIRECT.equals(type);
        }
    }

    /**
     * Chat membership record ({@code chat_members}) with role and per-member
     * state. {@code leftAt == null} means the member is currently active.
     */
    public static final class ChatMember {
        public final String id;
        public final String chatId;
        public final String userId;
        public final String role; // ChatMemberRole.MEMBER | ADMIN | OWNER
        public final String joinedAt;
        @Nullable
        public final String leftAt;
        @Nullable
        public final String mutedUntil;
        @Nullable
        public final Integer pinnedPosition;
        @Nullable
        public final String lastReadAt;

        public ChatMember(String id, String chatId, String userId, String role, String joinedAt,
                          @Nullable String leftAt, @Nullable String mutedUntil,
                          @Nullable Integer pinnedPosition, @Nullable String lastReadAt) {
            this.id = id;
            this.chatId = chatId;
            this.userId = userId;
            this.role = role;
            this.joinedAt = joinedAt;
            this.leftAt = leftAt;
            this.mutedUntil = mutedUntil;
            this.pinnedPosition = pinnedPosition;
            this.lastReadAt = lastReadAt;
        }
    }

    /**
     * Presence row ({@code user_presence}, migration 008). Mirrors the
     * {@code presence_status} enum values exactly - no invented states.
     */
    public static final class UserPresence {
        public static final String ONLINE = "online";
        public static final String AWAY = "away";
        public static final String OFFLINE = "offline";
        public static final String INVISIBLE = "invisible";

        private UserPresence() {
        }
    }
}
