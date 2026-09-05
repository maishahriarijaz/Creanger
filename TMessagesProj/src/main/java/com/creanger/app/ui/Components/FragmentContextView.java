/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package com.creanger.app.ui.Components;

import com.creanger.app.tgnet.TLRPC;
import static com.creanger.app.messenger.AndroidUtilities.dp;
import static com.creanger.app.messenger.AndroidUtilities.dpf2;
import static com.creanger.app.messenger.AndroidUtilities.lerp;
import static com.creanger.app.messenger.AndroidUtilities.pointTmp2;
import static com.creanger.app.messenger.LocaleController.getString;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.IntDef;
import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;

import com.creanger.app.messenger.AccountInstance;
import com.creanger.app.messenger.AndroidUtilities;
import com.creanger.app.messenger.AnimationNotificationsLocker;
import com.creanger.app.messenger.ChatObject;
import com.creanger.app.messenger.ContactsController;
import com.creanger.app.messenger.DialogObject;
import com.creanger.app.messenger.LocaleController;
import com.creanger.app.messenger.LocationController;
import com.creanger.app.messenger.MediaController;
import com.creanger.app.messenger.MessageObject;
import com.creanger.app.messenger.MessagesController;
import com.creanger.app.messenger.NotificationCenter;
import com.creanger.app.messenger.R;
import com.creanger.app.messenger.SendMessagesHelper;
import com.creanger.app.messenger.UserConfig;
import com.creanger.app.messenger.UserObject;
import com.creanger.app.ui.ActionBar.ActionBarMenuItem;
import com.creanger.app.ui.ActionBar.ActionBarMenuSlider;
import com.creanger.app.ui.ActionBar.AlertDialog;
import com.creanger.app.ui.ActionBar.BaseFragment;
import com.creanger.app.ui.ActionBar.Theme;
import com.creanger.app.ui.ChatActivity;
import com.creanger.app.ui.DialogsActivity;
import com.creanger.app.ui.LaunchActivity;
import com.creanger.app.ui.LocationActivity;


import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;

import me.vkryl.android.animator.ListAnimator;
import me.vkryl.android.animator.ReplaceAnimator;
import me.vkryl.core.lambda.Destroyable;

public class FragmentContextView extends FrameLayout implements NotificationCenter.NotificationCenterDelegate {
    public final static int STYLE_NOT_SET = -1,
            STYLE_AUDIO_PLAYER = 0,
            STYLE_LIVE_LOCATION = 2,
            STYLE_IMPORTING_MESSAGES = 5;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            STYLE_NOT_SET,
            STYLE_AUDIO_PLAYER,
            STYLE_LIVE_LOCATION,
            STYLE_IMPORTING_MESSAGES
    })
    public @interface Style {}

    private ImageView playButton;
    private PlayPauseDrawable playPauseDrawable;
    private AudioPlayerAlert.ClippingTextViewSwitcher titleTextView;
    private AudioPlayerAlert.ClippingTextViewSwitcher subtitleTextView;
    private AnimatorSet animatorSet;
    private BaseFragment fragment;
    private ChatActivityInterface chatActivity;
    private View applyingView;
    private FrameLayout frameLayout;
    private View shadow;
    private View selector;
    private RLottieImageView importingImageView;
    private ImageView closeButton;
    private ActionBarMenuItem playbackSpeedButton;
    private SpeedIconDrawable speedIcon;
    private ActionBarMenuSlider.SpeedSlider speedSlider;
    private ActionBarMenuItem.Item[] speedItems = new ActionBarMenuItem.Item[6];
    private FrameLayout silentButton;
    private ImageView silentButtonImage;

    private int currentProgress = -1;

    private MessageObject lastMessageObject;
    protected float topPadding;
    private boolean visible;
    @Style
    private int currentStyle = STYLE_NOT_SET;
    private String lastString;
    private boolean isMusic;
    private boolean supportsCalls = true;
    private AvatarsImageView avatars;

    private final int account = UserConfig.selectedAccount;

    private boolean isLocation;

    private FragmentContextViewDelegate delegate;
    private final Theme.ResourcesProvider resourcesProvider;
    private final boolean isSideMenued;

    private boolean firstLocationsLoaded;
    private int lastLocationSharingCount = -1;
    private Runnable checkLocationRunnable = new Runnable() {
        @Override
        public void run() {
            checkLocationString();
            AndroidUtilities.runOnUIThread(checkLocationRunnable, 1000);
        }
    };
    private AnimationNotificationsLocker notificationsLocker = new AnimationNotificationsLocker();
    private AnimationNotificationsLocker notificationsLocker2 = new AnimationNotificationsLocker(new int[]{
            NotificationCenter.messagesDidLoad
    });


    private boolean checkCallAfterAnimation;
    private boolean checkPlayerAfterAnimation;
    private boolean checkImportAfterAnimation;

    private final static float[] speeds = new float[] {
        .5f, 1f, 1.2f, 1.5f, 1.7f, 2f
    };

    public interface FragmentContextViewDelegate {
        void onAnimation(boolean start, boolean show);
    }

    public FragmentContextView(Context context, BaseFragment parentFragment, boolean location) {
        this(context, parentFragment, null, location, null);
    }

    public FragmentContextView(Context context, BaseFragment parentFragment, boolean location, Theme.ResourcesProvider resourcesProvider) {
        this(context, parentFragment, null, location, resourcesProvider);
    }

    public FragmentContextView(Context context, BaseFragment parentFragment, View paddingView, boolean location, Theme.ResourcesProvider resourcesProvider) {
        this(context, parentFragment, paddingView, location, resourcesProvider, false);
    }

    public FragmentContextView(Context context, BaseFragment parentFragment, View paddingView, boolean location, Theme.ResourcesProvider resourcesProvider, boolean isSideMenued) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        this.isSideMenued = isSideMenued;

        fragment = parentFragment;
        if (parentFragment instanceof ChatActivityInterface) {
            chatActivity = (ChatActivityInterface) parentFragment;
        }
        applyingView = paddingView;
        visible = true;
        isLocation = location;
        if (applyingView == null) {
            ((ViewGroup) fragment.getFragmentView()).setClipToPadding(false);
        }

        setTag(1);
    }

    public void setSupportsCalls(boolean value) {
        supportsCalls = value;
    }

    public void setDelegate(FragmentContextViewDelegate fragmentContextViewDelegate) {
        delegate = fragmentContextViewDelegate;
    }

    private void checkCreateView() {
        if (frameLayout != null) {
            return;
        }

        final Context context = getContext();
        frameLayout = new FrameLayout(context) {

            @Override
            public void invalidate() {
                super.invalidate();
                if (avatars != null && avatars.getVisibility() == VISIBLE) {
                    avatars.invalidate();
                }
            }
        };
        addView(frameLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 36, Gravity.TOP | Gravity.LEFT, 0, 0, 0, 0));

        selector = new View(context);
        frameLayout.addView(selector, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        if (!isInsideBubble) {
            shadow = new View(context);
            shadow.setBackgroundResource(R.drawable.blockpanel_shadow);
            addView(shadow, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 2, Gravity.LEFT | Gravity.TOP, 0, 36, 0, 0));
        }

        playButton = new ImageView(context);
        playButton.setScaleType(ImageView.ScaleType.CENTER);
        playButton.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_inappPlayerPlayPause), PorterDuff.Mode.MULTIPLY));
        playButton.setImageDrawable(playPauseDrawable = new PlayPauseDrawable(16));
        playButton.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_inappPlayerPlayPause) & 0x19ffffff, 1, dp(14)));
        addView(playButton, LayoutHelper.createFrame(36, 36, Gravity.TOP | Gravity.LEFT));
        playButton.setOnClickListener(v -> {
            if (currentStyle == STYLE_AUDIO_PLAYER) {
                if (MediaController.getInstance().isMessagePaused()) {
                    MediaController.getInstance().playMessage(MediaController.getInstance().getPlayingMessageObject());
                } else {
                    MediaController.getInstance().pauseMessage(MediaController.getInstance().getPlayingMessageObject());
                }
            }
        });

        importingImageView = new RLottieImageView(context);
        importingImageView.setScaleType(ImageView.ScaleType.CENTER);
        importingImageView.setAutoRepeat(true);
        importingImageView.setAnimation(R.raw.import_progress, 30, 30);
        importingImageView.setBackground(Theme.createCircleDrawable(dp(22), getThemedColor(Theme.key_inappPlayerPlayPause)));
        addView(importingImageView, LayoutHelper.createFrame(22, 22, Gravity.TOP | Gravity.LEFT, 7, 7, 0, 0));

        titleTextView = new AudioPlayerAlert.ClippingTextViewSwitcher(context) {
            @Override
            protected TextView createTextView() {
                TextView textView = new TextView(context);
                textView.setMaxLines(1);
                textView.setLines(1);
                textView.setSingleLine(true);
                textView.setEllipsize(TextUtils.TruncateAt.END);
                textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
                textView.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
                if (currentStyle == STYLE_AUDIO_PLAYER || currentStyle == STYLE_LIVE_LOCATION) {
                    textView.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
                    textView.setTypeface(Typeface.DEFAULT);
                    textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
                }
                return textView;
            }
        };
        addView(titleTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 36, Gravity.LEFT | Gravity.TOP, 35, 0, 36 + (isSideMenued ? 64 : 0), 0));

        subtitleTextView = new AudioPlayerAlert.ClippingTextViewSwitcher(context) {
            @Override
            protected TextView createTextView() {
                TextView textView = new TextView(context);
                textView.setMaxLines(1);
                textView.setLines(1);
                textView.setSingleLine(true);
                textView.setEllipsize(TextUtils.TruncateAt.END);
                textView.setGravity(Gravity.LEFT);
                textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                textView.setTextColor(getThemedColor(Theme.key_inappPlayerClose));
                return textView;
            }
        };
        addView(subtitleTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 36, Gravity.LEFT | Gravity.TOP, 35, 10, 36 + (isSideMenued ? 64 : 0), 0));

        silentButton = new FrameLayout(context);
        silentButtonImage = new ImageView(context);
        silentButtonImage.setImageResource(R.drawable.msg_mute);
        silentButtonImage.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_inappPlayerClose), PorterDuff.Mode.MULTIPLY));
        silentButton.addView(silentButtonImage, LayoutHelper.createFrame(20, 20, Gravity.CENTER));
        silentButton.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_inappPlayerClose) & 0x19ffffff, 1, dp(14)));
        silentButton.setContentDescription(getString(R.string.Unmute));
        silentButton.setOnClickListener(e -> {
            MediaController.getInstance().updateSilent(false);
        });
        silentButton.setVisibility(View.GONE);
        addView(silentButton, LayoutHelper.createFrame(36, 36, Gravity.RIGHT | Gravity.TOP, 0, 0, 36, 0));

        if (!isLocation) {
            createPlaybackSpeedButton();
        }

        avatars = new AvatarsImageView(context, false);
        avatars.setAvatarsTextSize(dp(21));
        avatars.setDelegate(() -> updateAvatars(true));
        avatars.setVisibility(GONE);
        addView(avatars, LayoutHelper.createFrame(108, 36, Gravity.LEFT | Gravity.TOP));

        closeButton = new ImageView(context);
        closeButton.setImageResource(R.drawable.miniplayer_close);
        closeButton.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_inappPlayerClose), PorterDuff.Mode.MULTIPLY));
        closeButton.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_inappPlayerClose) & 0x19ffffff, 1, dp(14)));
        closeButton.setScaleType(ImageView.ScaleType.CENTER);
        addView(closeButton, LayoutHelper.createFrame(36, 36, Gravity.RIGHT | Gravity.TOP, 0, 0, 4, 0));
        closeButton.setOnClickListener(v -> {
            if (currentStyle == STYLE_LIVE_LOCATION) {
                AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity(), resourcesProvider);
                builder.setTitle(getString(R.string.StopLiveLocationAlertToTitle));
                if (fragment instanceof DialogsActivity) {
                    builder.setMessage(getString(R.string.StopLiveLocationAlertAllText));
                } else {
                    TLRPC.Chat chat = chatActivity.getCurrentChat();
                    TLRPC.User user = chatActivity.getCurrentUser();
                    if (chat != null) {
                        builder.setMessage(AndroidUtilities.replaceTags(LocaleController.formatString("StopLiveLocationAlertToGroupText", R.string.StopLiveLocationAlertToGroupText, chat.title)));
                    } else if (user != null) {
                        builder.setMessage(AndroidUtilities.replaceTags(LocaleController.formatString("StopLiveLocationAlertToUserText", R.string.StopLiveLocationAlertToUserText, UserObject.getFirstName(user))));
                    } else {
                        builder.setMessage(getString(R.string.AreYouSure));
                    }
                }
                builder.setPositiveButton(getString(R.string.Stop), (dialogInterface, i) -> {
                    if (fragment instanceof DialogsActivity) {
                        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                            LocationController.getInstance(a).removeAllLocationSharings();
                        }
                    } else {
                        LocationController.getInstance(fragment.getCurrentAccount()).removeSharingLocation(chatActivity.getDialogId());
                    }
                });
                builder.setNegativeButton(getString(R.string.Cancel), null);
                AlertDialog alertDialog = builder.create();
                builder.show();
                TextView button = (TextView) alertDialog.getButton(DialogInterface.BUTTON_POSITIVE);
                if (button != null) {
                    button.setTextColor(getThemedColor(Theme.key_text_RedBold));
                }
            } else {
                MediaController.getInstance().cleanupPlayer(true, true);
            }
        });

        setOnClickListener(v -> {
            if (currentStyle == STYLE_AUDIO_PLAYER) {
                MessageObject messageObject = MediaController.getInstance().getPlayingMessageObject();
                if (fragment != null && messageObject != null) {
                    if (messageObject.isMusic()) {
                        final Activity activity = AndroidUtilities.findActivity(getContext());
                        if (activity instanceof LaunchActivity) {
                            new AudioPlayerAlert(activity, resourcesProvider).show();
                        } else if (AndroidUtilities.isContextSafe(LaunchActivity.instance)) {
                            new AudioPlayerAlert(LaunchActivity.instance, resourcesProvider).show();
                        }
                    } else {
                        long dialogId = 0;
                        if (chatActivity != null) {
                            dialogId = chatActivity.getDialogId();
                        }
                        if (messageObject.getDialogId() == dialogId) {
                            chatActivity.scrollToMessageId(messageObject.getId(), 0, false, 0, true, 0);
                        } else {
                            dialogId = messageObject.getDialogId();
                            Bundle args = new Bundle();
                            if (DialogObject.isEncryptedDialog(dialogId)) {
                                args.putInt("enc_id", DialogObject.getEncryptedChatId(dialogId));
                            } else if (DialogObject.isUserDialog(dialogId)) {
                                args.putLong("user_id", dialogId);
                            } else {
                                args.putLong("chat_id", -dialogId);
                            }
                            args.putInt("message_id", messageObject.getId());
                            fragment.presentFragment(new ChatActivity(args), fragment instanceof ChatActivity);
                        }
                    }
                }
            } else if (currentStyle == STYLE_LIVE_LOCATION) {
                long did = 0;
                int account = UserConfig.selectedAccount;
                if (chatActivity != null) {
                    did = chatActivity.getDialogId();
                    account = fragment.getCurrentAccount();
                } else if (LocationController.getLocationsCount() == 1) {
                    for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                        ArrayList<LocationController.SharingLocationInfo> arrayList = LocationController.getInstance(a).sharingLocationsUI;
                        if (!arrayList.isEmpty()) {
                            LocationController.SharingLocationInfo info = LocationController.getInstance(a).sharingLocationsUI.get(0);
                            did = info.did;
                            account = info.messageObject.currentAccount;
                            break;
                        }
                    }
                }
                if (did != 0) {
                    openSharingLocation(LocationController.getInstance(account).getSharingLocationInfo(did));
                } else {
                    fragment.showDialog(new SharingLocationsAlert(getContext(), this::openSharingLocation, resourcesProvider));
                }
            } else if (currentStyle == STYLE_IMPORTING_MESSAGES) {
                SendMessagesHelper.ImportingHistory importingHistory = fragment.getSendMessagesHelper().getImportingHistory(((ChatActivity) fragment).getDialogId());
                if (importingHistory == null) {
                    return;
                }
                ImportingAlert importingAlert = new ImportingAlert(getContext(), null, (ChatActivity) fragment, resourcesProvider);
                importingAlert.setOnHideListener(dialog -> checkImport(false));
                fragment.showDialog(importingAlert);
                checkImport(false);
            }
        });

        setLeftMargin(leftMargin);
    }

    private boolean slidingSpeed;

    private void createPlaybackSpeedButton() {
        if (playbackSpeedButton != null) {
            return;
        }
        playbackSpeedButton = new ActionBarMenuItem(getContext(), null, 0, getThemedColor(Theme.key_dialogTextBlack), resourcesProvider);
        playbackSpeedButton.setAdditionalYOffset(dp(24 + 6));
        playbackSpeedButton.setLongClickEnabled(false);
        playbackSpeedButton.setVisibility(GONE);
        playbackSpeedButton.setTag(null);
        playbackSpeedButton.setShowSubmenuByMove(false);
        playbackSpeedButton.setContentDescription(getString(R.string.AccDescrPlayerSpeed));
        playbackSpeedButton.setDelegate(id -> {
            if (id < 0 || id >= speeds.length) {
                return;
            }
            float oldSpeed = MediaController.getInstance().getPlaybackSpeed(isMusic), newSpeed = speeds[id];
            MediaController.getInstance().setPlaybackSpeed(isMusic, newSpeed);
            if (oldSpeed != newSpeed) {
                playbackSpeedChanged(false, oldSpeed, newSpeed);
            }
        });
        playbackSpeedButton.setIcon(speedIcon = new SpeedIconDrawable(true));
        final float[] toggleSpeeds = new float[] { 1.0F, 1.5F, 2F };
        speedSlider = new ActionBarMenuSlider.SpeedSlider(getContext(), resourcesProvider);
        speedSlider.setRoundRadiusDp(6);
        speedSlider.setDrawShadow(true);
        speedSlider.setOnValueChange((value, isFinal) -> {
            slidingSpeed = !isFinal;
            MediaController.getInstance().setPlaybackSpeed(isMusic, speedSlider.getSpeed(value));
        });
        speedItems[0] = playbackSpeedButton.lazilyAddSubItem(0, R.drawable.msg_speed_slow, getString(R.string.SpeedSlow));
        speedItems[1] = playbackSpeedButton.lazilyAddSubItem(1, R.drawable.msg_speed_normal, getString(R.string.SpeedNormal));
        speedItems[2] = playbackSpeedButton.lazilyAddSubItem(2, R.drawable.msg_speed_medium, getString(R.string.SpeedMedium));
        speedItems[3] = playbackSpeedButton.lazilyAddSubItem(3, R.drawable.msg_speed_fast, getString(R.string.SpeedFast));
        speedItems[4] = playbackSpeedButton.lazilyAddSubItem(4, R.drawable.msg_speed_veryfast, getString(R.string.SpeedVeryFast));
        speedItems[5] = playbackSpeedButton.lazilyAddSubItem(5, R.drawable.msg_speed_superfast, getString(R.string.SpeedSuperFast));
        if (AndroidUtilities.density >= 3.0f) {
            playbackSpeedButton.setPadding(0, 1, 0, 0);
        }
        playbackSpeedButton.setAdditionalXOffset(dp(8));
        addView(playbackSpeedButton, LayoutHelper.createFrame(36, 36, Gravity.TOP | Gravity.RIGHT, 0, 0, 36, 0));
        playbackSpeedButton.setOnClickListener(v -> {
            float currentPlaybackSpeed = MediaController.getInstance().getPlaybackSpeed(isMusic);
            float newSpeed;
            int index = -1;
            for (int i = 0; i < toggleSpeeds.length; ++i) {
                if (currentPlaybackSpeed - 0.1F <= toggleSpeeds[i]) {
                    index = i;
                    break;
                }
            }
            index++;
            if (index >= toggleSpeeds.length) {
                index = 0;
            }
            newSpeed = toggleSpeeds[index];
            MediaController.getInstance().setPlaybackSpeed(isMusic, newSpeed);
            playbackSpeedChanged(true, currentPlaybackSpeed, newSpeed);

            checkSpeedHint();
        });
        playbackSpeedButton.setOnLongClickListener(view -> {
            final float speed = MediaController.getInstance().getPlaybackSpeed(isMusic);
            speedSlider.setSpeed(speed, false);
            speedSlider.setBackgroundColor(Theme.getColor(Theme.key_actionBarDefaultSubmenuBackground, resourcesProvider));
            speedSlider.invalidateBlur(fragment instanceof ChatActivity);
            playbackSpeedButton.redrawPopup(Theme.getColor(Theme.key_actionBarDefaultSubmenuBackground));
            playbackSpeedButton.updateColor();
            updatePlaybackButton(false);
            playbackSpeedButton.setDimMenu(.3f);
            playbackSpeedButton.toggleSubMenu(speedSlider, null);
            playbackSpeedButton.setOnMenuDismiss(byButton -> {
                if (!byButton) {
                    playbackSpeedChanged(false, speed, MediaController.getInstance().getPlaybackSpeed(isMusic));
                }
            });
            MessagesController.getGlobalNotificationsSettings().edit().putInt("speedhint", -15).apply();
            return true;
        });
        updatePlaybackButton(false);
    }

    private HintView speedHintView;
    private long lastPlaybackClick;

    private void checkSpeedHint() {
        final long now = System.currentTimeMillis();
        if (now - lastPlaybackClick > 300) {
            int hintValue = MessagesController.getGlobalNotificationsSettings().getInt("speedhint", 0);
            hintValue++;
            if (hintValue > 2) {
                hintValue = -10;
            }
            MessagesController.getGlobalNotificationsSettings().edit().putInt("speedhint", hintValue).apply();
            if (hintValue >= 0) {
                showSpeedHint();
            }
        }
        lastPlaybackClick = now;
    }

    private void showSpeedHint() {
        if (fragment != null && getParent() instanceof ViewGroup) {
            speedHintView = new HintView(getContext(), 6, true) {
                @Override
                public void setVisibility(int visibility) {
                    super.setVisibility(visibility);
                    if (visibility != View.VISIBLE) {
                        try {
                            ((ViewGroup) getParent()).removeView(this);
                        } catch (Exception e) {}
                    }
                }
            };
            speedHintView.setExtraTranslationY(dp(-12));
            speedHintView.setText(getString(R.string.SpeedHint));
            MarginLayoutParams params = new MarginLayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.rightMargin = dp(3);
            ((ViewGroup) getParent()).addView(speedHintView, params);
            speedHintView.showForView(playbackSpeedButton, true);
        }
    }

    public void onPanTranslationUpdate(float y) {
        if (speedHintView != null) {
            speedHintView.setExtraTranslationY(dp(64 + 8) + y);
        }
    }

    private void updatePlaybackButton(boolean animated) {
        if (speedIcon == null) {
            return;
        }
        float currentPlaybackSpeed = MediaController.getInstance().getPlaybackSpeed(isMusic);
        speedIcon.setValue(currentPlaybackSpeed, animated);
        updateColors();

        boolean isFinal = !slidingSpeed;
        slidingSpeed = false;

        for (int a = 0; a < speedItems.length; a++) {
            if (isFinal && Math.abs(currentPlaybackSpeed - speeds[a]) < 0.05f) {
                speedItems[a].setColors(getThemedColor(Theme.key_featuredStickers_addButtonPressed), getThemedColor(Theme.key_featuredStickers_addButtonPressed));
            } else {
                speedItems[a].setColors(getThemedColor(Theme.key_actionBarDefaultSubmenuItem), getThemedColor(Theme.key_actionBarDefaultSubmenuItem));
            }
        }

        speedSlider.setSpeed(currentPlaybackSpeed, animated);
    }

    public void updateColors() {
        float currentPlaybackSpeed = MediaController.getInstance().getPlaybackSpeed(isMusic);
        final int color = getThemedColor(!equals(currentPlaybackSpeed, 1.0f) ? Theme.key_featuredStickers_addButtonPressed : Theme.key_inappPlayerClose);
        if (speedIcon != null) {
            speedIcon.setColor(color);
        }
        if (playbackSpeedButton != null) {
            playbackSpeedButton.setBackground(Theme.createSelectorDrawable(color & 0x19ffffff, 1, dp(14)));
        }

        if (playButton != null) {
            playButton.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_inappPlayerPlayPause), PorterDuff.Mode.MULTIPLY));
        }
        if (closeButton != null) {
            closeButton.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_inappPlayerClose), PorterDuff.Mode.MULTIPLY));
        }
        if (subtitleTextView != null) {
            for (int i = 0; i < 2; i++) {
                TextView textView = i == 0 ? subtitleTextView.getTextView() : subtitleTextView.getNextTextView();
                if (textView == null) {
                    continue;
                }
                textView.setTextColor(getThemedColor(Theme.key_inappPlayerClose));
            }
        }
        if (titleTextView != null) {
            final Object tag = titleTextView.getTag();
            if (tag instanceof Integer) {
                final int colorId = (int) tag;
                for (int i = 0; i < 2; i++) {
                    TextView textView = i == 0 ? titleTextView.getTextView() : titleTextView.getNextTextView();
                    if (textView == null) {
                        continue;
                    }
                    textView.setTextColor(getThemedColor(colorId));

                    CharSequence text = textView.getText();
                    if (text instanceof Spanned) {
                        Spanned spannable = (Spanned) text;

                        TypefaceSpan[] spans = spannable.getSpans(0, text.length(), TypefaceSpan.class);
                        if (spans != null) {
                            for (TypefaceSpan span : spans) {
                                span.setColor(getThemedColor(Theme.key_inappPlayerPerformer));
                            }
                        }
                    }
                }
            }
        }
    }

    private void openSharingLocation(final LocationController.SharingLocationInfo info) {
        if (info == null || !(fragment.getParentActivity() instanceof LaunchActivity)) {
            return;
        }
        LaunchActivity launchActivity = ((LaunchActivity) fragment.getParentActivity());
        launchActivity.switchToAccount(info.messageObject.currentAccount, true);

        LocationActivity locationActivity = new LocationActivity(2);
        locationActivity.setMessageObject(info.messageObject);
        final long dialog_id = info.messageObject.getDialogId();
        locationActivity.setDelegate((location, live, notify, scheduleDate, payStars) -> SendMessagesHelper.getInstance(info.messageObject.currentAccount).sendMessage(SendMessagesHelper.SendMessageParams.of(location, dialog_id, null, null, null, null, notify, scheduleDate, 0)));
        launchActivity.presentFragment(locationActivity);
    }

    @Keep
    public float getTopPadding() {
        return topPadding;
    }

    @Keep
    public void setTopPadding(float value) {
        topPadding = value;
        if (fragment != null && getParent() != null && !isInsideBubble) {
            View view = applyingView != null ? applyingView : fragment.getFragmentView();
            if (view != null && getParent() != null) {
                view.setPadding(0, (int) (getVisibility() == View.VISIBLE ? topPadding : 0), 0, 0);
            }
        }
    }

    private boolean equals(float a, float b) {
        return Math.abs(a - b) < 0.05f;
    }

    private void playbackSpeedChanged(boolean byTap, float oldValue, float newValue) {
        if (equals(oldValue, newValue)) {
            return;
        }

        final String text;
        final int resId;
        if (Math.abs(newValue - 1f) < 0.05f) {
            if (oldValue < newValue) {
                return;
            }
            text = getString(R.string.AudioSpeedNormal);
            if (Math.abs(oldValue - 2f) < 0.05f) {
                resId = R.raw.speed_2to1;
            } else if (newValue < oldValue) {
                resId = R.raw.speed_slow;
            } else {
                resId = R.raw.speed_fast;
            }
        } else if (byTap && equals(newValue, 1.5f) && equals(oldValue, 1f)) {
            text = LocaleController.formatString("AudioSpeedCustom", R.string.AudioSpeedCustom, SpeedIconDrawable.formatNumber(newValue));
            resId = R.raw.speed_1to15;
        } else if (byTap && equals(newValue, 2f) && equals(oldValue, 1.5f)) {
            text = getString(R.string.AudioSpeedFast);
            resId = R.raw.speed_15to2;
        } else {
            text = LocaleController.formatString("AudioSpeedCustom", R.string.AudioSpeedCustom, SpeedIconDrawable.formatNumber(newValue));
            resId = newValue < 1 ? R.raw.speed_slow : R.raw.speed_fast;
        }
        Bulletin bulletin = BulletinFactory.of(fragment).createSimpleBulletin(resId, text);
        bulletin.show();
    }

    private void updateSilent() {
        if (currentStyle == STYLE_AUDIO_PLAYER) {
            boolean isSilent = MediaController.getInstance().isSilent;
            AndroidUtilities.updateViewShow(silentButton, isSilent);
            AndroidUtilities.updateViewShow(playbackSpeedButton, !isSilent);
        } else {
            AndroidUtilities.updateViewShow(silentButton, false, true, false);
            AndroidUtilities.updateViewShow(playbackSpeedButton, false, true, false);
        }
    }

    private void updateStyle(@Style int style) {
        updateStyle(style, false);
    }

    private void updateStyle(@Style int style, boolean force) {
        if (currentStyle == style && !force) {
            return;
        }
        checkCreateView();
        currentStyle = style;
        frameLayout.setWillNotDraw(false);

        if (avatars != null) {
            avatars.setStyle(currentStyle);
            avatars.setLayoutParams(LayoutHelper.createFrame(108, getStyleHeight(), Gravity.LEFT | Gravity.TOP));
        }
        frameLayout.setLayoutParams(LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, getStyleHeight(), Gravity.TOP | Gravity.LEFT, 0, 0, 0, 0));
        if (!isInsideBubble) {
            shadow.setLayoutParams(LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 2, Gravity.LEFT | Gravity.TOP, 0, getStyleHeight(), 0, 0));
        }

        if (topPadding > 0 && topPadding != AndroidUtilities.dp2(getStyleHeight())) {
            updatePaddings();
            setTopPadding(AndroidUtilities.dp2(getStyleHeight()));
        }
        if (style == STYLE_IMPORTING_MESSAGES) {
            selector.setBackground(Theme.getSelectorDrawable(false));
            frameLayout.setBackgroundColor(isInsideBubble ? 0 : getThemedColor(Theme.key_inappPlayerBackground));
            frameLayout.setTag(Theme.key_inappPlayerBackground);

            for (int i = 0; i < 2; i++) {
                TextView textView = i == 0 ? titleTextView.getTextView() : titleTextView.getNextTextView();
                if (textView == null) {
                    continue;
                }
                textView.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
                textView.setTextColor(getThemedColor(Theme.key_inappPlayerTitle));
                textView.setTypeface(Typeface.DEFAULT);
                textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            }
            titleTextView.setTag(Theme.key_inappPlayerTitle);
            subtitleTextView.setVisibility(GONE);
            closeButton.setVisibility(GONE);
            playButton.setVisibility(GONE);
            avatars.setVisibility(GONE);
            importingImageView.setVisibility(VISIBLE);
            importingImageView.playAnimation();
            closeButton.setContentDescription(getString(R.string.AccDescrClosePlayer));
            if (playbackSpeedButton != null) {
                playbackSpeedButton.setVisibility(GONE);
                playbackSpeedButton.setTag(null);
            }
            titleTextView.setLayoutParams(LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 36, Gravity.LEFT | Gravity.TOP, 35, 0, (isSideMenued ? 64 : 0) + 36, 0));
        } else if (style == STYLE_AUDIO_PLAYER || style == STYLE_LIVE_LOCATION) {
            selector.setBackground(Theme.getSelectorDrawable(false));
            frameLayout.setBackgroundColor(isInsideBubble ? 0 : getThemedColor(Theme.key_inappPlayerBackground));
            frameLayout.setTag(Theme.key_inappPlayerBackground);

            subtitleTextView.setVisibility(GONE);
            closeButton.setVisibility(VISIBLE);
            playButton.setVisibility(VISIBLE);
            importingImageView.setVisibility(GONE);
            importingImageView.stopAnimation();
            avatars.setVisibility(GONE);
            for (int i = 0; i < 2; i++) {
                TextView textView = i == 0 ? titleTextView.getTextView() : titleTextView.getNextTextView();
                if (textView == null) {
                    continue;
                }
                textView.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
                textView.setTextColor(getThemedColor(Theme.key_inappPlayerTitle));
                textView.setTypeface(Typeface.DEFAULT);
                textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            }
            titleTextView.setTag(Theme.key_inappPlayerTitle);
            if (style == STYLE_AUDIO_PLAYER) {
                playButton.setLayoutParams(LayoutHelper.createFrame(36, 36, Gravity.TOP | Gravity.LEFT, 3, 0, 0, 0));
                titleTextView.setLayoutParams(LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 36, Gravity.LEFT | Gravity.TOP, 37, 0, (isSideMenued ? 64 : 0) + 36, 0));
                createPlaybackSpeedButton();
                if (playbackSpeedButton != null) {
                    playbackSpeedButton.setVisibility(VISIBLE);
                    playbackSpeedButton.setTag(1);
                }
                closeButton.setContentDescription(getString(R.string.AccDescrClosePlayer));
            } else {
                playButton.setLayoutParams(LayoutHelper.createFrame(36, 36, Gravity.TOP | Gravity.LEFT, 8, 0, 0, 0));
                titleTextView.setLayoutParams(LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 36, Gravity.LEFT | Gravity.TOP, 35 + 16, 0, (isSideMenued ? 64 : 0) + 36, 0));
                closeButton.setContentDescription(getString(R.string.AccDescrStopLiveLocation));
            }
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (animatorSet != null) {
            animatorSet.cancel();
            animatorSet = null;
        }
        visible = false;
        notificationsLocker.unlock();
        topPadding = 0;
        if (isLocation) {
            NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.liveLocationsChanged);
            NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.liveLocationsCacheChanged);
        } else {
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                NotificationCenter.getInstance(a).removeObserver(this, NotificationCenter.messagePlayingDidReset);
                NotificationCenter.getInstance(a).removeObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
                NotificationCenter.getInstance(a).removeObserver(this, NotificationCenter.messagePlayingDidStart);
                NotificationCenter.getInstance(a).removeObserver(this, NotificationCenter.historyImportProgressChanged);
                NotificationCenter.getInstance(a).removeObserver(this, NotificationCenter.messagePlayingProgressDidChanged);
            }
            NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.messagePlayingSpeedChanged);
        }
        wasDraw = false;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (isLocation) {
            NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.liveLocationsChanged);
            NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.liveLocationsCacheChanged);
            checkLiveLocation(true);
        } else {
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                NotificationCenter.getInstance(a).addObserver(this, NotificationCenter.messagePlayingDidReset);
                NotificationCenter.getInstance(a).addObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
                NotificationCenter.getInstance(a).addObserver(this, NotificationCenter.messagePlayingDidStart);
                NotificationCenter.getInstance(a).addObserver(this, NotificationCenter.historyImportProgressChanged);
                NotificationCenter.getInstance(a).addObserver(this, NotificationCenter.messagePlayingProgressDidChanged);
            }
            NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.messagePlayingSpeedChanged);

            if (chatActivity != null && fragment.getSendMessagesHelper().getImportingHistory(chatActivity.getDialogId()) != null && !isPlayingVoice()) {
                checkImport(true);
            } else {
                checkPlayer(true);
                updatePlaybackButton(false);
            }
        }

        if (visible && topPadding == 0) {
            updatePaddings();
            setTopPadding(AndroidUtilities.dp2(getStyleHeight()));
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, AndroidUtilities.dp2(getStyleHeight() + (isInsideBubble ? 0 : 2)));
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.liveLocationsChanged) {
            checkLiveLocation(false);
        } else if (id == NotificationCenter.liveLocationsCacheChanged) {
            if (chatActivity != null) {
                long did = (Long) args[0];
                if (chatActivity.getDialogId() == did) {
                    checkLocationString();
                }
            }
        } else if (id == NotificationCenter.messagePlayingDidStart || id == NotificationCenter.messagePlayingPlayStateChanged || id == NotificationCenter.messagePlayingDidReset) {
            checkPlayer(false);
        } else if (id == NotificationCenter.historyImportProgressChanged) {
            checkImport(false);
        } else if (id == NotificationCenter.messagePlayingSpeedChanged) {
            updatePlaybackButton(true);
        } else if (id == NotificationCenter.messagePlayingProgressDidChanged) {
            if (currentStyle == STYLE_AUDIO_PLAYER) {
                invalidate();
            }
        }
    }

    public int getStyleHeight() {
        return 36;
    }

    public boolean isCallTypeVisible() {
        return false;
    }

    private void checkLiveLocation(boolean create) {
        View fragmentView = fragment.getFragmentView();
        if (!create && fragmentView != null) {
            if (fragmentView.getParent() == null || ((View) fragmentView.getParent()).getVisibility() != VISIBLE) {
                create = true;
            }
        }
        boolean show;
        if (fragment instanceof DialogsActivity) {
            show = LocationController.getLocationsCount() != 0;
        } else {
            show = LocationController.getInstance(fragment.getCurrentAccount()).isSharingLocation(chatActivity.getDialogId());
        }
        if (!show) {
            lastLocationSharingCount = -1;
            AndroidUtilities.cancelRunOnUIThread(checkLocationRunnable);
            if (visible) {
                visible = false;
                if (create) {
                    if (getVisibility() != GONE) {
                        setVisibility(GONE);
                    }
                    setTopPadding(0);
                } else {
                    if (animatorSet != null) {
                        animatorSet.cancel();
                        animatorSet = null;
                    }
                    animatorSet = new AnimatorSet();
                    animatorSet.playTogether(ObjectAnimator.ofFloat(this, "topPadding", 0));
                    animatorSet.setDuration(200);
                    animatorSet.addListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            if (animatorSet != null && animatorSet.equals(animation)) {
                                setVisibility(GONE);
                                animatorSet = null;
                            }
                        }
                    });
                    animatorSet.start();
                }
            }
        } else {
            checkCreateView();
            updateStyle(STYLE_LIVE_LOCATION);
            playButton.setImageDrawable(new ShareLocationDrawable(getContext(), 1));
            if (create && topPadding == 0) {
                setTopPadding(AndroidUtilities.dp2(getStyleHeight()));
            }
            if (!visible) {
                if (!create) {
                    if (animatorSet != null) {
                        animatorSet.cancel();
                        animatorSet = null;
                    }
                    animatorSet = new AnimatorSet();
                    animatorSet.playTogether(ObjectAnimator.ofFloat(this, "topPadding", AndroidUtilities.dp2(getStyleHeight())));
                    animatorSet.setDuration(200);
                    animatorSet.addListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            if (animatorSet != null && animatorSet.equals(animation)) {
                                animatorSet = null;
                            }
                        }
                    });
                    animatorSet.start();
                }
                visible = true;
                setVisibility(VISIBLE);
            }

            if (fragment instanceof DialogsActivity) {
                String liveLocation = getString(R.string.LiveLocationContext);
                String param;
                String str;
                ArrayList<LocationController.SharingLocationInfo> infos = new ArrayList<>();
                for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                    infos.addAll(LocationController.getInstance(a).sharingLocationsUI);
                }
                if (infos.size() == 1) {
                    LocationController.SharingLocationInfo info = infos.get(0);
                    long dialogId = info.messageObject.getDialogId();
                    if (DialogObject.isUserDialog(dialogId)) {
                        TLRPC.User user = MessagesController.getInstance(info.messageObject.currentAccount).getUser(dialogId);
                        param = UserObject.getFirstName(user);
                        str = getString(R.string.AttachLiveLocationIsSharing);
                    } else {
                        TLRPC.Chat chat = MessagesController.getInstance(info.messageObject.currentAccount).getChat(-dialogId);
                        if (chat != null) {
                            param = chat.title;
                        } else {
                            param = "";
                        }
                        str = getString(R.string.AttachLiveLocationIsSharingChat);
                    }
                } else {
                    param = LocaleController.formatPluralString("Chats", infos.size());
                    str = getString(R.string.AttachLiveLocationIsSharingChats);
                }
                String fullString = String.format(str, liveLocation, param);
                int start = fullString.indexOf(liveLocation);
                SpannableStringBuilder stringBuilder = new SpannableStringBuilder(fullString);
                for (int i = 0; i < 2; i++) {
                    TextView textView = i == 0 ? titleTextView.getTextView() : titleTextView.getNextTextView();
                    if (textView == null) {
                        continue;
                    }
                    textView.setEllipsize(TextUtils.TruncateAt.END);
                }

                TypefaceSpan span = new TypefaceSpan(AndroidUtilities.bold(), 0, getThemedColor(Theme.key_inappPlayerPerformer));
                stringBuilder.setSpan(span, start, start + liveLocation.length(), Spanned.SPAN_INCLUSIVE_INCLUSIVE);
                titleTextView.setText(stringBuilder, false);
            } else {
                checkLocationRunnable.run();
                checkLocationString();
            }
        }
    }

    private void checkLocationString() {
        if (chatActivity == null || titleTextView == null) {
            return;
        }
        checkCreateView();
        long dialogId = chatActivity.getDialogId();
        int currentAccount = fragment.getCurrentAccount();
        ArrayList<TLRPC.Message> messages = LocationController.getInstance(currentAccount).locationsCache.get(dialogId);
        if (!firstLocationsLoaded) {
            LocationController.getInstance(currentAccount).loadLiveLocations(dialogId);
            firstLocationsLoaded = true;
        }

        int locationSharingCount = 0;
        TLRPC.User notYouUser = null;
        if (messages != null) {
            long currentUserId = UserConfig.getInstance(currentAccount).getClientUserId();
            int date = ConnectionsManager.getInstance(currentAccount).getCurrentTime();
            for (int a = 0; a < messages.size(); a++) {
                TLRPC.Message message = messages.get(a);
                if (message.media == null) {
                    continue;
                }
                if (message.date + message.media.period > date) {
                    long fromId = MessageObject.getFromChatId(message);
                    if (notYouUser == null && fromId != currentUserId) {
                        notYouUser = MessagesController.getInstance(currentAccount).getUser(fromId);
                    }
                    locationSharingCount++;
                }
            }
        }
        if (lastLocationSharingCount == locationSharingCount) {
            return;
        }
        lastLocationSharingCount = locationSharingCount;

        String liveLocation = getString(R.string.LiveLocationContext);
        String fullString;
        if (locationSharingCount == 0) {
            fullString = liveLocation;
        } else {
            int otherSharingCount = locationSharingCount - 1;
            if (LocationController.getInstance(currentAccount).isSharingLocation(dialogId)) {
                if (otherSharingCount != 0) {
                    if (otherSharingCount == 1 && notYouUser != null) {
                        fullString = String.format("%1$s - %2$s", liveLocation, LocaleController.formatString("SharingYouAndOtherName", R.string.SharingYouAndOtherName, UserObject.getFirstName(notYouUser)));
                    } else {
                        fullString = String.format("%1$s - %2$s %3$s", liveLocation, getString(R.string.ChatYourSelfName), LocaleController.formatPluralString("AndOther", otherSharingCount));
                    }
                } else {
                    fullString = String.format("%1$s - %2$s", liveLocation, getString(R.string.ChatYourSelfName));
                }
            } else {
                if (otherSharingCount != 0) {
                    fullString = String.format("%1$s - %2$s %3$s", liveLocation, UserObject.getFirstName(notYouUser), LocaleController.formatPluralString("AndOther", otherSharingCount));
                } else {
                    fullString = String.format("%1$s - %2$s", liveLocation, UserObject.getFirstName(notYouUser));
                }
            }
        }
        if (fullString.equals(lastString)) {
            return;
        }
        lastString = fullString;
        int start = fullString.indexOf(liveLocation);
        SpannableStringBuilder stringBuilder = new SpannableStringBuilder(fullString);
        for (int i = 0; i < 2; i++) {
            TextView textView = i == 0 ? titleTextView.getTextView() : titleTextView.getNextTextView();
            if (textView == null) {
                continue;
            }
            textView.setEllipsize(TextUtils.TruncateAt.END);
        }
        if (start >= 0) {
            TypefaceSpan span = new TypefaceSpan(AndroidUtilities.bold(), 0, getThemedColor(Theme.key_inappPlayerPerformer));
            stringBuilder.setSpan(span, start, start + liveLocation.length(), Spanned.SPAN_INCLUSIVE_INCLUSIVE);
        }
        titleTextView.setText(stringBuilder, false);
    }

    private void checkPlayer(boolean create) {
        if (visible && currentStyle == STYLE_IMPORTING_MESSAGES && !isPlayingVoice()) {
            return;
        }
        MessageObject messageObject = MediaController.getInstance().getPlayingMessageObject();
        View fragmentView = fragment.getFragmentView();
        if (!create && fragmentView != null) {
            if (fragmentView.getParent() == null || ((View) fragmentView.getParent()).getVisibility() != VISIBLE) {
                create = true;
            }
        }
        boolean wasVisible = visible;
        if (messageObject == null || messageObject.getId() == 0 || messageObject.isVideo()) {
            lastMessageObject = null;
            if (visible) {
                if (playbackSpeedButton != null && playbackSpeedButton.isSubMenuShowing()) {
                    playbackSpeedButton.toggleSubMenu();
                }
                visible = false;
                if (create) {
                    if (getVisibility() != GONE) {
                        setVisibility(GONE);
                    }
                    setTopPadding(0);
                } else {
                    if (animatorSet != null) {
                        animatorSet.cancel();
                        animatorSet = null;
                    }
                    notificationsLocker.lock();
                    animatorSet = new AnimatorSet();
                    animatorSet.playTogether(ObjectAnimator.ofFloat(this, "topPadding", 0));
                    animatorSet.setDuration(200);
                    if (delegate != null) {
                        delegate.onAnimation(true, false);
                    }
                    animatorSet.addListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            notificationsLocker.unlock();
                            if (animatorSet != null && animatorSet.equals(animation)) {
                                setVisibility(GONE);
                                if (delegate != null) {
                                    delegate.onAnimation(false, false);
                                }
                                animatorSet = null;
                                if (checkCallAfterAnimation) {
                                    checkCall(false);
                                } else if (checkPlayerAfterAnimation) {
                                    checkPlayer(false);
                                } else if (checkImportAfterAnimation) {
                                    checkImport(false);
                                }
                                checkCallAfterAnimation = false;
                                checkPlayerAfterAnimation = false;
                                checkImportAfterAnimation = false;
                            }
                        }
                    });
                    animatorSet.start();
                }
            } else {
                setVisibility(View.GONE);
            }
        } else {
            checkCreateView();
            if (currentStyle != STYLE_AUDIO_PLAYER && animatorSet != null && !create) {
                checkPlayerAfterAnimation = true;
                return;
            }
            int prevStyle = currentStyle;
            updateStyle(STYLE_AUDIO_PLAYER);
            if (create && topPadding == 0) {
                updatePaddings();
                setTopPadding(AndroidUtilities.dp2(getStyleHeight()));
                if (delegate != null) {
                    delegate.onAnimation(true, true);
                    delegate.onAnimation(false, true);
                }
            }
            if (!visible) {
                if (!create) {
                    if (animatorSet != null) {
                        animatorSet.cancel();
                        animatorSet = null;
                    }
                    notificationsLocker.lock();
                    animatorSet = new AnimatorSet();
                    if (!isInsideBubble) {
                        ((LayoutParams) getLayoutParams()).topMargin = -dp(getStyleHeight());
                    }
                    if (delegate != null) {
                        delegate.onAnimation(true, true);
                    }
                    animatorSet.playTogether(ObjectAnimator.ofFloat(this, "topPadding", AndroidUtilities.dp2(getStyleHeight())));
                    animatorSet.setDuration(200);
                    animatorSet.addListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            notificationsLocker.unlock();
                            if (animatorSet != null && animatorSet.equals(animation)) {
                                if (delegate != null) {
                                    delegate.onAnimation(false, true);
                                }
                                animatorSet = null;
                                if (checkCallAfterAnimation) {
                                    checkCall(false);
                                } else if (checkPlayerAfterAnimation) {
                                    checkPlayer(false);
                                } else if (checkImportAfterAnimation) {
                                    checkImport(false);
                                }
                                checkCallAfterAnimation = false;
                                checkPlayerAfterAnimation = false;
                                checkImportAfterAnimation = false;
                            }
                        }
                    });
                    animatorSet.start();
                }
                visible = true;
                setVisibility(VISIBLE);
            }
            if (MediaController.getInstance().isMessagePaused()) {
                playPauseDrawable.setPause(false, !create);
                playButton.setContentDescription(getString(R.string.AccActionPlay));
            } else {
                playPauseDrawable.setPause(true, !create);
                playButton.setContentDescription(getString(R.string.AccActionPause));
            }
            if (lastMessageObject != messageObject || prevStyle != STYLE_AUDIO_PLAYER) {
                lastMessageObject = messageObject;
                SpannableStringBuilder stringBuilder;
                if (lastMessageObject.isVoice() || lastMessageObject.isRoundVideo()) {
                    isMusic = false;
                    if (playbackSpeedButton != null) {
                        playbackSpeedButton.setAlpha(1.0f);
                        playbackSpeedButton.setEnabled(true);
                    }
                    titleTextView.setPadding(0, 0, dp(44), 0);
                    stringBuilder = new SpannableStringBuilder(String.format("%s %s", messageObject.getMusicAuthor(), messageObject.getMusicTitle()));

                    for (int i = 0; i < 2; i++) {
                        TextView textView = i == 0 ? titleTextView.getTextView() : titleTextView.getNextTextView();
                        if (textView == null) {
                            continue;
                        }
                        textView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                    }

                    updatePlaybackButton(false);
                } else {
                    isMusic = true;
                    if (playbackSpeedButton != null) {
                        if (messageObject.getDuration() >= 10 * 60) {
                            playbackSpeedButton.setAlpha(1.0f);
                            playbackSpeedButton.setEnabled(true);
titleTextView.setPadding(0, 0, dp(44), 0);
                            updatePlaybackButton(false);
                        } else {
                            playbackSpeedButton.setAlpha(0.0f);
                            playbackSpeedButton.setEnabled(false);
                            titleTextView.setPadding(0, 0, 0, 0);
                        }
                    } else {
                        titleTextView.setPadding(0, 0, 0, 0);
                    }
                    stringBuilder = new SpannableStringBuilder(String.format("%s - %s", messageObject.getMusicAuthor(), messageObject.getMusicTitle()));
                    for (int i = 0; i < 2; i++) {
                        TextView textView = i == 0 ? titleTextView.getTextView() : titleTextView.getNextTextView();
                        if (textView == null) {
                            continue;
                        }
                        textView.setEllipsize(TextUtils.TruncateAt.END);
                    }
                }
                TypefaceSpan span = new TypefaceSpan(AndroidUtilities.bold(), 0, getThemedColor(Theme.key_inappPlayerPerformer));
                stringBuilder.setSpan(span, 0, messageObject.getMusicAuthor().length(), Spanned.SPAN_INCLUSIVE_INCLUSIVE);
                titleTextView.setText(stringBuilder, !create && wasVisible && isMusic);
            }
        }
    }

    public void checkImport(boolean create) {
        if (chatActivity == null) {
            return;
        }
        checkCreateView();
        SendMessagesHelper.ImportingHistory importingHistory = fragment.getSendMessagesHelper().getImportingHistory(chatActivity.getDialogId());
        View fragmentView = fragment.getFragmentView();
        if (!create && fragmentView != null) {
            if (fragmentView.getParent() == null || ((View) fragmentView.getParent()).getVisibility() != VISIBLE) {
                create = true;
            }
        }

        Dialog dialog = fragment.getVisibleDialog();
        if ((isPlayingVoice() || chatActivity.shouldShowImport() || dialog instanceof ImportingAlert && !((ImportingAlert) dialog).isDismissed()) && importingHistory != null) {
            importingHistory = null;
        }

        if (importingHistory == null) {
            if (visible && (create && currentStyle == STYLE_NOT_SET || currentStyle == STYLE_IMPORTING_MESSAGES)) {
                visible = false;
                if (create) {
                    if (getVisibility() != GONE) {
                        setVisibility(GONE);
                    }
                    setTopPadding(0);
                } else {
                    if (animatorSet != null) {
                        animatorSet.cancel();
                        animatorSet = null;
                    }
                    final int currentAccount = account;
                    notificationsLocker.lock();
                    animatorSet = new AnimatorSet();
                    animatorSet.playTogether(ObjectAnimator.ofFloat(this, "topPadding", 0));
                    animatorSet.setDuration(220);
                    animatorSet.setInterpolator(CubicBezierInterpolator.DEFAULT);
                    animatorSet.addListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            notificationsLocker.unlock();
                            if (animatorSet != null && animatorSet.equals(animation)) {
                                setVisibility(GONE);
                                animatorSet = null;
                                if (checkCallAfterAnimation) {
                                    checkCall(false);
                                } else if (checkPlayerAfterAnimation) {
                                    checkPlayer(false);
                                } else if (checkImportAfterAnimation) {
                                    checkImport(false);
                                }
                                checkCallAfterAnimation = false;
                                checkPlayerAfterAnimation = false;
                                checkImportAfterAnimation = false;
                            }
                        }
                    });
                    animatorSet.start();
                }
            } else if (currentStyle == STYLE_NOT_SET || currentStyle == STYLE_IMPORTING_MESSAGES) {
                visible = false;
                setVisibility(GONE);
            }
        } else {
            if (currentStyle != STYLE_IMPORTING_MESSAGES && animatorSet != null && !create) {
                checkImportAfterAnimation = true;
                return;
            }
            updateStyle(STYLE_IMPORTING_MESSAGES);
            if (create && topPadding == 0) {
                updatePaddings();
                setTopPadding(AndroidUtilities.dp2(getStyleHeight()));
                if (delegate != null) {
                    delegate.onAnimation(true, true);
                    delegate.onAnimation(false, true);
                }
            }
            if (!visible) {
                if (!create) {
                    if (animatorSet != null) {
                        animatorSet.cancel();
                        animatorSet = null;
                    }
                    notificationsLocker.lock();
                    animatorSet = new AnimatorSet();
                    if (!isInsideBubble) {
                        ((LayoutParams) getLayoutParams()).topMargin = -dp(getStyleHeight());
                    }
                    if (delegate != null) {
                        delegate.onAnimation(true, true);
                    }
                    animatorSet.playTogether(ObjectAnimator.ofFloat(this, "topPadding", AndroidUtilities.dp2(getStyleHeight())));
                    animatorSet.setDuration(200);
                    animatorSet.addListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            notificationsLocker.unlock();
                            if (animatorSet != null && animatorSet.equals(animation)) {
                                if (delegate != null) {
                                    delegate.onAnimation(false, true);
                                }
                                animatorSet = null;
                                if (checkCallAfterAnimation) {
                                    checkCall(false);
                                } else if (checkPlayerAfterAnimation) {
                                    checkPlayer(false);
                                } else if (checkImportAfterAnimation) {
                                    checkImport(false);
                                }
                                checkCallAfterAnimation = false;
                                checkPlayerAfterAnimation = false;
                                checkImportAfterAnimation = false;
                            }
                        }
                    });
                    animatorSet.start();
                }
                visible = true;
                setVisibility(VISIBLE);
            }
            if (currentProgress != importingHistory.uploadProgress) {
                currentProgress = importingHistory.uploadProgress;
                titleTextView.setText(AndroidUtilities.replaceTags(LocaleController.formatString("ImportUploading", R.string.ImportUploading, importingHistory.uploadProgress)), false);
            }
        }
    }

    private boolean isPlayingVoice() {
        MessageObject messageObject = MediaController.getInstance().getPlayingMessageObject();
        return messageObject != null && messageObject.isVoice();
    }

    private void checkLiveStory(boolean create) {
    }

    public void checkCall(boolean create) {
        if (visible) {
            visible = false;
            if (create) {
                if (getVisibility() != GONE) {
                    setVisibility(GONE);
                }
                setTopPadding(0);
            }
        }
    }

    private void updateAvatars(boolean animated) {
    }


    boolean collapseTransition;
    float extraHeight;
    float collapseProgress;
    boolean wasDraw;

    public void setCollapseTransition(boolean show, float extraHeight, float progress) {
        collapseTransition = show;
        this.extraHeight = extraHeight;
        this.collapseProgress = progress;
    }

    private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        if (frameLayout == null) {
            return;
        }
        if (drawOverlay && getVisibility() != View.VISIBLE) {
            return;
        }
        super.dispatchDraw(canvas);

        if (currentStyle == STYLE_AUDIO_PLAYER) {
            MessageObject playingMessageObject = MediaController.getInstance().getPlayingMessageObject();
            if (playingMessageObject != null) {
                final float left = -dpf2(1);
                final float right = getMeasuredWidth() + dpf2(1);
                final float p = lerp(left, right, playingMessageObject.audioProgress);
                final float bottom = getMeasuredHeight() - (isInsideBubble ? 0 : dp(2));
                final float top = bottom - dpf2(2);

                progressPaint.setColor(getThemedColor(Theme.key_telegram_color));
                canvas.drawRoundRect(left, top, p, bottom, dpf2(1), dpf2(1), progressPaint);
            }
        }
        wasDraw = true;
    }

    boolean drawOverlay;

    public void setDrawOverlay(boolean drawOverlay) {
        this.drawOverlay = drawOverlay;
    }

    @Override
    public void invalidate() {
        super.invalidate();
    }

    public boolean isCallStyle() {
        return false;
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        updatePaddings();
        setTopPadding(topPadding);
        if (visibility == View.GONE) {
            wasDraw = false;
        }
    }

    private void updatePaddings() {
        if (isInsideBubble) {
            return;
        }

        int margin = 0;
        if (getVisibility() == VISIBLE) {
            margin -= dp(getStyleHeight());
        }
        ((LayoutParams) getLayoutParams()).topMargin = margin;
    }

    public boolean isInsideBubble;

    private int getThemedColor(int key) {
        return Theme.getColor(key, resourcesProvider);
    }

    private float leftMargin;
    public void setLeftMargin(float leftMargin) {
        if (frameLayout == null) {
            this.leftMargin = leftMargin;
        } else {
            if (playButton != null) {
                playButton.setTranslationX(leftMargin);
            }
            if (importingImageView != null) {
                importingImageView.setTranslationX(leftMargin);
            }
            if (titleTextView != null) {
                titleTextView.setTranslationX(leftMargin);
            }
            if (subtitleTextView != null) {
                subtitleTextView.setTranslationX(leftMargin);
            }
            if (avatars != null) {
                avatars.setTranslationX(leftMargin);
            }
        }
    }
}
