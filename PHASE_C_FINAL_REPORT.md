# PHASE C FINAL REPORT

Creanger chat lifecycle — full MTProto reachability audit and elimination of every runtime MTProto action reachable from a Creanger chat. Phase B's guards were untouched.

Invariant enforced: **Creanger chats never touch the MTProto wire at runtime.** Every reachable action either uses the Creanger data plane (`org.telegram.messenger.creanger`) or is blocked explicitly with a toast (`creangerBlockUnsupportedSend`). Non-Creanger (Telegram) chats keep stock behavior.

All changes are in `TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java`.

---

## 1. MTProto Reachability table

Action | Plane | Status | Location
---|---|---|---
Send text / reply text | Creanger (`creangerProcessSendingText` → `sendReplyTextMessage`) | Native, verified | ChatActivity:13944
Send photo / video / album / document / audio / voice | Creanger (`creangerSendPhoto/Video/Document/Audio/Voice`, album & picker chokepoints) | Native, verified | ChatActivity:14010–14330, 12144, 14689
Send contact / web-search image / poll / paid media / scheduled send | — | Blocked (toast) | ChatActivity:35258, 14777
Edit text | Creanger (`creangerEditTextMessage`, `canEditCreangerMessage` text-only) | Native, verified | ChatActivityEnterView:6909
Edit media replacement | — | **Blocked (new)** | ChatActivity:12160, 14695, 14735, 35309, 35467
Forward (single, action-mode, fab, same-chat, cross-chat) | — | **Blocked (new)** | ChatActivity:11790, 15163, 33200, 34258, menu 45187
Translate | — | **Blocked (new)** | menu 45204
Pin / Unpin | — | **Blocked (new)** | ChatActivity:33671, 33762
Delete / edit-undo-insert | Creanger (`createCreangerDeleteMessagesAlert`, `creangerDeleteTextMessage`, `creangerDiscardLocalMessage`) | Native, verified | ChatActivity:30537, 30607
Reactions | Creanger (`creangerProcessReaction`) | Native, verified | ChatActivity:32736
Reply (banner + send) | Creanger (reply header built by adapter; `sendReplyTextMessage`) | Native, verified | `CreangerMessageObjectAdapter.attachReply`, ChatActivity:13944
Copy text | Local (clipboard) | Safe no-op path | ChatActivity:33217
Save to gallery (physical) | Local (`saveMessageToGallery` — existing file only) | Safe no-op | ChatActivity:33227
Save to downloads / music | Local-only gate | **Blocked (new)** unless local file exists | ChatActivity:33509
Share file | Local-only gate | **Blocked (new)** unless local file exists | ChatActivity:33361
Typing indicator | — | **Blocked (new)** | ChatActivity:2241
Draft save (drafts) | — | **Blocked (new)** | ChatActivity:30014
Audio/voice/document playback | Creanger video URL seam only | **Blocked (new)** for voice/audio/doc without URL | ChatActivity:39058
Fullscreen (PhotoViewer) media open | Creanger image HTTP path only | **Blocked (new)** for video/gif/document | ChatActivity:36533
Profile / chat-profile open | — | **Blocked (new)** | ChatActivity:39474, 39498, 41520, 41529
Message details / statistics / view-thread | Not reachable (require `currentChat`/channel) | N/A | —
Stickers / GIFs / polls / locations / contacts | Not reachable in Creanger menu | N/A | —
Scheduled messages, send-now, edit-schedule | Not reachable (Creanger never schedules) | N/A | —
Report chat / copy link / statements | Not reachable (require `currentChat`/`currentUser`) | N/A | —

## 2. Forward

Was the largest cross-plane leak: a confirmed Creanger message could be forwarded into any Telegram chat via `SendMessagesHelper.sendMessage(messages, ...)` (MTProto `messages.forwardMessages`). Now every entry and commit is closed:

- Menu item `OPTION_FORWARD` no longer added for Creanger chats (fillMessageMenu, ChatActivity:45187).
- `processSelectedOption` OPTION_FORWARD case blocked (ChatActivity:33200).
- `openForward` blocked (ChatActivity:11790) — covers action-mode “Forward” and message-fab forward.
- Same-chat forward commit `forwardMessages(...)` blocked (ChatActivity:15163).
- Cross-chat staging in `didSelectDialogs` blocked when any selected message is a Creanger message (`isCreangerMessage`, ChatActivity:34258) — covers multi-select forward and OK-to-target commit.
- `MessageObject.canForwardMessage()` has no Creanger branch, but all UI entry points and both commit paths are now guarded. (Noted as a follow-up hardening option — see Section 15.)

## 3. Edit

- Text edits: routed to `creangerEditTextMessage` via `ChatActivityEnterView.processSendingText` (line 6909). `canEditCreangerMessage()` gates Edit to outbound, confirmed, text-only messages — media/caption edits never surface.
- `startEditingMessageObject` already skips the `messages.getMessageEditData` MTProto request for Creanger (Phase B guard, ChatActivity:32899) with no code change needed.

## 4. Edited media

Editing (media replacement) of a Creanger message would have fallen through the send chokepoints to `SendMessagesHelper.prepareSendingMedia/Documents` (MTProto). The menu gate (`canEditCreangerMessage` = text-only) already prevents most entries, and the following backstop guards now block any residual `editingMessageObject != null` path:

- `sendMedia` → blocked (ChatActivity:35467)
- `sendAudio` → blocked (ChatActivity:35309)
- `didSelectPhotos` → blocked (ChatActivity:14735)
- `didSelectFiles` → blocked (ChatActivity:14695)
- Album picker chokepoint (`processSelectedAttach` callback) → blocked (ChatActivity:12160)

## 5. Reply

Native. Synthetic `TL_messageReplyHeader` + `replyMessage` are attached by `CreangerMessageObjectAdapter.attachReply`; sending replies goes through `creangerProcessSendingText` → `sendReplyTextMessage` with `resolveCreangerReplyTargetId`. Verified, no MTProto.

## 6. Delete

Native. `createDeleteMessagesAlert` routes Creanger chats to `createCreangerDeleteMessagesAlert` (ChatActivity:30537); bulk path uses `creangerDeleteTextMessage` + `creangerDiscardLocalMessage` (ChatActivity:30607). Retry/cancel also routed to `creangerRetrySendTextMessage` / `creangerCancelPendingMessage`.

## 7. Reactions

Native. Reaction toggle guarded at ChatActivity:32736: Creanger message → `creangerProcessReaction` (idempotent `add_reaction`/`remove_reaction` RPC), everything else → `getSendMessagesHelper().sendReaction`. Emoji-only (custom-emoji rows skipped — no media loading).

## 8. Message menu

Confirmed-reachable options in the Creanger menu were: Reply, Copy, Delete, Edit (text), Forward, Translate, Save, Share, Pin/Unpin (guarded), plus media save/share. Forward and Translate now excluded by menu conditions; Pin/Unpin, Save-to-downloads (remote), and Share (remote) blocked in their handlers. Remaining reachable options are all native (Reply/Edit/Delete/Copy/Save-physical) or safe local no-ops. Options requiring `currentChat`/`currentUser`/channel state (Report, Copy-link, View-replies, Statistics, Add-to-GIFs, schedule, stickers, polls, locations, contacts, gifts, todos, sponsored) are structurally unreachable for Creanger because `currentChat == null && currentUser == null`.

## 9. Share / forward intents

- In-app share (`OPTION_SHARE`) now only permitted when a real local file exists (pending rows); remote media → blocked toast (ChatActivity:33361). No MTProto.
- The Android system share sheet (ACTION_SEND) is a device action — the receiving app (including Telegram) is not our runtime, so it cannot be controlled; it re-uploads only if the user confirms on that side. Not an in-process MTProto call.
- Typing in the input no longer fires `messages.setTyping` (ChatActivity:2241); drafts no longer fire `messages.saveDraft` for the synthetic peer (ChatActivity:30014); these were automatic background MTProto sends per keystroke/pause.

## 10. Background callbacks

- `needSendTyping` → `sendTyping` MTProto: blocked (2241).
- `saveDraft` → `MediaDataController.saveDraft` → `messages.saveDraft` MTProto: blocked (30014).
- Media autoplay/playback: blocked for voice/audio/document Creanger messages without a streamable URL (39058); Creanger video with `creanger_video_url` streams over HTTPS via MediaController's existing seam (MediaController:3878) — no FileLoader/MTProto.
- Fullscreen media open: blocked for video/gif/document Creanger messages (PhotoViewer has no URL seam → would MTProto-party-stream fake `TL_inputDocumentFileLocation`); Creanger images (photo `size.url` = HTTPS) render through the ImageLoader HTTP path — safe.
- Profile opens (`ProfileActivity` → `getFullUser`/`getFullChannel`): blocked (39474, 39498, 41520, 41529).
- Read/delivered marking: guarded in Phase B (`markMessageContentAsRead`, `markDialogAsRead`, `creangerMarkVisibleAsRead`); no MTProto contact.
- Link preview: Phase B guard drops `TL_account.getWebPagePreview` and clears stale previews (`searchLinks`); verified no RPC.
- In-chat search: Phase B hides search actions and no-ops `openSearchWithText`/`scrollToMessageId`/`jumpToDate` MTProto branches.

## 11. Remaining MTProto references

After the sweep, no user- or lifecycle-triggered MTProto RPC remains reachable from a Creanger chat. The remaining `TL_*`/`ConnectionsManager` touchpoints encountered are either (a) structurally unreachable in Creanger (menu conditions, `currentChat/currentUser == null`, no scheduled mode, no bots/polls/stickers), (b) guarded in Phase B, or (c) local-only (storage, ImageLoader HTTP, drafts preferences). Global account sync (getDialogs, contacts, online-status) runs against the real Telegram account and never addresses the Creanger synthetic dialog id.

## 12. Tests

- `:TMessagesProj:testDebugUnitTest` — BUILD SUCCESSFUL (all 30 Creanger data-plane suites pass; no regressions).
- New UI guards are Android `ChatActivity` route-level (no unit-test seam exists for UI flows); the reachability classification above is the regression artifact. (Unit tests cover the data-plane contract the guards route to: send/edit/delete/reactions/reply/read/upload.)

## 13. Build

- `:TMessagesProj:compileDebugJavaWithJavac` — clean (only pre-existing javac 8 deprecation warnings).
- `:TMessagesProj_App:assembleDebug` — BUILD SUCCESSFUL.

## 14. Remaining unsupported Creanger features

Blocked with an explicit toast (all surfaces are inert, no MTProto):

- Forwarding, pin/unpin, translate, contacts, polls, live locations, web-search images, stickers/GIFs, scheduled messages
- Replacing media while editing
- Saving/sharing remote media (only locally-existing files)
- Fullscreen video/gif/document viewer
- Audio/voice/document playback (video in-cell playback works off the HTTPS URL seam)
- Draft persistence, typing indicators
- Profile / chat-profile screens, full message statistics

## 15. Production blockers

1. **Header avatar/title tap** (`avatarContainer.openProfile(BaseFragment)`) still routes through the base container, which can open `ProfileActivity` for the synthetic chat and fire `getFullChannel`/`getFullUser`. The common cell-level and `openUserProfile`/`openThisProfile` paths are guarded; the raw `avatarContainer.openProfile(...)` call sites at ChatActivity:3669, 36125, 39522 are only reachable in non-Creanger flows today (view-as-topics, action-mode title, own-chat), but a dedicated header guard for `isCreangerChat` is recommended for completeness.
2. `MessageObject.canForwardMessage()` has no Creanger branch; today every forward entry/commit is guarded in `ChatActivity`, but a Creanger-aware `canForwardMessage()` (returning false) would make the invar unit-proof against future UI paths. Low priority; do not change without re-running Phase B tests.
3. Creanger video/audio media rely on the `creanger_video_url`/`creanger_poster_url` params being populated by the server mapping; signed/expiring `deliveryUrl` fallback is intentionally not preferenced for playback. Any new media type must register its URL seam in `MediaController` before it can be played, otherwise it is automatically blocked by the `needPlayMessage` guard (safe default).