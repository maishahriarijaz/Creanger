package com.creanger.app.messenger.creanger;

import com.creanger.app.messenger.creanger.data.CreangerChatHeader;
import com.creanger.app.messenger.creanger.model.ChatModels.ChatMember;
import com.creanger.app.messenger.creanger.model.ChatModels.CreangerChat;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CreangerChatHeaderTest {

    private static CreangerChat chat(String id, String type, String title, String username) {
        return new CreangerChat(id, type, title, username, null, null, null,
                false, false, false, null, null, null);
    }

    private static ChatMember member(String userId) {
        return new ChatMember("m-" + userId, "chat-1", userId, "member",
                null, null, null, null, null);
    }

    @Test
    public void displayNameBeatsUsername() {
        // The spec example: Display Name "Ijaz Ahmed" + username "ijaz".
        assertEquals("Ijaz Ahmed", CreangerChatHeader.resolveTitle(
                chat("c1", "direct", null, "ijaz"), "Ijaz Ahmed", "ijaz"));
    }

    @Test
    public void groupTitleBeatsPeerName() {
        assertEquals("Family", CreangerChatHeader.resolveTitle(
                chat("c1", "group", "Family", null), "Ijaz Ahmed", "ijaz"));
    }

    @Test
    public void missingDisplayNameFallsBackToUsername() {
        assertEquals("@ijaz", CreangerChatHeader.resolveTitle(
                chat("c1", "direct", null, "ijaz"), null, "ijaz"));
        assertEquals("@ijaz", CreangerChatHeader.resolveTitle(
                chat("c1", "direct", null, "ijaz"), "   ", "ijaz"));
        assertEquals("@ijaz", CreangerChatHeader.resolveTitle(
                null, null, "ijaz"));
    }

    @Test
    public void unknownChatResolvesToEmptyNeverNull() {
        assertEquals("", CreangerChatHeader.resolveTitle(null, null, null));
        assertEquals("", CreangerChatHeader.resolveTitle(
                chat("c1", "direct", null, null), null, null));
        assertFalse(CreangerChatHeader.hasTitle(
                CreangerChatHeader.resolveTitle(null, null, null)));
        assertTrue(CreangerChatHeader.hasTitle("Ijaz Ahmed"));
    }

    @Test
    public void peerIsFirstMemberOtherThanMe() {
        List<ChatMember> members = new ArrayList<>();
        members.add(member("me-1"));
        members.add(member("peer-9"));
        assertEquals("peer-9", CreangerChatHeader.peerUserId(members, "me-1"));
    }

    @Test
    public void peerSkipsLeftMembersAndSelfOnly() {
        ChatMember left = new ChatMember("m-x", "chat-1", "gone-1", "member",
                null, "2026-01-01", null, null, null);
        List<ChatMember> members = new ArrayList<>();
        members.add(left);
        assertNull(CreangerChatHeader.peerUserId(members, "me-1"));

        List<ChatMember> onlyMe = new ArrayList<>();
        onlyMe.add(member("me-1"));
        assertNull(CreangerChatHeader.peerUserId(onlyMe, "me-1"));
        assertNull(CreangerChatHeader.peerUserId(null, "me-1"));
    }
}
