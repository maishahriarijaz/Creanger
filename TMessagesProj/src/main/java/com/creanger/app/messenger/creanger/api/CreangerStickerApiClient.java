package com.creanger.app.messenger.creanger.api;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.creanger.app.messenger.creanger.model.ApiError;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Typed client for the sticker backend (migration 006 tables + 040 RPCs).
 *
 * Closes docs/archive/CREANGER_LOCAL_FEATURES.md "still needs backend/API":
 * downloading packs, install/remove across devices, global search, trending,
 * mask lookup. Recent/favorite/emoji-status stay local (LocalStickerStore);
 * install state is server-persisted (user_sticker_sets) so it syncs.
 *
 * Synchronous — MUST be called off the main thread. Android-free (org.json
 * only) except for the transport interface.
 */
public class CreangerStickerApiClient {

    private final CreangerHttpTransport transport;

    public CreangerStickerApiClient(CreangerHttpTransport transport) {
        this.transport = transport;
    }

    /** One sticker row from search_stickers / get_mask_stickers. */
    public static final class StickerHit {
        public final String stickerId;
        public final String setId;
        public final String emoji;

        public StickerHit(String stickerId, String setId, String emoji) {
            this.stickerId = stickerId;
            this.setId = setId;
            this.emoji = emoji;
        }
    }

    /** One trending set row from trending_sticker_sets. */
    public static final class TrendingSet {
        public final String setId;
        public final String name;
        public final String title;
        public final int installCount;
        public final boolean isOfficial;

        public TrendingSet(String setId, String name, String title, int installCount, boolean isOfficial) {
            this.setId = setId;
            this.name = name;
            this.title = title;
            this.installCount = installCount;
            this.isOfficial = isOfficial;
        }
    }

    /** Global sticker search (emoji exact or keyword ILIKE, server-ranked). */
    public List<StickerHit> searchStickers(String accessToken, String query, int limit)
            throws IOException, CreangerApiException {
        JSONObject args = new JSONObject();
        try {
            args.put("p_query", query != null ? query : "");
            args.put("p_limit", limit <= 0 ? 50 : limit);
        } catch (JSONException e) {
            throw new CreangerApiException(200, new ApiError(ApiError.INTERNAL_ERROR,
                    "malformed sticker search args", null, 0));
        }
        String body = executeJson("/rest/v1/rpc/search_stickers", args, accessToken);
        List<StickerHit> out = new ArrayList<>();
        JSONArray rows = parseArray(body);
        for (int i = 0; i < rows.length(); i++) {
            JSONObject o = rows.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String sid = nullIfEmpty(o.optString("sticker_id", null));
            String setId = nullIfEmpty(o.optString("set_id", null));
            if (sid == null || setId == null) {
                continue;
            }
            out.add(new StickerHit(sid, setId, nullIfEmpty(o.optString("emoji", null))));
        }
        return out;
    }

    /** Trending packs ordered by official + install_count (server-ranked). */
    public List<TrendingSet> trendingSets(String accessToken, int limit)
            throws IOException, CreangerApiException {
        JSONObject args = new JSONObject();
        try {
            args.put("p_limit", limit <= 0 ? 20 : limit);
        } catch (JSONException e) {
            throw new CreangerApiException(200, new ApiError(ApiError.INTERNAL_ERROR,
                    "malformed trending args", null, 0));
        }
        String body = executeJson("/rest/v1/rpc/trending_sticker_sets", args, accessToken);
        List<TrendingSet> out = new ArrayList<>();
        JSONArray rows = parseArray(body);
        for (int i = 0; i < rows.length(); i++) {
            JSONObject o = rows.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String setId = nullIfEmpty(o.optString("set_id", null));
            if (setId == null) {
                continue;
            }
            out.add(new TrendingSet(setId,
                    nullIfEmpty(o.optString("name", null)),
                    nullIfEmpty(o.optString("title", null)),
                    o.optInt("install_count", 0),
                    o.optBoolean("is_official", false)));
        }
        return out;
    }

    /** Mask-sticker lookup for arbitrary photos/documents (keyword-driven). */
    public List<StickerHit> maskStickers(String accessToken, int limit)
            throws IOException, CreangerApiException {
        JSONObject args = new JSONObject();
        try {
            args.put("p_limit", limit <= 0 ? 20 : limit);
        } catch (JSONException e) {
            throw new CreangerApiException(200, new ApiError(ApiError.INTERNAL_ERROR,
                    "malformed mask args", null, 0));
        }
        String body = executeJson("/rest/v1/rpc/get_mask_stickers", args, accessToken);
        List<StickerHit> out = new ArrayList<>();
        JSONArray rows = parseArray(body);
        for (int i = 0; i < rows.length(); i++) {
            JSONObject o = rows.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String sid = nullIfEmpty(o.optString("sticker_id", null));
            String setId = nullIfEmpty(o.optString("set_id", null));
            if (sid == null || setId == null) {
                continue;
            }
            out.add(new StickerHit(sid, setId, nullIfEmpty(o.optString("emoji", null))));
        }
        return out;
    }

    /** Installs a pack server-side (idempotent, syncs across devices). */
    public void installSet(String accessToken, String setId)
            throws IOException, CreangerApiException {
        if (setId == null || setId.isEmpty()) {
            throw new CreangerApiException(400, new ApiError(ApiError.INTERNAL_ERROR,
                    "set id is required", null, 0));
        }
        JSONObject args = new JSONObject();
        try {
            args.put("p_set_id", setId);
        } catch (JSONException e) {
            throw new CreangerApiException(200, new ApiError(ApiError.INTERNAL_ERROR,
                    "malformed install args", null, 0));
        }
        executeJson("/rest/v1/rpc/install_sticker_set", args, accessToken);
    }

    /** Removes a pack server-side (idempotent). */
    public void uninstallSet(String accessToken, String setId)
            throws IOException, CreangerApiException {
        if (setId == null || setId.isEmpty()) {
            return;
        }
        JSONObject args = new JSONObject();
        try {
            args.put("p_set_id", setId);
        } catch (JSONException e) {
            throw new CreangerApiException(200, new ApiError(ApiError.INTERNAL_ERROR,
                    "malformed uninstall args", null, 0));
        }
        executeJson("/rest/v1/rpc/uninstall_sticker_set", args, accessToken);
    }

    /**
     * Lists the caller's installed set ids (user_sticker_sets, RLS own-rows).
     * This is the cross-device sync source: after login on a new device,
     * intersect with the local catalog and fetch missing packs.
     */
    public List<String> installedSetIds(String accessToken)
            throws IOException, CreangerApiException {
        Map<String, String> query = new HashMap<>();
        query.put("select", "set_id");
        query.put("order", "installed_at.desc");
        String body = executeList("/rest/v1/user_sticker_sets", query, accessToken);
        List<String> out = new ArrayList<>();
        JSONArray rows = parseArray(body);
        for (int i = 0; i < rows.length(); i++) {
            JSONObject o = rows.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String setId = nullIfEmpty(o.optString("set_id", null));
            if (setId != null) {
                out.add(setId);
            }
        }
        return out;
    }

    private String executeList(String path, Map<String, String> query, String accessToken)
            throws IOException, CreangerApiException {
        ApiRequest request = new ApiRequest("GET", path, query, null, accessToken, null);
        TransportResponse raw = transport.execute(request);
        ApiError error = PostgRestResponseParser.interpretError(raw.statusCode, raw.body);
        if (error != null) {
            throw new CreangerApiException(raw.statusCode, error);
        }
        return raw.body;
    }

    private String executeJson(String path, JSONObject body, String accessToken)
            throws IOException, CreangerApiException {
        ApiRequest request = new ApiRequest("POST", path, null, body.toString(), accessToken, null);
        TransportResponse raw = transport.execute(request);
        ApiError error = PostgRestResponseParser.interpretError(raw.statusCode, raw.body);
        if (error != null) {
            throw new CreangerApiException(raw.statusCode, error);
        }
        return raw.body;
    }

    private static JSONArray parseArray(String body) throws CreangerApiException {
        if (body == null || body.isEmpty()) {
            return new JSONArray();
        }
        try {
            return new JSONArray(body);
        } catch (JSONException e) {
            throw new CreangerApiException(200, new ApiError(ApiError.INTERNAL_ERROR,
                    "malformed sticker response", null, 0));
        }
    }

    @Nullable
    private static String nullIfEmpty(@Nullable String value) {
        return value == null || value.isEmpty() || "null".equals(value) ? null : value;
    }
}
