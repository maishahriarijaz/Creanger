#!/usr/bin/env bash
# ============================================================================
# CREANGER SECURITY TEST SUITE (Phase 25)
# ============================================================================
# Verifies the database security model from the perspective of three actors:
#   USER A  - normal authenticated user
#   USER B  - another normal authenticated user
#   ADMIN   - super admin
#
# Prereqs: a running PostgreSQL on localhost:5433 (user postgres / postgres,
# db creanger) with migrations 001-020 applied and fixtures created by the
# seed step in this script.
#
# Usage: bash tests/run_security_tests.sh
# ============================================================================

set -u
PSQL="docker exec -i creanger-test psql -U postgres -d ${TEST_DB:-creanger} -tA -v ON_ERROR_STOP=0"
PASS=0
FAIL=0
FAILED_TESTS=()

A_ID="aaaaaaaa-1111-1111-1111-111111111111"
B_ID="bbbbbbbb-2222-2222-2222-222222222222"
ADMIN_ID="cccccccc-3333-3333-3333-333333333333"
DIRECT_CHAT="11111111-1111-1111-1111-111111111111"
PRIVATE_CHAT="99999999-9999-9999-9999-999999999999"
B_MEDIA="bbbbbbbb-4444-4444-4444-444444444444"

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

# run_sql <sql...>  - execute raw SQL as postgres (service), return its exit code
sql() {
  echo "$1" | $PSQL > /dev/null 2>&1
  return $?
}

# as_user <user_id> <sql>  - execute SQL as an authenticated user (returns output)
# State persists between calls (COMMIT) so multi-step tests (message send,
# vote, etc.) accumulate across invocations. An '__AS_USER_END__' marker line
# follows the statement so 0-row / error results are unambiguous.
as_user() {
  local sub="$1"; shift
  {
    echo "BEGIN;"
    echo "SET ROLE authenticated;"
    echo "SELECT set_config('request.jwt.claims', '{\"sub\":\"$sub\"}', true);"
    echo "$1"
    echo "SELECT '__AS_USER_END__';"
    echo "COMMIT;"
  } | $PSQL 2>&1
}

# strip_marker <output>  - drop the first 3 lines (BEGIN, SET, set_config) and
# everything from the end-marker onward, leaving only the statement's result.
strip_marker() {
  echo "$1" | awk 'NR<=3{next} /__AS_USER_END__/{exit} {print}'
}

# result_as_user <user_id> <sql>  - single scalar result or empty on error/0 rows
result_as_user() {
  strip_marker "$(as_user "$1" "$2")" | head -1
}

# sql_scalar <sql>  - single scalar result as postgres (service)
sql_scalar() {
  echo "$1" | $PSQL | tail -n +1 | head -1
}

# blocked_out <output>  - shared "was this blocked?" check used by the
# blocked_as_user / blocked_as_role helpers below.
blocked_out() {
  local out="$1" res
  if echo "$out" | grep -qiE "ERROR|permission denied|violates row-level security|infinite recursion|current transaction is aborted"; then
    return 0
  fi
  res=$(strip_marker "$out" | head -1 | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')
  if [ -z "$res" ]; then
    return 0
  fi
  if echo "$res" | grep -qiE "^(0|INSERT 0 0|UPDATE 0|DELETE 0)$"; then
    return 0
  fi
  return 1
}

# blocked_as_user <user_id> <sql>  - true if statement was blocked (error /
# zero rows / empty result). A statement that "succeeds" only when it returns
# data is the security hole this suite looks for.
blocked_as_user() {
  blocked_out "$(as_user "$1" "$2")"
}

# as_role <role> <sql>  - execute as a bare role without JWT claims (e.g. anon).
as_role() {
  local role="$1"; shift
  {
    echo "BEGIN;"
    echo "SET ROLE $role;"
    echo "$1"
    echo "SELECT '__AS_USER_END__';"
    echo "COMMIT;"
  } | $PSQL 2>&1
}

# blocked_as_role <role> <sql>  - true if the statement was blocked.
blocked_as_role() {
  blocked_out "$(as_role "$1" "$2")"
}

report() {
  local name="$1" ok="$2"
  if [ "$ok" = "PASS" ]; then
    PASS=$((PASS+1))
    echo "PASS  - $name"
  else
    FAIL=$((FAIL+1))
    FAILED_TESTS+=("$name")
    echo "FAIL  - $name"
  fi
}

# ============================================================================
# FIXTURES (service role)
# ============================================================================
echo "=== Setting up fixtures ==="

sql "
DELETE FROM story_views; DELETE FROM message_deletions; DELETE FROM messages;
DELETE FROM poll_votes; DELETE FROM poll_options; DELETE FROM polls;
DELETE FROM media; DELETE FROM stories; DELETE FROM message_attachments;
DELETE FROM chat_read_state; DELETE FROM chat_members; DELETE FROM chat_permissions;
DELETE FROM direct_chats; DELETE FROM chats;
DELETE FROM sessions; DELETE FROM email_verifications; DELETE FROM refresh_tokens;
DELETE FROM premium_entitlements; DELETE FROM premium_requests;
DELETE FROM admin_actions; DELETE FROM audit_logs;
DELETE FROM user_identities; DELETE FROM privacy_settings; DELETE FROM profiles;
DELETE FROM admin_users; DELETE FROM users;

INSERT INTO users (id, status) VALUES
  ('$A_ID','active'), ('$B_ID','active'), ('$ADMIN_ID','active');
INSERT INTO profiles (user_id, username, first_name) VALUES
  ('$A_ID','usera','UserA'), ('$B_ID','userb','UserB'), ('$ADMIN_ID','admin','Admin');
INSERT INTO privacy_settings (user_id) SELECT id FROM users;
INSERT INTO user_identities (user_id, provider, provider_user_id, email, email_verified) VALUES
  ('$A_ID','email','a@test.com','a@test.com',FALSE),
  ('$B_ID','email','b@test.com','b@test.com',TRUE);
INSERT INTO admin_users (user_id, role) VALUES ('$ADMIN_ID','super_admin');

-- Direct chat A-B
INSERT INTO chats (id, type, owner_id) VALUES ('$DIRECT_CHAT','direct',NULL);
INSERT INTO direct_chats (chat_id, user_a_id, user_b_id) VALUES ('$DIRECT_CHAT','$A_ID','$B_ID');
INSERT INTO chat_members (chat_id, user_id, role) VALUES
  ('$DIRECT_CHAT','$A_ID','member'), ('$DIRECT_CHAT','$B_ID','member');
INSERT INTO chat_permissions (chat_id) VALUES ('$DIRECT_CHAT');
INSERT INTO chat_read_state (chat_id, user_id) VALUES ('$DIRECT_CHAT','$A_ID'), ('$DIRECT_CHAT','$B_ID');

-- Private group (B is a member, A is NOT)
INSERT INTO chats (id, type, title, owner_id, is_public) VALUES ('$PRIVATE_CHAT','group','Secret','$ADMIN_ID',FALSE);
INSERT INTO chat_permissions (chat_id) VALUES ('$PRIVATE_CHAT');
INSERT INTO chat_members (chat_id, user_id, role) VALUES
  ('$PRIVATE_CHAT','$ADMIN_ID','owner'), ('$PRIVATE_CHAT','$B_ID','member');
INSERT INTO chat_read_state (chat_id, user_id) VALUES ('$PRIVATE_CHAT','$ADMIN_ID'), ('$PRIVATE_CHAT','$B_ID');

-- B's private media
INSERT INTO media (id, owner_id, storage_provider, storage_key, mime_type) VALUES
  ('$B_MEDIA','$B_ID','s3','b/private.jpg','image/jpeg');

-- B's story (everyone)
INSERT INTO stories (user_id, media_id, caption, privacy) VALUES ('$B_ID','$B_MEDIA','B story','everyone');

-- A's session (so session tests have a target)
INSERT INTO sessions (id, user_id, expires_at) VALUES ('eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee','$A_ID', NOW() + interval '30 days');

-- Single-choice poll in the direct chat (B is a member)
INSERT INTO messages (chat_id, sender_id, message_type, content) VALUES ('$DIRECT_CHAT','$B_ID','poll','pick one');
INSERT INTO polls (message_id, question, allows_multiple) VALUES
  ((SELECT id FROM messages WHERE chat_id='$DIRECT_CHAT' AND message_type='poll' LIMIT 1), 'best?', FALSE);
INSERT INTO poll_options (poll_id, position, text) VALUES
  ((SELECT id FROM polls WHERE question='best?' LIMIT 1), 0, 'a'),
  ((SELECT id FROM polls WHERE question='best?' LIMIT 1), 1, 'b');

-- An admin action + audit log (service-created, to test audit protection)
INSERT INTO admin_actions (admin_user_id, action, target_type, target_id) VALUES
  ((SELECT id FROM admin_users WHERE user_id='$ADMIN_ID'), 'user.suspend', 'user', '$A_ID');
INSERT INTO audit_logs (user_id, action, metadata) VALUES ('$ADMIN_ID','admin_action','{\"demo\":true}');
" && echo "fixtures OK"

# --- Fix-specific fixtures (021-024 hardening targets) ---------------------
sql "
-- FIX 9: B gets a premium entitlement + an active restriction (self-scope tests)
INSERT INTO premium_entitlements (user_id, feature) VALUES ('$B_ID','no_ads');
INSERT INTO user_restrictions (user_id, restriction_type) VALUES ('$B_ID','mute');

-- FIX 7/9: B messages in the direct chat and in the private group, plus a poll
-- in the private group (A is NOT a member of the private group).
INSERT INTO messages (chat_id, sender_id, message_type, content) VALUES
  ('$DIRECT_CHAT','$B_ID','text','report-me'),
  ('$PRIVATE_CHAT','$B_ID','text','report-me-private'),
  ('$PRIVATE_CHAT','$B_ID','poll','p2');
INSERT INTO polls (message_id, question, allows_multiple) VALUES
  ((SELECT id FROM messages WHERE chat_id='$PRIVATE_CHAT' AND message_type='poll' LIMIT 1),'p2?',FALSE);
INSERT INTO poll_options (poll_id, position, text) VALUES
  ((SELECT id FROM polls WHERE question='p2?' LIMIT 1),0,'x');

-- FIX 8: B close_friends + nobody stories (must never leak to A)
INSERT INTO stories (user_id, media_id, caption, privacy) VALUES
  ('$B_ID','$B_MEDIA','B close','close_friends'),
  ('$B_ID','$B_MEDIA','B nobody','nobody');
" && echo "fix fixtures OK"

M1=$(sql_scalar "SELECT id FROM messages WHERE content='report-me' AND chat_id='$DIRECT_CHAT';")
M2=$(sql_scalar "SELECT id FROM messages WHERE content='report-me-private' AND chat_id='$PRIVATE_CHAT';")
PRIV_POLL=$(sql_scalar "SELECT id FROM polls WHERE question='p2?';")
PRIV_OPTION=$(sql_scalar "SELECT id FROM poll_options WHERE poll_id='$PRIV_POLL' LIMIT 1;")
DIRECT_OPTION=$(sql_scalar "SELECT id FROM poll_options WHERE poll_id=(SELECT id FROM polls WHERE question='best?' LIMIT 1) LIMIT 1;")
CLOSE_STORY=$(sql_scalar "SELECT id FROM stories WHERE caption='B close';")

# ============================================================================
# TESTS
# ============================================================================

echo ""
echo "=== Running tests ==="

# --- T1: A cannot read B private data ------------------------------------
if blocked_as_user "$A_ID" "SELECT * FROM user_identities WHERE user_id = '$B_ID';"; then
  report "T1 User A cannot read User B private data (identities)" PASS
else
  report "T1 User A cannot read User B private data (identities)" FAIL
fi

# --- T2: A cannot modify B data ------------------------------------------
if blocked_as_user "$A_ID" "UPDATE user_identities SET email_verified=TRUE WHERE user_id='$B_ID';"; then
  report "T2 User A cannot modify User B data" PASS
else
  report "T2 User A cannot modify User B data" FAIL
fi

# --- T3: A cannot join arbitrary private chat ----------------------------
if blocked_as_user "$A_ID" "INSERT INTO chat_members (chat_id, user_id, role) VALUES ('$PRIVATE_CHAT','$A_ID','member');"; then
  report "T3 User A cannot join arbitrary private chats" PASS
else
  report "T3 User A cannot join arbitrary private chats" FAIL
fi

# --- T4: A cannot promote themselves -------------------------------------
if blocked_as_user "$A_ID" "INSERT INTO chat_members (chat_id, user_id, role) VALUES ('$PRIVATE_CHAT','$A_ID','owner');"; then
  report "T4a User A cannot insert self as owner" PASS
else
  report "T4a User A cannot insert self as owner" FAIL
fi
if blocked_as_user "$A_ID" "UPDATE chat_members SET role='owner' WHERE chat_id='$DIRECT_CHAT' AND user_id='$A_ID';"; then
  report "T4b User A cannot self-promote to owner" PASS
else
  report "T4b User A cannot self-promote to owner" FAIL
fi

# --- T5: A cannot manipulate B read state --------------------------------
if blocked_as_user "$A_ID" "UPDATE chat_read_state SET unread_count=0 WHERE user_id='$B_ID';"; then
  report "T5 User A cannot manipulate User B read state" PASS
else
  report "T5 User A cannot manipulate User B read state" FAIL
fi

# --- T6: A cannot search/read private chats they are not in --------------
res=$(result_as_user "$A_ID" "SELECT count(*) FROM search_messages_in_chat('$PRIVATE_CHAT','hello');")
if [ "$res" = "0" ] || [ -z "$res" ]; then
  report "T6 User A cannot search private chats they are not members of" PASS
else
  report "T6 User A cannot search private chats they are not members of" FAIL
fi

# --- T7: A cannot attach B's private media -------------------------------
# First A sends a message in the direct chat, then tries to attach B's media
as_user "$A_ID" "INSERT INTO messages (chat_id, sender_id, message_type, content) VALUES ('$DIRECT_CHAT','$A_ID','text','hello') RETURNING id;" > /dev/null 2>&1
if blocked_as_user "$A_ID" "INSERT INTO message_attachments (message_id, media_id) VALUES ((SELECT id FROM messages WHERE chat_id='$DIRECT_CHAT' AND sender_id='$A_ID' LIMIT 1), '$B_MEDIA');"; then
  report "T7 User A cannot attach User B's private media" PASS
else
  report "T7 User A cannot attach User B's private media" FAIL
fi

# --- T8: User cannot mark OTP verified without valid flow ----------------
if blocked_as_user "$A_ID" "INSERT INTO user_identities (user_id, provider, provider_user_id, email, email_verified) VALUES ('$A_ID','email','a2@test.com','a2@test.com',TRUE);"; then
  report "T8a User cannot create identity pre-verified" PASS
else
  report "T8a User cannot create identity pre-verified" FAIL
fi
if blocked_as_user "$A_ID" "UPDATE user_identities SET email_verified=TRUE WHERE user_id='$A_ID' AND provider='email';"; then
  report "T8b User cannot self-verify email via UPDATE" PASS
else
  report "T8b User cannot self-verify email via UPDATE" FAIL
fi
if blocked_as_user "$A_ID" "INSERT INTO email_verifications (user_id, email, purpose, code_hash, expires_at) VALUES ('$A_ID','a@test.com','login','x',NOW()+interval '10 minutes');"; then
  report "T8c User cannot self-issue OTP" PASS
else
  report "T8c User cannot self-issue OTP" FAIL
fi

# --- T9: User cannot arbitrarily create/modify sessions ------------------
if blocked_as_user "$A_ID" "INSERT INTO sessions (user_id, expires_at) VALUES ('$A_ID', NOW()+interval '1000 years');"; then
  report "T9a User cannot create arbitrary sessions" PASS
else
  report "T9a User cannot create arbitrary sessions" FAIL
fi
if blocked_as_user "$A_ID" "UPDATE sessions SET revoked_at=NULL, expires_at=NOW()+interval '1000 years' WHERE user_id='$A_ID';"; then
  report "T9b User cannot extend/revoke sessions" PASS
else
  report "T9b User cannot extend/revoke sessions" FAIL
fi

# --- T10: Non-admin cannot perform admin operations ----------------------
if blocked_as_user "$A_ID" "INSERT INTO admin_actions (admin_user_id, action) VALUES ((SELECT id FROM admin_users LIMIT 1),'user.suspend');"; then
  report "T10 User A cannot create admin action records" PASS
else
  report "T10 User A cannot create admin action records" FAIL
fi
if blocked_as_user "$A_ID" "INSERT INTO premium_entitlements (user_id, feature) VALUES ('$A_ID','no_ads');"; then
  report "T10b User A cannot grant premium to self" PASS
else
  report "T10b User A cannot grant premium to self" FAIL
fi
if blocked_as_user "$A_ID" "UPDATE premium_requests SET status='approved' WHERE user_id='$A_ID';"; then
  report "T10c User cannot approve own premium request" PASS
else
  report "T10c User cannot approve own premium request" FAIL
fi

# --- T11: Admin audit records are protected ------------------------------
# admin_actions + audit_logs were created in fixtures; now attempt forgery
if blocked_as_user "$ADMIN_ID" "UPDATE audit_logs SET metadata='{\"forged\":true}' WHERE user_id='$ADMIN_ID';"; then
  report "T11 Audit logs are append-only (no UPDATE forgery)" PASS
else
  report "T11 Audit logs are append-only (no UPDATE forgery)" FAIL
fi
if blocked_as_user "$A_ID" "UPDATE admin_actions SET new_state='{\"forged\":true}';"; then
  report "T11b Non-admin cannot forge admin action history" PASS
else
  report "T11b Non-admin cannot forge admin action history" FAIL
fi

# --- T12: Duplicate message retry does not duplicate ---------------------
id1=$(result_as_user "$A_ID" "SELECT send_text_message('$DIRECT_CHAT','client-1','hello');")
id2=$(result_as_user "$A_ID" "SELECT send_text_message('$DIRECT_CHAT','client-1','hello edited');")
cnt=$(result_as_user "$A_ID" "SELECT count(*) FROM messages WHERE client_message_id='client-1';")
if [ "$id1" = "$id2" ] && [ "$cnt" = "1" ]; then
  report "T12 Duplicate message retry returns same id, no duplicate row" PASS
else
  report "T12 Duplicate message retry returns same id, no duplicate row (id1=$id1 id2=$id2 cnt=$cnt)" FAIL
fi

# --- T13: Constraints enforce concurrency invariants ---------------------
# a) Story view: first view allowed, duplicate blocked by unique constraint
r1=$(result_as_user "$A_ID" "INSERT INTO story_views (story_id, viewer_id) VALUES ((SELECT id FROM stories LIMIT 1), '$A_ID') RETURNING viewer_id;")
if [ "$r1" = "$A_ID" ]; then
  if blocked_as_user "$A_ID" "INSERT INTO story_views (story_id, viewer_id) VALUES ((SELECT id FROM stories LIMIT 1), '$A_ID');"; then
    report "T13a Story view uniqueness enforced (no duplicate views)" PASS
  else
    report "T13a Story view uniqueness enforced (no duplicate views)" FAIL
  fi
else
  report "T13a Story view uniqueness enforced (no duplicate views)" FAIL
fi

# b) Direct chat pair uniqueness - concurrent creation
for i in 1 2 3 4 5; do
  ( result_as_user "$A_ID" "SELECT get_or_create_direct_chat('$A_ID','$ADMIN_ID');" > /dev/null 2>&1 ) &
done
wait
dc_cnt=$(sql_scalar "SELECT count(*) FROM direct_chats WHERE (user_a_id='$A_ID' AND user_b_id='$ADMIN_ID') OR (user_a_id='$ADMIN_ID' AND user_b_id='$A_ID');")
if [ "$dc_cnt" = "1" ]; then
  report "T13b Concurrent get_or_create_direct_chat creates exactly one chat" PASS
else
  report "T13b Concurrent get_or_create_direct_chat creates exactly one chat (got $dc_cnt)" FAIL
fi

# c) Single-choice poll: first vote allowed, second vote blocked
r1=$(result_as_user "$B_ID" "INSERT INTO poll_votes (poll_id, option_id, user_id) VALUES ((SELECT id FROM polls LIMIT 1), (SELECT id FROM poll_options WHERE poll_id=(SELECT id FROM polls LIMIT 1) LIMIT 1), '$B_ID') RETURNING user_id;")
if [ "$r1" = "$B_ID" ]; then
  if blocked_as_user "$B_ID" "INSERT INTO poll_votes (poll_id, option_id, user_id) VALUES ((SELECT id FROM polls LIMIT 1), (SELECT id FROM poll_options WHERE poll_id=(SELECT id FROM polls LIMIT 1) LIMIT 1), '$B_ID');"; then
    report "T13c Single-choice poll double vote blocked" PASS
  else
    report "T13c Single-choice poll double vote blocked" FAIL
  fi
else
  report "T13c Single-choice poll double vote blocked" FAIL
fi

# ============================================================================
# FIX 1: ATOMIC GROUP CHAT CREATION (021)
# ============================================================================

# --- T14a: create_group_chat atomically creates chat + owner + perms + read-state
gid=$(result_as_user "$A_ID" "SELECT id FROM create_group_chat('Test Group'::text, FALSE::boolean);")
atomic=$(sql_scalar "SELECT (SELECT count(*) FROM chats WHERE id='$gid' AND owner_id='$A_ID' AND type='group' AND deleted_at IS NULL)||':'||(SELECT count(*) FROM chat_members WHERE chat_id='$gid' AND user_id='$A_ID' AND role='owner' AND left_at IS NULL)||':'||(SELECT count(*) FROM chat_permissions WHERE chat_id='$gid')||':'||(SELECT count(*) FROM chat_read_state WHERE chat_id='$gid' AND user_id='$A_ID');")
if [ -n "$gid" ] && [ "$atomic" = "1:1:1:1" ]; then
  report "T14a create_group_chat is atomic (chat+owner+perms+read_state)" PASS
else
  report "T14a create_group_chat is atomic (chat+owner+perms+read_state) (gid=$gid atomic=$atomic)" FAIL
fi

# --- T14b: client cannot orphan a chat via direct INSERT INTO chats
if blocked_as_user "$A_ID" "INSERT INTO chats (type, title, owner_id, is_public) VALUES ('group','hax','$A_ID',FALSE);"; then
  report "T14b Client cannot direct-INSERT chats (no orphan path)" PASS
else
  report "T14b Client cannot direct-INSERT chats (no orphan path)" FAIL
fi

# --- T14c: only group/channel allowed via create_group_chat
if blocked_as_user "$A_ID" "SELECT id FROM create_group_chat('X'::text, FALSE::boolean, 'direct');"; then
  report "T14c create_group_chat rejects 'direct' type" PASS
else
  report "T14c create_group_chat rejects 'direct' type" FAIL
fi

# --- T14d: channels must be public
if blocked_as_user "$A_ID" "SELECT id FROM create_group_chat('Chan'::text, FALSE::boolean, 'channel');"; then
  report "T14d create_group_chat requires channels to be public" PASS
else
  report "T14d create_group_chat requires channels to be public" FAIL
fi

# --- T14e: empty title rejected
if blocked_as_user "$A_ID" "SELECT id FROM create_group_chat(''::text, FALSE::boolean, 'group');"; then
  report "T14e create_group_chat rejects empty title" PASS
else
  report "T14e create_group_chat rejects empty title" FAIL
fi

# ============================================================================
# FIX 2: REMOVED MEMBER CANNOT SELF-REJOIN (021)
# ============================================================================

# --- T15a: B voluntarily leaves the private group
r=$(result_as_user "$B_ID" "SELECT leave_chat('$PRIVATE_CHAT');")
left=$(sql_scalar "SELECT (left_at IS NOT NULL)::text FROM chat_members WHERE chat_id='$PRIVATE_CHAT' AND user_id='$B_ID';")
if [ "$r" = "t" ] && [ "$left" = "true" ]; then
  report "T15a leave_chat works for a member (left_at set)" PASS
else
  report "T15a leave_chat works for a member (r=$r left=$left)" FAIL
fi

# --- T15b: B cannot flip left_at = NULL to self-rejoin
if blocked_as_user "$B_ID" "UPDATE chat_members SET left_at=NULL WHERE chat_id='$PRIVATE_CHAT' AND user_id='$B_ID';"; then
  report "T15b Removed user cannot self-rejoin via left_at UPDATE" PASS
else
  report "T15b Removed user cannot self-rejoin via left_at UPDATE" FAIL
fi

# --- T15c: B cannot rejoin via direct INSERT either
if blocked_as_user "$B_ID" "INSERT INTO chat_members (chat_id, user_id, role) VALUES ('$PRIVATE_CHAT','$B_ID','member');"; then
  report "T15c Removed user cannot rejoin via INSERT" PASS
else
  report "T15c Removed user cannot rejoin via INSERT" FAIL
fi

# --- T15d: the last owner cannot abandon the chat
if blocked_as_user "$ADMIN_ID" "SELECT leave_chat('$PRIVATE_CHAT');"; then
  report "T15d Last owner cannot leave (ownership must transfer first)" PASS
else
  report "T15d Last owner cannot leave (ownership must transfer first)" FAIL
fi

# --- T15e: re-admission is a privileged action (admin re-adds B)
r=$(result_as_user "$ADMIN_ID" "SELECT add_chat_member('$PRIVATE_CHAT','$B_ID');")
active=$(sql_scalar "SELECT count(*) FROM chat_members WHERE chat_id='$PRIVATE_CHAT' AND left_at IS NULL;")
if [ -n "$r" ] && [ "$active" = "2" ]; then
  report "T15e Admin can re-add a removed member (add_chat_member)" PASS
else
  report "T15e Admin can re-add a removed member (r=$r active=$active)" FAIL
fi

# ============================================================================
# FIX 4: IDEMPOTENT SEND MUST NOT MUTATE (022)
# ============================================================================

# --- T16: retry with the same client_message_id never rewrites content
id1=$(result_as_user "$A_ID" "SELECT send_text_message('$DIRECT_CHAT','client-3','original');")
id2=$(result_as_user "$A_ID" "SELECT send_text_message('$DIRECT_CHAT','client-3','changed');")
check=$(sql_scalar "SELECT content || '|' || (edited_at IS NULL)::text FROM messages WHERE id='$id1';")
if [ "$id1" = "$id2" ] && [ "$check" = "original|true" ]; then
  report "T16 Retry with different content returns same id WITHOUT mutating message" PASS
else
  report "T16 Retry with different content returns same id WITHOUT mutating message (id1=$id1 id2=$id2 check=$check)" FAIL
fi

# ============================================================================
# FIX 5: TAMPER-PROOF EDIT HISTORY (022)
# ============================================================================

A_MSG=$(sql_scalar "SELECT id FROM messages WHERE client_message_id='client-3';")

# --- T17a: author can edit via RPC; history records the previous content
r=$(result_as_user "$A_ID" "SELECT edit_message('$A_MSG','edited-content');")
content=$(sql_scalar "SELECT content FROM messages WHERE id='$A_MSG';")
edited=$(sql_scalar "SELECT (edited_at IS NOT NULL)::text FROM messages WHERE id='$A_MSG';")
hist=$(sql_scalar "SELECT previous_content FROM message_edits WHERE message_id='$A_MSG' ORDER BY edit_sequence DESC LIMIT 1;")
if [ "$r" = "$A_MSG" ] && [ "$content" = "edited-content" ] && [ "$edited" = "true" ] && [ "$hist" = "original" ]; then
  report "T17a edit_message() updates content + appends history" PASS
else
  report "T17a edit_message() updates content + appends history (r=$r content=$content edited=$edited hist=$hist)" FAIL
fi

# --- T17b: non-author cannot edit
if blocked_as_user "$B_ID" "SELECT edit_message('$A_MSG','hijack');"; then
  report "T17b Non-author cannot edit a message" PASS
else
  report "T17b Non-author cannot edit a message" FAIL
fi

# --- T17c: no direct INSERT into message_edits (no fabricated history)
if blocked_as_user "$A_ID" "INSERT INTO message_edits (message_id, previous_content) VALUES ('$A_MSG','forged');"; then
  report "T17c Client cannot INSERT fabricated edit history" PASS
else
  report "T17c Client cannot INSERT fabricated edit history" FAIL
fi

# --- T17d: no direct content UPDATE bypassing the RPC
if blocked_as_user "$A_ID" "UPDATE messages SET content='direct-edit' WHERE id='$A_MSG';"; then
  report "T17d Client cannot UPDATE content directly (edit via RPC only)" PASS
else
  report "T17d Client cannot UPDATE content directly (edit via RPC only)" FAIL
fi

# --- T17e: editing to the same content is a no-op (no extra history row)
before=$(sql_scalar "SELECT count(*) FROM message_edits WHERE message_id='$A_MSG';")
result_as_user "$A_ID" "SELECT edit_message('$A_MSG','edited-content');" > /dev/null 2>&1
after=$(sql_scalar "SELECT count(*) FROM message_edits WHERE message_id='$A_MSG';")
if [ "$before" = "1" ] && [ "$after" = "1" ]; then
  report "T17e Idempotent edit appends no duplicate history row" PASS
else
  report "T17e Idempotent edit appends no duplicate history row (before=$before after=$after)" FAIL
fi

# ============================================================================
# FIX 6: DIRECT CHAT CREATION ONLY VIA get_or_create_direct_chat() (021)
# ============================================================================

# --- T18a: client cannot INSERT into direct_chats
if blocked_as_user "$A_ID" "INSERT INTO direct_chats (chat_id, user_a_id, user_b_id) VALUES ('00000000-0000-0000-0000-000000000001','$A_ID','$ADMIN_ID');"; then
  report "T18a Client cannot direct-INSERT direct_chats" PASS
else
  report "T18a Client cannot direct-INSERT direct_chats" FAIL
fi

# --- T18b: client cannot UPDATE direct_chats
if blocked_as_user "$A_ID" "UPDATE direct_chats SET user_b_id='$ADMIN_ID' WHERE chat_id='$DIRECT_CHAT';"; then
  report "T18b Client cannot UPDATE direct_chats" PASS
else
  report "T18b Client cannot UPDATE direct_chats" FAIL
fi

# ============================================================================
# FIX 7: MESSAGE REPORT INTEGRITY (023)
# ============================================================================

# --- T19a: a valid report (member reports another member's message) succeeds
r=$(result_as_user "$A_ID" "INSERT INTO message_reports (reporter_id, message_id, chat_id, reason) VALUES ('$A_ID','$M1','$DIRECT_CHAT','spam') RETURNING id;")
if [ -n "$r" ]; then
  report "T19a Valid message report is accepted" PASS
else
  report "T19a Valid message report is accepted" FAIL
fi

# --- T19b: reporting a nonexistent message is blocked
if blocked_as_user "$A_ID" "INSERT INTO message_reports (reporter_id, message_id, chat_id, reason) VALUES ('$A_ID','00000000-0000-0000-0000-000000000000','$DIRECT_CHAT','spam');"; then
  report "T19b Report of nonexistent message blocked" PASS
else
  report "T19b Report of nonexistent message blocked" FAIL
fi

# --- T19c: reporting a message with a mismatched chat is blocked
if blocked_as_user "$A_ID" "INSERT INTO message_reports (reporter_id, message_id, chat_id, reason) VALUES ('$A_ID','$M2','$DIRECT_CHAT','spam');"; then
  report "T19c Report with mismatched chat_id blocked" PASS
else
  report "T19c Report with mismatched chat_id blocked" FAIL
fi

# --- T19d: self-reporting is blocked
if blocked_as_user "$A_ID" "INSERT INTO message_reports (reporter_id, message_id, chat_id, reason) VALUES ('$A_ID','$A_MSG','$DIRECT_CHAT','spam');"; then
  report "T19d Self-report blocked" PASS
else
  report "T19d Self-report blocked" FAIL
fi

# --- T19e: non-member cannot report in a chat they are not in
if blocked_as_user "$A_ID" "INSERT INTO message_reports (reporter_id, message_id, chat_id, reason) VALUES ('$A_ID','$M2','$PRIVATE_CHAT','spam');"; then
  report "T19e Non-member cannot report in foreign chat" PASS
else
  report "T19e Non-member cannot report in foreign chat" FAIL
fi

# --- T19f: duplicate report by the same reporter is blocked (unique)
if blocked_as_user "$A_ID" "INSERT INTO message_reports (reporter_id, message_id, chat_id, reason) VALUES ('$A_ID','$M1','$DIRECT_CHAT','spam');"; then
  report "T19f Duplicate report blocked (unique per reporter/message)" PASS
else
  report "T19f Duplicate report blocked (unique per reporter/message)" FAIL
fi

# --- T19g: reports are append-only for clients (no UPDATE)
if blocked_as_user "$A_ID" "UPDATE message_reports SET status='resolved_no_action' WHERE message_id='$M1';"; then
  report "T19g Client cannot UPDATE a report" PASS
else
  report "T19g Client cannot UPDATE a report" FAIL
fi

# ============================================================================
# FIX 3: NO PUBLIC EXECUTE ON SECURITY DEFINER / PRIVACY HELPERS (024)
# ============================================================================

# --- T20a: anon cannot probe chat membership
if blocked_as_role anon "SELECT is_chat_member('$DIRECT_CHAT');"; then
  report "T20a anon cannot call is_chat_member()" PASS
else
  report "T20a anon cannot call is_chat_member()" FAIL
fi

# --- T20b: anon cannot probe story visibility
if blocked_as_role anon "SELECT can_view_story('$CLOSE_STORY');"; then
  report "T20b anon cannot call can_view_story()" PASS
else
  report "T20b anon cannot call can_view_story()" FAIL
fi

# --- T20c: authenticated users still can (policy evaluation depends on it)
r=$(result_as_user "$A_ID" "SELECT is_chat_member('$DIRECT_CHAT');")
if [ "$r" = "t" ]; then
  report "T20c authenticated can still call is_chat_member()" PASS
else
  report "T20c authenticated can still call is_chat_member() (r=$r)" FAIL
fi

# --- T20d/e: anon cannot call the remaining SECURITY DEFINER RPCs either
ok=1
blocked_as_role anon "SELECT add_chat_member('$DIRECT_CHAT','$A_ID');" || ok=0
blocked_as_role anon "SELECT get_or_create_direct_chat('$A_ID','$ADMIN_ID');" || ok=0
blocked_as_role anon "SELECT verify_email_code('00000000-0000-0000-0000-000000000000','x');" || ok=0
if [ "$ok" = "1" ]; then
  report "T20d anon cannot call SECURITY DEFINER RPCs (add/get_dc/verify_email)" PASS
else
  report "T20d anon cannot call SECURITY DEFINER RPCs (add/get_dc/verify_email)" FAIL
fi

# ============================================================================
# FIX 8: close_friends / nobody STORIES NEVER LEAK (024)
# ============================================================================

# --- T21a/b/c/d: A cannot see close_friends or nobody stories; B (owner) can
cnt=$(result_as_user "$A_ID" "SELECT count(*) FROM stories WHERE caption='B close';")
if [ "$cnt" = "0" ]; then
  report "T21a A cannot view B close_friends story" PASS
else
  report "T21a A cannot view B close_friends story (cnt=$cnt)" FAIL
fi
cnt=$(result_as_user "$A_ID" "SELECT count(*) FROM stories WHERE caption='B nobody';")
if [ "$cnt" = "0" ]; then
  report "T21b A cannot view B nobody story" PASS
else
  report "T21b A cannot view B nobody story (cnt=$cnt)" FAIL
fi
r=$(result_as_user "$A_ID" "SELECT can_view_story('$CLOSE_STORY');")
if [ "$r" = "f" ]; then
  report "T21c can_view_story() fails closed for close_friends" PASS
else
  report "T21c can_view_story() fails closed for close_friends (r=$r)" FAIL
fi
cnt=$(result_as_user "$B_ID" "SELECT count(*) FROM stories WHERE caption='B close';")
if [ "$cnt" = "1" ]; then
  report "T21d Owner can still view their own close_friends story" PASS
else
  report "T21d Owner can still view their own close_friends story (cnt=$cnt)" FAIL
fi

# ============================================================================
# FIX 9: INFORMATION-DISCLOSURE RPC HARDENING (024)
# ============================================================================

# B votes in the private-group poll (A is NOT a member of that group).
result_as_user "$B_ID" "INSERT INTO poll_votes (poll_id, option_id, user_id) VALUES ('$PRIV_POLL','$PRIV_OPTION','$B_ID');" > /dev/null 2>&1

# --- T22a: non-member sees 0 for a private poll vote count
r=$(result_as_user "$A_ID" "SELECT get_poll_option_vote_count('$PRIV_OPTION');")
if [ "$r" = "0" ]; then
  report "T22a Non-member cannot read poll vote counts" PASS
else
  report "T22a Non-member cannot read poll vote counts (r=$r)" FAIL
fi

# --- T22b: member sees the real count
r=$(result_as_user "$B_ID" "SELECT get_poll_option_vote_count('$PRIV_OPTION');")
if [ "$r" = "1" ]; then
  report "T22b Member can read poll vote counts" PASS
else
  report "T22b Member can read poll vote counts (r=$r)" FAIL
fi

# --- T22c: premium entitlement is self-scoped
r_a=$(result_as_user "$A_ID" "SELECT user_has_premium_feature('$B_ID','no_ads');")
r_b=$(result_as_user "$B_ID" "SELECT user_has_premium_feature('$B_ID','no_ads');")
if [ "$r_a" = "f" ] && [ "$r_b" = "t" ]; then
  report "T22c Premium entitlement status is self-scoped" PASS
else
  report "T22c Premium entitlement status is self-scoped (A=$r_a B=$r_b)" FAIL
fi

# --- T22d: restriction status is self-scoped
r_a=$(result_as_user "$A_ID" "SELECT user_has_active_restriction('$B_ID','mute');")
r_b=$(result_as_user "$B_ID" "SELECT user_has_active_restriction('$B_ID','mute');")
if [ "$r_a" = "f" ] && [ "$r_b" = "t" ]; then
  report "T22d Restriction status is self-scoped" PASS
else
  report "T22d Restriction status is self-scoped (A=$r_a B=$r_b)" FAIL
fi

# --- T22e: message deletion status is self-scoped (B deletes own view)
result_as_user "$B_ID" "INSERT INTO message_deletions (message_id, user_id, is_for_everyone) VALUES ('$M1','$B_ID',FALSE);" > /dev/null 2>&1
r2=$(result_as_user "$A_ID" "SELECT message_is_deleted_for_user('$M1','$B_ID');")
r3=$(result_as_user "$B_ID" "SELECT message_is_deleted_for_user('$M1','$B_ID');")
if [ "$r2" = "f" ] && [ "$r3" = "t" ]; then
  report "T22e Message deletion status is self-scoped" PASS
else
  report "T22e Message deletion status is self-scoped (A-query=$r2 B-query=$r3)" FAIL
fi

# --- T22f: anon cannot call summary / unread / maintenance functions
ok=1
blocked_as_role anon "SELECT get_user_chat_summary('$A_ID');" || ok=0
blocked_as_role anon "SELECT get_user_unread_count('$A_ID');" || ok=0
blocked_as_role anon "SELECT cleanup_expired_email_verifications();" || ok=0
blocked_as_role anon "SELECT expire_user_restrictions();" || ok=0
if [ "$ok" = "1" ]; then
  report "T22f anon cannot call summary/unread/maintenance functions" PASS
else
  report "T22f anon cannot call summary/unread/maintenance functions" FAIL
fi

# --- T22g: refresh-token probing is service_role-only (authenticated blocked)
if blocked_as_user "$A_ID" "SELECT detect_refresh_token_reuse('deadbeef','00000000-0000-0000-0000-000000000000');"; then
  report "T22g authenticated cannot probe refresh-token reuse" PASS
else
  report "T22g authenticated cannot probe refresh-token reuse" FAIL
fi

# --- T22h: maintenance functions still run as service_role
out=$(as_role service_role "SELECT cleanup_expired_email_verifications(); SELECT expire_user_restrictions();")
if ! echo "$out" | grep -qi "ERROR"; then
  report "T22h maintenance functions still run as service_role" PASS
else
  report "T22h maintenance functions still run as service_role" FAIL
fi

# ============================================================================
echo ""
echo "=========================================="
echo "RESULTS: $PASS passed, $FAIL failed"
if [ ${#FAILED_TESTS[@]} -gt 0 ]; then
  echo "FAILED:"
  printf '  - %s\n' "${FAILED_TESTS[@]}"
fi
echo "=========================================="

exit $FAIL
