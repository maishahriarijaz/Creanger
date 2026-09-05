package com.creanger.app.ui.Gifts;

import static com.creanger.app.messenger.AndroidUtilities.dp;
import static com.creanger.app.messenger.LocaleController.formatSpannable;
import static com.creanger.app.messenger.LocaleController.formatString;
import static com.creanger.app.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PointF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.RecyclerView;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.ProductDetails;

import com.creanger.app.messenger.AccountInstance;
import com.creanger.app.messenger.AndroidUtilities;
import com.creanger.app.messenger.AnimationNotificationsLocker;
import com.creanger.app.messenger.BillingController;
import com.creanger.app.messenger.BuildVars;
import com.creanger.app.messenger.LocaleController;
import com.creanger.app.messenger.MediaDataController;
import com.creanger.app.messenger.MessageObject;
import com.creanger.app.messenger.MessagesController;
import com.creanger.app.messenger.NotificationCenter;
import com.creanger.app.messenger.R;
import com.creanger.app.messenger.UserConfig;
import com.creanger.app.messenger.UserObject;
import com.creanger.app.messenger.browser.Browser;
import com.creanger.app.tgnet.ConnectionsManager;
import com.creanger.app.tgnet.TLObject;
import com.creanger.app.tgnet.TLRPC;
import com.creanger.app.ui.ActionBar.BaseFragment;
import com.creanger.app.ui.ActionBar.INavigationLayout;
import com.creanger.app.ui.ActionBar.Theme;
import com.creanger.app.ui.Cells.ChatActionCell;
import com.creanger.app.ui.Cells.EditEmojiTextCell;
import com.creanger.app.ui.ChatActivity;
import com.creanger.app.ui.Components.AlertsCreator;
import com.creanger.app.ui.Components.BottomSheetWithRecyclerListView;
import com.creanger.app.ui.Components.BulletinFactory;
import com.creanger.app.ui.Components.ColoredImageSpan;
import com.creanger.app.ui.Components.CubicBezierInterpolator;
import com.creanger.app.ui.Components.EditTextEmoji;
import com.creanger.app.ui.Components.EditTextSuggestionsFix;
import com.creanger.app.ui.Components.LayoutHelper;
import com.creanger.app.ui.Components.MotionBackgroundDrawable;
import com.creanger.app.ui.Components.Premium.GiftPremiumBottomSheet;
import com.creanger.app.ui.Components.Premium.boosts.BoostDialogs;
import com.creanger.app.ui.Components.Premium.boosts.BoostRepository;
import com.creanger.app.ui.Components.Premium.boosts.PremiumPreviewGiftSentBottomSheet;
import com.creanger.app.ui.Components.RecyclerListView;
import com.creanger.app.ui.Components.SizeNotifierFrameLayout;
import com.creanger.app.ui.Components.TypefaceSpan;
import com.creanger.app.ui.Components.UItem;
import com.creanger.app.ui.Components.UniversalAdapter;
import com.creanger.app.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import com.creanger.app.ui.Components.blur3.drawable.color.BlurredBackgroundColorProviderThemed;
import com.creanger.app.ui.Components.blur3.source.BlurredBackgroundSourceColor;
import com.creanger.app.ui.Components.chat.ViewPositionWatcher;
import com.creanger.app.ui.LaunchActivity;
import com.creanger.app.ui.ProfileActivity;
import com.creanger.app.ui.Stories.recorder.ButtonWithCounterView;
import com.creanger.app.ui.Stories.recorder.PreviewView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

public class SendGiftSheet extends BottomSheetWithRecyclerListView implements NotificationCenter.NotificationCenterDelegate {

    private final boolean self;
    private final int currentAccount;
    private final long dialogId;
    private final GiftPremiumBottomSheet.GiftTier premiumTier;
    private final String name;
    private final Runnable closeParentSheet;

    private final SizeNotifierFrameLayout chatView;
    private final LinearLayout chatLinearLayout;

    private final long send_paid_messages_stars;
//    private final ChatActionCell payActionCell;
    private final ChatActionCell actionCell;

    private final TLRPC.MessageAction action;
    private final MessageObject messageObject;

    private final LinearLayout buttonContainer;
    private final ButtonWithCounterView button;

    
    private EditEmojiTextCell messageEdit;

    private UniversalAdapter adapter;

    public final AnimationNotificationsLocker animationsLock = new AnimationNotificationsLocker();

    public SendGiftSheet(Context context, int currentAccount, GiftPremiumBottomSheet.GiftTier premiumTier, long dialogId, Runnable closeParentSheet) {
        super(context, null, true, false, false, false, ActionBarType.SLIDING, null);

        self = dialogId == UserConfig.getInstance(currentAccount).getClientUserId();
        setImageReceiverNumLevel(0, 4);
        fixNavigationBar();
//        setSlidingActionBar();
        headerPaddingTop = dp(4);
        headerPaddingBottom = dp(-10);

        this.currentAccount = currentAccount;
        this.dialogId = dialogId;
        this.premiumTier = premiumTier;
        this.closeParentSheet = closeParentSheet;

        topPadding = 0.2f;

        if (dialogId >= 0) {
            final TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(dialogId);
            this.name = UserObject.getForcedFirstName(user);
        } else {
            final TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-dialogId);
            this.name = chat == null ? "" : chat.title;
        }

        actionCell = new ChatActionCell(context, false, resourcesProvider);
        actionCell.setDelegate(new ChatActionCell.ChatActionCellDelegate() {});

        chatView = new SizeNotifierFrameLayout(context) {
            @Override
            protected boolean isActionBarVisible() {
                return false;
            }
            @Override
            protected boolean isStatusBarVisible() {
                return false;
            }
            @Override
            protected boolean useRootView() {
                return false;
            }
            int maxHeight = -1;
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                if (maxHeight != -1) {
                    super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                    if (getMeasuredHeight() < maxHeight) {
                        heightMeasureSpec = MeasureSpec.makeMeasureSpec(Math.max(maxHeight, getMeasuredHeight()), MeasureSpec.AT_MOST);
                    }
                }
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                if (maxHeight == -1) {
                    maxHeight = Math.max(maxHeight, getMeasuredHeight());
                }
            }
            @Override
            protected boolean drawChild(@NonNull Canvas canvas, View child, long drawingTime) {
                if (child == backgroundView) {
                    return true;
                }
                return super.drawChild(canvas, child, drawingTime);
            }

            @Override
            protected void onLayout(boolean changed, int l, int t, int r, int b) {
                super.onLayout(changed, l, t, r, b);
                chatLinearLayout.setTranslationY(((b - t) - chatLinearLayout.getMeasuredHeight()) / 2f);
                actionCell.setVisiblePart(chatLinearLayout.getY() + actionCell.getY(), getBackgroundSizeY());
            }

            @Override
            protected void onBackgroundViewInvalidate() {
                super.onBackgroundViewInvalidate();
                recyclerListView.invalidate();
            }
        };

        final Drawable drawable = PreviewView.getBackgroundDrawable(null, currentAccount, dialogId, Theme.isCurrentThemeDark());
        chatView.setBackgroundImage(drawable, false);
        Integer color = null;
        BlurredBackgroundSourceColor sourceColor = new BlurredBackgroundSourceColor();
        if (drawable instanceof ColorDrawable) {
            color = ((ColorDrawable) drawable).getColor();
        } else if (drawable instanceof MotionBackgroundDrawable) {
            if (((MotionBackgroundDrawable) drawable).getIntensity() < 0) {
                color = Color.BLACK;
            } else {
                int[] colors = ((MotionBackgroundDrawable) drawable).getColors();
                if (colors != null && colors.length > 0) {
                    color = colors[0];
                }
            }
        }
        sourceColor.setColor(color != null ? color : getThemedColor(Theme.key_dialogBackground));
        BlurredBackgroundDrawable msgDrawable = sourceColor.createDrawable();
        msgDrawable.setColorProvider(new BlurredBackgroundColorProviderThemed(resourcesProvider, Theme.key_dialogBackground));
        msgDrawable.setRadius(dp(20));
        msgDrawable.setPadding(dp(4));


        chatLinearLayout = new LinearLayout(context);
        chatLinearLayout.setOrientation(LinearLayout.VERTICAL);

        if (premiumTier.giftCodeOption != null) {
            TLRPC.TL_messageActionGiftCode action = new TLRPC.TL_messageActionGiftCode();
            action.unclaimed = true;
            action.via_giveaway = false;
            action.months = premiumTier.getMonths();
            action.flags |= 4;
            action.currency = premiumTier.getCurrency();
            action.amount = premiumTier.getPrice();
            if (premiumTier.googlePlayProductDetails != null) {
                action.amount = (long) (action.amount * Math.pow(10, BillingController.getInstance().getCurrencyExp(action.currency) - 6));
            }
            action.flags |= 16;
            action.message = new TLRPC.TL_textWithEntities();
            this.action = action;
        } else if (premiumTier != null && premiumTier.giftOption != null) {
            TLRPC.TL_messageActionGiftPremium action = new TLRPC.TL_messageActionGiftPremium();
            action.months = premiumTier.getMonths();
            action.currency = premiumTier.getCurrency();
            action.amount = premiumTier.getPrice();
            if (premiumTier.googlePlayProductDetails != null) {
                action.amount = (long) (action.amount * Math.pow(10, BillingController.getInstance().getCurrencyExp(action.currency) - 6));
            }
            action.flags |= 2;
            action.message = new TLRPC.TL_textWithEntities();
            this.action = action;
        } else {
            throw new RuntimeException("SendGiftSheet with no premium tier");
        }
        final TLRPC.TL_messageService message = new TLRPC.TL_messageService();
        message.id = 1;
        message.dialog_id = dialogId;
        message.from_id = MessagesController.getInstance(currentAccount).getPeer(UserConfig.getInstance(currentAccount).getClientUserId());
        message.peer_id = MessagesController.getInstance(currentAccount).getPeer(dialogId   );
        message.action = action;

        send_paid_messages_stars = 0;

        messageObject = new MessageObject(currentAccount, message, false, false);
        actionCell.setMessageObject(messageObject, true);
        chatLinearLayout.addView(actionCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL, 0, send_paid_messages_stars > 0 ? 0 : 8, 0, 8));

        chatView.addView(chatLinearLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL));

        messageEdit = new EditEmojiTextCell(context, (SizeNotifierFrameLayout) containerView, getString(R.string.Gift2MessageOptional), true, 200, EditTextEmoji.STYLE_GIFT, resourcesProvider) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                setPadding(dp(16), 0, dp(12), 0);
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            }

            @Override
            protected void dispatchDraw(@NonNull Canvas canvas) {
                msgDrawable.setBounds(dp(10), 0, getMeasuredWidth() - dp(10), getMeasuredHeight());
                msgDrawable.draw(canvas);
                super.dispatchDraw(canvas);
            }

            @Override
            protected void onTextChanged(CharSequence newText) {
                TLRPC.TL_textWithEntities txt;
                if (action instanceof TLRPC.TL_messageActionGiftCode) {
                    ((TLRPC.TL_messageActionGiftCode) action).flags |= 16;
                    txt = ((TLRPC.TL_messageActionGiftCode) action).message = new TLRPC.TL_textWithEntities();
                } else if (action instanceof TLRPC.TL_messageActionGiftPremium) {
                    ((TLRPC.TL_messageActionGiftPremium) action).flags |= 16;
                    txt = ((TLRPC.TL_messageActionGiftPremium) action).message = new TLRPC.TL_textWithEntities();
                } else return;
                CharSequence[] msg = new CharSequence[] { messageEdit.getText() };
                txt.entities = MediaDataController.getInstance(currentAccount).getEntities(msg, true);
                txt.text = msg[0].toString();
                messageObject.setType();
                actionCell.setMessageObject(messageObject, true);
                adapter.update(true);
                setButtonText(true);
            }

            @Override
            protected void onFocusChanged(boolean focused) {

            }
        };
        messageEdit.editTextEmoji.getEditText().addTextChangedListener(new EditTextSuggestionsFix());
        messageEdit.editTextEmoji.allowEmojisForNonPremium(true);
        messageEdit.setShowLimitWhenNear(50);
        setEditTextEmoji(messageEdit.editTextEmoji);
        messageEdit.setShowLimitOnFocus(true);

        messageEdit.setDivider(false);
        messageEdit.hideKeyboardOnEnter();
        messageEdit.setPadding(backgroundPaddingLeft, 0, backgroundPaddingLeft, 0);

        final DefaultItemAnimator itemAnimator = new DefaultItemAnimator() {
            @Override
            protected float animateByScale(View view) {
                return .3f;
            }
        };
        itemAnimator.setDelayAnimations(false);
        itemAnimator.setSupportsChangeAnimations(false);
        itemAnimator.setDurations(350);
        itemAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        itemAnimator.setDelayIncrement(40);
        recyclerListView.setItemAnimator(itemAnimator);
        adapter.update(false);

        buttonContainer = new LinearLayout(context);
        buttonContainer.setOrientation(LinearLayout.VERTICAL);
        buttonContainer.setBackgroundColor(Theme.getColor(Theme.key_dialogBackground, resourcesProvider));
        buttonContainer.setPadding(backgroundPaddingLeft, 0, backgroundPaddingLeft, 0);
        containerView.addView(buttonContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.FILL_HORIZONTAL | Gravity.BOTTOM));

        final View buttonShadow = new View(context);
        buttonShadow.setBackgroundColor(Theme.getColor(Theme.key_dialogGrayLine, resourcesProvider));
        buttonContainer.addView(buttonShadow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1f / AndroidUtilities.density, Gravity.FILL_HORIZONTAL | Gravity.TOP));

        button = new ButtonWithCounterView(context, resourcesProvider);
        button.setRound();
        setButtonText(false);
        buttonContainer.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, Gravity.FILL, 10, 10, 10, 10));
        button.setOnClickListener(v -> {
            if (button.isLoading()) return;

            button.setLoading(true);
            if (messageEdit.editTextEmoji.getEmojiPadding() > 0) {
                messageEdit.editTextEmoji.hidePopup(true);
            } else if (messageEdit.editTextEmoji.isKeyboardVisible()) {
                messageEdit.editTextEmoji.closeKeyboard();
            }
            buyPremiumTier();
        });

        layoutManager.setReverseLayout(reverseLayout = true);
        adapter.update(false);
        layoutManager.scrollToPositionWithOffset(adapter.getItemCount(), dp(200));

        recyclerListView.setPadding(backgroundPaddingLeft, 0, backgroundPaddingLeft, dp(48 + 10 + 10));
        recyclerListView.addItemDecoration(new RecyclerView.ItemDecoration() {
            final PointF p = new PointF();

            @Override
            public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
                float top = parent.getHeight();
                float bottom = 0;
                float left = 0;

                if (ViewPositionWatcher.computeCoordinatesInParent(chatView, recyclerListView, p)) {
                    left = p.x;
                    top = Math.min(top, p.y);
                    bottom = Math.max(bottom, p.y + chatView.getMeasuredHeight());
                }
                if (ViewPositionWatcher.computeCoordinatesInParent(messageEdit, recyclerListView, p)) {
                    top = Math.min(top, p.y);
                    bottom = Math.max(bottom, p.y + messageEdit.getMeasuredHeight() + dp(12));
                }

                if (top < bottom && chatView.backgroundView != null) {
                    final float s = (float) (bottom - top) / chatView.backgroundView.getHeight();
                    c.save();
                    c.clipRect(0, top, parent.getWidth(), bottom);
                    c.translate(left, top);
                    c.scale(s, s);
                    chatView.backgroundView.draw(c);
                    c.restore();
                }
                super.onDraw(c, parent, state);
            }
        });
        recyclerListView.setOnItemClickListener((view, position) -> {
            final UItem item = adapter.getItem(reverseLayout ? position : position - 1);
            if (item == null) return;
        });
        actionBar.setTitle(getTitle());
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
    }

    private void setButtonText(boolean animated) {
        if (premiumTier != null) {
            button.setText(new SpannableStringBuilder(LocaleController.formatString(R.string.Gift2SendPremium, premiumTier.getFormattedPrice())), animated);
            button.setSubText(null, animated);
        }
    }

    private TLRPC.TL_textWithEntities getMessage() {
        final long paidMessagesStarsPrice = MessagesController.getInstance(currentAccount).getSendPaidMessagesStars(dialogId);
        if (paidMessagesStarsPrice > 0) {
            return null;
        }
        if (action instanceof TLRPC.TL_messageActionGiftCode) {
            return ((TLRPC.TL_messageActionGiftCode) action).message;
        } else if (action instanceof TLRPC.TL_messageActionGiftPremium) {
            return ((TLRPC.TL_messageActionGiftPremium) action).message;
        } else {
            return null;
        }
    }

    @Override
    public void onOpenAnimationEnd() {
        super.onOpenAnimationEnd();
        recyclerListView.invalidateItemDecorations();
    }

    private void buyPremiumTier() {
        final TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(dialogId);
        if (user == null) {
            button.setLoading(false);
            return;
        }
        final Object option;
        if (premiumTier.giftCodeOption != null) {
            option = premiumTier.giftCodeOption;
        } else if (premiumTier.giftOption != null) {
            option = premiumTier.giftOption;
        } else {
            button.setLoading(false);
            return;
        }
        if (option instanceof TLRPC.TL_premiumGiftCodeOption) {
            final TLRPC.TL_premiumGiftCodeOption o = (TLRPC.TL_premiumGiftCodeOption) option;
            final BaseFragment fragment = new BaseFragment() {
                    @Override
                    public Activity getParentActivity() {
                        Activity activity = getOwnerActivity();
                        if (activity == null) activity = LaunchActivity.instance;
                        if (activity == null)
                            activity = AndroidUtilities.findActivity(SendGiftSheet.this.getContext());
                        return activity;
                    }

                    @Override
                    public Theme.ResourcesProvider getResourceProvider() {
                        return SendGiftSheet.this.resourcesProvider;
                    }
                };
                BoostRepository.payGiftCode(new ArrayList<>(Arrays.asList(user)), o, null, getMessage(), fragment, result -> {
                    if (closeParentSheet != null) {
                        closeParentSheet.run();
                    }
                    dismiss();
                    NotificationCenter.getInstance(UserConfig.selectedAccount).postNotificationName(NotificationCenter.giftsToUserSent);
                    AndroidUtilities.runOnUIThread(() -> PremiumPreviewGiftSentBottomSheet.show(new ArrayList<>(Arrays.asList(user))), 250);

                    MessagesController.getInstance(currentAccount).getMainSettings().edit()
                        .putBoolean("show_gift_for_" + dialogId, true)
                        .putBoolean(Calendar.getInstance().get(Calendar.YEAR) + "show_gift_for_" + dialogId, true)
                        .apply();
}, error -> {
                    BoostDialogs.showToastError(getContext(), error);
                });
        } else if (option instanceof TLRPC.TL_premiumGiftOption) {
            final TLRPC.TL_premiumGiftOption o = (TLRPC.TL_premiumGiftOption) option;
            if (BuildVars.useInvoiceBilling()) {
                final LaunchActivity activity = LaunchActivity.instance;
                if (activity != null) {
                    Uri uri = Uri.parse(o.bot_url);
                    if (uri.getHost().equals("t.me")) {
                        if (!uri.getPath().startsWith("/$") && !uri.getPath().startsWith("/invoice/")) {
                            activity.setNavigateToPremiumBot(true);
                        } else {
                            activity.setNavigateToPremiumGiftCallback(() -> onGiftSuccess(false));
                        }
                    }
                    Browser.openUrl(activity, premiumTier.giftOption.bot_url);
                    dismiss();
                }
            } else {
                if (BillingController.getInstance().isReady() && premiumTier.googlePlayProductDetails != null) {
                    TLRPC.TL_inputStorePaymentGiftPremium giftPremium = new TLRPC.TL_inputStorePaymentGiftPremium();
                    giftPremium.user_id = MessagesController.getInstance(currentAccount).getInputUser(user);
                    ProductDetails.OneTimePurchaseOfferDetails offerDetails = premiumTier.googlePlayProductDetails.getOneTimePurchaseOfferDetails();
                    giftPremium.currency = offerDetails.getPriceCurrencyCode();
                    giftPremium.amount = (long) ((offerDetails.getPriceAmountMicros() / Math.pow(10, 6)) * Math.pow(10, BillingController.getInstance().getCurrencyExp(giftPremium.currency)));

                    BillingController.getInstance().addResultListener(premiumTier.giftOption.store_product, billingResult -> {
                        if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                            AndroidUtilities.runOnUIThread(() -> onGiftSuccess(true));
                        }
                    });

                    TLRPC.TL_payments_canPurchaseStore req = new TLRPC.TL_payments_canPurchaseStore();
                    req.purpose = giftPremium;
                    ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                        if (response instanceof TLRPC.TL_boolTrue) {
                            BillingController.getInstance().launchBillingFlow(getBaseFragment().getParentActivity(), AccountInstance.getInstance(currentAccount), giftPremium, Collections.singletonList(BillingFlowParams.ProductDetailsParams.newBuilder()
                                    .setProductDetails(premiumTier.googlePlayProductDetails)
                                    .build()));
                        } else if (error != null) {
                            AlertsCreator.processError(currentAccount, error, getBaseFragment(), req);
                        }
                    }));
                }
            }
        }
    }

    private void onGiftSuccess(boolean fromGooglePlay) {
        TLRPC.UserFull full = MessagesController.getInstance(currentAccount).getUserFull(dialogId);
        final TLObject user = MessagesController.getInstance(currentAccount).getUserOrChat(dialogId);
        if (full != null) {
            if (user instanceof TLRPC.User) {
                ((TLRPC.User) user).premium = true;
                MessagesController.getInstance(currentAccount).putUser((TLRPC.User) user, true);
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.userInfoDidLoad, ((TLRPC.User) user).id, full);
            }
        }

        if (getBaseFragment() != null) {
            List<BaseFragment> fragments = new ArrayList<>(((LaunchActivity) getBaseFragment().getParentActivity()).getActionBarLayout().getFragmentStack());

            INavigationLayout layout = getBaseFragment().getParentLayout();
            ChatActivity lastChatActivity = null;
            for (BaseFragment fragment : fragments) {
                if (fragment instanceof ChatActivity) {
                    lastChatActivity = (ChatActivity) fragment;
                    if (lastChatActivity.getDialogId() != dialogId) {
                        fragment.removeSelfFromStack();
                    }
                } else if (fragment instanceof ProfileActivity) {
                    if (fromGooglePlay && layout.getLastFragment() == fragment) {
                        fragment.finishFragment();
                    } else {
                        fragment.removeSelfFromStack();
                    }
                }
            }
            if (lastChatActivity == null || lastChatActivity.getDialogId() != dialogId) {
                Bundle args = new Bundle();
                args.putLong("user_id", dialogId);
                layout.presentFragment(new ChatActivity(args), true);
            }
        }

        dismiss();
    }

    @Override
    protected CharSequence getTitle() {
        return getString(self ? R.string.Gift2TitleSelf2 : R.string.Gift2Title);
    }

    @Override
    protected RecyclerListView.SelectionAdapter createAdapter(RecyclerListView listView) {
        adapter = new UniversalAdapter(recyclerListView, getContext(), currentAccount, 0, true, this::fillItems, resourcesProvider);
        adapter.setApplyBackground(false);
        return adapter;
    }

    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        final long paidMessagesStarsPrice = MessagesController.getInstance(currentAccount).getSendPaidMessagesStars(dialogId);
        items.add(UItem.asCustom(-1, chatView));
        if (paidMessagesStarsPrice <= 0) {
            items.add(UItem.asCustom(-2, messageEdit));
            items.add(UItem.asSpace(dp(12)));
        }
        if (paidMessagesStarsPrice <= 0) {
            items.add(UItem.asShadow(-3, formatString(R.string.Gift2MessagePremiumInfo, name)));
        }
if (premiumTier != null && premiumTier.isStarsPaymentAvailable()) {
        }
        if (reverseLayout) Collections.reverse(items);
    }

    @Override
    public void show() {
        if (messageEdit != null) {
            messageEdit.editTextEmoji.onResume();
        }
        super.show();
    }

    boolean isDismissed = false;

    @Override
    public void dismiss() {
        if (messageEdit.editTextEmoji.getEmojiPadding() > 0) {
            messageEdit.editTextEmoji.hidePopup(true);
            return;
        } else if (messageEdit.editTextEmoji.isKeyboardVisible()) {
            messageEdit.editTextEmoji.closeKeyboard();
            return;
        }
        if (messageEdit != null) {
            messageEdit.editTextEmoji.onPause();
        }

        isDismissed = true;
        super.dismiss();
    }

    @Override
    public void onBackPressed() {
        if (messageEdit.editTextEmoji.getEmojiPadding() > 0) {
            messageEdit.editTextEmoji.hidePopup(true);
            return;
        } else if (messageEdit.editTextEmoji.isKeyboardVisible()) {
            messageEdit.editTextEmoji.closeKeyboard();
            return;
        }
        super.onBackPressed();
    }
}

