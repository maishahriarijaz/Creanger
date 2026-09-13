package com.creanger.app.messenger.creanger;

import org.junit.Test;
import com.creanger.app.messenger.ImageLocation;
import com.creanger.app.tgnet.TLRPC;

import static org.junit.Assert.*;

/**
 * Media Phase 2 render seam: the EXISTING Telegram photo bubble and
 * {@code PhotoViewer} build their image source with
 * {@code ImageLocation.getForObject(photoSize, photo)} → {@code getForPhoto}.
 * That's where a Creanger HTTPS source URL enters the stock {@code ImageLoader}
 * HTTP/cache path ({@code ImageLocation.path} → HttpImageTask, disk key =
 * {@code MD5(url).ext}). These tests pin that seam: Creanger photos route by
 * URL, legacy MTProto photos by location, and missing/invalid sources are a
 * safe no-op. No MTProto file fetching is ever produced for Creanger photos.
 */
public class CreangerImageRenderSeamTest {

    private static TLRPC.TL_photoSize legacySize() {
        TLRPC.TL_photoSize size = new TLRPC.TL_photoSize();
        size.type = "x";
        size.w = 320;
        size.h = 240;
        size.size = 1234;
        TLRPC.TL_fileLocationToBeDeprecated location = new TLRPC.TL_fileLocationToBeDeprecated();
        location.volume_id = 777;
        location.local_id = 42;
        location.dc_id = 2;
        size.location = location;
        return size;
    }

    private static TLRPC.TL_photo legacyPhoto() {
        TLRPC.TL_photo photo = new TLRPC.TL_photo();
        photo.id = 9001;
        photo.dc_id = 2;
        photo.access_hash = 5;
        photo.sizes = new java.util.ArrayList<>();
        return photo;
    }

    private static TLRPC.TL_photoSize creangerSize(String url) {
        TLRPC.TL_photoSize size = new TLRPC.TL_photoSize();
        size.type = "m";
        size.url = url;
        size.w = 800;
        size.h = 600;
        size.size = 9999;
        return size;
    }

    private static TLRPC.TL_photo creangerPhoto() {
        TLRPC.TL_photo photo = new TLRPC.TL_photo();
        photo.id = -1_000_000_000_123L;
        photo.dc_id = 0;
        photo.access_hash = 0;
        photo.sizes = new java.util.ArrayList<>();
        return photo;
    }

    // ---- Creanger URL -> existing HTTP/cache path ----

    @Test
    public void creangerPhotoRoutesByHttpsUrlThroughImageLoaderPath() {
        TLRPC.TL_photoSize size = creangerSize("https://cdn.example/img/photo_1.png");

        ImageLocation location = ImageLocation.getForObject(size, creangerPhoto());

        assertNotNull(location);
        // ImageLoader treats path.startsWith("http") as a remote URL and loads
        // it via HttpImageTask (disk cache: MD5(url).ext) — NO MTProto fetching.
        assertEquals("https://cdn.example/img/photo_1.png", location.path);
        assertNull("no MTProto location for Creanger photos", location.location);
        assertNull(location.document);
        assertNull(location.webFile);
    }

    @Test
    public void missingUrlWithNoLocationIsSafeNoOp() {
        TLRPC.TL_photoSize size = creangerSize(null);
        size.location = null;

        assertNull(ImageLocation.getForObject(size, creangerPhoto()));
    }

    @Test
    public void getForPathRejectsNull() {
        assertNull(ImageLocation.getForPath(null));
    }

    // ---- legacy Telegram image path unchanged ----

    @Test
    public void legacyPhotoStillRoutesByMtpProtoLocation() {
        TLRPC.TL_photoSize size = legacySize();
        size.url = null;

        ImageLocation location = ImageLocation.getForObject(size, legacyPhoto());

        assertNotNull(location);
        assertNull("legacy photos must keep path == null", location.path);
        assertNotNull("legacy photos keep their MTProto file location", location.location);
        assertEquals(777L, location.location.volume_id);
        assertEquals(42, location.location.local_id);
        assertEquals(2, location.location.dc_id);
    }

    @Test
    public void legacyPhotoWithNullLocationStillSafe() {
        // Pathological but must not NPE: no URL, dc_id 0, no location.
        TLRPC.TL_photoSize size = legacySize();
        size.url = null;
        size.location = null;
        TLRPC.TL_photo photo = legacyPhoto();
        photo.dc_id = 0;

        assertNull(ImageLocation.getForObject(size, photo));
    }
}