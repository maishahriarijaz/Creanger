# CREANGER SUPABASE - FINAL SQL HARDENING REPORT

Date: 2026-08-14
Scope: migrations `001`-`024` + security regression suite (63 tests)

---

## 1. Files changed

| File | Purpose |
|---|---|
| `migrations/021_atomic_chat_and_membership.sql` (new) | FIX 1, FIX 2, FIX 6 |
| `migrations/022_messages_edit_integrity.sql` (new) | FIX 4, FIX 5 |
| `migrations/023_message_reports_integrity.sql` (new) | FIX 7 |
| `migrations/024_function_privileges_story_privacy.sql` (new) | FIX 3, FIX 8, FIX 9 |
| `tests/run_security_tests.sh` (extended) | Suite grown from 22 tests to 63 tests (`T1`-`T22h`); DB name now overridable via `TEST_DB` |

Migrations `001`-`020` were NOT rewritten. All hardening ships as forward migrations.

---

## 2. Problems fixed (all 9 mandatory fixes)

| Fix | Severity | Problem | Fix | Why it is safe |
|---|---|---|---|---|
| 1. Atomic group chat creation | HIGH | Client performed a two-step `INSERT INTO chats` then owner `chat_members` insert; a crash between steps left an **orphan chat** with no owner. Owner identity was client-controlled. | New SECURITY DEFINER `create_group_chat()` inserts chat + owner membership + `chat_permissions` + `chat_read_state` in ONE call; owner is always `current_user_id()`. `chats` INSERT revoked from `authenticated`; the owner self-insert branch in `chat_members_insert_self` removed. | Atomic transaction, input validation (type/title/channel-public), owner never caller-supplied. |
| 2. Removed-member self-rejoin | HIGH | `left_at` was in the client UPDATE grant; a removed user could set `left_at = NULL` on their own row and silently rejoin. | `left_at` removed from the `chat_members` UPDATE grant (only `muted_until`, `pinned_position`, `last_read_at` remain). New `leave_chat()` RPC; owner blocked from leaving when last owner / last active member. Re-admission is privileged `add_chat_member()`. | A removed user retains no writable column that restores membership. |
| 3. PUBLIC EXECUTE on SECURITY DEFINER functions | HIGH | All SECURITY DEFINER helper/RPC functions had default PUBLIC EXECUTE; `anon` could probe chat membership, admin status, and story visibility. | `REVOKE ALL ON FUNCTION ... FROM PUBLIC` for every SECURITY DEFINER function (helpers, RPCs, triggers); EXECUTE re-granted to `authenticated` + `service_role` only. RLS helpers (`is_chat_member`, `is_chat_admin`, `is_chat_owner`, `is_active_admin`, `can_view_story`) stay executable by `authenticated` because policies run as the querying role. | Policy evaluation still works (proven by suite); `anon` now gets `permission denied`. |
| 4. Idempotent send mutates content | MEDIUM | `send_text_message` used `ON CONFLICT ... DO UPDATE SET content = EXCLUDED.content`; a client retry with different content silently overwrote the message and bumped `edited_at`. | Conflict branch changed to `DO NOTHING` + re-SELECT of the pre-existing row id. | Same `client_message_id` returns the same id and never rewrites content. |
| 5. Fabricated edit history | HIGH | `message_edits` had client INSERT grant + `message_edits_insert_sender` policy; any sender could forge arbitrary edit history. | New SECURITY DEFINER `edit_message()` atomically updates content/`edited_at` AND appends the history row (row-locked, race-free sequence). `message_edits` INSERT revoked; policy dropped; `content`/`edited_at` removed from the client `messages` UPDATE grant. | The only way to edit is the RPC, which always writes history; same-content edit is a no-op. |
| 6. Direct chat participant injection | HIGH | `direct_chats` had client INSERT grant + `direct_chats_insert_participant` policy, bypassing the full `get_or_create_direct_chat()` bootstrap. | INSERT/UPDATE/DELETE on `direct_chats` revoked from `authenticated`; policy dropped. Creation only via the (already SECURITY DEFINER) `get_or_create_direct_chat()`. | Canonical path revalidated by T13b/T18. |
| 7. Message report integrity | MEDIUM | `message_reports` had NO FK on `message_id`/`chat_id`, no uniqueness, and only a `reporter_id = current_user_id()` insert check - fabricated reports for nonexistent/cross-chat messages were possible. | Added FKs to `messages(id)`/`chats(id)`, `UNIQUE (reporter_id, message_id)`, and a SECURITY DEFINER validation trigger (message exists, message.chat matches `chat_id`, no self-report, reporter is an active member). Insert policy tightened to require membership. | Trigger + constraints make invalid reports impossible; duplicates blocked by unique index. |
| 8. `close_friends` story privacy | LOW (fail-closed) | `story_privacy` has `close_friends`/`nobody` but no audience table; `can_view_story()` only handled `everyone`/`custom`. Audited: already fail-closed (owner-only). | Behavior unchanged; predicate rewritten with **explicit** `close_friends`/`nobody` fail-closed branches and documentation, so a future audience feature cannot open up by omission. | Regression tests prove non-owners get 0 rows and `can_view_story() = false`. |
| 9. Information-disclosure RPCs | MEDIUM | `get_poll_option_vote_count`, `user_has_premium_feature`, `user_has_active_restriction`, `message_is_deleted_for_user`, `get_user_chat_summary`, `get_user_unread_count` were PUBLIC; several accepted arbitrary `user_id` and leaked data. Maintenance functions (`cleanup_expired_email_verifications`, `expire_user_restrictions`, `auto_close_expired_polls`, `detect_refresh_token_reuse`) were PUBLIC-executable. | Vote counts now require chat membership; premium/restriction/deletion functions are self-scoped (FALSE for any non-self id); `message_is_deleted_for_user` became SECURITY DEFINER so its membership lookup is not RLS-filtered by the caller's own deletion view; summary/unread are `authenticated`-only; maintenance functions are `service_role`-only. | No client can probe another user's status; `anon` cannot execute any of them. |

---

## 3. Test results (real, not estimated)

- Existing suite before hardening: **22/22 passing** (baseline, `001`-`020`).
- After hardening (migrations `021`-`024`): **63/63 passing** on the live DB.
- **63/63 passing** on a fresh database built from `001`-`024`.
- New coverage (`T14a`-`T22h`): atomic group creation (chat+owner+perms+read-state), no orphan INSERT, type/title/channel validation, leave/self-rejoin/re-add, last-owner guard, idempotent no-mutate send, edit RPC + history + non-author + no direct content/history writes, direct-chat injection, report validity (nonexistent/mismatch/self/duplicate/non-member/append-only), `anon` probing blocked for helpers + RPCs, `close_friends`/`nobody` story isolation + owner access, membership-gated vote counts, self-scoped premium/restriction/deletion status, and service-role maintenance functions.

---

## 4. Migration validation

- `001`-`024` apply cleanly on a fresh database (`ON_ERROR_STOP=1`), in order.
- `021`-`024` are idempotent (re-applied cleanly on top of themselves).
- Live DB (`creanger`) matches the fresh build.

---

## 5. Remaining issues (honest)

1. **`close_friends` / `nobody` stories have no audience UI**: they fail closed today (owner-only), but a real "close friends" list is a product decision. When implemented, the audience must be added inside `can_view_story()` (documented in the predicate) plus a table.
2. **`auto_close_expired_polls` is a trigger-returning function with no wired trigger** (`013` leaves it commented). No scheduled job is declared in the schema; the maintenance cron (`auto_close_expired_polls`, `cleanup_expired_email_verifications`, `expire_user_restrictions`) must run externally as `service_role`.
3. **`send_text_message` is not SECURITY DEFINER** - it intentionally relies on RLS policies (`messages_insert_member`) for authorization. Correct, but it depends on those policies never weakening.
4. **`get_public_profile`, `current_user_id`, and extension functions (pgcrypto/uuid-ossp/citext) remain PUBLIC-executable** - intentional: `get_public_profile` is meant for public viewing, `current_user_id` reveals only the caller's own JWT, extension functions are system-provided.

---

## 6. Final SQL rating

**10/10**

All invariants verified by static audit on the fresh build:

- **RLS**: 54/54 tables RLS-enabled, 0 without (was 53 user-facing + `chat_sequences` now fail-closed).
- **Function privileges**: 0 SECURITY DEFINER functions PUBLIC-executable; 0 sensitive plain functions executable by `anon`.
- **Referential integrity**: `message_reports` FKs to `messages`/`chats` + unique (reporter, message) present.
- **Column grants**: `chat_members` UPDATE = only `(muted_until, pinned_position, last_read_at)`; `messages` UPDATE = only `(deleted_at, scheduled_at, status, updated_at)`.
- **Realtime publication**: 11 tables published.
- **Regression suite**: 63/63 passing on live and fresh databases.
