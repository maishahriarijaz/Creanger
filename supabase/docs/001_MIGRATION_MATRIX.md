# Creanger Migration Matrix: Telegram → Supabase/PostgreSQL

## Overview

This document maps the existing Telegram data models to the new Creanger PostgreSQL schema,
providing a clear migration strategy for each component.

## Core Principle

**DO NOT** clone Telegram's schema. **DO** create Creanger-native models that support the same
user-facing features but with a clean, normalized PostgreSQL design.

## Migration Status Legend

| Symbol | Meaning |
|--------|---------|
| ✅ | Direct 1:1 mapping exists |
| ⚠️ | Partial mapping - requires transformation |
| ❌ | No mapping - feature removed or reimplemented |
| 🔄 | New implementation (no Telegram equivalent) |

---

## USER / IDENTITY SYSTEM

### Telegram → Creanger Mapping

| Telegram Concept | Creanger Table | Status | Notes |
|------------------|---------------|--------|-------|
| `TLRPC.User` | `users` + `profiles` | ⚠️ | Split into core identity + public profile |
| `users` (SQLite) | `users` | ✅ | Canonical account record |
| `users_data` (SQLite) | `profiles` | ✅ | About field → bio |
| `TLRPC.User.first_name` | `profiles.first_name` | ✅ | Direct mapping |
| `TLRPC.User.last_name` | `profiles.last_name` | ✅ | Direct mapping |
| `TLRPC.User.username` | `profiles.username` | ✅ | Direct mapping (with format validation) |
| `TLRPC.User.photo` | `profiles.avatar_media_id` | ⚠️ | Now references media table |
| `TLRPC.User.phone` | Removed | ❌ | Phone not used in Creanger (email/Google auth) |
| `TLRPC.User.status` | `user_presence` | ⚠️ | Now separate presence table |
| `TLRPC.User.is_premium` | `premium_plans` | 🔄 | Optional - implement if needed |
| `TLRPC.User.flags` | Various | ❌ | Telegram-specific flags removed |
| `clientUserId` | `users.id` | ✅ | UUID replaces Telegram's long ID |

### New Creanger Identity Features (No Telegram Equivalent)

| Feature | Creanger Table | Status |
|---------|---------------|--------|
| Email authentication | `user_identities` | 🔄 |
| Google authentication | `user_identities` | 🔄 |
| OTP verification | `email_verifications` | 🔄 |
| Password credentials | `password_credentials` | 🔄 |
| Multi-device support | `devices` + `sessions` | 🔄 |
| Refresh token rotation | `refresh_tokens` | 🔄 |

---

## CONTACTS (REMOVED)

Contacts / phonebook sync / phonebook-driven auto-block are **not part of Creanger's
product scope**. There is no `contacts` table and no phonebook-sync pipeline.

### Telegram → Creanger Mapping

| Telegram Concept | Creanger Table | Status | Notes |
|------------------|---------------|--------|-------|
| `TLRPC.TL_contact` | Removed | ❌ | Contacts feature not part of Creanger |
| `contacts` (SQLite) | Removed | ❌ | Mutual contact tracking not implemented |
| `user_contacts_v7` (SQLite) | Removed | ❌ | Phone book contacts not implemented |
| `user_phones_v7` (SQLite) | Removed | ❌ | Phone not used |
| `TLRPC.User.phone` matching | Removed | ❌ | Phone-based matching removed |
| Mutual contacts | Removed | ❌ | No contacts table |

### Telegram-Specific Features Removed

| Feature | Status | Reason |
|---------|--------|--------|
| Contacts / contact list | ❌ | Out of Creanger product scope |
| Phonebook sync | ❌ | Device contacts never stored server-side |
| Phonebook-driven auto-block | ❌ | Depends on phonebook sync (removed) |
| Phone number matching | ❌ | No phone auth in Creanger |
| Contact import by phone | ❌ | Not applicable |
| Contact invite by phone | ❌ | Not applicable |

### Downstream effects of contacts removal

| Area | Before | After |
|------|--------|-------|
| `privacy_visibility` enum | `everyone` / `contacts` / `nobody` | `everyone` / `nobody` |
| `privacy_groups` enum | `everyone` / `contacts` / `nobody` | `everyone` / `nobody` |
| `privacy_settings` visibility defaults | `'contacts'` | `'everyone'` (product decision - confirm) |
| `story_privacy` enum | included `contacts` tier | `contacts` tier removed; RLS no longer queries a contacts table |
| `message_type` enum | included `contact` (shared-contact messages) | `contact` removed |
| `notification_type` enum | included `contact_request` | `contact_request` removed |
| `system_config` | seeded `contact.max_count` | row removed |

---

## CHAT MODEL

### Telegram → Creanger Mapping

| Telegram Concept | Creanger Table | Status | Notes |
|------------------|---------------|--------|-------|
| `TLRPC.TL_chat` | `chats` | ✅ | Base chat entity |
| `TLRPC.TL_chatForbidden` | Removed | ❌ | Handled at application level |
| `TLRPC.TL_channel` | `chats` (type='channel') | ✅ | Type-based differentiation |
| `TLRPC.TL_channelForbidden` | Removed | ❌ | Handled at application level |
| `TLRPC.TL_chatEmpty` | Removed | ❌ | Not needed |
| `chats` (SQLite) | `chats` | ✅ | Direct mapping |
| `dialogs` (SQLite) | `chats` + `chat_members` | ⚠️ | Split into membership model |
| `TLRPC.Dialog` | `chats` + `chat_members` | ⚠️ | Dialog = chat + membership |
| `TLRPC.TL_dialogFolder` | Removed | ❌ | Chat Folders not part of Creanger product scope |
| `TLRPC.TL_folder` | Removed | ❌ | Chat Folders not part of Creanger product scope |

### Direct Chats

| Telegram Concept | Creanger Table | Status | Notes |
|------------------|---------------|--------|-------|
| 1:1 dialog | `direct_chats` | ✅ | Guaranteed canonical direct chat |
| `did` (dialog ID, positive) | `direct_chats.chat_id` | ⚠️ | Long → UUID conversion |

### Groups

| Telegram Concept | Creanger Table | Status | Notes |
|------------------|---------------|--------|-------|
| `TLRPC.TL_chat` (group) | `chats` (type='group') | ✅ | Type-based |
| `chat_settings_v2` (SQLite) | `chats` + `chat_members` | ⚠️ | Split across tables |
| `channel_users_v2` (SQLite) | `chat_members` | ✅ | Member tracking |
| `chat_members` | `chat_members` | ✅ | Direct mapping |
| Member roles | `chat_members.role` | ✅ | member/admin/owner |
| Group permissions | `chat_permissions` | 🔄 | New structured permissions |

### Channels

| Telegram Concept | Creanger Table | Status | Notes |
|------------------|---------------|--------|-------|
| `TLRPC.TL_channel` | `chats` (type='channel') | ✅ | Type-based |
| Channel username | `chats.username` | ✅ | Public username |
| Channel description | `chats.description` | ✅ | Direct mapping |
| Channel owner | `chats.owner_id` | ✅ | Direct mapping |
| Channel verified | `chats.is_verified` | ✅ | Direct mapping |
| `channel_admins_v3` (SQLite) | `chat_members` (role='admin') | ✅ | Unified member model |

### Telegram-Specific Chat Features Removed

| Feature | Status | Reason |
|---------|--------|--------|
| Megagroups (legacy) | ❌ | Not applicable |
| Broadcast groups | ❌ | Not applicable |
| Gigagroups | ❌ | Not applicable |
| Admin logs | ❌ | Not applicable |
| Slow mode | ❌ | Optional - add to chat_permissions if needed |
| Chat links | ❌ | Optional - add if needed |
| Chat invite links | ❌ | Optional - add if needed |

---

## MESSAGES

### Telegram → Creanger Mapping

| Telegram Concept | Creanger Table | Status | Notes |
|------------------|---------------|--------|-------|
| `TLRPC.Message` | `messages` | ✅ | Core message entity |
| `messages_v2` (SQLite) | `messages` | ✅ | Direct mapping |
| `messages_seq` (SQLite) | Removed | ❌ | MTProto-specific |
| `messages_holes` (SQLite) | Removed | ❌ | MTProto-specific |
| `TLRPC.Message.message` | `messages.content` | ✅ | Text content |
| `TLRPC.Message.date` | `messages.created_at` | ✅ | Timestamp |
| `TLRPC.Message.from_id` | `messages.sender_id` | ✅ | Sender reference |
| `TLRPC.Message.peer_id` | `messages.chat_id` | ✅ | Chat reference |
| `TLRPC.Message.reply_to` | `messages.reply_to_message_id` | ✅ | Reply reference |
| `TLRPC.Message.edit_date` | `messages.edited_at` | ✅ | Edit timestamp |
| `TLRPC.Message.ttl_period` | Removed | ❌ | Ephemeral messages not supported |
| `TLRPC.Message.grouped_id` | `messages.metadata` | ⚠️ | Stored in JSONB |
| `TLRPC.Message.views` | Removed | ❌ | Not applicable |
| `TLRPC.Message.forwards` | Removed | ❌ | Not applicable |
| `TLRPC.Message.replies` | `message_replies` | ✅ | Advanced reply tracking |

### Message Types

| Telegram Type | Creanger Type | Status | Notes |
|--------------|---------------|--------|-------|
| `TL_message` | `message_type = 'text'` | ✅ | Text messages |
| `TL_messageMediaPhoto` | `message_type = 'image'` | ✅ | Image messages |
| `TL_messageMediaDocument` | `message_type = 'document'` | ✅ | Document files |
| `TL_messageMediaGeo` | `message_type = 'location'` | ✅ | Location sharing |
| `TL_messageMediaContact` | Removed | ❌ | Shared-contact messages depend on the contacts feature (removed) |
| `TL_messageMediaPoll` | `message_type = 'poll'` | ✅ | Polls |
| `TL_messageMediaWebPage` | Removed | ❌ | Not applicable |
| `TL_messageService` | `message_type = 'system'` | ✅ | System messages |
| `TL_messageMediaInvoice` | Removed | ❌ | Bot payments not supported |
| `TL_messageMediaGame` | Removed | ❌ | Games not supported |
| `TL_messageMediaGeoLive` | Removed | ❌ | Live location optional |

### Scheduled Messages

| Telegram Concept | Creanger Table | Status | Notes |
|------------------|---------------|--------|-------|
| `scheduled_messages_v2` (SQLite) | `messages.scheduled_at` | ✅ | Built into messages table |
| `TLRPC.TL_messageScheduled` | `messages` with `status='scheduled'` | ✅ | Unified model |

### Message Deletions

| Telegram Concept | Creanger Table | Status | Notes |
|------------------|---------------|--------|-------|
| `deleted_at` on message | `messages.deleted_at` | ✅ | Delete for everyone |
| Per-user deletion | `message_deletions` | 🔄 | Delete for me |

### Message Reactions

| Telegram Concept | Creanger Table | Status | Notes |
|------------------|---------------|--------|-------|
| `TLRPC.TL_messageReactions` | `message_reactions` | ✅ | Direct mapping |
| `reactions` (SQLite) | `message_reactions` | ✅ | Reaction tracking |
| `reaction_mentions` (SQLite) | Removed | ❌ | Mentions optional |

### Message Read State

| Telegram Concept | Creanger Table | Status | Notes |
|------------------|---------------|--------|-------|
| `TLRPC.TL_readHistory` | `chat_read_state` | ⚠️ | Chat-level cursor instead of per-message |
| `inbox_max` / `outbox_max` (SQLite) | `chat_read_state.last_read_message_id` | ✅ | Unified read cursor |
| `unread_count` (SQLite) | `chat_read_state.unread_count` | ✅ | Cached unread count |

---

## MEDIA

### Telegram → Creanger Mapping

| Telegram Concept | Creanger Table | Status | Notes |
|------------------|---------------|--------|-------|
| `TLRPC.Photo` | `media` (mime_type like 'image/%') | ⚠️ | Unified media model |
| `TLRPC.Document` | `media` (mime_type like 'video/%', 'audio/%', etc.) | ⚠️ | Unified media model |
| `media_v4` (SQLite) | `media` | ✅ | Media metadata |
| `media_holes_v2` (SQLite) | Removed | ❌ | MTProto-specific |
| `media_counts_v2` (SQLite) | Removed | ❌ | Counts computed dynamically |
| `sent_files_v2` (SQLite) | `media` | ✅ | Uploaded files |
| `TLRPC.PhotoSize` | `media.metadata` | ⚠️ | Size variants in JSONB |
| `TLRPC.FileLocation` | `media.storage_key` + `media.public_url` | ⚠️ | New storage model |
| `TLRPC.TL_fileLocation` | `media.storage_key` | ✅ | Storage reference |
| `TL_inputFile` | `media.id` | ✅ | Media reference |
| `TL_inputDocument` | `media.id` | ✅ | Media reference |
| `TL_photoStrippedSize` | `media.thumbnail_media_id` | ✅ | Thumbnail reference |

### Storage Provider Mapping

| Provider | Telegram Equivalent | Creanger Storage | Notes |
|----------|-------------------|------------------|-------|
| `cloudinary` | N/A | Video/Audio/Large files | Primary for rich media |
| `imagebb` | N/A | Images | Image hosting |
| `s3` | N/A | Fallback/backup | Optional |
| `local` | N/A | Development only | Not for production |

### Telegram-Specific Media Features Removed

| Feature | Status | Reason |
|---------|--------|--------|
| CDN files | ❌ | Telegram CDN-specific |
| file_reference | ❌ | Telegram-specific |
| access_hash | ❌ | Telegram-specific |
| Encrypted files | ❌ | Secret chats removed |
| File parts upload | ❌ | Cloudinary/ImageBB handle this |
| Range downloads | ❌ | HTTP standard |

---

## POLLS

### Telegram → Creanger Mapping

| Telegram Concept | Creanger Table | Status | Notes |
|------------------|---------------|--------|-------|
| `TLRPC.TL_messageMediaPoll` | `messages` + `polls` | ✅ | Attached to message |
| `TLRPC.TL_poll` | `polls` | ✅ | Direct mapping |
| `TLRPC.TL_pollAnswer` | `poll_options` | ✅ | Direct mapping |
| `TLRPC.TL_pollAnswerVoters` | `poll_votes` | ✅ | Direct mapping |
| `polls_v2` (SQLite) | `polls` + `poll_options` + `poll_votes` | ✅ | Split across tables |

---

## STORIES

### Telegram → Creanger Mapping

| Telegram Concept | Creanger Table | Status | Notes |
|------------------|---------------|--------|-------|
| `TL_stories.StoryItem` | `stories` | ✅ | Direct mapping |
| `stories` (SQLite) | `stories` | ✅ | Direct mapping |
| `profile_stories` (SQLite) | `stories` | ✅ | User stories |
| `story_pushes` (SQLite) | `notifications` (type='story') | ⚠️ | Unified notifications |
| `story_drafts` (SQLite) | `drafts` | ⚠️ | Unified drafts |
| Story views | `story_views` | ✅ | Direct mapping |
| Story reactions | `story_reactions` | ✅ | Direct mapping |
| Story privacy | `stories.privacy` | ⚠️ | `contacts` tier removed (contacts feature not in scope); tiers are `everyone` / `close_friends` / `nobody` / `custom` |

### Telegram-Specific Story Features Removed

| Feature | Status | Reason |
|---------|--------|--------|
| Story albums | ❌ | Optional |
| Story links | ❌ | Optional |
| Story mentions | ❌ | Optional |

---

## DRAFTS

### Telegram → Creanger Mapping

| Telegram Concept | Creanger Table | Status | Notes |
|------------------|---------------|--------|-------|
| `TLRPC.DraftMessage` | `drafts` | ✅ | Direct mapping |
| `draftMessages` (SharedPreferences) | `drafts` | ✅ | Now server-synced |
| `draftPreferences` (SharedPreferences) | `drafts` | ✅ | Server-synced |

---

## STICKERS

### Telegram → Creanger Mapping

| Telegram Concept | Creanger Table | Status | Notes |
|------------------|---------------|--------|-------|
| `TLRPC.TL_stickerSet` | `sticker_sets` | ✅ | Direct mapping |
| `stickersets2` (SQLite) | `sticker_sets` | ✅ | Direct mapping |
| `TLRPC.TL_stickerPack` | `stickers` | ✅ | Direct mapping |
| `stickers_v2` (SQLite) | `stickers` | ✅ | Direct mapping |
| `TLRPC.TL_messages_stickerSet` | `sticker_sets` | ✅ | Direct mapping |
| Featured stickers | `sticker_sets.is_official` | ✅ | Direct mapping |
| Sticker keywords | `stickers.keywords` | ✅ | Array field |
| Animated stickers | `stickers.type = 'animated'` | ✅ | Type enum |
| Video stickers | `stickers.type = 'video'` | ✅ | Type enum |

### Telegram-Specific Sticker Features Removed

| Feature | Status | Reason |
|---------|--------|--------|
| Dice stickers | ❌ | Not applicable |
| Animated emoji | ❌ | Not applicable |
| Custom emoji | ❌ | Optional - implement if needed |
| Sticker search | ❌ | Optional |
| Recent stickers | ❌ | Not applicable |

---

## NOTIFICATIONS

### Telegram → Creanger Mapping

| Telegram Concept | Creanger Table | Status | Notes |
|------------------|---------------|--------|-------|
| `TLRPC.TL_updateNewMessage` | `messages` (realtime) | ✅ | Realtime updates |
| `TLRPC.TL_updateNewChannelMessage` | `messages` (realtime) | ✅ | Realtime updates |
| `unread_push_messages` (SQLite) | `notifications` | ✅ | Unified notifications |
| `NotificationsController` | `notifications` | ✅ | Application-level |
| `GcmPushListenerService` | `push_tokens` | ✅ | Token management |
| FCM token | `push_tokens.token` | ✅ | Direct mapping |
| Push registration | `push_tokens` | ✅ | Direct mapping |

---

## PRESENCE

### Telegram → Creanger Mapping

| Telegram Concept | Creanger Table | Status | Notes |
|------------------|---------------|--------|-------|
| `TLRPC.TL_userStatusOnline` | `user_presence.status = 'online'` | ✅ | Direct mapping |
| `TLRPC.TL_userStatusOffline` | `user_presence.status = 'offline'` | ✅ | Direct mapping |
| `TLRPC.TL_userStatusRecently` | `user_presence.status = 'away'` | ⚠️ | Approximate |
| `user.status` (SQLite) | `user_presence` | ✅ | Direct mapping |
| Last seen timestamp | `user_presence.last_seen_at` | ✅ | Direct mapping |
| Typing indicators | Realtime broadcast | 🔄 | Ephemeral (not in DB) |

---

## CALLS (REMOVED)

Calls / VoIP / call signaling are **not part of Creanger's product scope**.
The `calls` and `call_participants` tables (along with their enums, indexes,
triggers, and RLS policies) were created in an early draft of this schema and
are dropped by `014_remove_removed_features.sql`. `009_calls.sql` is now an
intentionally empty placeholder migration that documents this.

### Telegram → Creanger Mapping

| Telegram Concept | Creanger Table | Status | Notes |
|------------------|---------------|--------|-------|
| `TLRPC.TL_phoneCall` | Removed | ❌ | Calls/VoIP not part of Creanger |
| `TLRPC.TL_phoneCallRequested` | Removed | ❌ | Calls/VoIP not part of Creanger |
| `TLRPC.TL_phoneCallWaiting` | Removed | ❌ | Calls/VoIP not part of Creanger |
| `TLRPC.TL_phoneCallDiscarded` | Removed | ❌ | Calls/VoIP not part of Creanger |
| `VoIPService` | Removed | ❌ | No call signaling layer |
| Call duration / call type | Removed | ❌ | No call metadata stored |

### Downstream effects of calls removal

| Area | Before | After |
|------|--------|-------|
| `calls`, `call_participants` tables | Created in draft schema | Dropped in `014_remove_removed_features.sql` |
| `call_type`, `call_status`, `call_participant_role`, `call_participant_status` enums | Created in draft schema | Dropped in `014_remove_removed_features.sql` |
| `moderation_action_type` enum | included `restrict_calls` | `restrict_calls` removed (no calls feature to restrict) |
| `privacy_settings` | never had a `calls_privacy` column in the actual schema | N/A - see PRIVACY section below (previous doc revision incorrectly referenced a non-existent column) |

---

## PRIVACY

### Telegram → Creanger Mapping

| Telegram Concept | Creanger Table | Status | Notes |
|------------------|---------------|--------|-------|
| `TL_account.privacyRules` | `privacy_settings` | ✅ | Direct mapping |
| Last seen privacy | `privacy_settings.last_seen_visibility` | ✅ | Direct mapping (`everyone`/`nobody`) |
| Profile photo privacy | `privacy_settings.profile_photo_visibility` | ✅ | Direct mapping (`everyone`/`nobody`) |
| Phone visibility | `privacy_settings.phone_visibility` | ✅ | Direct mapping (`everyone`/`nobody`) |
| Bio visibility | `privacy_settings.bio_visibility` | ✅ | Direct mapping (`everyone`/`nobody`) |
| Email visibility | `privacy_settings.email_visibility` | ✅ | Direct mapping (`everyone`/`nobody`) |
| Groups privacy (who can add me) | `privacy_settings.groups_privacy` | ✅ | Direct mapping (`everyone`/`nobody`) |
| Forwards privacy | `privacy_settings.forwards_privacy` | ✅ | Direct mapping (`everyone`/`nobody`) |
| Voice messages privacy | `privacy_settings.voice_messages_privacy` | ✅ | Direct mapping (`everyone`/`nobody`) |
| Calls privacy | Removed | ❌ | Calls/VoIP not part of Creanger product scope |

**Note:** All `privacy_visibility` / `privacy_groups` fields previously had a
`contacts` tier (e.g. "visible to contacts only"). Since the contacts feature
is not part of Creanger's product scope, that tier has been removed; only
`everyone` and `nobody` remain. Defaults were changed from `'contacts'` to
`'everyone'` - this is a product decision that should be confirmed by the
product team, since it changes the out-of-the-box privacy posture (more
visible by default than the old "contacts-only" default).

### Telegram-Specific Privacy Features Removed

| Feature | Status | Reason |
|---------|--------|--------|
| Calls privacy | ❌ | Calls/VoIP not part of Creanger product scope |
| Contacts-only visibility tier | ❌ | Contacts feature not part of Creanger product scope |
| Music privacy | ❌ | Optional - not implemented |
| Gifts privacy | ❌ | Stars/Gifts not part of Creanger product scope |
| No paid messages privacy | ❌ | Stars/Payments not part of Creanger product scope |

---

## BLOCKING

### Telegram → Creanger Mapping

| Telegram Concept | Creanger Table | Status | Notes |
|------------------|---------------|--------|-------|
| `contacts` (SQLite, mutual=0) | `blocked_users` | ⚠️ | Telegram derived blocking from the contacts table; Creanger's `blocked_users` is a standalone table with no contacts dependency |
| Block list | `blocked_users` | ✅ | Direct mapping |
| Block status | `blocked_users` | ✅ | Direct mapping |

---

## REPORTING / SAFETY

### Telegram → Creanger Mapping

| Telegram Concept | Creanger Table | Status | Notes |
|------------------|---------------|--------|-------|
| `TL_account.reportPeer` | `user_reports` | ✅ | Direct mapping |
| `TL_messages.report` | `message_reports` | ✅ | Direct mapping |
| Report reasons | `user_reports.reason` | ✅ | Enum mapping |
| Report status | `user_reports.status` | ✅ | Direct mapping |

---

## SEARCH

### Telegram → Creanger Mapping

| Telegram Concept | Creanger Table | Status | Notes |
|------------------|---------------|--------|-------|
| `TL_messages.search` | Full-text search (PostgreSQL) | 🔄 | FTS index on messages.content |
| `search_recent` (SQLite) | Removed | ❌ | Client-side feature |
| `TLRPC.TL_messages_searchResults` | PostgreSQL FTS query | 🔄 | Server-side search |
| Hashtag search | PostgreSQL FTS | 🔄 | Optional |

---

## ADMIN / MODERATION

### Telegram → Creanger Mapping

| Telegram Concept | Creanger Table | Status | Notes |
|------------------|---------------|--------|-------|
| `channel_admins_v3` (SQLite) | `chat_members` (role='admin') | ✅ | Unified model |
| Admin rights | `admin_users` + `admin_permissions` | 🔄 | New model |
| Admin actions log | `admin_actions` | 🔄 | New model |
| Moderation actions | `moderation_actions` | 🔄 | New model |
| Audit logs | `audit_logs` | 🔄 | New model |

---

## PREMIUM (Admin-Request / Approval Based)

Creanger does **not** implement Telegram's payment-driven Premium/Stars model.
Instead, Premium is granted via an admin-request/approval workflow: a user
requests specific Premium features, an admin reviews and approves/rejects the
request, and approved features become active entitlements (optionally with an
expiry). This is implemented in `014_remove_removed_features.sql`.

### Telegram → Creanger Mapping

| Telegram Concept | Creanger Table | Status | Notes |
|------------------|---------------|--------|-------|
| `TL_help_premiumPromo` | `premium_plans` | ⚠️ | Re-implemented as an admin-defined plan (feature bundle), not a purchasable promo |
| `premium_promo` (SQLite) | `premium_plans` | ⚠️ | Same as above |
| Premium purchase / subscription flow | `premium_requests` | 🔄 | User submits a request instead of paying; admin approves/rejects |
| Premium feature grant | `premium_entitlements` | 🔄 | One row per user per active feature, optionally time-limited |
| Stars / in-app payments | Removed | ❌ | No monetization/payment rails in Creanger |
| Gifts | Removed | ❌ | Stars/Gifts not part of Creanger product scope |

### Creanger Premium Tables

| Table | Purpose |
|-------|---------|
| `premium_requests` | User-submitted requests for Premium features; tracks status (`pending`/`under_review`/`approved`/`rejected`/`revoked`/`expired`) and the reviewing admin |
| `premium_entitlements` | Active (or expired) feature grants per user, one row per `(user_id, feature)` |
| `premium_plans` | Admin-defined bundles of `premium_feature` values that can be requested |

**Status:** IMPLEMENTED - admin-request/approval based, no payment processing.

---

## FEATURE FLAGS

### Telegram → Creanger Mapping

| Telegram Concept | Creanger Table | Status | Notes |
|------------------|---------------|--------|-------|
| Feature flags | `feature_flags` | 🔄 | New model |
| App config | `system_config` | 🔄 | New model |

---

## SYSTEM CONFIGURATION

### Telegram → Creanger Mapping

| Telegram Concept | Creanger Table | Status | Notes |
|------------------|---------------|--------|-------|
| `app_config` (SQLite) | `system_config` | ✅ | Direct mapping |
| `keyvalue` (SQLite) | `system_config` | ✅ | Key-value config |
| Wallpapers | Removed | ❌ | Optional |
| Emoji keywords | Removed | ❌ | Client-side |
| Web recent | Removed | ❌ | Client-side |

---

## REMOVED TELEGRAM-SPECIFIC TABLES

The following Telegram-specific tables are **NOT** migrated to Creanger:

| Telegram Table | Reason |
|----------------|--------|
| `contacts` | Contacts (removed - not part of Creanger product scope) |
| `user_contacts_v7` | Contacts (removed - not part of Creanger product scope) |
| `user_phones_v7` | Contacts/phone (removed - not part of Creanger product scope) |
| `phone_calls` | Calls/VoIP (removed - not part of Creanger product scope) |
| `messages_holes` | MTProto-specific |
| `media_holes_v2` | MTProto-specific |
| `messages_seq` | MTProto-specific |
| `params` (PTS/QTS) | MTProto-specific |
| `randoms_v2` | MTProto-specific |
| `enc_tasks_v4` | Secret chats (removed) |
| `enc_chats` | Secret chats (removed) |
| `bot_keyboard` | Bots (removed) |
| `bot_keyboard_topics` | Bots (removed) |
| `botcache` | Bots (removed) |
| `bot_info_v2` | Bots (removed) |
| `attach_menu_bots` | Bots (removed) |
| `webpage_pending_v2` | Web pages (optional) |
| `emoji_keywords_v2` | Client-side |
| `emoji_keywords_info_v2` | Client-side |
| `wallpapers2` | Optional |
| `emoji_statuses` | Optional |
| `emoji_groups` | Optional |
| `animated_emoji` | Optional |
| `effects` | Optional |
| `downloading_documents` | Client-side |
| `requested_holes` | MTProto-specific |
| `sharing_locations` | Optional |
| `shortcut_widget` | Optional |
| `unconfirmed_auth` | MTProto-specific |
| `saved_reaction_tags` | Optional |
| `tag_message_id` | Optional |
| `business_replies` | Optional |
| `business_links` | Optional |
| `fact_checks` | Optional |
| `popular_bots` | Bots (removed) |
| `star_gifts2` | Stars/Gifts (removed - not part of Creanger product scope) |
| `gift_themes` | Stars/Gifts (removed - not part of Creanger product scope) |
| `poll_votes_mentions` | Optional |
| `ephemeral_messages` | Secret chats (removed) |
| `messages_holes_topics` | MTProto-specific |
| `media_holes_topics` | MTProto-specific |
| `media_counts_topics` | Computed dynamically |
| `reaction_mentions_topics` | Optional |
| `profile_stories_albums` | Optional |
| `profile_stories_albums_links` | Optional |
| `app_config` | Migrated to system_config |
| `web_browser_settings` | Optional |

---

## MIGRATION ORDER

The migrations must be executed in the following order:

1. `001_core_identity.sql` - Users, profiles, identities, sessions
2. `002_privacy_blocking_reporting.sql` - Privacy, blocking, reports (no contacts table - see CONTACTS section)
3. `003_chat_model.sql` - Chats, direct chats, members, permissions, topics
4. `004_messages.sql` - Messages, reactions, read state, deliveries
5. `005_media_polls.sql` - Media, attachments, polls
6. `006_drafts_stickers.sql` - Drafts, sticker sets, stickers
7. `007_stories.sql` - Stories, views, reactions
8. `008_notifications.sql` - Push tokens, notifications, presence
9. `009_calls.sql` - Intentionally empty; calls were removed (see CALLS section)
10. `010_admin_audit.sql` - Admin, audit, moderation, feature flags
11. `011_saved_messages.sql` - Saved messages
12. `012_realtime.sql` - Realtime configuration
13. `013_indexes_and_triggers.sql` - Performance indexes and triggers
14. `014_remove_removed_features.sql` - Drops calls/call_participants; adds admin-request Premium tables

---

## ROLLBACK STRATEGY

To rollback a migration:

1. Use `DROP TABLE IF EXISTS table_name CASCADE;` for each table in reverse order
2. Drop enum types with `DROP TYPE IF EXISTS type_name;`
3. Drop functions with `DROP FUNCTION IF EXISTS function_name;`
4. Ensure all dependent tables are dropped before parent tables

---

## VALIDATION QUERIES

After migration, run these validation queries to verify data integrity:

```sql
-- Verify all tables exist
SELECT table_name 
FROM information_schema.tables 
WHERE table_schema = 'public' 
ORDER BY table_name;

-- Verify RLS is enabled on all user-facing tables
SELECT tablename, rowsecurity 
FROM pg_tables 
WHERE schemaname = 'public' 
  AND rowsecurity = TRUE;

-- Verify indexes exist
SELECT indexname, tablename 
FROM pg_indexes 
WHERE schemaname = 'public' 
ORDER BY tablename, indexname;

-- Verify foreign keys
SELECT
    tc.table_name, 
    kcu.column_name, 
    ccu.table_name AS foreign_table_name,
    ccu.column_name AS foreign_column_name 
FROM information_schema.table_constraints AS tc 
JOIN information_schema.key_column_usage AS kcu
    ON tc.constraint_name = kcu.constraint_name
JOIN information_schema.constraint_column_usage AS ccu
    ON ccu.constraint_name = tc.constraint_name
WHERE tc.constraint_type = 'FOREIGN KEY'
ORDER BY tc.table_name;
```

---

## NOTES

1. **No Telegram protocol tables** - All MTProto/TLRPC-specific tables are removed
2. **No bot tables** - Bots/WebApps not part of Creanger product scope
3. **No secret chats** - End-to-end encrypted chats not part of Creanger product scope
4. **No phone authentication** - Email/Google auth only
5. **No Stars/Payments/Gifts** - Not part of Creanger product scope (no monetization); Premium is admin-request/approval based instead (see PREMIUM section)
6. **No contacts / phonebook sync / phonebook auto-block** - Not part of Creanger product scope (see CONTACTS section)
7. **No calls / VoIP** - Not part of Creanger product scope (see CALLS section)
8. **No Chat Folders** - Not part of Creanger product scope
9. **Media storage** - External providers (Cloudinary/ImageBB) for actual files
10. **Realtime** - Supabase Realtime for live updates, PostgreSQL for persistence
11. **Soft deletes** - Used where recovery/audit is important
12. **UUID primary keys** - All tables use UUID instead of Telegram's long IDs
13. **Row Level Security** - All user-facing tables have explicit RLS policies