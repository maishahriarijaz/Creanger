package com.creanger.app.messenger.creanger;

import org.junit.Test;
import com.creanger.app.messenger.creanger.api.ApiRequest;
import com.creanger.app.messenger.creanger.api.CreangerApiException;
import com.creanger.app.messenger.creanger.api.CreangerHttpTransport;
import com.creanger.app.messenger.creanger.api.CreangerMediaUploadClient;
import com.creanger.app.messenger.creanger.api.TransportResponse;
import com.creanger.app.messenger.creanger.model.MessageModels.UploadedMedia;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Media Send upload seam: {@link CreangerMediaUploadClient} POSTs an image's
 * raw bytes to the authenticated Creanger Media API
 * ({@code /v1/media/upload-image}) with the Bearer JWT, and
 * returns the PROVIDER-NEUTRAL {@link UploadedMedia} the server derived from
 * ImageBB. Pins: request shape (method/path/query/body/bearer/content-type),
 * the envelope parse into {@link UploadedMedia}, authentication/upstream/
 * malformed/blank-URL error mapping (a failed upload is never a silent
 * success), raw transport failures (e.g. timeout) propagating as
 * {@link IOException}, and the guarantee that the request never touches
 * ImageBB (no host, no key) — provider/JSON details live server-side only.
 */
public class CreangerMediaUploadClientTest {

    private static final class FakeTransport implements CreangerHttpTransport {
        final List<ApiRequest> requests = new ArrayList<>();
        final List<TransportResponse> responses = new ArrayList<>();
        volatile IOException failAll = null;

        @Override
        public TransportResponse execute(ApiRequest request) throws IOException {
            requests.add(request);
            if (failAll != null) {
                throw failAll;
            }
            TransportResponse response = responses.remove(0);
            if (response == null) {
                throw new IOException("no canned response for " + request.path);
            }
            return response;
        }

        TransportResponse json(int status, String body) {
            return new TransportResponse(status, body, null);
        }
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private String successEnvelope() {
        return "{"
                + "\"success\":true,"
                + "\"data\":{\"storageProvider\":\"imagebb\",\"storageKey\":\"2ndCYJK\","
                + "\"publicUrl\":\"https://i.ibb.co/xYz/image.jpg\",\"mimeType\":\"image/jpeg\","
                + "\"sizeBytes\":10,\"deliveryUrl\":null}"
                + "}";
    }

    @Test
    public void uploadPostsRawBytesToMediaApiAndParsesProviderNeutralResult() throws Exception {
        FakeTransport t = new FakeTransport();
        t.responses.add(t.json(200, successEnvelope()));
        CreangerMediaUploadClient client = new CreangerMediaUploadClient(t);

        UploadedMedia media = client.uploadImage("acc-live", "chat-1", "pic.jpg",
                bytes("jpeg-bytes"), "image/jpeg");

        assertNotNull(media);
        assertEquals("imagebb", media.storageProvider);
        assertEquals("2ndCYJK", media.storageKey);
        assertEquals("https://i.ibb.co/xYz/image.jpg", media.publicUrl);
        assertEquals("image/jpeg", media.mimeType);
        assertEquals("jpeg-bytes".length(), media.sizeBytes);
        assertNull(media.deliveryUrl);

        assertEquals(1, t.requests.size());
        ApiRequest r = t.requests.get(0);
        assertEquals("POST", r.method);
        assertEquals("/v1/media/upload-image", r.path);
        assertEquals("acc-live", r.accessToken);
        assertEquals("image/jpeg", r.contentType);
        assertEquals("pic.jpg", r.query.get("name"));
        assertEquals("chat-1", r.query.get("chat_id"));
        assertNotNull(r.body);
        assertEquals("jpeg-bytes", new String(r.body, StandardCharsets.UTF_8));
    }

    @Test
    public void uploadRequestNeverMentionsImageBB() throws Exception {
        FakeTransport t = new FakeTransport();
        t.responses.add(t.json(200, successEnvelope()));
        CreangerMediaUploadClient client = new CreangerMediaUploadClient(t);

        client.uploadImage("acc-live", "chat-1", "pic.jpg", bytes("jpeg-bytes"), "image/jpeg");

        ApiRequest r = t.requests.get(0);
        // The request targets ONLY the Creanger/Admin Media API. No ImageBB
        // host, no ImageBB API key and no provider JSON can ever leave Android.
        assertEquals("/v1/media/upload-image", r.path);
        assertFalse(String.valueOf(r.accessToken).contains("imgbb"));
        String wire = new String(r.body == null ? new byte[0] : r.body, StandardCharsets.UTF_8);
        assertFalse(wire.toLowerCase().contains("imgbb"));
        assertFalse(wire.toLowerCase().contains("i.ibb.co"));
    }

    @Test
    public void videoUploadParsesProviderNeutralWidthHeightDuration() throws Exception {
        FakeTransport t = new FakeTransport();
        // The /v1/media/upload-video envelope returned by Cloudinary-backed
        // upload: provider-neutral metadata including width/height/durationMs.
        t.responses.add(t.json(200,
                "{\"success\":true,\"data\":{\"storageProvider\":\"cloudinary\",\"storageKey\":\"vid/abc\","
                        + "\"publicUrl\":\"https://res.cloudinary.com/x/video/upload/v1/vid/abc.mp4\","
                        + "\"mimeType\":\"video/mp4\",\"sizeBytes\":1048576,\"deliveryUrl\":null,"
                        + "\"thumbnailUrl\":\"https://res.cloudinary.com/x/video/upload/w_320/v1/vid/abc.jpg\","
                        + "\"previewUrl\":null,\"width\":1920,\"height\":1080,\"durationMs\":15000}}"));
        CreangerMediaUploadClient client = new CreangerMediaUploadClient(t);

        UploadedMedia media = client.uploadVideo("acc-live", "chat-1", "clip.mp4",
                bytes("mp4-bytes"), "video/mp4");

        assertEquals("/v1/media/upload-video", t.requests.get(0).path);
        assertNotNull(media);
        assertEquals("cloudinary", media.storageProvider);
        assertEquals("vid/abc", media.storageKey);
        assertEquals("https://res.cloudinary.com/x/video/upload/v1/vid/abc.mp4", media.publicUrl);
        assertEquals("video/mp4", media.mimeType);
        assertEquals(Integer.valueOf(1920), media.width);
        assertEquals(Integer.valueOf(1080), media.height);
        assertEquals(Integer.valueOf(15000), media.durationMs);
        assertEquals("https://res.cloudinary.com/x/video/upload/w_320/v1/vid/abc.jpg", media.thumbnailUrl);
    }

    @Test
    public void videoUploadWithoutMetadataYieldsNullWidthHeightDuration() throws Exception {
        FakeTransport t = new FakeTransport();
        // A provider that does not report dimensions/duration must degrade to
        // nulls (never zeros, never fabricated metadata).
        t.responses.add(t.json(200,
                "{\"success\":true,\"data\":{\"storageProvider\":\"cloudinary\",\"storageKey\":\"vid/x\","
                        + "\"publicUrl\":\"https://res.cloudinary.com/x/video/upload/v1/vid/x.mp4\","
                        + "\"mimeType\":\"video/mp4\",\"sizeBytes\":42,\"deliveryUrl\":null,"
                        + "\"thumbnailUrl\":null,\"previewUrl\":null}}"));
        CreangerMediaUploadClient client = new CreangerMediaUploadClient(t);

        UploadedMedia media = client.uploadVideo("acc-live", "chat-1", "clip.mp4",
                bytes("mp4-bytes"), "video/mp4");

        assertNull(media.width);
        assertNull(media.height);
        assertNull(media.durationMs);
    }

    @Test
    public void authenticationFailureSurfacesTypedApiError() throws Exception {
        FakeTransport t = new FakeTransport();
        t.responses.add(t.json(401,
                "{\"success\":false,\"error\":{\"code\":\"MISSING_TOKEN\",\"message\":\"authentication required\"}}"));
        CreangerMediaUploadClient client = new CreangerMediaUploadClient(t);

        try {
            client.uploadImage("acc-live", "chat-1", "pic.jpg", bytes("jpeg-bytes"), "image/jpeg");
            fail("expected CreangerApiException");
        } catch (CreangerApiException e) {
            assertEquals(401, e.statusCode);
            assertEquals("MISSING_TOKEN", e.error.code);
        }
    }

    @Test
    public void upstreamTimeoutSurfacesTypedApiError() throws Exception {
        FakeTransport t = new FakeTransport();
        t.responses.add(t.json(504,
                "{\"success\":false,\"error\":{\"code\":\"UPSTREAM_TIMEOUT\",\"message\":\"image provider timed out\"}}"));
        CreangerMediaUploadClient client = new CreangerMediaUploadClient(t);

        try {
            client.uploadImage("acc-live", "chat-1", "pic.jpg", bytes("jpeg-bytes"), "image/jpeg");
            fail("expected CreangerApiException");
        } catch (CreangerApiException e) {
            assertEquals(504, e.statusCode);
            assertEquals("UPSTREAM_TIMEOUT", e.error.code);
        }
    }

    @Test
    public void blankProviderUrlSurfacesApiErrorNeverSuccess() throws Exception {
        FakeTransport t = new FakeTransport();
        t.responses.add(t.json(200,
                "{\"success\":true,\"data\":{\"storageProvider\":\"imagebb\",\"storageKey\":\"2ndCYJK\","
                        + "\"publicUrl\":\" \",\"mimeType\":\"image/jpeg\",\"sizeBytes\":11}}"));
        CreangerMediaUploadClient client = new CreangerMediaUploadClient(t);

        try {
            client.uploadImage("acc-live", "chat-1", "pic.jpg", bytes("jpeg-bytes"), "image/jpeg");
            fail("expected CreangerApiException");
        } catch (CreangerApiException e) {
            assertNotNull(e.error.message);
            assertTrue(e.error.message.contains("public url"));
        }
    }

    @Test
    public void missingStorageKeySurfacesApiErrorNeverSuccess() throws Exception {
        FakeTransport t = new FakeTransport();
        t.responses.add(t.json(200,
                "{\"success\":true,\"data\":{\"storageProvider\":\"imagebb\",\"publicUrl\":\"https://i.ibb.co/xYz/image.jpg\","
                        + "\"mimeType\":\"image/jpeg\",\"sizeBytes\":11}}"));
        CreangerMediaUploadClient client = new CreangerMediaUploadClient(t);

        try {
            client.uploadImage("acc-live", "chat-1", "pic.jpg", bytes("jpeg-bytes"), "image/jpeg");
            fail("expected CreangerApiException");
        } catch (CreangerApiException e) {
            assertNotNull(e.error.message);
            assertTrue(e.error.message.contains("storage key"));
        }
    }

    @Test
    public void emptySuccessEnvelopeSurfacesApiError() throws Exception {
        FakeTransport t = new FakeTransport();
        t.responses.add(t.json(200, "{\"success\":true}"));
        CreangerMediaUploadClient client = new CreangerMediaUploadClient(t);

        try {
            client.uploadImage("acc-live", "chat-1", "pic.jpg", bytes("jpeg-bytes"), "image/jpeg");
            fail("expected CreangerApiException");
        } catch (CreangerApiException e) {
            assertNotNull(e.error.message);
        }
    }

    @Test
    public void transportTimeoutPropagatesAsIOException() throws Exception {
        FakeTransport t = new FakeTransport();
        t.failAll = new IOException("timeout");
        CreangerMediaUploadClient client = new CreangerMediaUploadClient(t);

        try {
            client.uploadImage("acc-live", "chat-1", "pic.jpg", bytes("jpeg-bytes"), "image/jpeg");
            fail("expected IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("timeout"));
            // Nothing was reported as a silent success.
            assertEquals(1, t.requests.size());
        }
    }

    @Test(expected = CreangerApiException.class)
    public void emptyBytesAreRejectedWithoutAnyRequest() throws Exception {
        FakeTransport t = new FakeTransport();
        CreangerMediaUploadClient client = new CreangerMediaUploadClient(t);
        client.uploadImage("acc-live", "chat-1", "pic.jpg", new byte[0], "image/jpeg");
    }

    @Test
    public void fileNameIsSanitizedIntoQueryName() throws Exception {
        FakeTransport t = new FakeTransport();
        t.responses.add(t.json(200, successEnvelope()));
        CreangerMediaUploadClient client = new CreangerMediaUploadClient(t);

        client.uploadImage("acc-live", "chat-1", "../../etc/passwd", bytes("x"), "image/jpeg");

        assertEquals(1, t.requests.size());
        ApiRequest r = t.requests.get(0);
        // Traversal stays out of the query and never becomes a path.
        assertEquals("/v1/media/upload-image", r.path);
        assertEquals("etcpasswd", r.query.get("name"));
        assertFalse(r.query.get("name").contains(".."));
    }

    // ---- audio ----

    @Test
    public void audioUploadHitsAudioEndpointAndParsesDuration() throws Exception {
        FakeTransport t = new FakeTransport();
        t.responses.add(t.json(200,
                "{\"success\":true,\"data\":{\"storageProvider\":\"cloudinary\",\"storageKey\":\"voice/abc\","
                        + "\"publicUrl\":\"https://res.cloudinary.com/x/video/upload/v1/voice/abc.m4a\","
                        + "\"mimeType\":\"audio/mp4\",\"sizeBytes\":2048,\"deliveryUrl\":null,"
                        + "\"thumbnailUrl\":null,\"previewUrl\":null,\"width\":0,\"height\":0,\"durationMs\":7100}}"));
        CreangerMediaUploadClient client = new CreangerMediaUploadClient(t);

        UploadedMedia media = client.uploadAudio("acc-live", "chat-1", "voice.m4a",
                bytes("audio-bytes"), "audio/mp4");

        assertEquals(1, t.requests.size());
        ApiRequest r = t.requests.get(0);
        assertEquals("POST", r.method);
        assertEquals("/v1/media/upload-audio", r.path);
        assertEquals("audio/mp4", r.contentType);
        assertEquals("voice.m4a", r.query.get("name"));
        assertEquals("chat-1", r.query.get("chat_id"));
        assertEquals("audio-bytes", new String(r.body, StandardCharsets.UTF_8));

        assertNotNull(media);
        assertEquals("cloudinary", media.storageProvider);
        assertEquals("voice/abc", media.storageKey);
        assertEquals("audio/mp4", media.mimeType);
        assertEquals(2048, media.sizeBytes);
        assertEquals(Integer.valueOf(7100), media.durationMs);
    }

    @Test
    public void audioUploadWithoutMetadataYieldsNullDuration() throws Exception {
        FakeTransport t = new FakeTransport();
        t.responses.add(t.json(200,
                "{\"success\":true,\"data\":{\"storageProvider\":\"cloudinary\",\"storageKey\":\"voice/x\","
                        + "\"publicUrl\":\"https://res.cloudinary.com/x/video/upload/v1/voice/x.m4a\","
                        + "\"mimeType\":\"audio/mp4\",\"sizeBytes\":8,\"deliveryUrl\":null}}"));
        CreangerMediaUploadClient client = new CreangerMediaUploadClient(t);

        UploadedMedia media = client.uploadAudio("acc-live", "chat-1", "voice.m4a",
                bytes("audio-bytes"), "audio/mp4");

        assertNull(media.durationMs);
        assertNull(media.width);
        assertNull(media.height);
    }

    @Test(expected = CreangerApiException.class)
    public void emptyAudioBytesAreRejectedWithoutAnyRequest() throws Exception {
        FakeTransport t = new FakeTransport();
        CreangerMediaUploadClient client = new CreangerMediaUploadClient(t);
        client.uploadAudio("acc-live", "chat-1", "voice.m4a", new byte[0], "audio/mp4");
    }

    // ---- document ----

    @Test
    public void documentUploadHitsDocumentEndpointAndParsesProviderNeutralResult() throws Exception {
        FakeTransport t = new FakeTransport();
        t.responses.add(t.json(200,
                "{\"success\":true,\"data\":{\"storageProvider\":\"cloudinary\",\"storageKey\":\"invoice.pdf\","
                        + "\"publicUrl\":\"https://res.cloudinary.com/x/raw/upload/v1/invoice.pdf\","
                        + "\"mimeType\":\"application/pdf\",\"sizeBytes\":65536,\"deliveryUrl\":null}}"));
        CreangerMediaUploadClient client = new CreangerMediaUploadClient(t);

        UploadedMedia media = client.uploadDocument("acc-live", "chat-1", "invoice.pdf",
                bytes("pdf-bytes"), "application/pdf");

        assertEquals(1, t.requests.size());
        ApiRequest r = t.requests.get(0);
        assertEquals("POST", r.method);
        assertEquals("/v1/media/upload-document", r.path);
        assertEquals("application/pdf", r.contentType);
        // The display file name survives into the storage key (the adapter
        // renders documents from storageKey).
        assertEquals("invoice.pdf", r.query.get("name"));
        assertEquals("chat-1", r.query.get("chat_id"));
        assertEquals("pdf-bytes", new String(r.body, StandardCharsets.UTF_8));

        assertNotNull(media);
        assertEquals("cloudinary", media.storageProvider);
        assertEquals("invoice.pdf", media.storageKey);
        assertEquals("https://res.cloudinary.com/x/raw/upload/v1/invoice.pdf", media.publicUrl);
        assertEquals("application/pdf", media.mimeType);
        assertEquals(65536, media.sizeBytes);
        assertNull(media.width);
        assertNull(media.durationMs);
    }

    @Test
    public void documentRequestNeverMentionsCloudinaryInternals() throws Exception {
        FakeTransport t = new FakeTransport();
        t.responses.add(t.json(200,
                "{\"success\":true,\"data\":{\"storageProvider\":\"cloudinary\",\"storageKey\":\"a.pdf\","
                        + "\"publicUrl\":\"https://res.cloudinary.com/x/raw/upload/v1/a.pdf\","
                        + "\"mimeType\":\"application/pdf\",\"sizeBytes\":4,\"deliveryUrl\":null}}"));
        CreangerMediaUploadClient client = new CreangerMediaUploadClient(t);

        client.uploadDocument("acc-live", "chat-1", "a.pdf", bytes("data"), "application/pdf");

        ApiRequest r = t.requests.get(0);
        // The request targets ONLY the Creanger Media API; Cloudinary's API
        // key/secret never leave the server.
        assertEquals("/v1/media/upload-document", r.path);
        String wire = new String(r.body == null ? new byte[0] : r.body, StandardCharsets.UTF_8);
        assertFalse(wire.contains("res.cloudinary.com"));
        assertNull(r.accessToken == null ? null : (r.accessToken.contains("cloudinary") ? r.accessToken : null));
    }

    @Test(expected = CreangerApiException.class)
    public void emptyDocumentBytesAreRejectedWithoutAnyRequest() throws Exception {
        FakeTransport t = new FakeTransport();
        CreangerMediaUploadClient client = new CreangerMediaUploadClient(t);
        client.uploadDocument("acc-live", "chat-1", "a.pdf", new byte[0], "application/pdf");
    }
}