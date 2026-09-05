// ============================================================================
// CREANGER MEDIA UPLOAD EDGE FUNCTION - CLOUDINARY PROVIDER CLIENT
// ============================================================================
// The Creanger/Admin media API (this Edge Function) is the ONLY place Cloudinary
// is ever called. The Android client never sees the Cloudinary API credentials,
// never talks to Cloudinary and never parses Cloudinary JSON: it calls
// /functions/v1/upload-video and receives a provider-neutral UploadedMedia
// result (storage_provider / storage_key / public_url / thumbnail_url / ...).
//
// Provider abstraction: this module is the single seam between the Creanger
// media API and an external video provider. The same pattern used for ImageBB
// (imagebb.ts) applies here — pure TypeScript, no Deno.net/Deno.env access,
// so the whole provider behaviour is unit-testable with a stubbed fetch.
// ============================================================================

export const CLOUDINARY_API_URL = "https://api.cloudinary.com/v1_1";

export const CLOUDINARY_TIMEOUT_MS = 60_000;

/** Provider-neutral upload result - identical shape on the wire. */
export interface UploadedMedia {
  storageProvider: string;
  storageKey: string;
  publicUrl: string | null;
  mimeType: string;
  sizeBytes: number;
  deliveryUrl: string | null;
  thumbnailUrl: string | null;
  previewUrl: string | null;
  width?: number;
  height?: number;
  durationMs?: number;
}

/** A fetch-compatible function (Deno's global fetch or a test stub). */
export type FetchLike = typeof fetch;

export interface CloudinaryUploadParams {
  /** Server-side Cloudinary credentials (env CLOUDINARY_API_KEY, CLOUDINARY_API_SECRET, CLOUDINARY_CLOUD_NAME). Never leaves the function. */
  cloudName: string;
  apiKey: string;
  apiSecret: string;
  /** Raw video bytes (already validated client-side for size/type). */
  videoBytes: Uint8Array;
  /** MIME type of the uploaded bytes (from the client Content-Type). */
  mimeType: string;
  /** Optional human-readable name (Cloudinary public_id). Cosmetic only. */
  name?: string;
  /** Abort timeout for the upstream call. Default {@link CLOUDINARY_TIMEOUT_MS}. */
  timeoutMs?: number;
}

/** Upstream error classification used by the handler for its error envelope. */
export class CloudinaryUpstreamError extends Error {
  constructor(
    readonly status: number,
    message: string,
    readonly code = "UPSTREAM_ERROR",
  ) {
    super(message);
    this.name = "CloudinaryUpstreamError";
  }
}

/**
 * Validates the raw Cloudinary response and maps it to a provider-neutral
 * {@link UploadedMedia}. Malformed payloads, blank/non-http URLs and missing
 * ids are rejected explicitly - a broken provider response must never look
 * like a successful upload.
 */
export function parseCloudinaryResponse(
  body: string,
  status: number,
  mimeType: string,
  sizeBytes: number,
): UploadedMedia {
  if (status < 200 || status >= 300) {
    const providerMessage = extractCloudinaryMessage(tryParse(body));
    throw new CloudinaryUpstreamError(
      status,
      providerMessage ?? `video provider rejected the upload (HTTP ${status})`,
    );
  }
  let root: { secure_url?: unknown; public_id?: unknown; duration?: unknown; width?: unknown; height?: unknown; eager?: unknown[] } | null;
  try {
    root = JSON.parse(body);
  } catch {
    throw new CloudinaryUpstreamError(status, "video provider returned a malformed response", "UPSTREAM_MALFORMED");
  }
  if (!root) {
    throw new CloudinaryUpstreamError(status, "video provider returned no data", "UPSTREAM_MALFORMED");
  }
  const storageKey = typeof root.public_id === "string" && root.public_id !== "" ? root.public_id : null;
  const publicUrl = typeof root.secure_url === "string" ? root.secure_url.trim() : null;
  if (storageKey == null) {
    throw new CloudinaryUpstreamError(status, "video provider returned no public_id", "UPSTREAM_MALFORMED");
  }
  if (publicUrl == null || publicUrl === "") {
    throw new CloudinaryUpstreamError(status, "video provider returned a blank url", "UPSTREAM_INVALID_URL");
  }
  if (!/^https?:\/\//i.test(publicUrl)) {
    throw new CloudinaryUpstreamError(status, "video provider returned an invalid url", "UPSTREAM_INVALID_URL");
  }

  // Extract thumbnail URL from eager transformations if present
  let thumbnailUrl: string | null = null;
  if (Array.isArray(root.eager) && root.eager.length > 0) {
    const firstEager = root.eager[0] as { secure_url?: unknown } | undefined;
    const eagerUrl = typeof firstEager?.secure_url === "string" ? firstEager.secure_url.trim() : null;
    if (eagerUrl !== "") {
      thumbnailUrl = eagerUrl;
    }
  }

  // No preview URL in basic Cloudinary response; provider-neutral field remains null
  // Could be extended if Cloudinary provides animated preview transformations
  const previewUrl: string | null = null;

  return {
    storageProvider: "cloudinary",
    storageKey,
    publicUrl,
    mimeType,
    sizeBytes,
    deliveryUrl: null,
    thumbnailUrl,
    previewUrl,
    width: typeof root.width === "number" ? root.width : undefined,
    height: typeof root.height === "number" ? root.height : undefined,
    durationMs: typeof root.duration === "number" ? Math.round(root.duration * 1000) : undefined,
  };
}

function extractCloudinaryMessage(root: unknown): string | null {
  const error = (root as { error?: { message?: unknown } })?.error;
  const message = error?.message;
  return typeof message === "string" && message !== "" ? message : null;
}

function tryParse(body: string): unknown {
  try {
    return JSON.parse(body);
  } catch {
    return null;
  }
}

/**
 * Uploads {@link videoBytes} to Cloudinary and returns the provider-neutral
 * result. The API credentials are sent server-side only; the Authorization
 * header of the incoming client request is never forwarded to the provider.
 */
export async function uploadToCloudinary(
  fetchImpl: FetchLike,
  params: CloudinaryUploadParams,
): Promise<UploadedMedia> {
  const form = new FormData();
  const base64 = bytesToBase64(params.videoBytes);
  form.set("file", `data:${params.mimeType};base64,${base64}`);
  form.set("api_key", params.apiKey);
  if (params.name != null && params.name.trim() !== "") {
    form.set("public_id", params.name.trim());
  }
  // Request eager transformation for thumbnail (poster frame at 10% duration)
  form.set("eager", JSON.stringify([
    { width: 320, height: 180, crop: "fill", format: "jpg", start_offset: "0.1" }
  ]));
  form.set("eager_async", "false");
  form.set("resource_type", "video");

  const timeoutMs = params.timeoutMs ?? CLOUDINARY_TIMEOUT_MS;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetchImpl(`${CLOUDINARY_API_URL}/${params.cloudName}/video/upload`, {
      method: "POST",
      body: form,
      signal: controller.signal,
      // The client's Authorization (Creanger JWT) must never leak to the
      // provider: no headers are forwarded.
    });
    const text = await response.text();
    return parseCloudinaryResponse(text, response.status, params.mimeType, params.videoBytes.byteLength);
  } catch (err) {
    if (err instanceof CloudinaryUpstreamError) {
      throw err;
    }
    if (controller.signal.aborted) {
      throw new CloudinaryUpstreamError(504, "video provider timed out", "UPSTREAM_TIMEOUT");
    }
    throw new CloudinaryUpstreamError(502, "video provider is unreachable", "UPSTREAM_UNREACHABLE");
  } finally {
    clearTimeout(timer);
  }
}

export function bytesToBase64(bytes: Uint8Array): string {
  let binary = "";
  for (let i = 0; i < bytes.length; i++) {
    binary += String.fromCharCode(bytes[i]);
  }
  return btoa(binary);
}