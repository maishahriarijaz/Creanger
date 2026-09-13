package com.creanger.app.messenger.creanger.ui;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

import com.creanger.app.messenger.AndroidUtilities;
import com.creanger.app.messenger.ApplicationLoader;
import com.creanger.app.messenger.creanger.CreangerAuth;
import com.creanger.app.messenger.creanger.api.CreangerApiException;
import com.creanger.app.messenger.creanger.auth.AuthState;
import com.creanger.app.messenger.creanger.auth.CreangerAuthAsync;
import com.creanger.app.messenger.creanger.model.AuthModels.CreangerUser;
import com.creanger.app.ui.ActionBar.BaseFragment;
import com.creanger.app.ui.ActionBar.Theme;

public class CreangerEntryActivity extends BaseFragment {

    private static final long RETRY_DELAY_MS = 300;
    private static final int MAX_RETRIES = 20;

    private boolean routed;
    private int routeAttempts;

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);
        FrameLayout root = new FrameLayout(context);
        // Same background as the login screen, so there is no colour flash
        // between this routing fragment and the screen it presents.
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = root;
        routed = false;
        routeAttempts = 0;
        AndroidUtilities.runOnUIThread(this::route);
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        // If a previous routing attempt found no host activity (cold start
        // race), retry now that the fragment is resumed — otherwise the user
        // would sit on a blank screen forever.
        if (!routed && fragmentView != null) {
            AndroidUtilities.runOnUIThread(this::route);
        }
    }

    private void route() {
        if (routed || fragmentView == null || isFinished) {
            return;
        }
        Activity activity = getParentActivity();
        if (activity == null || activity.isFinishing()) {
            retryLater();
            return;
        }
        CreangerAuth auth = CreangerAuth.getInstance(ApplicationLoader.applicationContext);
        if (!auth.isEnabled() || auth.getState() != AuthState.AUTHENTICATED) {
            showLogin();
            return;
        }
        restoreSession(auth);
    }

    private void retryLater() {
        if (routed || routeAttempts >= MAX_RETRIES) {
            return;
        }
        routeAttempts++;
        AndroidUtilities.runOnUIThread(this::route, RETRY_DELAY_MS);
    }

    private void restoreSession(CreangerAuth auth) {
        auth.getAsync().loadCurrentUser(new CreangerAuthAsync.AuthCallback<CreangerUser>() {
            @Override
            public void onSuccess(CreangerUser user) {
                if (user != null) {
                    proceedHome(user);
                } else {
                    showLogin();
                }
            }

            @Override
            public void onError(CreangerApiException error, Throwable ioError) {
                CreangerUser cached = auth.getCurrentUserRepository().getCachedUser();
                if (cached != null) {
                    proceedHome(cached);
                } else {
                    showLogin();
                }
            }
        });
    }

    private void proceedHome(CreangerUser user) {
        if (routed || fragmentView == null || isFinished) {
            return;
        }
        Activity activity = getParentActivity();
        if (activity == null || activity.isFinishing()) {
            retryLater();
            return;
        }
        if (CreangerLoginFlowHelper.proceedWithUser(user, activity)) {
            routed = true;
        } else {
            showLogin();
        }
    }

    private void showLogin() {
        if (routed || fragmentView == null || isFinished) {
            return;
        }
        Activity activity = getParentActivity();
        if (activity == null || activity.isFinishing()) {
            retryLater();
            return;
        }
        try {
            if (presentFragment(new CreangerLoginActivity(), true)) {
                routed = true;
            } else {
                retryLater();
            }
        } catch (Exception e) {
            retryLater();
        }
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        return true;
    }
}
