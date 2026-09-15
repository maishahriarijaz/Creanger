package com.creanger.app.messenger.creanger.storage;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import com.creanger.app.model.LocalModels;
import com.creanger.app.model.LocalStickerCatalogCodec;

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
        preferences.edit().putString(CATALOG, LocalStickerCatalogCodec.encode(sets)).apply();
    }

    public List<LocalModels.LocalStickerSet> loadCatalog() {
        return LocalStickerCatalogCodec.decode(preferences.getString(CATALOG, null));
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
