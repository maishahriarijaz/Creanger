package com.creanger.app.network.model;

import com.creanger.app.tgnet.TLRPC;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Backend-neutral user model.
 * Maps to/from TLRPC.User for UI compatibility.
 */
public class UserModel {

    @NonNull
    public final String id;                    // Backend user ID (string for flexibility)
    @NonNull
    public final String username;              // Unique username
    @Nullable
    public final String displayName;           // Display name
    @Nullable
    public final String phoneNumber;           // Phone (if provided)
    @Nullable
    public final String email;                 // Email (if provided)
    @Nullable
    public final String avatarUrl;             // Profile photo URL
    @Nullable
    public final String bio;                   // About/bio
    public final boolean isVerified;           // Verified badge
    public final boolean isPremium;            // Premium status
    public final boolean isBot;                // Bot account
    public final boolean isDeleted;            // Deleted account
    public final boolean isScam;               // Scam flag
    public final boolean isFake;               // Fake flag
    public final long createdAt;               // Account creation timestamp
    @Nullable
    public final String lastSeen;              // Last seen (ISO8601 or "online")
    @Nullable
    public final Map<String, Object> extra;    // Backend-specific extra fields

    public UserModel(@NonNull Builder builder) {
        this.id = builder.id;
        this.username = builder.username;
        this.displayName = builder.displayName;
        this.phoneNumber = builder.phoneNumber;
        this.email = builder.email;
        this.avatarUrl = builder.avatarUrl;
        this.bio = builder.bio;
        this.isVerified = builder.isVerified;
        this.isPremium = builder.isPremium;
        this.isBot = builder.isBot;
        this.isDeleted = builder.isDeleted;
        this.isScam = builder.isScam;
        this.isFake = builder.isFake;
        this.createdAt = builder.createdAt;
        this.lastSeen = builder.lastSeen;
        this.extra = builder.extra != null ? Collections.unmodifiableMap(builder.extra) : Collections.emptyMap();
    }

    public boolean isOnline() {
        return "online".equalsIgnoreCase(lastSeen);
    }

    public long getLastSeenTime() {
        if (lastSeen == null || isOnline()) return 0;
        try {
            return java.time.Instant.parse(lastSeen).toEpochMilli();
        } catch (Exception e) {
            return 0;
        }
    }

    public static class Builder {
        @NonNull String id;
        @NonNull String username;
        @Nullable String displayName;
        @Nullable String phoneNumber;
        @Nullable String email;
        @Nullable String avatarUrl;
        @Nullable String bio;
        boolean isVerified = false;
        boolean isPremium = false;
        boolean isBot = false;
        boolean isDeleted = false;
        boolean isScam = false;
        boolean isFake = false;
        long createdAt = 0;
        @Nullable String lastSeen;
        @Nullable Map<String, Object> extra;

        public Builder(@NonNull String id, @NonNull String username) {
            this.id = id;
            this.username = username;
        }

        public Builder displayName(@Nullable String displayName) { this.displayName = displayName; return this; }
        public Builder phoneNumber(@Nullable String phoneNumber) { this.phoneNumber = phoneNumber; return this; }
        public Builder email(@Nullable String email) { this.email = email; return this; }
        public Builder avatarUrl(@Nullable String avatarUrl) { this.avatarUrl = avatarUrl; return this; }
        public Builder bio(@Nullable String bio) { this.bio = bio; return this; }
        public Builder verified(boolean verified) { this.isVerified = verified; return this; }
        public Builder premium(boolean premium) { this.isPremium = premium; return this; }
        public Builder bot(boolean bot) { this.isBot = bot; return this; }
        public Builder deleted(boolean deleted) { this.isDeleted = deleted; return this; }
        public Builder scam(boolean scam) { this.isScam = scam; return this; }
        public Builder fake(boolean fake) { this.isFake = fake; return this; }
        public Builder createdAt(long createdAt) { this.createdAt = createdAt; return this; }
        public Builder lastSeen(@Nullable String lastSeen) { this.lastSeen = lastSeen; return this; }
        public Builder extra(@Nullable Map<String, Object> extra) { this.extra = extra; return this; }

        public UserModel build() {
            return new UserModel(this);
        }
    }

    @Override
    @NonNull
    public String toString() {
        return "UserModel{id='" + id + "', username='" + username + "', displayName='" + displayName + "', isPremium=" + isPremium + '}';
    }
}