// ============================================================================
// CREANGER MEDIA API (Edge Function) + IMAGEBB PROVIDER - UNIT TESTS
// ============================================================================
// Runs with:  deno test supabase/functions/upload-image/
// Pure tests: stub fetch, injected env. No network, no secrets.
// ============================================================================

import { assertEquals, assertInstanceOf, assert } from "jsr:@std/assert";
import { handleUpload, MediaApiEnv } from "./index.ts";
import {
  ImageBbUpstreamError,
  IMAGEBB_API_URL,
  parseImageBbResponse,
  uploadToImageBb,
} from "./imagebb.ts";

// ---- helpers ---------------------------------------------------------------

const TEST_KEY = "test-imagebb-server-key-123";
const JPEG_BYTES = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 1, 2, 3, 4]);
const ENV: MediaApiEnv = { IMAGEBB_API_KEY: TEST_KEY };

function imgbbSuccessBody(id = "2ndCYJK", url = "https://i.ibb.co/xYz/image.jpg"): string {
  return JSON.stringify({
    data: { id, url, display_url: url, delete_url: "https://ibb.co/delete/abc", width: 640, height: 480, size: 4 },
    success: true,
    status: 200,
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

function post(env: MediaApiEnv, headers: Record<string, string>, body: Uint8Array, path = "/functions/v1/upload-image?name=pic.jpg"): Request {
  return new Request(`https://proj.supabase.co${path}`, {
    method: "POST",
    headers: { authorization: "Bearer jwt.token.here", "content-type": "image/jpeg", ...headers },
    body: body as unknown as BodyInit,
  });
}

// ---- imagebb client (backend -> ImageBB) -----------------------------------

Deno.test("uploadToImageBb posts key + base64 image to ImageBB and never forwards auth", async () => {
  const stub = recording((_input, init) => {
    return Promise.resolve(new Response(imgbbSuccessBody(), { status: 200 }));
  });
  const media = await uploadToImageBb(stub, { apiKey: TEST_KEY, imageBytes: JPEG_BYTES, mimeType: "image/jpeg" });

  const calls = recorded(stub);
  assertEquals(calls.length, 1);
  assertEquals(calls[0].url, IMAGEBB_API_URL);
  const form = calls[0].init.body as FormData;
  assertEquals(form.get("key"), TEST_KEY);
  assertEquals(form.get("name"), null);
  const headers = new Headers(calls[0].init.headers);
  // Account isolation: the Creanger user JWT must never be sent to the provider.
  assert(headers.get("authorization") === null, "Authorization header leaked to ImageBB");

  assertEquals(media.storageProvider, "imagebb");
  assertEquals(media.storageKey, "2ndCYJK");
  assertEquals(media.publicUrl, "https://i.ibb.co/xYz/image.jpg");
  assertEquals(media.mimeType, "image/jpeg");
  assertEquals(media.sizeBytes, JPEG_BYTES.byteLength);
  assertEquals(media.deliveryUrl, null);
});

Deno.test("uploadToImageBb sends the title name when provided", async () => {
  const stub = recording(() => Promise.resolve(new Response(imgbbSuccessBody(), { status: 200 })));
  await uploadToImageBb(stub, { apiKey: TEST_KEY, imageBytes: JPEG_BYTES, mimeType: "image/jpeg", name: "my pic" });
  const form = recorded(stub)[0].init.body as FormData;
  assertEquals(form.get("name"), "my pic");
});

Deno.test("malformed ImageBB response throws UPSTREAM_MALFORMED", async () => {
  const stub: typeof fetch = () => Promise.resolve(new Response("not-json", { status: 200 }));
  await assertRejectsWith(ImageBbUpstreamError, "UPSTREAM_MALFORMED", () =>
    uploadToImageBb(stub, { apiKey: TEST_KEY, imageBytes: JPEG_BYTES, mimeType: "image/jpeg" }));
});

Deno.test("ImageBB business failure (success:false) surfaces the provider message", async () => {
  const stub: typeof fetch = () =>
    Promise.resolve(new Response(
      JSON.stringify({ data: { error: { message: "Invalid API key", code: "exception_1" } }, success: false, status: 400 }),
      { status: 400 }));
  await assertRejectsWith(ImageBbUpstreamError, "UPSTREAM_ERROR", () =>
    uploadToImageBb(stub, { apiKey: "bad-key", imageBytes: JPEG_BYTES, mimeType: "image/jpeg" }), (e) => {
    assert(e.message.includes("Invalid API key"), `provider message must surface, got: ${e.message}`);
  });
});

Deno.test("ImageBB HTTP failure maps to UPSTREAM_ERROR", async () => {
  const stub: typeof fetch = () => Promise.resolve(new Response("", { status: 500 }));
  await assertRejectsWith(ImageBbUpstreamError, "UPSTREAM_ERROR", () =>
    uploadToImageBb(stub, { apiKey: TEST_KEY, imageBytes: JPEG_BYTES, mimeType: "image/jpeg" }));
});

Deno.test("ImageBB timeout maps to UPSTREAM_TIMEOUT", async () => {
  const stub: typeof fetch = (_input, init) =>
    new Promise((_resolve, reject) => {
      init?.signal?.addEventListener("abort", () =>
        reject(new DOMException("The operation was aborted.", "AbortError")));
    });
  await assertRejectsWith(ImageBbUpstreamError, "UPSTREAM_TIMEOUT", () =>
    uploadToImageBb(stub, { apiKey: TEST_KEY, imageBytes: JPEG_BYTES, mimeType: "image/jpeg", timeoutMs: 20 }));
});

Deno.test("blank returned url maps to UPSTREAM_INVALID_URL", () => {
  assertThrowsCode("UPSTREAM_INVALID_URL", () =>
    parseImageBbResponse(imgbbSuccessBody("id-1", "  "), 200, "image/jpeg", 4));
});

Deno.test("non-http returned url maps to UPSTREAM_INVALID_URL", () => {
  assertThrowsCode("UPSTREAM_INVALID_URL", () =>
    parseImageBbResponse(imgbbSuccessBody("id-1", "file:///tmp/x.jpg"), 200, "image/jpeg", 4));
});

Deno.test("missing storage id maps to UPSTREAM_MALFORMED", () => {
  assertThrowsCode("UPSTREAM_MALFORMED", () =>
    parseImageBbResponse(JSON.stringify({ data: { url: "https://i.ibb.co/x.jpg" }, success: true, status: 200 }), 200, "image/jpeg", 4));
});

// ---- media API handler (Android -> Creanger Media API) ---------------------

Deno.test("successful upload returns provider-neutral UploadedMedia envelope", async () => {
  const stub = recording(() => Promise.resolve(new Response(imgbbSuccessBody(), { status: 200 })));
  const res = await handleUpload(post(ENV, {}, JPEG_BYTES), ENV, stub);
  assertEquals(res.status, 200);
  const json = await res.json();
  assertEquals(json.success, true);
  assertEquals(json.data.storageProvider, "imagebb");
  assertEquals(json.data.storageKey, "2ndCYJK");
  assertEquals(json.data.publicUrl, "https://i.ibb.co/xYz/image.jpg");
  assertEquals(json.data.mimeType, "image/jpeg");
  assertEquals(json.data.sizeBytes, JPEG_BYTES.byteLength);
  const calls = recorded(stub);
  assertEquals(calls.length, 1);
  assertEquals(calls[0].url, IMAGEBB_API_URL);
});

Deno.test("authentication failure: missing JWT is rejected before the provider call", async () => {
  let providerCalled = false;
  const stub: typeof fetch = () => {
    providerCalled = true;
    return Promise.resolve(new Response(imgbbSuccessBody(), { status: 200 }));
  };
  const req = new Request("https://proj.supabase.co/functions/v1/upload-image", {
    method: "POST",
    headers: { "content-type": "image/jpeg" },
    body: JPEG_BYTES as unknown as BodyInit,
  });
  const res = await handleUpload(req, ENV, stub);
  assertEquals(res.status, 401);
  const json = await res.json();
  assertEquals(json.success, false);
  assertEquals(json.error.code, "MISSING_TOKEN");
  assert(!providerCalled, "provider must not be called for an unauthenticated request");
});

Deno.test("too-large upload is rejected (413) before the provider call", async () => {
  const big = new Uint8Array(300);
  const res = await handleUpload(post(ENV, { "content-length": String(300) }, big), { IMAGEBB_API_KEY: TEST_KEY, MAX_UPLOAD_BYTES: "100" }, () =>
    Promise.resolve(new Response("", { status: 500 })));
  assertEquals(res.status, 413);
  const json = await res.json();
  assertEquals(json.error.code, "VALIDATION_ERROR");
});

Deno.test("unsupported content type is rejected (415) before the provider call", async () => {
  const req = new Request("https://proj.supabase.co/functions/v1/upload-image", {
    method: "POST",
    headers: { authorization: "Bearer x.y.z", "content-type": "video/mp4" },
    body: JPEG_BYTES as unknown as BodyInit,
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

Deno.test("missing IMAGEBB_API_KEY is an INTERNAL_ERROR, never a silent upload", async () => {
  const res = await handleUpload(post({}, {}, JPEG_BYTES), {}, () =>
    Promise.resolve(new Response(imgbbSuccessBody(), { status: 200 })));
  assertEquals(res.status, 500);
  const json = await res.json();
  assertEquals(json.error.code, "INTERNAL_ERROR");
});

Deno.test("failed provider upload never returns a success envelope", async () => {
  const stub: typeof fetch = () =>
    Promise.resolve(new Response(
      JSON.stringify({ data: { error: { message: "max upload size 32MB exceeded" } }, success: false, status: 400 }),
      { status: 400 }));
  const res = await handleUpload(post(ENV, {}, JPEG_BYTES), ENV, stub);
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
  extra?: (e: ImageBbUpstreamError) => void,
) {
  await fn().then(
    () => {
      throw new Error(`expected ${ctor.name}(${code})`);
    },
    (e: unknown) => {
      assertInstanceOf(e, ctor);
      assertEquals((e as ImageBbUpstreamError).code, code);
      extra?.((e as ImageBbUpstreamError));
    },
  );
}

function assertThrowsCode(code: string, fn: () => unknown) {
  try {
    fn();
    throw new Error(`expected ${code}`);
  } catch (e) {
    assertInstanceOf(e, ImageBbUpstreamError);
    assertEquals((e as ImageBbUpstreamError).code, code);
  }
}

function assertNotNull(v: unknown, msg: string) {
  assert(v !== null && v !== undefined, msg);
}