package com.creanger.app.messenger.creanger;

import com.creanger.app.messenger.creanger.api.SupabaseAuthClient.ProfileRow;
import com.creanger.app.messenger.creanger.data.CreangerDialogList;
import com.creanger.app.messenger.creanger.model.ChatModels.CreangerChat;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CreangerDialogListTest {

    private static CreangerChat chat(String id, String type, String title, String username,
                                     String description) {
        return new CreangerChat(id, type, title, username, description, null, null,
                false, false, false, null, null, null);
    }

    @Test
    public void showsOnlyOnDefaultTabMainFolderOutsideSelection() {
        assertTrue(CreangerDialogList.shouldShow(0, 0, false));
        assertFalse(CreangerDialogList.shouldShow(1, 0, false));
        assertFalse(CreangerDialogList.shouldShow(7, 0, false));
        assertFalse(CreangerDialogList.shouldShow(0, 1, false));
        assertFalse(CreangerDialogList.shouldShow(0, 0, true));
    }

    @Test
    public void stableIdsAreNonNegativeAndDeterministic() {
        long a = CreangerDialogList.stableId("chat-uuid-1");
        long b = CreangerDialogList.stableId("chat-uuid-1");
        long c = CreangerDialogList.stableId("chat-uuid-2");
        assertEquals(a, b);
        assertTrue(a >= 0);
        assertTrue(a != c);
        assertEquals(0, CreangerDialogList.stableId(null));
    }

    @Test
    public void titlePrefersTitleThenUsername() {
        assertEquals("Family", CreangerDialogList.titleFor(
                chat("1", "group", "Family", "fam", null)));
        assertEquals("@fam", CreangerDialogList.titleFor(
                chat("1", "group", null, "fam", null)));
        assertEquals("@fam", CreangerDialogList.titleFor(
                chat("1", "group", "   ", "fam", null)));
        assertEquals("Chat", CreangerDialogList.titleFor(
                chat("1", "group", null, null, null)));
        assertEquals("Chat", CreangerDialogList.titleFor(null));
    }

    @Test
    public void subtitleShowsUsernameTypeOrDescription() {
        assertEquals("@fam", CreangerDialogList.subtitleFor(
                chat("1", "group", "Family", "fam", null)));
        assertEquals("Hello group", CreangerDialogList.subtitleFor(
                chat("1", "group", "Family", null, "Hello group")));
        assertEquals("Direct message", CreangerDialogList.subtitleFor(
                chat("1", "direct", null, null, null)));
        assertEquals("Channel", CreangerDialogList.subtitleFor(
                chat("1", "channel", null, null, null)));
        assertEquals("Group", CreangerDialogList.subtitleFor(
                chat("1", "group", null, null, null)));
    }

    @Test
    public void peerDisplayPrefersDisplayThenFullName() {
        assertEquals("Ijaz", CreangerDialogList.peerDisplayName(
                new ProfileRow("u", "ijaz", "Test", "User", "Ijaz")));
        assertEquals("Test User", CreangerDialogList.peerDisplayName(
                new ProfileRow("u", "ijaz", "Test", "User", null)));
        assertEquals(null, CreangerDialogList.peerDisplayName(
                new ProfileRow("u", "ijaz", null, null, null)));
        assertEquals(null, CreangerDialogList.peerDisplayName(null));
        assertEquals("ijaz", CreangerDialogList.peerUsername(
                new ProfileRow("u", "ijaz", null, null, null)));
        assertEquals(null, CreangerDialogList.peerUsername(
                new ProfileRow("u", null, null, null, null)));
        // Android org.json yields literal "null" for JSON null — never display it.
        assertEquals("Test User", CreangerDialogList.peerDisplayName(
                new ProfileRow("u", "null", "Test", "User", "null")));
        assertEquals(null, CreangerDialogList.peerUsername(
                new ProfileRow("u", "null", null, null, null)));
        assertEquals("Chat", CreangerDialogList.titleFor(
                chat("1", "direct", null, null, null), "null", "null"));
    }

    @Test
    public void directTitlePrefersPeerIdentity() {
        assertEquals("Ijaz", CreangerDialogList.titleFor(
                chat("1", "direct", null, null, null), "Ijaz", "ijaz"));
        assertEquals("@ijaz", CreangerDialogList.titleFor(
                chat("1", "direct", null, null, null), null, "ijaz"));
        assertEquals("@ijaz", CreangerDialogList.subtitleFor(
                chat("1", "direct", null, null, null), "Ijaz", "ijaz"));
        assertEquals("I", CreangerDialogList.initialsFor(
                chat("1", "direct", null, null, null), "Ijaz", "ijaz"));
        assertEquals("Family", CreangerDialogList.titleFor(
                chat("1", "group", "Family", "fam", null), "Ijaz", "ijaz"));
        assertEquals("Chat", CreangerDialogList.titleFor(
                chat("1", "direct", null, null, null), null, null));
    }

    @Test
    public void initialsTakeFirstTwoWords() {
        assertEquals("F", CreangerDialogList.initialsFor(
                chat("1", "group", "Family", null, null)));
        assertEquals("FG", CreangerDialogList.initialsFor(
                chat("1", "group", "Family Group", null, null)));
        assertEquals("F", CreangerDialogList.initialsFor(
                chat("1", "group", null, "fam", null)));
    }
}
