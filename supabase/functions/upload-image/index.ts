// ============================================================================
// CREANGER / ADMIN MEDIA API - /functions/v1/upload-image
// ============================================================================
// THIS IS THE ONLY IMAGE UPLOAD BOUNDARY THE ANDROID CLIENT CALLS.
//
// Required architecture (Media Phase 4, ImageBB provider):
//
//   Android  --(Bearer user JWT + raw image bytes)-->  /functions/v1/upload-image
//        the function authenticates the caller, validates size/type, uploads
//        the bytes to ImageBB (server-side IMAGEBB_API_KEY), validates the
//        ImageBB response and returns a provider-neutral UploadedMedia result
//        ({ storageProvider, storageKey, publicUrl, mimeType, sizeBytes }).
//   Android  --(UploadedMedia metadata)--> send_media_message RPC (029)
//        --(publicUrl)--> MediaAttachment.publicUrl --> Telegram renderer.
//
// Authentication is NOT a separate system: the function verifies the SAME
// Bearer user JWT the PostgREST data plane already requires (the Supabase
// platform validates the signature via the project JWT secret when
// verify_jwt=true, and this handler additionally enforces its presence so the
// boundary is deterministic). ImageBB credentials and provider JSON are owned
// entirely server-side - the Android client contains neither.
//
// Provider neutrality: imagebb.ts is the only provider-specific module. Future
// providers (video -> Cloudinary, etc.) map behind the same UploadedMedia
// contract without touching this handler's request/response shape.
// ============================================================================
// ---------------------------------------------------------------------------
// DEPRECATED — DO NOT DEPLOY.
//
// The Media upload API moved out of Supabase Edge Functions into the existing
// Creanger backend (creanger-auth-milestone3): Android now POSTs to the
// authenticated /v1/media/upload-image and /v1/media/upload-video endpoints of
// the Creanger backend (verifying the Custom Creanger JWT via authMiddleware).
// Supabase remains PostgreSQL + Realtime ONLY for this feature; ImageBB /
// Cloudinary are reached server-side by the backend's Media Service.
//
// This function was never deployed on the live project (returns 404). It and
// upload-video/ are kept only as the historical adapter source that was ported
// to the backend (src/media/imagebb.ts, src/services/mediaService.ts). Do NOT
// deploy or depend on them.
// ---------------------------------------------------------------------------

import {
  ImageBbUpstreamError,
  UploadedMedia,
  uploadToImageBb,
} from "./imagebb.ts";

const DEFAULT_MAX_UPLOAD_BYTES = 10 * 1024 * 1024; // 10 MiB
const ALLOWED_MIME_TYPES = new Set(["image/jpeg", "image/png", "image/gif", "image/webp"]);

export interface MediaApiEnv {
  IMAGEBB_API_KEY?: string;
  IMAGEBB_TIMEOUT_MS?: string;
  MAX_UPLOAD_BYTES?: string;
  ALLOWED_MIME_TYPES?: string;
  CORS_ALLOW_ORIGIN?: string;
}

export class MediaApiResponse extends Response {
  constructor(body: string, status: number, origin: string, init: ResponseInit = {}) {
    super(body, { ...init, status, headers: corsHeaders(init.headers, origin) });
  }
}

function corsHeaders(headers: HeadersInit | undefined, origin: string): Headers {
  const h = new Headers(headers);
  h.set("Access-Control-Allow-Origin", origin);
  h.set("Access-Control-Allow-Headers", "authorization, x-client-info, apikey, content-type, x-file-name");
  h.set("Access-Control-Allow-Methods", "POST, OPTIONS");
  return h;
}

function corsOrigin(env: MediaApiEnv): string {
  const fromEnv = (env.CORS_ALLOW_ORIGIN ?? "").trim();
  return fromEnv === "" ? "*" : fromEnv;
}

function ok(data: UploadedMedia, origin: string): MediaApiResponse {
  return new MediaApiResponse(
    JSON.stringify({ success: true, data }),
    200,
    origin,
    { headers: { "Content-Type": "application/json" } },
  );
}

function fail(status: number, code: string, message: string, origin: string): MediaApiResponse {
  return new MediaApiResponse(
    JSON.stringify({ success: false, error: { code, message } }),
    status,
    origin,
    { headers: { "Content-Type": "application/json" } },
  );
}

function extractName(name: string | null): string | undefined {
  const trimmed = name?.trim();
  return trimmed == null || trimmed === "" ? undefined : trimmed;
}

/**
 * The media upload endpoint. Pure and testable: every dependency (env + the
 * fetch-compatible provider caller) is injected.
 */
export async function handleUpload(
  request: Request,
  env: MediaApiEnv,
  providerFetch = fetch,
): Promise<Response> {
  if (request.method === "OPTIONS") {
    return new Response(null, { status: 204 });
  }
  const origin = corsOrigin(env);

  if (request.method !== "POST") {
    return fail(405, "METHOD_NOT_ALLOWED", "media upload requires POST", origin);
  }

  // Authentication: the SAME user JWT the data plane uses. Never a second
  // authentication system. (In production the platform already verified the
  // signature; this guard makes the boundary fail closed regardless.)
  const authorization = request.headers.get("authorization");
  if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length <= 7) {
    return fail(401, "MISSING_TOKEN", "authentication required", origin);
  }

  // Size/type validation happens here, server-side, before any provider call.
  const mimeType = (request.headers.get("content-type") ?? "").split(";")[0].trim().toLowerCase();
  const allowedTypes = parseAllowedTypes(env.ALLOWED_MIME_TYPES);
  if (!allowedTypes.has(mimeType)) {
    return fail(
      415,
      "VALIDATION_ERROR",
      `unsupported image content type '${mimeType === "" ? "(none)" : mimeType}'`,
      origin,
    );
  }

  const maxBytes = parseMaxBytes(env.MAX_UPLOAD_BYTES);

  const url = new URL(request.url);
  const declaredLength = Number(request.headers.get("content-length") ?? "0");
  if (declaredLength > maxBytes) {
    return fail(413, "VALIDATION_ERROR", "image exceeds the upload size limit", origin);
  }

  let bytes: Uint8Array;
  try {
    bytes = new Uint8Array(await request.arrayBuffer());
  } catch {
    return fail(400, "BAD_REQUEST", "could not read the upload body", origin);
  }
  if (bytes.byteLength === 0) {
    return fail(400, "VALIDATION_ERROR", "upload contains no image bytes", origin);
  }
  if (bytes.byteLength > maxBytes) {
    return fail(413, "VALIDATION_ERROR", "image exceeds the upload size limit", origin);
  }

  const apiKey = (env.IMAGEBB_API_KEY ?? "").trim();
  if (apiKey === "") {
    return fail(500, "INTERNAL_ERROR", "image provider is not configured on the server", origin);
  }

  const timeoutMs = parseTimeoutMs(env.IMAGEBB_TIMEOUT_MS);

  try {
    const uploaded = await uploadToImageBb(providerFetch, {
      apiKey,
      imageBytes: bytes,
      mimeType,
      name: extractName(url.searchParams.get("name")),
      timeoutMs,
    });
    return ok(uploaded, origin);
  } catch (err) {
    if (err instanceof ImageBbUpstreamError) {
      const status = err.status === 504 ? 504 : 502;
      const code =
        err.code === "UPSTREAM_TIMEOUT" ? "UPSTREAM_TIMEOUT"
          : err.code === "UPSTREAM_INVALID_URL" ? "UPSTREAM_INVALID_URL"
            : err.code === "UPSTREAM_MALFORMED" ? "UPSTREAM_MALFORMED"
              : err.code === "UPSTREAM_UNREACHABLE" ? "UPSTREAM_UNREACHABLE"
                : "UPSTREAM_ERROR";
      return fail(status, code, err.message, origin);
    }
    return fail(500, "INTERNAL_ERROR", "media upload failed unexpectedly", origin);
  }
}

function parseAllowedTypes(raw: string | undefined): Set<string> {
  if (raw == null || raw.trim() === "") {
    return ALLOWED_MIME_TYPES;
  }
  return new Set(raw.split(",").map((s) => s.trim().toLowerCase()).filter((s) => s !== ""));
}

function parseMaxBytes(raw: string | undefined): number {
  const n = Number(raw);
  return Number.isFinite(n) && n > 0 ? n : DEFAULT_MAX_UPLOAD_BYTES;
}

function parseTimeoutMs(raw: string | undefined): number {
  const n = Number(raw);
  return Number.isFinite(n) && n > 0 ? n : 25_000;
}

if (import.meta.main) {
  Deno.serve((req) => handleUpload(req, Deno.env.toObject()));
}