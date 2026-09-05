# Creanger Database Validation Queries

## Pre-Migration Validation

Run these queries BEFORE applying migrations to verify the database is ready.

```sql
-- Check PostgreSQL version (requires 13+)
SELECT version();

-- Check required extensions
SELECT extname, extversion 
FROM pg_extension 
WHERE extname IN ('uuid-ossp', 'pgcrypto', 'citext');

-- Check if tables already exist (should be empty)
SELECT table_name 
FROM information_schema.tables 
WHERE table_schema = 'public' 
ORDER BY table_name;

-- Check available disk space
SELECT pg_size_pretty(pg_database_size(current_database()));
```

## Post-Migration Validation

Run these queries AFTER applying all migrations to verify data integrity.

### 1. Table Existence Check

```sql
-- Verify all expected tables exist
-- NOTE: 'contacts', 'calls', and 'call_participants' are intentionally absent -
-- those features are not part of Creanger's product scope. If any of these
-- three appear in your database, that indicates a stale/un-migrated schema.
WITH expected_tables AS (
    SELECT unnest(ARRAY[
        'users', 'profiles', 'user_identities', 'email_verifications',
        'password_credentials', 'devices', 'sessions', 'refresh_tokens',
        'privacy_settings', 'blocked_users',
        'user_reports', 'message_reports', 'user_restrictions',
        'chats', 'direct_chats', 'chat_members', 'chat_permissions', 'topics',
        'messages', 'message_replies', 'message_edits', 'message_deletions',
        'message_reactions', 'chat_read_state', 'message_deliveries', 'message_forwards',
        'media', 'message_attachments',
        'polls', 'poll_options', 'poll_votes',
        'drafts', 'sticker_sets', 'stickers', 'user_sticker_sets',
        'stories', 'story_views', 'story_reactions', 'story_custom_audience',
        'push_tokens', 'notifications', 'user_presence',
        'saved_messages',
        'admin_users', 'admin_permissions', 'admin_actions',
        'audit_logs', 'moderation_actions', 'feature_flags', 'system_config',
        'premium_requests', 'premium_entitlements', 'premium_plans'
    ]) AS table_name
)
SELECT 
    e.table_name,
    CASE WHEN t.table_name IS NOT NULL THEN 'EXISTS' ELSE 'MISSING' END AS status
FROM expected_tables e
LEFT JOIN information_schema.tables t 
    ON t.table_name = e.table_name AND t.table_schema = 'public'
ORDER BY e.table_name;
```

### 1b. Removed-Feature Table Check

```sql
-- Verify tables belonging to removed features do NOT exist
SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'public'
  AND table_name IN (
    'contacts', 'calls', 'call_participants',
    'chat_folders', 'chat_folder_members',
    'bots', 'bot_webapps', 'secret_chats',
    'stars_balance', 'star_gifts', 'payments'
  );
-- Expect ZERO rows. Any row returned means a removed-feature table leaked
-- back into the schema (e.g. from a stale branch or manual DDL) and should
-- be dropped.
```

### 2. RLS Policy Check

```sql
-- Verify RLS is enabled on all user-facing tables
SELECT 
    tablename, 
    rowsecurity,
    CASE WHEN rowsecurity THEN 'ENABLED' ELSE 'DISABLED' END AS rls_status
FROM pg_tables 
WHERE schemaname = 'public'
ORDER BY tablename;
```

### 3. Index Verification

```sql
-- Verify critical indexes exist
SELECT 
    indexname, 
    tablename,
    indexdef
FROM pg_indexes 
WHERE schemaname = 'public'
ORDER BY tablename, indexname;
```

### 4. Foreign Key Verification

```sql
-- Verify all foreign keys
SELECT
    tc.table_name, 
    kcu.column_name, 
    ccu.table_name AS foreign_table_name,
    ccu.column_name AS foreign_column_name,
    tc.constraint_name
FROM information_schema.table_constraints AS tc 
JOIN information_schema.key_column_usage AS kcu
    ON tc.constraint_name = kcu.constraint_name
JOIN information_schema.constraint_column_usage AS ccu
    ON ccu.constraint_name = tc.constraint_name
WHERE tc.constraint_type = 'FOREIGN KEY'
ORDER BY tc.table_name, kcu.column_name;
```

### 5. Constraint Verification

```sql
-- Verify unique constraints
SELECT
    tc.table_name,
    tc.constraint_name,
    tc.constraint_type
FROM information_schema.table_constraints tc
WHERE tc.constraint_type IN ('PRIMARY KEY', 'UNIQUE')
  AND tc.table_schema = 'public'
ORDER BY tc.table_name, tc.constraint_type;
```

### 6. Check Constraint Verification

```sql
-- Verify check constraints
SELECT
    tc.table_name,
    tc.constraint_name,
    cc.check_clause
FROM information_schema.table_constraints tc
JOIN information_schema.check_constraints cc
    ON tc.constraint_name = cc.constraint_name
WHERE tc.constraint_type = 'CHECK'
  AND tc.table_schema = 'public'
ORDER BY tc.table_name;
```

### 7. Trigger Verification

```sql
-- Verify triggers exist
SELECT 
    event_object_table AS table_name,
    trigger_name,
    event_manipulation AS event,
    action_timing AS timing
FROM information_schema.triggers
WHERE trigger_schema = 'public'
ORDER BY event_object_table, trigger_name;
```

### 8. Function Verification

```sql
-- Verify custom functions exist
SELECT 
    routine_name,
    routine_type
FROM information_schema.routines
WHERE routine_schema = 'public'
  AND routine_type = 'FUNCTION'
ORDER BY routine_name;
```

### 9. Enum Type Verification

```sql
-- Verify enum types exist
SELECT 
    t.typname AS enum_name,
    string_agg(e.enumlabel, ', ' ORDER BY e.enumsortorder) AS values
FROM pg_type t
JOIN pg_enum e ON t.oid = e.enumtypid
WHERE t.typtype = 'e'
GROUP BY t.typname
ORDER BY t.typname;
```

### 10. Policy Verification

```sql
-- Verify RLS policies exist
SELECT 
    schemaname,
    tablename,
    policyname,
    permissive,
    roles,
    cmd,
    qual,
    with_check
FROM pg_policies
WHERE schemaname = 'public'
ORDER BY tablename, policyname;
```

## Functional Validation Tests

Run these test queries to verify the schema works correctly.

### Test 1: Create User and Profile

```sql
-- Insert test user
INSERT INTO users (status) VALUES ('active') RETURNING id;

-- Insert profile (using the returned user_id)
INSERT INTO profiles (user_id, username, first_name, last_name, bio)
VALUES (
    'user-uuid-here', 
    'testuser', 
    'Test', 
    'User', 
    'Test bio'
);

-- Verify
SELECT u.id, p.username, p.first_name, p.last_name
FROM users u
JOIN profiles p ON p.user_id = u.id
WHERE u.id = 'user-uuid-here';
```

### Test 2: Create Direct Chat

```sql
-- Get or create direct chat between two users
SELECT get_or_create_direct_chat('user-a-uuid', 'user-b-uuid');

-- Verify
SELECT dc.*, c.type
FROM direct_chats dc
JOIN chats c ON c.id = dc.chat_id
WHERE dc.chat_id = 'returned-chat-uuid';
```

### Test 3: Send Message

```sql
-- Insert message
INSERT INTO messages (chat_id, sender_id, message_type, content)
VALUES (
    'chat-uuid',
    'sender-uuid',
    'text',
    'Hello, this is a test message!'
) RETURNING id;

-- Verify unread count incremented
SELECT * FROM chat_read_state 
WHERE chat_id = 'chat-uuid' AND user_id = 'other-user-uuid';
```

### Test 4: Add Reaction

```sql
-- Add reaction
INSERT INTO message_reactions (message_id, user_id, reaction)
VALUES ('message-uuid', 'user-uuid', '👍');

-- Verify
SELECT * FROM message_reactions WHERE message_id = 'message-uuid';
```

### Test 5: Create Group Chat

```sql
-- Create group
INSERT INTO chats (type, title, owner_id, is_public)
VALUES ('group', 'Test Group', 'owner-uuid', FALSE)
RETURNING id;

-- Add members
INSERT INTO chat_members (chat_id, user_id, role)
VALUES 
    ('group-uuid', 'owner-uuid', 'owner'),
    ('group-uuid', 'member1-uuid', 'member'),
    ('group-uuid', 'member2-uuid', 'admin');

-- Initialize permissions
INSERT INTO chat_permissions (chat_id) VALUES ('group-uuid');

-- Initialize read states
INSERT INTO chat_read_state (chat_id, user_id)
VALUES 
    ('group-uuid', 'owner-uuid'),
    ('group-uuid', 'member1-uuid'),
    ('group-uuid', 'member2-uuid');
```

### Test 6: Upload Media

```sql
-- Insert media record
INSERT INTO media (
    owner_id, 
    storage_provider, 
    storage_key, 
    public_url,
    mime_type, 
    size_bytes,
    width,
    height
)
VALUES (
    'user-uuid',
    'cloudinary',
    'v1234567890/sample.jpg',
    'https://res.cloudinary.com/demo/image/upload/sample.jpg',
    'image/jpeg',
    1048576,
    1920,
    1080
) RETURNING id;
```

### Test 7: Create Poll

```sql
-- Create poll message
INSERT INTO messages (chat_id, sender_id, message_type)
VALUES ('chat-uuid', 'sender-uuid', 'poll')
RETURNING id;

-- Create poll
INSERT INTO polls (message_id, question, allows_multiple, is_anonymous)
VALUES ('message-uuid', 'What is your favorite color?', FALSE, TRUE)
RETURNING id;

-- Add options
INSERT INTO poll_options (poll_id, text, position)
VALUES 
    ('poll-uuid', 'Red', 0),
    ('poll-uuid', 'Blue', 1),
    ('poll-uuid', 'Green', 2);

-- Add votes
INSERT INTO poll_votes (poll_id, option_id, user_id)
VALUES ('poll-uuid', 'option-uuid', 'voter-uuid');

-- Verify vote counts
SELECT 
    po.text,
    COUNT(pv.id) AS vote_count
FROM poll_options po
LEFT JOIN poll_votes pv ON pv.option_id = po.id
WHERE po.poll_id = 'poll-uuid'
GROUP BY po.id, po.text
ORDER BY po.position;
```

### Test 8: Create Story

```sql
-- Insert story
INSERT INTO stories (user_id, media_id, caption, privacy, expires_at)
VALUES (
    'user-uuid',
    'media-uuid',
    'My story caption',
    'everyone',
    NOW() + INTERVAL '24 hours'
) RETURNING id;

-- Add view
INSERT INTO story_views (story_id, viewer_id)
VALUES ('story-uuid', 'viewer-uuid');

-- Add reaction
INSERT INTO story_reactions (story_id, user_id, reaction)
VALUES ('story-uuid', 'reactor-uuid', '❤️');
```

### Test 9: Notification Flow

```sql
-- Insert notification
INSERT INTO notifications (user_id, type, actor_id, entity_type, entity_id, payload)
VALUES (
    'recipient-uuid',
    'new_message',
    'sender-uuid',
    'message',
    'message-uuid',
    '{"preview": "Hello!"}'::jsonb
);

-- Mark as read
UPDATE notifications SET read_at = NOW() WHERE id = 'notification-uuid';
```

### Test 10: Search Messages

```sql
-- Full-text search
SELECT 
    m.id,
    m.content,
    m.created_at,
    ts_rank(to_tsvector('english', m.content), plainto_tsquery('english', 'search term')) AS rank
FROM messages m
WHERE m.chat_id = 'chat-uuid'
  AND m.deleted_at IS NULL
  AND to_tsvector('english', m.content) @@ plainto_tsquery('english', 'search term')
ORDER BY rank DESC
LIMIT 20;
```

## Performance Validation

```sql
-- Check table sizes
SELECT 
    relname AS table_name,
    n_live_tup AS row_count,
    pg_size_pretty(pg_relation_size(relid)) AS table_size
FROM pg_stat_user_tables
WHERE schemaname = 'public'
ORDER BY n_live_tup DESC;

-- Check index usage
SELECT 
    indexrelname AS index_name,
    relname AS table_name,
    idx_scan AS times_used,
    pg_size_pretty(pg_relation_size(indexrelid)) AS index_size
FROM pg_stat_user_indexes
WHERE schemaname = 'public'
ORDER BY idx_scan DESC;

-- Check slow queries (requires pg_stat_statements)
SELECT 
    query,
    calls,
    mean_time,
    total_time
FROM pg_stat_statements
ORDER BY mean_time DESC
LIMIT 10;
```

## Data Retention Validation

```sql
-- Check for expired email verifications
SELECT COUNT(*) AS expired_unverified
FROM email_verifications
WHERE verified_at IS NULL 
  AND expires_at < NOW();

-- Check for expired stories
SELECT COUNT(*) AS expired_stories
FROM stories
WHERE deleted_at IS NULL
  AND expires_at < NOW()
  AND is_pinned = FALSE;

-- Check for expired user restrictions
SELECT COUNT(*) AS expired_restrictions
FROM user_restrictions
WHERE is_active = TRUE
  AND expires_at < NOW()
  AND expires_at IS NOT NULL;

-- Check for expired sessions
SELECT COUNT(*) AS expired_sessions
FROM sessions
WHERE revoked_at IS NULL
  AND expires_at < NOW();
```

## Rollback Validation

```sql
-- Verify rollback procedures work (test in dev only!)
-- This should be run in a transaction and rolled back

BEGIN;
-- Drop all tables in reverse order
DROP TABLE IF EXISTS premium_entitlements CASCADE;
DROP TABLE IF EXISTS premium_requests CASCADE;
DROP TABLE IF EXISTS premium_plans CASCADE;
DROP TABLE IF EXISTS system_config CASCADE;
DROP TABLE IF EXISTS feature_flags CASCADE;
DROP TABLE IF EXISTS moderation_actions CASCADE;
DROP TABLE IF EXISTS audit_logs CASCADE;
DROP TABLE IF EXISTS admin_actions CASCADE;
DROP TABLE IF EXISTS admin_permissions CASCADE;
DROP TABLE IF EXISTS admin_users CASCADE;
DROP TABLE IF EXISTS saved_messages CASCADE;
-- NOTE: 'calls' and 'call_participants' are not created by this schema
-- version (feature removed); no drop needed.
DROP TABLE IF EXISTS user_presence CASCADE;
DROP TABLE IF EXISTS notifications CASCADE;
DROP TABLE IF EXISTS push_tokens CASCADE;
DROP TABLE IF EXISTS story_custom_audience CASCADE;
DROP TABLE IF EXISTS story_reactions CASCADE;
DROP TABLE IF EXISTS story_views CASCADE;
DROP TABLE IF EXISTS stories CASCADE;
DROP TABLE IF EXISTS user_sticker_sets CASCADE;
DROP TABLE IF EXISTS stickers CASCADE;
DROP TABLE IF EXISTS sticker_sets CASCADE;
DROP TABLE IF EXISTS drafts CASCADE;
DROP TABLE IF EXISTS poll_votes CASCADE;
DROP TABLE IF EXISTS poll_options CASCADE;
DROP TABLE IF EXISTS polls CASCADE;
DROP TABLE IF EXISTS message_attachments CASCADE;
DROP TABLE IF EXISTS media CASCADE;
DROP TABLE IF EXISTS message_forwards CASCADE;
DROP TABLE IF EXISTS message_deliveries CASCADE;
DROP TABLE IF EXISTS chat_read_state CASCADE;
DROP TABLE IF EXISTS message_reactions CASCADE;
DROP TABLE IF EXISTS message_deletions CASCADE;
DROP TABLE IF EXISTS message_edits CASCADE;
DROP TABLE IF EXISTS message_replies CASCADE;
DROP TABLE IF EXISTS messages CASCADE;
DROP TABLE IF EXISTS topics CASCADE;
DROP TABLE IF EXISTS chat_permissions CASCADE;
DROP TABLE IF EXISTS chat_members CASCADE;
DROP TABLE IF EXISTS direct_chats CASCADE;
DROP TABLE IF EXISTS chats CASCADE;
DROP TABLE IF EXISTS user_restrictions CASCADE;
DROP TABLE IF EXISTS message_reports CASCADE;
DROP TABLE IF EXISTS user_reports CASCADE;
DROP TABLE IF EXISTS blocked_users CASCADE;
DROP TABLE IF EXISTS privacy_settings CASCADE;
-- NOTE: 'contacts' is not created by this schema version (feature removed);
-- no drop needed.
DROP TABLE IF EXISTS refresh_tokens CASCADE;
DROP TABLE IF EXISTS sessions CASCADE;
DROP TABLE IF EXISTS devices CASCADE;
DROP TABLE IF EXISTS password_credentials CASCADE;
DROP TABLE IF EXISTS email_verifications CASCADE;
DROP TABLE IF EXISTS user_identities CASCADE;
DROP TABLE IF EXISTS profiles CASCADE;
DROP TABLE IF EXISTS users CASCADE;

-- Drop enum types
DROP TYPE IF EXISTS user_status CASCADE;
DROP TYPE IF EXISTS identity_provider CASCADE;
DROP TYPE IF EXISTS device_platform CASCADE;
DROP TYPE IF EXISTS session_revoke_reason CASCADE;
DROP TYPE IF EXISTS verification_purpose CASCADE;
DROP TYPE IF EXISTS privacy_visibility CASCADE;
DROP TYPE IF EXISTS privacy_groups CASCADE;
DROP TYPE IF EXISTS report_reason CASCADE;
DROP TYPE IF EXISTS report_status CASCADE;
DROP TYPE IF EXISTS restriction_type CASCADE;
DROP TYPE IF EXISTS chat_type CASCADE;
DROP TYPE IF EXISTS chat_member_role CASCADE;
DROP TYPE IF EXISTS topic_state CASCADE;
DROP TYPE IF EXISTS message_type CASCADE;
DROP TYPE IF EXISTS message_status CASCADE;
DROP TYPE IF EXISTS storage_provider_type CASCADE;
DROP TYPE IF EXISTS sticker_type CASCADE;
DROP TYPE IF EXISTS sticker_set_type CASCADE;
DROP TYPE IF EXISTS story_privacy CASCADE;
DROP TYPE IF EXISTS push_provider_type CASCADE;
DROP TYPE IF EXISTS notification_type CASCADE;
DROP TYPE IF EXISTS presence_status CASCADE;
-- NOTE: call_type / call_status / call_participant_role / call_participant_status
-- are not created by this schema version (calls feature removed); the
-- IF EXISTS guards make these drops safe no-ops if run against an older
-- database that still has them.
DROP TYPE IF EXISTS call_type CASCADE;
DROP TYPE IF EXISTS call_status CASCADE;
DROP TYPE IF EXISTS call_participant_role CASCADE;
DROP TYPE IF EXISTS call_participant_status CASCADE;
DROP TYPE IF EXISTS admin_role CASCADE;
DROP TYPE IF EXISTS audit_action CASCADE;
DROP TYPE IF EXISTS moderation_action_type CASCADE;
DROP TYPE IF EXISTS premium_request_status CASCADE;
DROP TYPE IF EXISTS premium_feature CASCADE;

-- Drop functions
DROP FUNCTION IF EXISTS update_updated_at_column() CASCADE;
DROP FUNCTION IF EXISTS current_user_id() CASCADE;
DROP FUNCTION IF EXISTS cleanup_expired_email_verifications() CASCADE;
DROP FUNCTION IF EXISTS detect_refresh_token_reuse(UUID, UUID) CASCADE;
DROP FUNCTION IF EXISTS user_has_active_restriction(UUID, restriction_type) CASCADE;
DROP FUNCTION IF EXISTS expire_user_restrictions() CASCADE;
DROP FUNCTION IF EXISTS message_is_deleted_for_user(UUID, UUID) CASCADE;
DROP FUNCTION IF EXISTS notify_new_message() CASCADE;
DROP FUNCTION IF EXISTS notify_message_update() CASCADE;
DROP FUNCTION IF EXISTS notify_reaction_change() CASCADE;
DROP FUNCTION IF EXISTS notify_new_notification() CASCADE;
DROP FUNCTION IF EXISTS update_chat_on_message() CASCADE;
DROP FUNCTION IF EXISTS update_chat_on_member_change() CASCADE;
DROP FUNCTION IF EXISTS increment_unread_on_message() CASCADE;
DROP FUNCTION IF EXISTS increment_story_view_count() CASCADE;
DROP FUNCTION IF EXISTS update_story_reaction_count() CASCADE;
DROP FUNCTION IF EXISTS auto_close_expired_polls() CASCADE;
DROP FUNCTION IF EXISTS get_user_unread_count(UUID) CASCADE;
DROP FUNCTION IF EXISTS get_user_chat_summary(UUID) CASCADE;
DROP FUNCTION IF EXISTS get_or_create_direct_chat(UUID, UUID) CASCADE;
DROP FUNCTION IF EXISTS mark_chat_as_read(UUID, UUID) CASCADE;
DROP FUNCTION IF EXISTS search_messages_in_chat(UUID, TEXT, INTEGER) CASCADE;
DROP FUNCTION IF EXISTS get_public_profile(UUID) CASCADE;
DROP FUNCTION IF EXISTS get_poll_option_vote_count(UUID) CASCADE;
DROP FUNCTION IF EXISTS user_has_premium_feature(UUID, premium_feature) CASCADE;

ROLLBACK; -- Don't actually commit the rollback!
```

## Security Validation

The security model is enforced by Row Level Security policies, minimal
column-level grants, SECURITY DEFINER service functions, and idempotency
constraints. The requirements below are the invariants the schema must hold;
each maps to a test in `tests/run_security_tests.sh`.

| # | Requirement | Enforcement |
|---|-------------|-------------|
| 1 | User cannot read another user's private data (identities, sessions) | RLS `SELECT` policies scope to `current_user_id()` |
| 2 | User cannot modify another user's data | RLS `UPDATE` policies scope to `current_user_id()`; column-level grants |
| 3 | User cannot join arbitrary private chats | `chat_members` insert policy requires public chat, owner, or direct-chat participant |
| 4 | User cannot self-promote to owner/admin | `role` column not granted to clients; owner-only insert policy |
| 5 | User cannot manipulate another user's read state | `chat_read_state` scoped to own rows |
| 6 | User cannot search/read private chats they are not members of | `search_messages_in_chat` is SECURITY INVOKER and subject to RLS |
| 7 | User cannot attach another user's private media | `message_attachments` insert policy verifies media ownership |
| 8 | Email cannot be marked verified without the OTP flow | `user_identities` prevents setting `email_verified`; `verify_email_code()` is the only path |
| 9 | User cannot create/extend arbitrary sessions | `sessions` has no client INSERT/UPDATE; only `revoke_own_session()` |
| 10 | Non-admin cannot perform admin operations | `admin_actions`/`admin_users` have no client write grants; RLS `is_active_admin()` checks |
| 11 | Admin audit records are protected (append-only) | `audit_logs`/`admin_actions` RLS + UPDATE-blocking triggers |
| 12 | Duplicate message retry does not duplicate | unique partial index on `(chat_id, sender_id, client_message_id)` |
| 13 | Concurrent operations respect constraints | unique indexes + `ON CONFLICT` in service functions |

### Running the suite

```bash
bash tests/run_security_tests.sh
```

The suite seeds three actors (A, B, admin) plus a direct chat, a private
group, media, a story, a poll, a session, and admin/audit rows, then attempts
each malicious action under `SET ROLE authenticated` with a forged JWT `sub`.
Every test must be blocked (RLS denial, privilege denial, or constraint).

### Adversarial queries (spot checks)

```sql
-- 1. A tries to read B's identities
SET ROLE authenticated;
SELECT set_config('request.jwt.claims',
  '{"sub":"aaaaaaaa-1111-1111-1111-111111111111"}', true);
SELECT * FROM user_identities WHERE user_id = 'bbbbbbbb-2222-2222-2222-222222222222';
-- expect: 0 rows

-- 2. A tries to join B's private group
INSERT INTO chat_members (chat_id, user_id, role)
VALUES ('99999999-9999-9999-9999-999999999999',
        'aaaaaaaa-1111-1111-1111-111111111111', 'member');
-- expect: RLS violation (new row violates row-level security policy)

-- 3. A tries to self-promote
UPDATE chat_members SET role = 'owner'
WHERE chat_id = '11111111-1111-1111-1111-111111111111'
  AND user_id = 'aaaaaaaa-1111-1111-1111-111111111111';
-- expect: permission denied for column role

-- 4. A tries to forge audit history
UPDATE audit_logs SET metadata = '{"forged":true}';
-- expect: permission denied / trigger blocks
RESET ROLE;
```

### Key invariants verified

- **All 42 user-facing tables** have RLS enabled with non-trivial policies
  (see `RLS Coverage` below). Internal-only tables (`chat_sequences`,
  `password_credentials`, `refresh_tokens`) have no client grants, so RLS is
  unnecessary there.
- **No RLS policy causes infinite recursion** — all policies reference
  non-recursive helper functions or single-level subqueries.
- **Client roles get minimal column grants**: e.g. `role` on `chat_members`,
  `status` on `premium_requests`, and `read_at` on `notifications` are the only
  mutable columns exposed.
- **Security-sensitive service functions** (`send_text_message`,
  `get_or_create_direct_chat`, `revoke_own_session`, `verify_email_code`) are
  SECURITY DEFINER and validate their own invariants.
- **Concurrency invariants** are backed by real constraints, not just RLS:
  unique direct-chat pairs, message idempotency keys, story-view uniqueness,
  and single-choice poll vote rules.