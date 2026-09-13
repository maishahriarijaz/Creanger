package com.creanger.app.messenger.creanger.data;

import com.creanger.app.messenger.creanger.model.ChatModels.ChatMember;
import com.creanger.app.messenger.creanger.model.ChatModels.CreangerChat;

import java.util.List;

/**
 * Pure-JVM title rules for the Creanger chat header: Display Name first,
 * username fallback, never Telegram objects, never null. The Android binding
 * ({@code ChatActivity.updateTitle()}) calls {@link #resolveTitle} and sets
 * the existing header mechanism with the result.
 */
public final class CreangerChatHeader {

    private CreangerChatHeader() {}

    /**
     * Resolves the header title.
     *
     * @param chat            the open chat, or null when not cached yet
     * @param peerDisplayName peer Display Name for direct chats, or null
     * @param peerUsername    peer username fallback, or null
     * @return title to render; empty string when nothing is known yet (never
     *         null, never email, never an internal id)
     */
    public static String resolveTitle(CreangerChat chat,
                                      String peerDisplayName,
                                      String peerUsername) {
        if (chat != null && chat.title != null && !chat.title.trim().isEmpty()) {
            return chat.title.trim();
        }
        if (peerDisplayName != null && !peerDisplayName.trim().isEmpty()) {
            return peerDisplayName.trim();
        }
        if (peerUsername != null && !peerUsername.trim().isEmpty()) {
            String normalized = peerUsername.trim();
            return normalized.startsWith("@") ? normalized : "@" + normalized;
        }
        return "";
    }

    /**
     * Finds the peer user id in a direct chat: the first member that is not
     * the current user. Returns null for groups/channels, empty membership,
     * or when only the current user is present.
     */
    public static String peerUserId(List<ChatMember> members, String myUserId) {
        if (members == null) {
            return null;
        }
        for (ChatMember m : members) {
            if (m == null || m.userId == null || m.userId.isEmpty()) {
                continue;
            }
            if (m.leftAt != null && !m.leftAt.isEmpty()) {
                continue;
            }
            if (myUserId != null && myUserId.equals(m.userId)) {
                continue;
            }
            return m.userId;
        }
        return null;
    }

    /** True when the resolved title carries visible text. */
    public static boolean hasTitle(String resolvedTitle) {
        return resolvedTitle != null && !resolvedTitle.isEmpty();
    }
}
