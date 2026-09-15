# Creanger local-first feature plan

The existing Telegram-style UI is retained. Local state is handled by
`LocalStickerStore`; it does not use Telegram RPC, MTProto, access hashes, or
data-center networking.

## Can work locally

- Built-in emoji panel and emoji selection.
- Sticker catalog cached on the device.
- Recent stickers and favorite stickers.
- Emoji status selection/removal stored locally.
- Preview of stickers already present in the local catalog.
- Sticker captions for locally available media, once the message adapter is wired.
- Offline opening of cached sticker packs.

## Still needs Creanger backend/API

- Downloading new sticker packs to the device.
- Installing/removing a pack across devices.
- Global sticker search.
- Trending sticker data and unread state.
- Multi-device recent/favorite synchronization.
- Sticker edit/delete authorization and persistence.
- Attached mask-sticker lookup for arbitrary photos/documents.
- Uploading sticker files and generating thumbnails.
- Sending animated emoji/stickers through the Creanger message/media adapter.

## Implementation rule

The migration path is:

`Creanger API/cache -> LocalModels -> UI adapter -> existing Telegram-style UI`

No layout, color, navigation, or interaction redesign is part of this work.
