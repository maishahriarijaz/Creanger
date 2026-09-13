package com.creanger.app.ui.Gifts;

import static android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO;
import static com.creanger.app.messenger.AndroidUtilities.dp;
import static com.creanger.app.messenger.AndroidUtilities.dpf2;
import static com.creanger.app.messenger.AndroidUtilities.lerp;
import static com.creanger.app.messenger.LocaleController.formatString;
import static com.creanger.app.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.CornerPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.GridLayoutManager;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.QueryProductDetailsParams;

import com.creanger.app.messenger.AndroidUtilities;
import com.creanger.app.messenger.BillingController;
import com.creanger.app.messenger.BirthdayController;
import com.creanger.app.messenger.BuildVars;
import com.creanger.app.messenger.DialogObject;
import com.creanger.app.messenger.Emoji;
import com.creanger.app.messenger.ImageReceiver;
import com.creanger.app.messenger.LiteMode;
import com.creanger.app.messenger.LocaleController;
import com.creanger.app.messenger.MessagesController;
import com.creanger.app.messenger.NotificationCenter;
import com.creanger.app.messenger.R;
import com.creanger.app.messenger.UserConfig;
import com.creanger.app.messenger.UserObject;
import com.creanger.app.messenger.Utilities;
import com.creanger.app.messenger.utils.Choreographer60FpsContent;
import com.creanger.app.messenger.utils.DrawableUtils;
import com.creanger.app.tgnet.TLObject;
import com.creanger.app.tgnet.TLRPC;
import com.creanger.app.tgnet.tl.TL_stars;
import com.creanger.app.ui.AccountFrozenAlert;
import com.creanger.app.ui.ActionBar.BaseFragment;
import com.creanger.app.ui.ActionBar.INavigationLayout;
import com.creanger.app.ui.ActionBar.Theme;
import com.creanger.app.ui.ChatActivity;
import com.creanger.app.ui.Components.AnimatedEmojiDrawable;
import com.creanger.app.ui.Components.AnimatedFloat;
import com.creanger.app.ui.Components.AvatarDrawable;
import com.creanger.app.ui.Components.BackupImageView;
import com.creanger.app.ui.Components.BatchParticlesDrawHelper;
import com.creanger.app.ui.Components.BottomSheetWithRecyclerListView;
import com.creanger.app.ui.Components.BulletinFactory;
import com.creanger.app.ui.Components.ColoredImageSpan;
import com.creanger.app.ui.Components.CompatDrawable;
import com.creanger.app.ui.Components.CubicBezierInterpolator;
import com.creanger.app.ui.Components.ExtendedGridLayoutManager;
import com.creanger.app.ui.Components.FlickerLoadingView;
import com.creanger.app.ui.Components.LayoutHelper;
import com.creanger.app.ui.Components.LinkSpanDrawable;
import com.creanger.app.ui.Components.Premium.GiftPremiumBottomSheet;
import com.creanger.app.ui.Components.Premium.StarParticlesView;
import com.creanger.app.ui.Components.Premium.boosts.BoostRepository;
import com.creanger.app.ui.Components.RecyclerListView;
import com.creanger.app.ui.Components.ScaleStateListAnimator;
import com.creanger.app.ui.Components.Text;
import com.creanger.app.ui.Components.TypefaceSpan;
import com.creanger.app.ui.Components.UItem;
import com.creanger.app.ui.Components.UniversalAdapter;
import com.creanger.app.ui.Components.UniversalRecyclerView;
import com.creanger.app.ui.Components.blur3.utils.NinePatchBuilder;
import com.creanger.app.ui.LaunchActivity;
import com.creanger.app.ui.PremiumPreviewFragment;
import com.creanger.app.ui.ProfileActivity;
import com.creanger.app.ui.Stars.StarsIntroActivity;
import com.creanger.app.ui.Components.Particles;
import com.creanger.app.ui.Stories.recorder.HintView2;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class GiftSheet extends BottomSheetWithRecyclerListView implements NotificationCenter.NotificationCenterDelegate {

    private final int currentAccount;
    private UniversalAdapter adapter;
    private List<TLRPC.TL_premiumGiftCodeOption> options;
    private final Runnable closeParentSheet;
    private TLRPC.DisallowedGiftsSettings userSettings;

    private final long dialogId;
    private final boolean self;
    private final String name;

    private final FrameLayout topView;
    private final FrameLayout premiumHeaderView;
    private final ExtendedGridLayoutManager layoutManager;
    private final DefaultItemAnimator itemAnimator;

    private final ArrayList<GiftPremiumBottomSheet.GiftTier> premiumTiers = new ArrayList<>();

    private boolean birthday;

    public GiftSheet(Context context, int currentAccount, long userId, Runnable closeParentSheet) {
        this(context, currentAccount, userId, null, closeParentSheet);
    }

    public GiftSheet(Context context, int currentAccount, long dialogId, List<TLRPC.TL_premiumGiftCodeOption> options, Runnable closeParentSheet) {
        super(context, null, false, false, false, null);

        this.currentAccount = currentAccount;
        this.dialogId = dialogId;
        this.self = UserConfig.getInstance(currentAccount).getClientUserId() == dialogId;
        this.options = options;
        this.closeParentSheet = closeParentSheet;
        setBackgroundColor(Theme.getColor(Theme.key_dialogGiftsBackground));
        fixNavigationBar(Theme.getColor(Theme.key_dialogGiftsBackground));

        final BackupImageView avatarImageView = new BackupImageView(context);
        avatarImageView.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        final AvatarDrawable avatarDrawable = new AvatarDrawable();

        if (dialogId > 0) {
            final TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(dialogId);
            this.name = UserObject.getForcedFirstName(user);
            avatarDrawable.setInfo(user);
            avatarImageView.setForUserOrChat(user, avatarDrawable);

            final TLRPC.UserFull userFull = MessagesController.getInstance(currentAccount).getUserFull(dialogId);
            userSettings = dialogId != UserConfig.getInstance(currentAccount).getClientUserId() && userFull != null ? userFull.disallowed_stargifts : null;
            if (userFull == null) {
                MessagesController.getInstance(currentAccount).loadFullUser(user, 0, true);
            }
        } else {
            final TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-dialogId);
            this.name = chat == null ? "" : chat.title;
            avatarDrawable.setInfo(chat);
            avatarImageView.setForUserOrChat(chat, avatarDrawable);
        }
        topPadding = 0.10f;

        // Gift Premium header
        premiumHeaderView = new FrameLayout(context);

        topView = new FrameLayout(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                super.onMeasure(
                    MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(dp(120), MeasureSpec.EXACTLY)
                );
            }
        };
        topView.setClipChildren(false);
        topView.setClipToPadding(false);

        final StarParticlesView particlesView = StarsIntroActivity.makeParticlesView(context, 70, 0);
        topView.addView(particlesView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        avatarImageView.setRoundRadius(dp(42));
        topView.addView(avatarImageView, LayoutHelper.createFrame(84, 84, Gravity.CENTER, 0, 15, 0, 17));
        ScaleStateListAnimator.apply(avatarImageView);
        avatarImageView.setOnClickListener(v -> {
            BaseFragment lastFragment = LaunchActivity.getSafeLastFragment();
            if (lastFragment == null) return;
            dismiss();
            lastFragment.presentFragment(ProfileActivity.of(dialogId));
        });

        final LinearLayout bottomView = new LinearLayout(context);
        bottomView.setOrientation(LinearLayout.VERTICAL);

        premiumHeaderView.addView(bottomView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.FILL_HORIZONTAL | Gravity.TOP));

        final TextView titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        titleView.setGravity(Gravity.CENTER);
        bottomView.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 4, 0, 4, 0));
        titleView.setMaxWidth(HintView2.cutInFancyHalf(titleView.getText(), titleView.getPaint()));

        final LinkSpanDrawable.LinksTextView subtitleView = new LinkSpanDrawable.LinksTextView(context, resourcesProvider);
        subtitleView.setLinkTextColor(Theme.getColor(Theme.key_chat_messageLinkIn, resourcesProvider));
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        subtitleView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        subtitleView.setGravity(Gravity.CENTER);
        subtitleView.setLineSpacing(dp(2.33f), 1.0f);
        bottomView.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 4, 4, 4, 12));

        titleView.setText(getString(R.string.Gift2Premium));
        subtitleView.setText(TextUtils.concat(
            AndroidUtilities.replaceTags(formatString(R.string.Gift2PremiumInfo, name)),
            " ",
            AndroidUtilities.replaceArrows(AndroidUtilities.makeClickable(getString(R.string.Gift2PremiumInfoLink), () -> {
                BaseFragment lastFragment = LaunchActivity.getLastFragment();
                if (lastFragment == null) {
                    return;
                }
                BaseFragment.BottomSheetParams params = new BaseFragment.BottomSheetParams();
                params.transitionFromLeft = true;
                params.allowNestedScroll = false;
                lastFragment.showAsSheet(new PremiumPreviewFragment("gifts"), params);
            }), true)
        ));
subtitleView.setMaxWidth(HintView2.cutInFancyHalf(subtitleView.getText(), subtitleView.getPaint()));

        layoutManager = new ExtendedGridLayoutManager(context, 3);
        layoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                if (adapter == null || position == 0)
                    return layoutManager.getSpanCount();
                final UItem item = adapter.getItem(position - 1);
                if (item == null || item.spanCount == UItem.MAX_SPAN_COUNT)
                    return layoutManager.getSpanCount();
                return item.spanCount;
            }
        });
        recyclerListView.setPadding(dp(16), 0, dp(16), 0);
        recyclerListView.setClipToPadding(false);
        recyclerListView.setClipChildren(false);
        recyclerListView.setLayoutManager(layoutManager);
        recyclerListView.setSelectorType(9);
        recyclerListView.setSelectorDrawableColor(0);
        itemAnimator = new DefaultItemAnimator() {
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
        recyclerListView.setOnItemClickListener((view, position) -> {
            final UItem item = adapter.getItem(position - 1);
            if (item == null) return;

            if (item.instanceOf(GiftCell.Factory.class)) {
                if (item.object instanceof GiftPremiumBottomSheet.GiftTier) {
                    final GiftPremiumBottomSheet.GiftTier premiumTier = (GiftPremiumBottomSheet.GiftTier) item.object;
                    new SendGiftSheet(context, currentAccount, premiumTier, this.dialogId, () -> {
                        if (closeParentSheet != null) {
                            closeParentSheet.run();
                        }
                        dismiss();
                    }).show();
                    return;
                }
            }
        });

        updatePremiumTiers();
        adapter.update(false);
        updateTitle();

        if (BirthdayController.getInstance(currentAccount).isToday(dialogId)) {
            setBirthday();
        }

        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.billingProductDetailsUpdated);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.userInfoDidLoad);

        actionBar.setTitle(getTitle());
        NotificationCenter.listenEmojiLoading(actionBar.getTitleTextView());
    }

    @Override
    public void show() {
        if (MessagesController.getInstance(currentAccount).isFrozen()) {
            AccountFrozenAlert.show(currentAccount);
            return;
        }
        if (userSettings != null && userSettings.disallow_premium_gifts && userSettings.disallow_unique_stargifts && userSettings.disallow_limited_stargifts && userSettings.disallow_unlimited_stargifts) {
            BaseFragment lastFragment = LaunchActivity.getSafeLastFragment();
            if (lastFragment != null) {
                BulletinFactory.of(lastFragment).createSimpleBulletin(R.raw.error, AndroidUtilities.replaceTags(LocaleController.formatString(R.string.UserDisallowedGifts, DialogObject.getShortName(dialogId)))).show();
            }
            return;
        }
        super.show();
    }

    public GiftSheet setBirthday() {
        return setBirthday(true);
    }

    public GiftSheet setBirthday(boolean b) {
        this.birthday = b;
        adapter.update(false);
        return this;
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

        BaseFragment lastFragment = LaunchActivity.getSafeLastFragment();
        if (lastFragment != null && lastFragment.getParentActivity() instanceof LaunchActivity) {
            List<BaseFragment> fragments = new ArrayList<>(((LaunchActivity) lastFragment.getParentActivity()).getActionBarLayout().getFragmentStack());

            INavigationLayout layout = lastFragment.getParentLayout();
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
                AndroidUtilities.runOnUIThread(() -> {
                    Bundle args = new Bundle();
                    args.putLong("user_id", dialogId);
                    layout.presentFragment(new ChatActivity(args), true);
                }, 200);
            }
        }

        dismiss();
        if (closeParentSheet != null) {
            closeParentSheet.run();
        }
    }

    @Override
    public void dismiss() {
        super.dismiss();
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.billingProductDetailsUpdated);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.userInfoDidLoad);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.billingProductDetailsUpdated) {
            updatePremiumTiers();
        } else if (id == NotificationCenter.userInfoDidLoad) {
            if (!isShown()) return;
            if ((long) args[0] == dialogId) {
                if (dialogId > 0) {
                    final TLRPC.UserFull userFull = MessagesController.getInstance(currentAccount).getUserFull(dialogId);
                    userSettings = dialogId != UserConfig.getInstance(currentAccount).getClientUserId() && userFull != null ? userFull.disallowed_stargifts : null;
                    if (userSettings != null && userSettings.disallow_premium_gifts && userSettings.disallow_unique_stargifts && userSettings.disallow_limited_stargifts && userSettings.disallow_unlimited_stargifts) {
                        dismiss();
                        BaseFragment lastFragment = LaunchActivity.getSafeLastFragment();
                        if (lastFragment != null) {
                            BulletinFactory.of(lastFragment).createSimpleBulletin(R.raw.error, AndroidUtilities.replaceTags(LocaleController.formatString(R.string.UserDisallowedGifts, DialogObject.getShortName(dialogId)))).show();
                        }
                        return;
                    }
                    if (adapter != null) {
                        adapter.update(true);
                    }
                }
            }
            if (premiumTiers == null || premiumTiers.isEmpty()) {
                updatePremiumTiers();
                if (adapter != null) {
                    adapter.update(true);
                }
            }
        }
    }

    private void updatePremiumTiers() {
        premiumTiers.clear();
        if (premiumTiers.isEmpty() && options != null && !options.isEmpty()) {
            List<QueryProductDetailsParams.Product> products = new ArrayList<>();
            long pricePerMonthMax = 0;
            for (int i = options.size() - 1; i >= 0; i--) {
                final TLRPC.TL_premiumGiftCodeOption option = options.get(i);
                if ("XTR".equalsIgnoreCase(option.currency)) continue;
                Object starsOption = null;
                for (TLRPC.TL_premiumGiftCodeOption o : options) {
                    if (o != option && "XTR".equalsIgnoreCase(o.currency) && o.months == option.months) {
                        starsOption = o;
                        break;
                    }
                }
                final GiftPremiumBottomSheet.GiftTier giftTier = new GiftPremiumBottomSheet.GiftTier(option, starsOption);
                premiumTiers.add(giftTier);
                if (BuildVars.useInvoiceBilling()) {
                    if (giftTier.getPricePerMonth() > pricePerMonthMax) {
                        pricePerMonthMax = giftTier.getPricePerMonth();
                    }
                } else if (giftTier.getStoreProduct() != null && BillingController.getInstance().isReady()) {
                    products.add(QueryProductDetailsParams.Product.newBuilder()
                            .setProductType(BillingClient.ProductType.INAPP)
                            .setProductId(giftTier.getStoreProduct())
                            .build());
                }
            }
            if (BuildVars.useInvoiceBilling()) {
                for (GiftPremiumBottomSheet.GiftTier tier : premiumTiers) {
                    tier.setPricePerMonthRegular(pricePerMonthMax);
                }
            } else if (!products.isEmpty()) {
                long startMs = System.currentTimeMillis();
                BillingController.getInstance().queryProductDetails(products, (billingResult, list) -> {
                    long pricePerMonthMaxStore = 0;

                    for (ProductDetails details : list) {
                        for (GiftPremiumBottomSheet.GiftTier giftTier : premiumTiers) {
                            if (giftTier.getStoreProduct() != null && giftTier.getStoreProduct().equals(details.getProductId())) {
                                giftTier.setGooglePlayProductDetails(details);

                                if (giftTier.getPricePerMonth() > pricePerMonthMaxStore) {
                                    pricePerMonthMaxStore = giftTier.getPricePerMonth();
                                }
                                break;
                            }
                        }
                    }

                    for (GiftPremiumBottomSheet.GiftTier giftTier : premiumTiers) {
                        giftTier.setPricePerMonthRegular(pricePerMonthMaxStore);
                    }
                    AndroidUtilities.runOnUIThread(() -> {
                        if (adapter != null) {
                            adapter.update(false);
                        }
                    });
                });
            }
        }
        if (premiumTiers.isEmpty()) {
            BoostRepository.loadGiftOptions(currentAccount, null, paymentOptions -> {
                if (getContext() == null || !isShown()) return;
                options = BoostRepository.filterGiftOptions(paymentOptions, 1);
                options = BoostRepository.filterGiftOptionsByBilling(options);
                if (!options.isEmpty()) {
                    updatePremiumTiers();
                    if (adapter != null) {
                        adapter.update(true);
                    }
                }
            });
        }
    }

    @Override
    protected CharSequence getTitle() {
        if (self) {
            return getString(R.string.Gift2TitleSelf1);
        }
        return Emoji.replaceEmoji(formatString(R.string.Gift2User, name), null, false);
    }

    @Override
    protected RecyclerListView.SelectionAdapter createAdapter(RecyclerListView listView) {
        adapter = new UniversalAdapter(recyclerListView, getContext(), currentAccount, 0, true, this::fillItems, resourcesProvider);
        adapter.setApplyBackground(false);
        return adapter;
    }

    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (!self && dialogId >= 0 && !(userSettings != null && userSettings.disallow_premium_gifts)) {
            items.add(UItem.asCustom(topView));
            items.add(UItem.asCustom(premiumHeaderView));
            if (premiumTiers != null && !premiumTiers.isEmpty()) {
                for (GiftPremiumBottomSheet.GiftTier tier : premiumTiers) {
                    items.add(GiftCell.Factory.asPremiumGift(tier));
                }
            } else {
                items.add(UItem.asFlicker(1, FlickerLoadingView.STAR_GIFT).setSpanCount(1));
                items.add(UItem.asFlicker(2, FlickerLoadingView.STAR_GIFT).setSpanCount(1));
                items.add(UItem.asFlicker(3, FlickerLoadingView.STAR_GIFT).setSpanCount(1));
            }
        }
    }

    public static class GiftCell extends FrameLayout {

        private final int currentAccount;
        private final Theme.ResourcesProvider resourcesProvider;

        public final FrameLayout card;
        public final CardBackground cardBackground;
        private final Ribbon ribbon;
        public final BackupImageView imageView;
        public FrameLayout.LayoutParams imageViewLayoutParams;

        private final TextView titleView;
        private final TextView subtitleView;
        private final FrameLayout priceLayout;
        private final StarsBackgroundView priceBackground;
        private final TextView priceView;
        private final TextView starsPriceView;

        private GiftPremiumBottomSheet.GiftTier premiumTier;
        private GiftPremiumBottomSheet.GiftTier lastTier;
        private Runnable cancel;

        public GiftCell(Context context, int currentAccount, Theme.ResourcesProvider resourcesProvider) {
            super(context);

            this.currentAccount = currentAccount;
            this.resourcesProvider = resourcesProvider;

            ScaleStateListAnimator.apply(this, .04f, 1.5f);

            imageView = new BackupImageView(context);
            imageView.getImageReceiver().setAutoRepeat(0);

            card = new FrameLayout(context);
            card.setBackground(cardBackground = new CardBackground(card, resourcesProvider, true));
            addView(card, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL));

            ribbon = new Ribbon(context);
            addView(ribbon, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT | Gravity.TOP, 0, 2, 1, 0));

            card.addView(imageView, imageViewLayoutParams = LayoutHelper.createFrame(80, 80, Gravity.CENTER, 0, 12, 0, 12));

            titleView = new TextView(context);
            titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
            titleView.setGravity(Gravity.CENTER);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            titleView.setTypeface(AndroidUtilities.bold());
            card.addView(titleView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 0, 93 - 4, 0, 0));

            subtitleView = new TextView(context);
            subtitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
            subtitleView.setGravity(Gravity.CENTER);
            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            card.addView(subtitleView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 0, 111 - 4, 0, 0));

            priceLayout = new FrameLayout(context) {
                @Override
                protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                    super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                    priceBackground.measure(
                        MeasureSpec.makeMeasureSpec(getMeasuredWidth(), MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(getMeasuredHeight(), MeasureSpec.EXACTLY)
                    );
                }
            };
            priceView = new TextView(context);
            priceView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            priceView.setTypeface(AndroidUtilities.bold());
            priceView.setPadding(dp(10), 0, dp(10), 0);
            priceView.setGravity(Gravity.CENTER);

            priceView.setTextColor(0xFF3391D4);
            card.addView(priceLayout, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM, 0, 0, 0, 11));

            priceBackground = new StarsBackgroundView(context);
            priceBackground.setBackgroundColor(0xFF0000FF);
            priceLayout.addView(priceBackground, LayoutHelper.createFrame(0, 0));
            priceLayout.addView(priceView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 26, Gravity.CENTER));

            priceBackground.setBackground(new StarsBackground(Theme.isCurrentThemeDark() ? 0x1EEBA52D : 0x40E8AB02));

            starsPriceView = new TextView(context);
            starsPriceView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10.66f);
            starsPriceView.setGravity(Gravity.CENTER);
            starsPriceView.setTextColor(Theme.isCurrentThemeDark() ? 0xFFEBA52D : 0xFFD67722);
            starsPriceView.setVisibility(View.GONE);
            card.addView(starsPriceView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 161, 0, 8));

            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            card.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            ribbon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        public GiftPremiumBottomSheet.GiftTier getPremiumTier() {
            return premiumTier;
        }

        public boolean setPremiumGift(GiftPremiumBottomSheet.GiftTier tier) {
            final int months = tier.getMonths();
            if (lastTier != tier) {
                if (cancel != null) {
                    cancel.run();
                    cancel = null;
                }
                cancel = StarsIntroActivity.setPremiumGiftImage(imageView, imageView.getImageReceiver(), months);
            }

            cardBackground.setBackdrop(null);
            cardBackground.setPattern(null);
            cardBackground.setStrokeColors(null);
            titleView.setText(LocaleController.formatPluralString("Gift2Months", months));
            subtitleView.setText(getString(R.string.TelegramPremiumShort));
            titleView.setVisibility(View.VISIBLE);
            subtitleView.setVisibility(View.VISIBLE);
            imageView.setTranslationY(-dp(8));
            if (tier.isStarsPaymentAvailable()) {
                starsPriceView.setTextColor(Theme.isCurrentThemeDark() ? 0xFFEBA52D : 0xFFD67722);
                starsPriceView.setVisibility(View.VISIBLE);
                final SpannableStringBuilder starsPrice = new SpannableStringBuilder("" + LocaleController.formatNumber(tier.getStarsPrice(), ','));
                starsPrice.setSpan(new TypefaceSpan(AndroidUtilities.bold()), 0, starsPrice.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                final ColoredImageSpan[] span = new ColoredImageSpan[1];
                starsPriceView.setText(StarsIntroActivity.replaceStarsWithPlain(LocaleController.formatSpannable(R.string.PremiumOrStarsPrice, starsPrice), .48f, span));
                span[0].spaceScaleX = .8f;
            } else {
                starsPriceView.setVisibility(View.GONE);
            }

            imageViewLayoutParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            imageView.setLayoutParams(imageViewLayoutParams);

            priceView.setPadding(dp(10), 0, dp(10), 0);
            priceView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            priceView.setText(tier.getFormattedPrice());
            priceBackground.setBackground(Theme.createRoundRectDrawable(dp(13), 0x193391D4));
            priceView.setTextColor(0xFF3391D4);
            ((MarginLayoutParams) priceLayout.getLayoutParams()).topMargin = dp(130);
            ((FrameLayout.LayoutParams) priceLayout.getLayoutParams()).gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;

            lastTier = tier;
            this.premiumTier = tier;

            updateRibbon();

            return false;
        }

        private void updateRibbon() {
            if (premiumTier != null) {
                if (premiumTier.getDiscount() > 0) {
                    ribbon.setVisibility(View.VISIBLE);
                    ribbon.setBackdrop(null);
                    ribbon.setColors(0xFFD94FFF, 0xFF826DFF);
                    ribbon.setStrokeColor(0);
                    ribbon.setText(12, formatString(R.string.GiftPremiumOptionDiscount, premiumTier.getDiscount()), true);
                } else {
                    ribbon.setVisibility(View.GONE);
                    ribbon.setBackdrop(null);
                    ribbon.setStrokeColor(0);
                }
            } else {
                ribbon.setVisibility(View.GONE);
            }
        }

        public void setRibbonColor(int color) {
            ribbon.setColor(color);
            ribbon.invalidate();
        }

        public void invalidateCustom() {
            card.invalidate();
            card.invalidateDrawable(cardBackground);
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName("android.widget.Button");
            info.setClickable(true);
            if (isEnabled()) {
                info.addAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
            try {
                final StringBuilder sb = new StringBuilder();
                CharSequence name = null;
                if (premiumTier != null) {
                    if (titleView != null && titleView.getVisibility() == View.VISIBLE && !TextUtils.isEmpty(titleView.getText())) {
                        name = titleView.getText();
                    }
                }
                if (TextUtils.isEmpty(name)) {
                    name = getString(R.string.Gift2Gift);
                }
                sb.append(name);
                if (subtitleView != null && subtitleView.getVisibility() == View.VISIBLE && !TextUtils.isEmpty(subtitleView.getText())) {
                    sb.append(", ").append(subtitleView.getText());
                }
                if (ribbon != null && ribbon.getVisibility() == View.VISIBLE) {
                    final CharSequence ribbonText = ribbon.getText();
                    if (!TextUtils.isEmpty(ribbonText)) {
                        sb.append(", ").append(ribbonText);
                    }
                }
                if (priceLayout != null && priceLayout.getVisibility() == View.VISIBLE
                        && priceView != null && priceView.getVisibility() == View.VISIBLE
                        && !TextUtils.isEmpty(priceView.getText())) {
                    sb.append(", ").append(priceView.getText());
                }
                info.setContentDescription(sb.toString());
            } catch (Exception ignored) {}
        }

        public static class Factory extends UItem.UItemFactory<GiftCell> {
            static { setup(new Factory()); }

            @Override
            public GiftCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
                return new GiftCell(context, currentAccount, resourcesProvider);
            }

            @Override
            public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
                final GiftCell cell = (GiftCell) view;
                boolean animated = false;
                if (item.object instanceof GiftPremiumBottomSheet.GiftTier) {
                    animated = cell.setPremiumGift((GiftPremiumBottomSheet.GiftTier) item.object);
                }
                cell.card.setAlpha(item.enabled ? 1.0f : 0.65f);
                cell.ribbon.setAlpha(item.enabled ? 1.0f : 0.5f);
            }

            public static UItem asPremiumGift(GiftPremiumBottomSheet.GiftTier tier) {
                final UItem item = UItem.ofFactory(Factory.class).setSpanCount(1);
                item.object = tier;
                return item;
            }

            @Override
            public boolean equals(UItem a, UItem b) {
                if (a.accent != b.accent) return false;
                if (a.object != null || b.object != null) {
                    if (a.object instanceof GiftPremiumBottomSheet.GiftTier) {
                        return a.object == b.object;
                    }
                }
                return (
                    a.intValue == b.intValue &&
                    a.checked == b.checked &&
                    a.longValue == b.longValue &&
                    TextUtils.equals(a.text, b.text)
                );
            }
        }
    }

    public static class RibbonDrawable extends CompatDrawable {

        private Text text;
        private Path path = new Path();
        private Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float scale;
        private Particles particles;

        public static void fillRibbonPath(Path path, float s, boolean left) {
            final Utilities.CallbackReturn<Float, Float> x = v -> left ? 48.0f - v : v;
            path.rewind();
            path.moveTo(dp(s * x.run(46.83f)), dp(s * 24.5f));
            path.lineTo(dp(s * x.run(23.5f)), dp(s * 1.17f));
            path.cubicTo(dp(s * x.run(22.75f)), dp(s * 0.42f), dp(s * x.run(21.73f)), 0f, dp(s * x.run(20.68f)), 0f);
            path.cubicTo(dp(s * x.run(19.62f)), 0f, dp(s * x.run(2.73f)), dp(s * 0.05f), dp(s * x.run(1.55f)), dp(s * 0.05f));
            path.cubicTo(dp(s * x.run(0.36f)), dp(s * 0.05f), dp(s * x.run(-0.23f)), dp(s * 1.4885f), dp(s * x.run(0.6f)), dp(s * 2.32f));
            path.lineTo(dp(s * x.run(45.72f)), dp(s * 47.44f));
            path.cubicTo(dp(s * x.run(46.56f)), dp(s * 48.28f), dp(s * x.run(48f)), dp(s * 47.68f), dp(s * x.run(48f)), dp(s * 46.5f));
            path.cubicTo(dp(s * x.run(48.0f)), dp(s * 45.31f), dp(s * x.run(48f)), dp(s * 28.38f), dp(s * x.run(48f)), dp(s * 27.32f));
            path.cubicTo(dp(s * x.run(48.0f)), dp(s * 26.26f), dp(s * x.run(47.5f)), dp(s * 25.24f), dp(s * x.run(46.82f)), dp(s * 24.5f));
            path.close();
        }

        public RibbonDrawable(View view, float scale) {
            super(view);
            fillRibbonPath(path, this.scale = scale, false);

            paint.setColor(0xFFF55951);
            paint.setPathEffect(new CornerPathEffect(dp(2.33f)));
            strokePaint.setColor(0);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeJoin(Paint.Join.ROUND);
            strokePaint.setStrokeCap(Paint.Cap.ROUND);
        }

        public void setParticles(boolean p) {
            if (p == (particles != null)) return;
            if (p) {
                particles = new Particles(Particles.TYPE_RADIAL_INSIDE, 12);
                particles.setSpeed(5.0f);
            } else {
                particles = null;
            }
        }

        public void setColor(int color) {
            paint.setShader(null);
            paint.setColor(color);
        }

        public void setStrokeColor(int color) {
            strokePaint.setColor(color);
        }

        public void setColors(int color1, int color2) {
            paint.setShader(new LinearGradient(0, 0, dp(48), dp(48), new int[]{ color1, color2 }, new float[] { 0, 1 }, Shader.TileMode.CLAMP));
        }

        public void setBackdrop(TL_stars.starGiftAttributeBackdrop backdrop, boolean swap, boolean darken) {
            if (backdrop == null) {
                paint.setShader(null);
            } else {
                if (left) swap = !swap;
                paint.setShader(new LinearGradient(0, 0, dp(48), dp(48), new int[]{
                    Theme.adaptHSV(backdrop.center_color | 0xFF000000, swap ? +0.07f : +0.05f, (swap ? -0.15f : -0.1f) - (darken ? 0.125f : 0)),
                    Theme.adaptHSV(backdrop.edge_color | 0xFF000000, swap ? +0.07f : +0.05f, (swap ? -0.15f : -0.1f) - (darken ? 0.125f : 0))
                }, new float[] { swap ? 1 : 0, swap ? 0 : 1 }, Shader.TileMode.CLAMP));
            }
        }

        public void setText(int textSizeDp, CharSequence text, boolean bold) {
            this.text = new Text(text, textSizeDp, bold ? AndroidUtilities.bold() : null);
        }

        private boolean left;
        public void setLeft(boolean left) {
            fillRibbonPath(path, scale, this.left = left);
        }

        private int textColor = 0xFFFFFFFF;
        public void setTextColor(int textColor) {
            this.textColor = textColor;
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            canvas.save();
            canvas.translate(getBounds().right - dp(48), getBounds().top);
            if (strokePaint.getAlpha() > 0) {
                strokePaint.setStrokeWidth(2 * dp(1.33f));
                canvas.drawPath(path, strokePaint);
            }
            canvas.drawPath(path, paint);
            if (particles != null) {
                canvas.clipPath(path);
                particles.setBounds(0, 0, dp(48), dp(48));
                particles.process();
                particles.draw(canvas, 0xFFFFFFFF);
                invalidateSelf();
            }
            if (text != null) {
                canvas.save();
                canvas.rotate(left ? -45 : 45, getBounds().width() / 2f + dp(left ? -7 : 6), getBounds().height() / 2f - dp(left ? 5 : 6));
                final float scale = Math.min(1, dp(40) / text.getCurrentWidth());
                canvas.scale(scale, scale, getBounds().width() / 2f + dp(left ? -7 : 6), getBounds().height() / 2f - dp(left ? 5 : 6));
                text.draw(canvas, getBounds().width() / 2f + dp(left ? -7 : 6) - text.getWidth() / 2f, getBounds().height() / 2f - dp(left ? 4 : 5), textColor, 1f);
                canvas.restore();
            }
            canvas.restore();
        }
    }

    public static class Ribbon extends View {

        public final RibbonDrawable drawable = new RibbonDrawable(this, 1.0f);
        private CharSequence currentText;

        public Ribbon(Context context) {
            super(context);
            drawable.setCallback(this);
        }

        public CharSequence getText() {
            return currentText;
        }

        public void setText(CharSequence text, boolean bold) {
            currentText = text;
            drawable.setText(bold ? 10 : 11, text, bold);
        }

        public void setText(int textSizeDp, CharSequence text, boolean bold) {
            currentText = text;
            drawable.setText(textSizeDp, text, bold);
        }

        public void setColor(int color) {
            drawable.setColor(color);
        }

        public void setStrokeColor(int strokeColor) {
            drawable.setStrokeColor(strokeColor);
        }

        public void setColors(int color1, int color2) {
            drawable.setColors(color1, color2);
        }

        public void setBackdrop(TL_stars.starGiftAttributeBackdrop backdrop) {
            drawable.setBackdrop(backdrop, false, false);
            invalidate();
        }

        @Override
        protected boolean verifyDrawable(@NonNull Drawable who) {
            return drawable == who || super.verifyDrawable(who);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(dp(50), dp(50));
        }

        @Override
        protected void dispatchDraw(@NonNull Canvas canvas) {
            drawable.setBounds(0, 0, getWidth(), getHeight());
            drawable.draw(canvas);
        }
    }

    private static class StarsBackgroundView extends View {
        private StarsBackground currentBackground;

        public StarsBackgroundView(Context context) {
            super(context);
        }

        @Override
        public void setBackground(Drawable background) {
            if (currentBackground != null) {
                if (isAttachedToWindow()) {
                    currentBackground.detach();
                }
                currentBackground = null;
            }

            super.setBackground(background);
            if (background instanceof StarsBackground) {
                currentBackground = (StarsBackground) background;
                if (isAttachedToWindow()) {
                    currentBackground.attach();
                }
            }
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (currentBackground != null) {
                currentBackground.attach();
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (currentBackground != null) {
                currentBackground.detach();
            }
        }
    }

    private static class StarsBackground extends Drawable {
        private static int tickIndex;

        private final int particlesColor;
        private final int color;

        public StarsBackground(int color) {
            this(ColorUtils.setAlphaComponent(color, 0x80), color);
        }

        public StarsBackground(int particlesColor, int color) {
            this.particlesColor = particlesColor;
            this.color = color;
            backgroundPaint.setColor(color);

            if (BatchParticlesDrawHelper.isAvailable()) {
                particles = new Particles(Particles.TYPE_RADIAL, 25);
            } else {
                particles = null;
            }
        }

        public final RectF rectF = new RectF();
        public final Path path = new Path();
        public final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        public final @Nullable Particles particles;

        @Override
        public void draw(@NonNull Canvas canvas) {
            canvas.drawPath(path, backgroundPaint);
            if (particles != null && (particlesAllowed || !isAttached)) {
                canvas.save();
                canvas.clipPath(path);
                if (invalidateRunnable == null) {
                    particles.process();
                }
                particles.draw(canvas, particlesColor);
                canvas.restore();

                if (invalidateRunnable == null) {
                    invalidateSelf();
                }
            }
        }

        private void invalidateParticles() {
            if (particles != null) {
                particles.process();
                invalidateSelf();
            }
        }

        private boolean particlesAllowed;
        private void checkParticlesAllowed() {
            boolean particlesAllowed = particles != null && isAttached && LiteMode.isEnabled(LiteMode.FLAG_PARTICLES);

            if (this.particlesAllowed == particlesAllowed) {
                return;
            }
            this.particlesAllowed = particlesAllowed;

            if (particlesAllowed) {
                Choreographer60FpsContent.getInstance().addFrameCallback(invalidateRunnable = this::invalidateParticles, 15);
            } else {
                Choreographer60FpsContent.getInstance().removeFrameCallback(invalidateRunnable);
            }
            invalidateSelf();
        }


        private Runnable invalidateRunnable;
        private Utilities.Callback<Boolean> liteModeCallback;
        private boolean isAttached;

        public void attach() {
            if (!isAttached) {
                isAttached = true;
                checkParticlesAllowed();
                LiteMode.addOnPowerSaverAppliedListener(liteModeCallback = b -> checkParticlesAllowed());
            }
        }

        public void detach() {
            if (isAttached) {
                isAttached = false;
                checkParticlesAllowed();
                LiteMode.removeOnPowerSaverAppliedListener(liteModeCallback);
            }
        }


        @Override
        protected void onBoundsChange(@NonNull Rect bounds) {
            super.onBoundsChange(bounds);

            final float r = Math.min(bounds.width(), bounds.height()) / 2f;
            rectF.set(bounds);
            path.rewind();
            path.addRoundRect(rectF, r, r, Path.Direction.CW);
            if (particles != null) {
                particles.setBounds(rectF);
            }
        }

        @Override
        public void setAlpha(int alpha) {
            backgroundPaint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            backgroundPaint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSPARENT;
        }
    }

    private static class SharedBackgroundDrawables {
        private final Bitmap[] shadowNinePatchBitmap = new Bitmap[1];
        private Drawable shadowNinePatch;

        private final Bitmap[] filledNinePatchBitmap = new Bitmap[1];
        private Drawable filledNinePatch;

        private final Bitmap[] filledWithShadowNinePatchBitmap = new Bitmap[1];
        private Drawable filledWithShadowNinePatch;

        private final float[] radii = new float[8];

        public SharedBackgroundDrawables() {
            Arrays.fill(radii, dp(11));
        }

        private int lastShadowColor;
        private int lastFillingColor;
        private int lastFillingWithShadowFillingColor;
        private int lastFillingWithShadowShadowColor;

        public Drawable getOrCreateShadowNinePatch(int shadowColor) {
            if (shadowNinePatch == null || lastShadowColor != shadowColor) {
                lastShadowColor = shadowColor;
                shadowNinePatch = NinePatchBuilder.createNinePatch(shadowNinePatchBitmap, 0, radii,
                    dp(1.66f), shadowColor, 0, dp(.33f),
                    NinePatchBuilder.TRANSPARENT_COLOR);
            }
            return shadowNinePatch;
        }

        public Drawable getOrCreateFilledNinePatch(int fillingColor) {
            if (filledNinePatch == null || lastFillingColor != fillingColor) {
                lastFillingColor = fillingColor;
                filledNinePatch = NinePatchBuilder.createNinePatch(filledNinePatchBitmap, fillingColor,
                    radii, 0, 0, 0, 0, fillingColor);
            }
            return filledNinePatch;
        }

        public Drawable getOrCreateFilledWithShadowNinePatch(int fillingColor, int shadowColor) {
            if (filledWithShadowNinePatch == null || lastFillingWithShadowFillingColor != fillingColor && lastFillingWithShadowShadowColor != shadowColor) {
                lastFillingWithShadowFillingColor = fillingColor;
                lastFillingWithShadowShadowColor = shadowColor;
                filledWithShadowNinePatch = NinePatchBuilder.createNinePatch(filledWithShadowNinePatchBitmap,
                    fillingColor, radii, dp(1.66f), shadowColor, 0, dp(.33f), fillingColor);
            }
            return filledWithShadowNinePatch;
        }
    }

    public static class CardBackground extends Drawable {
        private static SharedBackgroundDrawables staticSharedBackgroundDrawables = new SharedBackgroundDrawables();

        private final View view;
        private final Theme.ResourcesProvider resourcesProvider;
        public final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        public final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Path clipPath = new Path();
        private final boolean withShadow;

        private TL_stars.starGiftAttributeBackdrop backdrop;

        private int gradientRadius;
        private RadialGradient gradient;
        private final Matrix gradientMatrix = new Matrix();
        private AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable pattern;

        private int[] strokeColors;
        private int strokeGradientWidth, strokeGradientHeight;
        private LinearGradient strokeGradient;
        private final Path strokeClipPath = new Path();
        private final Matrix strokeGradientMatrix = new Matrix();

        private boolean selected;
        private final Paint selectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private AnimatedFloat animatedSelected = new AnimatedFloat(this::invalidate, 320, CubicBezierInterpolator.EASE_OUT_QUINT);
        private float r = dp(11);

        public void setRoundRadius(float r) {
            this.r = r;
        }

        public CardBackground(View view, Theme.ResourcesProvider resourcesProvider, boolean withShadow) {
            this.view = view;
            this.resourcesProvider = resourcesProvider;
            pattern = new AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable(view, dp(28)) {
                @Override
                public void invalidate() {
                    super.invalidate();
                    if (CardBackground.this.getCallback() != null) {
                        CardBackground.this.getCallback().invalidateDrawable(CardBackground.this);
                    }
                }
            };
            view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(@NonNull View v) {
                    pattern.attach();
                }
                @Override
                public void onViewDetachedFromWindow(@NonNull View v) {
                    pattern.detach();
                }
            });
            if (view.isAttachedToWindow()) pattern.attach();
            this.withShadow = withShadow;
            paint.setColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider));
            checkShadow(withShadow);
            selectedPaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStyle(Paint.Style.STROKE);
        }

        private boolean lastNeedShadow;

        private void checkShadow(boolean needShadow) {
            if (lastNeedShadow != needShadow) {
                lastNeedShadow = needShadow;
                if (needShadow) {
                    paint.setShadowLayer(dp(1.66f), 0, dp(.33f), Theme.getColor(Theme.key_dialogCardShadow, resourcesProvider));
                } else {
                    paint.setShadowLayer(0, 0, 0, 0);
                }
            }
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            draw(canvas, 0.0f);
        }

        public static final float PADDING_HORIZONTAL_DP = 3.33f;
        public static final float PADDING_VERTICAL_DP = 4;

        public boolean withPadding = true;
        public void setPadding(boolean pad) {
            withPadding = pad;
        }

        public int selectionStyle = 0;

        public void draw(@NonNull Canvas canvas, float largerParticlesAlpha) {
            Rect bounds = getBounds();
            final float selected = animatedSelected.set(this.selected);
            rect.set(bounds);
            if (withPadding) rect.inset(dp(PADDING_HORIZONTAL_DP), dp(PADDING_VERTICAL_DP));
            if (backdrop != null) {
                final int radius = lerp(Math.min(bounds.width(), bounds.height()), Math.max(bounds.width(), bounds.height()), 0.35f) / 2;
                if (gradient == null || gradientRadius != radius) {
                    gradient = new RadialGradient(0, 0, gradientRadius = radius, new int[] { backdrop.center_color | 0xFF000000, backdrop.center_color | 0xFF000000, backdrop.edge_color | 0xFF000000 }, new float[] { 0, 0f, 1 }, Shader.TileMode.CLAMP);
                }
                gradientMatrix.reset();
                gradientMatrix.postTranslate(bounds.centerX(), Math.min(dp(50), bounds.centerY()));
                gradient.setLocalMatrix(gradientMatrix);
                paint.setShader(gradient);
            } else {
                paint.setShader(null);
            }

            final int shadowColor = Theme.getColor(Theme.key_dialogCardShadow, resourcesProvider);
            final int filledColor = Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider);
            final boolean canUseShared = r == dp(11)
                && shadowColor == Theme.getColor(Theme.key_dialogCardShadow)
                && filledColor == Theme.getColor(Theme.key_windowBackgroundWhite);

            checkShadow(withShadow && !canUseShared);
            if (canUseShared) {
                if (staticSharedBackgroundDrawables == null) {
                    staticSharedBackgroundDrawables = new SharedBackgroundDrawables();
                }

                rect.round(AndroidUtilities.rectTmp2);
                if (backdrop != null) {
                    if (withShadow) {
                        final Drawable d = staticSharedBackgroundDrawables.getOrCreateShadowNinePatch(shadowColor);
                        DrawableUtils.setBoundsIncreasePadding(d, AndroidUtilities.rectTmp2);
                        d.draw(canvas);
                    }
                    canvas.drawRoundRect(rect, r, r, paint);
                } else {
                    final Drawable d;
                    if (withShadow) {
                        d = staticSharedBackgroundDrawables.getOrCreateFilledWithShadowNinePatch(filledColor, shadowColor);
                    } else {
                        d = staticSharedBackgroundDrawables.getOrCreateFilledNinePatch(filledColor);
                    }
                    DrawableUtils.setBoundsIncreasePadding(d, AndroidUtilities.rectTmp2);
                    d.draw(canvas);
                }
            } else {
                canvas.drawRoundRect(rect, r, r, paint);
            }

            final boolean clip = strokeColors != null || backdrop != null && !pattern.isEmpty();
            if (clip) {
                canvas.save();
                clipPath.rewind();
                clipPath.addRoundRect(rect, r, r, Path.Direction.CW);
                canvas.clipPath(clipPath);
            }
            if (strokeColors != null) {
                if (strokeGradient == null) {
                    strokeGradient = new LinearGradient(0, 0, 100, 0, strokeColors, new float[] { 0, 1f }, Shader.TileMode.CLAMP);
                }
                strokeGradientMatrix.reset();
                strokeGradientMatrix.postTranslate(bounds.left, bounds.top);
                strokeGradientMatrix.postRotate((float) (Math.atan2(bounds.height(), bounds.width()) / Math.PI * 180f));
                final float length = (float) Math.sqrt(Math.pow(bounds.width(), 2) + Math.pow(bounds.height(), 2));
                strokeGradientMatrix.postScale(length / 100f, length / 100f);
                strokeGradient.setLocalMatrix(strokeGradientMatrix);
                strokePaint.setShader(strokeGradient);
                strokePaint.setStrokeWidth(dp(4.66f));
                canvas.drawRoundRect(rect, r, r, strokePaint);
            }
            if (backdrop != null) {
                int color = backdrop.pattern_color | 0xFF000000;
                pattern.setColor(color);
            }
            if (clip) {
                canvas.restore();
            }

            if (selected > 0) {
                if (selectionStyle == 0) {
                    selectedPaint.setColor(selectedColor != null ? selectedColor : Theme.getColor(selectedColorKey, resourcesProvider));
                    selectedPaint.setStrokeWidth(lerp(0, dpf2(1.667f), selected));
                    AndroidUtilities.rectTmp.set(rect);
                    final float b = lerp(-dpf2(2.33f), dpf2(3.33f), selected);
                    AndroidUtilities.rectTmp.inset(b, b);
                    final float r = lerp(this.r, dpf2(7.33f), selected);
                    canvas.drawRoundRect(AndroidUtilities.rectTmp, r, r, selectedPaint);
                } else if (selectionStyle == 1) {
                    selectedPaint.setColor(selectedColor != null ? selectedColor : Theme.getColor(selectedColorKey, resourcesProvider));
                    selectedPaint.setStrokeWidth(lerp(0, dpf2(3), selected));
                    AndroidUtilities.rectTmp.set(rect);
                    final float b = lerp(0, dpf2(3) / 2.0f, selected);
                    AndroidUtilities.rectTmp.inset(b, b);
                    final float r = lerp(this.r, dpf2(10), selected);
                    canvas.drawRoundRect(AndroidUtilities.rectTmp, r, r, selectedPaint);
                }
            }
        }

        public int selectedColorKey = Theme.key_windowBackgroundWhite;
        public Integer selectedColor;

        @Override
        public boolean getPadding(@NonNull Rect padding) {
            padding.set(
                dp(3.33f),
                dp(4),
                dp(3.33f),
                dp(4)
            );
            return true;
        }

        @Override
        public void setAlpha(int alpha) {}
        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {}
        @Override
        public int getOpacity() {
            return PixelFormat.TRANSPARENT;
        }

        public void invalidate() {
            view.invalidate();
            if (getCallback() != null) {
                getCallback().invalidateDrawable(this);
            }
        }

        public void setBackdrop(TL_stars.starGiftAttributeBackdrop backdrop) {
            if (this.backdrop != backdrop) {
                gradient = null;
            }
            this.backdrop = backdrop;
            invalidate();
        }

        public long patternDocumentId;
        public void setPattern(TL_stars.starGiftAttributePattern pattern) {
            patternDocumentId = 0;
            if (pattern == null) {
                this.pattern.set((Drawable) null, false);
            } else {
                this.pattern.set(pattern.document, false);
                if (pattern.document != null) {
                    patternDocumentId = pattern.document.id;
                }
            }
        }

        public void setStrokeColors(int[] colors) {
            if (strokeColors == colors) return;
            strokeColors = colors;
            strokeGradient = null;
            invalidate();
        }

        public void setSelected(boolean selected, boolean animated) {
            if (this.selected == selected) return;
            this.selected = selected;
            if (!animated) {
                animatedSelected.force(selected);
            }
            invalidate();
        }


        private Bitmap lastDrawnBitmap;
        private Paint lastDrawnBitmapPaint;
        private int lastDrawnColor;

        private Bitmap getStableBitmapFromPattern(AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable d) {
            if (!d.isStable()) {
                return null;
            }

            Drawable drawable = d.getDrawable();
            if (drawable instanceof AnimatedEmojiDrawable) {
                AnimatedEmojiDrawable animatedEmojiDrawable = (AnimatedEmojiDrawable) drawable;

                ImageReceiver imageReceiver = animatedEmojiDrawable.getImageReceiver();
                long documentId = animatedEmojiDrawable.getDocumentId();
                if (imageReceiver != null && documentId == patternDocumentId) {
                    Bitmap bitmap = imageReceiver.getBitmap();
                    if (bitmap != null) {
                        return bitmap;
                    }
                }
            }

            return null;
        }
    }
}
