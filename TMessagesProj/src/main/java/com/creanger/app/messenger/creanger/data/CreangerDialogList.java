package com.creanger.app.messenger.creanger.data;

import com.creanger.app.messenger.creanger.api.SupabaseAuthClient.ProfileRow;
import com.creanger.app.messenger.creanger.model.ChatModels.ChatMember;
import com.creanger.app.messenger.creanger.model.ChatModels.CreangerChat;
import com.creanger.app.messenger.creanger.model.MessageModels.CreangerMessage;
import com.creanger.app.messenger.creanger.model.MessageModels.MessageStatus;

/**
 * Pure-JVM list rules for showing Creanger chats inside the Telegram-style
 * dialog list: gating (default tab only), stable ids and row text. The
 * Android binding renders rows through the stock DialogCell CustomDialog
 * path (ui.Adapters.DialogsAdapter.customDialogFor); this class holds
 * everything unit testable without Android types.
 */
public final class CreangerDialogList {

    private CreangerDialogList() {}

    /**
     * Creanger rows appear only on the default All tab ({@code dialogsType ==
     * 0}, mirroring {@code DialogsActivity.DIALOGS_TYPE_DEFAULT} without
     * touching Android classes), in the main folder, and never in selection /
     * forward / import flows.
     */
    public static boolean shouldShow(int dialogsType, int folderId, boolean onlySelect) {
        return dialogsType == 0 && folderId == 0 && !onlySelect;
    }

    /** Stable non-negative id derived from the chat UUID (diffing only). */
    public static long stableId(String chatUuid) {
        if (chatUuid == null) {
            return 0;
        }
        return ((long) chatUuid.hashCode()) & 0xffffffffL;
    }

    /** Row title: chat title, else @username, else a generic fallback. */
    public static String titleFor(CreangerChat chat) {
        if (chat == null) {
            return "Chat";
        }
        if (chat.title != null && !chat.title.trim().isEmpty()) {
            return chat.title.trim();
        }
        if (chat.username != null && !chat.username.trim().isEmpty()) {
            return "@" + chat.username.trim();
        }
        return "Chat";
    }

    /** Row subtitle: @username when titled, else the chat type label. */
    public static String subtitleFor(CreangerChat chat) {
        if (chat == null) {
            return "";
        }
        boolean hasTitle = chat.title != null && !chat.title.trim().isEmpty();
        if (hasTitle && chat.username != null && !chat.username.trim().isEmpty()) {
            return "@" + chat.username.trim();
        }
        if (hasTitle && chat.description != null && !chat.description.trim().isEmpty()) {
            return chat.description.trim();
        }
        if (chat.isDirect()) {
            return "Direct message";
        }
        if ("channel".equals(chat.type)) {
            return "Channel";
        }
        return "Group";
    }

    /**
     * Peer display name from a profiles row, priority: display name, then
     * full name (first + last), else null. Never blank, never literal "null".
     */
    public static String peerDisplayName(ProfileRow row) {
        if (row == null) {
            return null;
        }
        if (isPresentable(row.displayName)) {
            return row.displayName.trim();
        }
        String first = isPresentable(row.firstName) ? row.firstName.trim() : null;
        String last = isPresentable(row.lastName) ? row.lastName.trim() : null;
        if (first != null && last != null) {
            return first + " " + last;
        }
        if (first != null) {
            return first;
        }
        if (last != null) {
            return last;
        }
        return null;
    }

    /** Peer username from a profiles row, or null when unclaimed/missing. */
    public static String peerUsername(ProfileRow row) {
        if (row == null || !isPresentable(row.username)) {
            return null;
        }
        return row.username.trim();
    }

    private static boolean isPresentable(String v) {
        return v != null && !v.trim().isEmpty() && !"null".equals(v.trim());
    }

    /**
     * Row title with peer identity: for direct chats the peer's display name
     * wins, then @peer-username; groups/channels keep their own title.
     * Falls back to {@link #titleFor(CreangerChat)} (never blank).
     */
    public static String titleFor(CreangerChat chat, String peerDisplayName, String peerUsername) {
        if (chat != null && chat.isDirect()) {
            if (isPresentable(peerDisplayName)) {
                return peerDisplayName.trim();
            }
            if (isPresentable(peerUsername)) {
                return "@" + peerUsername.trim();
            }
        }
        return titleFor(chat);
    }

    /**
     * Row subtitle with peer identity: @peer-username when the title came
     * from the peer display name, else {@link #subtitleFor(CreangerChat)}.
     */
    public static String subtitleFor(CreangerChat chat, String peerDisplayName, String peerUsername) {
        if (chat != null && chat.isDirect() && isPresentable(peerDisplayName)
                && isPresentable(peerUsername)) {
            return "@" + peerUsername.trim();
        }
        return subtitleFor(chat);
    }

    /** Initials for the avatar circle (up to 2 letters). */
    public static String initialsFor(CreangerChat chat) {
        return initialsFor(chat, null, null);
    }

    /** Initials for the peer-aware title (up to 2 letters). */
    public static String initialsFor(CreangerChat chat, String peerDisplayName, String peerUsername) {
        String source = titleFor(chat, peerDisplayName, peerUsername);
        if (source.startsWith("@")) {
            source = source.substring(1);
        }
        String[] parts = source.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(2, parts.length); i++) {
            if (!parts[i].isEmpty()) {
                sb.append(Character.toUpperCase(parts[i].charAt(0)));
            }
        }
        if (sb.length() == 0) {
            return "?";
        }
        return sb.toString();
    }

    // ---- Fully Telegram-style dialog row (time / preview / unread / sort) ----
    // Pure-JVM (no Android types): the Android row renders from these through
    // the stock DialogCell.CustomDialog path, mirroring native rows exactly.

    /** Telegram-style date label (UTC, English): today HH:MM, yesterday, weekday, else dd.MM.yy. */
    public static String timeLabelFor(int lastMessageDateSec, long nowSec) {
        if (lastMessageDateSec <= 0) {
            return "";
        }
        long daySecs = 86400L;
        long nowDay = (nowSec >= 0 ? nowSec : System.currentTimeMillis() / 1000) / daySecs;
        long msgDay = ((long) lastMessageDateSec) / daySecs;
        long diff = nowDay - msgDay;
        if (diff <= 0) {
            int secOfDay = (int) (lastMessageDateSec % daySecs);
            if (secOfDay < 0) {
                secOfDay += (int) daySecs;
            }
            return String.format(java.util.Locale.US, "%02d:%02d", secOfDay / 3600, (secOfDay % 3600) / 60);
        }
        if (diff == 1) {
            return "Yesterday";
        }
        if (diff < 7) {
            // 1970-01-01 was a Thursday (index 4 with Monday=0).
            String[] weekdays = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
            int dow = (int) ((msgDay + 3) % 7);
            if (dow < 0) {
                dow += 7;
            }
            return weekdays[dow];
        }
        java.util.Calendar cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        cal.setTimeInMillis(((long) lastMessageDateSec) * 1000L);
        int day = cal.get(java.util.Calendar.DAY_OF_MONTH);
        int month = cal.get(java.util.Calendar.MONTH) + 1;
        int year = cal.get(java.util.Calendar.YEAR) % 100;
        return String.format(java.util.Locale.US, "%02d.%02d.%02d", day, month, year);
    }

    /**
     * Last-message preview for the dialog row: trimmed content (max 150 chars),
     * prefixed with "You: " for outbound rows, or "Sender: " for inbound group
     * rows with a known sender name. Never null, never literal "null".
     */
    public static String lastMessagePreview(String content, boolean out,
                                            String senderName, boolean isDirect) {
        String body = isPresentable(content) ? content.trim().replaceAll("\\s+", " ") : "";
        if (body.length() > 150) {
            body = body.substring(0, 149).trim() + "…";
        }
        if (out) {
            return body.isEmpty() ? "You" : "You: " + body;
        }
        if (!isDirect && isPresentable(senderName)) {
            String sender = senderName.trim().replaceAll("\\s+", " ");
            return body.isEmpty() ? sender : sender + ": " + body;
        }
        return body;
    }

    /**
     * Raw row message text for the native dialog cell: trimmed content with
     * line breaks flattened to spaces (max 150 chars, mirroring the stock
     * cell's own truncation). No sender prefixes — the native cell draws its
     * own read-state ticks. Never null, never literal "null".
     */
    public static String rowMessageText(String content) {
        String body = isPresentable(content) ? content.trim().replaceAll("\\s+", " ") : "";
        if (body.length() > 150) {
            body = body.substring(0, 150);
        }
        return body;
    }

    /** True when the unread badge must draw. */
    public static boolean shouldShowUnread(int unreadCount) {
        return unreadCount > 0;
    }

    /** Badge text: exact count ("" when none). Never null. */
    public static String unreadText(int unreadCount) {
        if (unreadCount <= 0) {
            return "";
        }
        return String.valueOf(unreadCount);
    }

    /**
     * Dialog sort: pinned rows first, then newest message date first.
     * Returns negative when a sorts before b (usable as a Comparator).
     */
    public static int sortCompare(boolean pinnedA, int dateA, boolean pinnedB, int dateB) {
        if (pinnedA != pinnedB) {
            return pinnedA ? -1 : 1;
        }
        return Integer.compare(dateB, dateA);
    }

    // ---- Row state for the native dialog bind (DialogCell.CustomDialog) ----
    // Pure-JVM assembly of everything the native bind needs beyond title/peer identity:
    // last-message preview + date, unread count, pinned/muted flags. The Android
    // layer (DialogsActivity/DialogsAdapter) only fetches data and posts the map.

    /** How many newest messages bound a dialog-row refresh reads per chat. */
    public static final int ROW_MESSAGE_WINDOW = 50;

    /**
     * Render state of one dialog row. {@code lastMessage} is the raw newest
     * non-deleted content (possibly empty — the cell falls back to the
     * subtitle); {@code lastMessageDateSec} is 0 when unknown (hides the date
     * label). {@code latestStatus} is the newest visible message's
     * {@link MessageStatus} (null when none) driving the read-state ticks.
     * Never null fields except {@code senderName}/{@code latestStatus}.
     */
    public static final class RowState {
        public final String lastMessage;
        public final int lastMessageDateSec;
        public final int unreadCount;
        public final boolean pinned;
        public final boolean muted;
        public final boolean out;
        public final String senderName;
        public final String latestStatus;

        public RowState(String lastMessage, int lastMessageDateSec, int unreadCount,
                        boolean pinned, boolean muted, boolean out, String senderName,
                        String latestStatus) {
            this.lastMessage = lastMessage != null ? lastMessage : "";
            this.lastMessageDateSec = lastMessageDateSec;
            this.unreadCount = Math.max(0, unreadCount);
            this.pinned = pinned;
            this.muted = muted;
            this.out = out;
            this.senderName = senderName;
            this.latestStatus = latestStatus;
        }
    }

    /**
     * Parses an ISO-8601 timestamp to unix seconds (UTC). Accepts
     * {@code 2026-09-15T10:30:00Z}, with millis, or with a {@code +HH:MM} /
     * {@code -HH:MM} offset. Returns 0 when null/empty/unparseable (the row
     * then hides its date label instead of showing a wrong date). No
     * java.time (min SDK 21).
     */
    public static int parseIso8601ToUnix(String iso) {
        if (iso == null) {
            return 0;
        }
        String s = iso.trim();
        if (s.isEmpty()) {
            return 0;
        }
        try {
            int tzSign = 0;
            int tzHours = 0;
            int tzMinutes = 0;
            int plusIdx = s.lastIndexOf('+');
            int minusIdx = s.lastIndexOf('-');
            int tzIdx = plusIdx > 10 ? plusIdx : (minusIdx > 10 ? minusIdx : -1);
            if (tzIdx > 0) {
                String tzPart = s.substring(tzIdx);
                s = s.substring(0, tzIdx);
                String tzNum = tzPart.substring(1);
                String[] tzParts = tzNum.split(":");
                tzHours = Integer.parseInt(tzParts[0]);
                tzMinutes = tzParts.length > 1 ? Integer.parseInt(tzParts[1]) : 0;
                tzSign = tzPart.charAt(0) == '+' ? 1 : -1;
            } else if (s.endsWith("Z") || s.endsWith("z")) {
                s = s.substring(0, s.length() - 1);
            }
            int dotIdx = s.indexOf('.');
            if (dotIdx > 0) {
                s = s.substring(0, dotIdx);
            }
            int tIdx = s.indexOf('T');
            if (tIdx < 0) {
                tIdx = s.indexOf(' ');
            }
            if (tIdx < 0) {
                return 0;
            }
            String datePart = s.substring(0, tIdx);
            String timePart = s.substring(tIdx + 1);
            String[] dateParts = datePart.split("-");
            String[] timeParts = timePart.split(":");
            if (dateParts.length != 3 || timeParts.length != 3) {
                return 0;
            }
            int year = Integer.parseInt(dateParts[0]);
            int month = Integer.parseInt(dateParts[1]);
            int day = Integer.parseInt(dateParts[2]);
            int hour = Integer.parseInt(timeParts[0]);
            int minute = Integer.parseInt(timeParts[1]);
            int second = Integer.parseInt(timeParts[2]);
            java.util.Calendar cal = java.util.Calendar.getInstance(
                    java.util.TimeZone.getTimeZone("UTC"));
            cal.clear();
            cal.setLenient(false);
            cal.set(year, month - 1, day, hour, minute, second);
            long millis = cal.getTimeInMillis() - tzSign * (tzHours * 3600L + tzMinutes * 60L) * 1000L;
            long secs = millis / 1000L;
            return secs > Integer.MAX_VALUE || secs < 0 ? 0 : (int) secs;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * True when {@code mutedUntilIso} parses to a moment after {@code nowMs}.
     * Null/empty/unparseable/past values mean not muted (fail open: the row
     * still renders, just without the muted badge color).
     */
    public static boolean isMuted(String mutedUntilIso, long nowMs) {
        int mutedSec = parseIso8601ToUnix(mutedUntilIso);
        return mutedSec > 0 && ((long) mutedSec) * 1000L > nowMs;
    }

    /**
     * Counts known-unread messages: inbound (sender is not me), not
     * soft-deleted, and not marked READ. The per-message {@code status}
     * (migration 026) is the authority — opening the chat advances inbound
     * rows to {@code read} via {@code mark_message_status}, which clears the
     * badge. A null status (unknown state) falls back to the legacy
     * {@code lastReadAtIso} timestamp comparison so old cached rows never
     * inflate the count.
     *
     * <p>Note: {@code chat_members.last_read_at} is never written by any
     * client or RPC, so timestamp-only counting kept the badge forever; the
     * status check above is what lets it clear.
     */
    public static int unreadCount(java.util.List<CreangerMessage> messages, String myId,
                                  String lastReadAtIso) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int readSec = parseIso8601ToUnix(lastReadAtIso);
        int count = 0;
        for (CreangerMessage m : messages) {
            if (m == null || m.deletedAt != null) {
                continue;
            }
            if (myId != null && myId.equals(m.senderId)) {
                continue;
            }
            if (m.senderId == null) {
                continue;
            }
            if (MessageStatus.READ.equals(m.status)) {
                continue;
            }
            if (m.status != null) {
                count++;
                continue;
            }
            if (parseIso8601ToUnix(m.createdAt) > readSec) {
                count++;
            }
        }
        return count;
    }

    /**
     * Newest non-deleted message of a newest-first list, or null when none is
     * visible. Shared by {@link #rowStateFor} and the Android layer (which
     * additionally resolves the sender profile for group previews).
     */
    public static CreangerMessage latestVisible(java.util.List<CreangerMessage> newestFirst) {
        if (newestFirst == null) {
            return null;
        }
        for (CreangerMessage m : newestFirst) {
            if (m != null && m.deletedAt == null) {
                return m;
            }
        }
        return null;
    }

    /**
     * Assembles the {@link RowState} for one chat from its newest-first
     * messages, my user id, my membership row (may be null) and the resolved
     * sender display name for group previews (may be null). The newest
     * non-deleted message supplies preview content/date/out; when none exists
     * the row falls back to subtitle rendering with zero date/unread.
     */
    public static RowState rowStateFor(CreangerChat chat,
                                       java.util.List<CreangerMessage> newestFirst,
                                       String myId, ChatMember me, String senderName,
                                       long nowMs) {
        CreangerMessage latest = latestVisible(newestFirst);
        String lastReadAt = me != null ? me.lastReadAt : null;
        int unread = unreadCount(newestFirst, myId, lastReadAt);
        boolean pinned = me != null && me.pinnedPosition != null;
        boolean muted = me != null && isMuted(me.mutedUntil, nowMs);
        if (latest == null) {
            return new RowState("", 0, 0, pinned, muted, false, senderName, null);
        }
        boolean out = myId != null && myId.equals(latest.senderId);
        int dateSec = parseIso8601ToUnix(latest.createdAt);
        String content = latest.content != null ? latest.content : "";
        return new RowState(content, dateSec, unread, pinned, muted, out, senderName, latest.status);
    }
}
