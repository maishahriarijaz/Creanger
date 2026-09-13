package com.creanger.app.messenger.creanger.api;

import org.json.JSONObject;
import com.creanger.app.messenger.creanger.model.ApiError;
import com.creanger.app.messenger.creanger.model.MessageModels.UploadedMedia;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Raw media byte uploader for the Creanger chat data plane (Media Send phase).
 *
 * This client is the Android side of the Creanger Media API: it POSTs the raw
 * bytes to the authenticated {@code /v1/media/upload-image} and
 * {@code /v1/media/upload-video} endpoints of the Creanger backend with the
 * SAME Custom Creanger JWT + HTTPS transport as auth. The backend verifies
 * the JWT (authMiddleware), validates size/type, uploads to the provider
 * (ImageBB for images, Cloudinary for video) server-side and returns a
 * PROVIDER-NEUTRAL {@link UploadedMedia} result — so this class never touches
 * ImageBB/Cloudinary directly, never contains provider credentials and never
 * parses provider JSON.
 *
 * The returned {@link UploadedMedia} (storage provider + key + public URL) is
 * exactly what {@code send_media_message} (migration 029) later persists via
 * Supabase PostgREST; the binary bytes only ever travel Android → the
 * Creanger Media API.
 *
 * The request path is relative to the transport's configured base URL (the
 * Creanger backend), which is distinct from the Supabase chat data plane.
 *
 * Synchronous — MUST be called off the main thread.
 */
public final class CreangerMediaUploadClient {

    private static final String MEDIA_API_PATH = "/v1/media/upload-image";

    private final CreangerHttpTransport transport;

    /**
     * @param transport the transport bound to the Creanger backend base URL
     *                  (same Bearer Custom JWT handling as auth); its base URL
     *                  is where the Media API is deployed
     */
    public CreangerMediaUploadClient(CreangerHttpTransport transport) {
        this.transport = transport;
    }

    /**
     * Uploads an image's bytes to the Creanger Media API
     * ({@code /v1/media/upload-image}), which stores the image via ImageBB
     * server-side and returns the provider-neutral metadata a
     * {@code send_media_message} attachment row needs (provider, storage key,
     * public URL and byte size).
     *
     * @throws CreangerApiException on an authenticated rejection (validation,
     *                              provider failure, malformed/blank provider
     *                              URL) — a failed upload is NEVER reported as
     *                              success
     * @throws IOException          on transport failure (e.g. the 30s read
     *                              timeout); the caller marks the send FAILED
     *                              and may retry the same bytes later
     */
    public UploadedMedia uploadImage(String accessToken, String chatId, String fileName,
                                     byte[] imageData, String mimeType)
            throws IOException, CreangerApiException {
        if (imageData == null || imageData.length == 0) {
            throw new CreangerApiException(200, new ApiError(ApiError.INTERNAL_ERROR,
                    "image has no bytes to upload", null, 0));
        }
        if (chatId == null || chatId.isEmpty()) {
            throw new CreangerApiException(200, new ApiError(ApiError.INTERNAL_ERROR,
                    "chat id is required for upload", null, 0));
        }

        Map<String, String> query = new HashMap<>();
        String safeName = sanitize(fileName);
        if (safeName != null && !safeName.isEmpty()) {
            query.put("name", safeName);
        }
        query.put("chat_id", chatId);

        ApiRequest request = new ApiRequest("POST", MEDIA_API_PATH, query, null, accessToken, null,
                imageData, mimeType != null ? mimeType : "application/octet-stream");
        TransportResponse raw = transport.execute(request);
        return parseEnvelope(raw, imageData.length, mimeType);
    }

    /**
     * Parses the Creanger Media API envelope ({@code { success, data?, error? }}).
     * Success must carry a complete, provider-neutral upload: a missing/blank
     * public URL, a missing storage key or a missing provider are all treated
     * as errors — never silently turned into a send.
     */
    private static UploadedMedia parseEnvelope(TransportResponse raw, long sizeBytes,
                                               @Nullable String mimeType)
            throws CreangerApiException {
        boolean ok = raw.statusCode >= 200 && raw.statusCode < 300;
        if (!ok) {
            ApiError error = JsonEnvelopeParser.parse(raw.statusCode, raw.body, null).error;
            if (error == null) {
                error = new ApiError(ApiError.INTERNAL_ERROR,
                        "media upload failed (HTTP " + raw.statusCode + ")", null, 0);
            }
            throw new CreangerApiException(raw.statusCode, error);
        }

        JSONObject data = JsonEnvelopeParser.dataObject(raw.body);
        if (data == null) {
            throw new CreangerApiException(200, new ApiError(ApiError.INTERNAL_ERROR,
                    "media api returned an empty result", null, 0));
        }

        String storageProvider = data.optString("storageProvider", null);
        String storageKey = data.optString("storageKey", null);
        String publicUrl = data.optString("publicUrl", null);
        String responseMime = data.optString("mimeType", mimeType);
        if (storageProvider == null || storageProvider.isEmpty()) {
            throw new CreangerApiException(200, new ApiError(ApiError.INTERNAL_ERROR,
                    "media api returned no storage provider", null, 0));
        }
        if (storageKey == null || storageKey.isEmpty()) {
            throw new CreangerApiException(200, new ApiError(ApiError.INTERNAL_ERROR,
                    "media api returned no storage key", null, 0));
        }
        if (publicUrl == null || publicUrl.trim().isEmpty() || !isHttpUrl(publicUrl.trim())) {
            throw new CreangerApiException(200, new ApiError(ApiError.INTERNAL_ERROR,
                    "media api returned an invalid public url", null, 0));
        }
        long size = data.optLong("sizeBytes", sizeBytes);
        // Provider-neutral video metadata from the Media API
        // (Cloudinary reports reliable width/height/duration server-side).
        Integer width = nullableInt(data, "width");
        Integer height = nullableInt(data, "height");
        Integer durationMs = nullableInt(data, "durationMs");
        return new UploadedMedia(storageProvider, storageKey, publicUrl.trim(),
                responseMime != null && !responseMime.isEmpty()
                        ? responseMime : (mimeType != null ? mimeType : "application/octet-stream"),
                size, nullIfEmpty(data.optString("deliveryUrl", null)),
                nullIfEmpty(data.optString("thumbnailUrl", null)),
                nullIfEmpty(data.optString("previewUrl", null)),
                width, height, durationMs);
    }

    private static Integer nullableInt(JSONObject o, String key) {
        if (!o.has(key) || o.isNull(key)) {
            return null;
        }
        try {
            return o.optInt(key);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Uploads a video's bytes to the Creanger Media API
     * ({@code /v1/media/upload-video}), which stores the video via Cloudinary
     * server-side and returns the provider-neutral metadata a
     * {@code send_media_message} attachment row needs (provider, storage key,
     * public URL, thumbnail URL and byte size).
     *
     * @throws CreangerApiException on an authenticated rejection (validation,
     *                              provider failure, malformed/blank provider
     *                              URL) — a failed upload is NEVER reported as
     *                              success
     * @throws IOException          on transport failure (e.g. the 60s read
     *                              timeout); the caller marks the send FAILED
     *                              and may retry the same bytes later
     */
public UploadedMedia uploadVideo(String accessToken, String chatId, String fileName,
                                     byte[] videoData, String mimeType)
            throws IOException, CreangerApiException {
        if (videoData == null || videoData.length == 0) {
            throw new CreangerApiException(200, new ApiError(ApiError.INTERNAL_ERROR,
                    "video has no bytes to upload", null, 0));
        }
        if (chatId == null || chatId.isEmpty()) {
            throw new CreangerApiException(200, new ApiError(ApiError.INTERNAL_ERROR,
                    "chat id is required for upload", null, 0));
        }

        Map<String, String> query = new HashMap<>();
        String safeName = sanitize(fileName);
        if (safeName != null && !safeName.isEmpty()) {
            query.put("name", safeName);
        }
        query.put("chat_id", chatId);

        // Use the video-specific endpoint of the Creanger Media API.
        ApiRequest request = new ApiRequest("POST", "/v1/media/upload-video", query, null, accessToken, null,
                videoData, mimeType != null ? mimeType : "application/octet-stream");
        TransportResponse raw = transport.execute(request);
        return parseEnvelope(raw, videoData.length, mimeType);
    }

    /**
     * Uploads audio bytes (mp3/m4a/wav/ogg) to the Creanger Media API
     * ({@code /v1/media/upload-audio}), which stores the audio via Cloudinary
     * server-side (resource_type video, audio auto-detect) and returns the
     * provider-neutral metadata — including the server-computed play duration
     * ({@code durationMs}) — that a {@code send_media_message} attachment row
     * needs. This is the ONLY place the raw bytes of an audio/voice send travel.
     *
     * @throws CreangerApiException on an authenticated rejection (validation,
     *                              provider failure, malformed/blank provider
     *                              URL) — a failed upload is NEVER reported as
     *                              success
     * @throws IOException          on transport failure (e.g. the read timeout);
     *                              the caller marks the send FAILED and may retry
     *                              the same bytes later
     */
    public UploadedMedia uploadAudio(String accessToken, String chatId, String fileName,
                                     byte[] audioData, String mimeType)
            throws IOException, CreangerApiException {
        if (audioData == null || audioData.length == 0) {
            throw new CreangerApiException(200, new ApiError(ApiError.INTERNAL_ERROR,
                    "audio has no bytes to upload", null, 0));
        }
        if (chatId == null || chatId.isEmpty()) {
            throw new CreangerApiException(200, new ApiError(ApiError.INTERNAL_ERROR,
                    "chat id is required for upload", null, 0));
        }

        Map<String, String> query = new HashMap<>();
        String safeName = sanitize(fileName);
        if (safeName != null && !safeName.isEmpty()) {
            query.put("name", safeName);
        }
        query.put("chat_id", chatId);

        ApiRequest request = new ApiRequest("POST", "/v1/media/upload-audio", query, null, accessToken, null,
                audioData, mimeType != null ? mimeType : "application/octet-stream");
        TransportResponse raw = transport.execute(request);
        return parseEnvelope(raw, audioData.length, mimeType);
    }

    /**
     * Uploads arbitrary document bytes (pdf/zip/text/...) to the Creanger Media
     * API ({@code /v1/media/upload-document}), which stores the document via
     * Cloudinary {@code raw} server-side and returns the provider-neutral
     * metadata that a {@code send_media_message} attachment row needs. The
     * {@code fileName} is sanitized into the Cloudinary {@code public_id}, so
     * the returned {@code storageKey} preserves a displayable file name (the
     * adapter renders documents from {@code MediaAttachment.storageKey}).
     *
     * @throws CreangerApiException on an authenticated rejection (validation,
     *                              provider failure, malformed/blank provider
     *                              URL) — a failed upload is NEVER reported as
     *                              success
     * @throws IOException          on transport failure (e.g. the read timeout);
     *                              the caller marks the send FAILED and may retry
     *                              the same bytes later
     */
    public UploadedMedia uploadDocument(String accessToken, String chatId, String fileName,
                                        byte[] documentData, String mimeType)
            throws IOException, CreangerApiException {
        if (documentData == null || documentData.length == 0) {
            throw new CreangerApiException(200, new ApiError(ApiError.INTERNAL_ERROR,
                    "document has no bytes to upload", null, 0));
        }
        if (chatId == null || chatId.isEmpty()) {
            throw new CreangerApiException(200, new ApiError(ApiError.INTERNAL_ERROR,
                    "chat id is required for upload", null, 0));
        }

        Map<String, String> query = new HashMap<>();
        String safeName = sanitize(fileName);
        if (safeName != null && !safeName.isEmpty()) {
            query.put("name", safeName);
        }
        query.put("chat_id", chatId);

        ApiRequest request = new ApiRequest("POST", "/v1/media/upload-document", query, null, accessToken, null,
                documentData, mimeType != null ? mimeType : "application/octet-stream");
        TransportResponse raw = transport.execute(request);
        return parseEnvelope(raw, documentData.length, mimeType);
    }

    private static boolean isHttpUrl(String url) {
        return url.startsWith("https://") || url.startsWith("http://");
    }

    /** Rejects path separators, traversal and control characters. */
    private static String sanitize(@Nullable String name) {
        if (name == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '/' || c == '\\' || c == '.' && sb.length() == 0) {
                continue;
            }
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }

    @Nullable
    private static String nullIfEmpty(@Nullable String value) {
        // Android's org.json returns the literal string "null" for JSON null
        // (unlike the reference impl used in JVM tests) — treat it as missing.
        return value == null || value.isEmpty() || "null".equals(value) ? null : value;
    }
}
