package com.creanger.app.messenger.creanger.realtime;

import org.json.JSONException;
import org.json.JSONObject;
import com.creanger.app.messenger.creanger.model.MessageModels.CreangerMessage;
import com.creanger.app.messenger.creanger.model.MessageModels.MessageReaction;
import com.creanger.app.messenger.creanger.model.MessageModels.MessageStatus;
import com.creanger.app.messenger.creanger.model.MessageModels.MessageType;

import androidx.annotation.Nullable;

/**
 * Parses Supabase Realtime (Phoenix Channels) frames into Creanger message
 * events. Deliberately pure-JVM and {@code org.json}-only, mirroring the rest
 * of the data plane, so the whole realtime intake is unit-testable without
 * Android.
 *
 * Wire shapes supported (both the current and the legacy client protocols):
 * <pre>
 *   // modern: payload.data = { schema, table, eventType, new, old }
 *   {"topic":"realtime:messages","event":"postgres_changes",
 *    "payload":{"type":"postgres_changes",
 *               "data":{"schema":"public","table":"messages","eventType":"INSERT",
 *                       "new":{&lt;messages row&gt;}}}}
 *
 *   // legacy: payload.record = &lt;messages row&gt;
 *   {"topic":"realtime:messages","event":"postgres_changes",
 *    "payload":{"record":{&lt;messages row&gt;}}}
 * </pre>
 *
 * Event type mapping on {@code public.messages}:
 * <ul>
 *   <li>{@code INSERT} &rarr; {@link Kind#MESSAGE_INSERT} — a new row.</li>
 *   <li>{@code UPDATE} with {@code new.deleted_at} set &rarr;
 *       {@link Kind#MESSAGE_DELETE} — a soft-delete tombstone (the schema's
 *       deletion model is {@code messages.deleted_at}, see migration 025).</li>
 *   <li>{@code UPDATE} with {@code content} unchanged but {@code status}
 *       changed &rarr; {@link Kind#MESSAGE_STATUS} — a delivered/read advance
 *       from the {@code mark_message_status} RPC (migration 026).</li>
 *   <li>{@code UPDATE} otherwise &rarr; {@link Kind#MESSAGE_EDIT} — content /
 *       {@code edited_at} changed by the {@code edit_message} RPC.</li>
 *   <li>{@code DELETE} &rarr; {@link Kind#MESSAGE_DELETE} from {@code old} —
 *       a hard-deleted row (author-only {@code messages_delete_sender}).</li>
 * </ul>
 *
 * Everything else (other tables, other channels, malformed JSON) is reported
 * as {@link Kind#IGNORED} or {@link Kind#MALFORMED}. A row whose {@code
 * chat_id} differs from the subscribed chat is reported as
 * {@link Kind#IGNORED} (wrong-chat events are dropped before they ever reach
 * the UI).
 */
public final class RealtimeMessageParser {

    /** Topic used by the Supabase Realtime {@code messages} table channel. */
    public static final String CHANNEL_TOPIC = "realtime:messages";
    public static final String TABLE_MESSAGES = "messages";
    public static final String SCHEMA_PUBLIC = "public";

    /**
     * Table of {@code postgres_changes} subscriptions on the same channel as
     * {@code messages} (reaction rows carry {@code message_id}+{@code user_id}
     * but no {@code chat_id}).
     */
    public static final String TABLE_MESSAGE_REACTIONS = "message_reactions";

    public enum Kind {
        /** A brand-new message row for the subscribed chat. */
        MESSAGE_INSERT,
        /** A row was edited (content/edited_at changed) via the edit RPC. */
        MESSAGE_EDIT,
        /** A row was deleted (soft tombstone via UPDATE or hard DELETE event). */
        MESSAGE_DELETE,
        /**
         * A row's delivery/read status advanced via the {@code mark_message_status}
         * RPC (migration 026): an UPDATE that changed {@code status} but not
         * {@code content}. Status-only changes never bump {@code chat_seq}, so
         * these are recovered separately via {@code get_message_statuses_since}.
         */
MESSAGE_STATUS,
        /**
         * A {@code message_reactions} row was INSERTed/UPDATEed or DELETEd via
         * the {@code add_reaction}/{@code remove_reaction} RPCs (migration 027).
         * The row carries {@code message_id} + {@code user_id} but NO
         * {@code chat_id} — {@link #reactionAdded} tells the repository whether
         * to add or remove the user, and membership is verified against the
         * loaded message window.
         */
        REACTION_CHANGE,
        /**
         * An ephemeral broadcast typing event (not persisted to DB). Another
         * user started or stopped typing in the subscribed chat.
         */
        TYPING,
        /** The channel join was acknowledged (phx_reply ok). */
        JOINED,
        /** Frame for another chat/table/event type — safely ignored. */
        IGNORED,
        /** Publically-not-subscribable / join rejected (authz). */
        UNAUTHORIZED,
        /** Not a frame we can understand. */
        MALFORMED
    }

    public static final class Result {
        public final Kind kind;
        @Nullable
        public final String chatId;
        @Nullable
        public final CreangerMessage message;
        /** For {@link Kind#MESSAGE_DELETE}: the id of the deleted message. */
        @Nullable
        public final String deletedMessageId;
        /** For {@link Kind#REACTION_CHANGE}: the reaction row (a live object). */
        @Nullable
        public final MessageReaction reaction;
        /** For {@link Kind#REACTION_CHANGE}: true for INSERT/UPDATE, false for DELETE. */
        public final boolean reactionAdded;
        /** For {@link Kind#TYPING}: the user who is typing (Creanger UUID). */
        @Nullable
        public final String typingUserId;
        /** For {@link Kind#TYPING}: true if typing started, false if typing stopped. */
        public final boolean isTyping;

        private Result(Kind kind, @Nullable String chatId, @Nullable CreangerMessage message,
                       @Nullable String deletedMessageId, @Nullable MessageReaction reaction,
                       boolean reactionAdded) {
            this(kind, chatId, message, deletedMessageId, reaction, reactionAdded, null, false);
        }

        private Result(Kind kind, @Nullable String chatId, @Nullable CreangerMessage message,
                       @Nullable String deletedMessageId, @Nullable MessageReaction reaction,
                       boolean reactionAdded, @Nullable String typingUserId, boolean isTyping) {
            this.kind = kind;
            this.chatId = chatId;
            this.message = message;
            this.deletedMessageId = deletedMessageId;
            this.reaction = reaction;
            this.reactionAdded = reactionAdded;
            this.typingUserId = typingUserId;
            this.isTyping = isTyping;
        }

        static Result insert(String chatId, CreangerMessage message) {
            return new Result(Kind.MESSAGE_INSERT, chatId, message, null, null, true);
        }

        static Result edit(String chatId, CreangerMessage message) {
            return new Result(Kind.MESSAGE_EDIT, chatId, message, null, null, true);
        }

        static Result status(String chatId, CreangerMessage message) {
            return new Result(Kind.MESSAGE_STATUS, chatId, message, null, null, true);
        }

        static Result delete(String chatId, String deletedMessageId) {
            return new Result(Kind.MESSAGE_DELETE, chatId, null, deletedMessageId, null, true);
        }

        static Result reaction(MessageReaction reaction, boolean added) {
            return new Result(Kind.REACTION_CHANGE, null, null, null, reaction, added);
        }

        static Result simple(Kind kind) {
            return new Result(kind, null, null, null, null, true);
        }

        static Result typing(String chatId, String userId, boolean isTyping) {
            return new Result(Kind.TYPING, chatId, null, null, null, true, userId, isTyping);
        }
    }

    private static final String EVENT_POSTGRES_CHANGES = "postgres_changes";
    private static final String EVENT_BROADCAST = "broadcast";
    private static final String EVENT_PHX_REPLY = "phx_reply";
    static final String BROADCAST_EVENT_TYPING = "typing";
    private static final String EVENT_TYPE_INSERT = "INSERT";
    private static final String EVENT_TYPE_UPDATE = "UPDATE";
    private static final String EVENT_TYPE_DELETE = "DELETE";

    /** postgres_changes subscription events used in the channel join. */
    public static final String EVENT_JOIN_INSERT = "INSERT";
    public static final String EVENT_JOIN_UPDATE = "UPDATE";
    public static final String EVENT_JOIN_DELETE = "DELETE";

    private RealtimeMessageParser() {
    }

    /**
     * Parses one raw Realtime frame for the currently subscribed chat.
     *
     * @param subscribedChatId the Creanger chat UUID this client is open on
     * @param frameJson        the raw frame text received from the Realtime server
     */
    public static Result parse(String subscribedChatId, String frameJson) {
        if (frameJson == null || frameJson.trim().isEmpty()) {
            return Result.simple(Kind.IGNORED);
        }
        final JSONObject frame;
        try {
            frame = new JSONObject(frameJson);
        } catch (JSONException e) {
            return Result.simple(Kind.MALFORMED);
        }

        String topic = frame.optString("topic", null);
        String event = frame.optString("event", null);
        if (!CHANNEL_TOPIC.equals(topic)) {
            return Result.simple(Kind.IGNORED);
        }

        if (EVENT_PHX_REPLY.equals(event)) {
            JSONObject payload = frame.optJSONObject("payload");
            String status = payload != null ? payload.optString("status", null) : null;
            return "ok".equalsIgnoreCase(status)
                    ? Result.simple(Kind.JOINED)
                    : Result.simple(Kind.UNAUTHORIZED);
        }
        if (EVENT_BROADCAST.equals(event)) {
            return parseBroadcast(subscribedChatId, frame);
        }
        if (!EVENT_POSTGRES_CHANGES.equals(event)) {
            return Result.simple(Kind.IGNORED);
        }

        JSONObject payload = frame.optJSONObject("payload");
        if (payload == null) {
            return Result.simple(Kind.MALFORMED);
        }
        JSONObject data = payload.optJSONObject("data");

        // Modern protocol: { data: { schema, table, eventType, new, old } }
        if (data != null) {
            String schema = data.optString("schema", null);
            String table = data.optString("table", null);
            if (!SCHEMA_PUBLIC.equals(schema)) {
                return Result.simple(Kind.IGNORED);
            }
            if (TABLE_MESSAGE_REACTIONS.equals(table)) {
                return modernReaction(data);
            }
            if (!TABLE_MESSAGES.equals(table)) {
                return Result.simple(Kind.IGNORED);
            }
            return modern(subscribedChatId, data);
        }

        // Legacy protocol: { record: { ... } } implies an INSERT on the channel.
        JSONObject legacyRecord = payload.optJSONObject("record");
        if (legacyRecord != null) {
            return messageFromRecord(subscribedChatId, legacyRecord, Kind.MESSAGE_INSERT);
        }
        return Result.simple(Kind.IGNORED);
    }

    private static Result modern(String subscribedChatId, JSONObject data) {
        String eventType = data.optString("eventType", data.optString("type", null));
        JSONObject newRecord = data.optJSONObject("new");
        JSONObject oldRecord = data.optJSONObject("old");

        if (EVENT_TYPE_INSERT.equalsIgnoreCase(eventType)) {
            if (newRecord == null) {
                return Result.simple(Kind.IGNORED);
            }
            return messageFromRecord(subscribedChatId, newRecord, Kind.MESSAGE_INSERT);
        }
        if (EVENT_TYPE_UPDATE.equalsIgnoreCase(eventType)) {
            if (newRecord == null) {
                return Result.simple(Kind.IGNORED);
            }
            // Soft-delete tombstone: new.deleted_at set → a deletion event.
            if (!newRecord.isNull("deleted_at") && newRecord.optString("deleted_at", null) != null) {
                return deletionFromRecord(subscribedChatId, newRecord);
            }
            // Status-only change (content untouched): a delivered/read advance
            // from the mark_message_status RPC (migration 026). A content edit
            // that also carries a status field still maps to MESSAGE_EDIT.
            if (oldRecord != null
                    && jsonEquals(oldRecord, newRecord, "content")
                    && !jsonEquals(oldRecord, newRecord, "status")) {
                return messageFromRecord(subscribedChatId, newRecord, Kind.MESSAGE_STATUS);
            }
            return messageFromRecord(subscribedChatId, newRecord, Kind.MESSAGE_EDIT);
        }
        if (EVENT_TYPE_DELETE.equalsIgnoreCase(eventType)) {
            if (oldRecord == null) {
                return Result.simple(Kind.IGNORED);
            }
            return deletionFromRecord(subscribedChatId, oldRecord);
        }
        return Result.simple(Kind.IGNORED);
    }

    // ---- message_reactions events (no chat_id anywhere in the row) ----

    /**
     * Parses a {@code message_reactions} postgres_change into a
     * {@link Kind#REACTION_CHANGE}. INSERT/UPDATE carry the row in {@code new}
     * and mean {@code added == true} (an add_reaction, or an edit of an
     * existing reaction); DELETE carries it in {@code old} and means
     * {@code added == false} (a remove_reaction). The row has no
     * {@code chat_id}, so routing to the right chat is left to the repository
     * (which verifies {@code message_id} against the loaded window).
     */
    private static Result modernReaction(JSONObject data) {
        String eventType = data.optString("eventType", data.optString("type", null));
        JSONObject record;
        boolean added;
        if (EVENT_TYPE_DELETE.equalsIgnoreCase(eventType)) {
            record = data.optJSONObject("old");
            added = false;
        } else {
            record = data.optJSONObject("new");
            added = true;
        }
        if (record == null) {
            return Result.simple(Kind.IGNORED);
        }
        MessageReaction reaction = new MessageReaction(
                pickId(record),
                nullIfEmpty(record.optString("user_id", null)),
                nullIfEmpty(record.optString("reaction", null)),
                record.optBoolean("is_custom_emoji", false),
                record.isNull("custom_emoji_id") ? null : nullIfEmpty(record.optString("custom_emoji_id", null)),
                record.isNull("created_at") ? null : nullIfEmpty(record.optString("created_at", null)),
                record.isNull("updated_at") ? null : nullIfEmpty(record.optString("updated_at", null)));
        if (reaction.messageId == null || reaction.userId == null || reaction.reaction == null) {
            return Result.simple(Kind.IGNORED);
        }
        return Result.reaction(reaction, added);
    }

    // ---- record → CreangerMessage ----

    private static Result messageFromRecord(String subscribedChatId, JSONObject record, Kind kind) {
        if (kind != Kind.MESSAGE_INSERT && kind != Kind.MESSAGE_EDIT && kind != Kind.MESSAGE_STATUS) {
            return Result.simple(Kind.IGNORED);
        }
        String chatId = nullIfEmpty(record.optString("chat_id", null));
        if (chatId == null || !chatId.equals(subscribedChatId)) {
            // Never surface another chat's message into the open chat.
            return Result.simple(Kind.IGNORED);
        }
        // On Android, optString never yields its default for JSON null (it
        // yields the literal "null"), so normalize first, then fall back.
        String messageType = nullIfEmpty(record.optString("message_type", null));
        if (messageType == null) {
            messageType = MessageType.TEXT;
        }
        String status = nullIfEmpty(record.optString("status", null));
        if (status == null) {
            status = MessageStatus.SENT;
        }
        CreangerMessage message = new CreangerMessage(
                pickId(record),
                chatId,
                nullIfEmpty(record.optString("sender_id", null)),
                messageType,
                nullIfEmpty(record.optString("content", null)),
                status,
                nullIfEmpty(record.optString("client_message_id", null)),
                nullableLong(record, "chat_seq"),
                nullIfEmpty(record.optString("created_at", null)),
                nullIfEmpty(record.optString("edited_at", null)),
                nullIfEmpty(record.optString("deleted_at", null)),
                nullIfEmpty(record.optString("updated_at", null)),
                nullIfEmpty(record.optString("reply_to_message_id", null)),
                false);
        switch (kind) {
            case MESSAGE_EDIT:
                return Result.edit(chatId, message);
            case MESSAGE_STATUS:
                return Result.status(chatId, message);
            default:
                return Result.insert(chatId, message);
        }
    }

    /**
     * True when both records carry the same value for {@code key}, treating
     * JSON null as absent (so a "cleared to null" is a real change).
     */
    private static boolean jsonEquals(JSONObject a, JSONObject b, String key) {
        Object va = a.isNull(key) ? null : a.opt(key);
        Object vb = b.isNull(key) ? null : b.opt(key);
        if (va == null && vb == null) {
            return true;
        }
        if (va == null || vb == null) {
            return false;
        }
        return va.toString().equals(vb.toString());
    }

    private static Result deletionFromRecord(String subscribedChatId, JSONObject record) {
        String chatId = record.optString("chat_id", null);
        if (chatId == null || !chatId.equals(subscribedChatId)) {
            return Result.simple(Kind.IGNORED);
        }
        return Result.delete(chatId, pickId(record));
    }

    /** Realtime rows key the message id as {@code id}; RPC rows use {@code message_id}. */
    private static String pickId(JSONObject row) {
        if (!row.isNull("id")) {
            return row.optString("id", null);
        }
        if (!row.isNull("message_id")) {
            return row.optString("message_id", null);
        }
        return null;
    }

    private static Long nullableLong(JSONObject row, String key) {
        if (row.isNull(key)) {
            return null;
        }
        Object value = row.opt(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(row.optString(key, "0"));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Nullable
    private static String nullIfEmpty(@Nullable String value) {
        // Android's org.json returns the literal string "null" for JSON null
        // (unlike the reference impl used in JVM tests) — treat it as missing.
        return value == null || value.isEmpty() || "null".equals(value) ? null : value;
    }

    /**
     * Parses an ephemeral Phoenix broadcast frame. Currently only
     * {@code typing} events are supported — these are NOT persisted to the
     * database and are delivered in real-time to other subscribers.
     *
     * Broadcast frame shape:
     * <pre>
     *   {"topic":"realtime:messages","event":"broadcast",
     *    "payload":{"type":"broadcast","event":"typing",
     *               "payload":{"user_id":"...","chat_id":"...",
     *                           "is_typing":true}}}
     * </pre>
     */
    private static Result parseBroadcast(String subscribedChatId, JSONObject frame) {
        JSONObject payload = frame.optJSONObject("payload");
        if (payload == null) {
            return Result.simple(Kind.IGNORED);
        }
        String innerEvent = payload.optString("event", null);
        if (!BROADCAST_EVENT_TYPING.equals(innerEvent)) {
            return Result.simple(Kind.IGNORED);
        }
        JSONObject data = payload.optJSONObject("payload");
        if (data == null) {
            return Result.simple(Kind.MALFORMED);
        }
        String chatId = data.optString("chat_id", null);
        String userId = data.optString("user_id", null);
        boolean isTyping = data.optBoolean("is_typing", false);
        if (chatId == null || userId == null || !chatId.equals(subscribedChatId)) {
            return Result.simple(Kind.IGNORED);
        }
        return Result.typing(chatId, userId, isTyping);
    }
}