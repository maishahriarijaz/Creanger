package com.creanger.app.messenger.creanger;

import org.junit.Test;
import com.creanger.app.messenger.creanger.model.MessageModels.CreangerMessage;
import com.creanger.app.messenger.creanger.model.MessageModels.MessageStatus;
import com.creanger.app.messenger.creanger.realtime.RealtimeMessageParser;
import com.creanger.app.messenger.creanger.realtime.RealtimeMessageParser.Kind;
import com.creanger.app.messenger.creanger.realtime.RealtimeMessageParser.Result;

import static org.junit.Assert.*;

/**
 * Parser tests: converts raw Supabase Realtime (Phoenix) frames into
 * {@link CreangerMessage} events for the open chat. Covers INSERT (new
 * message), UPDATE (edit and soft-delete tombstone &rarr; delete), DELETE
 * events, the modern and legacy wire shapes, wrong-chat / wrong-table events
 * being ignored, malformed frames, and channel join (phx_reply) handling.
 */
public class RealtimeMessageParserTest {

    private static final String CHAT = "chat-1";

    /** Modern wire shape: payload.data.new carries the row. */
    private static String modernInsert(String id, String chatId, long chatSeq,
                                       String content, String clientMsgId, String sender) {
        return "{\"topic\":\"realtime:messages\",\"event\":\"postgres_changes\",\"payload\":{\"type\":\"postgres_changes\",\"data\":{"
                + "\"schema\":\"public\",\"table\":\"messages\",\"eventType\":\"INSERT\","
                + "\"new\":{\"id\":\"" + id + "\",\"chat_id\":\"" + chatId + "\",\"sender_id\":\"" + sender + "\","
                + "\"message_type\":\"text\",\"content\":\"" + content + "\",\"status\":\"sent\","
                + "\"client_message_id\":" + (clientMsgId == null ? "null" : "\"" + clientMsgId + "\"")
                + ",\"chat_seq\":" + chatSeq + ",\"created_at\":\"2026-08-16T10:00:00Z\","
                + "\"edited_at\":null,\"deleted_at\":null,\"updated_at\":\"2026-08-16T10:00:00Z\","
                + "\"is_system_message\":false,\"metadata\":{},\"topic_id\":null,\"reply_to_message_id\":null,\"scheduled_at\":null}"
                + "}}}";
    }

    /** Legacy wire shape: payload.record carries the row. */
    private static String legacyInsert(String id, String chatId, long chatSeq, String content) {
        return "{\"topic\":\"realtime:messages\",\"event\":\"postgres_changes\",\"payload\":{\"record\":{"
                + "\"id\":\"" + id + "\",\"chat_id\":\"" + chatId + "\",\"sender_id\":\"uuid-other\","
                + "\"message_type\":\"text\",\"content\":\"" + content + "\",\"status\":\"delivered\","
                + "\"chat_seq\":" + chatSeq + ",\"created_at\":\"2026-08-16T10:00:00Z\"}}}";
    }

    /** Modern UPDATE frame; {@code deletedAt} != null models a soft-delete tombstone. */
    private static String modernUpdate(String id, String chatId, String content, String deletedAt) {
        String deleted = deletedAt == null ? "null" : "\"" + deletedAt + "\"";
        return "{\"topic\":\"realtime:messages\",\"event\":\"postgres_changes\",\"payload\":{\"data\":{"
                + "\"schema\":\"public\",\"table\":\"messages\",\"eventType\":\"UPDATE\","
                + "\"new\":{\"id\":\"" + id + "\",\"chat_id\":\"" + chatId + "\",\"sender_id\":\"uuid-other\","
                + "\"message_type\":\"text\",\"content\":\"" + content + "\",\"status\":\"sent\","
                + "\"client_message_id\":null,\"chat_seq\":1,\"created_at\":\"2026-08-16T10:00:00Z\","
                + "\"edited_at\":" + (deletedAt == null ? "\"2026-08-16T11:00:00Z\"" : "null")
                + ",\"deleted_at\":" + deleted + ",\"updated_at\":\"2026-08-16T11:00:00Z\"}}}}";
    }

    /** Modern DELETE frame carrying only {@code old}. */
    private static String deleteEvent(String id, String chatId) {
        return "{\"topic\":\"realtime:messages\",\"event\":\"postgres_changes\",\"payload\":{\"data\":{"
                + "\"schema\":\"public\",\"table\":\"messages\",\"eventType\":\"DELETE\","
                + "\"old\":{\"id\":\"" + id + "\",\"chat_id\":\"" + chatId + "\",\"sender_id\":\"uuid-other\","
                + "\"message_type\":\"text\",\"content\":\"old\",\"chat_seq\":1,"
                + "\"created_at\":\"2026-08-16T10:00:00Z\"}}}}";
    }

    private static String joinReply(String status) {
        return "{\"topic\":\"realtime:messages\",\"event\":\"phx_reply\",\"payload\":{\"status\":\"" + status
                + "\"},\"ref\":\"1\"}";
    }

    /** Reaction frame on the shared channel: table message_reactions, no chat_id. */
    private static String reactionChange(String messageId, String userId, String reaction,
                                         String eventType, boolean inNew) {
        String body = "\"message_id\":\"" + messageId + "\",\"user_id\":\"" + userId + "\","
                + "\"reaction\":\"" + reaction + "\",\"is_custom_emoji\":false,\"custom_emoji_id\":null,"
                + "\"created_at\":\"2026-08-16T10:00:00Z\",\"updated_at\":\"2026-08-16T10:00:00Z\"";
        return "{\"topic\":\"realtime:messages\",\"event\":\"postgres_changes\",\"payload\":{\"data\":{"
                + "\"schema\":\"public\",\"table\":\"message_reactions\",\"eventType\":\"" + eventType + "\","
                + (inNew ? "\"new\":{" : "\"old\":{") + body + "}}}}";
    }

    /**
     * Modern UPDATE frame carrying a full {@code old} record, so the parser can
     * discriminate a status-only change (content equal, status differs) from a
     * content edit (content differs).
     */
    private static String modernUpdateStatus(String id, String chatId, String oldContent, String newContent,
                                             String oldStatus, String newStatus) {
        return "{\"topic\":\"realtime:messages\",\"event\":\"postgres_changes\",\"payload\":{\"data\":{"
                + "\"schema\":\"public\",\"table\":\"messages\",\"eventType\":\"UPDATE\","
                + "\"new\":{\"id\":\"" + id + "\",\"chat_id\":\"" + chatId + "\",\"sender_id\":\"uuid-other\","
                + "\"message_type\":\"text\",\"content\":\"" + newContent + "\",\"status\":\"" + newStatus + "\","
                + "\"client_message_id\":null,\"chat_seq\":1,\"created_at\":\"2026-08-16T10:00:00Z\","
                + "\"edited_at\":null,\"deleted_at\":null,\"updated_at\":\"2026-08-16T12:00:00Z\"},"
                + "\"old\":{\"id\":\"" + id + "\",\"chat_id\":\"" + chatId + "\","
                + "\"content\":\"" + oldContent + "\",\"status\":\"" + oldStatus + "\"}}}}";
    }

    // ---- new realtime message ----

    @Test
    public void modernInsertParsesMessagePreservingClientIdAndSeq() {
        Result r = RealtimeMessageParser.parse(CHAT,
                modernInsert("m-100", CHAT, 42, "hello", "cm-abc", "uuid-other"));

        assertEquals(Kind.MESSAGE_INSERT, r.kind);
        assertEquals(CHAT, r.chatId);
        assertNotNull(r.message);
        assertEquals("m-100", r.message.id);
        assertEquals(CHAT, r.message.chatId);
        assertEquals("uuid-other", r.message.senderId);
        assertEquals("hello", r.message.content);
        assertEquals(Long.valueOf(42L), r.message.chatSeq);
        assertEquals("cm-abc", r.message.clientMessageId); // preserved
        assertEquals(MessageStatus.SENT, r.message.status);
        assertFalse(r.message.isLocal); // server row, never local
    }

    @Test
    public void legacyInsertParsesMessage() {
        Result r = RealtimeMessageParser.parse(CHAT, legacyInsert("m-1", CHAT, 3, "hey"));

        assertEquals(Kind.MESSAGE_INSERT, r.kind);
        assertEquals("m-1", r.message.id);
        assertEquals(Long.valueOf(3L), r.message.chatSeq);
        assertEquals(MessageStatus.DELIVERED, r.message.status);
    }

    // ---- wrong-chat event ignored ----

    @Test
    public void wrongChatInsertIsIgnored() {
        Result r = RealtimeMessageParser.parse(CHAT,
                modernInsert("m-9", "chat-OTHER", 7, "nope", null, "uuid-other"));

        assertEquals(Kind.IGNORED, r.kind);
        assertNull(r.message);
    }

    @Test
    public void wrongTableInsertIsIgnored() {
        String frame = modernInsert("m-1", CHAT, 1, "x", null, "u").replace("realtime:messages",
                "realtime:message_reactions").replace("\"table\":\"messages\"", "\"table\":\"message_reactions\"");
        assertEquals(Kind.IGNORED, RealtimeMessageParser.parse(CHAT, frame).kind);
    }

    @Test
    public void updateEditIsParsedAsMessageEdit() {
        Result r = RealtimeMessageParser.parse(CHAT,
                modernUpdate("m-1", CHAT, "edited", null));
        assertEquals(Kind.MESSAGE_EDIT, r.kind);
        assertEquals(CHAT, r.chatId);
        assertNotNull(r.message);
        assertEquals("m-1", r.message.id);
        assertEquals("edited", r.message.content);
        assertNotNull(r.message.editedAt);
        assertNull(r.deletedMessageId);
    }

    @Test
    public void updateWithDeletedAtIsAParsedDelete() {
        Result r = RealtimeMessageParser.parse(CHAT,
                modernUpdate("m-1", CHAT, "gone", "2026-08-16T12:00:00Z"));
        assertEquals(Kind.MESSAGE_DELETE, r.kind);
        assertEquals(CHAT, r.chatId);
        assertEquals("m-1", r.deletedMessageId);
        assertNull(r.message);
    }

    @Test
    public void wrongChatUpdateIsIgnored() {
        assertEquals(Kind.IGNORED,
                RealtimeMessageParser.parse(CHAT, modernUpdate("m-1", "chat-OTHER", "x", null)).kind);
    }

    // ---- status-only updates (mark_message_status RPC, migration 026) ----

    @Test
    public void statusOnlyUpdateParsesAsMessageStatus() {
        Result r = RealtimeMessageParser.parse(CHAT,
                modernUpdateStatus("m-1", CHAT, "hi", "hi", "sent", "read"));

        assertEquals(Kind.MESSAGE_STATUS, r.kind);
        assertEquals(CHAT, r.chatId);
        assertNotNull(r.message);
        assertEquals("m-1", r.message.id);
        assertEquals("hi", r.message.content);
        assertEquals(MessageStatus.READ, r.message.status);
        assertNull(r.deletedMessageId);
    }

    @Test
    public void contentChangeParsesAsEditEvenWhenStatusAlsoChanges() {
        Result r = RealtimeMessageParser.parse(CHAT,
                modernUpdateStatus("m-1", CHAT, "hi", "edited", "sent", "read"));

        assertEquals(Kind.MESSAGE_EDIT, r.kind);
        assertEquals("edited", r.message.content);
    }

    @Test
    public void wrongChatStatusUpdateIsIgnored() {
        assertEquals(Kind.IGNORED, RealtimeMessageParser.parse(CHAT,
                modernUpdateStatus("m-1", "chat-OTHER", "hi", "hi", "sent", "read")).kind);
    }

    @Test
    public void tombstoneWinsOverStatusDiscrimination() {
        String frame = modernUpdateStatus("m-1", CHAT, "hi", "hi", "sent", "read")
                .replace("\"deleted_at\":null", "\"deleted_at\":\"2026-08-16T13:00:00Z\"");
        Result r = RealtimeMessageParser.parse(CHAT, frame);

        assertEquals(Kind.MESSAGE_DELETE, r.kind);
        assertEquals("m-1", r.deletedMessageId);
    }

    @Test
    public void deleteEventWithOldIsAParsedDelete() {
        Result r = RealtimeMessageParser.parse(CHAT, deleteEvent("m-1", CHAT));
        assertEquals(Kind.MESSAGE_DELETE, r.kind);
        assertEquals(CHAT, r.chatId);
        assertEquals("m-1", r.deletedMessageId);
    }

    @Test
    public void deleteEventWithMissingOldIsIgnored() {
        String frame = "{\"topic\":\"realtime:messages\",\"event\":\"postgres_changes\",\"payload\":{\"data\":{"
                + "\"schema\":\"public\",\"table\":\"messages\",\"eventType\":\"DELETE\"}}}";
        assertEquals(Kind.IGNORED, RealtimeMessageParser.parse(CHAT, frame).kind);
    }

    @Test
    public void wrongChatDeleteEventIsIgnored() {
        assertEquals(Kind.IGNORED,
                RealtimeMessageParser.parse(CHAT, deleteEvent("m-1", "chat-OTHER")).kind);
    }

    @Test
    public void unrelatedChannelIsIgnored() {
        assertEquals(Kind.IGNORED,
                RealtimeMessageParser.parse(CHAT, "{\"topic\":\"presence:online_users\",\"event\":\"presence_state\"}").kind);
    }

    // ---- message_reactions events (migration 027) ----

    @Test
    public void reactionInsertParsesAsReactionChangeAdded() {
        Result r = RealtimeMessageParser.parse(CHAT,
                reactionChange("m-1", "user-a", "\uD83D\uDC4D", "INSERT", true));

        assertEquals(Kind.REACTION_CHANGE, r.kind);
        assertTrue(r.reactionAdded);
        assertNotNull(r.reaction);
        assertEquals("m-1", r.reaction.messageId);
        assertEquals("user-a", r.reaction.userId);
        assertEquals("\uD83D\uDC4D", r.reaction.reaction);
        assertFalse(r.reaction.isCustomEmoji);
    }

    @Test
    public void reactionUpdateParsesAsReactionChangeAdded() {
        Result r = RealtimeMessageParser.parse(CHAT,
                reactionChange("m-1", "user-a", "\uD83D\uDC4D", "UPDATE", true));

        assertEquals(Kind.REACTION_CHANGE, r.kind);
        assertTrue(r.reactionAdded);
        assertEquals("m-1", r.reaction.messageId);
    }

    @Test
    public void reactionDeleteParsesAsReactionChangeRemoved() {
        Result r = RealtimeMessageParser.parse(CHAT,
                reactionChange("m-1", "user-a", "\uD83D\uDC4D", "DELETE", false));

        assertEquals(Kind.REACTION_CHANGE, r.kind);
        assertFalse(r.reactionAdded);
        assertEquals("m-1", r.reaction.messageId);
        assertEquals("user-a", r.reaction.userId);
    }

    @Test
    public void reactionRowWithoutMessageIdIsIgnored() {
        String frame = "{\"topic\":\"realtime:messages\",\"event\":\"postgres_changes\",\"payload\":{\"data\":{"
                + "\"schema\":\"public\",\"table\":\"message_reactions\",\"eventType\":\"INSERT\","
                + "\"new\":{\"user_id\":\"user-a\",\"reaction\":\"\uD83D\uDC4D\"}}}}";
        assertEquals(Kind.IGNORED, RealtimeMessageParser.parse(CHAT, frame).kind);
    }

    // ---- join/handshake ----

    @Test
    public void joinAckIsReportedAsJoined() {
        assertEquals(Kind.JOINED, RealtimeMessageParser.parse(CHAT, joinReply("ok")).kind);
    }

    @Test
    public void joinRejectedIsUnauthorized() {
        assertEquals(Kind.UNAUTHORIZED, RealtimeMessageParser.parse(CHAT, joinReply("error")).kind);
    }

    // ---- malformed ----

    @Test
    public void malformedJsonIsMalformed() {
        assertEquals(Kind.MALFORMED, RealtimeMessageParser.parse(CHAT, "{not json").kind);
        assertEquals(Kind.MALFORMED, RealtimeMessageParser.parse(CHAT, "[]").kind);
    }

    @Test
    public void emptyFrameIsIgnored() {
        assertEquals(Kind.IGNORED, RealtimeMessageParser.parse(CHAT, "   ").kind);
        assertEquals(Kind.IGNORED, RealtimeMessageParser.parse(CHAT, null).kind);
    }

    // ---- media message inserts (migration 029) ----

    /** Modern INSERT frame for a media message; realtime rows carry NO attachment expand. */
    private static String mediaInsert(String id, String chatId, long chatSeq, String messageType, String caption) {
        String content = caption == null ? "null" : "\"" + caption + "\"";
        return "{\"topic\":\"realtime:messages\",\"event\":\"postgres_changes\",\"payload\":{\"data\":{"
                + "\"schema\":\"public\",\"table\":\"messages\",\"eventType\":\"INSERT\","
                + "\"new\":{\"id\":\"" + id + "\",\"chat_id\":\"" + chatId + "\",\"sender_id\":\"uuid-other\","
                + "\"message_type\":\"" + messageType + "\",\"content\":" + content + ",\"status\":\"sent\","
                + "\"client_message_id\":null,\"chat_seq\":" + chatSeq
                + ",\"created_at\":\"2026-08-16T10:00:00Z\","
                + "\"edited_at\":null,\"deleted_at\":null,\"updated_at\":\"2026-08-16T10:00:00Z\","
                + "\"reply_to_message_id\":null}}}}";
    }

    @Test
    public void mediaInsertParsesMessageTypeAndCaption() {
        Result r = RealtimeMessageParser.parse(CHAT,
                mediaInsert("m-img", CHAT, 51, "image", "look at this"));

        assertEquals(Kind.MESSAGE_INSERT, r.kind);
        assertNotNull(r.message);
        assertEquals("image", r.message.messageType);
        assertTrue(r.message.isMedia());
        assertEquals("look at this", r.message.content);
        // Realtime delivers the bare messages row; attachment metadata is filled
        // by the repository snapshot (recoverAttachments), not by the frame.
        assertTrue(r.message.attachments.isEmpty());
    }

    @Test
    public void voiceInsertParsesTypeWithoutCaption() {
        Result r = RealtimeMessageParser.parse(CHAT,
                mediaInsert("m-voice", CHAT, 52, "voice", null));

        assertEquals(Kind.MESSAGE_INSERT, r.kind);
        assertNotNull(r.message);
        assertEquals("voice", r.message.messageType);
        assertNull(r.message.content);
        assertEquals(MessageStatus.SENT, r.message.status);
    }

    @Test
    public void wrongChatMediaInsertIsIgnored() {
        assertEquals(Kind.IGNORED,
                RealtimeMessageParser.parse(CHAT, mediaInsert("m-9", "chat-OTHER", 53, "document", "x")).kind);
    }
}