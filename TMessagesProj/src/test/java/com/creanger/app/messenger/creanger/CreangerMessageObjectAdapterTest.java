package com.creanger.app.messenger.creanger;

import org.junit.Test;
import com.creanger.app.messenger.creanger.data.CreangerMessageObjectAdapter;
import com.creanger.app.messenger.creanger.data.CreangerMessageUiModel;
import com.creanger.app.messenger.creanger.model.MessageModels.MediaAttachment;
import com.creanger.app.messenger.creanger.model.MessageModels.MessageStatus;
import com.creanger.app.messenger.creanger.model.MessageModels.MessageType;
import com.creanger.app.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Media Phase 2: a received Creanger IMAGE message must render through the
 * EXISTING Telegram photo bubble / PhotoViewer / ImageLoader pipeline by
 * carrying the HTTPS source URL on the {@code TLRPC.PhotoSize} the stock
 * renderer already reads — no UI changes, no MTProto file fetching, legacy
 * Telegram image messages untouched.
 */
public class CreangerMessageObjectAdapterTest {

    private static final String OWNER = "uuid-1";

    private static MediaAttachment attachment(String publicUrl, String deliveryUrl, String storageKey,
                                              String mime, Integer width, Integer height, long sizeBytes) {
        return new MediaAttachment("att-1", "m-img", "media-1", 0, null,
                "cloudinary", storageKey, publicUrl, deliveryUrl, mime,
                sizeBytes, "abc123", width, height, null, null, null, null, null, null, "2026-08-15T12:00:00Z");
    }

    private static CreangerMessageUiModel image(List<MediaAttachment> atts, String content) {
        return new CreangerMessageUiModel("m-img", "chat-1", "uuid-s", content, MessageStatus.SENT,
                null, 1L, "2026-08-15T12:00:00Z", null, false, false, MessageType.IMAGE, atts);
    }

    private static CreangerMessageObjectAdapter adapter() {
        return new CreangerMessageObjectAdapter(OWNER);
    }

    private static TLRPC.TL_messageMediaPhoto photoMedia(CreangerMessageUiModel m) {
        TLRPC.Message msg = adapter().toTlrpcMessage(m);
        assertNotNull("image must map to message media", msg.media);
        assertTrue("Creanger IMAGE must produce TL_messageMediaPhoto, was " + msg.media.getClass().getSimpleName(),
                msg.media instanceof TLRPC.TL_messageMediaPhoto);
        return (TLRPC.TL_messageMediaPhoto) msg.media;
    }

    // ---- image attachment -> existing renderer mapping ----

    @Test
    public void imageAttachmentMapsToPhotoWithSourceUrl() {
        CreangerMessageUiModel m = image(Collections.singletonList(
                attachment("https://cdn.example/img/photo_1.png", null, "img/photo_1", "image/png",
                        800, 600, 12345)), "look");

        TLRPC.TL_messageMediaPhoto media = photoMedia(m);
        TLRPC.TL_photo photo = (TLRPC.TL_photo) media.photo;
        // Fully Telegram-style ladder: s/m/x/y, same HTTPS source on every rung.
        assertEquals(4, photo.sizes.size());
        String[] expectedTypes = {"s", "m", "x", "y"};
        int[][] expectedWh = {{100, 75}, {320, 240}, {800, 600}, {800, 600}};
        for (int i = 0; i < 4; i++) {
            TLRPC.TL_photoSize size = (TLRPC.TL_photoSize) photo.sizes.get(i);
            assertEquals(expectedTypes[i], size.type);
            assertEquals("https://cdn.example/img/photo_1.png", size.url);
            assertEquals(expectedWh[i][0], size.w);
            assertEquals(expectedWh[i][1], size.h);
            assertEquals(12345, size.size);
            // No MTProto file location: the ImageLoader HTTP path is used instead.
            assertNull(size.location);
        }
    }

    @Test
    public void photoLadderNeverUpcalesSmallSource() {
        CreangerMessageUiModel m = image(Collections.singletonList(
                attachment("https://cdn.example/img/small.png", null, "img/small", "image/png",
                        80, 60, 500)), null);

        TLRPC.TL_photo photo = (TLRPC.TL_photo) photoMedia(m).photo;
        assertEquals(4, photo.sizes.size());
        for (int i = 0; i < 4; i++) {
            TLRPC.TL_photoSize size = (TLRPC.TL_photoSize) photo.sizes.get(i);
            assertEquals(80, size.w);
            assertEquals(60, size.h);
        }
    }

    @Test
    public void photoLadderWithUnknownDimsIsSafeNoOp() {
        CreangerMessageUiModel m = image(Collections.singletonList(
                attachment("https://cdn.example/img/broken.png", null, "img/broken", "image/png",
                        null, null, 700)), null);

        TLRPC.TL_photo photo = (TLRPC.TL_photo) photoMedia(m).photo;
        assertEquals(4, photo.sizes.size());
        for (int i = 0; i < 4; i++) {
            TLRPC.TL_photoSize size = (TLRPC.TL_photoSize) photo.sizes.get(i);
            assertEquals(0, size.w);
            assertEquals(0, size.h);
            assertEquals("https://cdn.example/img/broken.png", size.url);
            assertNull(size.location);
        }
    }

    @Test
    public void imageSourcePrefersPublicUrlOverDeliveryUrl() {
        CreangerMessageUiModel m = image(Collections.singletonList(
                attachment("https://cdn.example/img/stable.png",
                        "https://signed.example/dl?sig=xyz", "img/stable", "image/jpeg",
                        400, 300, 5000)), "cap");

        TLRPC.TL_photoSize size = ((TLRPC.TL_photoSize) photoMedia(m).photo.sizes.get(0));
        assertEquals("public (stable) URL preferred over signed delivery URL",
                "https://cdn.example/img/stable.png", size.url);
    }

    @Test
    public void deliveryUrlUsedWhenNoPublicUrl() {
        CreangerMessageUiModel m = image(Collections.singletonList(
                attachment(null, "https://signed.example/dl?sig=abc", "img/signed", "image/png",
                        100, 100, 99)), null);

        TLRPC.TL_photoSize size = ((TLRPC.TL_photoSize) photoMedia(m).photo.sizes.get(0));
        assertEquals("https://signed.example/dl?sig=abc", size.url);
    }

    // ---- missing URL safety ----

    @Test
    public void missingUrlLeavesNothingToLoad() {
        // Neither a public nor a delivery URL.
        CreangerMessageUiModel m = image(Collections.singletonList(
                attachment(null, null, "img/no-url", "image/png", 100, 100, 99)), null);

        TLRPC.TL_photoSize size = ((TLRPC.TL_photoSize) photoMedia(m).photo.sizes.get(0));
        assertNull("no usable URL -> renderer must have nothing to load", size.url);
        assertNull(size.location);
    }

    @Test
    public void blankUrlTreatedAsMissing() {
        CreangerMessageUiModel m = image(Collections.singletonList(
                attachment("   ", " ", "img/blank", "image/png", 100, 100, 99)), null);

        assertNull(((TLRPC.TL_photoSize) photoMedia(m).photo.sizes.get(0)).url);
    }

    // ---- invalid media metadata ----

    @Test
    public void invalidMetadataStillYieldsSafePhotoBubble() {
        // width/height/size/mime garbage must not crash the mapping.
        CreangerMessageUiModel m = image(Collections.singletonList(
                attachment("https://cdn.example/img/broken.png", null, "img/broken", "text/plain",
                        null, null, 0)), null);

        TLRPC.TL_messageMediaPhoto media = photoMedia(m);
        TLRPC.TL_photoSize size = (TLRPC.TL_photoSize) media.photo.sizes.get(0);
        // The URL stays the source even when the metadata is garbage; the
        // renderer derives real dimensions from the decoded bitmap.
        assertEquals("https://cdn.example/img/broken.png", size.url);
        assertEquals(0, size.w);
        assertEquals(0, size.h);
        assertEquals(0, size.size);
    }

    @Test
    public void nonImageMediaStaysDocumentBacked() {
        // Video/audio/voice/document keep the document mapping with NO url (out of phase).
        List<MediaAttachment> attsV = new ArrayList<>();
        attsV.add(attachment("https://cdn.example/v.mp4", null, "v.mp4", "video/mp4", 1280, 720, 999));
        CreangerMessageUiModel m = new CreangerMessageUiModel("m-vid", "chat-1", "uuid-s", "cap",
                MessageStatus.SENT, null, 1L, "2026-08-15T12:00:00Z", null, false, false,
                MessageType.VIDEO, attsV);

        TLRPC.Message msg = adapter().toTlrpcMessage(m);
        assertTrue(msg.media instanceof TLRPC.TL_messageMediaDocument);
        assertEquals("video/mp4", ((TLRPC.TL_messageMediaDocument) msg.media).document.mime_type);
    }

    // ---- Phase 5B: video poster/playback seams ----

    @Test
    public void confirmedVideoCarriesStreamableAttributeAndPosterThumb() {
        List<MediaAttachment> atts = new ArrayList<>();
        atts.add(videoAttachment("https://cdn.example/v.mp4", "https://img.example/v_poster.jpg",
                1280, 720, 15000, null, null));
        CreangerMessageUiModel m = new CreangerMessageUiModel("m-vid", "chat-1", "uuid-s", "cap",
                MessageStatus.SENT, null, 1L, "2026-08-15T12:00:00Z", null, false, false,
                MessageType.VIDEO, atts);

        TLRPC.TL_messageMediaDocument media = (TLRPC.TL_messageMediaDocument) adapter().toTlrpcMessage(m).media;
        TLRPC.Document document = media.document;
        assertEquals("video/mp4", document.mime_type);

        boolean hasStreamableVideoAttr = false;
        for (TLRPC.DocumentAttribute attr : document.attributes) {
            if (attr instanceof TLRPC.TL_documentAttributeVideo) {
                TLRPC.TL_documentAttributeVideo v = (TLRPC.TL_documentAttributeVideo) attr;
                hasStreamableVideoAttr = true;
                assertTrue("Creanger video must advertise supports_streaming so the bubble shows a play affordance",
                        v.supports_streaming);
                assertEquals(1280, v.w);
                assertEquals(720, v.h);
            }
        }
        assertTrue("video attributes must include TL_documentAttributeVideo", hasStreamableVideoAttr);

        assertEquals("poster thumb rides on document.thumbs so the existing ChatMessageCell video branch renders it",
                1, document.thumbs.size());
        TLRPC.TL_photoSize poster = (TLRPC.TL_photoSize) document.thumbs.get(0);
        assertEquals("https://img.example/v_poster.jpg", poster.url);
        assertEquals("m", poster.type);
        assertEquals(1280, poster.w);
        // No MTProto location: the ImageLoader HTTP path must be used (the
        // getForDocument URL seam in ImageLocation).
        assertNull(poster.location);
    }

    @Test
    public void pendingVideoUsesLocalPosterStillForPreview() {
        // Pending (unsent) video: no server thumbnail yet — only the locally
        // generated poster still (an image file) must drive the bubble preview.
        List<MediaAttachment> atts = new ArrayList<>();
        atts.add(videoAttachment(null, null, 1920, 1080, 0,
                "/data/user/0/com.creanger.app.messenger/cache/creanger_poster_123.jpg", null));
        CreangerMessageUiModel m = new CreangerMessageUiModel("m-vid", "chat-1", "uuid-s", null,
                MessageStatus.PENDING, null, -1L, "2026-08-15T12:00:01Z", null, true, false,
                MessageType.VIDEO, atts);

        TLRPC.TL_messageMediaDocument media = (TLRPC.TL_messageMediaDocument) adapter().toTlrpcMessage(m).media;
        TLRPC.Document document = media.document;
        assertEquals(1, document.thumbs.size());
        TLRPC.TL_photoSize poster = (TLRPC.TL_photoSize) document.thumbs.get(0);
        assertEquals("local poster still path", "/data/user/0/com.creanger.app.messenger/cache/creanger_poster_123.jpg",
                poster.url);
        assertNull(poster.location);
    }

    @Test
    public void videoWithoutAnyPosterSourceYieldsSafeEmptyThumbs() {
        List<MediaAttachment> atts = new ArrayList<>();
        atts.add(videoAttachment("https://cdn.example/v.mp4", null, 640, 480, 0, null, null));
        CreangerMessageUiModel m = new CreangerMessageUiModel("m-vid", "chat-1", "uuid-s", null,
                MessageStatus.SENT, null, 1L, "2026-08-15T12:00:00Z", null, false, false,
                MessageType.VIDEO, atts);

        TLRPC.TL_messageMediaDocument media = (TLRPC.TL_messageMediaDocument) adapter().toTlrpcMessage(m).media;
        assertTrue("no poster source -> empty thumbs, renderer shows a clean video bubble",
                media.document.thumbs.isEmpty());
    }

    private static MediaAttachment videoAttachment(String publicUrl, String thumbnailUrl,
                                                   Integer width, Integer height, long sizeBytes,
                                                   String localPath, Integer duration) {
        return new MediaAttachment("att-v", "m-vid", "media-v", 0, null,
                "cloudinary", "v/" + (publicUrl != null ? "key" : "pending"), publicUrl, null, "video/mp4",
                sizeBytes, "def456", width, height, duration, null, localPath, thumbnailUrl, null, null,
                "2026-08-15T12:00:00Z");
    }
}