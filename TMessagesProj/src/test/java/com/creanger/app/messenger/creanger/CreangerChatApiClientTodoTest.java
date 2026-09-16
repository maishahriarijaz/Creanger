package com.creanger.app.messenger.creanger;

import com.creanger.app.messenger.creanger.api.ApiRequest;
import com.creanger.app.messenger.creanger.api.CreangerChatApiClient;
import com.creanger.app.messenger.creanger.api.CreangerDocumentPreview;
import com.creanger.app.messenger.creanger.api.CreangerHttpTransport;
import com.creanger.app.messenger.creanger.api.TransportResponse;
import com.creanger.app.messenger.creanger.model.MessageModels.CreangerMessage;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Migration 040 client contract: bulk delete, document search,
 * recent-media preview, close-friends RPCs.
 */
public class CreangerChatApiClientTodoTest {

    private static final class ScriptedTransport implements CreangerHttpTransport {
        final List<ApiRequest> requests = new ArrayList<>();
        String responseBody = "[]";
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

    @Test
    public void bulkDeleteParsesIds() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        t.responseBody = "[\"m1\",\"m2\"]";
        List<String> deleted = new CreangerChatApiClient(t)
                .bulkDeleteMessages("tok", Arrays.asList("m1", "m2"));
        assertEquals(2, deleted.size());
        assertTrue(t.last().path.contains("bulk_delete_messages"));
    }

    @Test
    public void bulkDeleteEmptyIsNoop() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        List<String> deleted = new CreangerChatApiClient(t)
                .bulkDeleteMessages("tok", new ArrayList<>());
        assertTrue(deleted.isEmpty());
        assertTrue(t.requests.isEmpty());
    }

    @Test
    public void searchDocumentsHitsRpc() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        t.responseBody = "[]";
        List<CreangerMessage> out = new CreangerChatApiClient(t)
                .searchDocumentsInChat("tok", "chat1", "pdf", 10);
        assertTrue(out.isEmpty());
        assertTrue(t.last().path.contains("search_documents_in_chat"));
    }

    @Test
    public void recentMediaParsesRows() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        t.responseBody = "[{\"message_id\":\"m1\",\"public_url\":\"https://res.cloudinary.com/d/x.pdf\","
                + "\"thumbnail_url\":null,\"mime_type\":\"application/pdf\",\"created_at\":null}]";
        List<CreangerDocumentPreview.RecentMedia> media = new CreangerChatApiClient(t)
                .getRecentMedia("tok", "chat1", 4);
        assertEquals(1, media.size());
        assertEquals("m1", media.get(0).messageId);
        assertTrue(t.last().path.contains("get_recent_media"));
    }

    @Test
    public void closeFriendsHitRpc() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        CreangerChatApiClient c = new CreangerChatApiClient(t);
        c.addCloseFriend("tok", "friend1");
        assertTrue(t.last().path.contains("add_close_friend"));
        c.removeCloseFriend("tok", "friend1");
        assertTrue(t.last().path.contains("remove_close_friend"));
    }
}
