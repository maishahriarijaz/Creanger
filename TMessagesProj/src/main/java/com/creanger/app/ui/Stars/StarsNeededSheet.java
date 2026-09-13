package com.creanger.app.ui.Stars;

import static com.creanger.app.messenger.AndroidUtilities.dp;
import static com.creanger.app.messenger.LocaleController.formatString;
import static com.creanger.app.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.DefaultItemAnimator;

import com.creanger.app.messenger.AndroidUtilities;
import com.creanger.app.messenger.ApplicationLoader;
import com.creanger.app.messenger.BuildVars;
import com.creanger.app.messenger.NotificationCenter;
import com.creanger.app.messenger.R;
import com.creanger.app.messenger.browser.Browser;
import com.creanger.app.messenger.utils.tlutils.AmountUtils;
import com.creanger.app.ui.ActionBar.BaseFragment;
import com.creanger.app.ui.ActionBar.Theme;
import com.creanger.app.ui.ChatActivity;
import com.creanger.app.ui.Components.BottomSheetWithRecyclerListView;
import com.creanger.app.ui.Components.CubicBezierInterpolator;
import com.creanger.app.ui.Components.LayoutHelper;
import com.creanger.app.ui.Components.Premium.GLIcon.GLIconRenderer;
import com.creanger.app.ui.Components.Premium.GLIcon.GLIconTextureView;
import com.creanger.app.ui.Components.Premium.GLIcon.Icon3D;
import com.creanger.app.ui.Components.Premium.StarParticlesView;
import com.creanger.app.ui.Components.RecyclerListView;
import com.creanger.app.ui.Components.UItem;
import com.creanger.app.ui.Components.UniversalAdapter;
import com.creanger.app.ui.Stories.recorder.ButtonWithCounterView;
import com.creanger.app.ui.Stories.recorder.HintView2;
import com.creanger.app.ui.LaunchActivity;

import java.util.ArrayList;

/**
 * Standalone bottom-sheet used by the Stars/gifting flows to request a Stars top-up.
 */
public class StarsNeededSheet extends BottomSheetWithRecyclerListView implements NotificationCenter.NotificationCenterDelegate {
    private final AmountUtils.Amount requiredAmount;

    private final HeaderView headerView;
    private final FrameLayout footerView;
    private final ButtonWithCounterView topUpButton;
    private Runnable whenPurchased;

    public static boolean allowTopUp() {
        return ApplicationLoader.isStandaloneBuild() || BuildVars.isBetaApp() || BuildVars.isHuaweiStoreApp();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.starOptionsLoaded || id == NotificationCenter.starBalanceUpdated) {
            if (adapter != null) {
                adapter.update(true);
            }

            AmountUtils.Amount balance = StarsController.getTonInstance(currentAccount).getBalanceAmount();
            headerView.titleView.setText(formatString(R.string.TonNeededTitle,
                AmountUtils.Amount.fromNano(requiredAmount.asNano() - balance.asNano(), AmountUtils.Currency.TON).asFormatString()));
            if (actionBar != null) {
                actionBar.setTitle(getTitle());
            }
            if (balance.asNano() >= requiredAmount.asNano()) {
                if (whenPurchased != null) {
                    whenPurchased.run();
                    whenPurchased = null;
                    dismiss();
                }
            }
        }
    }

    @Override
    public void show() {
        AmountUtils.Amount balance = StarsController.getTonInstance(currentAccount).getBalanceAmount();
        if (balance.asNano() >= requiredAmount.asNano()) {
            if (whenPurchased != null) {
                whenPurchased.run();
                whenPurchased = null;
            }
            return;
        }
        BaseFragment lastFragment = LaunchActivity.getLastFragment();
        if (lastFragment instanceof ChatActivity) {
            ChatActivity chatActivity = (ChatActivity) lastFragment;
            if (chatActivity.isKeyboardVisible() && chatActivity.getChatActivityEnterView() != null) {
                chatActivity.getChatActivityEnterView().closeKeyboard();
            }
        }
        super.show();
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.starOptionsLoaded);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.starBalanceUpdated);
    }

    @Override
    public void dismissInternal() {
        super.dismissInternal();
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.starOptionsLoaded);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.starBalanceUpdated);
    }

    public StarsNeededSheet(
        Context context,
        Theme.ResourcesProvider resourcesProvider,
        AmountUtils.Amount requiredAmount,
        boolean canToUpFragment,
        Runnable whenPurchased
    ) {
        super(context, null, false, false, false, resourcesProvider);

        topPadding = .2f;

        this.whenPurchased = whenPurchased;

        fixNavigationBar();
        recyclerListView.setPadding(backgroundPaddingLeft, 0, backgroundPaddingLeft, 0);
        recyclerListView.setOnItemClickListener((view, position) -> {
            if (adapter == null) return;
            UItem item = adapter.getItem(position - 1);
            if (item == null) return;
            onItemClick(item, adapter);
        });
        DefaultItemAnimator itemAnimator = new DefaultItemAnimator();
        itemAnimator.setSupportsChangeAnimations(false);
        itemAnimator.setDelayAnimations(false);
        itemAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        itemAnimator.setDurations(350);
        recyclerListView.setItemAnimator(itemAnimator);
        setBackgroundColor(Theme.getColor(Theme.key_dialogBackground, resourcesProvider));

        this.requiredAmount = requiredAmount;
        headerView = new HeaderView(context, currentAccount, resourcesProvider);

        final AmountUtils.Amount balance = StarsController.getTonInstance(currentAccount).getBalanceAmount();
        headerView.titleView.setText(formatString(R.string.TonNeededTitle,
            AmountUtils.Amount.fromNano(requiredAmount.asNano() - balance.asNano(), AmountUtils.Currency.TON).asFormatString()));

        headerView.subtitleView.setText(AndroidUtilities.replaceTags(getString(R.string.FragmentAddFunds)));
        headerView.subtitleView.setMaxWidth(HintView2.cutInFancyHalf(headerView.subtitleView.getText(), headerView.subtitleView.getPaint()));
        actionBar.setTitle(getTitle());

        footerView = new FrameLayout(context);

        topUpButton = new ButtonWithCounterView(getContext(), getResourcesProvider());
        footerView.addView(topUpButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, Gravity.CENTER, 20, 10, 20, 20));

        if (canToUpFragment || allowTopUp()) {
            topUpButton.setText(getString(R.string.TopUpViaFragment), false);
            topUpButton.setOnClickListener(v -> {
                Browser.openUrlInSystemBrowser(getContext(), getString(R.string.TopUpViaFragmentLink));
            });
        } else {
            topUpButton.setText(getString(R.string.Close), false);
            topUpButton.setOnClickListener(v -> {
               dismiss();
            });
        }

        if (adapter != null) {
            adapter.update(false);
        }
    }

    @Override
    protected CharSequence getTitle() {
        if (headerView == null) return null;
        return headerView.titleView.getText();
    }

    private UniversalAdapter adapter;
    @Override
    protected RecyclerListView.SelectionAdapter createAdapter(RecyclerListView listView) {
        return adapter = new UniversalAdapter(recyclerListView, getContext(), currentAccount, 0, true, this::fillItems, resourcesProvider);
    }

    private boolean expanded;

    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asCustom(headerView));
        items.add(UItem.asCustom(footerView));
        // items.add(UItem.asSpace(dp(256)));
    }

    public void onItemClick(UItem item, UniversalAdapter adapter) {

    }

    @Override
    public void dismiss() {
        super.dismiss();
        if (headerView != null) {
            headerView.iconView.setPaused(true);
        }
    }

    public static StarParticlesView makeParticlesView(Context context, int particlesCount, int type) {
        return new StarParticlesView(context) {
            Paint[] paints;

            @Override
            protected void configure() {
                drawable = new Drawable(particlesCount);
                drawable.type = 106;
                drawable.roundEffect = false;
                drawable.useRotate = false;
                drawable.useBlur = true;
                drawable.checkBounds = true;
                drawable.isCircle = false;
                drawable.useScale = true;
                drawable.startFromCenter = true;
                if (type == 1) {
                    drawable.centerOffsetY = dp(32 - 8);
                }
                paints = new Paint[20];
                for (int i = 0; i < paints.length; ++i) {
                    paints[i] = new Paint(Paint.ANTI_ALIAS_FLAG);
                    paints[i].setColorFilter(new PorterDuffColorFilter(ColorUtils.blendARGB(0xFF2E81D9, 0xFF26BBFA, i / (float) (paints.length - 1)), PorterDuff.Mode.SRC_IN));
                }
                drawable.getPaint = i -> paints[i % paints.length];
                drawable.size1 = 17;
                drawable.size2 = 18;
                drawable.size3 = 19;
                drawable.colorKey = Theme.key_windowBackgroundWhiteBlackText;
                drawable.init();
            }

            @Override
            protected int getStarsRectWidth() {
                return getMeasuredWidth();
            }

            { setClipWithGradient(); }
        };
    }

    public static class HeaderView extends LinearLayout {
        private final FrameLayout topView;
        public final StarParticlesView particlesView;
        public final GLIconTextureView iconView;
        public final TextView titleView;
        public final TextView subtitleView;

        public HeaderView(Context context, int currentAccount, Theme.ResourcesProvider resourcesProvider) {
            super(context);

            setOrientation(VERTICAL);
            topView = new FrameLayout(context);
            topView.setClipChildren(false);
            topView.setClipToPadding(false);

            particlesView = makeParticlesView(context, 70, 0);
            topView.addView(particlesView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            iconView = new GLIconTextureView(context, GLIconRenderer.DIALOG_STYLE, Icon3D.TYPE_DIAMOND);
            iconView.mRenderer.colorKey1 = Theme.key_starsGradient1;
            iconView.mRenderer.colorKey2 = Theme.key_starsGradient2;
            iconView.mRenderer.updateColors();
            iconView.setStarParticlesView(particlesView);
            topView.addView(iconView, LayoutHelper.createFrame(170, 170, Gravity.CENTER, 0, 32, 0, 24));
            iconView.setPaused(false);

            addView(topView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 180));

            titleView = new TextView(context);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
            titleView.setTypeface(AndroidUtilities.bold());
            titleView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
            titleView.setGravity(Gravity.CENTER);
            addView(titleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 2, 0, 0));

            subtitleView = new TextView(context);
            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            subtitleView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
            subtitleView.setGravity(Gravity.CENTER);
            addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 9, 0, 18));
        }
    }
}
