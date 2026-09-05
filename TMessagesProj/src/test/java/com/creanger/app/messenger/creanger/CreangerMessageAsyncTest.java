package com.creanger.app.messenger.creanger;

import org.junit.Test;
import com.creanger.app.messenger.creanger.api.ApiRequest;
import com.creanger.app.messenger.creanger.api.SupabaseAuthClient;
import com.creanger.app.messenger.creanger.api.CreangerApiException;
import com.creanger.app.messenger.creanger.api.CreangerChatApiClient;
import com.creanger.app.messenger.creanger.api.CreangerHttpTransport;
import com.creanger.app.messenger.creanger.api.CreangerMediaUploadClient;
import com.creanger.app.messenger.creanger.api.TransportResponse;
import com.creanger.app.messenger.creanger.auth.CreangerAuthEngine;
import com.creanger.app.messenger.creanger.data.CreangerChatBridge;
import com.creanger.app.messenger.creanger.data.CreangerMessageAsync;
import com.creanger.app.messenger.creanger.data.CreangerMessageUiModel;
import com.creanger.app.messenger.creanger.data.MessageRepository;
import com.creanger.app.messenger.creanger.model.AuthModels.AuthSession;
import com.creanger.app.messenger.creanger.model.AuthModels.CreangerUser;
import com.creanger.app.messenger.creanger.model.MessageModels.MediaAttachment;
import com.creanger.app.messenger.creanger.model.MessageModels.MessagePage;
import com.creanger.app.messenger.creanger.model.MessageModels.MessageType;
import com.creanger.app.messenger.creanger.storage.CreangerTokenStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Async wrapper tests: {@link CreangerMessageAsync} drives the
 * {@link CreangerChatBridge} on a background executor and posts completion
 * callbacks to a (here, synchronous) main-thread poster. Exercises: newest-page
 * load, older-page load, optimistic send with immediate pending row and
 * reconcile, failure surfacing as onError with the FAILED row kept, retry
 * reusing the same client id, and authentication gating.
 */
public class CreangerMessageAsyncTest {

    private static final String OWNER = "uuid-1";

    private static final class ScriptedTransport implements CreangerHttpTransport {
        final List<TransportResponse> responses = new ArrayList<>();
        final List<ApiRequest> requests = new ArrayList<>();
        volatile IOException failAll = null;

        @Override
        public TransportResponse execute(ApiRequest request) throws IOException {
            requests.add(request);
            if (failAll != null) {
                throw failAll;
            }
            TransportResponse response = responses.remove(0);
            if (response == null) {
                throw new IOException("no canned response for " + request.method + " " + request.path);
            }
            return response;
        }

        TransportResponse json(int status, String body) {
            return new TransportResponse(status, body, null);
        }
    }

    private static final class InMemoryStore implements CreangerTokenStore {
        volatile AuthSession stored;

        @Override
        public AuthSession load() {
            return stored;
        }

        @Override
        public void store(AuthSession session) {
            this.stored = session;
        }

        @Override
        public void clear() {
            this.stored = null;
        }
    }

    private static CreangerAuthEngine authenticatedEngine(ScriptedTransport t, InMemoryStore store) {
        store.store(new AuthSession("acc-live", "ref",
                new CreangerUser(OWNER, "alice", "a@x.com", true, null, null, null),
                System.currentTimeMillis()));
        return new CreangerAuthEngine(new SupabaseAuthClient(t), store);
    }

    private static MessageRepository repo(ScriptedTransport t, CreangerAuthEngine engine) {
        return new MessageRepository(engine, new CreangerChatApiClient(t));
    }

    private static MessageRepository repoWithUpload(ScriptedTransport t, CreangerAuthEngine engine) {
        return new MessageRepository(engine, new CreangerChatApiClient(t),
                new CreangerMediaUploadClient(t));
    }

    private static CreangerMessageAsync async(ScriptedTransport t, InMemoryStore store) {
        return new CreangerMessageAsync(
                new CreangerChatBridge(repo(t, authenticatedEngine(t, store)), OWNER, null),
                synchronousExecutor(),
                runnable -> runnable.run()); // synchronous main poster
    }

    private static CreangerMessageAsync asyncWithUpload(ScriptedTransport t, InMemoryStore store) {
        return new CreangerMessageAsync(
                new CreangerChatBridge(repoWithUpload(t, authenticatedEngine(t, store)), OWNER, null),
                synchronousExecutor(),
                runnable -> runnable.run()); // synchronous main poster
    }

    private static ExecutorService synchronousExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });
    }

    /** A callback that captures success to an AtomicReference and fails the test
     *  on error unless told not to. */
    private static <T> CreangerMessageAsync.Callback<T> capture(
            AtomicReference<T> out, AtomicReference<Throwable> errOut) {
        return new CreangerMessageAsync.Callback<T>() {
            @Override
            public void onSuccess(T result) {
                out.set(result);
                synchronized (out) {
                    out.notifyAll();
                }
            }

            @Override
            public void onError(CreangerApiException error, Throwable ioError) {
                errOut.set(error != null ? error : ioError);
                synchronized (out) {
                    out.notifyAll();
                }
            }
        };
    }

    /** Waits until the callback fired or the timeout elapses. */
    private static void await(AtomicReference<?> signal) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        synchronized (signal) {
            while (signal.get() == null && System.currentTimeMillis() < deadline) {
                signal.wait(100);
            }
        }
    }

    private static String msgRow(String id, long chatSeq, String content, String createdAt) {
        return "{\"id\":\"" + id + "\",\"chat_id\":\"chat-1\",\"sender_id\":\"uuid-1\",\"message_type\":\"text\","
                + "\"content\":\"" + content + "\",\"status\":\"sent\",\"client_message_id\":null,"
                + "\"chat_seq\":" + chatSeq + ",\"created_at\":\"" + createdAt
                + "\",\"edited_at\":null,\"deleted_at\":null,\"updated_at\":\"" + createdAt + "\"}";
    }

    private static String rowsJson(String... rows) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < rows.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(rows[i]);
        }
        return sb.append(']').toString();
    }

    // ---- loading ----

    @Test
    public void refreshLoadsNewestFirst() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        t.responses.add(t.json(200, rowsJson(
                msgRow("m-2", 2, "second", "2026-08-15T08:02:00Z"),
                msgRow("m-1", 1, "first", "2026-08-15T08:01:00Z"))));
        AtomicReference<MessagePage> out = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        CreangerMessageAsync async = async(t, store);

        async.refreshMessages("chat-1", 30, capture(out, err));
        await(out);

        assertNull(err.get());
        assertNotNull(out.get());
        assertFalse(out.get().hasMore);
        List<CreangerMessageUiModel> msgs = async.getMessages("chat-1");
        assertEquals(2, msgs.size());
        assertEquals("m-2", msgs.get(0).id);
        assertEquals("m-1", msgs.get(1).id);
    }

    @Test
    public void loadOlderReturnsPage() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        t.responses.add(t.json(200, rowsJson(msgRow("m-1", 1, "first", "2026-08-15T08:01:00Z"))));
        AtomicReference<MessagePage> out = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        CreangerMessageAsync async = async(t, store);

        async.loadOlderMessages("chat-1", 5, 30, capture(out, err));
        await(out);

        assertNull(err.get());
        assertEquals(1, out.get().messages.size());
        assertEquals("m-1", out.get().messages.get(0).id);
    }

    // ---- optimistic send + reconcile ----

    @Test
    public void sendShowsPendingImmediatelyThenConfirmsOnMainThread() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        t.responses.add(t.json(200, "\"server-msg-1\""));
        AtomicReference<String> out = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        CreangerMessageAsync async = async(t, store);

        // The pending row must be visible before the callback (optimistic).
        async.sendTextMessage("chat-1", "client-abc", "hello", capture(out, err));
        assertTrue(async.getMessages("chat-1").get(0).isPending());
        await(out);

        assertNull(err.get());
        assertEquals("server-msg-1", out.get());
        CreangerMessageUiModel confirmed = async.getMessages("chat-1").get(0);
        assertEquals("server-msg-1", confirmed.id);
        assertFalse(confirmed.isPending());
        assertFalse(confirmed.isLocal);
    }

    @Test
    public void sendFailureFlagsFailedRowAndReportsError() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        t.failAll = new IOException("no route to host");
        AtomicReference<String> out = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        CreangerMessageAsync async = async(t, store);

        async.sendTextMessage("chat-1", "client-fail", "hello", capture(out, err));
        await(err);

        assertNull(out.get());
        assertNotNull(err.get());
        assertTrue(err.get() instanceof IOException);
        CreangerMessageUiModel row = async.getMessages("chat-1").get(0);
        assertTrue(row.isFailed());
        assertTrue(row.isLocal);
    }

    @Test
    public void retryReusesClientIdAndConfirms() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        t.responses.add(t.json(200, "\"server-msg-9\""));
        AtomicReference<String> out = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        CreangerMessageAsync async = async(t, store);

        async.retrySend("chat-1", "client-retry", "hello", capture(out, err));
        await(out);

        assertNull(err.get());
        assertEquals("server-msg-9", out.get());
        List<CreangerMessageUiModel> msgs = async.getMessages("chat-1");
        assertEquals(1, msgs.size());
        assertEquals("server-msg-9", msgs.get(0).id);
        assertFalse(msgs.get(0).isPending());
    }

    // ---- image send (Media Send phase) ----

    @Test
    public void sendImageUploadsBytesThenSendsMediaRpcAndConfirms() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        t.responses.add(t.json(200,
                "{\"success\":true,\"data\":{\"storageProvider\":\"imagebb\",\"storageKey\":\"2ndCYJK\","
                        + "\"publicUrl\":\"https://i.ibb.co/xYz/image.jpg\",\"mimeType\":\"image/jpeg\","
                        + "\"sizeBytes\":11}}"));
        t.responses.add(t.json(200, "\"server-img-1\""));               // send_media_message RPC
        AtomicReference<String> out = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        CreangerMessageAsync async = asyncWithUpload(t, store);

        async.sendImageMessage("chat-1", "client-img", "pic.jpg",
                "jpeg-bytes".getBytes(StandardCharsets.UTF_8), "image/jpeg",
                "caption", null, capture(out, err));

        // The optimistic PENDING image row renders before any callback.
        CreangerMessageUiModel pending = async.getMessages("chat-1").get(0);
        assertTrue(pending.isPending());
        assertEquals(MessageType.IMAGE, pending.messageType);
        await(out);

        assertNull(err.get());
        assertEquals("server-img-1", out.get());

        // Upload went out first (raw bytes, bearer, content type) to the
        // Creanger/Admin Media API ...
        assertEquals(2, t.requests.size());
        ApiRequest upload = t.requests.get(0);
        assertEquals("/v1/media/upload-image", upload.path);
        assertEquals("acc-live", upload.accessToken);
        assertEquals("image/jpeg", upload.contentType);
        assertEquals("jpeg-bytes", new String(upload.body, StandardCharsets.UTF_8));
        // ... then the media metadata RPC.
        assertEquals("/rest/v1/rpc/send_media_message", t.requests.get(1).path);
        assertTrue(t.requests.get(1).jsonBody.contains("image"));

        // Confirmed row carries the provider-neutral public URL from the Media
        // API, not the local file.
        CreangerMessageUiModel confirmed = async.getMessages("chat-1").get(0);
        assertEquals("server-img-1", confirmed.id);
        assertFalse(confirmed.isLocal);
        assertEquals(1, confirmed.attachments.size());
        assertEquals("https://i.ibb.co/xYz/image.jpg",
                confirmed.attachments.get(0).publicUrl);
    }

    @Test
    public void failedImageSendKeepsFailedRowAndRetryUsesSameBytes() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        // First attempt: the Creanger Media API upload itself fails (HTTP 502).
        t.responses.add(t.json(502,
                "{\"success\":false,\"error\":{\"code\":\"UPSTREAM_ERROR\",\"message\":\"image provider rejected the upload\"}}"));
        AtomicReference<String> out = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        CreangerMessageAsync async = asyncWithUpload(t, store);

        async.sendImageMessage("chat-1", "client-img", "pic.jpg",
                "jpeg-bytes".getBytes(StandardCharsets.UTF_8), "image/jpeg",
                null, null, capture(out, err));
        await(err);

        assertNull(out.get());
        assertTrue(err.get() instanceof CreangerApiException);
        assertTrue(async.getMessages("chat-1").get(0).isFailed());

        // Retry: the upload + RPC now succeed; the SAME bytes + client id reuse
        // (idempotent), and the row confirms.
        t.responses.add(t.json(200,
                "{\"success\":true,\"data\":{\"storageProvider\":\"imagebb\",\"storageKey\":\"2ndCYJK\","
                        + "\"publicUrl\":\"https://i.ibb.co/xYz/image.jpg\",\"mimeType\":\"image/jpeg\","
                        + "\"sizeBytes\":11}}"));
        t.responses.add(t.json(200, "\"server-img-2\""));
        out.set(null);
        err.set(null);
        async.retrySendImageMessage("chat-1", "client-img", capture(out, err));
        await(out);

        assertNull(err.get());
        assertEquals("server-img-2", out.get());
        // The retry re-fired the upload with the original payload.
        ApiRequest retryUpload = t.requests.get(1);
        assertEquals("/v1/media/upload-image", retryUpload.path);
        assertEquals("jpeg-bytes", new String(retryUpload.body, StandardCharsets.UTF_8));
        CreangerMessageUiModel confirmed = async.getMessages("chat-1").get(0);
        assertEquals("server-img-2", confirmed.id);
        assertFalse(confirmed.isPending());
    }

    // ---- voice / audio send ----

    @Test
    public void sendVoiceUploadsThenSendsVoiceRpcAndConfirmsWithDuration() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        t.responses.add(t.json(200,
                "{\"success\":true,\"data\":{\"storageProvider\":\"cloudinary\",\"storageKey\":\"voice/abc\","
                        + "\"publicUrl\":\"https://res.cloudinary.com/x/video/upload/v1/voice/abc.m4a\","
                        + "\"mimeType\":\"audio/mp4\",\"sizeBytes\":11,\"deliveryUrl\":null,"
                        + "\"thumbnailUrl\":null,\"previewUrl\":null,\"durationMs\":7250}}"));
        t.responses.add(t.json(200, "\"server-voice-1\""));
        AtomicReference<String> out = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        CreangerMessageAsync async = asyncWithUpload(t, store);

        async.sendVoiceMessage("chat-1", "client-voice", "voice.m4a",
                "audio-bytes".getBytes(StandardCharsets.UTF_8), "audio/mp4",
                null, null, capture(out, err));

        CreangerMessageUiModel pending = async.getMessages("chat-1").get(0);
        assertTrue(pending.isPending());
        assertEquals(MessageType.VOICE, pending.messageType);
        await(out);

        assertNull(err.get());
        assertEquals("server-voice-1", out.get());
        assertEquals(2, t.requests.size());
        ApiRequest upload = t.requests.get(0);
        assertEquals("/v1/media/upload-audio", upload.path);
        assertEquals("audio/mp4", upload.contentType);
        assertEquals("audio-bytes", new String(upload.body, StandardCharsets.UTF_8));
        assertEquals("/rest/v1/rpc/send_media_message", t.requests.get(1).path);
        assertTrue(t.requests.get(1).jsonBody.contains("voice"));

        CreangerMessageUiModel confirmed = async.getMessages("chat-1").get(0);
        assertEquals("server-voice-1", confirmed.id);
        assertFalse(confirmed.isLocal);
        assertEquals(Integer.valueOf(7250), confirmed.attachments.get(0).duration);
    }

    @Test
    public void failedVoiceSendKeepsFailedRowAndRetryUsesSameBytes() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        // First attempt: the Creanger Media API audio upload itself fails.
        t.responses.add(t.json(502,
                "{\"success\":false,\"error\":{\"code\":\"UPSTREAM_ERROR\",\"message\":\"audio provider rejected the upload\"}}"));
        AtomicReference<String> out = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        CreangerMessageAsync async = asyncWithUpload(t, store);

        async.sendVoiceMessage("chat-1", "client-voice", "voice.m4a",
                "audio-bytes".getBytes(StandardCharsets.UTF_8), "audio/mp4",
                null, null, capture(out, err));
        await(err);

        assertNull(out.get());
        assertTrue(err.get() instanceof CreangerApiException);
        assertTrue(async.getMessages("chat-1").get(0).isFailed());

        // Retry: upload + RPC succeed with the SAME bytes + client id (idempotent).
        t.responses.add(t.json(200,
                "{\"success\":true,\"data\":{\"storageProvider\":\"cloudinary\",\"storageKey\":\"voice/abc\","
                        + "\"publicUrl\":\"https://res.cloudinary.com/x/video/upload/v1/voice/abc.m4a\","
                        + "\"mimeType\":\"audio/mp4\",\"sizeBytes\":11,\"deliveryUrl\":null,"
                        + "\"durationMs\":7250}}"));
        t.responses.add(t.json(200, "\"server-voice-2\""));
        out.set(null);
        err.set(null);
        async.retrySendAudioMessage("chat-1", "client-voice", capture(out, err));
        await(out);

        assertNull(err.get());
        assertEquals("server-voice-2", out.get());
        ApiRequest retryUpload = t.requests.get(1);
        assertEquals("/v1/media/upload-audio", retryUpload.path);
        assertEquals("audio-bytes", new String(retryUpload.body, StandardCharsets.UTF_8));
        CreangerMessageUiModel confirmed = async.getMessages("chat-1").get(0);
        assertEquals("server-voice-2", confirmed.id);
        assertFalse(confirmed.isPending());
        assertEquals("voice", confirmed.messageType);
    }

    // ---- document send ----

    @Test
    public void sendDocumentUploadsThenSendsDocumentRpcAndConfirms() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        t.responses.add(t.json(200,
                "{\"success\":true,\"data\":{\"storageProvider\":\"cloudinary\",\"storageKey\":\"invoice.pdf\","
                        + "\"publicUrl\":\"https://res.cloudinary.com/x/raw/upload/v1/invoice.pdf\","
                        + "\"mimeType\":\"application/pdf\",\"sizeBytes\":11,\"deliveryUrl\":null}}"));
        t.responses.add(t.json(200, "\"server-doc-1\""));
        AtomicReference<String> out = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        CreangerMessageAsync async = asyncWithUpload(t, store);

        async.sendDocumentMessage("chat-1", "client-doc", "invoice.pdf",
                "pdf-bytes".getBytes(StandardCharsets.UTF_8), "application/pdf",
                "contract", null, capture(out, err));

        CreangerMessageUiModel pending = async.getMessages("chat-1").get(0);
        assertTrue(pending.isPending());
        assertEquals(MessageType.DOCUMENT, pending.messageType);
        await(out);

        assertNull(err.get());
        assertEquals("server-doc-1", out.get());
        assertEquals(2, t.requests.size());
        ApiRequest upload = t.requests.get(0);
        assertEquals("/v1/media/upload-document", upload.path);
        assertEquals("application/pdf", upload.contentType);
        assertEquals("pdf-bytes", new String(upload.body, StandardCharsets.UTF_8));
        assertEquals("/rest/v1/rpc/send_media_message", t.requests.get(1).path);
        assertTrue(t.requests.get(1).jsonBody.contains("document"));

        CreangerMessageUiModel confirmed = async.getMessages("chat-1").get(0);
        assertEquals("server-doc-1", confirmed.id);
        assertFalse(confirmed.isLocal);
        assertEquals("invoice.pdf", confirmed.attachments.get(0).storageKey);
        assertEquals("application/pdf", confirmed.attachments.get(0).mimeType);
    }

    @Test
    public void failedDocumentSendKeepsFailedRowAndRetryUsesSameBytes() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        // First attempt: the Creanger Media API document upload itself fails.
        t.responses.add(t.json(502,
                "{\"success\":false,\"error\":{\"code\":\"UPSTREAM_ERROR\",\"message\":\"document provider rejected the upload\"}}"));
        AtomicReference<String> out = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        CreangerMessageAsync async = asyncWithUpload(t, store);

        async.sendDocumentMessage("chat-1", "client-doc", "invoice.pdf",
                "pdf-bytes".getBytes(StandardCharsets.UTF_8), "application/pdf",
                null, null, capture(out, err));
        await(err);

        assertNull(out.get());
        assertTrue(err.get() instanceof CreangerApiException);
        assertTrue(async.getMessages("chat-1").get(0).isFailed());

        // Retry: upload + RPC succeed with the SAME bytes + client id (idempotent).
        t.responses.add(t.json(200,
                "{\"success\":true,\"data\":{\"storageProvider\":\"cloudinary\",\"storageKey\":\"invoice.pdf\","
                        + "\"publicUrl\":\"https://res.cloudinary.com/x/raw/upload/v1/invoice.pdf\","
                        + "\"mimeType\":\"application/pdf\",\"sizeBytes\":11,\"deliveryUrl\":null}}"));
        t.responses.add(t.json(200, "\"server-doc-2\""));
        out.set(null);
        err.set(null);
        async.retrySendDocumentMessage("chat-1", "client-doc", capture(out, err));
        await(out);

        assertNull(err.get());
        assertEquals("server-doc-2", out.get());
        ApiRequest retryUpload = t.requests.get(1);
        assertEquals("/v1/media/upload-document", retryUpload.path);
        assertEquals("pdf-bytes", new String(retryUpload.body, StandardCharsets.UTF_8));
        CreangerMessageUiModel confirmed = async.getMessages("chat-1").get(0);
        assertEquals("server-doc-2", confirmed.id);
        assertEquals("document", confirmed.messageType);
        assertFalse(confirmed.isPending());
    }

    // ---- from-path sends (Phase D pickers/recorder) ----

    private static File tempAudioFile() throws IOException {
        File f = File.createTempFile("creanger-voice", ".m4a");
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write("audio-bytes".getBytes(StandardCharsets.UTF_8));
        }
        f.deleteOnExit();
        return f;
    }

    private static File tempDocumentFile() throws IOException {
        File f = File.createTempFile("creanger-doc", ".pdf");
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write("pdf-bytes".getBytes(StandardCharsets.UTF_8));
        }
        f.deleteOnExit();
        return f;
    }

    @Test
    public void sendVoiceMessageFromPathReadsFileThenConfirmsAsVoice() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        File voice = tempAudioFile();
        t.responses.add(t.json(200,
                "{\"success\":true,\"data\":{\"storageProvider\":\"cloudinary\",\"storageKey\":\"voice/path\","
                        + "\"publicUrl\":\"https://res.cloudinary.com/x/video/upload/v1/voice/path.m4a\","
                        + "\"mimeType\":\"audio/mp4\",\"sizeBytes\":11,\"deliveryUrl\":null,"
                        + "\"thumbnailUrl\":null,\"previewUrl\":null,\"durationMs\":7250}}"));
        t.responses.add(t.json(200, "\"server-voice-path\""));
        AtomicReference<String> out = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        CreangerMessageAsync async = asyncWithUpload(t, store);

        async.sendVoiceMessageFromPath("chat-1", voice.getAbsolutePath(), "audio/mp4",
                null, null, capture(out, err));

        CreangerMessageUiModel pending = async.getMessages("chat-1").get(0);
        assertTrue(pending.isPending());
        assertEquals(MessageType.VOICE, pending.messageType);
        await(out);

        assertNull(err.get());
        assertEquals("server-voice-path", out.get());
        // The on-disk bytes were uploaded, not a synthetic empty buffer.
        assertEquals("/v1/media/upload-audio", t.requests.get(0).path);
        assertEquals("audio-bytes", new String(t.requests.get(0).body, StandardCharsets.UTF_8));
        assertEquals("/rest/v1/rpc/send_media_message", t.requests.get(1).path);
        assertTrue(t.requests.get(1).jsonBody.contains("voice"));
        CreangerMessageUiModel confirmed = async.getMessages("chat-1").get(0);
        assertEquals("voice", confirmed.messageType);
        assertFalse(confirmed.isPending());
    }

    @Test
    public void sendMusicMessageFromPathReadsFileThenConfirmsAsAudio() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        File track = tempAudioFile();
        t.responses.add(t.json(200,
                "{\"success\":true,\"data\":{\"storageProvider\":\"cloudinary\",\"storageKey\":\"audio/track\","
                        + "\"publicUrl\":\"https://res.cloudinary.com/x/video/upload/v1/audio/track.m4a\","
                        + "\"mimeType\":\"audio/mp4\",\"sizeBytes\":11,\"deliveryUrl\":null,"
                        + "\"durationMs\":224000}}"));
        t.responses.add(t.json(200, "\"server-music-1\""));
        AtomicReference<String> out = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        CreangerMessageAsync async = asyncWithUpload(t, store);

        async.sendMusicMessageFromPath("chat-1", track.getAbsolutePath(), "audio/mp4",
                "song title", null, capture(out, err));

        CreangerMessageUiModel pending = async.getMessages("chat-1").get(0);
        assertTrue(pending.isPending());
        assertEquals(MessageType.AUDIO, pending.messageType);
        assertEquals("song title", pending.content);
        await(out);

        assertNull(err.get());
        assertEquals("server-music-1", out.get());
        assertEquals("/v1/media/upload-audio", t.requests.get(0).path);
        assertEquals("audio-bytes", new String(t.requests.get(0).body, StandardCharsets.UTF_8));
        assertEquals("/rest/v1/rpc/send_media_message", t.requests.get(1).path);
        assertTrue(t.requests.get(1).jsonBody.contains("audio"));
        assertFalse(t.requests.get(1).jsonBody.contains("voice"));
        CreangerMessageUiModel confirmed = async.getMessages("chat-1").get(0);
        assertEquals("audio", confirmed.messageType);
        assertEquals(Integer.valueOf(224000), confirmed.attachments.get(0).duration);
        assertFalse(confirmed.isPending());
    }

    @Test
    public void sendDocumentMessageFromPathReadsFileThenConfirms() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        File doc = tempDocumentFile();
        t.responses.add(t.json(200,
                "{\"success\":true,\"data\":{\"storageProvider\":\"cloudinary\",\"storageKey\":\"raw/note\","
                        + "\"publicUrl\":\"https://res.cloudinary.com/x/raw/upload/v1/raw/note.pdf\","
                        + "\"mimeType\":\"application/pdf\",\"sizeBytes\":11,\"deliveryUrl\":null}}"));
        t.responses.add(t.json(200, "\"server-doc-path\""));
        AtomicReference<String> out = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        CreangerMessageAsync async = asyncWithUpload(t, store);

        async.sendDocumentMessageFromPath("chat-1", doc.getAbsolutePath(), "application/pdf",
                "contract", null, capture(out, err));

        CreangerMessageUiModel pending = async.getMessages("chat-1").get(0);
        assertTrue(pending.isPending());
        assertEquals(MessageType.DOCUMENT, pending.messageType);
        await(out);

        assertNull(err.get());
        assertEquals("server-doc-path", out.get());
        assertEquals("/v1/media/upload-document", t.requests.get(0).path);
        assertEquals("pdf-bytes", new String(t.requests.get(0).body, StandardCharsets.UTF_8));
        assertEquals("/rest/v1/rpc/send_media_message", t.requests.get(1).path);
        assertTrue(t.requests.get(1).jsonBody.contains("document"));
        CreangerMessageUiModel confirmed = async.getMessages("chat-1").get(0);
        assertEquals("document", confirmed.messageType);
        assertFalse(confirmed.isPending());
    }

    @Test
    public void fromPathMissingFileSurfacesErrorAndKeepsFailedRow() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        AtomicReference<String> out = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        CreangerMessageAsync async = asyncWithUpload(t, store);

        async.sendVoiceMessageFromPath("chat-1", "/does/not/exist.m4a", "audio/mp4",
                null, null, capture(out, err));
        await(err);

        assertNull(out.get());
        assertNotNull(err.get());
        // No upload/RPC requests were made for an unreadable file.
        assertTrue(t.requests.isEmpty());
        CreangerMessageUiModel row = async.getMessages("chat-1").get(0);
        assertTrue(row.isFailed());
        assertEquals(MessageType.VOICE, row.messageType);
    }

    @Test
    public void failedMusicSendKeepsFailedRowAndRetryRoutesThroughMusicPath() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        t.responses.add(t.json(502,
                "{\"success\":false,\"error\":{\"code\":\"UPSTREAM_ERROR\",\"message\":\"audio provider rejected the upload\"}}"));
        AtomicReference<String> out = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        CreangerMessageAsync async = asyncWithUpload(t, store);

        async.sendMusicMessage("chat-1", "client-music", "track.m4a",
                "audio-bytes".getBytes(StandardCharsets.UTF_8), "audio/mp4",
                "song", null, capture(out, err));
        await(err);

        assertNull(out.get());
        assertTrue(err.get() instanceof CreangerApiException);
        assertTrue(async.getMessages("chat-1").get(0).isFailed());

        t.responses.add(t.json(200,
                "{\"success\":true,\"data\":{\"storageProvider\":\"cloudinary\",\"storageKey\":\"audio/track2\","
                        + "\"publicUrl\":\"https://res.cloudinary.com/x/video/upload/v1/audio/track2.m4a\","
                        + "\"mimeType\":\"audio/mp4\",\"sizeBytes\":11,\"deliveryUrl\":null,"
                        + "\"durationMs\":224000}}"));
        t.responses.add(t.json(200, "\"server-music-2\""));
        out.set(null);
        err.set(null);
        async.retrySendAudioMessage("chat-1", "client-music", capture(out, err));
        await(out);

        assertNull(err.get());
        assertEquals("server-music-2", out.get());
        // Retry went through the MUSIC path (AUDIO payload), not the voice path.
        // Request order: [0] failed upload, [1] retry upload, [2] RPC.
        assertTrue(t.requests.get(2).jsonBody.contains("audio"));
        assertFalse(t.requests.get(2).jsonBody.contains("voice"));
        List<CreangerMessageUiModel> msgs = async.getMessages("chat-1");
        assertEquals(1, msgs.size());
        assertEquals("server-music-2", msgs.get(0).id);
        assertEquals("audio", msgs.get(0).messageType);
        assertFalse(msgs.get(0).isPending());
    }

    // ---- errors ----

    @Test
    public void unauthorizedRefreshReportsTypedApiError() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        t.responses.add(t.json(401, "{\"message\":\"not authorized\"}"));
        AtomicReference<MessagePage> out = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        CreangerMessageAsync async = async(t, store);

        async.refreshMessages("chat-1", 30, capture(out, err));
        await(err);

        assertNull(out.get());
        assertNotNull(err.get());
        assertTrue(err.get() instanceof CreangerApiException);
        assertTrue(((CreangerApiException) err.get()).is("UNAUTHORIZED"));
    }

    // ---- media send + attachment recovery (migration 029) ----

    private static MediaAttachment mediaAtt(String storageKey, Integer duration) {
        return new MediaAttachment(null, null, "media-1", 0, null, "cloudinary", storageKey,
                "https://pub.example/" + storageKey, null, "video/mp4" , 12345, "abc123",
                1920, 1080, duration, null, null, null, null, null, "2026-08-15T08:01:00Z");
    }

    @Test
    public void sendMediaShowsPendingTypeAndAttachmentsThenConfirms() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        t.responses.add(t.json(200, "\"server-media-1\""));
        AtomicReference<String> out = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        CreangerMessageAsync async = async(t, store);
        List<MediaAttachment> atts = java.util.Collections.singletonList(mediaAtt("k/vid", 15000));

        async.sendMediaMessage("chat-1", "client-media", "video", "hi", atts, capture(out, err));

        // Optimistic pending media row renders instantly (before the RPC resolves).
        CreangerMessageUiModel pending = async.getMessages("chat-1").get(0);
        assertEquals("video", pending.messageType);
        assertTrue(pending.isMedia());
        assertEquals("hi", pending.content);
        assertEquals(1, pending.attachments.size());
        assertEquals("k/vid", pending.attachments.get(0).storageKey);
        assertEquals(Integer.valueOf(15000), pending.attachments.get(0).duration);

        await(out);

        assertNull(err.get());
        assertEquals("server-media-1", out.get());
        assertEquals("/rest/v1/rpc/send_media_message", t.requests.get(0).path);
        CreangerMessageUiModel confirmed = async.getMessages("chat-1").get(0);
        assertEquals("server-media-1", confirmed.id);
        assertEquals("video", confirmed.messageType);
        assertEquals(1, confirmed.attachments.size());
        assertFalse(confirmed.isPending());
        assertFalse(confirmed.isLocal);
    }

    @Test
    public void sendMediaFailureFlagsFailedMediaRowAndReportsError() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        t.failAll = new IOException("no route to host");
        AtomicReference<String> out = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        CreangerMessageAsync async = async(t, store);

        async.sendMediaMessage("chat-1", "client-media-fail", "voice", null,
                java.util.Collections.singletonList(mediaAtt("k/voice", 4000)), capture(out, err));
        await(err);

        assertNull(out.get());
        assertNotNull(err.get());
        assertTrue(err.get() instanceof IOException);
        CreangerMessageUiModel row = async.getMessages("chat-1").get(0);
        assertTrue(row.isFailed());
        assertEquals("voice", row.messageType);
        assertEquals(1, row.attachments.size());
        assertEquals(Integer.valueOf(4000), row.attachments.get(0).duration);
    }

    @Test
    public void loadAttachmentsFillsFlatRowsAfterPageLoad() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        t.responses.add(t.json(200, rowsJson(
                "{\"id\":\"m-vid\",\"chat_id\":\"chat-1\",\"sender_id\":\"uuid-1\",\"message_type\":\"video\","
                        + "\"content\":\"caption\",\"status\":\"sent\",\"client_message_id\":null,\"chat_seq\":1,"
                        + "\"created_at\":\"2026-08-15T08:07:00Z\",\"edited_at\":null,\"deleted_at\":null,"
                        + "\"updated_at\":\"2026-08-15T08:07:00Z\"}")));
        t.responses.add(t.json(200, rowsJson(
                "{\"id\":\"att-1\",\"message_id\":\"m-vid\",\"position\":0,\"caption\":null,"
                        + "\"media\":{\"id\":\"media-1\",\"owner_id\":\"uuid-1\",\"storage_provider\":\"imagebb\","
                        + "\"storage_key\":\"ib/x\",\"public_url\":null,\"delivery_url\":\"https://dl.example/x\","
                        + "\"mime_type\":\"video/mp4\",\"size_bytes\":999,\"checksum\":null,\"width\":1920,"
                        + "\"height\":1080,\"duration\":15000,\"thumbnail_media_id\":null,"
                        + "\"created_at\":\"2026-08-15T08:02:00Z\",\"deleted_at\":null}}")));
        AtomicReference<MessagePage> page = new AtomicReference<>();
        AtomicReference<Throwable> pageErr = new AtomicReference<>();
        AtomicReference<Void> attOut = new AtomicReference<>();
        AtomicReference<Throwable> attErr = new AtomicReference<>();
        CreangerMessageAsync async = async(t, store);

        async.refreshMessages("chat-1", 30, capture(page, pageErr));
        await(page);
        assertNull(pageErr.get());
        assertTrue(async.getMessages("chat-1").get(0).attachments.isEmpty());

        List<String> ids = new ArrayList<>();
        ids.add("m-vid");
        async.loadAttachments("chat-1", ids, capture(attOut, attErr));
        await(attOut);

        assertNull(attErr.get());
        assertEquals("/rest/v1/message_attachments", t.requests.get(1).path);
        CreangerMessageUiModel filled = async.getMessages("chat-1").get(0);
        assertEquals("video", filled.messageType);
        assertEquals(1, filled.attachments.size());
        assertEquals("ib/x", filled.attachments.get(0).storageKey);
        assertEquals("video/mp4", filled.attachments.get(0).mimeType);
        assertEquals(Integer.valueOf(1920), filled.attachments.get(0).width);
    }
}
