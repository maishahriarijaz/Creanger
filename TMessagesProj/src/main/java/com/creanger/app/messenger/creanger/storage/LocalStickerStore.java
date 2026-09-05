package com.creanger.app.messenger.creanger.storage;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import com.creanger.app.model.LocalModels;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Local-first sticker/emoji state. It contains no network or Telegram RPC code.
 * The catalog is a cache; recent/favorite/status state remains usable offline.
 */
public final class LocalStickerStore {
    private static final String PREFS = "creanger_local_stickers";
    private static final String CATALOG = "catalog";
    private static final String RECENT = "recent";
    private static final String FAVORITES = "favorites";
    private static final String STATUS = "emoji_status";

    private final SharedPreferences preferences;

    public LocalStickerStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void saveCatalog(List<LocalModels.LocalStickerSet> sets) {
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
        preferences.edit().putString(CATALOG, result.toString()).apply();
    }

    public List<LocalModels.LocalStickerSet> loadCatalog() {
        String raw = preferences.getString(CATALOG, null);
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

    public void addRecent(String documentId) { addToSet(RECENT, documentId); }
    public void removeRecent(String documentId) { removeFromSet(RECENT, documentId); }
    public List<String> getRecent() { return getSet(RECENT); }

    public void addFavorite(String documentId) { addToSet(FAVORITES, documentId); }
    public void removeFavorite(String documentId) { removeFromSet(FAVORITES, documentId); }
    public List<String> getFavorites() { return getSet(FAVORITES); }

    public void setEmojiStatus(@Nullable String documentId) {
        SharedPreferences.Editor editor = preferences.edit();
        if (documentId == null) editor.remove(STATUS); else editor.putString(STATUS, documentId);
        editor.apply();
    }

    @Nullable public String getEmojiStatus() { return preferences.getString(STATUS, null); }

    private void addToSet(String key, @Nullable String value) {
        if (value == null || value.length() == 0) return;
        Set<String> values = new HashSet<>(preferences.getStringSet(key, Collections.emptySet()));
        values.remove(value);
        values.add(value);
        preferences.edit().putStringSet(key, values).apply();
    }

    private void removeFromSet(String key, @Nullable String value) {
        if (value == null) return;
        Set<String> values = new HashSet<>(preferences.getStringSet(key, Collections.emptySet()));
        if (values.remove(value)) preferences.edit().putStringSet(key, values).apply();
    }

    private List<String> getSet(String key) {
        return new ArrayList<>(preferences.getStringSet(key, Collections.emptySet()));
    }
}
