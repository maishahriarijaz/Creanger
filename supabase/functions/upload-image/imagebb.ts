// ============================================================================
// CREANGER MEDIA UPLOAD EDGE FUNCTION - IMAGEBB PROVIDER CLIENT
// ============================================================================
// The Creanger/Admin media API (this Edge Function) is the ONLY place ImageBB
// is ever called. The Android client never sees the ImageBB API key, never
// talks to ImageBB and never parses ImageBB JSON: it calls
// /functions/v1/upload-image and receives a provider-neutral UploadedMedia
// result (storage_provider / storage_key / public_url / mime_type / size_bytes).
//
// Provider abstraction: this module is the single seam between the Creanger
// media API and an external image provider. Later providers (video -> Cloudinary,
// audio/document -> Cloudinary or another) get their own adapter module behind
// the same UploadedMedia contract; no Android or handler change is required.
//
// This module is pure TypeScript (no Deno.net/Deno.env access) so the whole
// provider behaviour is unit-testable with a stubbed fetch.
// ============================================================================

export const IMAGEBB_API_URL = "https://api.imgbb.com/1/upload";

export const IMAGEBB_TIMEOUT_MS = 25_000;

/** Provider-neutral upload result - identical shape on the wire. */
export interface UploadedMedia {
  storageProvider: string;
  storageKey: string;
  publicUrl: string | null;
  mimeType: string;
  sizeBytes: number;
  deliveryUrl: string | null;
}

/** A fetch-compatible function (Deno's global fetch or a test stub). */
export type FetchLike = typeof fetch;

export interface ImageBbUploadParams {
  /** Server-side ImageBB API key (env IMAGEBB_API_KEY). Never leaves the function. */
  apiKey: string;
  /** Raw image bytes (already validated client-side for size/type). */
  imageBytes: Uint8Array;
  /** Optional human-readable name (ImageBB 'title'). Cosmetic only. */
  name?: string;
  /** MIME type of the uploaded bytes (from the client Content-Type). */
  mimeType: string;
  /** Abort timeout for the upstream call. Default {@link IMAGEBB_TIMEOUT_MS}. */
  timeoutMs?: number;
}

/** Upstream error classification used by the handler for its error envelope. */
export class ImageBbUpstreamError extends Error {
  constructor(
    readonly status: number,
    message: string,
    readonly code = "UPSTREAM_ERROR",
  ) {
    super(message);
    this.name = "ImageBbUpstreamError";
  }
}

/**
 * Validates the raw ImageBB response and maps it to a provider-neutral
 * {@link UploadedMedia}. Malformed payloads, blank/non-http URLs and missing
 * ids are rejected explicitly - a broken provider response must never look
 * like a successful upload.
 */
export function parseImageBbResponse(
  body: string,
  status: number,
  mimeType: string,
  sizeBytes: number,
): UploadedMedia {
  if (status < 200 || status >= 300) {
    const providerMessage = extractImageBbMessage(tryParse(body));
    throw new ImageBbUpstreamError(
      status,
      providerMessage ?? `image provider rejected the upload (HTTP ${status})`,
    );
  }
  let root: { success?: boolean; data?: unknown; status?: unknown };
  try {
    root = JSON.parse(body);
  } catch {
    throw new ImageBbUpstreamError(status, "image provider returned a malformed response", "UPSTREAM_MALFORMED");
  }
  if (!root || root.success !== true) {
    throw new ImageBbUpstreamError(status, extractImageBbMessage(root) ?? "image provider rejected the upload");
  }
  const data = root.data as {
    id?: unknown;
    url?: unknown;
    delete_url?: unknown;
  } | undefined;
  const storageKey = typeof data?.id === "string" && data.id !== "" ? data.id : null;
  const publicUrl = typeof data?.url === "string" ? data.url.trim() : null;
  if (storageKey == null) {
    throw new ImageBbUpstreamError(status, "image provider returned no storage id", "UPSTREAM_MALFORMED");
  }
  if (publicUrl == null || publicUrl === "") {
    throw new ImageBbUpstreamError(status, "image provider returned a blank url", "UPSTREAM_INVALID_URL");
  }
  if (!/^https?:\/\//i.test(publicUrl)) {
    throw new ImageBbUpstreamError(status, "image provider returned an invalid url", "UPSTREAM_INVALID_URL");
  }
  return {
    storageProvider: "imagebb",
    storageKey,
    publicUrl,
    mimeType,
    sizeBytes,
    deliveryUrl: null,
  };
}

function extractImageBbMessage(root: unknown): string | null {
  const data = (root as { data?: { error?: { message?: unknown } } })?.data;
  const message = data?.error?.message;
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
 * Uploads {@link imageBytes} to ImageBB and returns the provider-neutral
 * result. The API key is sent server-side only; the Authorization header of
 * the incoming client request is never forwarded to the provider.
 */
export async function uploadToImageBb(
  fetchImpl: FetchLike,
  params: ImageBbUploadParams,
): Promise<UploadedMedia> {
  const form = new FormData();
  // base64 string transport - ImageBB accepts the image data directly as a
  // base64 parameter; the key rides along server-side.
  const base64 = bytesToBase64(params.imageBytes);
  form.set("key", params.apiKey);
  form.set("image", base64);
  if (params.name != null && params.name.trim() !== "") {
    form.set("name", params.name.trim());
  }

  const timeoutMs = params.timeoutMs ?? IMAGEBB_TIMEOUT_MS;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetchImpl(IMAGEBB_API_URL, {
      method: "POST",
      body: form,
      signal: controller.signal,
      // The client's Authorization (Creanger JWT) must never leak to the
      // provider: no headers are forwarded.
    });
    const text = await response.text();
    return parseImageBbResponse(text, response.status, params.mimeType, params.imageBytes.byteLength);
  } catch (err) {
    if (err instanceof ImageBbUpstreamError) {
      throw err;
    }
    if (controller.signal.aborted) {
      throw new ImageBbUpstreamError(504, "image provider timed out", "UPSTREAM_TIMEOUT");
    }
    throw new ImageBbUpstreamError(502, "image provider is unreachable", "UPSTREAM_UNREACHABLE");
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