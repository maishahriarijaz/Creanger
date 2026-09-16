package com.creanger.app.messenger.creanger.api;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Android-free document helpers for the E2 document plane (migration 040).
 *
 * Covers the PHASE_E2 §6 debt that was provider-side only:
 * thumbnail generation, chat-list preview, bulk operations, document search
 * indexing. All methods are pure JVM (no Android types) so they are
 * unit-testable per the Creanger Android-free core rule.
 */
public final class CreangerDocumentPreview {

    private CreangerDocumentPreview() {}

    /** Providers the client trusts for direct HTTPS access (E2 security audit). */
    public static boolean isTrustedProviderUrl(@Nullable String url) {
        if (url == null || !url.startsWith("https://")) {
            return false;
        }
        String host = hostOf(url);
        return "res.cloudinary.com".equals(host)
                || host.endsWith(".cloudinary.com")
                || "images.imagebb.com".equals(host)
                || host.endsWith(".imagebb.com");
    }

    /**
     * Builds a Cloudinary thumbnail/transformation URL for a video/document.
     * Non-Cloudinary URLs are returned unchanged (ImageBB serves its own
     * thumbnails). Null-safe: null in, null out.
     */
    @Nullable
    public static String thumbnailFor(@Nullable String publicUrl, int width, int height) {
        if (publicUrl == null || publicUrl.isEmpty()) {
            return null;
        }
        if (!publicUrl.contains("res.cloudinary.com")) {
            return publicUrl;
        }
        int upload = publicUrl.indexOf("/upload/");
        if (upload < 0) {
            return publicUrl;
        }
        int w = Math.max(64, Math.min(width <= 0 ? 320 : width, 1024));
        int h = Math.max(64, Math.min(height <= 0 ? 320 : height, 1024));
        String transform = "c_fill,w_" + w + ",h_" + h + ",f_jpg,pg_1";
        return publicUrl.substring(0, upload + "/upload/".length())
                + transform
                + publicUrl.substring(upload + "/upload/".length());
    }

    /** Preview variant (animated / short preview URL) for video. */
    @Nullable
    public static String previewFor(@Nullable String publicUrl) {
        if (publicUrl == null || publicUrl.isEmpty()) {
            return null;
        }
        if (!publicUrl.contains("res.cloudinary.com")) {
            return publicUrl;
        }
        int upload = publicUrl.indexOf("/upload/");
        if (upload < 0) {
            return publicUrl;
        }
        return publicUrl.substring(0, upload + "/upload/".length())
                + "f_mp4,du_3"
                + publicUrl.substring(upload + "/upload/".length());
    }

    /** Chat-list preview picks the first trusted media URL, else null. */
    @Nullable
    public static String chatListPreview(List<RecentMedia> items) {
        if (items == null) {
            return null;
        }
        for (RecentMedia item : items) {
            if (item == null) {
                continue;
            }
            String candidate = item.thumbnailUrl != null ? item.thumbnailUrl : item.publicUrl;
            if (isTrustedProviderUrl(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** Chunks ids for bulk delete (server accepts any size, client caps for URL safety). */
    public static List<List<String>> chunkForBulkDelete(List<String> ids, int chunkSize) {
        List<List<String>> out = new ArrayList<>();
        if (ids == null || ids.isEmpty()) {
            return out;
        }
        int size = chunkSize <= 0 ? 100 : chunkSize;
        for (int i = 0; i < ids.size(); i += size) {
            out.add(new ArrayList<>(ids.subList(i, Math.min(ids.size(), i + size))));
        }
        return out;
    }

    /** Minimal recent-media row for chat-list preview (get_recent_media RPC). */
    public static final class RecentMedia {
        @Nullable public final String messageId;
        @Nullable public final String publicUrl;
        @Nullable public final String thumbnailUrl;
        @Nullable public final String mimeType;
        @Nullable public final String createdAt;

        public RecentMedia(@Nullable String messageId, @Nullable String publicUrl,
                           @Nullable String thumbnailUrl, @Nullable String mimeType,
                           @Nullable String createdAt) {
            this.messageId = messageId;
            this.publicUrl = publicUrl;
            this.thumbnailUrl = thumbnailUrl;
            this.mimeType = mimeType;
            this.createdAt = createdAt;
        }
    }

    private static String hostOf(String url) {
        int start = url.indexOf("://");
        if (start < 0) {
            return "";
        }
        int end = url.indexOf('/', start + 3);
        String host = end < 0 ? url.substring(start + 3) : url.substring(start + 3, end);
        int colon = host.indexOf(':');
        return colon < 0 ? host : host.substring(0, colon);
    }
}
