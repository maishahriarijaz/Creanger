# Creanger Data-Plane E2E Proof (M1-R2 items 1-9)

Status: **PASS — all items verified against a live local stack on 2026-08-15.**

This document records the end-to-end proof that the Creanger chat data plane
works exactly as the product contract requires: the real Android client
(`ChatRepository` + `CreangerChatApiClient`) reaches a PostgREST/Supabase-style
data plane with the real Custom Auth Server JWT, and Row Level Security scopes
every row correctly.

## 1. Stack under test

| Layer | Component | Address |
|-------|-----------|---------|
| Auth server | Custom Auth Server (node/tsx, `EMAIL_DEV_CAPTURE=true`) | `http://localhost:3000` |
| Data plane | PostgREST v14.17 (`postgrest/postgrest:latest`) in docker | `http://localhost:5433` (container 3000) |
| Gateway stand-in | `/rest/v1` prefix-stripping proxy (Kong stand-in) | `http://localhost:5434` |
| Database | PostgreSQL 16.15, DB `creanger_chat` (24 migrations applied) | `localhost:5432` |
| Client under test | `CreangerDataPlaneE2ETest` (JVM, production classes) | `TMessagesProj/src/test/...` |

The gateway stand-in exists because the production client requests paths under
`/rest/v1/...` (the Supabase/Kong convention), while raw PostgREST serves
resources at the root. The proxy strips the prefix — a faithful stand-in for
Kong, no schema change required.

## 2. The two integration gaps found and fixed

### 2.1 Missing `role` claim in the access-token JWT

The Custom Auth Server's access token originally contained `sub`,
`sessionId`, `deviceId`, `type`, `aud`, `iss` — but **no `role` claim**.
PostgREST therefore fell back to the `anon` role and returned
`42501 permission denied` on every table, even with a perfectly valid
signature.

Fix: `creanger-auth-milestone3/src/auth/jwt.ts` now includes
`role: "authenticated"` in the signed payload, and `VerifiedAccessToken`
carries the claim (verified tokens default to `anonymous` when absent).
JWT unit tests (14) pass; the auth server was restarted.

### 2.2 Non-RFC-7517 JWKS endpoint

The auth server served the JWKS under `/.well-known/jwks.json` wrapped in
the Custom Auth envelope (`{ success, data: { keys } }`), which PostgREST
cannot consume. Fix (non-breaking): added `GET /jwt/keys` returning the raw
RFC 7517 `{ "keys": [...] }` document. PostgREST is configured with
`PGRST_JWT_SECRET=@/etc/postgrest/jwks.json` (a JWK set file), and validates
RS256 JWTs via the published `key-1` signing key.

## 3. PostgREST configuration

```env
PGRST_DB_URI=postgres://authenticator:...@postgres:5432/creanger_chat
PGRST_DB_SCHEMA=public
PGRST_DB_ANON_ROLE=anon
PGRST_DB_ROLES=authenticated,service_role,anon
PGRST_JWT_SECRET=@/etc/postgrest/jwks.json   # raw JWK set, validated live
PGRST_JWT_AUD=creanger-clients
```

PostgREST runs on the auth-milestone3 docker network; the host reaches it on
`localhost:5433` (container 3000). Linux needs `--add-host
host.docker.internal:host-gateway` when the auth server publishes JWKS over
HTTP from the host (final config mounts the raw JWKS file instead).

## 4. Item-by-item verdict (M1-R2 items 1-9)

| # | Item | Verdict |
|---|------|---------|
| 1 | PostgREST validates the Custom Auth JWT signature (RS256) | **PASS** — garbage JWT → `401` (`PGRST301`); valid JWT accepted |
| 2 | JWT claims (sub/aud) drive `current_user_id()` | **PASS** — RLS filters by `sub` |
| 3 | JWT carries a `role` claim mapped to PostgREST roles | **PASS after fix** (§2.1) — previously `anon`/`42501` |
| 4 | `chats` RLS isolates users | **PASS** — 22/22 boundary checks |
| 5 | `chat_members` never leaks rows of unauthorized chats | **PASS** — non-member lists return `[]`, not rows |
| 6 | Direct chats scoped to the two participants | **PASS** — members = exactly A+B |
| 7 | 401 for garbage/expired/missing tokens | **PASS** — typed `UNAUTHORIZED` in client |
| 8 | Public channels visible to all authenticated users | **PASS** — visible but members still hidden (RLS) |
| 9 | Real Android client reaches the data plane end-to-end | **PASS** — `CreangerDataPlaneE2ETest`, 2/2 (below) |

Items 1-8 were additionally verified server-side by the boundary suite
(`/tmp/opencode/verify_rls.py`, 22/22 checks) against the live stack.

## 5. Client-side E2E test

`TMessagesProj/src/test/java/org/telegram/messenger/creanger/CreangerDataPlaneE2ETest.java`
exercises the production client classes with only the raw HTTP transport
replaced by a JVM mirror of `HttpsUrlConnectionTransport` (identical
base-url/query/header behavior). Flow:

1. **Real login** against `localhost:3000`: `startLogin` → OTP from
   `/dev/otp` → `verifyLogin`. The resulting access-token JWT is what
   PostgREST validates (sub, `role=authenticated`, aud).
2. **Real client**: `ChatRepository` + `CreangerChatApiClient` pointed at the
   `/rest/v1` gateway stand-in (`localhost:5434`).
3. `refreshChats()` returns the RLS-scoped chat set.
4. `refreshMembers(chatId)` returns membership records.

Fixture (idempotent seed, `/tmp/opencode/seed_dataplane.py`): fixed accounts
`dataplane-a/b@example.test`; direct chat A↔B; private group A (A owner, B
member); public channel (B owner); private group B (B only).

Assertions:

- A sees exactly `{direct, groupA, public}` — **not** `groupB`.
- B sees `{direct, groupA, public, groupB}`.
- Direct chat members = exactly the two participants, both `member`.
- A's member list of the public channel (not a member) is empty.
- A garbage JWT → `CreangerApiException` with `ApiError.UNAUTHORIZED`.
- Per-account chat cache stays isolated.

Result: `CreangerDataPlaneE2ETest` 2/2 passed; full creanger JVM suite
(47 tests across 8 classes) green.

## 6. How to reproduce

```bash
# 1. Auth server up (dev, EMAIL_DEV_CAPTURE=true) on :3000.
# 2. PostgREST on :5433 with the config in §3.
# 3. Gateway stand-in on :5434:
python3 /tmp/opencode/supabase_gateway_proxy.py
# 4. Seed fixture (idempotent; re-runs reuse the fixed accounts):
python3 /tmp/opencode/seed_dataplane.py
# 5. Run the E2E (JVM; needs JDK 21):
JAVA_HOME=/path/to/jdk21 ./gradlew :TMessagesProj:testDebugUnitTest \
  --tests "org.telegram.messenger.creanger.CreangerDataPlaneE2ETest"
```

The test skips (not fails) when any local server is unreachable, so it never
breaks builds without the dev stack.
