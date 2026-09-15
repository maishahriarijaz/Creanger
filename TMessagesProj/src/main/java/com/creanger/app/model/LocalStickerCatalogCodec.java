package com.creanger.app.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Android-free JSON codec for the locally cached sticker catalog.
 * Pure JVM (org.json only, no Android types) so the offline cache round-trip
 * is unit-testable per the Creanger Android-free core rule.
 *
 * {@link com.creanger.app.messenger.creanger.storage.LocalStickerStore} delegates
 * persistence to this codec; behavior is identical to the previous inline
 * implementation.
 */
public final class LocalStickerCatalogCodec {
    private LocalStickerCatalogCodec() {}

    public static String encode(List<LocalModels.LocalStickerSet> sets) {
        JSONArray result = new JSONArray();
        if (sets != null) {
            for (LocalModels.LocalStickerSet set : sets) {
                if (set == null || set.id == null) continue;
                JSONObject item = new JSONObject();
                try {
                    item.put("id", set.id);
                    item.put("short_name", set.shortName == null ? "" : set.shortName);
                    item.put("title", set.title == null ? "" : set.title);
                    item.put("count", set.count);
                    JSONArray documents = new JSONArray();
                    if (set.documents != null) {
                        for (LocalModels.LocalStickerItem document : set.documents) {
                            if (document == null) continue;
                            JSONObject value = new JSONObject();
                            value.put("id", document.id);
                            value.put("document_id", document.documentId);
                            value.put("type", document.type);
                            value.put("w", document.w);
                            value.put("h", document.h);
                            value.put("url", document.url == null ? JSONObject.NULL : document.url);
                            value.put("size", document.size);
                            documents.put(value);
                        }
                    }
                    item.put("documents", documents);
                    result.put(item);
                } catch (Exception ignored) {
                    // A malformed cache item must not prevent the rest of the catalog saving.
                }
            }
        }
        return result.toString();
    }

    public static List<LocalModels.LocalStickerSet> decode(String raw) {
        if (raw == null) return Collections.emptyList();
        ArrayList<LocalModels.LocalStickerSet> result = new ArrayList<>();
        try {
            JSONArray sets = new JSONArray(raw);
            for (int i = 0; i < sets.length(); i++) {
                JSONObject value = sets.optJSONObject(i);
                if (value == null) continue;
                ArrayList<LocalModels.LocalStickerItem> documents = new ArrayList<>();
                JSONArray items = value.optJSONArray("documents");
                if (items != null) {
                    for (int j = 0; j < items.length(); j++) {
                        JSONObject item = items.optJSONObject(j);
                        if (item == null) continue;
                        documents.add(new LocalModels.LocalStickerItem(
                                item.optString("id", ""),
                                item.optString("document_id", ""),
                                item.optString("type", "regular"),
                                item.optInt("w"), item.optInt("h"),
                                item.isNull("url") ? null : item.optString("url", null),
                                item.optInt("size")));
                    }
                }
                result.add(new LocalModels.LocalStickerSet(
                        value.optString("id", ""),
                        value.optString("short_name", ""),
                        value.optString("title", ""),
                        value.optInt("count"), documents, null, null));
            }
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
        return result;
    }
}
