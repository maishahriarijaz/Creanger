# MTProto Baseline Audit Report
## Telegram-master Fork (Creanger)

---

## Executive Summary

This project has **already migrated away from MTProto/TGNet** to a custom backend architecture called **Creanger** (Supabase GoTrue + PostgREST + Realtime). The native MTProto C++ layer is **disabled in CMakeLists.txt** (lines 175-205). The Java `ConnectionsManager` now acts as a **compatibility wrapper** delegating to `CustomNetworkInterface` implementations.

---

## 1. MTProto Entry Points

**Status: DISABLED / WRAPPER ONLY**

| Entry Point | Location | Status |
|-------------|----------|--------|
| `ConnectionsManager` (Java) | `com/creanger/app/tgnet/ConnectionsManager.java` | **Compatibility wrapper** - delegates to `CustomConnectionsManager` |
| `ConnectionsManager` (Native C++) | `jni/tgnet/ConnectionsManager.cpp` | **NOT COMPILED** - disabled in CMakeLists.txt |
| `NativeByteBuffer` (Java) | `com/creanger/app/tgnet/NativeByteBuffer.java` | **KEPT** - used by local DB serialization (`MessagesStorage`, `MediaDataController`) |
| `NativeByteBuffer` (Native) | `jni/tgnet/NativeByteBuffer.cpp` | **NOT COMPILED** - disabled in CMakeLists.txt |
| `TLObject` / `TLRPC` | `com/creanger/app/tgnet/` | **KEPT** - data models for UI compatibility |

**Key Evidence:**
- `CMakeLists.txt:175-205` - `#tgnet - DISABLED: MTProto removed, using CustomNetworkInterface`
- `CMakeLists.txt:657` - `# tgnet        # DISABLED: MTProto removed`
- `ConnectionsManager.java:82-90` - "DELEGATE to CustomNetworkInterface... MTProto/Telegram server logic is REMOVED"

---

## 2. TGNet Entry Points

**Status: REPLACED by CustomNetworkInterface**

| Component | Status |
|-----------|--------|
| `ConnectionsManager.sendRequest()` | Delegates to `CustomNetworkInterface.sendRequest()` |
| `ConnectionsManager.cancelRequest()` | Delegates to `CustomNetworkInterface.cancelRequest()` |
| `ConnectionsManager.bindRequestToGuid()` | Delegates to `CustomNetworkInterface.bindRequestToGuid()` |
| `ConnectionsManager.setUserId()` | Delegates to `CustomNetworkInterface.setUserId()` |
| `ConnectionsManager.setPushConnectionEnabled()` | Delegates to native (still compiled for VOIP) |
| `ConnectionsManager.updateDcSettings()` | Delegates to native (stub) |

**Native TGNet (C++) - DISABLED:**
- `ApiScheme.cpp/h` - MTProto schema
- `MTProtoScheme.cpp/h` - MTProto protocol
- `Connection.cpp/h` - TCP connection
- `ConnectionSocket.cpp/h` - Socket handling
- `Datacenter.cpp/h` - Datacenter management
- `Handshake.cpp/h` - MTProto handshake
- `BuffersStorage.cpp/h` - Buffer pooling

---

## 3. ConnectionsManager Callers

**Active callers (using compatibility wrapper):**

| Category | Files | Count |
|----------|-------|-------|
| **UI Activities/Fragments** | `IntroActivity`, `LoginActivity`, `ChannelMonetizationLayout`, `ReportBottomSheet`, `ChatRightsEditActivity`, `PremiumPreviewFragment`, `TopicCreateFragment`, `GroupCreateFinalActivity`, `ChatUsersActivity`, `ChangeBioActivity`, `SelectChatUserSheet`, `LocationActivity`, `NotificationsSettingsActivity`, `ChatEditTypeActivity`, `PhotoPickerActivity`, `WallpapersListActivity`, `ThemeSetUrlActivity`, `ThemePreviewActivity`, `MessageStatisticActivity`, `ManageLinksActivity`, `ChannelAdminLogActivity`, `SessionsActivity`, `StatisticActivity`, `ProfileActivity`, `ChatActivity`, `ChangeUsernameActivity`, `GroupInviteActivity`, `ArchivedStickersActivity`, `AutoDeleteMessagesActivity` | 30+ |
| **Controllers** | `MessagesController`, `MediaDataController`, `ContactsController`, `TopicsController`, `FactCheckController`, `MemberRequestsController`, `LocationController`, `DownloadController`, `FileRefController`, `HashtagSearchController`, `TranslateController`, `PasskeysController`, `ChatMessagesMetadataController`, `SendMessagesHelper`, `SecretChatHelper`, `FileUploadOperation`, `FileLoadOperation`, `UserNameResolver`, `VideoAds`, `ImageLoader` | 20+ |
| **UI Components** | `StoriesController`, `PeerStoriesView`, `StoryEntry`, `StoryContainsEmojiButton`, `EmojiView`, `StickersAlert`, `SearchAdapterHelper`, `SharedMediaLayout`, `PaymentFormActivity`, `TwoStepVerificationActivity`, `TwoStepVerificationSetupActivity` | 10+ |

**All callers use the same API surface** - they don't know MTProto is gone.

---

## 4. TLRPC Network Usage vs UI/Model Usage

### ❌ TLRPC Network/Protocol Usage (REMOVED)
- MTProto request/response serialization → **REPLACED** by REST/JSON via `CreangerChatApiClient`
- MTProto RPC calls (`TL_methods`) → **REPLACED** by PostgREST RPC calls
- MTProto datacenter routing → **REMOVED** (single Supabase endpoint)
- MTProto encryption/authorization → **REPLACED** by Supabase GoTrue JWT
- MTProto long-polling/WebSocket → **REPLACED** by Supabase Realtime (Phoenix channels)

### ✅ TLRPC Types Still Required (UI/LOCAL COMPATIBILITY)

| Type | Used By | Purpose |
|------|---------|---------|
| `TLRPC.Message` | `MessageModelMapper`, `MessageObject`, `ChatActivity`, `MediaDataController` | UI message model |
| `TLRPC.MessageMedia` | `MediaModelMapper`, `ImageLoader`, `FileLoader` | Media display |
| `TLRPC.MessageEntity` | `EntityModelMapper`, `MessageObject`, `RichMessageLayout` | Text formatting |
| `TLRPC.User` / `TLRPC.Chat` | `UserModelMapper`, `ChatModelMapper`, `MessagesController`, `ContactsController` | User/chat display |
| `TLRPC.Photo` / `TLRPC.Document` | `MediaModelMapper`, `ImageLoader`, `FileLoader` | Media handling |
| `TLRPC.TL_error` | `RequestDelegate` callbacks | Error handling compatibility |
| `TLRPC.Vector` | Various mappers | Collections |
| `SerializedData` / `NativeByteBuffer` | `MessagesStorage`, `MediaDataController`, `FileLoadOperation`, `DownloadController`, `UserConfig`, `SharedConfig`, `MessageKeyData`, `AuthTokensHelper` | **Local DB serialization only** |
| `TLObject` base | `SecretChatHelper` (local), `SQLiteCursor`, `SQLitePreparedStatement`, `MediaController`, `VideoEditedInfo`, `ImageLocation`, `MessageCustomParamsHelper` | Local serialization/deserialization |

---

## 5. Native Networking Path

### Still Compiled (JNI):
| Native Module | Purpose | Used By |
|---------------|---------|---------|
| `tgvoip` / `tgcalls` | VoIP calling | `VoIPService`, `NativeInstance` |
| `FileLog.cpp` | Logging (used by voip) | Native logging |
| `opus` / `libvpx` / `libdav1d` | Audio/video codecs | VoIP, media |
| `ffmpeg` / `swscale` / `avcodec` | Media processing | Media, stories |
| `rlottie` | Animation rendering | UI |
| `sqlite` | Local database | `MessagesStorage` |
| `boringssl` | TLS/crypto | VoIP, potentially other |
| `tde2e` / `tdutils` | MTProto 2.0 / TDLib (likely for VoIP) | VoIP |

### NOT Compiled (Disabled):
| Native Module | Was Used For |
|---------------|--------------|
| `tgnet` (all) | MTProto networking |
| `MTProtoScheme` | MTProto protocol |
| `ApiScheme` | Telegram API schema |
| `Datacenter` | DC management |
| `Connection` / `ConnectionSocket` | TCP connections |
| `Handshake` | MTProto handshake |

---

## 6. Telegram Server Connection Paths

| Path | Status | Replacement |
|------|--------|-------------|
| `ConnectionsManager` → native `tgnet` → Telegram DCs | **REMOVED** | N/A |
| `FileUploadOperation` → `uploadFile` MTProto | **REMOVED** | `CreangerMediaUploadClient` → Supabase Storage |
| `FileLoadOperation` → `downloadFile` MTProto | **REMOVED** | `CreangerChatApiClient` / CDN URLs |
| Push notifications (MTProto) | **REMOVED** | Firebase FCM (via `PushListenerController`) |
| Secret chats (MTProto layer) | **REMOVED** | Not implemented in Creanger |

---

## 7. Creanger Network Architecture (EXISTING)

### Layer Architecture (per AGENTS.md):
```
api/ (HTTP clients + parser)
  ├── CreangerHttpTransport (interface)
  ├── HttpsUrlConnectionTransport (implementation)
  ├── CreangerChatApiClient (PostgREST chat/messages)
  ├── SupabaseAuthClient (GoTrue auth)
  ├── CreangerMediaUploadClient (Supabase Storage)
  ├── JsonEnvelopeParser (JSON parsing)
  └── PostgRestResponseParser

data/ (repository/bridge/controller/async/adapter/mapping)
  ├── ChatRepository (chat list cache + refresh)
  ├── MessageRepository (message cache + send + realtime apply)
  ├── CurrentUserRepository (current user profile)
  ├── CreangerChatBridge (UI-facing controller)
  ├── CreangerChatController (screen-level phase/paging)
  ├── CreangerMessageAsync (Android async wrapper)
  ├── CreangerMessageObjectAdapter (TLRPC ↔ Creanger mapping)
  └── CreangerMessageMapping

model/ (plain value types, org.json + JVM only)
  ├── AuthModels (User, Session, tokens)
  ├── ChatModels (Chat, Member)
  ├── MessageModels (Message, Reaction, Status, Media)
  ├── ChatModels, EntityModels, MediaModels, UserModels
  └── NetworkError, PaginatedResponse

realtime/ (WebSocket/Phoenix)
  ├── MessageRealtimeClient (Phoenix channel client)
  ├── SocketMessageRealtimeTransport (OkHttp WebSocket)
  ├── WebSocketFrameCodec (Phoenix protocol)
  ├── MessageRealtimeDeduplicator (idempotent apply)
  ├── MessageRealtimeTransport (interface)
  └── RealtimeMessageParser

storage/ + auth/ + device/
  ├── KeystoreTokenStore (Android Keystore)
  ├── CreangerTokenStore (interface)
  ├── CreangerDeviceIdentity
  ├── CreangerAuthEngine (auth orchestration)
  └── CreangerAuthAsync
```

### Authentication Layer:
- `CreangerAuthEngine` - orchestrates Supabase GoTrue flow
- `SupabaseAuthClient` - HTTP client for `/auth/v1/*`
- `KeystoreTokenStore` - secure token storage in Android Keystore
- `CreangerAuth` - facade wiring repositories

### REST Layer:
- `CreangerChatApiClient` - PostgREST `/rest/v1/*` for chats/messages
- `CreangerMediaUploadClient` - Supabase Storage `/storage/v1/*`
- All RPCs use `SECURITY DEFINER` pattern (migrations 025, 026, 027, 031)

### Realtime Layer:
- Phoenix channels over WebSocket (`SocketMessageRealtimeTransport`)
- Channels: `messages:{chat_id}`, `presence:{chat_id}`, `status:{chat_id}`
- Idempotent application via `MessageRealtimeDeduplicator`
- Recovery via watermark cursors (`statusUpdatedAtByChat`, `messageChangeUpdatedAtByChat`)

### Backend Communication:
- **Auth**: Supabase GoTrue (`/auth/v1/token?grant_type=...`, `/auth/v1/user`)
- **Data**: PostgREST (`/rest/v1/chats`, `/rest/v1/messages`, RPCs)
- **Realtime**: Supabase Realtime (Phoenix/WebSocket)
- **Storage**: Supabase Storage (`/storage/v1/object/...`)

---

## 8. Potentially Safe Deletions

### Java (if fully committing to Creanger):
| File | Reason |
|------|--------|
| `com/creanger/app/tgnet/ConnectionsManager.java.bak2` | Backup file |
| `com/creanger/app/tgnet/ConnectionsManager.java.bak3` | Backup file |
| `com/creanger/app/tgnet/tl/` (all `TL_*.java`) | MTProto RPC definitions - **UNUSED** for network |
| `com/creanger/app/tgnet/json/` | MTProto JSON serialization - **UNUSED** |
| `com/creanger/app/tgnet/RequestDelegateInternal.java` | Unused (only 1 ref in file itself) |
| `com/creanger/app/tgnet/RequestDelegateTimestamp.java` | Unused |
| `com/creanger/app/tgnet/QuickAckDelegate.java` | MTProto-specific |
| `com/creanger/app/tgnet/WriteToSocketDelegate.java` | MTProto-specific |
| `com/creanger/app/tgnet/ResultCallback.java` | MTProto-specific |
| `com/creanger/app/tgnet/RequestTimeDelegate.java` | MTProto-specific |

### Native (already disabled in CMake):
- Entire `jni/tgnet/` directory (can be deleted if not needed for reference)
- `jni/TgNetWrapper.cpp` - JNI bridge for disabled tgnet

### ProGuard Rules (can simplify):
- Lines 18-21 in `proguard-rules.pro` keep `ConnectionsManager`, `NativeByteBuffer`, `RequestDelegate`, `RequestTimeDelegate` - only `NativeByteBuffer` truly needed

---

## 9. Files Requiring Compatibility Shims

**These MUST remain for UI/local code compatibility:**

| File | Why Required |
|------|--------------|
| `com/creanger/app/tgnet/TLRPC.java` | 2.9M lines - all UI message/user/chat/media models |
| `com/creanger/app/tgnet/TLObject.java` | Base class for all TLRPC types |
| `com/creanger/app/tgnet/NativeByteBuffer.java` | Local DB serialization (MessagesStorage, etc.) |
| `com/creanger/app/tgnet/SerializedData.java` | Local serialization |
| `com/creanger/app/tgnet/InputSerializedData.java` | Local deserialization |
| `com/creanger/app/tgnet/OutputSerializedData.java` | Local serialization |
| `com/creanger/app/tgnet/Vector.java` / `VectorLegacy.java` | Collections in TLRPC |
| `com/creanger/app/tgnet/TLClassStore.java` | TLRPC class registry (for local deserialization) |
| `com/creanger/app/tgnet/RequestDelegate.java` | Callback interface used by all UI code |
| `com/creanger/app/tgnet/ConnectionsManager.java` | **Wrapper facade** - all UI calls this |
| `com/creanger/app/network/CustomNetworkInterface.java` | Interface definition |
| `com/creanger/app/network/CustomConnectionsManager.java` | Current stub implementation |
| `com/creanger/app/network/engine/CustomBackendNetworkEngine.java` | Real Creanger implementation |
| `com/creanger/app/network/engine/CustomHttpClient.java` | HTTP client for Creanger REST |

---

## 10. Files That Must NEVER Be Touched

| File | Reason |
|------|--------|
| `com/creanger/app/messenger/creanger/**` | **Core Creanger architecture** - tested, working, documented |
| `com/creanger/app/network/**` | **Network abstraction layer** - bridges UI to Creanger |
| `com/creanger/app/tgnet/TLRPC.java` | **UI model definitions** - 2.9M lines, massive refactor risk |
| `com/creanger/app/tgnet/NativeByteBuffer.java` | **Local DB serialization** - MessagesStorage depends on it |
| `com/creanger/app/tgnet/SerializedData.java` + `Input/OutputSerializedData` | **Local serialization** - widely used |
| `com/creanger/app/messenger/MessagesStorage.java` | **Local database** - 887K lines, uses TLRPC serialization |
| `com/creanger/app/messenger/MediaDataController.java` | **Media cache** - 511K lines, uses TLRPC/SerializationData |
| `com/creanger/app/messenger/MessageObject.java` | **Message UI model** - 685K lines, heavy TLRPC usage |
| `com/creanger/app/messenger/MessagesController.java` | **Core controller** - 1.2M lines, orchestrates everything |
| `com/creanger/app/messenger/SendMessagesHelper.java` | **Send pipeline** - 759K lines, uses ConnectionsManager API |
| `com/creanger/app/messenger/ApplicationLoader.java` | **App initialization** - wiring point |
| `com/creanger/app/messenger/UserConfig.java` | **Account config** - used by Creanger auth |
| `com/creanger/app/messenger/SharedConfig.java` | **Shared settings** - used by network config |
| `com/creanger/app/messenger/NotificationCenter.java` | **Event bus** - used by Creanger for UI updates |
| `AndroidManifest.xml` | **App manifest** - permissions, services, activities |
| `build.gradle` + `CMakeLists.txt` | **Build configuration** - defines what's compiled |
| `proguard-rules.pro` | **R8/ProGuard config** - keeps critical classes |

---

## Classification Summary

| ID | Classification | Key Files |
|----|----------------|-----------|
| 1 | MTProto protocol | **DISABLED** - `jni/tgnet/` (not compiled) |
| 2 | TGNet networking | **REPLACED** - `CustomNetworkInterface` + `CustomBackendNetworkEngine` |
| 3 | Telegram RPC | **REMOVED** - was MTProto RPCs, now PostgREST RPCs |
| 4 | Telegram network model | **REPLACED** - was DC/MTProto, now Supabase REST/Realtime |
| 5 | UI-only compatibility dependency | **ACTIVE** - `TLRPC` types, `ConnectionsManager` wrapper |
| 6 | Local database/cache | **ACTIVE** - `MessagesStorage`, `MediaDataController`, `NativeByteBuffer` |
| 7 | Media | **ACTIVE** - `FileLoader`, `ImageLoader`, `DownloadController` (use CDN URLs) |
| 8 | Graphics/native rendering | **ACTIVE** - `rlottie`, `ffmpeg`, `ImageReceiver`, `VideoPlayer` |
| 9 | Dead code | `ConnectionsManager.java.bak2/3`, `jni/tgnet/` (disabled), `TL_*` RPC classes |
| 10 | Unknown | Minimal - codebase well understood |

---

## Conclusion

**The MTProto layer has been successfully removed at the network transport level.** The codebase maintains a **compatibility façade** (`ConnectionsManager` + `TLRPC` types) so that 1.2M+ lines of UI and local storage code continue working unchanged. The actual network communication now flows through:

```
UI → ConnectionsManager (wrapper) → CustomNetworkInterface
    → CustomBackendNetworkEngine → CreangerAuth/ChatRepository/MessageRepository
    → CreangerChatApiClient (PostgREST) + SocketMessageRealtimeTransport (Phoenix WS)
    → Supabase (GoTrue + PostgREST + Realtime + Storage)
```

**No further MTProto cleanup is needed** unless the TLRPC model layer itself is to be replaced (massive undertaking, not recommended).

---

*Report generated: Phase 0 - Read-only audit*
*No code modifications performed*