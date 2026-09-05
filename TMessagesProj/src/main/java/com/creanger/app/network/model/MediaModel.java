package com.creanger.app.network.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Media attachment model for messages.
 */
public class MediaModel {

    @NonNull
    public final String id;                    // Media ID
    @NonNull
    public final MessageModel.MediaType type;
    @Nullable
    public final String url;                   // Direct download URL (signed/presigned)
    @Nullable
    public final String thumbnailUrl;          // Thumbnail URL
    @Nullable
    public final String fileName;              // Original file name
    @Nullable
    public final String mimeType;              // MIME type
    public final long fileSize;                // File size in bytes
    public final int width;                    // Image/video width
    public final int height;                   // Image/video height
    public final int duration;                 // Audio/video duration in seconds
    @Nullable
    public final String caption;               // Media caption
    @Nullable
    public final List<EntityModel> captionEntities; // Caption entities
    @Nullable
    public final Map<String, Object> extra;    // Backend-specific extra fields

    public MediaModel(@NonNull Builder builder) {
        this.id = builder.id;
        this.type = builder.type;
        this.url = builder.url;
        this.thumbnailUrl = builder.thumbnailUrl;
        this.fileName = builder.fileName;
        this.mimeType = builder.mimeType;
        this.fileSize = builder.fileSize;
        this.width = builder.width;
        this.height = builder.height;
        this.duration = builder.duration;
        this.caption = builder.caption;
        this.captionEntities = builder.captionEntities != null ? Collections.unmodifiableList(builder.captionEntities) : Collections.emptyList();
        this.extra = builder.extra != null ? Collections.unmodifiableMap(builder.extra) : Collections.emptyMap();
    }

    public static class Builder {
        @NonNull String id;
        @NonNull MessageModel.MediaType type;
        @Nullable String url;
        @Nullable String thumbnailUrl;
        @Nullable String fileName;
        @Nullable String mimeType;
        long fileSize = 0;
        int width = 0;
        int height = 0;
        int duration = 0;
        @Nullable String caption;
        @Nullable List<EntityModel> captionEntities;
        @Nullable Map<String, Object> extra;

        public Builder(@NonNull String id, @NonNull MessageModel.MediaType type) {
            this.id = id;
            this.type = type;
        }

        public Builder url(@Nullable String url) { this.url = url; return this; }
        public Builder thumbnailUrl(@Nullable String url) { this.thumbnailUrl = url; return this; }
        public Builder fileName(@Nullable String name) { this.fileName = name; return this; }
        public Builder mimeType(@Nullable String mime) { this.mimeType = mime; return this; }
        public Builder fileSize(long size) { this.fileSize = size; return this; }
        public Builder dimensions(int width, int height) { this.width = width; this.height = height; return this; }
        public Builder duration(int seconds) { this.duration = seconds; return this; }
        public Builder caption(@Nullable String caption) { this.caption = caption; return this; }
        public Builder captionEntities(@Nullable List<EntityModel> entities) { this.captionEntities = entities; return this; }
        public Builder extra(@Nullable Map<String, Object> extra) { this.extra = extra; return this; }

        public MediaModel build() {
            return new MediaModel(this);
        }
    }
}