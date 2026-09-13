package com.creanger.app.ui.Components.Premium.boosts;

import static com.creanger.app.messenger.AndroidUtilities.replaceTags;
import static com.creanger.app.messenger.LocaleController.formatPluralString;
import static com.creanger.app.messenger.LocaleController.getString;
import static com.creanger.app.ui.Components.Premium.boosts.SelectorBottomSheet.TYPE_CHANNEL;
import static com.creanger.app.ui.Components.Premium.boosts.SelectorBottomSheet.TYPE_COUNTRY;
import static com.creanger.app.ui.Components.Premium.boosts.SelectorBottomSheet.TYPE_USER;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.creanger.app.messenger.AndroidUtilities;
import com.creanger.app.messenger.ChatObject;
import com.creanger.app.messenger.DialogObject;
import com.creanger.app.messenger.LocaleController;
import com.creanger.app.messenger.MessagesController;
import com.creanger.app.messenger.R;
import com.creanger.app.messenger.UserConfig;
import com.creanger.app.messenger.UserObject;
import com.creanger.app.tgnet.TLRPC;
import com.creanger.app.ui.ActionBar.AlertDialog;
import com.creanger.app.ui.ActionBar.BaseFragment;
import com.creanger.app.ui.ActionBar.BottomSheet;
import com.creanger.app.ui.ActionBar.Theme;
import com.creanger.app.ui.Components.BulletinFactory;
import com.creanger.app.ui.LaunchActivity;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class BoostDialogs {

    public static void showToastError(Context context, TLRPC.TL_error error) {
        if (error != null && error.text != null && !TextUtils.isEmpty(error.text)) {
            Toast.makeText(context, error.text, Toast.LENGTH_LONG).show();
        }
    }

    public static void processApplyGiftCodeError(TLRPC.TL_error error, FrameLayout containerLayout, Theme.ResourcesProvider resourcesProvider, Runnable share) {
        if (error == null || error.text == null) {
            return;
        }
        if (error.text.contains("PREMIUM_SUB_ACTIVE_UNTIL_")) {
            String strDate = error.text.replace("PREMIUM_SUB_ACTIVE_UNTIL_", "");
            long date = Long.parseLong(strDate);
            String formattedDate = LocaleController.getInstance().getFormatterBoostExpired().format(new Date(date * 1000L));
            String subTitleText = getString("GiftPremiumActivateErrorText", R.string.GiftPremiumActivateErrorText);
            SpannableStringBuilder subTitleWithLink = AndroidUtilities.replaceSingleTag(
                    subTitleText,
                    Theme.key_undo_cancelColor, 0,
                    share);
            BulletinFactory.of(containerLayout, resourcesProvider).createSimpleBulletin(R.raw.chats_infotip,
                    LocaleController.getString(R.string.GiftPremiumActivateErrorTitle),
                    AndroidUtilities.replaceCharSequence("%1$s", subTitleWithLink, replaceTags("**" + formattedDate + "**"))
            ).show();
            try {
                containerLayout.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
            } catch (Exception ignore) {}
        } else {
            BoostDialogs.showToastError(containerLayout.getContext(), error);
        }
    }

    public static void showGiftLinkForwardedBulletin(long did) {
        CharSequence text;
        if (did == UserConfig.getInstance(UserConfig.selectedAccount).clientUserId) {
            text = AndroidUtilities.replaceTags(LocaleController.getString(R.string.BoostingGiftLinkForwardedToSavedMsg));
        } else {
            if (DialogObject.isChatDialog(did)) {
                TLRPC.Chat chat = MessagesController.getInstance(UserConfig.selectedAccount).getChat(-did);
                text = AndroidUtilities.replaceTags(LocaleController.formatString("BoostingGiftLinkForwardedTo", R.string.BoostingGiftLinkForwardedTo, chat.title));
            } else {
                TLRPC.User user = MessagesController.getInstance(UserConfig.selectedAccount).getUser(did);
                text = AndroidUtilities.replaceTags(LocaleController.formatString("BoostingGiftLinkForwardedTo", R.string.BoostingGiftLinkForwardedTo, UserObject.getFirstName(user)));
            }
        }
        AndroidUtilities.runOnUIThread(() -> {
            BulletinFactory bulletinFactory = BulletinFactory.global();
            if (bulletinFactory != null) {
                bulletinFactory.createSimpleBulletinWithIconSize(R.raw.forward, text, 30).show();
            }
        }, 450);
    }

    public static void showUnsavedChanges(int type, Context context, Theme.ResourcesProvider resourcesProvider, Runnable onApply, Runnable onDiscard) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setTitle(getString("UnsavedChanges", R.string.UnsavedChanges));
        String text;
        switch (type) {
            case TYPE_USER:
                text = getString("BoostingApplyChangesUsers", R.string.BoostingApplyChangesUsers);
                break;
            case TYPE_CHANNEL:
                text = getString("BoostingApplyChangesChannels", R.string.BoostingApplyChangesChannels);
                break;
            case TYPE_COUNTRY:
                text = getString("BoostingApplyChangesCountries", R.string.BoostingApplyChangesCountries);
                break;
            default:
                text = "";
        }
        builder.setMessage(text);
        builder.setPositiveButton(getString("ApplyTheme", R.string.ApplyTheme), (dialogInterface, i) -> {
            onApply.run();
        });
        builder.setNegativeButton(getString("Discard", R.string.Discard), (dialogInterface, i) -> {
            onDiscard.run();
        });
        builder.show();
    }

    public static boolean checkReduceUsers(Context context, Theme.ResourcesProvider resourcesProvider, List<TLRPC.TL_premiumGiftCodeOption> list, TLRPC.TL_premiumGiftCodeOption selected) {
        if (selected.store_product == null) {
            List<Integer> result = new ArrayList<>();
            for (TLRPC.TL_premiumGiftCodeOption item : list) {
                if (item.months == selected.months && item.store_product != null) {
                    result.add(item.users);
                }
            }

            String downTo = TextUtils.join(", ", result);
            int current = selected.users;

            AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
            builder.setTitle(getString("BoostingReduceQuantity", R.string.BoostingReduceQuantity));
            builder.setMessage(replaceTags(formatPluralString("BoostingReduceUsersTextPlural", current, downTo)));
            builder.setPositiveButton(getString("OK", R.string.OK), (dialogInterface, i) -> {

            });
            builder.show();
            return true;
        }
        return false;
    }

    public static void showPrivateChannelAlert(TLRPC.Chat chat, Context context, Theme.ResourcesProvider resourcesProvider, Runnable onCanceled, Runnable onAccepted) {
        final AtomicBoolean isAddButtonClicked = new AtomicBoolean(false);
        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        boolean isChannel = ChatObject.isChannelAndNotMegaGroup(chat);
        builder.setTitle(getString(isChannel ? R.string.BoostingGiveawayPrivateChannel : R.string.BoostingGiveawayPrivateGroup));
        builder.setMessage(getString(isChannel ? R.string.BoostingGiveawayPrivateChannelWarning : R.string.BoostingGiveawayPrivateGroupWarning));
        builder.setPositiveButton(getString("Add", R.string.Add), (dialogInterface, i) -> {
            isAddButtonClicked.set(true);
            onAccepted.run();
        });
        builder.setNegativeButton(getString("Cancel", R.string.Cancel), (dialogInterface, i) -> {

        });
        builder.setOnDismissListener(dialog -> {
            if (!isAddButtonClicked.get()) {
                onCanceled.run();
            }
        });
        builder.show();
    }
}
