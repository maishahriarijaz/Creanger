package com.creanger.app.messenger.creanger.data;

import com.creanger.app.messenger.creanger.api.SupabaseAuthClient.ProfileRow;
import com.creanger.app.messenger.creanger.model.ChatModels.CreangerChat;

/**
 * Pure-JVM list rules for showing Creanger chats inside the Telegram-style
 * dialog list: gating (default tab only), stable ids and row text. The
 * Android binding lives in {@code ui.Cells.CreangerDialogCell} and
 * {@code ui.Adapters.DialogsAdapter}; this class holds everything unit
 * testable without Android types.
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
}
