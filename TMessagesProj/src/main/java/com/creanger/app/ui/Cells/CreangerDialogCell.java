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
 * Dialog-list row for a Creanger chat. Deliberately independent of
 * {@link DialogCell}: Creanger chats have no MTProto peer/message objects, so
 * the row renders straight from {@link CreangerChat} (avatar initials, title,
 * subtitle) with the same 70dp rhythm and theme keys as the stock rows.
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
    private final TextView subtitleText;
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

        titleText = new TextView(context);
        titleText.setTextSize(16);
        titleText.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM));
        titleText.setSingleLine(true);
        titleText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(titleText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        subtitleText = new TextView(context);
        subtitleText.setTextSize(14);
        subtitleText.setSingleLine(true);
        subtitleText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(subtitleText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    public void bind(CreangerChat chat) {
        bind(chat, null, null);
    }

    /** Binds with direct-chat peer identity (display name / username). */
    public void bind(CreangerChat chat, String peerDisplayName, String peerUsername) {
        chatId = chat != null ? chat.id : null;
        titleText.setText(CreangerDialogList.titleFor(chat, peerDisplayName, peerUsername));
        titleText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        subtitleText.setText(CreangerDialogList.subtitleFor(chat, peerDisplayName, peerUsername));
        subtitleText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
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
