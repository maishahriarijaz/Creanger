-- ============================================================================
-- CREANGER SUPABASE SCHEMA - MIGRATION 023: MESSAGE REPORT INTEGRITY
-- ============================================================================
--
-- Mandatory Fix 7 (message reports must reference real messages in the given
-- chat, must be unique per reporter/message, and must be validated).
--
-- Previously message_reports had NO FK on message_id / chat_id, no uniqueness,
-- and an INSERT policy that only checked reporter_id = current_user_id() -
-- anyone could fabricate a report for a message that does not exist or that
-- lives in a different chat. This migration:
--
--  1. Adds FOREIGN KEY message_reports.message_id -> messages(id) and
--     message_reports.chat_id -> chats(id).
--  2. Enforces UNIQUE (reporter_id, message_id): one report per reporter per
--     message; a reporter cannot spam duplicate reports.
--  3. Adds a SECURITY DEFINER validation trigger that guarantees:
--       - the message exists,
--       - the message actually belongs to the reported chat_id,
--       - the reporter is NOT the message author (no self-reporting),
--       - the reporter is an active member of the chat (no outsider spam).
--  4. Tightens the INSERT policy to require the reporter be a member of the
--     reported chat. The trigger performs the authoritative checks.
-- ============================================================================

-- ============================================================================
-- 1. FOREIGN KEYS (idempotent)
-- ============================================================================

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'message_reports_message_id_fkey'
          AND conrelid = 'message_reports'::regclass
    ) THEN
        ALTER TABLE public.message_reports
            ADD CONSTRAINT message_reports_message_id_fkey
            FOREIGN KEY (message_id) REFERENCES public.messages(id) ON DELETE CASCADE;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'message_reports_chat_id_fkey'
          AND conrelid = 'message_reports'::regclass
    ) THEN
        ALTER TABLE public.message_reports
            ADD CONSTRAINT message_reports_chat_id_fkey
            FOREIGN KEY (chat_id) REFERENCES public.chats(id) ON DELETE CASCADE;
    END IF;
END $$;

-- ============================================================================
-- 2. ONE REPORT PER REPORTER PER MESSAGE
-- ============================================================================

CREATE UNIQUE INDEX IF NOT EXISTS message_reports_reporter_message_unique
    ON public.message_reports(reporter_id, message_id);

-- ============================================================================
-- 3. VALIDATION TRIGGER (SECURITY DEFINER - bypasses RLS for authoritative
--    checks; clients cannot INSERT/UPDATE reports that fail validation)
-- ============================================================================

CREATE OR REPLACE FUNCTION public.validate_message_report()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_chat_id UUID;
    v_sender_id UUID;
BEGIN
    IF NEW.message_id IS NULL OR NEW.chat_id IS NULL OR NEW.reporter_id IS NULL THEN
        RAISE EXCEPTION 'message_id, chat_id and reporter_id are required';
    END IF;

    SELECT m.chat_id, m.sender_id INTO v_chat_id, v_sender_id
    FROM public.messages m
    WHERE m.id = NEW.message_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'reported message does not exist';
    END IF;

    IF v_chat_id IS DISTINCT FROM NEW.chat_id THEN
        RAISE EXCEPTION 'reported message does not belong to the given chat';
    END IF;

    IF v_sender_id = NEW.reporter_id THEN
        RAISE EXCEPTION 'cannot report your own message';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM public.chat_members cm
        WHERE cm.chat_id = v_chat_id
          AND cm.user_id = NEW.reporter_id
          AND cm.left_at IS NULL
    ) THEN
        RAISE EXCEPTION 'only chat members may report messages';
    END IF;

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trigger_validate_message_report ON message_reports;
CREATE TRIGGER trigger_validate_message_report
    BEFORE INSERT OR UPDATE ON message_reports
    FOR EACH ROW
    EXECUTE FUNCTION public.validate_message_report();

-- FIX 3 consistency: the trigger function is not client-callable.
REVOKE ALL ON FUNCTION public.validate_message_report() FROM PUBLIC;

-- ============================================================================
-- 4. TIGHTENED INSERT POLICY
-- ============================================================================
-- Reporter must be themselves AND a member of the reported chat. The trigger
-- covers message-existence, message<->chat consistency, no-self-report, and
-- membership in depth.

DROP POLICY IF EXISTS message_reports_insert_own ON message_reports;
CREATE POLICY message_reports_insert_own ON message_reports
    FOR INSERT WITH CHECK (
        reporter_id = current_user_id()
        AND public.is_chat_member(chat_id)
    );
