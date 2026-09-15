package com.creanger.app.ui.Cells;

import static com.creanger.app.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.creanger.app.messenger.AndroidUtilities;
import com.creanger.app.messenger.creanger.data.CreangerDialogList;
import com.creanger.app.messenger.creanger.model.ChatModels.CreangerChat;
import com.creanger.app.ui.ActionBar.Theme;
import com.creanger.app.ui.Components.LayoutHelper;

/**
 * Dialog-list row for a Creanger chat, fully Telegram-style.
 *
 * <p>Mirrors the stock {@link DialogCell} rhythm (70dp row, 46dp avatar,
 * title + date on the first line, preview + badge on the second) but renders
 * straight from {@link CreangerChat} + dialog state — Creanger chats have no
 * MTProto peer/message objects. All date/preview/badge decisions come from
 * the pure-JVM {@link CreangerDialogList} helpers so they stay unit-testable.
 */
public class CreangerDialogCell extends FrameLayout {

    /** Avatar palette picked deterministically from the chat id hash. */
    private static final int[] AVATAR_COLORS = {
            0xFF4A6CF7, 0xFF7C4DFF, 0xFF00ACC1, 0xFF43A047,
            0xFFFB8C00, 0xFFE53935, 0xFF8E24AA
    };

    private final TextView avatarText;
    private final GradientDrawable avatarBg;
    private final TextView titleText;
    private final TextView timeText;
    private final TextView previewText;
    private final TextView unreadBadge;
    private final GradientDrawable unreadBg;
    private final TextView pinText;
    private String chatId;

    public CreangerDialogCell(Context context) {
        super(context);
        setBackground(Theme.createSelectorDrawable(
                Theme.getColor(Theme.key_windowBackgroundWhite), 0));

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        addView(row, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 70, Gravity.CENTER_VERTICAL));

        FrameLayout avatarHolder = new FrameLayout(context);
        row.addView(avatarHolder, LayoutHelper.createLinear(46, 46, Gravity.CENTER_VERTICAL, 12, 0, 0, 0));
        avatarBg = new GradientDrawable();
        avatarBg.setShape(GradientDrawable.OVAL);
        avatarHolder.setBackground(avatarBg);
        avatarText = new TextView(context);
        avatarText.setTextSize(17);
        avatarText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        avatarText.setTextColor(0xFFFFFFFF);
        avatarText.setGravity(Gravity.CENTER);
        avatarHolder.addView(avatarText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 12, 0, 12, 0));

        // First line: title (weight 1) + date.
        LinearLayout topLine = new LinearLayout(context);
        topLine.setOrientation(LinearLayout.HORIZONTAL);
        topLine.setGravity(Gravity.CENTER_VERTICAL);
        texts.addView(topLine, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        titleText = new TextView(context);
        titleText.setTextSize(16);
        titleText.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM));
        titleText.setSingleLine(true);
        titleText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        topLine.addView(titleText, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 0, 0, 8, 0));

        timeText = new TextView(context);
        timeText.setTextSize(13);
        timeText.setSingleLine(true);
        topLine.addView(timeText, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 0, 0, 0, 0));

        // Second line: preview (weight 1) + unread badge + pin.
        LinearLayout bottomLine = new LinearLayout(context);
        bottomLine.setOrientation(LinearLayout.HORIZONTAL);
        bottomLine.setGravity(Gravity.CENTER_VERTICAL);
        texts.addView(bottomLine, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        previewText = new TextView(context);
        previewText.setTextSize(14);
        previewText.setSingleLine(true);
        previewText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bottomLine.addView(previewText, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 0, 0, 8, 0));

        pinText = new TextView(context);
        pinText.setTextSize(12);
        pinText.setSingleLine(true);
        pinText.setText("📌");
        pinText.setVisibility(GONE);
        bottomLine.addView(pinText, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 0, 0, 4, 0));

        unreadBadge = new TextView(context);
        unreadBadge.setTextSize(13);
        unreadBadge.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        unreadBadge.setTextColor(0xFFFFFFFF);
        unreadBadge.setGravity(Gravity.CENTER);
        unreadBadge.setSingleLine(true);
        unreadBadge.setVisibility(GONE);
        unreadBadge.setMinWidth(dp(20));
        unreadBadge.setPadding(dp(5), dp(1), dp(5), dp(2));
        unreadBg = new GradientDrawable();
        unreadBg.setShape(GradientDrawable.OVAL);
        unreadBadge.setBackground(unreadBg);
        bottomLine.addView(unreadBadge, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 0, 0, 0, 0));
    }

    public void bind(CreangerChat chat) {
        bind(chat, null, null);
    }

    /** Legacy bind: title + subtitle preview, no date/badge (kept for compat). */
    public void bind(CreangerChat chat, String peerDisplayName, String peerUsername) {
        bindFull(chat, peerDisplayName, peerUsername,
                CreangerDialogList.subtitleFor(chat, peerDisplayName, peerUsername),
                0, 0, false, false, false, null, chat != null && chat.isDirect(),
                System.currentTimeMillis() / 1000);
    }

    /**
     * Fully Telegram-style bind: title + date on line one, last-message
     * preview + unread badge / pin on line two.
     */
    public void bindFull(CreangerChat chat, String peerDisplayName, String peerUsername,
                         String lastMessage, int lastMessageDateSec, int unreadCount,
                         boolean pinned, boolean muted, boolean out,
                         String senderName, boolean isDirect, long nowSec) {
        chatId = chat != null ? chat.id : null;
        titleText.setText(CreangerDialogList.titleFor(chat, peerDisplayName, peerUsername));
        titleText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));

        String time = CreangerDialogList.timeLabelFor(lastMessageDateSec, nowSec);
        timeText.setText(time);
        timeText.setVisibility(time.isEmpty() ? GONE : VISIBLE);
        timeText.setTextColor(Theme.getColor(CreangerDialogList.shouldShowUnread(unreadCount)
                ? Theme.key_chats_date_bold : Theme.key_chats_date));

        String preview = CreangerDialogList.lastMessagePreview(lastMessage, out, senderName, isDirect);
        if (preview.isEmpty()) {
            preview = CreangerDialogList.subtitleFor(chat, peerDisplayName, peerUsername);
        }
        previewText.setText(preview);
        previewText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));

        if (CreangerDialogList.shouldShowUnread(unreadCount)) {
            unreadBadge.setVisibility(VISIBLE);
            unreadBadge.setText(CreangerDialogList.unreadText(unreadCount));
            unreadBg.setColor(Theme.getColor(muted
                    ? Theme.key_chats_unreadCounterMuted : Theme.key_chats_unreadCounter));
            pinText.setVisibility(GONE);
        } else {
            unreadBadge.setVisibility(GONE);
            if (pinned) {
                pinText.setVisibility(VISIBLE);
                pinText.setTextColor(Theme.getColor(Theme.key_chats_pinnedOverlay));
            } else {
                pinText.setVisibility(GONE);
            }
        }

        avatarText.setText(CreangerDialogList.initialsFor(chat, peerDisplayName, peerUsername));
        avatarBg.setColor(avatarColorFor(chatId));
        setContentDescription(titleText.getText());
    }

    /** Creanger chat UUID backing this row, or null. */
    public String getCreangerChatId() {
        return chatId;
    }

    public static int avatarColorFor(String id) {
        return AVATAR_COLORS[Math.abs(id != null ? id.hashCode() : 0) % AVATAR_COLORS.length];
    }
}
