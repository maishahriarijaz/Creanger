package com.creanger.app.messenger.creanger;

import com.creanger.app.messenger.creanger.api.SupabaseAuthClient.ProfileRow;
import com.creanger.app.messenger.creanger.data.CreangerDialogList;
import com.creanger.app.messenger.creanger.model.ChatModels.ChatMember;
import com.creanger.app.messenger.creanger.model.ChatModels.CreangerChat;
import com.creanger.app.messenger.creanger.model.MessageModels.CreangerMessage;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
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
                new ProfileRow("u", "ijaz", "Test", "User", "Ijaz", null, null)));
        assertEquals("Test User", CreangerDialogList.peerDisplayName(
                new ProfileRow("u", "ijaz", "Test", "User", null, null, null)));
        assertEquals(null, CreangerDialogList.peerDisplayName(
                new ProfileRow("u", "ijaz", null, null, null, null, null)));
        assertEquals(null, CreangerDialogList.peerDisplayName(null));
        assertEquals("ijaz", CreangerDialogList.peerUsername(
                new ProfileRow("u", "ijaz", null, null, null, null, null)));
        assertEquals(null, CreangerDialogList.peerUsername(
                new ProfileRow("u", null, null, null, null, null, null)));
        // Android org.json yields literal "null" for JSON null — never display it.
        assertEquals("Test User", CreangerDialogList.peerDisplayName(
                new ProfileRow("u", "null", "Test", "User", "null", null, null)));
        assertEquals(null, CreangerDialogList.peerUsername(
                new ProfileRow("u", "null", null, null, null, null, null)));
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

    // ---- Fully Telegram-style row: date / preview / unread / sort ----

    @Test
    public void timeLabelSameDayIsHourMinute() {
        // 2026-09-15T10:30:00Z = epoch day boundary + 10:30.
        int date = (int) (1757932200L); // verified 10:30 UTC
        long now = 1757936400L; // same UTC day 11:40
        assertEquals("10:30", CreangerDialogList.timeLabelFor(date, now));
    }

    @Test
    public void timeLabelYesterdayAndWeekdayAndDate() {
        long now = 1757936400L; // 2026-09-15 (Monday)
        assertEquals("Yesterday",
                CreangerDialogList.timeLabelFor((int) (now - 86400), now));
        // 3 days ago = Friday -> weekday label.
        String weekday = CreangerDialogList.timeLabelFor((int) (now - 3 * 86400), now);
        assertEquals("Fri", weekday);
        // 30 days ago -> dd.MM.yy.
        assertFalse(CreangerDialogList.timeLabelFor((int) (now - 30 * 86400), now).isEmpty());
        assertEquals("", CreangerDialogList.timeLabelFor(0, now));
        assertEquals("", CreangerDialogList.timeLabelFor(-5, now));
    }

    @Test
    public void lastMessagePreviewPrefixesAndTruncates() {
        assertEquals("You: hi", CreangerDialogList.lastMessagePreview("hi", true, null, true));
        assertEquals("You", CreangerDialogList.lastMessagePreview(null, true, null, true));
        assertEquals("Bob: hello", CreangerDialogList.lastMessagePreview("hello", false, "Bob", false));
        assertEquals("hello", CreangerDialogList.lastMessagePreview("hello", false, "Bob", true));
        assertEquals("", CreangerDialogList.lastMessagePreview(null, false, null, true));
        assertEquals("", CreangerDialogList.lastMessagePreview("null", false, "null", false));
        String longText = new String(new char[200]).replace('\0', 'a');
        String preview = CreangerDialogList.lastMessagePreview(longText, false, null, true);
        assertTrue(preview.length() <= 151);
        assertTrue(preview.endsWith("…"));
    }

    @Test
    public void unreadBadgeRules() {
        assertFalse(CreangerDialogList.shouldShowUnread(0));
        assertTrue(CreangerDialogList.shouldShowUnread(3));
        assertEquals("", CreangerDialogList.unreadText(0));
        assertEquals("3", CreangerDialogList.unreadText(3));
        assertEquals("120", CreangerDialogList.unreadText(120));
    }

    @Test
    public void sortPinnedFirstThenNewest() {
        assertTrue(CreangerDialogList.sortCompare(true, 1, false, 999) < 0);
        assertTrue(CreangerDialogList.sortCompare(false, 999, true, 1) > 0);
        assertTrue(CreangerDialogList.sortCompare(false, 200, false, 100) < 0);
        assertEquals(0, CreangerDialogList.sortCompare(false, 100, false, 100));
    }

    // ---- Row state: ISO dates, mute, unread, assembly ----

    private static CreangerMessage msg(String id, String senderId, String content,
                                       String createdAt, String deletedAt) {
        return new CreangerMessage(id, "c1", senderId, "text", content, "delivered",
                null, 1L, createdAt, null, deletedAt, null, null, false);
    }

    private static ChatMember member(String userId, String mutedUntil,
                                     Integer pinnedPosition, String lastReadAt) {
        return new ChatMember("m", "c1", userId, "member", "2026-09-01T00:00:00Z",
                null, mutedUntil, pinnedPosition, lastReadAt);
    }

    @Test
    public void isoParserHandlesZMillisOffsetAndGarbage() {
        assertEquals(1789468200, CreangerDialogList.parseIso8601ToUnix("2026-09-15T10:30:00Z"));
        assertEquals(1789468200, CreangerDialogList.parseIso8601ToUnix("2026-09-15T10:30:00.123Z"));
        assertEquals(1789468200, CreangerDialogList.parseIso8601ToUnix("2026-09-15T12:30:00+02:00"));
        assertEquals(1789468200, CreangerDialogList.parseIso8601ToUnix("2026-09-15T05:00:00-05:30"));
        assertEquals(0, CreangerDialogList.parseIso8601ToUnix(null));
        assertEquals(0, CreangerDialogList.parseIso8601ToUnix(""));
        assertEquals(0, CreangerDialogList.parseIso8601ToUnix("not-a-date"));
        assertEquals(0, CreangerDialogList.parseIso8601ToUnix("2026-13-45T99:99:99Z"));
    }

    @Test
    public void mutedOnlyWhenInFuture() {
        long nowMs = 1789471800L * 1000L; // 2026-09-15 11:40 UTC
        assertTrue(CreangerDialogList.isMuted("2026-09-16T00:00:00Z", nowMs));
        assertFalse(CreangerDialogList.isMuted("2026-09-14T00:00:00Z", nowMs));
        assertFalse(CreangerDialogList.isMuted(null, nowMs));
        assertFalse(CreangerDialogList.isMuted("", nowMs));
        assertFalse(CreangerDialogList.isMuted("garbage", nowMs));
    }

    @Test
    public void unreadCountsInboundNewerThanLastRead() {
        List<CreangerMessage> messages = new ArrayList<>();
        messages.add(msg("m3", "peer", "newest", "2026-09-15T11:00:00Z", null));
        messages.add(msg("m2", "me", "mine", "2026-09-15T10:50:00Z", null));
        messages.add(msg("m1", "peer", "old", "2026-09-15T10:00:00Z", null));
        assertEquals(1, CreangerDialogList.unreadCount(messages, "me", "2026-09-15T10:30:00Z"));
        assertEquals(0, CreangerDialogList.unreadCount(messages, "me", "2026-09-15T12:00:00Z"));
        // Never marked read: every inbound message counts.
        assertEquals(2, CreangerDialogList.unreadCount(messages, "me", null));
        assertEquals(0, CreangerDialogList.unreadCount(null, "me", null));
        assertEquals(0, CreangerDialogList.unreadCount(new ArrayList<CreangerMessage>(), "me", null));
    }

    @Test
    public void unreadSkipsDeletedAndUnknownSenders() {
        List<CreangerMessage> messages = new ArrayList<>();
        messages.add(msg("m1", "peer", "gone", "2026-09-15T11:00:00Z", "2026-09-15T11:05:00Z"));
        messages.add(msg("m2", null, "anon", "2026-09-15T11:00:00Z", null));
        assertEquals(0, CreangerDialogList.unreadCount(messages, "me", null));
    }

    @Test
    public void rowStateAssemblesLatestPreviewAndFlags() {
        List<CreangerMessage> messages = new ArrayList<>();
        messages.add(msg("m2", "peer", "hello there", "2026-09-15T10:30:00Z", null));
        messages.add(msg("m1", "me", "hi", "2026-09-15T10:00:00Z", null));
        ChatMember me = member("me", null, 3, "2026-09-15T10:15:00Z");
        CreangerDialogList.RowState row = CreangerDialogList.rowStateFor(
                chat("c1", "group", "Family", null, null), messages, "me", me, "Bob", 1789471800L * 1000L);
        assertEquals("hello there", row.lastMessage);
        assertEquals(1789468200, row.lastMessageDateSec);
        assertEquals(1, row.unreadCount);
        assertTrue(row.pinned);
        assertFalse(row.muted);
        assertFalse(row.out);
        assertEquals("Bob", row.senderName);
    }

    @Test
    public void rowStateSkipsDeletedLatestAndHandlesEmpty() {
        List<CreangerMessage> messages = new ArrayList<>();
        messages.add(msg("m2", "peer", "gone", "2026-09-15T10:30:00Z", "2026-09-15T10:35:00Z"));
        messages.add(msg("m1", "me", "mine", "2026-09-15T10:00:00Z", null));
        CreangerDialogList.RowState row = CreangerDialogList.rowStateFor(
                chat("c1", "direct", null, null, null), messages, "me", null, null, 0L);
        assertEquals("mine", row.lastMessage);
        assertTrue(row.out);
        assertEquals(0, row.unreadCount);
        assertFalse(row.pinned);
        assertFalse(row.muted);

        CreangerDialogList.RowState empty = CreangerDialogList.rowStateFor(
                chat("c1", "direct", null, null, null), null, "me", null, null, 0L);
        assertEquals("", empty.lastMessage);
        assertEquals(0, empty.lastMessageDateSec);
        assertEquals(0, empty.unreadCount);
        assertNull(CreangerDialogList.latestVisible(null));
    }
}
