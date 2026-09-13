package com.creanger.app.network.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Text entity model (bold, italic, link, mention, etc.)
 */
public class EntityModel {

    public enum Type {
        BOLD,
        ITALIC,
        UNDERLINE,
        STRIKETHROUGH,
        CODE,
        PRE,
        TEXT_LINK,
        TEXT_MENTION,
        HASHTAG,
        CASHTAG,
        BOT_COMMAND,
        URL,
        EMAIL,
        PHONE_NUMBER,
        CUSTOM_EMOJI
    }

    @NonNull
    public final Type type;
    public final int offset;          // Start position in text
    public final int length;          // Length of entity
    @Nullable
    public final String url;          // For TEXT_LINK
    @Nullable
    public final String userId;       // For TEXT_MENTION
    @Nullable
    public final String language;     // For PRE (code language)
    @Nullable
    public final String customEmojiId; // For CUSTOM_EMOJI

    public EntityModel(@NonNull Builder builder) {
        this.type = builder.type;
        this.offset = builder.offset;
        this.length = builder.length;
        this.url = builder.url;
        this.userId = builder.userId;
        this.language = builder.language;
        this.customEmojiId = builder.customEmojiId;
    }

    public static class Builder {
        @NonNull Type type;
        int offset = 0;
        int length = 0;
        @Nullable String url;
        @Nullable String userId;
        @Nullable String language;
        @Nullable String customEmojiId;

        public Builder(@NonNull Type type) {
            this.type = type;
        }

        public Builder offset(int offset) { this.offset = offset; return this; }
        public Builder length(int length) { this.length = length; return this; }
        public Builder url(@Nullable String url) { this.url = url; return this; }
        public Builder userId(@Nullable String userId) { this.userId = userId; return this; }
        public Builder language(@Nullable String language) { this.language = language; return this; }
        public Builder customEmojiId(@Nullable String id) { this.customEmojiId = id; return this; }

        public EntityModel build() {
            return new EntityModel(this);
        }
    }

    public static EntityModel bold(int offset, int length) {
        return new Builder(Type.BOLD).offset(offset).length(length).build();
    }

    public static EntityModel italic(int offset, int length) {
        return new Builder(Type.ITALIC).offset(offset).length(length).build();
    }

    public static EntityModel code(int offset, int length) {
        return new Builder(Type.CODE).offset(offset).length(length).build();
    }

    public static EntityModel pre(int offset, int length, @Nullable String language) {
        return new Builder(Type.PRE).offset(offset).length(length).language(language).build();
    }

    public static EntityModel textLink(int offset, int length, @NonNull String url) {
        return new Builder(Type.TEXT_LINK).offset(offset).length(length).url(url).build();
    }

    public static EntityModel textMention(int offset, int length, @NonNull String userId) {
        return new Builder(Type.TEXT_MENTION).offset(offset).length(length).userId(userId).build();
    }

    public static EntityModel url(int offset, int length) {
        return new Builder(Type.URL).offset(offset).length(length).build();
    }

    public static EntityModel hashtag(int offset, int length) {
        return new Builder(Type.HASHTAG).offset(offset).length(length).build();
    }

    @Override
    @NonNull
    public String toString() {
        return "EntityModel{type=" + type + ", offset=" + offset + ", length=" + length + '}';
    }
}