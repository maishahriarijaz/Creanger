-- ============================================================================
-- CREANGER SUPABASE SCHEMA - MIGRATION 020: REALTIME PUBLICATION
-- ============================================================================
-- Registers the tables that genuinely need live updates in the Supabase
-- realtime publication. Only a curated subset is added - NOT every table.
--
-- Tables added (each has correct RLS; Realtime respects RLS when delivering
-- changes to subscribers):
--   messages, message_reactions, chat_read_state, chat_members, notifications,
--   user_presence, stories, story_views, story_reactions, premium_requests,
--   premium_entitlements
--
-- Not added (no need): users, profiles, media (heavy/storage pointers),
-- audit/admin tables, poll_votes (privacy), draft/sticker tables.
--
-- Recovery guarantee: Realtime is NOT the source of truth. Any missed event
-- can be recovered through the database sync cursor (messages.chat_seq) using
-- get_messages_since().
--
-- This migration is a safe no-op when the cluster is not configured for
-- logical replication (wal_level <> logical) - i.e. it will not break a
-- vanilla PostgreSQL install, but will properly configure real Supabase.
-- ============================================================================

DO $$
DECLARE
    tbl TEXT;
BEGIN
    IF current_setting('wal_level', TRUE) <> 'logical' THEN
        RAISE NOTICE 'wal_level is not logical - skipping realtime publication setup';
        RETURN;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_publication WHERE pubname = 'supabase_realtime') THEN
        CREATE PUBLICATION supabase_realtime;
    END IF;

    FOREACH tbl IN ARRAY ARRAY[
        'messages',
        'message_reactions',
        'chat_read_state',
        'chat_members',
        'notifications',
        'user_presence',
        'stories',
        'story_views',
        'story_reactions',
        'premium_requests',
        'premium_entitlements'
    ]
    LOOP
        IF NOT EXISTS (
            SELECT 1
            FROM pg_publication_rel pr
            JOIN pg_class c ON c.oid = pr.prrelid
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE pr.prpubid = (SELECT oid FROM pg_publication WHERE pubname = 'supabase_realtime')
              AND n.nspname = 'public'
              AND c.relname = tbl
        ) THEN
            EXECUTE format('ALTER PUBLICATION supabase_realtime ADD TABLE public.%I', tbl);
        END IF;
    END LOOP;
END
$$;