package com.creanger.app.messenger.creanger.realtime;

import com.creanger.app.messenger.creanger.model.MessageModels.CreangerMessage;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which realtime message events have already been delivered for each
 * subscribed chat, so a frame that arrives more than once (a known Supabase
 * Realtime behaviour — an event can be delivered twice) is never surfaced
 * twice. Deduplicates by message {@code id} AND by {@code client_message_id}
 * (the idempotency key), mirroring the data-plane merge.
 *
 * Dedup is event-aware so the different event kinds never shadow each other:
 * <ul>
 *   <li>{@link #isNew} — {@code INSERT} rows, by id + client_message_id.</li>
 *   <li>{@link #isNewEdit} — {@code UPDATE} edits. An edit is only "new" when
 *       its content actually differs from the last edit delivered for the same
 *       message; an exact echo (a duplicate frame, or the Realtime echo of the
 *       user's OWN optimistic edit) is reported as a duplicate so the local
 *       optimistic edit and its server echo never render twice.</li>
 *   <li>{@link #isNewDelete} — {@code DELETE} / soft-tombstone deletions, by
 *       id. A re-delivered deletion only fires once.</li>
 * </ul>
 *
 * Cross-path deduplication (realtime vs. REST poll / send-confirm,
 * optimistic-edit echo vs. realtime edit) is also handled by the
 * {@code MessageRepository} cache merge and its realtime apply methods; this
 * class guards the realtime event stream itself. Per-chat state is cleared on
 * {@link #clear(String)} / {@link #clearAll()} so logout / account-switch
 * never leaks into the next chat.
 */
public final class MessageRealtimeDeduplicator {

    private final Map<String, Set<String>> idsByChat = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> clientIdsByChat = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> lastEditContentByChat = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> deletedIdsByChat = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> lastStatusByChat = new ConcurrentHashMap<>();
    /**
     * Per-message reaction add/remove state: key {@code messageId|userId|reaction}
     * -> last applied boolean. A frame that repeats the last applied state (a
     * Supabase double-delivery, or the echo of the caller's own RPC) is a
     * duplicate; a genuine toggle (add then remove, or remove then add) is
     * surfaced so counts/UI follow every real change.
     */
    private final Map<String, Map<String, Boolean>> lastReactionStateByChat = new ConcurrentHashMap<>();

    /**
     * Returns {@code true} when this is the first time the INSERT has been
     * delivered for the chat (and remembers it). Returns {@code false} for a
     * duplicate and still remembers any new id.
     */
    public boolean isNew(String chatId, CreangerMessage message) {
        if (chatId == null || message == null) {
            return false;
        }
        Set<String> ids = idsByChat.computeIfAbsent(chatId, k -> new HashSet<>());
        Set<String> clientIds = clientIdsByChat.computeIfAbsent(chatId, k -> new HashSet<>());
        synchronized (this) {
            boolean duplicate = (message.id != null && ids.contains(message.id))
                    || (message.clientMessageId != null && clientIds.contains(message.clientMessageId));
            if (message.id != null) {
                ids.add(message.id);
            }
            if (message.clientMessageId != null) {
                clientIds.add(message.clientMessageId);
            }
            return !duplicate;
        }
    }

    /**
     * Returns {@code true} for an edit that should be surfaced: the first edit
     * for a message, or an edit whose content differs from the last edit
     * delivered for that message. An exact repeat (Realtime re-delivery, or the
     * echo of the caller's own optimistic edit) returns {@code false}.
     */
    public boolean isNewEdit(String chatId, CreangerMessage message) {
        if (chatId == null || message == null || message.id == null) {
            return false;
        }
        Map<String, String> edits = lastEditContentByChat.computeIfAbsent(chatId, k -> new ConcurrentHashMap<>());
        String content = message.content != null ? message.content : "";
        synchronized (this) {
            String previous = edits.get(message.id);
            if (previous != null && previous.equals(content)) {
                return false;
            }
            edits.put(message.id, content);
            return true;
        }
    }

    /**
     * Returns {@code true} the first time a deletion is reported for the chat
     * and remembers the id; a re-delivered deletion returns {@code false}.
     */
    public boolean isNewDelete(String chatId, String deletedMessageId) {
        if (chatId == null || deletedMessageId == null) {
            return false;
        }
        Set<String> deleted = deletedIdsByChat.computeIfAbsent(chatId, k -> new HashSet<>());
        synchronized (this) {
            return deleted.add(deletedMessageId);
        }
    }

    /**
     * Returns {@code true} for a delivery/read status change that should be
     * surfaced: the first status observed for a message, or one that differs
     * from the last status delivered for that message. An exact repeat (a
     * Realtime re-delivery, or the echo of this recipient's own
     * {@code mark_message_status} RPC) returns {@code false}. Out-of-order /
     * regressing statuses are surfaced — the repository applies them
     * monotonically and drops the no-op — so a delivered-after-read never
     * regresses the sender's check.
     */
    public boolean isNewStatus(String chatId, String messageId, String status) {
        if (chatId == null || messageId == null || status == null) {
            return false;
        }
        Map<String, String> statuses = lastStatusByChat.computeIfAbsent(chatId, k -> new ConcurrentHashMap<>());
        synchronized (this) {
            String previous = statuses.get(messageId);
            if (status.equals(previous)) {
                return false;
            }
            statuses.put(messageId, status);
            return true;
        }
    }

    /**
     * Returns {@code true} the first time a reaction add/remove is reported for
     * the chat and remembers it; a frame repeating the last applied state
     * returns {@code false}. A genuine add→remove or remove→add toggle is
     * always surfaced.
     */
    public boolean isNewReactionChange(String chatId, String messageId, String userId,
                                       String reaction, boolean added) {
        if (chatId == null || messageId == null || userId == null || reaction == null) {
            return false;
        }
        String key = messageId + "|" + userId + "|" + reaction;
        Map<String, Boolean> states = lastReactionStateByChat.computeIfAbsent(chatId, k -> new ConcurrentHashMap<>());
        synchronized (this) {
            Boolean previous = states.get(key);
            if (previous != null && previous.booleanValue() == added) {
                return false;
            }
            states.put(key, added);
            return true;
        }
    }

    /** Forgets everything about one chat (done on unsubscribe). */
    public void clear(String chatId) {
        idsByChat.remove(chatId);
        clientIdsByChat.remove(chatId);
        lastEditContentByChat.remove(chatId);
        deletedIdsByChat.remove(chatId);
        lastStatusByChat.remove(chatId);
        lastReactionStateByChat.remove(chatId);
    }

    /** Forgets everything (logout / account isolation / close). */
    public void clearAll() {
        idsByChat.clear();
        clientIdsByChat.clear();
        lastEditContentByChat.clear();
        deletedIdsByChat.clear();
        lastStatusByChat.clear();
        lastReactionStateByChat.clear();
    }
}