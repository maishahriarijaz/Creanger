# AGENTS.md

Build & test commands and conventions for this Telegram-master fork.

## Environment

- JDK (required; the `~/.jdk` path is dead): `JAVA_HOME=/home/ijaz/.jdks/jbr-21.0.11`
- Root Gradle modules: `:TMessagesProj` (library + unit tests), `:TMessagesProj_App` (app APK)
- Gradle 8.x wrapper at `./gradlew`

## Commands

```bash
# Full debug APK (the product)
JAVA_HOME=/home/ijaz/.jdks/jbr-21.0.11 ./gradlew :TMessagesProj_App:assembleDebug

# Full unit test suite
JAVA_HOME=/home/ijaz/.jdks/jbr-21.0.11 ./gradlew :TMessagesProj:testDebugUnitTest

# Creanger data-plane tests only (fast loop)
JAVA_HOME=/home/ijaz/.jdks/jbr-21.0.11 ./gradlew :TMessagesProj:testDebugUnitTest --tests "org.telegram.messenger.creanger.*"
```

Always finish a phase with full tests + `assembleDebug` green.

## Creanger conventions (`org.telegram.messenger.creanger`)

- Layering: `api/` (HTTP clients + parser) -> `data/` (repository/bridge/controller/async/adapter/mapping) -> `model/` (plain value types, org.json + JVM only) -> `realtime/` (WebSocket/Phoenix) -> `storage/` + `auth/` + `device/`.
- `MessageRepository` is the core cache/state (optimistic idempotent applies, recovery, logout `clear()`). `CreangerChatBridge` = sync UI-facing controller, `CreangerMessageAsync` = Android async wrapper (`Callback<T>`, main-thread poster), `CreangerChatController` = screen-level phase/paging. `CreangerAuth`/`CreangerAuth.java` facade wires repositories.
- Android-free core: the tested seam must not import Android types (min SDK 21 → no `java.time`; use ms epoch strings or epoch-millis longs).
- Feature-gate routing in UI: `BuildConfig.USE_CREANGER_AUTH` + `CreangerChatDetection.isCreangerChat(...)`; Creanger chats carry `creanger_uuid` in `messageOwner.params` (`CreangerMessageObjectAdapter.buildParams`) and `CreangerChatDetection.ARG_CREANGER_CHAT_ID = "creanger_chat_id"` via launch arguments.
- New message tables/RPCs MUST be added to `supabase/realtime` publication (`020_realtime_publication.sql`) and RPCs follow the `SECURITY DEFINER` member-gated pattern of `025/026/027`.
- No logging in the creanger package: no `Log.*`, `println`, `printStackTrace`, and never log access/refresh tokens (trace-log safety).

## Tests

- Flat dir `TMessagesProj/src/test/java/org/telegram/messenger/creanger/`, one `*Test.java` per production class, `org.junit.Assert.*`.
- Mocks are private nested classes per test file (scripted/`FakeTransport`, direct executor, recording scheduler, synchronous poster) — no shared support class.
- Realtime/client-frame builder helpers exist per test file (`insert(...)`, `updateEdit(...)`, `statusUpdate(...)`, `reactionChange(...)`).

## Supabase migrations

- Naming: `NNN_snake_case_descriptor.sql`, zero-padded, next number = highest existing + 1.
- Message-scoped features historically: `025` delete RPC, `026` read state, `027` message reactions.