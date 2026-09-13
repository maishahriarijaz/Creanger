/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package com.creanger.app.ui;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;

import androidx.annotation.NonNull;

import com.creanger.app.messenger.AndroidUtilities;
import com.creanger.app.messenger.ApplicationLoader;
import com.creanger.app.messenger.BuildVars;
import com.creanger.app.messenger.FileLog;
import com.creanger.app.messenger.LocaleController;
import com.creanger.app.messenger.MessagesController;
import com.creanger.app.messenger.NotificationCenter;
import com.creanger.app.messenger.R;
import com.creanger.app.messenger.SharedConfig;
import com.creanger.app.messenger.UserConfig;
import com.creanger.app.tgnet.ConnectionsManager;
import com.creanger.app.tgnet.tl.TL_account;
import com.creanger.app.ui.ActionBar.AlertDialog;
import com.creanger.app.ui.ActionBar.BaseFragment;
import com.creanger.app.ui.ActionBar.DrawerLayoutContainer;
import com.creanger.app.ui.ActionBar.INavigationLayout;
import com.creanger.app.ui.ActionBar.Theme;
import com.creanger.app.ui.Components.AlertsCreator;
import com.creanger.app.ui.Components.LayoutHelper;
import com.creanger.app.ui.Components.PasscodeView;
import com.creanger.app.ui.Components.SizeNotifierFrameLayout;

import java.util.ArrayList;

public class ExternalActionActivity extends Activity implements INavigationLayout.INavigationLayoutDelegate {

    private boolean finished;
    private static final ArrayList<BaseFragment> mainFragmentsStack = new ArrayList<>();
    private static final ArrayList<BaseFragment> layerFragmentsStack = new ArrayList<>();

    private PasscodeView passcodeView;
    protected INavigationLayout actionBarLayout;
    protected INavigationLayout layersActionBarLayout;
    protected SizeNotifierFrameLayout backgroundTablet;
    protected DrawerLayoutContainer drawerLayoutContainer;

    private Intent passcodeSaveIntent;
    private boolean passcodeSaveIntentIsNew;
    private int passcodeSaveIntentAccount;
    private int passcodeSaveIntentState;
    private boolean passcodeSaveIntentIsRestore;

    private Runnable lockRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ApplicationLoader.postInitApplication();

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setTheme(R.style.Theme_TMessages);
        getWindow().setBackgroundDrawableResource(R.drawable.transparent);
        if (!SharedConfig.passcodeHash.isEmpty() && !SharedConfig.allowScreenCapture) {
            try {
                getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
                AndroidUtilities.logFlagSecure();
            } catch (Exception e) {
                FileLog.e(e);
            }
        }

        super.onCreate(savedInstanceState);

        if (!SharedConfig.passcodeHash.isEmpty() && SharedConfig.appLocked) {
            SharedConfig.lastPauseTime = (int) (SystemClock.elapsedRealtime() / 1000);
        }

        AndroidUtilities.fillStatusBarHeight(this, false);
        Theme.createDialogsResources(this);
        Theme.createChatResources(this, false);

        actionBarLayout = INavigationLayout.newLayout(this, false);

        drawerLayoutContainer = new DrawerLayoutContainer(this);
        setContentView(drawerLayoutContainer, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        if (AndroidUtilities.isTablet()) {
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

            RelativeLayout launchLayout = new RelativeLayout(this);
            drawerLayoutContainer.addView(launchLayout);
            FrameLayout.LayoutParams layoutParams1 = (FrameLayout.LayoutParams) launchLayout.getLayoutParams();
            layoutParams1.width = LayoutHelper.MATCH_PARENT;
            layoutParams1.height = LayoutHelper.MATCH_PARENT;
            launchLayout.setLayoutParams(layoutParams1);

            backgroundTablet = new SizeNotifierFrameLayout(this) {
                @Override
                protected boolean isActionBarVisible() {
                    return false;
                }
            };
            backgroundTablet.setOccupyStatusBar(false);
            backgroundTablet.setBackgroundImage(Theme.getCachedWallpaper(), Theme.isWallpaperMotion());
            launchLayout.addView(backgroundTablet, LayoutHelper.createRelative(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            launchLayout.addView(actionBarLayout.getView(), LayoutHelper.createRelative(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            FrameLayout shadowTablet = new FrameLayout(this);
            shadowTablet.setBackgroundColor(0x7F000000);
            launchLayout.addView(shadowTablet, LayoutHelper.createRelative(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            shadowTablet.setOnTouchListener((v, event) -> {
                if (!actionBarLayout.getFragmentStack().isEmpty() && event.getAction() == MotionEvent.ACTION_UP) {
                    float x = event.getX();
                    float y = event.getY();
                    int[] location = new int[2];
                    layersActionBarLayout.getView().getLocationOnScreen(location);
                    int viewX = location[0];
                    int viewY = location[1];

                    if (layersActionBarLayout.checkTransitionAnimation() || x > viewX && x < viewX + layersActionBarLayout.getView().getWidth() && y > viewY && y < viewY + layersActionBarLayout.getView().getHeight()) {
                        return false;
                    } else {
                        if (!layersActionBarLayout.getFragmentStack().isEmpty()) {
                            for (int a = 0; a < layersActionBarLayout.getFragmentStack().size() - 1; a++) {
                                layersActionBarLayout.removeFragmentFromStack(layersActionBarLayout.getFragmentStack().get(0));
                                a--;
                            }
                            layersActionBarLayout.closeLastFragment(true);
                        }
                        return true;
                    }
                }
                return false;
            });

            shadowTablet.setOnClickListener(v -> {

            });

            layersActionBarLayout = INavigationLayout.newLayout(this, false);
            layersActionBarLayout.setRemoveActionBarExtraHeight(true);
            layersActionBarLayout.setBackgroundView(shadowTablet);
            layersActionBarLayout.setUseAlphaAnimations(true);
            layersActionBarLayout.getView().setBackgroundResource(R.drawable.boxshadow);
            launchLayout.addView(layersActionBarLayout.getView(), LayoutHelper.createRelative(530, (AndroidUtilities.isSmallTablet() ? 528 : 700)));
            layersActionBarLayout.setFragmentStack(layerFragmentsStack);
            layersActionBarLayout.setDelegate(this);
            layersActionBarLayout.setDrawerLayoutContainer(drawerLayoutContainer);
        } else {
            RelativeLayout launchLayout = new RelativeLayout(this);
            drawerLayoutContainer.addView(launchLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            backgroundTablet = new SizeNotifierFrameLayout(this) {
                @Override
                protected boolean isActionBarVisible() {
                    return false;
                }
            };
            backgroundTablet.setOccupyStatusBar(false);
            backgroundTablet.setBackgroundImage(Theme.getCachedWallpaper(), Theme.isWallpaperMotion());
            launchLayout.addView(backgroundTablet, LayoutHelper.createRelative(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            launchLayout.addView(actionBarLayout.getView(), LayoutHelper.createRelative(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        }

        // drawerLayoutContainer.setDrawerLayout(listView);

        drawerLayoutContainer.setParentActionBarLayout(actionBarLayout);
        actionBarLayout.setDrawerLayoutContainer(drawerLayoutContainer);
        actionBarLayout.setFragmentStack(mainFragmentsStack);
        actionBarLayout.setDelegate(this);

        passcodeView = new PasscodeView(this);
        drawerLayoutContainer.addView(passcodeView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.closeOtherAppActivities, this);

        actionBarLayout.removeAllFragments();
        if (layersActionBarLayout != null) {
            layersActionBarLayout.removeAllFragments();
        }

        handleIntent(getIntent(), false, savedInstanceState != null, false, UserConfig.selectedAccount, 0);
        needLayout();
    }

    private void showPasscodeActivity() {
        if (passcodeView == null) {
            return;
        }
        SharedConfig.appLocked = true;
        if (SecretMediaViewer.hasInstance() && SecretMediaViewer.getInstance().isVisible()) {
            SecretMediaViewer.getInstance().closePhoto(false, false);
        } else if (PhotoViewer.hasInstance() && PhotoViewer.getInstance().isVisible()) {
            PhotoViewer.getInstance().closePhoto(false, true);
        } else if (ArticleViewer.hasInstance() && ArticleViewer.getInstance().isVisible()) {
            ArticleViewer.getInstance().close(false, true);
        }
        passcodeView.onShow(true, false);
        SharedConfig.isWaitingForPasscodeEnter = true;
        passcodeView.setDelegate(view -> {
            SharedConfig.isWaitingForPasscodeEnter = false;
            if (passcodeSaveIntent != null) {
                handleIntent(passcodeSaveIntent, passcodeSaveIntentIsNew, passcodeSaveIntentIsRestore, true, passcodeSaveIntentAccount, passcodeSaveIntentState);
                passcodeSaveIntent = null;
            }
            actionBarLayout.showLastFragment();
            if (AndroidUtilities.isTablet()) {
                layersActionBarLayout.showLastFragment();
            }

            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.passcodeDismissed, view);
        });
    }

    public void onFinishLogin() {
        handleIntent(passcodeSaveIntent, passcodeSaveIntentIsNew, passcodeSaveIntentIsRestore, true, passcodeSaveIntentAccount, passcodeSaveIntentState);
        actionBarLayout.removeAllFragments();
        if (layersActionBarLayout != null) {
            layersActionBarLayout.removeAllFragments();
        }
        if (backgroundTablet != null) {
            backgroundTablet.setVisibility(View.VISIBLE);
        }
    }

    protected boolean checkPasscode(final Intent intent, final boolean isNew, final boolean restore, final boolean fromPassword, final int intentAccount, int state) {
        if (!fromPassword && (AndroidUtilities.needShowPasscode(true) || SharedConfig.isWaitingForPasscodeEnter)) {
            showPasscodeActivity();
            passcodeSaveIntent = intent;
            passcodeSaveIntentIsNew = isNew;
            passcodeSaveIntentIsRestore = restore;
            passcodeSaveIntentAccount = intentAccount;
            passcodeSaveIntentState = state;
            UserConfig.getInstance(intentAccount).saveConfig(false);
            return false;
        }
        return true;
    }

    protected boolean handleIntent(final Intent intent, final boolean isNew, final boolean restore, final boolean fromPassword, final int intentAccount, int state) {
        if (!checkPasscode(intent, isNew, restore, fromPassword, intentAccount, state)) {
            return false;
        }
            if (AndroidUtilities.isTablet()) {
                if (layersActionBarLayout.getFragmentStack().isEmpty()) {
                    layersActionBarLayout.addFragmentToStack(new CacheControlActivity());
                }
            } else {
                if (actionBarLayout.getFragmentStack().isEmpty()) {
                    actionBarLayout.addFragmentToStack(new CacheControlActivity());
                }
            }
            if (!AndroidUtilities.isTablet()) {
                backgroundTablet.setVisibility(View.GONE);
            }
            actionBarLayout.showLastFragment();
            if (AndroidUtilities.isTablet()) {
                layersActionBarLayout.showLastFragment();
            }
            intent.setAction(null);
        return false;
    }

    public void switchToAccount(int account) {
        if (account == UserConfig.selectedAccount) {
            return;
        }
        ConnectionsManager.getInstance(UserConfig.selectedAccount).setAppPaused(true, false);
        UserConfig.selectedAccount = account;
        UserConfig.getInstance(0).saveConfig(false);
        if (!ApplicationLoader.mainInterfacePaused) {
            ConnectionsManager.getInstance(UserConfig.selectedAccount).setAppPaused(false, false);
        }
    }

    @Override
    public boolean onPreIme() {
        return false;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent, true, false, false, UserConfig.selectedAccount, 0);
    }

    private void onFinish() {
        if (finished) {
            return;
        }
        if (lockRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(lockRunnable);
            lockRunnable = null;
        }
        finished = true;
    }

    public void presentFragment(BaseFragment fragment) {
        actionBarLayout.presentFragment(fragment);
    }

    public boolean presentFragment(final BaseFragment fragment, final boolean removeLast, boolean forceWithoutAnimation) {
        return actionBarLayout.presentFragment(fragment, removeLast, forceWithoutAnimation, true, false);
    }

    public void needLayout() {
        if (AndroidUtilities.isTablet()) {
            RelativeLayout.LayoutParams relativeLayoutParams = (RelativeLayout.LayoutParams) layersActionBarLayout.getView().getLayoutParams();
            relativeLayoutParams.leftMargin = (AndroidUtilities.displaySize.x - relativeLayoutParams.width) / 2;
            int y = AndroidUtilities.statusBarHeight;
            relativeLayoutParams.topMargin = y + (AndroidUtilities.displaySize.y - relativeLayoutParams.height - y) / 2;
            layersActionBarLayout.getView().setLayoutParams(relativeLayoutParams);


            if (!AndroidUtilities.isSmallTablet() || getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
                int leftWidth = AndroidUtilities.displaySize.x / 100 * 35;
                if (leftWidth < AndroidUtilities.dp(320)) {
                    leftWidth = AndroidUtilities.dp(320);
                }

                relativeLayoutParams = (RelativeLayout.LayoutParams) actionBarLayout.getView().getLayoutParams();
                relativeLayoutParams.width = leftWidth;
                relativeLayoutParams.height = LayoutHelper.MATCH_PARENT;
                actionBarLayout.getView().setLayoutParams(relativeLayoutParams);

                if (AndroidUtilities.isSmallTablet() && actionBarLayout.getFragmentStack().size() == 2) {
                    BaseFragment chatFragment = actionBarLayout.getFragmentStack().get(1);
                    chatFragment.onPause();
                    actionBarLayout.getFragmentStack().remove(1);
                    actionBarLayout.showLastFragment();
                }
            } else {
                relativeLayoutParams = (RelativeLayout.LayoutParams) actionBarLayout.getView().getLayoutParams();
                relativeLayoutParams.width = LayoutHelper.MATCH_PARENT;
                relativeLayoutParams.height = LayoutHelper.MATCH_PARENT;
                actionBarLayout.getView().setLayoutParams(relativeLayoutParams);
            }
        }
    }

    public void fixLayout() {
        if (!AndroidUtilities.isTablet()) {
            return;
        }
        if (actionBarLayout == null) {
            return;
        }
        actionBarLayout.getView().getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                needLayout();
                if (actionBarLayout != null) {
                    actionBarLayout.getView().getViewTreeObserver().removeOnGlobalLayoutListener(this);
                }
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        actionBarLayout.onPause();
        if (AndroidUtilities.isTablet()) {
            layersActionBarLayout.onPause();
        }
        ApplicationLoader.externalInterfacePaused = true;
        onPasscodePause();
        if (passcodeView != null) {
            passcodeView.onPause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        onFinish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        actionBarLayout.onResume();
        if (AndroidUtilities.isTablet()) {
            layersActionBarLayout.onResume();
        }
        ApplicationLoader.externalInterfacePaused = false;
        onPasscodeResume();
        if (passcodeView.getVisibility() != View.VISIBLE) {
            actionBarLayout.onResume();
            if (AndroidUtilities.isTablet()) {
                layersActionBarLayout.onResume();
            }
        } else {
            actionBarLayout.dismissDialogs();
            if (AndroidUtilities.isTablet()) {
                layersActionBarLayout.dismissDialogs();
            }
            passcodeView.onResume();
        }
    }

    private void onPasscodePause() {
        if (lockRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(lockRunnable);
            lockRunnable = null;
        }
        if (!SharedConfig.passcodeHash.isEmpty()) {
            SharedConfig.lastPauseTime = (int) (SystemClock.elapsedRealtime() / 1000);
            lockRunnable = new Runnable() {
                @Override
                public void run() {
                    if (lockRunnable == this) {
                        if (AndroidUtilities.needShowPasscode(true)) {
                            if (BuildVars.LOGS_ENABLED) {
                                FileLog.d("lock app");
                            }
                            showPasscodeActivity();
                        } else {
                            if (BuildVars.LOGS_ENABLED) {
                                FileLog.d("didn't pass lock check");
                            }
                        }
                        lockRunnable = null;
                    }
                }
            };
            if (SharedConfig.appLocked) {
                AndroidUtilities.runOnUIThread(lockRunnable, 1000);
            } else if (SharedConfig.autoLockIn != 0) {
                AndroidUtilities.runOnUIThread(lockRunnable, (long) SharedConfig.autoLockIn * 1000 + 1000);
            }
        } else {
            SharedConfig.lastPauseTime = 0;
        }
        SharedConfig.saveConfig();
    }

    private void onPasscodeResume() {
        if (lockRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(lockRunnable);
            lockRunnable = null;
        }
        if (AndroidUtilities.needShowPasscode(true)) {
            showPasscodeActivity();
        }
        if (SharedConfig.lastPauseTime != 0) {
            SharedConfig.lastPauseTime = 0;
            SharedConfig.saveConfig();
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull android.content.res.Configuration newConfig) {
        AndroidUtilities.checkDisplaySize(this, newConfig);
        AndroidUtilities.setPreferredMaxRefreshRate(getWindow());
        super.onConfigurationChanged(newConfig);
        fixLayout();
    }

    @Override
    public void onBackPressed() {
        if (passcodeView.getVisibility() == View.VISIBLE) {
            finish();
            return;
        }
        if (PhotoViewer.getInstance().isVisible()) {
            PhotoViewer.getInstance().closePhoto(true, false);
        } else if (AndroidUtilities.isTablet()) {
            if (layersActionBarLayout.getView().getVisibility() == View.VISIBLE) {
                layersActionBarLayout.onBackPressed();
            } else {
                actionBarLayout.onBackPressed();
            }
        } else {
            actionBarLayout.onBackPressed();
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        actionBarLayout.onLowMemory();
        if (AndroidUtilities.isTablet()) {
            layersActionBarLayout.onLowMemory();
        }
    }

    @Override
    public boolean needPresentFragment(BaseFragment fragment, boolean removeLast, boolean forceWithoutAnimation, INavigationLayout layout) {
        return true;
    }

    @Override
    public boolean needAddFragmentToStack(BaseFragment fragment, INavigationLayout layout) {
        return true;
    }

    @Override
    public boolean needCloseLastFragment(INavigationLayout layout) {
        if (AndroidUtilities.isTablet()) {
            if (layout == actionBarLayout && layout.getFragmentStack().size() <= 1) {
                onFinish();
                finish();
                return false;
            } else if (layout == layersActionBarLayout && actionBarLayout.getFragmentStack().isEmpty() && layersActionBarLayout.getFragmentStack().size() == 1) {
                onFinish();
                finish();
                return false;
            }
        } else {
            if (layout.getFragmentStack().size() <= 1) {
                onFinish();
                finish();
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRebuildAllFragments(INavigationLayout layout, boolean last) {
        if (AndroidUtilities.isTablet()) {
            if (layout == layersActionBarLayout) {
                actionBarLayout.rebuildAllFragmentViews(last, last);
            }
        }
    }
}
