#!/usr/bin/env bash
# ============================================================================
# CREANGER ADMIN CONTROL PLANE TEST SUITE (Migration 032)
# ============================================================================
# Verifies the admin RPC boundary from the perspective of multiple actors:
#   A         - normal authenticated user (must be fully blocked)
#   ADMIN     - super_admin (full control)
#   MOD       - moderator        (users.read/write + users.suspend +
#                                 moderation.read/write per seeded permissions;
#                                 NO audit.read)
#   READONLY  - readonly         (users.read + moderation.read + audit.read,
#                                 NO write permission)
#   INACTIVE  - super_admin with is_active=FALSE (must be blocked)
#
# All RPC side-effects are asserted via service-role psql (superuser), because
# the tables the RPCs write to (users, sessions, reports, ...) are RLS-scoped
# to their owners and are correctly NOT readable by an admin's own JWT.
#
# Prereqs: a running PostgreSQL on localhost:5433 (user postgres / postgres,
# db creanger) with migrations 001-032 applied.
#
# Usage: bash tests/run_admin_tests.sh
# ============================================================================

set -u
PSVC="docker exec -i creanger-test psql -U postgres -d ${TEST_DB:-creanger} -tA"
PSQL="docker exec -i creanger-test psql -U postgres -d ${TEST_DB:-creanger} -tA -v ON_ERROR_STOP=0"
PASS=0
FAIL=0
FAILED_TESTS=()

A_ID="aaaaaaaa-1111-1111-1111-111111111111"
B_ID="bbbbbbbb-2222-2222-2222-222222222222"
ADMIN_ID="cccccccc-3333-3333-3333-333333333333"
MOD_ID="dddddddd-4444-4444-4444-444444444444"
RO_ID="eeeeeeee-5555-5555-5555-555555555555"
INACTIVE_ID="ffffffff-6666-6666-6666-666666666666"
DIRECT_CHAT="11111111-1111-1111-1111-111111111111"
PRIVATE_CHAT="99999999-9999-9999-9999-999999999999"
B_MEDIA="bbbbbbbb-4444-4444-4444-444444444444"
SESS_A="22222222-2222-2222-2222-222222222222"
SESS_B="33333333-3333-3333-3333-333333333333"
M1="44444444-4444-4444-4444-444444444444"
M2="55555555-5555-5555-5555-555555555555"
BADID="00000000-0000-0000-0000-000000000000"

# ---------------------------------------------------------------------------
# Helpers (same conventions as run_security_tests.sh)
# ---------------------------------------------------------------------------

sql() { echo "$1" | $PSQL > /dev/null 2>&1; return $?; }

# svc_scalar <sql> - single scalar result as postgres (service role / superuser)
svc_scalar() { echo "$1" | $PSVC | tail -n +1 | head -1; }

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

strip_marker() {
  echo "$1" | awk 'NR<=3{next} /__AS_USER_END__/{exit} {print}'
}

result_as_user() { strip_marker "$(as_user "$1" "$2")" | head -1; }

blocked_as_user() {
  local out res
  out=$(as_user "$1" "$2")
  if echo "$out" | grep -qiE "ERROR|permission denied|violates row-level security"; then
    return 0
  fi
  res=$(strip_marker "$out" | head -1 | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')
  if [ -z "$res" ]; then return 0; fi
  if echo "$res" | grep -qiE "^(0|INSERT 0 0|UPDATE 0|DELETE 0)$"; then return 0; fi
  return 1
}

report() {
  local name="$1" ok="$2"
  if [ "$ok" = "PASS" ]; then
    PASS=$((PASS+1)); echo "PASS  - $name"
  else
    FAIL=$((FAIL+1)); FAILED_TESTS+=("$name"); echo "FAIL  - $name"
  fi
}

expect_str() { report "$1" "$( [ "$2" = "$3" ] && echo PASS || echo FAIL )"; }

# ============================================================================
# FIXTURES (service role)
# ============================================================================
echo "=== Setting up admin fixtures ==="
sql "
DELETE FROM message_reports; DELETE FROM user_reports; DELETE FROM moderation_actions;
DELETE FROM user_restrictions; DELETE FROM admin_actions; DELETE FROM audit_logs;
DELETE FROM message_deletions; DELETE FROM messages; DELETE FROM media; DELETE FROM stories;
DELETE FROM message_attachments; DELETE FROM chat_read_state; DELETE FROM chat_members;
DELETE FROM chat_permissions; DELETE FROM direct_chats; DELETE FROM chats;
DELETE FROM refresh_tokens; DELETE FROM sessions; DELETE FROM devices;
DELETE FROM premium_entitlements; DELETE FROM admin_users; DELETE FROM user_identities;
DELETE FROM privacy_settings; DELETE FROM profiles; DELETE FROM users;
" && echo "clean OK"

sql "
INSERT INTO users (id, status) VALUES
  ('$A_ID','active'), ('$B_ID','active'), ('$ADMIN_ID','active'),
  ('$MOD_ID','active'), ('$RO_ID','active'), ('$INACTIVE_ID','active');
INSERT INTO profiles (user_id, username, first_name) VALUES
  ('$A_ID','usera','UserA'), ('$B_ID','userb','UserB'), ('$ADMIN_ID','admin','Admin'),
  ('$MOD_ID','modo','Moderator'), ('$RO_ID','roro','ReadOnly'), ('$INACTIVE_ID','inact','Inactive');
INSERT INTO privacy_settings (user_id) SELECT id FROM users;
INSERT INTO user_identities (user_id, provider, provider_user_id, email, email_verified) VALUES
  ('$A_ID','email','a@test.com','a@test.com',TRUE),
  ('$B_ID','email','b@test.com','b@test.com',TRUE);
INSERT INTO admin_users (user_id, role, is_active) VALUES
  ('$ADMIN_ID','super_admin',TRUE),
  ('$MOD_ID','moderator',TRUE),
  ('$RO_ID','readonly',TRUE),
  ('$INACTIVE_ID','super_admin',FALSE);

-- Direct chat A-B
INSERT INTO chats (id, type, owner_id, title) VALUES ('$DIRECT_CHAT','direct',NULL,'A-B');
INSERT INTO direct_chats (chat_id, user_a_id, user_b_id) VALUES ('$DIRECT_CHAT','$A_ID','$B_ID');
INSERT INTO chat_members (chat_id, user_id, role) VALUES
  ('$DIRECT_CHAT','$A_ID','member'), ('$DIRECT_CHAT','$B_ID','member');
INSERT INTO chat_permissions (chat_id) VALUES ('$DIRECT_CHAT');
INSERT INTO chat_read_state (chat_id, user_id) VALUES ('$DIRECT_CHAT','$A_ID'), ('$DIRECT_CHAT','$B_ID');

-- Private group (B member only)
INSERT INTO chats (id, type, title, owner_id, is_public) VALUES ('$PRIVATE_CHAT','group','Secret','$B_ID',FALSE);
INSERT INTO chat_permissions (chat_id) VALUES ('$PRIVATE_CHAT');
INSERT INTO chat_members (chat_id, user_id, role) VALUES ('$PRIVATE_CHAT','$B_ID','owner');
INSERT INTO chat_read_state (chat_id, user_id) VALUES ('$PRIVATE_CHAT','$B_ID');

-- Messages (M1 text in direct chat by A; M2 text in private group by B)
INSERT INTO messages (id, chat_id, sender_id, message_type, content) VALUES
  ('$M1','$DIRECT_CHAT','$A_ID','text','hello from A'),
  ('$M2','$PRIVATE_CHAT','$B_ID','text','secret from B');

-- B media + attachment to M2
INSERT INTO media (id, owner_id, storage_provider, storage_key, mime_type, size_bytes, public_url) VALUES
  ('$B_MEDIA','$B_ID','s3','b/private.jpg','image/jpeg',12345,'https://cdn.example/b/private.jpg');
INSERT INTO message_attachments (message_id, media_id, position) VALUES ('$M2','$B_MEDIA',0);

-- B premium entitlement + active restriction
INSERT INTO premium_entitlements (user_id, feature, is_active) VALUES ('$B_ID','no_ads',TRUE);
INSERT INTO user_restrictions (user_id, restriction_type, reason) VALUES ('$B_ID','mute','noisy');

-- Moderation history (a warn issued by ADMIN)
INSERT INTO moderation_actions (action_type, target_user_id, moderator_id, reason, is_active)
VALUES ('warn','$A_ID','$ADMIN_ID','warning',TRUE);

-- Reports: (user report: B reported A) + (message report: A reported M2)
INSERT INTO user_reports (reporter_id, reported_user_id, reason, description) VALUES
  ('$B_ID','$A_ID','spam','usera spams');
INSERT INTO message_reports (reporter_id, message_id, chat_id, reason, description) VALUES
  ('$B_ID','$M1','$DIRECT_CHAT','harassment','hello from A reported');

-- Sessions + refresh tokens for A and B (+ B device)
INSERT INTO devices (id, user_id, device_identifier, device_name, platform) VALUES
  ('00000000-7777-7777-7777-777777777777','$B_ID','dev-hash-b','B Phone','android');
INSERT INTO sessions (id, user_id, device_id, expires_at) VALUES
  ('$SESS_A','$A_ID',NULL,NOW() + interval '30 days'),
  ('$SESS_B','$B_ID','00000000-7777-7777-7777-777777777777',NOW() + interval '30 days');
INSERT INTO refresh_tokens (session_id, family_id, token_hash, expires_at) VALUES
  ('$SESS_A','77777777-8888-8888-8888-888888888888','hash-a',NOW() + interval '30 days'),
  ('$SESS_B','99999999-aaaa-aaaa-aaaa-aaaaaaaaaaaa','hash-b',NOW() + interval '30 days');

-- Seeded audit trail (one admin_action + one audit_log entry)
INSERT INTO admin_actions (admin_user_id, action, target_type, target_id, metadata)
VALUES ((SELECT id FROM admin_users WHERE user_id='$ADMIN_ID'),'system.fixture','user','$A_ID','{\"seed\":true}');
INSERT INTO audit_logs (user_id, action, actor_type, metadata)
VALUES ('$ADMIN_ID','admin_action','admin','{\"seed\":true}');
" && echo "fixtures OK"

USER_REPORT=$(svc_scalar "SELECT id FROM user_reports WHERE reported_user_id='$A_ID';")
MSG_REPORT=$(svc_scalar "SELECT id FROM message_reports WHERE message_id='$M1';")
echo "user_report=$USER_REPORT msg_report=$MSG_REPORT"

echo ""
echo "=== Running admin tests ==="

# ============================================================================
# 1. NON-ADMIN IS FULLY BLOCKED
# ============================================================================
if blocked_as_user "$A_ID" "SELECT * FROM admin_whoami();"; then report "A cannot call admin_whoami" PASS; else report "A cannot call admin_whoami" FAIL; fi
if blocked_as_user "$A_ID" "SELECT * FROM admin_dashboard_stats();"; then report "A cannot call admin_dashboard_stats" PASS; else report "A cannot call admin_dashboard_stats" FAIL; fi
if blocked_as_user "$A_ID" "SELECT * FROM admin_list_users(NULL,NULL,'created_at','desc',1,25);"; then report "A cannot call admin_list_users" PASS; else report "A cannot call admin_list_users" FAIL; fi
if blocked_as_user "$A_ID" "SELECT * FROM admin_get_user('$B_ID');"; then report "A cannot call admin_get_user" PASS; else report "A cannot call admin_get_user" FAIL; fi
if blocked_as_user "$A_ID" "SELECT * FROM admin_list_reports('all',NULL,1,25);"; then report "A cannot call admin_list_reports" PASS; else report "A cannot call admin_list_reports" FAIL; fi
if blocked_as_user "$A_ID" "SELECT * FROM admin_suspend_user('$B_ID');"; then report "A cannot call admin_suspend_user" PASS; else report "A cannot call admin_suspend_user" FAIL; fi
if blocked_as_user "$A_ID" "SELECT * FROM admin_revoke_session('$SESS_A');"; then report "A cannot call admin_revoke_session" PASS; else report "A cannot call admin_revoke_session" FAIL; fi
if blocked_as_user "$A_ID" "SELECT * FROM admin_delete_message('$M1');"; then report "A cannot call admin_delete_message" PASS; else report "A cannot call admin_delete_message" FAIL; fi
if blocked_as_user "$A_ID" "SELECT * FROM admin_resolve_report('user','$USER_REPORT','dismissed');"; then report "A cannot call admin_resolve_report" PASS; else report "A cannot call admin_resolve_report" FAIL; fi
if blocked_as_user "$A_ID" "SELECT * FROM admin_list_sessions(NULL,1,25);"; then report "A cannot call admin_list_sessions" PASS; else report "A cannot call admin_list_sessions" FAIL; fi
if blocked_as_user "$A_ID" "SELECT * FROM admin_list_audit(NULL,1,25);"; then report "A cannot call admin_list_audit" PASS; else report "A cannot call admin_list_audit" FAIL; fi

# ============================================================================
# 2. ROLE PERMISSION GATING
# ============================================================================
# NOTE: permission strings come from the SEEDED admin_permissions (migration
# 010): moderator HAS users.suspend + moderation.write but NOT audit.read;
# readonly HAS users.read + moderation.read + audit.read but NO write perms.
expect_str "readonly can admin_whoami" "readonly" "$(result_as_user "$RO_ID" "SELECT admin_whoami()->>'role';")"
expect_str "readonly HAS users.read" "6" "$(result_as_user "$RO_ID" "SELECT count(*) FROM admin_list_users(NULL,NULL,'created_at','desc',1,25);")"
expect_str "readonly HAS audit.read" "t" "$(result_as_user "$RO_ID" "SELECT (count(*) > 0);")"
if blocked_as_user "$RO_ID" "SELECT public.admin_require('users.suspend');"; then report "readonly blocked from users.suspend" PASS; else report "readonly blocked from users.suspend" FAIL; fi
if blocked_as_user "$RO_ID" "SELECT public.admin_require('moderation.write');"; then report "readonly blocked from moderation.write" PASS; else report "readonly blocked from moderation.write" FAIL; fi
if blocked_as_user "$RO_ID" "SELECT public.admin_require('system.config');"; then report "readonly blocked from system.config" PASS; else report "readonly blocked from system.config" FAIL; fi
if blocked_as_user "$RO_ID" "SELECT * FROM admin_resolve_report('user','$USER_REPORT','dismissed');"; then report "readonly blocked from resolving reports" PASS; else report "readonly blocked from resolving reports" FAIL; fi
if blocked_as_user "$RO_ID" "SELECT * FROM admin_suspend_user('$B_ID');"; then report "readonly blocked from suspending users" PASS; else report "readonly blocked from suspending users" FAIL; fi
if blocked_as_user "$RO_ID" "SELECT * FROM admin_delete_message('$M1');"; then report "readonly blocked from deleting messages" PASS; else report "readonly blocked from deleting messages" FAIL; fi

expect_str "moderator can list users (users.read)" "6" "$(result_as_user "$MOD_ID" "SELECT count(*) FROM admin_list_users(NULL,NULL,'created_at','desc',1,25);")"
if blocked_as_user "$MOD_ID" "SELECT public.admin_require('audit.read');"; then report "moderator blocked from audit.read (seed)" PASS; else report "moderator blocked from audit.read (seed)" FAIL; fi
expect_str "moderator HAS users.suspend (seed)" "t" "$(result_as_user "$MOD_ID" "SELECT (public.admin_require('users.suspend') IS NOT NULL);")"
expect_str "moderator HAS moderation.write (seed)" "t" "$(result_as_user "$MOD_ID" "SELECT (public.admin_require('moderation.write') IS NOT NULL);")"

if blocked_as_user "$INACTIVE_ID" "SELECT * FROM admin_whoami();"; then report "inactive admin fully blocked" PASS; else report "inactive admin fully blocked" FAIL; fi

# ============================================================================
# 3. SUPER ADMIN READ PATHS
# ============================================================================
expect_str "whoami role" "super_admin" "$(result_as_user "$ADMIN_ID" "SELECT admin_whoami()->>'role';")"
expect_str "whoami permission set non-empty" "9" "$(result_as_user "$ADMIN_ID" "SELECT jsonb_array_length(admin_whoami()->'permissions')::text;")"

expect_str "dashboard total users" "6" "$(result_as_user "$ADMIN_ID" "SELECT (admin_dashboard_stats()->>'total_users');")"
expect_str "dashboard total chats" "2" "$(result_as_user "$ADMIN_ID" "SELECT (admin_dashboard_stats()->>'total_chats');")"
expect_str "dashboard total messages" "2" "$(result_as_user "$ADMIN_ID" "SELECT (admin_dashboard_stats()->>'total_messages');")"

expect_str "list_users total" "6" "$(result_as_user "$ADMIN_ID" "SELECT total::text FROM admin_list_users(NULL,NULL,'created_at','desc',1,25) LIMIT 1;")"
expect_str "list_users search=usera" "1" "$(result_as_user "$ADMIN_ID" "SELECT count(*) FROM admin_list_users('usera',NULL,'created_at','desc',1,25);")"
expect_str "list_users status filter=suspended" "0" "$(result_as_user "$ADMIN_ID" "SELECT count(*) FROM admin_list_users(NULL,'suspended','created_at','desc',1,25);")"

expect_str "get_user counts.active_sessions for B (1 live)" "1" "$(result_as_user "$ADMIN_ID" "SELECT (admin_get_user('$B_ID')->'counts'->>'active_sessions')::text;")"
expect_str "get_user counts.total_sessions for B (1)" "1" "$(result_as_user "$ADMIN_ID" "SELECT (admin_get_user('$B_ID')->'counts'->>'total_sessions')::text;")"
expect_str "get_user profile.username for A" "usera" "$(result_as_user "$ADMIN_ID" "SELECT admin_get_user('$A_ID')->'profile'->>'username';")"
expect_str "get_user exposes admin role of B (non-admin=null)" "" "$(result_as_user "$ADMIN_ID" "SELECT COALESCE(admin_get_user('$B_ID')->'admin'->>'role','');")"
expect_str "get_user restrictions list non-empty for B" "1" "$(result_as_user "$ADMIN_ID" "SELECT jsonb_array_length(admin_get_user('$B_ID')->'restrictions')::text;")"

expect_str "list_chats total" "2" "$(result_as_user "$ADMIN_ID" "SELECT total::text FROM admin_list_chats(NULL,NULL,1,25) LIMIT 1;")"
expect_str "get_chat title (direct)" "A-B" "$(result_as_user "$ADMIN_ID" "SELECT admin_get_chat('$DIRECT_CHAT')->'chat'->>'title';")"
expect_str "get_chat member_count=2" "2" "$(result_as_user "$ADMIN_ID" "SELECT jsonb_array_length(admin_get_chat('$DIRECT_CHAT')->'members')::text;")"
expect_str "get_chat includes reports for DIRECT (1)" "1" "$(result_as_user "$ADMIN_ID" "SELECT jsonb_array_length(admin_get_chat('$DIRECT_CHAT')->'reports')::text;")"

expect_str "list_messages total" "2" "$(result_as_user "$ADMIN_ID" "SELECT total::text FROM admin_list_messages(NULL::uuid,NULL,1,25) LIMIT 1;")"
expect_str "get_message content M1" "hello from A" "$(result_as_user "$ADMIN_ID" "SELECT admin_get_message('$M1')->'message'->>'content';")"
expect_str "get_message attachments on M2" "1" "$(result_as_user "$ADMIN_ID" "SELECT jsonb_array_length(admin_get_message('$M2')->'media')::text;")"

expect_str "list_media total" "1" "$(result_as_user "$ADMIN_ID" "SELECT total::text FROM admin_list_media(NULL,NULL,NULL,1,25) LIMIT 1;")"
expect_str "list_media provider filter s3" "1" "$(result_as_user "$ADMIN_ID" "SELECT count(*) FROM admin_list_media('s3',NULL,NULL,1,25);")"

expect_str "list_reports all=2 (user 1 + message 1)" "2" "$(result_as_user "$ADMIN_ID" "SELECT (admin_list_reports('all',NULL,1,25)->>'total')::text;")"
expect_str "list_reports user=1" "1" "$(result_as_user "$ADMIN_ID" "SELECT (admin_list_reports('user',NULL,1,25)->>'total')::text;")"
expect_str "list_reports message=1" "1" "$(result_as_user "$ADMIN_ID" "SELECT (admin_list_reports('message',NULL,1,25)->>'total')::text;")"
expect_str "list_reports all pending before writes (2)" "2" "$(result_as_user "$ADMIN_ID" "SELECT (admin_list_reports('all','pending',1,25)->>'total')::text;")"

expect_str "list_sessions total" "2" "$(result_as_user "$ADMIN_ID" "SELECT total::text FROM admin_list_sessions(NULL,1,25) LIMIT 1;")"

expect_str "list_audit returns at least seeded rows" "t" "$(result_as_user "$ADMIN_ID" "SELECT (count(*) > 0);")"

expect_str "list_admin_users count" "4" "$(result_as_user "$ADMIN_ID" "SELECT count(*)::text FROM admin_list_admin_users();")"
expect_str "list_moderation_actions count" "1" "$(result_as_user "$ADMIN_ID" "SELECT count(*)::text FROM admin_list_moderation_actions(NULL,1,25);")"

expect_str "search_global 'usera'" "1" "$(result_as_user "$ADMIN_ID" "SELECT count(*)::text FROM admin_search_global('usera',25);")"
expect_str "search_global 'secret'" "1" "$(result_as_user "$ADMIN_ID" "SELECT count(*)::text FROM admin_search_global('secret',25);")"

# ============================================================================
# 4. WRITE PATHS + AUDIT TRAIL
# ============================================================================
# Moderator (has moderation.write) resolves the user report first.
result_as_user "$MOD_ID" "SELECT admin_resolve_report('user','$USER_REPORT','resolved_no_action','ok');" > /dev/null
expect_str "moderator resolve -> resolved_no_action" "resolved_no_action" "$(svc_scalar "SELECT status FROM user_reports WHERE id='$USER_REPORT';")"

# Super admin reopens + resolves with action taken.
result_as_user "$ADMIN_ID" "SELECT admin_reopen_report('user','$USER_REPORT');" > /dev/null
expect_str "reopen -> under_review" "under_review" "$(svc_scalar "SELECT status FROM user_reports WHERE id='$USER_REPORT';")"
expect_str "reopen clears resolved_at" "" "$(svc_scalar "SELECT COALESCE(resolved_at::text,'') FROM user_reports WHERE id='$USER_REPORT';")"

result_as_user "$ADMIN_ID" "SELECT admin_resolve_report('user','$USER_REPORT','resolved_action_taken','warned');" > /dev/null
expect_str "resolve -> resolved_action_taken" "resolved_action_taken" "$(svc_scalar "SELECT status FROM user_reports WHERE id='$USER_REPORT';")"
expect_str "resolve records admin_action row" "1" "$(svc_scalar "SELECT count(*) FROM admin_actions a JOIN admin_users au ON au.id=a.admin_user_id WHERE au.user_id='$ADMIN_ID' AND a.action='report.resolve';")"
expect_str "resolve resolved_by = admin user" "$ADMIN_ID" "$(svc_scalar "SELECT u.id FROM user_reports r JOIN users u ON u.id=r.resolved_by WHERE r.id='$USER_REPORT';")"

result_as_user "$ADMIN_ID" "SELECT admin_resolve_report('message','$MSG_REPORT','resolved_no_action','ok');" > /dev/null
expect_str "resolve message report" "resolved_no_action" "$(svc_scalar "SELECT status FROM message_reports WHERE id='$MSG_REPORT';")"

result_as_user "$ADMIN_ID" "SELECT admin_revoke_session('$SESS_A','testing');" > /dev/null
expect_str "revoke_session sets revoked_at" "1" "$(svc_scalar "SELECT count(*) FROM sessions WHERE id='$SESS_A' AND revoked_at IS NOT NULL;")"
expect_str "revoke_session revokes refresh family" "1" "$(svc_scalar "SELECT count(*) FROM refresh_tokens WHERE session_id='$SESS_A' AND revoked_at IS NOT NULL;")"
expect_str "revoke_session writes audit_log for victim" "1" "$(svc_scalar "SELECT count(*) FROM audit_logs WHERE user_id='$A_ID' AND action='session_revoked';")"

result_as_user "$ADMIN_ID" "SELECT admin_suspend_user('$B_ID','spam');" > /dev/null
expect_str "suspend sets users.status=suspended" "suspended" "$(svc_scalar "SELECT status FROM users WHERE id='$B_ID';")"
expect_str "suspend adds active ban restriction" "ban" "$(svc_scalar "SELECT restriction_type FROM user_restrictions WHERE user_id='$B_ID' AND is_active ORDER BY issued_at DESC LIMIT 1;")"
expect_str "suspend writes user.suspend admin_action" "1" "$(svc_scalar "SELECT count(*) FROM admin_actions a JOIN admin_users au ON au.id=a.admin_user_id WHERE au.user_id='$ADMIN_ID' AND a.action='user.suspend';")"
expect_str "suspend writes account_suspended audit" "1" "$(svc_scalar "SELECT count(*) FROM audit_logs WHERE user_id='$B_ID' AND action='account_suspended';")"

result_as_user "$ADMIN_ID" "SELECT admin_reinstate_user('$B_ID','appeal granted');" > /dev/null
expect_str "reinstate sets status=active" "active" "$(svc_scalar "SELECT status FROM users WHERE id='$B_ID';")"
expect_str "reinstate revokes all active restrictions (2)" "2" "$(svc_scalar "SELECT count(*) FROM user_restrictions WHERE user_id='$B_ID' AND revoked_at IS NOT NULL;")"

result_as_user "$ADMIN_ID" "SELECT admin_delete_message('$M1','tos');" > /dev/null
expect_str "delete_message soft-deletes M1" "1" "$(svc_scalar "SELECT count(*) FROM messages WHERE id='$M1' AND deleted_at IS NOT NULL;")"
expect_str "delete_message writes content.remove admin_action" "1" "$(svc_scalar "SELECT count(*) FROM admin_actions a JOIN admin_users au ON au.id=a.admin_user_id WHERE au.user_id='$ADMIN_ID' AND a.action='content.remove';" )"

# ============================================================================
# 5. NON-EXISTENT / INVALID TARGETS FAIL CLOSED (even for super_admin)
# ============================================================================
if blocked_as_user "$ADMIN_ID" "SELECT * FROM admin_get_user('$BADID');"; then report "get_user fails closed for unknown user" PASS; else report "get_user fails closed for unknown user" FAIL; fi
if blocked_as_user "$ADMIN_ID" "SELECT * FROM admin_revoke_session('$BADID');"; then report "revoke_session fails closed for unknown session" PASS; else report "revoke_session fails closed for unknown session" FAIL; fi
if blocked_as_user "$ADMIN_ID" "SELECT * FROM admin_resolve_report('user','$BADID','dismissed');"; then report "resolve_report fails closed for unknown report" PASS; else report "resolve_report fails closed for unknown report" FAIL; fi
if blocked_as_user "$ADMIN_ID" "SELECT * FROM admin_suspend_user('$BADID');"; then report "suspend fails closed for unknown user" PASS; else report "suspend fails closed for unknown user" FAIL; fi
if blocked_as_user "$ADMIN_ID" "SELECT * FROM admin_delete_message('$BADID');"; then report "delete_message fails closed for unknown message" PASS; else report "delete_message fails closed for unknown message" FAIL; fi
if blocked_as_user "$ADMIN_ID" "SELECT * FROM admin_resolve_report('user','$USER_REPORT','bogus');"; then report "resolve_report rejects invalid status" PASS; else report "resolve_report rejects invalid status" FAIL; fi

# ============================================================================
# 6. NO RLS POLICY WAS RELAXED - normal users still fully isolated
# ============================================================================
if blocked_as_user "$A_ID" "SELECT * FROM users WHERE id='$B_ID';"; then report "A still cannot read B directly (users RLS intact)" PASS; else report "A still cannot read B directly (users RLS intact)" FAIL; fi
if blocked_as_user "$A_ID" "SELECT * FROM sessions WHERE user_id='$B_ID';"; then report "A still cannot read B sessions (RLS intact)" PASS; else report "A still cannot read B sessions (RLS intact)" FAIL; fi
if blocked_as_user "$A_ID" "SELECT * FROM admin_actions;"; then report "A still cannot read admin_actions directly" PASS; else report "A still cannot read admin_actions directly" FAIL; fi

echo ""
echo "=========================================="
echo "RESULTS: ${PASS} passed, ${FAIL} failed"
echo "=========================================="
if [ ${FAIL} -gt 0 ]; then
  printf 'FAILED: %s\n' "${FAILED_TESTS[@]}"
  exit 1
fi
exit 0