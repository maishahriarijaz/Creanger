package com.creanger.app.messenger.creanger.ui;

import static com.creanger.app.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.text.InputType;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;

import com.creanger.app.messenger.AndroidUtilities;
import com.creanger.app.messenger.ApplicationLoader;
import com.creanger.app.messenger.LocaleController;
import com.creanger.app.messenger.R;
import com.creanger.app.messenger.creanger.CreangerAuth;
import com.creanger.app.messenger.creanger.api.CreangerApiException;
import com.creanger.app.messenger.creanger.auth.CreangerAuthAsync;
import com.creanger.app.messenger.creanger.model.ApiError;
import com.creanger.app.messenger.creanger.model.AuthModels.AuthSession;
import com.creanger.app.messenger.creanger.model.AuthModels.GoogleAuthResult;
import com.creanger.app.ui.ActionBar.BaseFragment;
import com.creanger.app.ui.ActionBar.Theme;
import com.creanger.app.ui.ActionBar.ThemeDescription;
import com.creanger.app.ui.Components.LayoutHelper;
import com.creanger.app.ui.Components.SimpleThemeDescription;
import com.creanger.app.ui.Components.SizeNotifierFrameLayout;

import java.util.ArrayList;

/**
 * Login screen for the Creanger auth flow.
 *
 * The card layout comes from the Creanger UI design; every colour is read from
 * the app's theme engine through {@link CreangerAuthTheme}, and the top-right
 * switch is the app's own day/night toggle. Auth logic (attemptLogin /
 * signInWithGoogle / handleGoogle / error handling) is unchanged and still talks
 * only to CreangerAuth / Supabase via CreangerAuthAsync.
 */
public class CreangerLoginActivity extends BaseFragment {

    private static final int GOOGLE_SIGN_IN_REQUEST = 9001;

    private CreangerAuthTheme.FieldWithIconCompat identifierField;
    private CreangerAuthTheme.FieldWithIconCompat passwordField;
    private CreangerAuthButton loginButton;
    private CreangerAuthButton googleButton;
    private TextView errorTextView;
    private ImageView passwordToggle;
    private CreangerAuthCard card;
    private CreangerAuthTheme.ThemeToggle themeToggle;
    private SizeNotifierFrameLayout root;

    // Views that need re-tinting when the app theme changes.
    private TextView logoText;
    private TextView tagline;
    private TextView welcomeTitle;
    private TextView welcomeSub;
    private TextView identifierLabel;
    private TextView pwLabel;
    private TextView orLabel;
    private TextView googleLabel;
    private View orDividerLeft;
    private View orDividerRight;
    private CreangerAuthTheme.AuthFooter footer;

    private boolean passwordVisible;
    private boolean busy;
    private CreangerAuth creangerAuth;
    private Context mContext;

    @Override
    public View createView(Context context) {
        mContext = context;
        creangerAuth = CreangerAuth.getInstance(ApplicationLoader.applicationContext);

        actionBar.setAddToContainer(false);

        int hPad = CreangerAuthTheme.contentHPad();

        root = new SizeNotifierFrameLayout(context);
        root.setBackgroundColor(CreangerAuthTheme.screenBg());

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        scrollView.setClipToPadding(false);
        scrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scrollView.setVerticalScrollBarEnabled(false);
        root.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        // hPad is already in pixels and clamps the content to MAX_CONTENT_WIDTH_DP
        // on wide screens, so children below stay MATCH_PARENT with no side margins.
        content.setPadding(hPad, dp(CreangerAuthTheme.GAP_XL_DP), hPad, dp(CreangerAuthTheme.GAP_XXL_DP));
        scrollView.addView(content, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));

        // ===== THEME TOGGLE (top right) — the app's real day/night switch =====
        FrameLayout toggleRow = new FrameLayout(context);
        content.addView(toggleRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        themeToggle = CreangerAuthTheme.createThemeToggle(context);
        toggleRow.addView(themeToggle.row, LayoutHelper.createFrame(44, 44,
                Gravity.TOP | (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT)));

        // ===== BRAND SECTION =====
        LinearLayout brandSection = new LinearLayout(context);
        brandSection.setOrientation(LinearLayout.VERTICAL);
        brandSection.setGravity(Gravity.CENTER_HORIZONTAL);
        brandSection.setPadding(0, dp(CreangerAuthTheme.GAP_DP), 0, dp(CreangerAuthTheme.GAP_XXL_DP));
        content.addView(brandSection, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));

        LinearLayout logoRow = new LinearLayout(context);
        logoRow.setOrientation(LinearLayout.HORIZONTAL);
        logoRow.setGravity(Gravity.CENTER_VERTICAL);
        brandSection.addView(logoRow, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));

        ImageView logoIcon = new ImageView(context);
        logoIcon.setImageResource(R.drawable.ic_creanger_logo);
        logoRow.addView(logoIcon, LayoutHelper.createLinear(44, 44, Gravity.CENTER_VERTICAL, 0, 0, CreangerAuthTheme.RADIUS_MD, 0));

        logoText = new TextView(context);
        logoText.setText("Creanger");
        logoText.setTextSize(28);
        logoText.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM));
        logoText.setLetterSpacing(-0.02f);
        logoText.setTextColor(CreangerAuthTheme.textPrimary());
        logoRow.addView(logoText, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        tagline = new TextView(context);
        tagline.setText(LocaleController.getString(R.string.CreangerTagline));
        tagline.setTextSize(14);
        tagline.setTextColor(CreangerAuthTheme.textTertiary());
        tagline.setGravity(Gravity.CENTER);
        tagline.setPadding(0, dp(4), 0, 0);
        brandSection.addView(tagline, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));

        // ===== FORM CARD =====
        card = new CreangerAuthCard(context);
        LinearLayout cardContent = new LinearLayout(context);
        cardContent.setOrientation(LinearLayout.VERTICAL);
        int cardPadH = dp(CreangerAuthTheme.GAP_XL_DP);
        cardContent.setPadding(cardPadH, dp(CreangerAuthTheme.GAP_XXL_DP), cardPadH, dp(CreangerAuthTheme.GAP_XL_DP));
        card.addView(cardContent, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        content.addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));

        welcomeTitle = new TextView(context);
        welcomeTitle.setText(LocaleController.getString(R.string.CreangerWelcomeBack));
        welcomeTitle.setTextSize(26);
        welcomeTitle.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM));
        welcomeTitle.setTextColor(CreangerAuthTheme.textPrimary());
        welcomeTitle.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        cardContent.addView(welcomeTitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        welcomeSub = new TextView(context);
        welcomeSub.setText(LocaleController.getString(R.string.CreangerSignInSubtitle));
        welcomeSub.setTextSize(15);
        welcomeSub.setTextColor(CreangerAuthTheme.textSecondary());
        welcomeSub.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        welcomeSub.setPadding(0, dp(4), 0, dp(CreangerAuthTheme.GAP_XL_DP));
        cardContent.addView(welcomeSub, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Identifier label + field (username or email — both still accepted)
        identifierLabel = CreangerAuthTheme.labelText(context, LocaleController.getString(R.string.CreangerEmailOrUsernameLabel), 13);
        cardContent.addView(identifierLabel,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 0, CreangerAuthTheme.GAP_DP));

        identifierField = CreangerAuthTheme.iconFieldCompat(context, R.drawable.ic_mail_outline, R.string.CreangerUsernameOrEmail,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, EditorInfo.IME_ACTION_NEXT);
        identifierField.field.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_NEXT) {
                passwordField.field.requestFocus();
                AndroidUtilities.showKeyboard(passwordField.field);
            }
            return false;
        });
        cardContent.addView(identifierField.row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, CreangerAuthTheme.FIELD_HEIGHT_DP));

        // Password label + field with eye toggle
        pwLabel = CreangerAuthTheme.labelText(context, LocaleController.getString(R.string.CreangerPassword), 13);
        pwLabel.setPadding(0, dp(CreangerAuthTheme.GAP_LG_DP), 0, 0);
        cardContent.addView(pwLabel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 0, CreangerAuthTheme.GAP_DP));

        passwordField = CreangerAuthTheme.iconFieldCompat(context, R.drawable.ic_lock_outline, R.string.CreangerPassword,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, EditorInfo.IME_ACTION_DONE);
        passwordField.field.setTransformationMethod(PasswordTransformationMethod.getInstance());
        passwordField.field.setOnEditorActionListener((v, a, e) -> {
            if (a == EditorInfo.IME_ACTION_DONE) attemptLogin();
            return false;
        });

        passwordToggle = new ImageView(context);
        passwordToggle.setImageResource(R.drawable.ic_eye_off);
        passwordToggle.setColorFilter(CreangerAuthTheme.textTertiary());
        passwordToggle.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        passwordToggle.setBackground(Theme.createSelectorDrawable(
                Theme.multAlpha(CreangerAuthTheme.textPrimary(), 0.10f), Theme.RIPPLE_MASK_CIRCLE_20DP));
        passwordToggle.setOnClickListener(v -> togglePasswordVisibility());
        passwordToggle.setContentDescription(LocaleController.getString(R.string.CreangerPasswordShow));
        passwordField.row.addView(passwordToggle, LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL));

        cardContent.addView(passwordField.row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, CreangerAuthTheme.FIELD_HEIGHT_DP, 0, 0, 0, 0, CreangerAuthTheme.GAP_DP));

        // Error text
        errorTextView = CreangerAuthTheme.errorText(context);
        cardContent.addView(errorTextView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Primary login button
        loginButton = new CreangerAuthButton(context, LocaleController.getString(R.string.CreangerLoginButton),
                CreangerAuthTheme.accent(), CreangerAuthTheme.onAccent());
        loginButton.setOnClickListener(v -> attemptLogin());
        cardContent.addView(loginButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, CreangerAuthTheme.BUTTON_HEIGHT_DP, 0, CreangerAuthTheme.GAP_DP, 0, 0));

        // "or" divider
        LinearLayout orSection = new LinearLayout(context);
        orSection.setOrientation(LinearLayout.HORIZONTAL);
        orSection.setGravity(Gravity.CENTER_VERTICAL);
        orSection.setPadding(0, dp(CreangerAuthTheme.GAP_XL_DP), 0, dp(CreangerAuthTheme.GAP_XL_DP));
        cardContent.addView(orSection, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        orDividerLeft = new View(context);
        orDividerLeft.setBackgroundColor(CreangerAuthTheme.dividerColor());
        orSection.addView(orDividerLeft, LayoutHelper.createLinear(0, 1, 1f, Gravity.CENTER_VERTICAL));

        orLabel = new TextView(context);
        orLabel.setText(LocaleController.getString(R.string.CreangerOrContinueWith).toUpperCase());
        orLabel.setTextSize(11);
        orLabel.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM));
        orLabel.setLetterSpacing(0.08f);
        orLabel.setTextColor(CreangerAuthTheme.textTertiary());
        orLabel.setPadding(dp(14), 0, dp(14), 0);
        orSection.addView(orLabel, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        orDividerRight = new View(context);
        orDividerRight.setBackgroundColor(CreangerAuthTheme.dividerColor());
        orSection.addView(orDividerRight, LayoutHelper.createLinear(0, 1, 1f, Gravity.CENTER_VERTICAL));

        // Google button
        googleButton = new CreangerAuthButton(context, LocaleController.getString(R.string.CreangerContinueWithGoogle),
                CreangerAuthTheme.googleBg(), CreangerAuthTheme.textSecondary());
        googleButton.setOnClickListener(v -> signInWithGoogle());
        googleButton.setContentDescription(LocaleController.getString(R.string.CreangerContinueWithGoogle));

        LinearLayout googleContent = new LinearLayout(context);
        googleContent.setOrientation(LinearLayout.HORIZONTAL);
        googleContent.setGravity(Gravity.CENTER);
        googleButton.removeAllViews();
        googleButton.addView(googleContent, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        ImageView googleIcon = new ImageView(context);
        googleIcon.setImageResource(R.drawable.ic_google_logo);
        googleIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        googleContent.addView(googleIcon, LayoutHelper.createLinear(20, 20, Gravity.CENTER_VERTICAL, 0, 0, 10, 0));

        googleLabel = new TextView(context);
        googleLabel.setText(LocaleController.getString(R.string.CreangerContinueWithGoogle));
        googleLabel.setTextSize(15);
        googleLabel.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM));
        googleLabel.setTextColor(CreangerAuthTheme.textSecondary());
        googleContent.addView(googleLabel);

        cardContent.addView(googleButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, CreangerAuthTheme.BUTTON_HEIGHT_DP));

        // ===== FOOTER =====
        footer = CreangerAuthTheme.authFooter(context, this::showPrivacyDialog, this::showTermsDialog);
        content.addView(footer.row, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL, 0, CreangerAuthTheme.GAP_XXL_DP, 0, 0));

        // ===== FINISH =====
        fragmentView = root;

        CreangerAuthTheme.slideUp(brandSection, 0);
        CreangerAuthTheme.slideUp(card, 80);
        CreangerAuthTheme.slideUp(footer.row, 160);

        identifierField.field.requestFocus();
        AndroidUtilities.runOnUIThread(() -> AndroidUtilities.showKeyboard(identifierField.field));

        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        // Lets the ScrollView lift the focused field above the keyboard.
        AndroidUtilities.requestAdjustResize(getParentActivity(), classGuid);
    }

    /** Re-applies every colour from the app theme; used as the ThemeDescription delegate. */
    private void onThemeChanged() {
        if (root == null) return;
        root.setBackgroundColor(CreangerAuthTheme.screenBg());
        card.refreshTheme();
        themeToggle.refreshTheme();

        logoText.setTextColor(CreangerAuthTheme.textPrimary());
        tagline.setTextColor(CreangerAuthTheme.textTertiary());
        welcomeTitle.setTextColor(CreangerAuthTheme.textPrimary());
        welcomeSub.setTextColor(CreangerAuthTheme.textSecondary());
        identifierLabel.setTextColor(CreangerAuthTheme.textSecondary());
        pwLabel.setTextColor(CreangerAuthTheme.textSecondary());

        identifierField.refreshTheme();
        passwordField.refreshTheme();
        passwordToggle.setColorFilter(CreangerAuthTheme.textTertiary());
        passwordToggle.setBackground(Theme.createSelectorDrawable(
                Theme.multAlpha(CreangerAuthTheme.textPrimary(), 0.10f), Theme.RIPPLE_MASK_CIRCLE_20DP));

        if (errorTextView.getVisibility() == View.VISIBLE) {
            errorTextView.setTextColor(CreangerAuthTheme.errorColor());
        }

        loginButton.setColors(CreangerAuthTheme.accent(), CreangerAuthTheme.onAccent());
        googleButton.setColors(CreangerAuthTheme.googleBg(), CreangerAuthTheme.textSecondary());
        googleLabel.setTextColor(CreangerAuthTheme.textSecondary());

        orDividerLeft.setBackgroundColor(CreangerAuthTheme.dividerColor());
        orDividerRight.setBackgroundColor(CreangerAuthTheme.dividerColor());
        orLabel.setTextColor(CreangerAuthTheme.textTertiary());

        footer.refreshTheme();
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        return SimpleThemeDescription.createThemeDescriptions(this::onThemeChanged,
                Theme.key_windowBackgroundGray,
                Theme.key_windowBackgroundWhite,
                Theme.key_windowBackgroundWhiteBlackText,
                Theme.key_windowBackgroundWhiteGrayText,
                Theme.key_windowBackgroundWhiteHintText,
                Theme.key_windowBackgroundWhiteInputField,
                Theme.key_windowBackgroundWhiteInputFieldActivated,
                Theme.key_featuredStickers_addButton,
                Theme.key_featuredStickers_buttonText,
                Theme.key_text_RedRegular,
                Theme.key_divider);
    }

    @Override
    public boolean isLightStatusBar() {
        return ColorUtils.calculateLuminance(Theme.getColor(Theme.key_windowBackgroundGray, null, true)) > 0.7f;
    }

    private void showPrivacyDialog() {
        if (getParentActivity() == null) return;
        new com.creanger.app.ui.ActionBar.AlertDialog.Builder(getParentActivity())
                .setTitle(LocaleController.getString(R.string.CreangerPrivacyDialogTitle))
                .setMessage(LocaleController.getString(R.string.CreangerPrivacyDialogText))
                .setPositiveButton(LocaleController.getString(R.string.OK), null)
                .show();
    }

    private void showTermsDialog() {
        if (getParentActivity() == null) return;
        new com.creanger.app.ui.ActionBar.AlertDialog.Builder(getParentActivity())
                .setTitle(LocaleController.getString(R.string.CreangerTermsDialogTitle))
                .setMessage(LocaleController.getString(R.string.CreangerTermsDialogText))
                .setPositiveButton(LocaleController.getString(R.string.OK), null)
                .show();
    }

    private void togglePasswordVisibility() {
        passwordVisible = !passwordVisible;
        passwordField.field.setTransformationMethod(passwordVisible
                ? HideReturnsTransformationMethod.getInstance()
                : PasswordTransformationMethod.getInstance());
        passwordToggle.setImageResource(passwordVisible ? R.drawable.ic_eye : R.drawable.ic_eye_off);
        passwordToggle.setContentDescription(LocaleController.getString(
                passwordVisible ? R.string.CreangerPasswordHide : R.string.CreangerPasswordShow));
        passwordField.field.setSelection(passwordField.field.length());

        passwordToggle.animate()
                .scaleX(0.85f).scaleY(0.85f)
                .setDuration(80)
                .withEndAction(() -> passwordToggle.animate()
                        .scaleX(1f).scaleY(1f)
                        .setDuration(80)
                        .start())
                .start();
    }

    private void attemptLogin() {
        if (busy) return;
        String id = identifierField.field.getText().toString().trim();
        String pw = passwordField.field.getText().toString();
        hideError();

        boolean hasError = false;
        if (id.isEmpty()) {
            showError(LocaleController.getString(R.string.CreangerErrorEmptyUsernameOrEmail));
            identifierField.setErrorState(true);
            hasError = true;
        }
        if (pw.isEmpty()) {
            if (!hasError) showError(LocaleController.getString(R.string.CreangerErrorEmptyPassword));
            passwordField.setErrorState(true);
            hasError = true;
        }
        if (hasError) return;

        setBusy(true);
        creangerAuth.getAsync().loginWithPassword(id, pw, new CreangerAuthAsync.AuthCallback<AuthSession>() {
            @Override public void onSuccess(AuthSession r) {
                setBusy(false);
                if (r != null && r.user != null) {
                    CreangerLoginFlowHelper.proceedWithUser(r.user, getParentActivity());
                } else {
                    showError(LocaleController.getString(R.string.CreangerErrorGeneric));
                }
            }
            @Override public void onError(CreangerApiException e, Throwable t) {
                setBusy(false);
                onErr(e, t);
            }
        });
    }

    private void signInWithGoogle() {
        if (busy) return;
        hideError();
        try {
            GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                    .requestIdToken(mContext.getString(R.string.default_web_client_id))
                    .requestEmail()
                    .build();
            GoogleSignInClient client = GoogleSignIn.getClient(getParentActivity(), gso);
            startActivityForResult(client.getSignInIntent(), GOOGLE_SIGN_IN_REQUEST);
        } catch (Exception e) {
            showError(LocaleController.getString(R.string.CreangerErrorGeneric));
        }
    }

    @Override
    public void onActivityResultFragment(int req, int res, Intent data) {
        super.onActivityResultFragment(req, res, data);
        if (req == GOOGLE_SIGN_IN_REQUEST && res == Activity.RESULT_OK && data != null) {
            try {
                GoogleSignInAccount a = GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException.class);
                if (a != null && a.getIdToken() != null) handleGoogle(a);
            } catch (ApiException e) {
                if (e.getStatusCode() != 12501) showError(LocaleController.getString(R.string.CreangerErrorGeneric));
            }
        }
    }

    private void handleGoogle(GoogleSignInAccount account) {
        setBusy(true);
        creangerAuth.getAsync().googleAuth(account.getIdToken(), new CreangerAuthAsync.AuthCallback<GoogleAuthResult>() {
            @Override
            public void onSuccess(GoogleAuthResult r) {
                setBusy(false);
                if (r == null) return;
                if (r.needsRegistration) {
                    String email = r.session != null && r.session.user != null && r.session.user.email != null
                            ? r.session.user.email : account.getEmail();
                    presentFragment(new CreangerGoogleAccountSetupActivity(email, account.getDisplayName()));
                } else if (r.session != null && r.session.user != null) {
                    CreangerLoginFlowHelper.proceedWithUser(r.session.user, getParentActivity());
                }
            }
            @Override public void onError(CreangerApiException e, Throwable t) { setBusy(false); onErr(e, t); }
        });
    }

    private void onErr(CreangerApiException error, Throwable io) {
        AndroidUtilities.runOnUIThread(() -> {
            if (getParentActivity() == null || getParentActivity().isFinishing()) return;
            if (error != null && error.is(ApiError.INVALID_CREDENTIALS)) {
                showError(LocaleController.getString(R.string.CreangerErrorInvalidCredentials));
                identifierField.setErrorState(true);
                passwordField.setErrorState(true);
            } else if (error != null && error.is(ApiError.RATE_LIMITED)) {
                showError(LocaleController.getString(R.string.CreangerErrorRateLimited));
            } else {
                showError(CreangerLoginFlowHelper.safeErrorMessage(error, io));
            }
        });
    }

    private void setBusy(boolean b) {
        busy = b;
        loginButton.setLoading(b);
        googleButton.setEnabled(!b);
        googleButton.setAlpha(b ? 0.5f : 1f);
        identifierField.field.setEnabled(!b);
        passwordField.field.setEnabled(!b);
        passwordToggle.setEnabled(!b);
    }

    private void showError(String msg) { CreangerAuthTheme.showError(errorTextView, msg); }
    private void hideError() { CreangerAuthTheme.hideError(errorTextView); }

    @Override public boolean onBackPressed(boolean inv) { if (inv) finishFragment(); return true; }
}
