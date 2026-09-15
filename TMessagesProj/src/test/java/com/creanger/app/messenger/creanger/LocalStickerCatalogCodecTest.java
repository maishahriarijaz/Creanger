package com.creanger.app.messenger.creanger;

import com.creanger.app.model.LocalModels;
import com.creanger.app.model.LocalStickerCatalogCodec;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Offline catalog cache contract: encode/decode round-trips locally with
 * org.json only (no RPC, no network). Covers the "can work locally" bullets
 * of CREANGER_LOCAL_FEATURES (cached catalog, offline open, null-safe items).
 */
public class LocalStickerCatalogCodecTest {

    private static LocalModels.LocalStickerSet set(String id, String shortName, String title,
                                                   LocalModels.LocalStickerItem... docs) {
        ArrayList<LocalModels.LocalStickerItem> documents = new ArrayList<>();
        Collections.addAll(documents, docs);
        return new LocalModels.LocalStickerSet(id, shortName, title, docs.length, documents, null, null);
    }

    @Test
    public void roundTripPreservesSetsAndItems() {
        List<LocalModels.LocalStickerSet> input = new ArrayList<>();
        input.add(set("s1", "pack_one", "Pack One",
                new LocalModels.LocalStickerItem("i1", "d1", "regular", 512, 512, "https://x/1.webp", 42),
                new LocalModels.LocalStickerItem("i2", "d2", "animated", 256, 256, null, 0)));

        List<LocalModels.LocalStickerSet> out = LocalStickerCatalogCodec.decode(
                LocalStickerCatalogCodec.encode(input));

        assertEquals(1, out.size());
        assertEquals("s1", out.get(0).id);
        assertEquals("pack_one", out.get(0).shortName);
        assertEquals("Pack One", out.get(0).title);
        assertEquals(2, out.get(0).documents.size());
        assertEquals("https://x/1.webp", out.get(0).documents.get(0).url);
        assertNull(out.get(0).documents.get(1).url);
        assertEquals("animated", out.get(0).documents.get(1).type);
    }

    @Test
    public void nullAndEmptyInputsYieldEmptyCatalog() {
        assertTrue(LocalStickerCatalogCodec.decode(null).isEmpty());
        assertTrue(LocalStickerCatalogCodec.decode(LocalStickerCatalogCodec.encode(null)).isEmpty());
        assertTrue(LocalStickerCatalogCodec.decode(
                LocalStickerCatalogCodec.encode(Collections.emptyList())).isEmpty());
    }

    @Test
    public void malformedJsonYieldsEmptyCatalog() {
        assertTrue(LocalStickerCatalogCodec.decode("not-json{{{").isEmpty());
    }

    @Test
    public void nullIdSetsAreSkipped() {
        List<LocalModels.LocalStickerSet> input = new ArrayList<>();
        input.add(null);
        input.add(set("ok", "ok", "Ok"));
        List<LocalModels.LocalStickerSet> out = LocalStickerCatalogCodec.decode(
                LocalStickerCatalogCodec.encode(input));
        assertEquals(1, out.size());
        assertEquals("ok", out.get(0).id);
    }
}
