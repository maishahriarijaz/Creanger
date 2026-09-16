package com.creanger.app.messenger.creanger;

import com.creanger.app.messenger.creanger.api.ApiRequest;
import com.creanger.app.messenger.creanger.api.CreangerHttpTransport;
import com.creanger.app.messenger.creanger.api.CreangerStickerApiClient;
import com.creanger.app.messenger.creanger.api.TransportResponse;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Sticker backend contract (migration 040): search, trending, mask lookup,
 * install/uninstall, installed-ids sync for multi-device.
 */
public class CreangerStickerApiClientTest {

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
    public void searchParsesHitsAndSkipsBadRows() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        t.responseBody = "[{\"sticker_id\":\"s1\",\"set_id\":\"set1\",\"emoji\":\"😀\"},"
                + "{\"sticker_id\":null,\"set_id\":\"set1\"}]";
        List<CreangerStickerApiClient.StickerHit> hits =
                new CreangerStickerApiClient(t).searchStickers("tok", "😀", 10);
        assertEquals(1, hits.size());
        assertEquals("s1", hits.get(0).stickerId);
        assertEquals("set1", hits.get(0).setId);
        assertTrue(t.last().path.contains("search_stickers"));
    }

    @Test
    public void trendingParsesSets() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        t.responseBody = "[{\"set_id\":\"set1\",\"name\":\"n\",\"title\":\"T\",\"install_count\":5,\"is_official\":true}]";
        List<CreangerStickerApiClient.TrendingSet> sets =
                new CreangerStickerApiClient(t).trendingSets("tok", 5);
        assertEquals(1, sets.size());
        assertEquals("set1", sets.get(0).setId);
        assertEquals(5, sets.get(0).installCount);
        assertTrue(sets.get(0).isOfficial);
    }

    @Test
    public void installAndUninstallHitRpc() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        CreangerStickerApiClient c = new CreangerStickerApiClient(t);
        c.installSet("tok", "set1");
        assertTrue(t.last().path.contains("install_sticker_set"));
        c.uninstallSet("tok", "set1");
        assertTrue(t.last().path.contains("uninstall_sticker_set"));
    }

    @Test
    public void installedIdsSync() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        t.responseBody = "[{\"set_id\":\"a\"},{\"set_id\":\"b\"}]";
        List<String> ids = new CreangerStickerApiClient(t).installedSetIds("tok");
        assertEquals(2, ids.size());
        assertTrue(ids.contains("a"));
        assertTrue(t.last().path.contains("user_sticker_sets"));
    }
}
