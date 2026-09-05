# Creanger Supabase Schema

## Overview

This directory contains the complete PostgreSQL/Supabase schema for the Creanger messaging application,
designed to replace Telegram's MTProto/TLRPC/server-side data architecture.

## Architecture Principles

1. **PostgreSQL is the SERVER SOURCE OF TRUTH** - All authoritative data lives in PostgreSQL
2. **SQLite is ONLY the Android local cache** - Offline state and fast UI rendering only
3. **Supabase Realtime for live updates** - Not a replacement for PostgreSQL storage
4. **Cloudinary for video/audio/large media** - External media storage
5. **ImageBB for images** - External image hosting
6. **UUIDs for primary keys** - Consistent, non-sequential identifiers
7. **Row Level Security (RLS)** - All user-facing tables have explicit RLS policies
8. **No Telegram protocol tables** - MTProto/TLRPC/PTS/QTS tables are NOT included

## Directory Structure

```
supabase/
├── README.md                          # This file
├── migrations/                        # SQL migration files (run in order)
│   ├── 001_core_identity.sql          # Users, profiles, identities, sessions
│   ├── 002_privacy_blocking_reporting.sql  # Privacy, blocking, reports (no contacts - removed)
│   ├── 003_chat_model.sql             # Chats, members, permissions, topics
│   ├── 004_messages.sql               # Messages, reactions, read state
│   ├── 005_media_polls.sql            # Media, attachments, polls
│   ├── 006_drafts_stickers.sql        # Drafts, sticker sets, stickers
│   ├── 007_stories.sql                # Stories, views, reactions
│   ├── 008_notifications.sql          # Push tokens, notifications, presence
│   ├── 009_calls.sql                  # Intentionally empty - calls removed (see 014)
│   ├── 010_admin_audit.sql            # Admin, audit, moderation, feature flags
│   ├── 011_saved_messages.sql         # Saved messages
│   ├── 012_realtime.sql               # Realtime configuration
│   ├── 013_indexes_and_triggers.sql   # Performance indexes and triggers
│   ├── 014_remove_removed_features.sql # Drops calls tables; adds admin-request Premium
│   ├── 029_message_media_send.sql     # send_media_message RPC (media metadata, atomic)
│   └── 030_media_upload_security.sql  # ImageBB integrity + provider architecture
├── functions/                         # DEPRECATED Edge Functions (kept for reference only)
│   ├── upload-image/                  # Old ImageBB proxy (never deployed; do not deploy)
│   └── upload-video/                  # Old Cloudinary proxy (never deployed; do not deploy)
├── docs/                              # Documentation
│   ├── 001_MIGRATION_MATRIX.md        # Telegram → Creanger mapping
│   ├── 002_ERD.md                     # Entity Relationship Diagram
│   ├── 003_VALIDATION.md              # Validation queries
│   └── 004_DATAPLANE_E2E_PROOF.md     # Live PostgREST + client E2E proof (M1-R2)
└── seed/                              # Seed data (if needed)
```

## Table Summary

| Table | Purpose | Lines | Status |
|-------|---------|-------|--------|
| `users` | Canonical account records | 50 | REQUIRED |
| `profiles` | User-facing profile info | 30 | REQUIRED |
| `user_identities` | Authentication identities | 40 | REQUIRED |
| `email_verifications` | OTP verification (hashed) | 35 | REQUIRED |
| `password_credentials` | Argon2id password hashes | 15 | REQUIRED |
| `devices` | User device tracking | 35 | REQUIRED |
| `sessions` | Active user sessions | 35 | REQUIRED |
| `refresh_tokens` | Hashed refresh tokens | 40 | REQUIRED |
| `privacy_settings` | Privacy controls | 35 | REQUIRED |
| `blocked_users` | Blocking relationships | 15 | REQUIRED |
| `user_reports` | User abuse reports | 30 | REQUIRED |
| `message_reports` | Message abuse reports | 30 | REQUIRED |
| `user_restrictions` | Moderation restrictions | 35 | REQUIRED |
| `chats` | Root conversation entities | 35 | REQUIRED |
| `direct_chats` | Canonical 1:1 conversations | 20 | REQUIRED |
| `chat_members` | Chat membership records | 35 | REQUIRED |
| `chat_permissions` | Per-chat permissions | 30 | REQUIRED |
| `topics` | Forum topics (optional) | 30 | REQUIRED |
| `messages` | Core message entity | 60 | REQUIRED |
| `message_replies` | Advanced reply tracking | 25 | REQUIRED |
| `message_edits` | Message edit history | 20 | REQUIRED |
| `message_deletions` | Per-user deletions | 25 | REQUIRED |
| `message_reactions` | Message reactions | 30 | REQUIRED |
| `chat_read_state` | Chat-level read cursors | 30 | REQUIRED |
| `message_deliveries` | Multi-device delivery | 25 | REQUIRED |
| `message_forwards` | Forward tracking | 25 | REQUIRED |
| `media` | Media metadata (external files) | 40 | REQUIRED |
| `message_attachments` | Message-media links | 25 | REQUIRED |
| `polls` | Poll metadata | 35 | REQUIRED |
| `poll_options` | Poll choices | 20 | REQUIRED |
| `poll_votes` | Poll votes | 20 | REQUIRED |
| `drafts` | Message drafts | 25 | REQUIRED |
| `sticker_sets` | Sticker pack metadata | 35 | OPTIONAL |
| `stickers` | Individual stickers | 25 | OPTIONAL |
| `user_sticker_sets` | User installed sets | 20 | OPTIONAL |
| `stories` | User stories | 30 | OPTIONAL |
| `story_views` | Story view tracking | 20 | OPTIONAL |
| `story_reactions` | Story reactions | 20 | OPTIONAL |
| `story_custom_audience` | Custom story audience | 15 | OPTIONAL |
| `push_tokens` | Device push tokens | 25 | REQUIRED |
| `notifications` | User notifications | 30 | REQUIRED |
| `user_presence` | Lightweight presence | 15 | REQUIRED |
| `saved_messages` | Saved/bookmarked messages | 25 | REQUIRED |
| `admin_users` | Admin accounts | 25 | REQUIRED |
| `admin_permissions` | Admin role permissions | 20 | REQUIRED |
| `admin_actions` | Admin action audit trail | 25 | REQUIRED |
| `audit_logs` | Security event logs | 30 | REQUIRED |
| `moderation_actions` | Content/user moderation | 30 | REQUIRED |
| `feature_flags` | Feature rollout flags | 25 | REQUIRED |
| `system_config` | System configuration | 20 | REQUIRED |
| `premium_requests` | Admin-request Premium requests | 25 | REQUIRED |
| `premium_entitlements` | Granted Premium feature entitlements | 20 | REQUIRED |
| `premium_plans` | Admin-defined Premium feature bundles | 20 | REQUIRED |
| **TOTAL** | **53 tables** | **~1,500** | |

**Removed from product scope (not present in this schema):** `contacts`,
`calls`, `call_participants`. See `docs/001_MIGRATION_MATRIX.md` for details.
No tables exist for phonebook sync, phonebook auto-block, bots/WebApps,
secret chats, Chat Folders, or Stars/Payments/Gifts either - those features
never had dedicated tables in this schema.

## Migration Order

Run migrations in numeric order:

```bash
psql -h your-db-host -U postgres -d your-db -f migrations/001_core_identity.sql
psql -h your-db-host -U postgres -d your-db -f migrations/002_privacy_blocking_reporting.sql
# ... continue for all files
```

Or use Supabase CLI:

```bash
supabase migration up
```

## Soft Delete Strategy

| Table | Strategy | Recovery |
|-------|----------|----------|
| `users` | `deleted_at` | Admin can restore |
| `chats` | `deleted_at` | Owner can restore within 30 days |
| `messages` | `deleted_at` | Not recoverable (privacy) |
| `media` | `deleted_at` | Not recoverable (storage cost) |
| `stories` | `expires_at` + `deleted_at` | Auto-expire, manual delete |
| `sessions` | `revoked_at` | Cannot restore |
| `devices` | `revoked_at` | User can re-register |
| `user_restrictions` | `expires_at` + `is_active` | Auto-expire |

## Data Retention Policy

| Data Type | Retention | Action |
|-----------|-----------|--------|
| Expired email verifications | 24 hours | Auto-delete |
| Expired OTP codes | 24 hours | Auto-delete |
| Stories | 24 hours (configurable) | Auto-expire |
| Expired sessions | 7 days | Auto-delete |
| Expired user restrictions | Until expired | Auto-deactivate |
| Audit logs | 90 days | Archive/delete |
| Admin actions | 1 year | Archive |
| Deleted users | 30 days | Hard delete after |
| Deleted chats | 30 days | Hard delete after |
| Refresh tokens | 7 days after expiry | Auto-delete |
| Push tokens | Until revoked | Keep |

## Security Notes

1. **No plaintext secrets** - Passwords, OTPs, tokens are all hashed (Argon2id)
2. **Row Level Security** - All user-facing tables have explicit RLS policies
3. **Service role for sensitive operations** - Password/refresh token access via service role only
4. **IP hashing** - IPs stored as SHA-256 hashes, not raw
5. **Audit logging** - All security-sensitive operations are logged
6. **No Telegram protocol data** - No MTProto/TLRPC/PTS/QTS persistence

## Key Differences from Telegram

| Feature | Telegram | Creanger |
|---------|----------|----------|
| Primary keys | Long integers | UUIDs |
| Protocol | MTProto binary | REST/WebSocket (JSON) |
| Auth | Phone number | Email/Google |
| Secret chats | Native E2E | Not supported |
| Bots | Supported | Not supported |
| Stars/Gifts/Payments | Supported | Not supported |
| Chat Folders | Supported | Not supported |
| Contacts / phonebook | Supported | Not supported |
| Calls/VoIP | Supported | Not supported |
| Channels | Full support | Basic support |
| Groups | Full support | Full support |
| Media storage | Telegram CDN | Cloudinary/ImageBB |
| Local DB | SQLite (authoritative) | SQLite (cache only) |
| Real-time | MTProto updates | Supabase Realtime |
| Search | Server-side | PostgreSQL FTS |
| Multi-account | Native | Supported |

## Migration from Telegram Client

The Android client migration should follow this pattern:

1. **Keep Telegram infrastructure** - Don't delete TLRPC, MessagesController, etc.
2. **Create Creanger data layer** - New Repository pattern alongside legacy
3. **Gradual migration** - Move features one by one from legacy to new
4. **UI layer unchanged** - Existing UI works with new data layer
5. **Remove legacy** - Only after all features migrated

```
UI Layer (unchanged)
    ↓
Repository (new)
    ├── CreangerDataSource (new - Supabase)
    └── LegacyTelegramDataSource (old - MTProto)
```

## Image Upload Architecture

Images (and video) are uploaded through the authenticated Creanger Media API of
the Creanger backend (`creanger-auth-milestone3`) — `POST /v1/media/upload-image`
and `POST /v1/media/upload-video`. The backend verifies the Custom Creanger JWT
(`authMiddleware`), validates size/type, uploads to ImageBB/Cloudinary
server-side (credentials are backend secrets; they never appear in the Android
APK) and returns a PROVIDER-NEUTRAL `UploadedMedia` result. Supabase handles
ONLY PostgreSQL + Realtime: the Android client calls the backend's Media API,
then persists the returned metadata via the existing `send_media_message` RPC.

The legacy `functions/upload-image` and `functions/upload-video` Edge Functions
are DEPRECATED (never deployed on the live project; return 404) and must NOT be
deployed. They are kept only as the historical source the backend's
`src/media/imagebb.ts` / `src/media/cloudinary.ts` adapters were ported from.

## Next Steps

1. Apply migrations in order
2. Configure Supabase Realtime publications
3. Deploy the Creanger backend with `IMAGEBB_API_KEY` + Cloudinary credentials
4. Implement Android data layer
5. Create migration scripts for existing data (if any)
6. Test all RLS policies
7. Performance testing with realistic data volumes
8. Set up monitoring and alerting

## License

This schema is designed for the Creanger application. Adapt as needed for your specific requirements.