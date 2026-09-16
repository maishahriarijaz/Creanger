package com.creanger.app.messenger.creanger.api;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import com.creanger.app.messenger.creanger.model.ApiError;
import com.creanger.app.messenger.creanger.model.ChatModels.ChatMember;
import com.creanger.app.messenger.creanger.model.ChatModels.CreangerChat;
import com.creanger.app.messenger.creanger.model.MessageModels.CreangerMessage;
import com.creanger.app.messenger.creanger.model.MessageModels.MediaAttachment;
import com.creanger.app.messenger.creanger.model.MessageModels.MessagePage;
import com.creanger.app.messenger.creanger.model.MessageModels.MessageReaction;
import com.creanger.app.messenger.creanger.model.MessageModels.MessageStatus;
import com.creanger.app.messenger.creanger.model.MessageModels.MessageStatusUpdate;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin, typed client for the PostgREST/Supabase REST chat data plane. A
 * separate transport (pointed at the chat base URL) carries the short-lived
 * access-token JWT as a Bearer token, so Row Level Security scopes every row
 * to the authenticated user (see {@code current_user_id()} and the chat RLS
 * policies in the Supabase migrations).
 *
 * PostgREST replies with raw JSON arrays (not the Custom Auth Server
 * envelope); list rows map 1:1 onto the {@code ChatModels}/{@code MessageModels}
 * fields. Message ordering and send go through the schema RPCs
 * ({@code get_messages_since}, {@code send_text_message}) or the
 * {@code messages} table with deterministic {@code chat_seq} ordering. All
 * methods are synchronous and MUST be called off the main thread.
 */
public class CreangerChatApiClient {

    private final CreangerHttpTransport transport;

    public CreangerChatApiClient(CreangerHttpTransport transport) {
        this.transport = transport;
    }

    /**
     * Lists the chats the authenticated user can see. The DB applies the
     * {@code chats_select_members} RLS policy (is_chat_member(id) or public),
     * so the user never sees chats they are not a member of.
     */
    public List<CreangerChat> listChats(String accessToken) throws IOException, CreangerApiException {
        Map<String, String> query = query(
                "select", "id,type,title,username,description,avatar_media_id,owner_id,"
                        + "is_verified,is_public,is_archived,created_at,updated_at,deleted_at",
                "order", "updated_at.desc");
        String body = executeList("/rest/v1/chats", query, accessToken);
        List<CreangerChat> out = new ArrayList<>();
        JSONArray rows = parseArray(body);
        for (int i = 0; i < rows.length(); i++) {
            out.add(parseChat(rows.optJSONObject(i)));
        }
        return out;
    }

    /**
     * Lists the membership records of a single chat (owning member always has
     * their own row; members select via the {@code chat_members_select_members}
     * policy).
     */
    public List<ChatMember> listMembers(String accessToken, String chatId) throws IOException, CreangerApiException {
        Map<String, String> query = query(
                "select", "id,chat_id,user_id,role,joined_at,left_at,muted_until,pinned_position,last_read_at",
                "chat_id", "eq." + chatId,
                "order", "joined_at.asc");
        String body = executeList("/rest/v1/chat_members", query, accessToken);
        List<ChatMember> out = new ArrayList<>();
        JSONArray rows = parseArray(body);
        for (int i = 0; i < rows.length(); i++) {
            out.add(parseMember(rows.optJSONObject(i)));
        }
        return out;
    }

    /**
     * Lists messages of a chat, newest first (deterministic {@code chat_seq}
     * DESC ordering from the {@code messages} table). RLS scopes rows to chat
     * members; non-members receive an empty list, never rows.
     *
     * @param beforeSeq cursor: when non-null, only older messages ({@code
     *                  chat_seq < beforeSeq}) are returned — the next page of
     *                  history
     */
    public MessagePage listMessages(String accessToken, String chatId, int limit,
                                    @Nullable Long beforeSeq) throws IOException, CreangerApiException {
        if (limit <= 0) {
            return new MessagePage(new ArrayList<>(), beforeSeq, false);
        }
        Map<String, String> query = query(
                "select", MESSAGE_SELECT,
                "chat_id", "eq." + chatId,
                "order", "chat_seq.desc",
                "limit", String.valueOf(limit + 1)); // one extra row probes for more history
        if (beforeSeq != null) {
            query.put("chat_seq", "lt." + beforeSeq);
        }
        String body = executeList("/rest/v1/messages", query, accessToken);
        JSONArray rows = parseArray(body);
        boolean hasMore = rows.length() > limit;
        List<CreangerMessage> out = new ArrayList<>();
        int keep = Math.min(rows.length(), limit);
        for (int i = 0; i < keep; i++) {
            out.add(parseMessage(rows.optJSONObject(i)));
        }
        Long next = null;
        if (!out.isEmpty() && hasMore) {
            // Newest-first; the last (oldest) row of the page is the cursor for
            // the next older page. Only meaningful when more history exists.
            CreangerMessage oldest = out.get(out.size() - 1);
            if (oldest.chatSeq != null) {
                next = oldest.chatSeq;
            }
        }
        return new MessagePage(out, next, hasMore);
    }

    /**
     * Forward sync via the {@code get_messages_since} RPC: all messages in the
     * chat with {@code chat_seq > afterSeq}, ordered {@code chat_seq} ASC
     * (oldest of the new first). Empty for non-members.
     */
    public List<CreangerMessage> getMessagesSince(String accessToken, String chatId, long afterSeq, int limit)
            throws IOException, CreangerApiException {
        JSONObject args = new JSONObject();
        try {
            args.put("p_chat_id", chatId);
            args.put("p_after_seq", afterSeq);
            args.put("p_limit", limit);
        } catch (JSONException e) {
            throw new CreangerApiException(200, new ApiError(ApiError.INTERNAL_ERROR,
                    "malformed rpc args", null, 0));
        }
        String body = executeJson("/rest/v1/rpc/get_messages_since", args, accessToken);
        List<CreangerMessage> out = new ArrayList<>();
        JSONArray rows = parseArray(body);
        for (int i = 0; i < rows.length(); i++) {
            // get_messages_since omits the predicate column; every row in this
            // response belongs to the queried chat, so default it rather than
            // leaving a null chatId that ingestion would reject.
            out.add(parseMessage(rows.optJSONObject(i), chatId));
        }
        return out;
    }

    /**
     * Sends a text message through the {@code send_text_message} RPC. The
     * client-supplied {@code clientMessageId} makes the send idempotent: a
     * retry with the same {@code (chat, sender, client_message_id)} returns the
     * original message id (first write wins; content is not mutated, per
     * migration 022). {@code replyToMessageId} is the authoritative Creanger
     * UUID of the replied-to message ({@code messages.reply_to_message_id});
     * the schema validates it belongs to the same chat.
     *
     * @return the server message id
     */
    public String sendTextMessage(String accessToken, String chatId, String clientMessageId,
                                  String content, @Nullable String replyToMessageId)
            throws IOException, CreangerApiException {
        return sendTextMessage(accessToken, chatId, clientMessageId, content, replyToMessageId, null);
    }

    /**
     * Sends a text message through the canonical 5-argument
     * {@code send_text_message} contract (migration 033): {@code p_rich_text}
     * carries the TL_iv.RichText-compatible JSON object when the caller has
     * formatting, otherwise NULL for plain text.
     */
    public String sendTextMessage(String accessToken, String chatId, String clientMessageId,
                                  String content, @Nullable String replyToMessageId,
                                  @Nullable JSONObject richText)
            throws IOException, CreangerApiException {
        JSONObject args = new JSONObject();
        try {
            args.put("p_chat_id", chatId);
            args.put("p_client_message_id", clientMessageId);
            args.put("p_content", content);
            args.put("p_rich_text", richText != null ? richText : JSONObject.NULL);
            args.put("p_reply_to_message_id", replyToMessageId != null ? replyToMessageId : JSONObject.NULL);
        } catch (JSONException e) {
            throw new CreangerApiException(200, new ApiError(ApiError.INTERNAL_ERROR,
                    "malformed rpc args", null, 0));
        }
        String body = executeJson("/rest/v1/rpc/send_text_message", args, accessToken);
        return parseUuid(body);
    }

    /** Convenience for a plain (non-reply) text send. */
    public String sendTextMessage(String accessToken, String chatId, String clientMessageId, String content)
            throws IOException, CreangerApiException {
        return sendTextMessage(accessToken, chatId, clientMessageId, content, null);
    }

    /**
     * Sends a media message through the {@code send_media_message} RPC
     * (migration 029: SECURITY DEFINER, member-gated, idempotent, atomic). The
     * message row, every {@code media} row and every {@code message_attachments}
     * row are persisted in ONE server-side transaction.
     *
     * {@code messageType} is one of {@code MessageType.IMAGE/VIDEO/DOCUMENT/
     * AUDIO/VOICE}; {@code caption} becomes the message's {@code content} (a
     * document with no caption is sent with an empty content per the schema's
     * NOT NULL check); {@code attachments} carries only METADATA — the binary
     * bytes are stored externally and are never sent here.
     *
     * Idempotent exactly like {@link #sendTextMessage}: a retry with the same
     * {@code clientMessageId} returns the ORIGINAL message id and never
     * re-inserts or mutates the attachments (first write wins).
     *
     * @return the server message id
     */
    public String sendMediaMessage(String accessToken, String chatId, String clientMessageId,
                                   String messageType, @Nullable String caption,
                                   @Nullable String replyToMessageId,
                                   List<MediaAttachment> attachments)
            throws IOException, CreangerApiException {
        JSONObject args = new JSONObject();
        try {
            args.put("p_chat_id", chatId);
            args.put("p_client_message_id", clientMessageId);
            args.put("p_message_type", messageType);
            args.put("p_caption", caption != null ? caption : JSONObject.NULL);
            args.put("p_reply_to_message_id", replyToMessageId != null ? replyToMessageId : JSONObject.NULL);
            JSONArray attachmentRows = new JSONArray();
            for (MediaAttachment a : attachments) {
                if (a == null) {
                    continue;
                }
                JSONObject row = new JSONObject();
                row.put("position", a.position);
                if (a.caption != null) {
                    row.put("caption", a.caption);
                }
                row.put("storage_provider", a.storageProvider);
                row.put("storage_key", a.storageKey);
                if (a.publicUrl != null) {
                    row.put("public_url", a.publicUrl);
                }
                if (a.deliveryUrl != null) {
                    row.put("delivery_url", a.deliveryUrl);
                }
                row.put("mime_type", a.mimeType);
                row.put("size_bytes", a.sizeBytes);
                if (a.checksum != null) {
                    row.put("checksum", a.checksum);
                }
                if (a.width != null) {
                    row.put("width", a.width);
                }
                if (a.height != null) {
                    row.put("height", a.height);
                }
                if (a.duration != null) {
                    row.put("duration", a.duration);
                }
                if (a.thumbnailUrl != null) {
                    row.put("thumbnail_url", a.thumbnailUrl);
                }
                attachmentRows.put(row);
            }
            args.put("p_attachments", attachmentRows);
        } catch (JSONException e) {
            throw new CreangerApiException(200, new ApiError(ApiError.INTERNAL_ERROR,
                    "malformed rpc args", null, 0));
        }
        String body = executeJson("/rest/v1/rpc/send_media_message", args, accessToken);
        return parseUuid(body);
    }

    /**
     * Fetches the raw attachment rows for a set of message ids via the
     * {@code message_attachments} table with the {@code media} row embedded
     * (PostgREST expand, migration 005). RLS scopes rows to chat members (the
     * {@code message_attachments_select_members} + {@code media_select_owner}
     * policies), so a non-member receives an empty list. This is the attachment
     * SNAPSHOT used to fill media metadata onto rows that arrived without it
     * (RPC recovery, Realtime inserts) after a page load or reconnect.
     */
    public List<MediaAttachment> getAttachments(String accessToken, List<String> messageIds)
            throws IOException, CreangerApiException {
        if (messageIds == null || messageIds.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, String> query = query(
                "select", ATTACHMENT_SELECT,
                "message_id", "in.(" + joinIn(messageIds) + ")",
                "order", "position.asc");
        String body = executeList("/rest/v1/message_attachments", query, accessToken);
        List<MediaAttachment> out = new ArrayList<>();
        JSONArray rows = parseArray(body);
        for (int i = 0; i < rows.length(); i++) {
            MediaAttachment attachment = parseAttachment(rows.optJSONObject(i));
            if (attachment != null) {
                out.add(attachment);
            }
        }
        return out;
    }

    /**
     * Edits an own text message through the author-only {@code edit_message}
     * RPC (migration 022: SECURITY DEFINER, current-caller resolved server
     * side, append-only {@code message_edits} history). Idempotent: editing to
     * the current content is a no-op that still returns the message id. A
     * caller that is not the author gets an observable error (never a silent
     * success), so the optimistic local edit can be rolled back.
     *
     * @return the message id the server confirmed (same as {@code messageId})
     */
    public String editMessage(String accessToken, String messageId, String newContent)
            throws IOException, CreangerApiException {
        JSONObject args = new JSONObject();
        try {
            args.put("p_message_id", messageId);
            args.put("p_new_content", newContent);
        } catch (JSONException e) {
            throw new CreangerApiException(200, new ApiError(ApiError.INTERNAL_ERROR,
                    "malformed rpc args", null, 0));
        }
        String body = executeJson("/rest/v1/rpc/edit_message", args, accessToken);
        return parseUuid(body);
    }

    /**
     * Deletes an own text message through the author-only {@code delete_message}
     * RPC (migration 025: SECURITY DEFINER, soft delete via
     * {@code messages.deleted_at}). Idempotent: deleting an already-deleted
     * message returns the same id. A non-author gets an observable error.
     *
     * @return the message id the server confirmed (same as {@code messageId})
     */
    public String deleteMessage(String accessToken, String messageId)
            throws IOException, CreangerApiException {
        JSONObject args = new JSONObject();
        try {
            args.put("p_message_id", messageId);
        } catch (JSONException e) {
            throw new CreangerApiException(200, new ApiError(ApiError.INTERNAL_ERROR,
                    "malformed rpc args", null, 0));
        }
        String body = executeJson("/rest/v1/rpc/delete_message", args, accessToken);
        return parseUuid(body);
    }

    /**
     * Marks the delivery/read state of a message through the recipient-only
     * {@code mark_message_status} RPC (migration 026: SECURITY DEFINER,
     * membership + author-of-own-message guards, monotonic advance). Only
     * {@code delivered}/{@code read} are accepted targets; a call that does not
     * advance (an echo, an out-of-order delivered after a read) is an idempotent
     * no-op returning the same message id. A non-member or the author gets an
     * observable error (never a silent success).
     *
     * @return the message id the server confirmed (same as {@code messageId})
     */
    public String markMessageStatus(String accessToken, String messageId, String status)
            throws IOException, CreangerApiException {
        JSONObject args = new JSONObject();
        try {
            args.put("p_message_id", messageId);
            args.put("p_status", status);
        } catch (JSONException e) {
            throw new CreangerApiException(200, new ApiError(ApiError.INTERNAL_ERROR,
                    "malformed rpc args", null, 0));
        }
        String body = executeJson("/rest/v1/rpc/mark_message_status", args, accessToken);
        return parseUuid(body);
    }

    /**
     * Recovery for status changes missed while Realtime was down, via the
     * {@code get_message_statuses_since} RPC (migration 026): every message
     * with {@code status IN ('delivered','read')} that changed after
     * {@code afterUpdatedAt}, ordered {@code updated_at} ASC. Status changes do
     * not bump {@code chat_seq}, so this is the cursor that complements
     * {@link #getMessagesSince}. Empty for non-members. A {@code null}
     * watermark cursor means "everything".
     */
    public List<MessageStatusUpdate> getMessageStatusesSince(String accessToken, String chatId,
                                                             @Nullable String afterUpdatedAt, int limit)
            throws IOException, CreangerApiException {
        JSONObject args = new JSONObject();
        try {
            args.put("p_chat_id", chatId);
            args.put("p_after_updated_at", afterUpdatedAt != null ? afterUpdatedAt : JSONObject.NULL);
            args.put("p_limit", limit);
        } catch (JSONException e) {
            throw new CreangerApiException(200, new ApiError(ApiError.INTERNAL_ERROR,
                    "malformed rpc args", null, 0));
        }
        String body = executeJson("/rest/v1/rpc/get_message_statuses_since", args, accessToken);
        List<MessageStatusUpdate> out = new ArrayList<>();
        JSONArray rows = parseArray(body);
        for (int i = 0; i < rows.length(); i++) {
            JSONObject o = rows.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String statusUpdate = nullIfEmpty(o.optString("status", null));
            if (statusUpdate == null) {
                statusUpdate = MessageStatus.SENT;
            }
            out.add(new MessageStatusUpdate(
                    o.has("message_id") && !o.isNull("message_id") ? nullIfEmpty(o.optString("message_id", null)) : null,
                    statusUpdate,
                    nullIfEmpty(o.optString("updated_at", null))));
        }
        return out;
    }

    /**
     * Recovery for edits/deletes missed while Realtime was down, via the
     * {@code get_message_changes_since} RPC (migration 028): every message
     * with {@code edited_at} or {@code deleted_at} set that changed after
     * {@code afterUpdatedAt} (ordered {@code updated_at} ASC, full projection
     * so a recovered edit can replace the cached row verbatim). Edits and
     * soft-deletes never bump {@code chat_seq}, so neither
     * {@link #getMessagesSince} nor {@link #getMessageStatusesSince} can
     * recover them — this is the {@code updated_at} cursor that closes that
     * gap. Soft-deleted tombstones are returned so the client can drop the
     * row. Empty for non-members. A {@code null} watermark means "everything".
     */
    public List<CreangerMessage> getMessageChangesSince(String accessToken, String chatId,
                                                        @Nullable String afterUpdatedAt, int limit)
            throws IOException, CreangerApiException {
        JSONObject args = new JSONObject();
        try {
            args.put("p_chat_id", chatId);
            args.put("p_after_updated_at", afterUpdatedAt != null ? afterUpdatedAt : JSONObject.NULL);
            args.put("p_limit", limit);
        } catch (JSONException e) {
            throw new CreangerApiException(200, new ApiError(ApiError.INTERNAL_ERROR,
                    "malformed rpc args", null, 0));
        }
        String body = executeJson("/rest/v1/rpc/get_message_changes_since", args, accessToken);
        List<CreangerMessage> out = new ArrayList<>();
        JSONArray rows = parseArray(body);
        for (int i = 0; i < rows.length(); i++) {
            out.add(parseMessage(rows.optJSONObject(i)));
        }
        return out;
    }

    /**
     * Adds the authenticated user's reaction to a message through the
     * {@code add_reaction} RPC (migration 027: SECURITY DEFINER, member-gated,
     * idempotent). Re-adding the SAME reaction to the same message is a no-op
     * (first write wins per the {@code message_reactions_unique} constraint);
     * a DIFFERENT reaction on the same message adds a second row. A non-member
     * or a deleted message yields an observable error (never a silent success).
     *
     * @return the message id the server confirmed
     */
    public String addReaction(String accessToken, String messageId, String reaction)
            throws IOException, CreangerApiException {
        return addReaction(accessToken, messageId, reaction, false, null);
    }

    /**
     * Adds the current user's reaction, carrying custom {@link
     * com.creanger.app.messenger.creanger.model.MessageModels.MessageReaction
     * MessageReaction} fields so a custom/Premium reaction (migration 027
     * {@code p_is_custom_emoji} / {@code p_custom_emoji_id}) is stored
     * correctly server-side. Emoji reactions pass {@code isCustomEmoji=false}.
     */
    public String addReaction(String accessToken, String messageId, String reaction,
                              boolean isCustomEmoji, String customEmojiId)
            throws IOException, CreangerApiException {
        JSONObject args = new JSONObject();
        try {
            args.put("p_message_id", messageId);
            args.put("p_reaction", reaction);
            args.put("p_is_custom_emoji", isCustomEmoji);
            if (isCustomEmoji && customEmojiId != null) {
                args.put("p_custom_emoji_id", customEmojiId);
            }
        } catch (JSONException e) {
            throw new CreangerApiException(200, new ApiError(ApiError.INTERNAL_ERROR,
                    "malformed rpc args", null, 0));
        }
        String body = executeJson("/rest/v1/rpc/add_reaction", args, accessToken);
        return parseUuid(body);
    }

    /**
     * Removes the authenticated user's reaction from a message through the
     * {@code remove_reaction} RPC (migration 027: SECURITY DEFINER, own-row
     * scoped, idempotent). Removing a reaction that is not (or no longer)
     * present is a no-op returning the same message id. Never touches another
     * user's reactions.
     *
     * @return the message id the server confirmed
     */
    public String removeReaction(String accessToken, String messageId, String reaction)
            throws IOException, CreangerApiException {
        return removeReaction(accessToken, messageId, reaction, false, null);
    }

    /**
     * Removes the current user's reaction, mirroring the custom {@link
     * com.creanger.app.messenger.creanger.model.MessageModels.MessageReaction
     * MessageReaction} fields used when it was added. The RPC is own-row
     * scoped, so only the caller's matching row is ever removed server-side.
     */
    public String removeReaction(String accessToken, String messageId, String reaction,
                                 boolean isCustomEmoji, String customEmojiId)
            throws IOException, CreangerApiException {
        JSONObject args = new JSONObject();
        try {
            args.put("p_message_id", messageId);
            args.put("p_reaction", reaction);
            args.put("p_is_custom_emoji", isCustomEmoji);
            if (isCustomEmoji && customEmojiId != null) {
                args.put("p_custom_emoji_id", customEmojiId);
            }
        } catch (JSONException e) {
            throw new CreangerApiException(200, new ApiError(ApiError.INTERNAL_ERROR,
                    "malformed rpc args", null, 0));
        }
        String body = executeJson("/rest/v1/rpc/remove_reaction", args, accessToken);
        return parseUuid(body);
    }

    /**
     * Fetches the current reaction rows for a set of message ids ({@code
     * message_id=in.(...)} on the {@code message_reactions} table). RLS scopes
     * rows to chat members (the {@code message_reactions_select_member}
     * policy), so a non-member receives an empty list. This is the reaction
     * SNAPSHOT used both when a page of messages is loaded and after a
     * Realtime reconnect: reactions can be deleted, so the client replaces its
     * per-message state from this source rather than trusting a watermark
     * cursor.
     */
    public List<MessageReaction> getReactions(String accessToken, List<String> messageIds)
            throws IOException, CreangerApiException {
        if (messageIds == null || messageIds.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, String> query = query(
                "select", "message_id,user_id,reaction,is_custom_emoji,custom_emoji_id,created_at,updated_at",
                "message_id", "in.(" + joinIn(messageIds) + ")",
                "order", "updated_at.asc");
        String body = executeList("/rest/v1/message_reactions", query, accessToken);
        List<MessageReaction> out = new ArrayList<>();
        JSONArray rows = parseArray(body);
        for (int i = 0; i < rows.length(); i++) {
            JSONObject o = rows.optJSONObject(i);
            if (o == null) {
                continue;
            }
            out.add(new MessageReaction(
                    nullIfEmpty(o.optString("message_id", null)),
                    nullIfEmpty(o.optString("user_id", null)),
                    nullIfEmpty(o.optString("reaction", null)),
                    o.optBoolean("is_custom_emoji", false),
                    nullIfEmpty(o.optString("custom_emoji_id", null)),
                    nullIfEmpty(o.optString("created_at", null)),
                    nullIfEmpty(o.optString("updated_at", null))));
        }
        return out;
    }

    // ---- message parsing ----

    private static final String ATTACHMENT_SELECT =
            "id,message_id,position,caption,"
                    + "media(id,owner_id,storage_provider,storage_key,public_url,delivery_url,"
                    + "mime_type,size_bytes,checksum,width,height,duration,thumbnail_media_id,"
                    + "thumbnail_url,created_at,deleted_at)";

    private static final String MESSAGE_SELECT =
            "id,chat_id,sender_id,message_type,content,status,client_message_id,chat_seq,"
                    + "reply_to_message_id,created_at,edited_at,deleted_at,updated_at,"
                    + "message_attachments(" + ATTACHMENT_SELECT + ")";

    private static CreangerMessage parseMessage(JSONObject o) {
        return parseMessage(o, null);
    }

    private static CreangerMessage parseMessage(JSONObject o, @Nullable String defaultChatId) {
        if (o == null) {
            return null;
        }
        // RPC rows (get_messages_since) key the id as `message_id`; table rows
        // use `id`. Accept both.
        String id = nullIfEmpty(o.has("message_id") ? o.optString("message_id", null)
                : o.optString("id", null));
        String chatId = nullIfEmpty(o.optString("chat_id", null));
        if ((chatId == null || chatId.isEmpty()) && defaultChatId != null) {
            chatId = defaultChatId;
        }
        // On Android, optString never yields its default for JSON null (it
        // yields the literal "null"), so normalize first, then fall back.
        String status = nullIfEmpty(o.optString("status", null));
        if (status == null) {
            status = MessageStatus.SENT;
        }
        CreangerMessage message = new CreangerMessage(
                id,
                chatId,
                nullIfEmpty(o.optString("sender_id", null)),
                nullIfEmpty(o.optString("message_type", null)),
                nullIfEmpty(o.optString("content", null)),
                status,
                nullIfEmpty(o.optString("client_message_id", null)),
                nullableLong(o, "chat_seq"),
                nullIfEmpty(o.optString("created_at", null)),
                nullIfEmpty(o.optString("edited_at", null)),
                nullIfEmpty(o.optString("deleted_at", null)),
                nullIfEmpty(o.optString("updated_at", null)),
                nullIfEmpty(o.optString("reply_to_message_id", null)),
                false,
                parseAttachments(o));
        JSONObject richText = o.optJSONObject("rich_text");
        if (richText != null) {
            message.setRichText(richText);
        }
        return message;
    }

    /**
     * Parses the embedded {@code message_attachments} resource of a messages
     * row (PostgREST expand) into {@link MediaAttachment} values. RPC rows that
     * carry no expand produce an empty list; the client fills those from
     * {@link #getAttachments} (snapshot).
     */
    private static List<MediaAttachment> parseAttachments(JSONObject o) {
        JSONArray rows = o != null ? o.optJSONArray("message_attachments") : null;
        if (rows == null) {
            return new ArrayList<>();
        }
        List<MediaAttachment> out = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            MediaAttachment attachment = parseAttachment(rows.optJSONObject(i));
            if (attachment != null) {
                out.add(attachment);
            }
        }
        return out;
    }

    /**
     * Parses one {@code message_attachments} row (with the {@code media} row
     * embedded) into a {@link MediaAttachment}. Rows without a media object
     * (or with missing required media columns) are skipped.
     */
    private static MediaAttachment parseAttachment(JSONObject row) {
        if (row == null) {
            return null;
        }
        JSONObject media = row.optJSONObject("media");
        if (media == null) {
            return null;
        }
        String storageProvider = nullIfEmpty(media.optString("storage_provider", null));
        String storageKey = nullIfEmpty(media.optString("storage_key", null));
        String mimeType = nullIfEmpty(media.optString("mime_type", null));
        if (storageProvider == null || storageKey == null || mimeType == null) {
            return null;
        }
        return new MediaAttachment(
                nullIfEmpty(row.optString("id", null)),
                nullIfEmpty(row.optString("message_id", null)),
                nullIfEmpty(media.optString("id", null)),
                row.optInt("position", 0),
                nullIfEmpty(row.optString("caption", null)),
                storageProvider,
                storageKey,
                nullIfEmpty(media.optString("public_url", null)),
                nullIfEmpty(media.optString("delivery_url", null)),
                mimeType,
                media.optLong("size_bytes", 0),
                nullIfEmpty(media.optString("checksum", null)),
                nullableInt(media, "width"),
                nullableInt(media, "height"),
                nullableInt(media, "duration"),
                nullIfEmpty(media.optString("thumbnail_media_id", null)),
                nullIfEmpty(media.optString("local_path", null)),
                nullIfEmpty(media.optString("thumbnail_url", null)),
                nullIfEmpty(media.optString("preview_url", null)),
                nullIfEmpty(media.optString("deleted_at", null)),
                nullIfEmpty(media.optString("created_at", null)));
    }

    private static Long nullableLong(JSONObject o, String key) {
        if (o.isNull(key)) {
            return null;
        }
        return o.optLong(key);
    }

    private static Integer nullableInt(JSONObject o, String key) {
        if (o.isNull(key)) {
            return null;
        }
        return o.optInt(key);
    }

    private static String parseUuid(String body) throws CreangerApiException {
        if (body == null || body.trim().isEmpty()) {
            throw new CreangerApiException(200, new ApiError(ApiError.INTERNAL_ERROR,
                    "send returned an empty id", null, 0));
        }
        try {
            // PostgREST returns a scalar UUID as a quoted JSON string.
            Object value = new JSONTokener(body.trim()).nextValue();
            if (value instanceof String) {
                return (String) value;
            }
            if (value instanceof java.lang.Number) {
                return value.toString();
            }
            throw new CreangerApiException(200, new ApiError(ApiError.INTERNAL_ERROR,
                    "send returned a non-UUID body", null, 0));
        } catch (JSONException e) {
            throw new CreangerApiException(200, new ApiError(ApiError.INTERNAL_ERROR,
                    "malformed send response", null, 0));
        }
    }

    // ---- search (migration 017 RPC) ----

    /**
     * Searches messages in a chat via the {@code search_messages_in_chat} RPC
     * (migration 017: membership-gated, full-text ILIKE on {@code content}).
     * Returns matching messages ordered by {@code created_at DESC}.
     * Empty list for non-members or no matches.
     */
    public List<CreangerMessage> searchMessagesInChat(String accessToken, String chatId,
                                                      String query, int limit)
            throws IOException, CreangerApiException {
        JSONObject args = new JSONObject();
        try {
            args.put("p_chat_id", chatId);
            args.put("p_search_query", query);
            args.put("p_limit", limit);
        } catch (JSONException e) {
            throw new CreangerApiException(200, new ApiError(ApiError.INTERNAL_ERROR,
                    "malformed rpc args", null, 0));
        }
        String body = executeJson("/rest/v1/rpc/search_messages_in_chat", args, accessToken);
        List<CreangerMessage> out = new ArrayList<>();
        JSONArray rows = parseArray(body);
        for (int i = 0; i < rows.length(); i++) {
            out.add(parseMessage(rows.optJSONObject(i)));
        }
        return out;
    }

    // ---- pin/unpin messages (pin_message RPC, migration 038) ----

    /**
     * Pins a message through the {@code pin_message} RPC (migration 038:
     * SECURITY DEFINER, sender-or-admin gated). The server merges
     * {@code metadata.pinned = true}, preserving all other metadata keys.
     * Idempotent: pinning an already-pinned message is a no-op.
     *
     * @return the message id the server confirmed (same as {@code messageId})
     */
    public String pinMessage(String accessToken, String messageId)
            throws IOException, CreangerApiException {
        return setPinned(accessToken, messageId, true);
    }

    /**
     * Unpins a message through the {@code pin_message} RPC. Idempotent:
     * unpinning an already-unpinned message is a no-op.
     *
     * @return the message id the server confirmed (same as {@code messageId})
     */
    public String unpinMessage(String accessToken, String messageId)
            throws IOException, CreangerApiException {
        return setPinned(accessToken, messageId, false);
    }

    /**
     * Sets or clears {@code metadata.pinned} server-side. The RPC owns the
     * metadata mutation and its authorization; clients hold no direct UPDATE
     * grant on {@code messages.metadata}.
     */
    private String setPinned(String accessToken, String messageId, boolean pinned)
            throws IOException, CreangerApiException {
        JSONObject args = new JSONObject();
        try {
            args.put("p_message_id", messageId);
            args.put("p_pinned", pinned);
        } catch (JSONException e) {
            throw new CreangerApiException(200, new ApiError(ApiError.INTERNAL_ERROR,
                    "malformed rpc args", null, 0));
        }
        String body = executeJson("/rest/v1/rpc/pin_message", args, accessToken);
        return parseUuid(body);
    }

    /**
     * Returns all pinned messages for the given chat.  A message is pinned
     * when its {@code metadata->>'pinned'} is {@code 'true'}.  Results are
     * ordered by {@code created_at DESC} (newest pin first).
     */
    public List<CreangerMessage> getPinnedMessages(String accessToken, String chatId)
            throws IOException, CreangerApiException {
        Map<String, String> q = new HashMap<>();
        q.put("select", MESSAGE_SELECT);
        q.put("chat_id", "eq." + chatId);
        q.put("metadata->>pinned", "eq.true");
        q.put("order", "created_at.desc");
        q.put("deleted_at", "is.null");
        String body = executeList("/rest/v1/messages", q, accessToken);
        List<CreangerMessage> out = new ArrayList<>();
        JSONArray rows = parseArray(body);
        for (int i = 0; i < rows.length(); i++) {
            out.add(parseMessage(rows.optJSONObject(i)));
        }
        return out;
    }

    // ---- drafts (migration 006 REST upsert/fetch/delete, 038 uniqueness) ----

    /**
     * Saves (upserts) a draft scoped to {@code (ownerId, chatId, topicId)}.
     * The owner id is the authenticated user id supplied by the repository
     * layer — never caller input — so one user can neither see nor overwrite
     * another's draft. A lost upsert race (409 on the unique index) falls
     * back to UPDATE.
     *
     * @param ownerId    the authenticated user id (draft owner)
     * @param chatId     the Creanger chat UUID
     * @param content    the draft text (null or empty to delete the draft)
     * @param replyToId  the Creanger UUID of the replied-to message, or null
     * @param topicId    the topic UUID (null for main chat)
     */
    public void saveDraft(String accessToken, String ownerId, String chatId, @Nullable String content,
                          @Nullable String replyToId, @Nullable String topicId)
            throws IOException, CreangerApiException {
        if (content == null || content.isEmpty()) {
            deleteDraft(accessToken, ownerId, chatId, topicId);
            return;
        }
        // Try UPDATE first (existing row for this user+chat+topic)
        Map<String, String> matchQuery = draftScopeQuery(ownerId, chatId, topicId);
        matchQuery.put("select", "id");
        String matchBody = executeList("/rest/v1/drafts", matchQuery, accessToken);
        JSONArray existing = parseArray(matchBody);
        if (existing.length() > 0) {
            // UPDATE existing draft
            JSONObject patch = new JSONObject();
            try {
                patch.put("content", content);
                if (replyToId != null) {
                    patch.put("reply_to_message_id", replyToId);
                } else {
                    patch.put("reply_to_message_id", JSONObject.NULL);
                }
            } catch (JSONException e) {
                throw new CreangerApiException(200, new ApiError(ApiError.INTERNAL_ERROR,
                        "malformed draft patch", null, 0));
            }
            executePatch("/rest/v1/drafts", matchQuery, patch.toString(), accessToken);
        } else {
            // INSERT new draft
            JSONObject row = new JSONObject();
            try {
                row.put("user_id", ownerId);
                row.put("chat_id", chatId);
                row.put("content", content);
                if (replyToId != null) {
                    row.put("reply_to_message_id", replyToId);
                }
                if (topicId != null) {
                    row.put("topic_id", topicId);
                }
            } catch (JSONException e) {
                throw new CreangerApiException(200, new ApiError(ApiError.INTERNAL_ERROR,
                        "malformed draft row", null, 0));
            }
            JSONArray arr = new JSONArray();
            arr.put(row);
            try {
                executeInsert("/rest/v1/drafts", arr.toString(), accessToken);
            } catch (CreangerApiException e) {
                if (e.statusCode == 409) {
                    // Lost the upsert race: the row now exists, update it.
                    JSONObject patch = new JSONObject();
                    try {
                        patch.put("content", content);
                        if (replyToId != null) {
                            patch.put("reply_to_message_id", replyToId);
                        } else {
                            patch.put("reply_to_message_id", JSONObject.NULL);
                        }
                    } catch (JSONException je) {
                        throw new CreangerApiException(200, new ApiError(ApiError.INTERNAL_ERROR,
                                "malformed draft patch", null, 0));
                    }
                    executePatch("/rest/v1/drafts", matchQuery, patch.toString(), accessToken);
                } else {
                    throw e;
                }
            }
        }
    }

    /** Main-chat save (topic null). */
    public void saveDraft(String accessToken, String ownerId, String chatId, @Nullable String content,
                          @Nullable String replyToId)
            throws IOException, CreangerApiException {
        saveDraft(accessToken, ownerId, chatId, content, replyToId, null);
    }

    /**
     * Builds the per-owner draft scope filter. Topic rows match
     * {@code topic_id=eq.<id>}; main-chat rows match {@code topic_id=is.null},
     * so topics stay independent and the main chat keeps exactly one draft.
     */
    private static Map<String, String> draftScopeQuery(String ownerId, String chatId,
                                                       @Nullable String topicId) {
        Map<String, String> q = new HashMap<>();
        q.put("user_id", "eq." + ownerId);
        q.put("chat_id", "eq." + chatId);
        q.put("topic_id", topicId == null ? "is.null" : "eq." + topicId);
        return q;
    }

    /**
     * Fetches the owner's draft for the given chat+topic. Returns {@code null}
     * when no draft exists.
     */
    @Nullable
    public DraftInfo getDraft(String accessToken, String ownerId, String chatId,
                              @Nullable String topicId)
            throws IOException, CreangerApiException {
        Map<String, String> query = draftScopeQuery(ownerId, chatId, topicId);
        query.put("select", "id,content,reply_to_message_id,topic_id,updated_at");
        String body = executeList("/rest/v1/drafts", query, accessToken);
        JSONArray rows = parseArray(body);
        if (rows.length() == 0) {
            return null;
        }
        JSONObject row = rows.optJSONObject(0);
        if (row == null) {
            return null;
        }
        String content = nullIfEmpty(row.optString("content", null));
        if (content == null || content.isEmpty()) {
            return null;
        }
        return new DraftInfo(
                content,
                nullIfEmpty(row.optString("reply_to_message_id", null)),
                nullIfEmpty(row.optString("topic_id", null)),
                nullIfEmpty(row.optString("updated_at", null)));
    }

    /** Main-chat fetch (topic null). */
    @Nullable
    public DraftInfo getDraft(String accessToken, String ownerId, String chatId)
            throws IOException, CreangerApiException {
        return getDraft(accessToken, ownerId, chatId, null);
    }

    /**
     * Deletes the owner's draft for the given chat+topic (idempotent).
     */
    public void deleteDraft(String accessToken, String ownerId, String chatId, @Nullable String topicId)
            throws IOException, CreangerApiException {
        executeDelete("/rest/v1/drafts", draftScopeQuery(ownerId, chatId, topicId), accessToken);
    }

    /** Lightweight draft data transfer object. */
    public static final class DraftInfo {
        public final String content;
        @Nullable public final String replyToMessageId;
        @Nullable public final String topicId;
        @Nullable public final String updatedAt;

        public DraftInfo(String content, @Nullable String replyToMessageId,
                         @Nullable String topicId, @Nullable String updatedAt) {
            this.content = content;
            this.replyToMessageId = replyToMessageId;
            this.topicId = topicId;
            this.updatedAt = updatedAt;
        }
    }

    // ---- forward messages (migration 004 message_forwards + existing send RPCs) ----

    /**
     * Forwards a Creanger message to another Creanger chat.
     *
     * The method performs three steps on the server:
     * <ol>
     *   <li>Reads the source message (content + type) from the {@code messages} table.</li>
     *   <li>Creates a <em>new</em> message in {@code destChatId} via the existing
     *       {@code send_text_message} or {@code send_media_message} RPCs (so the
     *       destination chat receives a real Creanger message that participates in
     *       realtime subscriptions, reactions, edits, etc.).</li>
     *   <li>Inserts a {@code message_forwards} row that links
     *       {@code original_message_id → forwarded_message_id}.
     *       The INSERT is gated by RLS:
     *       {@code forwarded_by = current_user_id()} + membership checks.</li>
     * </ol>
     *
     * For <b>media</b> messages the source attachments are read from
     * {@code message_attachments} and re-attached to the new message, so the
     * destination receives the same media payload.
     *
     * @param userId      the current user's Supabase UUID (needed for the
     *                    {@code forwarded_by} column, which has no DEFAULT and
     *                    must match {@code current_user_id()} per RLS).
     * @param sourceMsgId the Creanger UUID of the message to forward.
     * @param destChatId  the Creanger chat UUID of the destination chat.
     * @return the {@code forwarded_message_id} (new message UUID in the destination).
     */
    public String forwardMessage(String accessToken, String userId, String sourceMsgId,
                                 String destChatId)
            throws IOException, CreangerApiException {
        // 1. Read source message
        Map<String, String> srcQ = query(
                "select", "id,chat_id,message_type,content",
                "id", "eq." + sourceMsgId);
        String srcBody = executeList("/rest/v1/messages", srcQ, accessToken);
        JSONArray srcRows = parseArray(srcBody);
        if (srcRows.length() == 0) {
            throw new CreangerApiException(404, new ApiError(ApiError.NOT_FOUND,
                    "source message not found", null, 0));
        }
        JSONObject src = srcRows.optJSONObject(0);
        String messageType = src.optString("message_type", "text");
        String content = src.optString("content", "");

        // 2. Create new message in destination
        String clientMsgId = java.util.UUID.randomUUID().toString();
        String newMsgId;
        if ("text".equals(messageType)) {
            newMsgId = sendTextMessage(accessToken, destChatId, clientMsgId, content);
        } else {
            // Read source attachments and re-create them in the destination
            List<MediaAttachment> atts = getAttachments(accessToken,
                    java.util.Collections.singletonList(sourceMsgId));
            newMsgId = sendMediaMessage(accessToken, destChatId, clientMsgId,
                    messageType, content, null, atts);
        }

        // 3. Insert message_forwards record
        JSONObject fwRow = new JSONObject();
        try {
            fwRow.put("original_message_id", sourceMsgId);
            fwRow.put("forwarded_by", userId);
            fwRow.put("forwarded_to_chat_id", destChatId);
            fwRow.put("forwarded_message_id", newMsgId);
        } catch (JSONException e) {
            throw new CreangerApiException(200, new ApiError(ApiError.INTERNAL_ERROR,
                    "malformed forward record", null, 0));
        }
        JSONArray arr = new JSONArray();
        arr.put(fwRow);
        executeInsert("/rest/v1/message_forwards", arr.toString(), accessToken);

        return newMsgId;
    }

    // ---- edit media: replace message_attachments (no migration needed) ----

    /**
     * Replaces the media attachment on an existing message.  Composes from
     * existing primitives: (1) DELETE old {@code message_attachments} row,
     * (2) INSERT new row pointing to the new {@code media.id}.
     * RLS allows sender to DELETE/INSERT on their own messages.
     *
     * @param accessToken  JWT for the authenticated user (message author)
     * @param messageId    UUID of the existing message to edit
     * @param oldMediaId   UUID of the current media to replace (for targeted DELETE)
     * @param newMediaId   UUID of the newly uploaded media row
     * @param position     attachment position (usually 0)
     * @param caption      new caption (null to preserve existing)
     */
    public void replaceMediaAttachment(String accessToken, String messageId,
                                       String oldMediaId, String newMediaId,
                                       int position, @Nullable String caption)
            throws IOException, CreangerApiException {
        // 1. Delete old attachment link
        Map<String, String> delQuery = new java.util.LinkedHashMap<>();
        delQuery.put("message_id", "eq." + messageId);
        delQuery.put("media_id", "eq." + oldMediaId);
        executeDelete("/rest/v1/message_attachments", delQuery, accessToken);

        // 2. Insert new attachment link
        JSONObject newRow = new JSONObject();
        try {
            newRow.put("message_id", messageId);
            newRow.put("media_id", newMediaId);
            newRow.put("position", position);
            if (caption != null) {
                newRow.put("caption", caption);
            }
        } catch (JSONException e) {
            throw new CreangerApiException(200, new ApiError(ApiError.INTERNAL_ERROR,
                    "malformed attachment payload", null, 0));
        }
        JSONArray arr = new JSONArray();
        arr.put(newRow);
        executeInsert("/rest/v1/message_attachments", arr.toString(), accessToken);
    }

    /**
     * Updates the caption on an existing message via the {@code edit_message}
     * SECURITY DEFINER RPC (migration 033).  Pass {@code null} to skip.
     */
    public void editMessageCaption(String accessToken, String messageId, String newCaption)
            throws IOException, CreangerApiException {
        if (newCaption == null) {
            return;
        }
        JSONObject args = new JSONObject();
        try {
            args.put("p_message_id", messageId);
            args.put("p_new_content", newCaption);
        } catch (JSONException e) {
            throw new CreangerApiException(200, new ApiError(ApiError.INTERNAL_ERROR,
                    "malformed rpc args", null, 0));
        }
        executeJson("/rest/v1/rpc/edit_message", args, accessToken);
    }

    /**
     * Inserts a new {@code media} row via PostgREST.  The authenticated user
     * becomes the {@code owner_id} (RLS: {@code media_insert_owner}).  Returns
     * the new {@code media.id}.
     */
    public String insertMedia(String accessToken, String ownerId, String storageProvider,
                               String storageKey, @Nullable String publicUrl,
                               @Nullable String deliveryUrl, String mimeType,
                               long sizeBytes, @Nullable Integer width,
                               @Nullable Integer height, @Nullable Integer duration)
            throws IOException, CreangerApiException {
        JSONObject row = new JSONObject();
        try {
            // owner_id is required (NOT NULL, RLS WITH CHECK) and always the
            // authenticated owner supplied by the repository layer.
            row.put("owner_id", ownerId);
            row.put("storage_provider", storageProvider);
            row.put("storage_key", storageKey);
            if (publicUrl != null) row.put("public_url", publicUrl);
            if (deliveryUrl != null) row.put("delivery_url", deliveryUrl);
            row.put("mime_type", mimeType);
            row.put("size_bytes", sizeBytes);
            if (width != null) row.put("width", width);
            if (height != null) row.put("height", height);
            if (duration != null) row.put("duration", duration);
        } catch (JSONException e) {
            throw new CreangerApiException(200, new ApiError(ApiError.INTERNAL_ERROR,
                    "malformed media payload", null, 0));
        }
        JSONArray arr = new JSONArray();
        arr.put(row);
        executeInsert("/rest/v1/media", arr.toString(), accessToken);
        // Query back the media row by storage_key + mime_type to get the id.
        // PostgREST doesn't support RETURNING without Prefer header on our transport.
        Map<String, String> q = new java.util.LinkedHashMap<>();
        q.put("storage_key", "eq." + storageKey);
        q.put("mime_type", "eq." + mimeType);
        q.put("order", "created_at.desc");
        q.put("limit", "1");
        String body = executeList("/rest/v1/media", q, accessToken);
        JSONArray result = parseArray(body);
        if (result.length() == 0) {
            throw new CreangerApiException(200, new ApiError(ApiError.INTERNAL_ERROR,
                    "media insert succeeded but readback failed", null, 0));
        }
        JSONObject mediaRow = result.optJSONObject(0);
        if (mediaRow == null) {
            throw new CreangerApiException(200, new ApiError(ApiError.INTERNAL_ERROR,
                    "media insert succeeded but readback failed", null, 0));
        }
        return mediaRow.optString("id", null);
    }

    // ---- presence (migration 036 RPC + user_presence REST read) ----

    /**
     * Sets the caller's own presence via the {@code set_presence} SECURITY
     * DEFINER RPC (migration 036). {@code status} must be one of
     * {@link ChatModels.UserPresence} constants. When {@code touchLastSeen}
     * is true the row's last_seen_at is refreshed (heartbeat); pass false for
     * a pure status transition.
     */
    public void setPresence(String accessToken, String status, boolean touchLastSeen)
            throws IOException, CreangerApiException {
        if (status == null || status.isEmpty()) {
            throw new CreangerApiException(400, new ApiError(ApiError.INTERNAL_ERROR,
                    "presence status must not be empty", null, 0));
        }
        JSONObject args = new JSONObject();
        try {
            args.put("p_status", status);
            args.put("p_touch_last_seen", touchLastSeen);
        } catch (JSONException e) {
            throw new CreangerApiException(400, new ApiError(ApiError.INTERNAL_ERROR,
                    "presence args build failed", null, 0));
        }
        executeJson("/rest/v1/rpc/set_presence", args, accessToken);
    }

    /**
     * Reads current presence rows for the given user ids (RLS: SELECT allowed
     * for all authenticated). Returns raw JSON rows; empty list when none.
     */
    public JSONArray getPresence(String accessToken, List<String> userIds)
            throws IOException, CreangerApiException {
        if (userIds == null || userIds.isEmpty()) {
            return new JSONArray();
        }
        Map<String, String> query = query(
                "select", "user_id,status,last_seen_at",
                "user_id", "in.(" + joinIn(userIds) + ")");
        String body = executeList("/rest/v1/user_presence", query, accessToken);
        return parseArray(body);
    }

    // ---- migration 040: bulk delete / document search / preview / close friends ----

    /**
     * Bulk soft-deletes own messages via bulk_delete_messages (040).
     * Server-side author check: only own rows are deleted. Returns deleted ids.
     */
    public List<String> bulkDeleteMessages(String accessToken, List<String> messageIds)
            throws IOException, CreangerApiException {
        List<String> out = new ArrayList<>();
        if (messageIds == null || messageIds.isEmpty()) {
            return out;
        }
        JSONObject args = new JSONObject();
        try {
            JSONArray arr = new JSONArray();
            for (String id : messageIds) {
                if (id != null) {
                    arr.put(id);
                }
            }
            args.put("p_message_ids", arr);
        } catch (JSONException e) {
            throw new CreangerApiException(200, new ApiError(ApiError.INTERNAL_ERROR,
                    "malformed bulk delete args", null, 0));
        }
        String body = executeJson("/rest/v1/rpc/bulk_delete_messages", args, accessToken);
        try {
            Object value = new org.json.JSONTokener(body == null ? "" : body.trim()).nextValue();
            if (value instanceof JSONArray) {
                JSONArray rows = (JSONArray) value;
                for (int i = 0; i < rows.length(); i++) {
                    String id = rows.optString(i, null);
                    if (id != null && !"null".equals(id)) {
                        out.add(id);
                    }
                }
            } else if (value instanceof String) {
                out.add((String) value);
            }
        } catch (JSONException e) {
            throw new CreangerApiException(200, new ApiError(ApiError.INTERNAL_ERROR,
                    "malformed bulk delete response", null, 0));
        }
        return out;
    }

    /**
     * Document-scoped search via search_documents_in_chat (040).
     * Filters media messages by text; membership-gated server-side.
     */
    public List<CreangerMessage> searchDocumentsInChat(String accessToken, String chatId,
                                                      String query, int limit)
            throws IOException, CreangerApiException {
        JSONObject args = new JSONObject();
        try {
            args.put("p_chat_id", chatId);
            args.put("p_search_query", query != null ? query : "");
            args.put("p_limit", limit);
        } catch (JSONException e) {
            throw new CreangerApiException(200, new ApiError(ApiError.INTERNAL_ERROR,
                    "malformed rpc args", null, 0));
        }
        String body = executeJson("/rest/v1/rpc/search_documents_in_chat", args, accessToken);
        List<CreangerMessage> out = new ArrayList<>();
        JSONArray rows = parseArray(body);
        for (int i = 0; i < rows.length(); i++) {
            out.add(parseMessage(rows.optJSONObject(i)));
        }
        return out;
    }

    /**
     * Recent media for chat-list document preview via get_recent_media (040).
     */
    public List<CreangerDocumentPreview.RecentMedia> getRecentMedia(String accessToken,
                                                                   String chatId, int limit)
            throws IOException, CreangerApiException {
        JSONObject args = new JSONObject();
        try {
            args.put("p_chat_id", chatId);
            args.put("p_limit", limit <= 0 ? 4 : limit);
        } catch (JSONException e) {
            throw new CreangerApiException(200, new ApiError(ApiError.INTERNAL_ERROR,
                    "malformed rpc args", null, 0));
        }
        String body = executeJson("/rest/v1/rpc/get_recent_media", args, accessToken);
        List<CreangerDocumentPreview.RecentMedia> out = new ArrayList<>();
        JSONArray rows = parseArray(body);
        for (int i = 0; i < rows.length(); i++) {
            JSONObject o = rows.optJSONObject(i);
            if (o == null) {
                continue;
            }
            out.add(new CreangerDocumentPreview.RecentMedia(
                    nullIfEmpty(o.optString("message_id", null)),
                    nullIfEmpty(o.optString("public_url", null)),
                    nullIfEmpty(o.optString("thumbnail_url", null)),
                    nullIfEmpty(o.optString("mime_type", null)),
                    nullIfEmpty(o.optString("created_at", null))));
        }
        return out;
    }

    /** Adds a user to the caller's close-friends audience (040). */
    public void addCloseFriend(String accessToken, String friendId)
            throws IOException, CreangerApiException {
        JSONObject args = new JSONObject();
        try {
            args.put("p_friend_id", friendId);
        } catch (JSONException e) {
            throw new CreangerApiException(200, new ApiError(ApiError.INTERNAL_ERROR,
                    "malformed rpc args", null, 0));
        }
        executeJson("/rest/v1/rpc/add_close_friend", args, accessToken);
    }

    /** Removes a user from the caller's close-friends audience (040). */
    public void removeCloseFriend(String accessToken, String friendId)
            throws IOException, CreangerApiException {
        JSONObject args = new JSONObject();
        try {
            args.put("p_friend_id", friendId);
        } catch (JSONException e) {
            throw new CreangerApiException(200, new ApiError(ApiError.INTERNAL_ERROR,
                    "malformed rpc args", null, 0));
        }
        executeJson("/rest/v1/rpc/remove_close_friend", args, accessToken);
    }

    /**
     * Lists the caller's close-friends audience ids (040). Plain PostgREST
     * read on {@code story_close_friends}: the owner-scoped RLS policy
     * restricts rows to the caller's own list. Never null.
     */
    public List<String> listCloseFriendIds(String accessToken)
            throws IOException, CreangerApiException {
        String body = executeList("/rest/v1/story_close_friends",
                query("select", "friend_id", "order", "created_at.asc"), accessToken);
        List<String> out = new ArrayList<>();
        JSONArray rows = parseArray(body);
        for (int i = 0; i < rows.length(); i++) {
            JSONObject o = rows.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String id = nullIfEmpty(o.optString("friend_id", null));
            if (id != null) {
                out.add(id);
            }
        }
        return out;
    }

    // ---- execution ----

    private String executeList(String path, Map<String, String> query, String accessToken)
            throws IOException, CreangerApiException {
        ApiRequest request = new ApiRequest("GET", path, query, null, accessToken, null);
        TransportResponse raw = transport.execute(request);
        ApiError error = PostgRestResponseParser.interpretError(raw.statusCode, raw.body);
        if (error != null) {
            throw new CreangerApiException(raw.statusCode, error);
        }
        return raw.body;
    }

    /** Executes a JSON-body request (RPC POST on the data plane). */
    private String executeJson(String path, JSONObject body, String accessToken)
            throws IOException, CreangerApiException {
        ApiRequest request = new ApiRequest("POST", path, null, body.toString(), accessToken, null);
        TransportResponse raw = transport.execute(request);
        ApiError error = PostgRestResponseParser.interpretError(raw.statusCode, raw.body);
        if (error != null) {
            throw new CreangerApiException(raw.statusCode, error);
        }
        return raw.body;
    }

    /** PATCH with a JSON body (PostgREST partial update). */
    private String executePatch(String path, Map<String, String> query, String jsonBody,
                                String accessToken)
            throws IOException, CreangerApiException {
        ApiRequest request = new ApiRequest("PATCH", path, query, jsonBody, accessToken, null);
        TransportResponse raw = transport.execute(request);
        ApiError error = PostgRestResponseParser.interpretError(raw.statusCode, raw.body);
        if (error != null) {
            throw new CreangerApiException(raw.statusCode, error);
        }
        return raw.body;
    }

    /** POST with a JSON array body (PostgREST bulk insert). */
    private String executeInsert(String path, String jsonBody, String accessToken)
            throws IOException, CreangerApiException {
        ApiRequest request = new ApiRequest("POST", path, null, jsonBody, accessToken, null);
        TransportResponse raw = transport.execute(request);
        ApiError error = PostgRestResponseParser.interpretError(raw.statusCode, raw.body);
        if (error != null) {
            throw new CreangerApiException(raw.statusCode, error);
        }
        return raw.body;
    }

    /** DELETE with query filters (PostgREST filtered delete). */
    private String executeDelete(String path, Map<String, String> query, String accessToken)
            throws IOException, CreangerApiException {
        ApiRequest request = new ApiRequest("DELETE", path, query, null, accessToken, null);
        TransportResponse raw = transport.execute(request);
        ApiError error = PostgRestResponseParser.interpretError(raw.statusCode, raw.body);
        if (error != null) {
            throw new CreangerApiException(raw.statusCode, error);
        }
        return raw.body;
    }

    private static JSONArray parseArray(String body) throws CreangerApiException {
        if (body == null || body.isEmpty()) {
            return new JSONArray();
        }
        try {
            return new JSONArray(body);
        } catch (JSONException e) {
            throw new CreangerApiException(200, new ApiError(ApiError.INTERNAL_ERROR,
                    "malformed chat data-plane response", null, 0));
        }
    }

    private static CreangerChat parseChat(JSONObject o) {
        if (o == null) {
            return null;
        }
        return new CreangerChat(
                o.optString("id", null),
                o.optString("type", null),
                nullIfEmpty(o.optString("title", null)),
                nullIfEmpty(o.optString("username", null)),
                nullIfEmpty(o.optString("description", null)),
                nullIfEmpty(o.optString("avatar_media_id", null)),
                nullIfEmpty(o.optString("owner_id", null)),
                o.optBoolean("is_verified", false),
                o.optBoolean("is_public", false),
                o.optBoolean("is_archived", false),
                o.optString("created_at", null),
                o.optString("updated_at", null),
                nullIfEmpty(o.optString("deleted_at", null)));
    }

    private static ChatMember parseMember(JSONObject o) {
        if (o == null) {
            return null;
        }
        Integer pinned = o.has("pinned_position") && !o.isNull("pinned_position")
                ? o.optInt("pinned_position", 0) : null;
        return new ChatMember(
                o.optString("id", null),
                o.optString("chat_id", null),
                o.optString("user_id", null),
                o.optString("role", null),
                o.optString("joined_at", null),
                nullIfEmpty(o.optString("left_at", null)),
                nullIfEmpty(o.optString("muted_until", null)),
                pinned,
                nullIfEmpty(o.optString("last_read_at", null)));
    }

    @androidx.annotation.Nullable
    private static String nullIfEmpty(@androidx.annotation.Nullable String value) {
        // Android's org.json returns the literal string "null" for JSON null
        // (unlike the reference impl used in JVM tests) — treat it as missing.
        return value == null || value.isEmpty() || "null".equals(value) ? null : value;
    }

    private static Map<String, String> query(String... kvs) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < kvs.length; i += 2) {
            m.put(kvs[i], kvs[i + 1]);
        }
        return m;
    }

    /** PostgREST {@code in.()} uses a comma-separated parenthesized list. */
    private static String joinIn(List<String> messageIds) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < messageIds.size(); i++) {
            String id = messageIds.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append(id);
        }
        return sb.toString();
    }
}