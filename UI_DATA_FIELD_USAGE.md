# UI Data Field Usage — Telegram UI → Local Models

Generated after hard purge (Phase 10 Step 1). Only fields actually read by preserved UI/client code.

| UI Type | Fields Actually Consumed by UI/Renderer | Source | Canonical Target |
|---------|------------------------------------------|--------|------------------|
| **Message** | `id` (long/int display), `message` (String text), `media` (MessageMedia), `entities` (bold/url/mention), `date` (int epoch), `out` (bool is outgoing), `peer_id`/`dialog_id` (Peer resolution), `from_id` (sender), `reply_to` (reply header), `attachPath`/`params` (creanger_uuid) | ChatMessageCell, MessageObject, ChatActivity | `MessageModels.CreangerMessage` (id, chatId, senderId, content, status, createdAt, attachments, replyToMessageId) |
| **User** | `id`, `first_name`/`last_name`/`displayName`, `username`, `phone`, `photo` (small/big PhotoSize), `status` (online/offline), `premium`, `bot`, `verified`, `flags` | DialogCell, Profile, AvatarDrawable | `UserModel` (id, username, displayName, avatarUrl, isPremium, isBot, isVerified) — also `AuthModels.CreangerUser` for backend |
| **Chat** | `id`, `title`, `username`, `photo`, `participants_count`, `megagroup`/`isChannel`, `creator`/`admin_rights`, `defaultBannedRights` | ChatObject, DialogCell | `ChatModels.CreangerChat` (id, type, title, username, avatarMediaId, ownerId) |
| **Dialog** | `peer` (Chat/User), `top_message`, `unread_count`, `last_message_date`, `notify_settings`, `folder_id`, `pinned`, `unread_mark` | DialogCell, DialogsActivity | NEW `CreangerDialog` (chatId, lastMessage, unreadCount, isPinned, lastMessageDate) — local |
| **Peer** | `user_id` / `chat_id` / `channel_id` discriminator | MessageObject, DialogObject | NEW `LocalPeer` (kind=USER/CHAT/CHANNEL, id String) |
| **Photo** | `id`, `access_hash` (obsolete), `sizes` (PhotoSize list), `date` | ImageLoader, PhotoViewer | `MediaAttachment` (width, height, publicUrl, thumbnailUrl) + `UploadedMedia` |
| **PhotoSize** | `type` (s m x y), `w`/`h`, `size`, `location` (FileLocation volume/dc), `bytes` | ImageLoader | `LocalPhotoSize` (type, w, h, url) — renderer only needs w/h/url |
| **Document** | `id`, `access_hash`, `mime_type`, `size`, `attributes` (Video/Audio/Sticker), `thumbs`, `file_reference` (obsolete), `dc_id` (obsolete) | DocumentObject, ChatMessageCell | `MediaAttachment` (mimeType, sizeBytes, width, height, duration) |
| **FileLocation** | `volume_id`, `local_id`, `secret`, `dc_id`, `file_reference` — ALL Telegram transport | ImageLoader/FileLoadOperation | **DROP** — replace with `publicUrl`/`deliveryUrl` (Creanger storage) |
| **MessageMedia** | `photo`, `document`, `webpage`, `poll`, `contact`, `geo`, `storyItem`, `media_areas` | MessageObject.getMedia | NEW `LocalMessageMedia` (kind, photo, document, poll, webpage, story) |
| **MessageEntity** | `offset`, `length`, `url` (for TextUrl), `user_id` (MentionName), `language` (Pre), `document_id` (CustomEmoji) | EntityModelMapper, MessageObject | `EntityModel` (existing network model) |
| **WebPage** | `url`, `display_url`, `title`, `description`, `site_name`, `photo`, `duration`, `author`, `embed_url` | ArticleViewer, ChatMessageCell | NEW `LocalWebPage` (url, title, description, siteName, photoUrl) |
| **ReplyMarkup** | `rows` (KeyboardButton[][]), `resize`, `selective`, `single_use` | ChatActivity | NEW `LocalReplyMarkup` (rows, isInline) |
| **Reaction / ReactionCount** | `reaction` (emoji/custom), `count`, `chosen`, `document_id` | ChatMessageCell reactions | `MessageModels.ReactionSummary` + `MessageReaction` (already clean) |
| **Poll** | `id`, `question`, `answers` (PollAnswer), `results` (PollResults), `closed`, `total_voters`, `recent_voters` | ChatMessageCell poll rendering | NEW `LocalPoll` (question, answers, isClosed, canVote) |
| **PollAnswer** | `text`, `option` (byte[]), `voters_count`, `chosen` | Poll rendering | NEW `LocalPollAnswer` |
| **MediaArea** | `channel_id`+`msg_id`, `venue` (geo+title), `reaction` | MessageObject.media_areas | NEW `LocalMediaArea` (kind, geo, title) — only for rendering |
| **VideoSize** | `type`, `w`, `h`, `size`, `video_start_ts` | DocumentObject | **MERGE** into `MediaAttachment` w/h |
| **StickerSet** | `id`, `short_name`, `title`, `count`, `documents` | StickersAlert | NEW `LocalStickerSet` (id, shortName, title, documents) |
| **TodoItem** | `title`, `completed`, `assignee` | SendMessagesHelper todo | NEW `LocalTodoItem` (title, isCompleted) |
| **StoryItem** | `id`, `peer`, `media`, `date`, `media_areas`, `privacy`, `views` | PeerStoriesView, StoriesController | Existing `CreangerStory`? else NEW `LocalStory` (id, mediaUrl, date) |

**Dropped Telegram-only fields:** `constructor`, `accessHash`, `dc_id`, `layer`, `local_id`, `server_id`, `random_id`, `seq`, `pts`, `pts_count`, `rpc flags`, `file_reference` — replaced by UUID/string IDs, `chatSeq`, `publicUrl`.

**Reuse decision:** Message/User/Chat/Dialog/Photo/Document map to existing `MessageModels.CreangerMessage`, `UserModel`/`AuthModels.CreangerUser`, `ChatModels.CreangerChat`; Poll/Reaction/MediaArea/StickerSet/Todo/Story require new plain POJOs.
