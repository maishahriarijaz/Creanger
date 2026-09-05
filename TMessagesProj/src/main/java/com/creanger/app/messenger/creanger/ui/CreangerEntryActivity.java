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

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);
        FrameLayout root = new FrameLayout(context);
        // Same background as the login screen, so there is no colour flash
        // between this routing fragment and the screen it presents.
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = root;
        AndroidUtilities.runOnUIThread(this::route);
        return fragmentView;
    }

    private void route() {
        Activity activity = getParentActivity();
        if (activity == null || activity.isFinishing()) {
            return;
        }
        CreangerAuth auth = CreangerAuth.getInstance(ApplicationLoader.applicationContext);
        if (!auth.isEnabled() || auth.getState() != AuthState.AUTHENTICATED) {
            showLogin();
            return;
        }
        restoreSession(auth);
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
        Activity activity = getParentActivity();
        if (activity == null || activity.isFinishing()) {
            return;
        }
        CreangerLoginFlowHelper.proceedWithUser(user, activity);
    }

    private void showLogin() {
        Activity activity = getParentActivity();
        if (activity == null || activity.isFinishing()) {
            return;
        }
        presentFragment(new CreangerLoginActivity(), true);
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        return true;
    }
}
