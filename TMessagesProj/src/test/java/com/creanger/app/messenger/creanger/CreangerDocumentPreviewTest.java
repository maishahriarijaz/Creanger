package com.creanger.app.messenger.creanger;

import com.creanger.app.messenger.creanger.api.CreangerDocumentPreview;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

/**
 * E2 document debt: trusted HTTPS providers, Cloudinary thumbnails,
 * chat-list preview selection, bulk-delete chunking (migration 040).
 */
public class CreangerDocumentPreviewTest {

    @Test
    public void trustedProvidersOnlyHttps() {
        assertTrue(CreangerDocumentPreview.isTrustedProviderUrl("https://res.cloudinary.com/d/x.mp4"));
        assertTrue(CreangerDocumentPreview.isTrustedProviderUrl("https://images.imagebb.com/a.png"));
        assertFalse(CreangerDocumentPreview.isTrustedProviderUrl("http://res.cloudinary.com/d/x.mp4"));
        assertFalse(CreangerDocumentPreview.isTrustedProviderUrl("https://evil.com/x.mp4"));
        assertFalse(CreangerDocumentPreview.isTrustedProviderUrl(null));
    }

    @Test
    public void thumbnailForCloudinaryAddsTransform() {
        String url = "https://res.cloudinary.com/demo/video/upload/v1/x.mp4";
        String thumb = CreangerDocumentPreview.thumbnailFor(url, 320, 240);
        assertNotNull(thumb);
        assertTrue(thumb.contains("c_fill,w_320,h_240"));
        assertTrue(thumb.startsWith("https://"));
    }

    @Test
    public void thumbnailForNonCloudinaryUnchanged() {
        String url = "https://images.imagebb.com/a.png";
        assertEquals(url, CreangerDocumentPreview.thumbnailFor(url, 100, 100));
        assertNull(CreangerDocumentPreview.thumbnailFor(null, 100, 100));
    }

    @Test
    public void chatListPreviewPicksFirstTrusted() {
        CreangerDocumentPreview.RecentMedia bad =
                new CreangerDocumentPreview.RecentMedia("m1", "https://evil.com/x", null, "video/mp4", null);
        CreangerDocumentPreview.RecentMedia good =
                new CreangerDocumentPreview.RecentMedia("m2", "https://res.cloudinary.com/d/x.mp4",
                        "https://res.cloudinary.com/demo/video/upload/w_1/x.jpg", "video/mp4", null);
        assertEquals("https://res.cloudinary.com/demo/video/upload/w_1/x.jpg",
                CreangerDocumentPreview.chatListPreview(Arrays.asList(bad, good)));
        assertNull(CreangerDocumentPreview.chatListPreview(Collections.emptyList()));
        assertNull(CreangerDocumentPreview.chatListPreview(null));
    }

    @Test
    public void bulkChunkingSplits() {
        java.util.List<String> ids = Arrays.asList("a", "b", "c", "d", "e");
        java.util.List<java.util.List<String>> chunks = CreangerDocumentPreview.chunkForBulkDelete(ids, 2);
        assertEquals(3, chunks.size());
        assertEquals(2, chunks.get(0).size());
        assertEquals(1, chunks.get(2).size());
        assertTrue(CreangerDocumentPreview.chunkForBulkDelete(null, 2).isEmpty());
    }
}
