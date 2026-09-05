# Creanger / Admin Media API — `upload-image` Edge Function

The authenticated image upload boundary for the Creanger data plane.

```
Android (CreangerMediaUploadClient)
  → POST {chat_base_url}/functions/v1/upload-image
      Authorization: Bearer <user JWT>       (the same JWT PostgREST uses)
      Content-Type: image/jpeg|png|gif|webp
      raw image bytes                         (query: ?name=<title>)
  → this function:
      1. rejects requests without a valid user JWT
      2. validates size (default 10 MiB) and MIME type
      3. uploads the bytes to ImageBB with the server-side IMAGEBB_API_KEY
      4. validates the ImageBB response (id + http(s) url, never blank)
      5. returns a provider-neutral UploadedMedia result
  → Android
      → send_media_message RPC (migration 029) persists metadata only
      → MediaAttachment.publicUrl → existing Telegram image renderer
```

The ImageBB API key, the ImageBB JSON contract and all provider-specific logic
live ONLY on the server. The Android client contains none of it.

## Deploy

```bash
supabase functions deploy upload-image --project-ref <ref> --no-verify-jwt=false
```

`verify_jwt` stays ON (the default): the Supabase platform validates the same
project JWT secret that PostgREST trusts, so this is not a second authentication
system — it is the existing data-plane authentication.

## Environment variables

| Variable | Default | Purpose |
|----------|---------|---------|
| `IMAGEBB_API_KEY` | (required) | ImageBB server-side API key. Secret — set via `supabase secrets set IMAGEBB_API_KEY=...` |
| `MAX_UPLOAD_BYTES` | `10485760` | Maximum accepted image size (10 MiB default) |
| `ALLOWED_MIME_TYPES` | `image/jpeg,image/png,image/gif,image/webp` | Allowed content types |
| `IMAGEBB_TIMEOUT_MS` | `25000` | Upstream provider call timeout |
| `CORS_ALLOW_ORIGIN` | `*` | Access-Control-Allow-Origin |

## Response envelope

Success (`200`):

```json
{
  "success": true,
  "data": {
    "storageProvider": "imagebb",
    "storageKey": "<imagebb id>",
    "publicUrl": "https://i.ibb.co/.../image.jpg",
    "mimeType": "image/jpeg",
    "sizeBytes": 48231,
    "deliveryUrl": null
  }
}
```

Errors follow the Creanger server error contract (`{ success:false, error:{ code, message } }`):
`401 MISSING_TOKEN`, `413/415 VALIDATION_ERROR`, `502 UPSTREAM_ERROR`,
`502 UPSTREAM_MALFORMED`, `502 UPSTREAM_INVALID_URL`, `502 UPSTREAM_UNREACHABLE`,
`504 UPSTREAM_TIMEOUT`, `500 INTERNAL_ERROR`.

## Tests

```bash
deno test supabase/functions/upload-image/
```

Coverage: ImageBB success/malformed/failure/timeout/blank-url/invalid-url,
authentication failure, size/type validation, missing provider key, no-success-
on-failure, and JWT-leak isolation (the client Authorization header is never
forwarded to ImageBB).

## Provider neutrality

`imagebb.ts` is the only provider-specific module. A future video/audio provider
(Cloudinary, etc.) implements the same `UploadedMedia` contract and this handler's
request/response shape stays unchanged.
