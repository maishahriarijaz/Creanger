package com.creanger.app.messenger.creanger;

import com.creanger.app.messenger.creanger.api.ApiRequest;
import com.creanger.app.messenger.creanger.api.CreangerChatApiClient;
import com.creanger.app.messenger.creanger.api.CreangerHttpTransport;
import com.creanger.app.messenger.creanger.api.TransportResponse;
import com.creanger.app.messenger.creanger.model.MessageModels.CreangerMessage;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Contract tests for {@link CreangerChatApiClient} against a scripted
 * transport: proves owner/topic scoping of drafts, owner_id on media
 * inserts, the canonical 5-arg send_text contract, chatId recovery
 * defaulting and the pin_message RPC shape.
 */
public class CreangerChatApiClientTest {

    private static final class ScriptedTransport implements CreangerHttpTransport {
        final List<ApiRequest> requests = new ArrayList<>();
        String responseBody = "{}";
        int statusCode = 200;

        @Override
        public TransportResponse execute(ApiRequest request) {
            requests.add(request);
            return new TransportResponse(statusCode, responseBody, null);
        }

        ApiRequest last() {
            return requests.get(requests.size() - 1);
        }
    }

    private static CreangerChatApiClient client(ScriptedTransport t) {
        return new CreangerChatApiClient(t);
    }

    @Test
    public void insertMediaIncludesOwnerId() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        t.responseBody = "[{\"id\":\"media-1\"}]";

        String id = client(t).insertMedia("acc", "owner-9", "imagebb", "k1", "https://p.url/i",
                null, "image/png", 100L, 10, 20, null);

        assertEquals("media-1", id);
        ApiRequest insert = null;
        for (ApiRequest r : t.requests) {
            if (r.path.contains("/rest/v1/media") && "POST".equals(r.method)) {
                insert = r;
            }
        }
        assertNotNull(insert);
        JSONObject row = new JSONArray(insert.jsonBody).getJSONObject(0);
        assertEquals("owner-9", row.getString("owner_id"));
    }

    @Test
    public void draftSaveIncludesOwnerAndScopesByUser() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        t.responseBody = "[]"; // no existing row -> INSERT path

        client(t).saveDraft("acc", "owner-A", "chat-1", "hello", null, null);

        ApiRequest insert = t.last();
        assertTrue(insert.path.contains("/rest/v1/drafts"));
        assertEquals("owner-A", new JSONArray(insert.jsonBody)
                .getJSONObject(0).getString("user_id"));
    }

    @Test
    public void draftLookupScopedByUserAndTopic() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        t.responseBody = "[]";

        client(t).getDraft("acc", "owner-A", "chat-1", "topic-7");
        ApiRequest topicGet = t.last();
        assertEquals("eq.owner-A", topicGet.query.get("user_id"));
        assertEquals("eq.chat-1", topicGet.query.get("chat_id"));
        assertEquals("eq.topic-7", topicGet.query.get("topic_id"));

        client(t).getDraft("acc", "owner-A", "chat-1", null);
        ApiRequest mainGet = t.last();
        assertEquals("eq.owner-A", mainGet.query.get("user_id"));
        assertEquals("is.null", mainGet.query.get("topic_id"));

        // A different user resolves to a different scope: no cross-user access.
        client(t).deleteDraft("acc", "owner-B", "chat-1", null);
        ApiRequest del = t.last();
        assertEquals("eq.owner-B", del.query.get("user_id"));
        assertEquals("is.null", del.query.get("topic_id"));
    }

    @Test
    public void sendTextUsesFiveArgContractWithRichText() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        t.responseBody = "\"msg-1\"";

        JSONObject rich = new JSONObject("{\"type\":\"bold\",\"text\":\"hi\"}");
        String id = client(t).sendTextMessage("acc", "chat-1", "c1", "hi", null, rich);

        assertEquals("msg-1", id);
        JSONObject args = new JSONObject(t.last().jsonBody);
        assertEquals("chat-1", args.getString("p_chat_id"));
        assertEquals("c1", args.getString("p_client_message_id"));
        assertEquals("bold", args.getJSONObject("p_rich_text").getString("type"));
        assertTrue(t.last().path.contains("send_text_message"));
    }

    @Test
    public void sendTextPlainOmitsRichTextAsNull() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        t.responseBody = "\"msg-2\"";

        client(t).sendTextMessage("acc", "chat-1", "c2", "plain", null);

        JSONObject args = new JSONObject(t.last().jsonBody);
        assertTrue(args.isNull("p_rich_text"));
    }

    @Test
    public void recoveryRowsDefaultToQueriedChatId() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        // get_messages_since omits the predicate column (no chat_id).
        t.responseBody = "[{\"message_id\":\"m1\",\"sender_id\":\"u1\",\"message_type\":\"text\","
                + "\"content\":\"hi\",\"status\":\"sent\",\"chat_seq\":7}]";

        List<CreangerMessage> rows = client(t).getMessagesSince("acc", "chat-9", 0, 50);

        assertEquals(1, rows.size());
        assertEquals("chat-9", rows.get(0).chatId);
        assertNotNull(rows.get(0).id);
    }

    @Test
    public void recoveryRowsPreserveRichText() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        t.responseBody = "[{\"message_id\":\"m2\",\"chat_id\":\"chat-9\",\"sender_id\":\"u1\","
                + "\"message_type\":\"text\",\"content\":\"hi\",\"rich_text\":{\"type\":\"bold\"}}]";

        List<CreangerMessage> rows = client(t).getMessagesSince("acc", "chat-9", 0, 50);

        assertEquals(1, rows.size());
        assertTrue(rows.get(0).hasRichText());
    }

    @Test
    public void pinAndUnpinUsePinRpc() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        t.responseBody = "\"m1\"";

        assertEquals("m1", client(t).pinMessage("acc", "m1"));
        JSONObject pinArgs = new JSONObject(t.last().jsonBody);
        assertTrue(t.last().path.contains("pin_message"));
        assertEquals("m1", pinArgs.getString("p_message_id"));
        assertTrue(pinArgs.getBoolean("p_pinned"));

        assertEquals("m1", client(t).unpinMessage("acc", "m1"));
        JSONObject unpinArgs = new JSONObject(t.last().jsonBody);
        assertTrue(t.last().path.contains("pin_message"));
        assertEquals("m1", unpinArgs.getString("p_message_id"));
        assertEquals(false, unpinArgs.getBoolean("p_pinned"));
    }

    @Test
    public void nullRowSurvivesRecoveryParsing() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        t.responseBody = "[null]";

        List<CreangerMessage> rows = client(t).getMessagesSince("acc", "chat-9", 0, 50);

        assertEquals(1, rows.size());
        assertNull(rows.get(0));
    }
}
