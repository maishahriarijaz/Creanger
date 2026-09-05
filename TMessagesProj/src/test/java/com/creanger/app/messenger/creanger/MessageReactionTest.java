package com.creanger.app.messenger.creanger;

import org.junit.Test;
import com.creanger.app.messenger.creanger.api.ApiRequest;
import com.creanger.app.messenger.creanger.api.SupabaseAuthClient;
import com.creanger.app.messenger.creanger.api.CreangerApiException;
import com.creanger.app.messenger.creanger.api.CreangerChatApiClient;
import com.creanger.app.messenger.creanger.api.CreangerHttpTransport;
import com.creanger.app.messenger.creanger.api.TransportResponse;
import com.creanger.app.messenger.creanger.auth.CreangerAuthEngine;
import com.creanger.app.messenger.creanger.data.MessageRepository;
import com.creanger.app.messenger.creanger.model.AuthModels.AuthSession;
import com.creanger.app.messenger.creanger.model.AuthModels.CreangerUser;
import com.creanger.app.messenger.creanger.model.MessageModels.CreangerMessage;
import com.creanger.app.messenger.creanger.model.MessageModels.MessageReaction;
import com.creanger.app.messenger.creanger.model.MessageModels.MessageStatus;
import com.creanger.app.messenger.creanger.model.MessageModels.MessageType;
import com.creanger.app.messenger.creanger.model.MessageModels.ReactionSummary;
import com.creanger.app.messenger.creanger.storage.CreangerTokenStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Message-reaction tests (migration 027) on {@link MessageRepository}:
 *
 *  - realtime add/remove reaction rows updating per-reaction distinct-user
 *    counts and the current user's {@code chosen} flag, with idempotent
 *    re-deliveries and out-of-order toggles never corrupting state
 *  - one user holding SEVERAL reactions on the same message (each distinct
 *    reaction is its own entry; the UNIQUE constraint is per reaction)
 *  - the {@code add_reaction}/{@code remove_reaction} RPCs: correct
 *    path/arguments, applied as-is, and the server staying authoritative
 *  - reconnect/page-load recovery via the REST {@code message_reactions}
 *    snapshot REPLACING local state (reactions can be deleted, so experience
 *    removals are reconstructed from the fresh fetch)
 *  - reaction state dropped with its deleted message and cleared on logout /
 *    account switch (per-owner keying)
 */
public class MessageReactionTest {

    private static final String OWNER = "uuid-1";
    private static final String SENDER = "uuid-2";
    private static final String CHAT = "chat-1";
    private static final String THUMBS = "\uD83D\uDC4D";
    private static final String HEART = "\u2764\uFE0F";

    private static final class ScriptedTransport implements CreangerHttpTransport {
        final List<TransportResponse> responses = new ArrayList<>();
        final List<ApiRequest> requests = new ArrayList<>();

        @Override
        public TransportResponse execute(ApiRequest request) throws IOException {
            requests.add(request);
            TransportResponse response = responses.remove(0);
            if (response == null) {
                throw new IOException("no canned response for " + request.method + " " + request.path);
            }
            return response;
        }

        TransportResponse json(int status, String body) {
            return new TransportResponse(status, body, null);
        }
    }

    private static final class InMemoryStore implements CreangerTokenStore {
        volatile AuthSession stored;

        @Override
        public AuthSession load() {
            return stored;
        }

        @Override
        public void store(AuthSession session) {
            this.stored = session;
        }

        @Override
        public void clear() {
            this.stored = null;
        }
    }

    private static MessageRepository repo(ScriptedTransport t, InMemoryStore store) {
        store.store(new AuthSession("acc-live", "ref",
                new CreangerUser(OWNER, "alice", "a@x.com", true, null, null, null),
                System.currentTimeMillis()));
        return new MessageRepository(new CreangerAuthEngine(new SupabaseAuthClient(t), store),
                new CreangerChatApiClient(t));
    }

    private static String msgRow(String id, long chatSeq, String content, String status) {
        return "{\"id\":\"" + id + "\",\"chat_id\":\"" + CHAT + "\",\"sender_id\":\"" + SENDER + "\","
                + "\"message_type\":\"text\",\"content\":\"" + content + "\",\"status\":\"" + status + "\","
                + "\"client_message_id\":null,\"chat_seq\":" + chatSeq
                + ",\"created_at\":\"2026-08-15T08:01:00Z\",\"edited_at\":null,\"deleted_at\":null,"
                + "\"updated_at\":\"2026-08-15T08:01:00Z\"}";
    }

    private static String rowsJson(String... rows) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < rows.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(rows[i]);
        }
        return sb.append(']').toString();
    }

    /** Reaction row as returned by the REST data plane (keys: message_id…). */
    private static String reactionRow(String messageId, String userId, String reaction) {
        return "{\"message_id\":\"" + messageId + "\",\"user_id\":\"" + userId + "\",\"reaction\":\""
                + reaction + "\",\"is_custom_emoji\":false,\"custom_emoji_id\":null,"
                + "\"created_at\":\"2026-08-16T09:00:00Z\",\"updated_at\":\"2026-08-16T09:00:00Z\"}";
    }

    private static MessageReaction reaction(String messageId, String userId, String reaction) {
        return new MessageReaction(messageId, userId, reaction, false, null,
                "2026-08-16T09:00:00Z", "2026-08-16T09:00:00Z");
    }

    private static MessageReaction customReaction(String messageId, String userId,
                                                  String customEmojiId) {
        return new MessageReaction(messageId, userId, customEmojiId, true, customEmojiId,
                "2026-08-16T09:00:00Z", "2026-08-16T09:00:00Z");
    }

    private static ReactionSummary find(List<ReactionSummary> summaries, String reaction) {
        for (ReactionSummary s : summaries) {
            if (reaction.equals(s.reaction)) {
                return s;
            }
        }
        return null;
    }

    /** Finds the custom/Premium reaction summary for a given custom emoji id. */
    private static ReactionSummary findCustom(List<ReactionSummary> summaries, String customEmojiId) {
        for (ReactionSummary s : summaries) {
            if (s.isCustomEmoji && customEmojiId.equals(s.customEmojiId)) {
                return s;
            }
        }
        return null;
    }

    private static List<ApiRequest> requestsFor(List<ApiRequest> requests, String path) {
        List<ApiRequest> out = new ArrayList<>();
        for (ApiRequest r : requests) {
            if (path.equals(r.path)) {
                out.add(r);
            }
        }
        return out;
    }

    // ---- realtime reaction apply + summaries ----

    @Test
    public void realtimeAddAndRemoveBuildDistinctUserCounts() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        MessageRepository mr = repo(t, store);
        t.responses.add(t.json(200, rowsJson(msgRow("m-1", 1, "hi", MessageStatus.SENT))));
        mr.refreshMessages(CHAT, 30);

        assertTrue(mr.applyRealtimeReaction(CHAT, reaction("m-1", SENDER, THUMBS), true));
        assertTrue(mr.applyRealtimeReaction(CHAT, reaction("m-1", OWNER, THUMBS), true));
        assertTrue(mr.applyRealtimeReaction(CHAT, reaction("m-1", "uuid-3", THUMBS), true));

        List<ReactionSummary> summaries = mr.getReactionSummaries(CHAT, "m-1");
        ReactionSummary thumbs = find(summaries, THUMBS);
        assertNotNull(thumbs);
        assertEquals(3, thumbs.count); // three DISTINCT users
        assertTrue(thumbs.chosen);     // the owner reacted too
        assertFalse(thumbs.isCustomEmoji);

        // Removal of a non-owner user keeps the count honest; idempotent.
        assertTrue(mr.applyRealtimeReaction(CHAT, reaction("m-1", "uuid-3", THUMBS), false));
        assertFalse(mr.applyRealtimeReaction(CHAT, reaction("m-1", "uuid-3", THUMBS), false));
        assertEquals(2, find(mr.getReactionSummaries(CHAT, "m-1"), THUMBS).count);
    }

    @Test
    public void duplicateAndOutOfOrderReactionFramesCollapse() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        MessageRepository mr = repo(t, store);
        t.responses.add(t.json(200, rowsJson(msgRow("m-1", 1, "hi", MessageStatus.SENT))));
        mr.refreshMessages(CHAT, 30);

        assertTrue(mr.applyRealtimeReaction(CHAT, reaction("m-1", OWNER, THUMBS), true));
        // Re-delivered by Realtime: nothing changes.
        assertFalse(mr.applyRealtimeReaction(CHAT, reaction("m-1", OWNER, THUMBS), true));
        assertEquals(1, find(mr.getReactionSummaries(CHAT, "m-1"), THUMBS).count);

        // Out-of-order remove then re-add keeps the count consistent.
        assertTrue(mr.applyRealtimeReaction(CHAT, reaction("m-1", OWNER, THUMBS), false));
        assertTrue(mr.applyRealtimeReaction(CHAT, reaction("m-1", OWNER, THUMBS), true));
        assertEquals(1, find(mr.getReactionSummaries(CHAT, "m-1"), THUMBS).count);
        assertTrue(find(mr.getReactionSummaries(CHAT, "m-1"), THUMBS).chosen);
    }

    @Test
    public void oneUserMayHoldSeveralDistinctReactions() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        MessageRepository mr = repo(t, store);
        t.responses.add(t.json(200, rowsJson(msgRow("m-1", 1, "hi", MessageStatus.SENT))));
        mr.refreshMessages(CHAT, 30);

        assertTrue(mr.applyRealtimeReaction(CHAT, reaction("m-1", OWNER, THUMBS), true));
        assertTrue(mr.applyRealtimeReaction(CHAT, reaction("m-1", OWNER, HEART), true));
        assertTrue(mr.applyRealtimeReaction(CHAT, reaction("m-1", SENDER, HEART), true));

        List<ReactionSummary> summaries = mr.getReactionSummaries(CHAT, "m-1");
        assertEquals(1, find(summaries, THUMBS).count); // only the owner
        assertEquals(2, find(summaries, HEART).count);  // owner + sender
        assertTrue(find(summaries, HEART).chosen);
    }

    @Test
    public void reactionForMessageOutsideLoadedWindowIsRejected() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        MessageRepository mr = repo(t, store);
        t.responses.add(t.json(200, rowsJson(msgRow("m-1", 1, "hi", MessageStatus.SENT))));
        mr.refreshMessages(CHAT, 30);

        assertFalse(mr.isMessageKnown(CHAT, "m-999"));
        assertTrue(mr.isMessageKnown(CHAT, "m-1"));

        // A realtime row for an unknown message (other chat, not-yet-loaded
        // window) must not fabricate state.
        assertFalse(mr.applyRealtimeReaction(CHAT, reaction("m-999", SENDER, THUMBS), true));
        assertNull(find(mr.getReactionSummaries(CHAT, "m-999"), THUMBS));
    }

    @Test
    public void customPremiumReactionCountsSeparatelyFromEmoji() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        MessageRepository mr = repo(t, store);
        t.responses.add(t.json(200, rowsJson(msgRow("m-1", 1, "hi", MessageStatus.SENT))));
        mr.refreshMessages(CHAT, 30);

        // The SAME string as an emoji and as a custom/Premium reaction must be
        // two DISTINCT summaries, each with its own count and custom fields.
        assertTrue(mr.applyRealtimeReaction(CHAT, reaction("m-1", SENDER, THUMBS), true));
        assertTrue(mr.applyRealtimeReaction(CHAT, customReaction("m-1", SENDER, "sticker-1"), true));
        assertTrue(mr.applyRealtimeReaction(CHAT, customReaction("m-1", OWNER, "sticker-1"), true));

        List<ReactionSummary> summaries = mr.getReactionSummaries(CHAT, "m-1");
        ReactionSummary emoji = find(summaries, THUMBS);
        ReactionSummary custom = findCustom(summaries, "sticker-1");
        assertNotNull(emoji);
        assertNotNull(custom);
        assertEquals(1, emoji.count);
        assertFalse(emoji.isCustomEmoji);
        assertNull(emoji.customEmojiId);
        assertEquals(2, custom.count); // sender + owner, DISTINCT users
        assertTrue(custom.isCustomEmoji);
        assertEquals("sticker-1", custom.customEmojiId);
        assertTrue(custom.chosen);

        // Removing the custom row collapses only the custom summary.
        assertTrue(mr.applyRealtimeReaction(CHAT, customReaction("m-1", SENDER, "sticker-1"), false));
        assertEquals(1, findCustom(mr.getReactionSummaries(CHAT, "m-1"), "sticker-1").count);
        assertEquals(1, find(summaries, THUMBS).count); // emoji untouched
    }

    @Test
    public void customPremiumRpcSendsCustomEmojiArgs() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        MessageRepository mr = repo(t, store);
        t.responses.add(t.json(200, rowsJson(msgRow("m-1", 1, "hi", MessageStatus.SENT))));
        mr.refreshMessages(CHAT, 30);

        t.responses.add(t.json(200, "\"m-1\""));
        assertEquals("m-1", mr.addReaction(CHAT, "m-1", "sticker-1", true, "sticker-1"));

        t.responses.add(t.json(200, "\"m-1\""));
        assertEquals("m-1", mr.removeReaction(CHAT, "m-1", "sticker-1", true, "sticker-1"));

        List<ApiRequest> adds = requestsFor(t.requests, "/rest/v1/rpc/add_reaction");
        List<ApiRequest> removes = requestsFor(t.requests, "/rest/v1/rpc/remove_reaction");
        assertEquals(1, adds.size());
        assertEquals(1, removes.size());
        assertTrue(adds.get(0).jsonBody.contains("\"p_reaction\":\"sticker-1\""));
        assertTrue(adds.get(0).jsonBody.contains("\"p_is_custom_emoji\":true"));
        assertTrue(adds.get(0).jsonBody.contains("\"p_custom_emoji_id\":\"sticker-1\""));
        assertTrue(removes.get(0).jsonBody.contains("\"p_is_custom_emoji\":true"));
        assertTrue(removes.get(0).jsonBody.contains("\"p_custom_emoji_id\":\"sticker-1\""));
    }

    // ---- add/remove RPCs ----

    @Test
    public void addReactionCallsTheRpc() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        MessageRepository mr = repo(t, store);
        t.responses.add(t.json(200, rowsJson(msgRow("m-1", 1, "hi", MessageStatus.SENT))));
        mr.refreshMessages(CHAT, 30);

        t.responses.add(t.json(200, "\"m-1\""));
        assertEquals("m-1", mr.addReaction(CHAT, "m-1", THUMBS));

        List<ApiRequest> rpcs = requestsFor(t.requests, "/rest/v1/rpc/add_reaction");
        assertEquals(1, rpcs.size());
        assertTrue(rpcs.get(0).jsonBody.contains("\"p_message_id\":\"m-1\""));
        assertTrue(rpcs.get(0).jsonBody.contains("\"p_reaction\":\"" + THUMBS + "\""));
    }

    @Test
    public void removeReactionCallsTheRpc() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        MessageRepository mr = repo(t, store);
        t.responses.add(t.json(200, rowsJson(msgRow("m-1", 1, "hi", MessageStatus.SENT))));
        mr.refreshMessages(CHAT, 30);

        t.responses.add(t.json(200, "\"m-1\""));
        assertEquals("m-1", mr.removeReaction(CHAT, "m-1", THUMBS));

        List<ApiRequest> rpcs = requestsFor(t.requests, "/rest/v1/rpc/remove_reaction");
        assertEquals(1, rpcs.size());
        assertTrue(rpcs.get(0).jsonBody.contains("\"p_message_id\":\"m-1\""));
        assertTrue(rpcs.get(0).jsonBody.contains("\"p_reaction\":\"" + THUMBS + "\""));
    }

    @Test
    public void reactionRpcUnauthorizedSurfacesError() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        MessageRepository mr = repo(t, store);
        t.responses.add(t.json(200, rowsJson(msgRow("m-1", 1, "hi", MessageStatus.SENT))));
        mr.refreshMessages(CHAT, 30);

        t.responses.add(t.json(403, ""));
        try {
            mr.addReaction(CHAT, "m-1", THUMBS);
            fail("expected CreangerApiException for unauthorized reaction");
        } catch (CreangerApiException e) {
            assertEquals(403, e.statusCode);
        }
    }

    // ---- snapshot recovery (reconnect / page load) ----

    @Test
    public void recoverReactionsReplacesStateIncludingRemovals() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        MessageRepository mr = repo(t, store);
        t.responses.add(t.json(200, rowsJson(msgRow("m-1", 1, "hi", MessageStatus.SENT),
                msgRow("m-2", 2, "yo", MessageStatus.SENT))));
        mr.refreshMessages(CHAT, 30);

        // Local (pre-reconnect) state: two people on m-1's thumbs.
        assertTrue(mr.applyRealtimeReaction(CHAT, reaction("m-1", SENDER, THUMBS), true));
        assertTrue(mr.applyRealtimeReaction(CHAT, reaction("m-1", OWNER, THUMBS), true));

        // While offline, one of them removed the reaction and m-2 gained one.
        // The fresh snapshot (message_reactions?message_id=in.()) is REPLACED
        // wholesale — a watermark cursor could never reconstruct the removal.
        t.responses.add(t.json(200, rowsJson(
                reactionRow("m-1", OWNER, THUMBS),
                reactionRow("m-2", SENDER, HEART))));
        List<String> ids = new ArrayList<>();
        ids.add("m-1");
        ids.add("m-2");
        mr.recoverReactions(CHAT, ids);

        List<ReactionSummary> m1 = mr.getReactionSummaries(CHAT, "m-1");
        assertEquals(1, find(m1, THUMBS).count); // SENDER's row removed by the snapshot
        assertTrue(find(m1, THUMBS).chosen);

        ReactionSummary m2Heart = find(mr.getReactionSummaries(CHAT, "m-2"), HEART);
        assertNotNull(m2Heart);
        assertEquals(1, m2Heart.count);
    }

    @Test
    public void recoverReactionsQueryUsesInListOnMessageReactions() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        MessageRepository mr = repo(t, store);
        t.responses.add(t.json(200, rowsJson(msgRow("m-1", 1, "hi", MessageStatus.SENT))));
        mr.refreshMessages(CHAT, 30);

        t.responses.add(t.json(200, "[]"));
        mr.recoverReactions(CHAT, java.util.Collections.singletonList("m-1"));

        List<ApiRequest> lists = requestsFor(t.requests, "/rest/v1/message_reactions");
        assertEquals(1, lists.size());
        assertEquals("in.(m-1)", lists.get(0).query.get("message_id"));
    }

    // ---- lifecycle ----

    @Test
    public void reactionStateIsDroppedWithItsDeletedMessage() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        MessageRepository mr = repo(t, store);
        t.responses.add(t.json(200, rowsJson(msgRow("m-1", 1, "hi", MessageStatus.SENT))));
        mr.refreshMessages(CHAT, 30);
        assertTrue(mr.applyRealtimeReaction(CHAT, reaction("m-1", SENDER, THUMBS), true));

        assertTrue(mr.applyRealtimeDelete(CHAT, "m-1"));
        assertNull(find(mr.getReactionSummaries(CHAT, "m-1"), THUMBS));
    }

    @Test
    public void clearCachedMessagesDropsReactionState() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        MessageRepository mr = repo(t, store);
        t.responses.add(t.json(200, rowsJson(msgRow("m-1", 1, "hi", MessageStatus.SENT))));
        mr.refreshMessages(CHAT, 30);
        assertTrue(mr.applyRealtimeReaction(CHAT, reaction("m-1", SENDER, THUMBS), true));

        mr.clearCachedMessages();
        assertTrue(mr.getCachedMessages(CHAT).isEmpty());
        assertNull(find(mr.getReactionSummaries(CHAT, "m-1"), THUMBS));
    }
}