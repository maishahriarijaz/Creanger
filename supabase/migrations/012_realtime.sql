-- ============================================================================
-- CREANGER SUPABASE SCHEMA - MIGRATION 012: REALTIME CONFIGURATION
-- ============================================================================
-- This migration configures Supabase Realtime publications for tables
-- that need real-time updates.
-- ============================================================================

-- ============================================================================
-- REALTIME PUBLICATIONS
-- ============================================================================

-- Note: These are managed through the Supabase dashboard or via SQL commands.
-- The following sets up the realtime publication for Creanger tables.

-- Create publication for realtime tables (requires superuser or specific privileges)
-- This command may need to be run by a database owner or through Supabase dashboard

-- Uncomment and run manually if you have superuser access:
-- DROP PUBLICATION IF EXISTS supabase_realtime;
-- CREATE PUBLICATION supabase_realtime;

-- ============================================================================
-- REALTIME TABLE CONFIGURATION
-- ============================================================================

-- The following tables should be added to the realtime publication:
-- 
-- 1. messages              - New messages in chats
-- 2. message_reactions     - Reaction additions/removals
-- 3. chat_read_state       - Read status updates
-- 4. chats                 - Chat metadata changes
-- 5. chat_members          - Member additions/removals/role changes
-- 6. user_presence         - User online/offline status
-- 7. notifications         - New notifications
-- 8. stories               - New stories/expirations
-- 9. story_reactions       - Story reactions
-- 10. premium_requests     - Premium request status changes (admin approval)
-- 11. premium_entitlements - Premium feature grant/revoke
-- 12. typing_indicators    - Ephemeral (use broadcast channel instead)

-- Example command to add table to publication:
-- ALTER PUBLICATION supabase_realtime ADD TABLE messages;
-- ALTER PUBLICATION supabase_realtime ADD TABLE message_reactions;
-- ALTER PUBLICATION supabase_realtime ADD TABLE chat_read_state;
-- ALTER PUBLICATION supabase_realtime ADD TABLE chats;
-- ALTER PUBLICATION supabase_realtime ADD TABLE chat_members;
-- ALTER PUBLICATION supabase_realtime ADD TABLE user_presence;
-- ALTER PUBLICATION supabase_realtime ADD TABLE notifications;
-- ALTER PUBLICATION supabase_realtime ADD TABLE stories;
-- ALTER PUBLICATION supabase_realtime ADD TABLE story_reactions;
-- ALTER PUBLICATION supabase_realtime ADD TABLE premium_requests;
-- ALTER PUBLICATION supabase_realtime ADD TABLE premium_entitlements;

-- ============================================================================
-- TYPING INDICATORS (Ephemeral - use Realtime Broadcast)
-- ============================================================================
-- 
-- Typing indicators should NOT be stored in PostgreSQL.
-- Use Supabase Realtime Broadcast channels instead:
--
-- Channel: typing:{chat_id}
-- Events: typing_start, typing_stop
-- Payload: { user_id, chat_id, timestamp }
--
-- The Android client should:
-- 1. Subscribe to channel 'typing:{chat_id}'
-- 2. Broadcast 'typing_start' when user starts typing
-- 3. Broadcast 'typing_stop' when user stops typing
-- 4. Listen for typing events from other users

-- ============================================================================
-- PRESENCE (User online status)
-- ============================================================================
--
-- Use Supabase Presence API for tracking online users:
--
-- Channel: online_users
-- Track: { user_id, online_at, metadata }
--
-- The Android client should:
-- 1. Subscribe to presence channel on app start
-- 2. Sync local state with server presence state
-- 3. Update user_presence table on significant state changes

-- ============================================================================
-- FUNCTIONS FOR REALTIME HELPERS
-- ============================================================================

-- Function to get unread count for a user across all chats
CREATE OR REPLACE FUNCTION get_user_unread_count(p_user_id UUID)
RETURNS INTEGER AS $$
DECLARE
    v_count INTEGER;
BEGIN
    SELECT COALESCE(SUM(unread_count), 0) INTO v_count
    FROM chat_read_state
    WHERE user_id = p_user_id;
    RETURN v_count;
END;
$$ LANGUAGE plpgsql STABLE;

-- Function to get chat summary for a user
CREATE OR REPLACE FUNCTION get_user_chat_summary(p_user_id UUID)
RETURNS TABLE (
    chat_id UUID,
    chat_type chat_type,
    title TEXT,
    last_message_at TIMESTAMPTZ,
    unread_count INTEGER,
    member_count BIGINT
) AS $$
BEGIN
    RETURN QUERY
    SELECT 
        c.id AS chat_id,
        c.type AS chat_type,
        c.title,
        MAX(m.created_at) AS last_message_at,
        COALESCE(crs.unread_count, 0) AS unread_count,
        (SELECT COUNT(*) FROM chat_members cm WHERE cm.chat_id = c.id AND cm.left_at IS NULL) AS member_count
    FROM chats c
    JOIN chat_members cm ON cm.chat_id = c.id AND cm.user_id = p_user_id AND cm.left_at IS NULL
    LEFT JOIN messages m ON m.chat_id = c.id AND m.deleted_at IS NULL
    LEFT JOIN chat_read_state crs ON crs.chat_id = c.id AND crs.user_id = p_user_id
    WHERE c.deleted_at IS NULL
    GROUP BY c.id, c.type, c.title, crs.unread_count
    ORDER BY last_message_at DESC NULLS LAST;
END;
$$ LANGUAGE plpgsql STABLE;