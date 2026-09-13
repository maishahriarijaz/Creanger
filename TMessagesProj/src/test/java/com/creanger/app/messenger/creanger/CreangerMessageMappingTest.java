package com.creanger.app.messenger.creanger;

import org.junit.Test;
import com.creanger.app.messenger.creanger.data.CreangerMessageMapping;
import com.creanger.app.messenger.creanger.data.CreangerMessageUiModel;
import com.creanger.app.messenger.creanger.model.MessageModels.MediaAttachment;
import com.creanger.app.messenger.creanger.model.MessageModels.MessageStatus;
import com.creanger.app.messenger.creanger.model.MessageModels.MessageType;

import java.util.Map;

import static org.junit.Assert.*;

/**
 * JVM tests for {@link CreangerMessageMapping} — the pure Java core of the
 * Creanger→Telegram compatibility layer. No Android dependencies.
 *
 * Tests cover:
 * 1. Text message content/timestamp mapping
 * 2. Sender/out mapping
 * 3. Pending/failed/sent state mapping
 * 4. UUID ↔ synthetic ID bijection
 * 5. Params preservation
 * 6. Flag computation
 * 7. Invalid input handling
 */
public class CreangerMessageMappingTest {

    private static final String OWNER_ID = "uuid-owner-123";
    private static final String CHAT_UUID = "chat-uuid-456";
    private static final String SENDER_UUID = "uuid-sender-789";

    private CreangerMessageMapping mapping() {
        return new CreangerMessageMapping(OWNER_ID);
    }

    private CreangerMessageUiModel baseText(String id, String content, String status, String clientMsgId, String createdAt, boolean out) {
        return new CreangerMessageUiModel(
                id, CHAT_UUID, SENDER_UUID, content, status,
                clientMsgId, 1L, createdAt, false, out);
    }

    // ---- 1. text message mapping ----

    @Test
    public void textMessageMapsContentAndTimestamp() {
        CreangerMessageUiModel m = baseText("creanger-msg-1", "Hello world", MessageStatus.SENT, null, "2026-08-15T12:00:00Z", true);
        CreangerMessageMapping mapping = mapping();

        int timestamp = mapping.parseIso8601ToUnix(m.createdAt);
        Map<String, String> params = mapping.buildParams(m);

        assertEquals("Hello world", m.content);
        assertEquals(1786795200, timestamp); // 2026-08-15T12:00:00Z
        assertEquals("creanger-msg-1", params.get("creanger_uuid"));
    }

    // ---- 2. sender/out mapping ----

    @Test
    public void outFlagDeterminesBubbleAlignment() {
        CreangerMessageMapping mapping = mapping();

        int flagsOut = mapping.computeFlags(true);
        int flagsIn = mapping.computeFlags(false);

        // FLAG_1 (bit 1) = out
        assertTrue("out=true should have FLAG_1 set", (flagsOut & (1 << 1)) != 0);
        assertFalse("out=false should not have FLAG_1", (flagsIn & (1 << 1)) != 0);
    }

    // ---- 3. pending/failed/sent state mapping ----

    @Test
    public void pendingStatusMapsToSendingState() {
        CreangerMessageMapping mapping = mapping();

        int state = mapping.getSendState(MessageStatus.PENDING);
        boolean negativeId = mapping.shouldHaveNegativeId(MessageStatus.PENDING);

        assertEquals(1, state); // MESSAGE_SEND_STATE_SENDING
        assertTrue(negativeId);
    }

    @Test
    public void failedStatusMapsToSendErrorState() {
        CreangerMessageMapping mapping = mapping();

        int state = mapping.getSendState(MessageStatus.FAILED);
        boolean negativeId = mapping.shouldHaveNegativeId(MessageStatus.FAILED);

        assertEquals(2, state); // MESSAGE_SEND_STATE_SEND_ERROR
        assertTrue(negativeId);
    }

    @Test
    public void sentStatusMapsToSentState() {
        CreangerMessageMapping mapping = mapping();

        int state = mapping.getSendState(MessageStatus.SENT);
        boolean negativeId = mapping.shouldHaveNegativeId(MessageStatus.SENT);

        assertEquals(0, state); // MESSAGE_SEND_STATE_SENT
        assertFalse(negativeId);
    }

    @Test
    public void deliveredAndReadAlsoMapToSentState() {
        CreangerMessageMapping mapping = mapping();

        assertEquals(0, mapping.getSendState(MessageStatus.DELIVERED));
        assertEquals(0, mapping.getSendState(MessageStatus.READ));
        assertFalse(mapping.shouldHaveNegativeId(MessageStatus.DELIVERED));
        assertFalse(mapping.shouldHaveNegativeId(MessageStatus.READ));
    }

    // ---- 4. UUID ↔ synthetic ID bijection ----

    @Test
    public void sameUuidAlwaysMapsToSameSyntheticId() {
        CreangerMessageMapping mapping = mapping();
        String uuid = "stable-uuid";

        long id1 = mapping.getOrCreateSyntheticId(uuid);
        long id2 = mapping.getOrCreateSyntheticId(uuid);

        assertNotEquals(0L, id1);
        assertEquals(id1, id2);
    }

    @Test
    public void differentUuidsMapToDifferentSyntheticIds() {
        CreangerMessageMapping mapping = mapping();

        long id1 = mapping.getOrCreateSyntheticId("uuid-a");
        long id2 = mapping.getOrCreateSyntheticId("uuid-b");

        assertNotEquals(id1, id2);
    }

    @Test
    public void roundTripUuidToSyntheticAndBack() {
        CreangerMessageMapping mapping = mapping();
        String uuid = "round-trip-uuid";

        long synthetic = mapping.getOrCreateSyntheticId(uuid);
        String back = mapping.getCreangerUuid(synthetic);

        assertEquals(uuid, back);
    }

    @Test
    public void syntheticIdLookupReturnsNullForUnknown() {
        CreangerMessageMapping mapping = mapping();

        assertNull(mapping.getCreangerUuid(999999L));
        assertNull(mapping.getSyntheticId("unknown-uuid"));
    }

    // ---- 5. params preservation ----

    @Test
    public void paramsPreserveCreangerUuidAndClientMessageId() {
        CreangerMessageMapping mapping = mapping();
        CreangerMessageUiModel m = new CreangerMessageUiModel(
                "creanger-msg-1", "chat-1", "sender-1", "Test", MessageStatus.SENT,
                "client-abc-123", 42L, "2026-08-15T08:01:00Z", false, true);

        Map<String, String> params = mapping.buildParams(m);

        assertEquals("creanger-msg-1", params.get("creanger_uuid"));
        assertEquals("client-abc-123", params.get("client_message_id"));
        assertEquals("42", params.get("chat_seq"));
        assertEquals("false", params.get("is_local"));
    }

    @Test
    public void nullOptionalFieldsDefaultToEmptyStrings() {
        CreangerMessageMapping mapping = mapping();
        CreangerMessageUiModel m = new CreangerMessageUiModel(
                "id", "chat", "sender", "Test", MessageStatus.SENT,
                null, null, "2026-08-15T08:01:00Z", false, true);

        Map<String, String> params = mapping.buildParams(m);

        assertEquals("", params.get("client_message_id"));
        assertEquals("", params.get("chat_seq"));
        assertEquals("false", params.get("is_local"));
    }

    @Test
    public void videoParamsCarryPlaybackUrlAndPosterUrl() {
        CreangerMessageMapping mapping = mapping();
        MediaAttachment v = new MediaAttachment("att-1", "m-vid", "media-1", 0, null,
                "cloudinary", "v/key", "https://cdn.example/v.mp4", null, "video/mp4",
                12345, "abc", 1280, 720, 15000, null, null,
                "https://img.example/v_poster.jpg", null, null, "2026-08-15T12:00:00Z");
        CreangerMessageUiModel m = new CreangerMessageUiModel(
                "m-vid", "chat-1", "sender-1", "cap", MessageStatus.SENT,
                "client-9", 7L, "2026-08-15T12:00:00Z", null, false, true,
                MessageType.VIDEO, java.util.Collections.singletonList(v));

        Map<String, String> params = mapping.buildParams(m);

        assertEquals("https://cdn.example/v.mp4", params.get(CreangerChatDetection.PARAM_VIDEO_URL));
        assertEquals("https://img.example/v_poster.jpg", params.get(CreangerChatDetection.PARAM_POSTER_URL));
    }

    @Test
    public void textAndPendingVideoParamsCarryNoPlaybackUrl() {
        CreangerMessageMapping mapping = mapping();
        // Pending video has no public URL yet -> no playback seam.
        MediaAttachment pending = new MediaAttachment("att-1", "m-vid", "media-1", 0, null,
                "cloudinary", "local", null, null, "video/mp4",
                0, "abc", 1280, 720, 0, null,
                "/cache/creanger_poster_1.jpg", null, null, null, "2026-08-15T12:00:00Z");
        CreangerMessageUiModel mv = new CreangerMessageUiModel(
                "m-vid", "chat-1", "sender-1", null, MessageStatus.PENDING,
                "client-9", 7L, "2026-08-15T12:00:00Z", null, true, true,
                MessageType.VIDEO, java.util.Collections.singletonList(pending));
        Map<String, String> videoParams = mapping.buildParams(mv);
        assertNull(videoParams.get(CreangerChatDetection.PARAM_VIDEO_URL));

        // Plain text message never carries the video seams.
        CreangerMessageUiModel mt = new CreangerMessageUiModel(
                "m-text", "chat-1", "sender-1", "hi", MessageStatus.SENT,
                null, 1L, "2026-08-15T12:00:00Z", false, true);
        Map<String, String> textParams = mapping.buildParams(mt);
        assertNull(textParams.get(CreangerChatDetection.PARAM_VIDEO_URL));
        assertNull(textParams.get(CreangerChatDetection.PARAM_POSTER_URL));
    }

    // ---- 6. flag computation ----

    @Test
    public void computeFlagsSetsOnlyOutForTextMessage() {
        CreangerMessageMapping mapping = mapping();

        int flags = mapping.computeFlags(true);

        // Only FLAG_1 (bit 1) should be set for out=true text message
        assertEquals(1 << 1, flags);
    }

    @Test
    public void setFlagManipulatesBitsCorrectly() {
        CreangerMessageMapping mapping = mapping();

        int flags = 0;
        flags = mapping.setFlag(flags, 1, true);  // set bit 1
        assertEquals(2, flags);
        flags = mapping.setFlag(flags, 1, false); // clear bit 1
        assertEquals(0, flags);
        flags = mapping.setFlag(flags, 5, true);  // set bit 5
        assertEquals(32, flags);
    }

    // ---- 7. send state mapping ----

    @Test
    public void sendStateConstantsMatchLegacyValues() {
        CreangerMessageMapping mapping = mapping();

        // These must match MessageObject.MESSAGE_SEND_STATE_* constants
        assertEquals(0, mapping.getSendState(MessageStatus.SENT));
        assertEquals(1, mapping.getSendState(MessageStatus.PENDING));
        assertEquals(2, mapping.getSendState(MessageStatus.FAILED));
    }

    // ---- 8. invalid/malformed handling ----

    @Test
    public void malformedTimestampDefaultsToNow() {
        CreangerMessageMapping mapping = mapping();

        int timestamp = mapping.parseIso8601ToUnix("not-a-timestamp");
        int now = (int) (System.currentTimeMillis() / 1000);

        // Should be within last minute
        assertTrue(Math.abs(timestamp - now) < 60);
    }

    @Test
    public void nullTimestampDefaultsToNow() {
        CreangerMessageMapping mapping = mapping();

        int timestamp = mapping.parseIso8601ToUnix(null);
        int now = (int) (System.currentTimeMillis() / 1000);

        assertTrue(Math.abs(timestamp - now) < 60);
    }

    @Test
    public void nullUuidGeneratesUniqueSyntheticId() {
        CreangerMessageMapping mapping = mapping();

        long id1 = mapping.getOrCreateSyntheticId(null);
        long id2 = mapping.getOrCreateSyntheticId(null);

        assertNotEquals(id1, id2);
        assertTrue(id1 <= -1_000_000_000_000L);
    }

    @Test
    public void ownerIdIsPreserved() {
        CreangerMessageMapping mapping = new CreangerMessageMapping("custom-owner-456");

        assertEquals("custom-owner-456", mapping.getOwnerCreangerId());
    }

    // ---- 9. synthetic ID space ----

    @Test
    public void syntheticIdsAreNegativeAndLarge() {
        CreangerMessageMapping mapping = mapping();

        long id = mapping.getOrCreateSyntheticId("test-uuid");

        // Should be negative and far from MTProto positive ID space
        assertTrue(id <= -1_000_000_000_000L);
    }

    @Test
    public void sequentialNullUuidsDecrement() {
        CreangerMessageMapping mapping = mapping();

        long id1 = mapping.getOrCreateSyntheticId(null);
        long id2 = mapping.getOrCreateSyntheticId(null);

        assertEquals(id1 - 1, id2);
    }

    // ---- Collision safety ----

    @Test
    public void sequentialAllocationIsCollisionFree() {
        CreangerMessageMapping mapping = mapping();

        // Allocate many IDs sequentially
        long prev = Long.MAX_VALUE;
        for (int i = 0; i < 10000; i++) {
            long id = mapping.getOrCreateSyntheticId("uuid-" + i);
            // Each new ID should be strictly less than previous (decrementing)
            assertTrue("ID " + id + " should be < " + prev, id < prev);
            prev = id;
        }
    }

    @Test
    public void sameUuidAlwaysReturnsSameId() {
        CreangerMessageMapping mapping = mapping();

        long id1 = mapping.getOrCreateSyntheticId("same-uuid");
        long id2 = mapping.getOrCreateSyntheticId("same-uuid");
        long id3 = mapping.getOrCreateSyntheticId("same-uuid");

        assertEquals(id1, id2);
        assertEquals(id2, id3);
    }

    @Test
    public void differentUuidsGetDifferentIds() {
        CreangerMessageMapping mapping = mapping();

        long id1 = mapping.getOrCreateSyntheticId("uuid-a");
        long id2 = mapping.getOrCreateSyntheticId("uuid-b");

        assertNotEquals(id1, id2);
    }

    @Test
    public void reverseMappingIsCorrect() {
        CreangerMessageMapping mapping = mapping();
        String uuid = "reverse-test-uuid";

        long synthetic = mapping.getOrCreateSyntheticId(uuid);
        String back = mapping.getCreangerUuid(synthetic);

        assertEquals(uuid, back);
    }

    @Test
    public void nullUuidsGetUniqueIds() {
        CreangerMessageMapping mapping = mapping();

        long id1 = mapping.getOrCreateSyntheticId(null);
        long id2 = mapping.getOrCreateSyntheticId(null);

        assertNotEquals(id1, id2);
        // Both should be negative (sequential allocator starts at -1T and decrements)
        assertTrue(id1 < 0);
        assertTrue(id2 < 0);
    }

    @Test
    public void largeScaleAllocationNoCollisions() {
        CreangerMessageMapping mapping = mapping();

        // Allocate 100k IDs - verify no duplicates
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (int i = 0; i < 100000; i++) {
            long id = mapping.getOrCreateSyntheticId("mass-" + i);
            assertFalse("Duplicate ID at iteration " + i + ": " + id, seen.contains(id));
            seen.add(id);
        }
    }

    // ---- 10. media render kind (migration 029) ----

    @Test
    public void mediaRenderKindMapsMediaTypes() {
        assertEquals("photo", CreangerMessageMapping.mediaRenderKind(MessageType.IMAGE));
        assertEquals("video", CreangerMessageMapping.mediaRenderKind(MessageType.VIDEO));
        assertEquals("audio", CreangerMessageMapping.mediaRenderKind(MessageType.AUDIO));
        assertEquals("voice", CreangerMessageMapping.mediaRenderKind(MessageType.VOICE));
        assertEquals("document", CreangerMessageMapping.mediaRenderKind(MessageType.DOCUMENT));
    }

    @Test
    public void mediaRenderKindFallsBackForTextAndUnknown() {
        assertEquals("document", CreangerMessageMapping.mediaRenderKind(MessageType.TEXT));
        assertEquals("document", CreangerMessageMapping.mediaRenderKind(null));
        assertEquals("document", CreangerMessageMapping.mediaRenderKind("photo"));
    }

    // ---- 11. image source URL (Media Phase 2 render path) ----

    private static MediaAttachment attWith(String publicUrl, String deliveryUrl) {
        return new MediaAttachment(null, null, "media-1", 0, null, "cloudinary", "k/x",
                publicUrl, deliveryUrl, "image/png", 100, "abc", 1, 1,
                null, null, null, null, null, null, "2026-08-15T12:00:00Z");
    }

    @Test
    public void imageSourceUrlPrefersPublicOverDelivery() {
        assertEquals("https://cdn.example/a.png",
                CreangerMessageMapping.imageSourceUrl(attWith("https://cdn.example/a.png", "https://signed/b")));
    }

    @Test
    public void imageSourceUrlFallsBackToDeliveryWhenNoPublic() {
        assertEquals("https://signed.example/dl?sig=abc",
                CreangerMessageMapping.imageSourceUrl(attWith(null, "https://signed.example/dl?sig=abc")));
    }

    @Test
    public void imageSourceUrlIsNullWhenNoUsableUrl() {
        assertNull("null attachment", CreangerMessageMapping.imageSourceUrl(null));
        assertNull("both urls null", CreangerMessageMapping.imageSourceUrl(attWith(null, null)));
        assertNull("blank urls treated as missing",
                CreangerMessageMapping.imageSourceUrl(attWith("   ", "  ")));
    }

    @Test
    public void imageSourceUrlRejectsBlankPublicOrdersDelivery() {
        // A public URL made of whitespace only must not be used as a source;
        // the valid delivery URL then wins.
        assertEquals("https://ok.example/x",
                CreangerMessageMapping.imageSourceUrl(attWith(" \t ", "https://ok.example/x")));
    }

    @Test
    public void imageSourceUrlFallsBackToLocalPathWhenNoRemoteUrl() {
        // When both publicUrl and deliveryUrl are absent, the localPath (a local
        // file path/URI) is used as the source for immediate pending preview.
        MediaAttachment att = new MediaAttachment(null, null, "media-1", 0, null, "local", "pending",
                null, null, "image/jpeg", 0, null, null, null, null,
                null, "/data/user/0/cache/pending_img.jpg", null, null, null, null);
        assertEquals("/data/user/0/cache/pending_img.jpg",
                CreangerMessageMapping.imageSourceUrl(att));
    }

    @Test
    public void imageSourceUrlPrefersPublicOverLocalPath() {
        // publicUrl takes precedence over localPath.
        MediaAttachment att = new MediaAttachment(null, null, "media-1", 0, null, "local", "pending",
                "https://cdn.example/img.jpg", null, "image/jpeg", 0, null, null, null, null,
                null, "/data/user/0/cache/pending_img.jpg", null, null, null, null);
        assertEquals("https://cdn.example/img.jpg",
                CreangerMessageMapping.imageSourceUrl(att));
    }

    @Test
    public void imageSourceUrlPrefersDeliveryOverLocalPath() {
        // deliveryUrl takes precedence over localPath.
        MediaAttachment att = new MediaAttachment(null, null, "media-1", 0, null, "local", "pending",
                null, "https://signed.example/dl", "image/jpeg", 0, null, null, null, null,
                null, "/data/user/0/cache/pending_img.jpg", null, null, null, null);
        assertEquals("https://signed.example/dl",
                CreangerMessageMapping.imageSourceUrl(att));
    }
}