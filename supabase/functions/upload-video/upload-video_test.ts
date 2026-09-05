// ============================================================================
// CREANGER VIDEO UPLOAD EDGE FUNCTION + CLOUDINARY PROVIDER - UNIT TESTS
// ============================================================================
// Runs with:  deno test supabase/functions/upload-video/
// Pure tests: stub fetch, injected env. No network, no secrets.
// ============================================================================

import { assertEquals, assertInstanceOf, assert } from "jsr:@std/assert";
import { handleUpload, MediaApiEnv } from "./index.ts";
import {
  CloudinaryUpstreamError,
  CLOUDINARY_API_URL,
  parseCloudinaryResponse,
  uploadToCloudinary,
} from "./cloudinary.ts";

// ---- helpers ---------------------------------------------------------------

const TEST_CLOUD_NAME = "test-cloud";
const TEST_API_KEY = "test-api-key";
const TEST_API_SECRET = "test-api-secret";
const VIDEO_BYTES = new Uint8Array([0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6F, 0x6D]); // Minimal MP4 header
const ENV: MediaApiEnv = { CLOUDINARY_CLOUD_NAME: TEST_CLOUD_NAME, CLOUDINARY_API_KEY: TEST_API_KEY, CLOUDINARY_API_SECRET: TEST_API_SECRET };

function cloudinarySuccessBody(
  publicId = "test/video123",
  url = "https://res.cloudinary.com/test-cloud/video/upload/v1/test/video123.mp4",
  thumbUrl = "https://res.cloudinary.com/test-cloud/video/upload/e_start_offset:0.1,c_fill,w_320,h_180/v1/test/video123.jpg"
): string {
  return JSON.stringify({
    public_id: publicId,
    version: 1,
    signature: "abc123",
    width: 1920,
    height: 1080,
    format: "mp4",
    resource_type: "video",
    created_at: "2026-08-15T12:00:00Z",
    tags: [],
    bytes: VIDEO_BYTES.length,
    type: "upload",
    etag: "abc",
    placeholder: false,
    url: `http://res.cloudinary.com/test-cloud/video/upload/v1/${publicId}.mp4`,
    secure_url: url,
    duration: 42.5,
    eager: [
      {
        transformation: "e_start_offset:0.1,c_fill,w_320,h_180",
        width: 320,
        height: 180,
        url: `http://res.cloudinary.com/test-cloud/video/upload/e_start_offset:0.1,c_fill,w_320,h_180/v1/${publicId}.jpg`,
        secure_url: thumbUrl,
      },
    ],
  });
}

type FetchCall = { url: string; init: RequestInit };
const callsByFn = new WeakMap<Function, FetchCall[]>();

/** Wraps a stub fetch impl so every call is recorded for assertions. */
function recording(impl: typeof fetch): typeof fetch {
  const calls: FetchCall[] = [];
  const wrapper = (async (input: RequestInfo | URL, init?: RequestInit) => {
    calls.push({ url: String(input), init: init ?? {} });
    return impl(input, init);
  }) as typeof fetch;
  callsByFn.set(wrapper, calls);
  return wrapper;
}

function recorded(fetchImpl: typeof fetch): FetchCall[] {
  return callsByFn.get(fetchImpl) ?? [];
}

function post(env: MediaApiEnv, headers: Record<string, string>, body: Uint8Array, path = "/functions/v1/upload-video?name=vid.mp4"): Request {
  return new Request(`https://proj.supabase.co${path}`, {
    method: "POST",
    headers: { authorization: "Bearer jwt.token.here", "content-type": "video/mp4", ...headers },
    body: body as unknown as BodyInit,
  });
}

// ---- cloudinary client (backend -> Cloudinary) -----------------------------------

Deno.test("uploadToCloudinary posts key + base64 video to Cloudinary and never forwards auth", async () => {
  const stub = recording((_input, init) => {
    return Promise.resolve(new Response(cloudinarySuccessBody(), { status: 200 }));
  });
  const media = await uploadToCloudinary(stub, {
    cloudName: TEST_CLOUD_NAME,
    apiKey: TEST_API_KEY,
    apiSecret: TEST_API_SECRET,
    videoBytes: VIDEO_BYTES,
    mimeType: "video/mp4",
  });

  const calls = recorded(stub);
  assertEquals(calls.length, 1);
  assertEquals(calls[0].url, `${CLOUDINARY_API_URL}/${TEST_CLOUD_NAME}/video/upload`);
  const form = calls[0].init.body as FormData;
  assertEquals(form.get("api_key"), TEST_API_KEY);
  assertEquals(form.get("resource_type"), "video");
  assertEquals(form.get("eager_async"), "false");
  // Account isolation: the Creanger user JWT must never be sent to the provider.
  const headers = new Headers(calls[0].init.headers);
  assert(headers.get("authorization") === null, "Authorization header leaked to Cloudinary");

  assertEquals(media.storageProvider, "cloudinary");
  assertEquals(media.storageKey, "test/video123");
  assertEquals(media.publicUrl, "https://res.cloudinary.com/test-cloud/video/upload/v1/test/video123.mp4");
  assertEquals(media.mimeType, "video/mp4");
  assertEquals(media.sizeBytes, VIDEO_BYTES.length);
  assertEquals(media.thumbnailUrl, "https://res.cloudinary.com/test-cloud/video/upload/e_start_offset:0.1,c_fill,w_320,h_180/v1/test/video123.jpg");
  assertEquals(media.previewUrl, null);
  assertEquals(media.width, 1920);
  assertEquals(media.height, 1080);
  assertEquals(media.durationMs, 42500);
});

Deno.test("uploadToCloudinary sends the title name when provided", async () => {
  const stub = recording(() => Promise.resolve(new Response(cloudinarySuccessBody("my-video"), { status: 200 })));
  await uploadToCloudinary(stub, { cloudName: TEST_CLOUD_NAME, apiKey: TEST_API_KEY, apiSecret: TEST_API_SECRET, videoBytes: VIDEO_BYTES, mimeType: "video/mp4", name: "my video" });
  const form = recorded(stub)[0].init.body as FormData;
  assertEquals(form.get("public_id"), "my video");
});

Deno.test("malformed Cloudinary response throws UPSTREAM_MALFORMED", async () => {
  const stub: typeof fetch = () => Promise.resolve(new Response("not-json", { status: 200 }));
  await assertRejectsWith(CloudinaryUpstreamError, "UPSTREAM_MALFORMED", () =>
    uploadToCloudinary(stub, { cloudName: TEST_CLOUD_NAME, apiKey: TEST_API_KEY, apiSecret: TEST_API_SECRET, videoBytes: VIDEO_BYTES, mimeType: "video/mp4" }));
});

Deno.test("Cloudinary business failure (400) surfaces the provider message", async () => {
  const stub: typeof fetch = () =>
    Promise.resolve(new Response(
      JSON.stringify({ error: { message: "Invalid API key" } }),
      { status: 400 }));
  await assertRejectsWith(CloudinaryUpstreamError, "UPSTREAM_ERROR", () =>
    uploadToCloudinary(stub, { cloudName: TEST_CLOUD_NAME, apiKey: "bad-key", apiSecret: TEST_API_SECRET, videoBytes: VIDEO_BYTES, mimeType: "video/mp4" }), (e) => {
    assert(e.message.includes("Invalid API key") || e.message.includes("rejected"));
  });
});

Deno.test("Cloudinary HTTP failure maps to UPSTREAM_ERROR", async () => {
  const stub: typeof fetch = () => Promise.resolve(new Response("", { status: 500 }));
  await assertRejectsWith(CloudinaryUpstreamError, "UPSTREAM_ERROR", () =>
    uploadToCloudinary(stub, { cloudName: TEST_CLOUD_NAME, apiKey: TEST_API_KEY, apiSecret: TEST_API_SECRET, videoBytes: VIDEO_BYTES, mimeType: "video/mp4" }));
});

Deno.test("Cloudinary timeout maps to UPSTREAM_TIMEOUT", async () => {
  const stub: typeof fetch = (_input, init) =>
    new Promise((_resolve, reject) => {
      init?.signal?.addEventListener("abort", () =>
        reject(new DOMException("The operation was aborted.", "AbortError")));
    });
  await assertRejectsWith(CloudinaryUpstreamError, "UPSTREAM_TIMEOUT", () =>
    uploadToCloudinary(stub, { cloudName: TEST_CLOUD_NAME, apiKey: TEST_API_KEY, apiSecret: TEST_API_SECRET, videoBytes: VIDEO_BYTES, mimeType: "video/mp4", timeoutMs: 20 }));
});

Deno.test("blank returned url maps to UPSTREAM_INVALID_URL", () => {
  assertThrowsCode("UPSTREAM_INVALID_URL", () =>
    parseCloudinaryResponse(
      JSON.stringify({ public_id: "id", secure_url: "  " }),
      200, "video/mp4", 4));
});

Deno.test("non-http returned url maps to UPSTREAM_INVALID_URL", () => {
  assertThrowsCode("UPSTREAM_INVALID_URL", () =>
    parseCloudinaryResponse(
      JSON.stringify({ public_id: "id", secure_url: "file:///tmp/x.mp4" }),
      200, "video/mp4", 4));
});

Deno.test("missing public_id maps to UPSTREAM_MALFORMED", () => {
  assertThrowsCode("UPSTREAM_MALFORMED", () =>
    parseCloudinaryResponse(JSON.stringify({ secure_url: "https://res.cloudinary.com/x.mp4" }), 200, "video/mp4", 4));
});

// ---- media API handler (Android -> Creanger Media API) ---------------------

Deno.test("successful video upload returns provider-neutral UploadedMedia envelope with thumbnailUrl", async () => {
  const stub = recording(() => Promise.resolve(new Response(cloudinarySuccessBody(), { status: 200 })));
  const res = await handleUpload(post(ENV, {}, VIDEO_BYTES), ENV, stub);
  assertEquals(res.status, 200);
  const json = await res.json();
  assertEquals(json.success, true);
  assertEquals(json.data.storageProvider, "cloudinary");
  assertEquals(json.data.storageKey, "test/video123");
  assertEquals(json.data.publicUrl, "https://res.cloudinary.com/test-cloud/video/upload/v1/test/video123.mp4");
  assertEquals(json.data.mimeType, "video/mp4");
  assertEquals(json.data.sizeBytes, VIDEO_BYTES.length);
  assertEquals(json.data.thumbnailUrl, "https://res.cloudinary.com/test-cloud/video/upload/e_start_offset:0.1,c_fill,w_320,h_180/v1/test/video123.jpg");
  assertEquals(json.data.previewUrl, null);
  assertEquals(recorded(stub).length, 1);
  assertEquals(recorded(stub)[0].url, `${CLOUDINARY_API_URL}/${TEST_CLOUD_NAME}/video/upload`);
});

Deno.test("authentication failure: missing JWT is rejected before the provider call", async () => {
  let providerCalled = false;
  const stub: typeof fetch = () => {
    providerCalled = true;
    return Promise.resolve(new Response(cloudinarySuccessBody(), { status: 200 }));
  };
  const req = new Request("https://proj.supabase.co/functions/v1/upload-video", {
    method: "POST",
    headers: { "content-type": "video/mp4" },
    body: VIDEO_BYTES as unknown as BodyInit,
  });
  const res = await handleUpload(req, ENV, stub);
  assertEquals(res.status, 401);
  const json = await res.json();
  assertEquals(json.success, false);
  assertEquals(json.error.code, "MISSING_TOKEN");
  assert(!providerCalled, "provider must not be called for an unauthenticated request");
});

Deno.test("too-large video is rejected (413) before the provider call", async () => {
  const big = new Uint8Array(300);
  const res = await handleUpload(post(ENV, { "content-length": String(300) }, big), { CLOUDINARY_CLOUD_NAME: TEST_CLOUD_NAME, CLOUDINARY_API_KEY: TEST_API_KEY, CLOUDINARY_API_SECRET: TEST_API_SECRET, MAX_UPLOAD_BYTES: "100" }, () =>
    Promise.resolve(new Response("", { status: 500 })));
  assertEquals(res.status, 413);
  const json = await res.json();
  assertEquals(json.error.code, "VALIDATION_ERROR");
});

Deno.test("unsupported content type is rejected (415) before the provider call", async () => {
  const req = new Request("https://proj.supabase.co/functions/v1/upload-video", {
    method: "POST",
    headers: { authorization: "Bearer x.y.z", "content-type": "image/jpeg" },
    body: VIDEO_BYTES as unknown as BodyInit,
  });
  const res = await handleUpload(req, ENV, () => Promise.resolve(new Response("", { status: 500 })));
  assertEquals(res.status, 415);
  const json = await res.json();
  assertEquals(json.error.code, "VALIDATION_ERROR");
});

Deno.test("empty body is rejected (400)", async () => {
  const res = await handleUpload(post(ENV, {}, new Uint8Array(0)), ENV, () =>
    Promise.resolve(new Response("", { status: 500 })));
  assertEquals(res.status, 400);
});

Deno.test("missing Cloudinary credentials is an INTERNAL_ERROR, never a silent upload", async () => {
  const res = await handleUpload(post({}, {}, VIDEO_BYTES), {}, () =>
    Promise.resolve(new Response(cloudinarySuccessBody(), { status: 200 })));
  assertEquals(res.status, 500);
  const json = await res.json();
  assertEquals(json.error.code, "INTERNAL_ERROR");
});

Deno.test("failed provider upload never returns a success envelope", async () => {
  const stub: typeof fetch = () =>
    Promise.resolve(new Response(
      JSON.stringify({ error: { message: "max upload size 32MB exceeded" } }),
      { status: 400 }));
  const res = await handleUpload(post(ENV, {}, VIDEO_BYTES), ENV, stub);
  assertEquals(res.status, 502);
  const json = await res.json();
  assertEquals(json.success, false);
  assertNotNull(json.error, "provider failure must surface as an error");
  assert(!("data" in json), "no provider-neutral data may be returned on failure");
});

// ---- misc ------------------------------------------------------------------

async function assertRejectsWith(
  ctor: { new (...args: any[]): any },
  code: string,
  fn: () => Promise<unknown>,
  extra?: (e: CloudinaryUpstreamError) => void,
) {
  await fn().then(
    () => {
      throw new Error(`expected ${ctor.name}(${code})`);
    },
    (e: unknown) => {
      assertInstanceOf(e, ctor);
      assertEquals((e as CloudinaryUpstreamError).code, code);
      extra?.((e as CloudinaryUpstreamError));
    },
  );
}

function assertThrowsCode(code: string, fn: () => unknown) {
  try {
    fn();
    throw new Error(`expected ${code}`);
  } catch (e) {
    assertInstanceOf(e, CloudinaryUpstreamError);
    assertEquals((e as CloudinaryUpstreamError).code, code);
  }
}

function assertNotNull(v: unknown, msg: string) {
  assert(v !== null && v !== undefined, msg);
}