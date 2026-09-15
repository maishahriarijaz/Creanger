# Creanger repair plan

## Verified baseline (2026-09-05)

- Gradle wrapper: 8.7.
- Android Gradle Plugin: 8.6.1.
- Compile/target SDK: 35; minimum SDK: 21.
- Java source/target compatibility: 8; local build JDK: `/home/ijaz/.jdks/jbr-21.0.11`.
- `TMessagesProj` contributes 2,855 Java source files to `compileDebugJavaWithJavac`.
- The current compile produces 1,282 diagnostic lines.
- `TLRPC.java` is a 2,025-line compatibility shim and has no direct compiler diagnostic in the current build.
- The first visible diagnostics are in `ui/Components/EmojiView.java`, beginning at line 11.
- The largest visible clusters are `MessagesController`, `EmojiView`, `MessageObject`, `DialogCell`, `ChatActivity`, and `Theme`.

## Architectural constraints

Creanger models and Supabase repositories remain canonical. TLRPC objects are temporary UI adapters only. Do not restore the original Telegram schema, MTProto, TGNet, Telegram authentication, or Telegram media transport.

## Repair order

1. Make the current directory recoverable before source edits (Git is not present in this checkout; preserve timestamped copies of files that will be changed).
2. Establish a retained-feature boundary: authentication, profiles, chats, messages, media URLs, reactions, and read state.
3. Remove or isolate Telegram-only feature callers (secret chats, Stars/payments, bot web apps, Telegram DC media, and other protocol-only flows) from the retained build path.
4. Repair the retained model seams, starting with sticker/emoji code where `LocalModels` and `TLRPC` are currently mixed.
5. Repair only field/type errors proven by the next compiler run; never add universal `Object`, `String`, or guessed protocol fields.
6. Run Creanger unit tests after each stable cluster, then full unit tests and `assembleDebug` at the end of each phase.

## Current first target

`EmojiView.java` and its adjacent sticker/emoji callers are the first target because the compiler exposes their imports and mixed `LocalModels`/TLRPC assignments first. The target is a renderer-facing adapter boundary, not a restoration of Telegram sticker networking.
