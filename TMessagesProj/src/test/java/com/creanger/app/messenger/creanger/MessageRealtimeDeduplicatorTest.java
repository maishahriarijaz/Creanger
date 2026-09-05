package com.creanger.app.messenger.creanger;

import org.junit.Test;
import com.creanger.app.messenger.creanger.model.MessageModels.CreangerMessage;
import com.creanger.app.messenger.creanger.realtime.MessageRealtimeDeduplicator;

import static org.junit.Assert.*;

/**
 * Deduplicator tests: repeated Realtime events for the same chat are collapsed
 * by message id AND by {@code client_message_id}, per-chat state is isolated,
 * and {@link #clear} / {@link #clearAll} reset state for account isolation.
 */
public class MessageRealtimeDeduplicatorTest {

    private static CreangerMessage msg(String id, String clientId) {
        return new CreangerMessage(id, "chat-1", "sender", "text", "hi",
                "sent", clientId, 1L, "2026-08-16T10:00:00Z", null, null, null, false);
    }

    @Test
    public void firstDeliveryIsNewSecondIsDuplicate() {
        MessageRealtimeDeduplicator d = new MessageRealtimeDeduplicator();

        assertTrue(d.isNew("chat-1", msg("m-1", "cm-1")));
        assertFalse(d.isNew("chat-1", msg("m-1", "cm-1"))); // same id again
    }

    @Test
    public void duplicateByClientMessageIdIsIgnored() {
        MessageRealtimeDeduplicator d = new MessageRealtimeDeduplicator();

        assertTrue(d.isNew("chat-1", msg("m-1", "cm-7")));
        // A retry/echo with a different id but the same idempotency key.
        assertFalse(d.isNew("chat-1", msg("m-1b", "cm-7")));
    }

    @Test
    public void distinctMessageIdsAreAllNew() {
        MessageRealtimeDeduplicator d = new MessageRealtimeDeduplicator();

        assertTrue(d.isNew("chat-1", msg("m-1", "cm-1")));
        assertTrue(d.isNew("chat-1", msg("m-2", "cm-2")));
        assertTrue(d.isNew("chat-1", msg("m-3", null)));
    }

    @Test
    public void nullIdNeverDedupesAndClears() {
        MessageRealtimeDeduplicator d = new MessageRealtimeDeduplicator();

        assertTrue(d.isNew("chat-1", msg(null, null)));
        d.clear("chat-1");
        // After clear the same event is new again.
        assertTrue(d.isNew("chat-1", msg(null, null)));
    }

    @Test
    public void chatsAreIsolated() {
        MessageRealtimeDeduplicator d = new MessageRealtimeDeduplicator();
        CreangerMessage a = msg("m-1", "cm-1");
        CreangerMessage sameIdOtherChat = new CreangerMessage("m-1", "chat-2", "sender", "text",
                "hi", "sent", "cm-1", 1L, "2026-08-16T10:00:00Z", null, null, null, false);

        assertTrue(d.isNew("chat-1", a));
        assertTrue(d.isNew("chat-2", sameIdOtherChat)); // same id, different chat → new
    }

    @Test
    public void clearAllResetsEverything() {
        MessageRealtimeDeduplicator d = new MessageRealtimeDeduplicator();

        assertTrue(d.isNew("chat-1", msg("m-1", "cm-1")));
        d.clearAll();
        assertTrue(d.isNew("chat-1", msg("m-1", "cm-1")));
    }

    // ---- edits ----

    @Test
    public void firstEditIsNewExactEchoIsDuplicate() {
        MessageRealtimeDeduplicator d = new MessageRealtimeDeduplicator();
        CreangerMessage edit = msg("m-1", null);

        assertTrue(d.isNewEdit("chat-1", edit));
        assertFalse(d.isNewEdit("chat-1", edit)); // same content echo → duplicate
    }

    @Test
    public void contentChangingEditIsNewAgain() {
        MessageRealtimeDeduplicator d = new MessageRealtimeDeduplicator();
        CreangerMessage edit = msg("m-1", null);
        CreangerMessage edit2 = new CreangerMessage("m-1", "chat-1", "sender", "text",
                "new-content", "sent", null, 1L, "2026-08-16T10:00:00Z", null, null, null, false);

        assertTrue(d.isNewEdit("chat-1", edit));
        assertTrue(d.isNewEdit("chat-1", edit2)); // distinct edit → new
        assertFalse(d.isNewEdit("chat-1", edit2)); // echo of the distinct edit → duplicate
    }

    @Test
    public void editDedupIsPerChat() {
        MessageRealtimeDeduplicator d = new MessageRealtimeDeduplicator();
        CreangerMessage a = msg("m-1", null);
        CreangerMessage sameOtherChat = new CreangerMessage("m-1", "chat-2", "sender", "text",
                "hi", "sent", null, 1L, "2026-08-16T10:00:00Z", null, null, null, false);

        assertTrue(d.isNewEdit("chat-1", a));
        assertTrue(d.isNewEdit("chat-2", sameOtherChat)); // same id+content, other chat → new
    }

    // ---- deletes ----

    @Test
    public void deleteDeliveredOncePerChat() {
        MessageRealtimeDeduplicator d = new MessageRealtimeDeduplicator();

        assertTrue(d.isNewDelete("chat-1", "m-1"));
        assertFalse(d.isNewDelete("chat-1", "m-1"));
        assertTrue(d.isNewDelete("chat-1", "m-2"));
    }

    @Test
    public void deleteDedupIsolatesByChat() {
        MessageRealtimeDeduplicator d = new MessageRealtimeDeduplicator();

        assertTrue(d.isNewDelete("chat-1", "m-1"));
        assertTrue(d.isNewDelete("chat-2", "m-1")); // same id, other chat → new
    }

    @Test
    public void clearResetsEditAndDeleteState() {
        MessageRealtimeDeduplicator d = new MessageRealtimeDeduplicator();

        assertTrue(d.isNewEdit("chat-1", msg("m-1", null)));
        assertTrue(d.isNewDelete("chat-1", "m-1"));
        d.clear("chat-1");
        assertTrue(d.isNewDelete("chat-1", "m-1"));
        assertTrue(d.isNewEdit("chat-1", msg("m-1", null)));
    }
}