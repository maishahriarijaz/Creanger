package com.creanger.app.messenger.creanger.ui;

import static com.creanger.app.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.InputType;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;

import com.creanger.app.messenger.AndroidUtilities;
import com.creanger.app.messenger.ApplicationLoader;
import com.creanger.app.messenger.BuildConfig;
import com.creanger.app.messenger.LocaleController;
import com.creanger.app.messenger.R;
import com.creanger.app.messenger.creanger.CreangerAuth;
import com.creanger.app.messenger.creanger.api.CreangerApiException;
import com.creanger.app.messenger.creanger.auth.CreangerAuthAsync;
import com.creanger.app.messenger.creanger.auth.GoogleSignInErrorMapper;
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
 * Flat single-page composition: brand header, form, divider, Google button
 * and footer all live directly on the page background — no floating card.
 * Every colour is read from the app's theme engine through
 * {@link CreangerAuthTheme}, and the top-right switch is the app's own
 * day/night toggle. Auth logic (attemptLogin / signInWithGoogle /
 * handleGoogle / error handling) talks only to CreangerAuth / Supabase via
 * CreangerAuthAsync.
 *
 * Keyboard behavior relies on the existing {@code adjustResize} window setup
 * (see {@link #onResume}); on top of that the focused field is smoothly
 * scrolled into view using its real on-screen position — no fixed paddings,
 * no hardcoded keyboard heights.
 */
public class CreangerLoginActivity extends BaseFragment {

    private static final int GOOGLE_SIGN_IN_REQUEST = 9001;
    /** Placeholder that must be replaced with the real OAuth web client ID. */
    private static final String WEB_CLIENT_ID_PLACEHOLDER = "YOUR_GOOGLE_OAUTH_WEB_CLIENT_ID";

    private CreangerAuthTheme.FieldWithIconCompat identifierField;
    private CreangerAuthTheme.FieldWithIconCompat passwordField;
    private CreangerAuthButton loginButton;
    private FrameLayout googleButton;
    private GradientDrawable googleButtonBg;
    private TextView errorTextView;
    private ImageView passwordToggle;
    private CreangerAuthTheme.ThemeToggle themeToggle;
    private SizeNotifierFrameLayout root;
    private ScrollView scrollView;
    private LinearLayout content;

    // Views that need re-tinting when the app theme changes.
    private TextView logoText;
    private TextView tagline;
    private TextView welcomeTitle;
    private TextView welcomeSub;
    private TextView identifierLabel;
    private TextView pwLabel;
    private TextView orLabel;
    private TextView googleLabel;
    private GradientDrawable logoTileBg;
    private View orDividerLeft;
    private View orDividerRight;
    private GradientDrawable brandGlowBg;
    private CreangerAuthTheme.AuthFooter footer;

    private boolean passwordVisible;
    private boolean busy;
    private boolean googlePickerInFlight;
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

        // Soft accent glow behind the brand — gives the page depth in any theme.
        brandGlowBg = new GradientDrawable();
        brandGlowBg.setShape(GradientDrawable.RECTANGLE);
        brandGlowBg.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        brandGlowBg.setGradientRadius(dp(240));
        applyBrandGlowColors();
        View brandGlow = new View(context);
        brandGlow.setBackground(brandGlowBg);
        brandGlow.setClickable(false);
        brandGlow.setFocusable(false);
        root.addView(brandGlow, LayoutHelper.createFrame(560, 340, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, -60, 0, 0));

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        scrollView.setClipToPadding(false);
        scrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scrollView.setVerticalScrollBarEnabled(false);
        root.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        this.scrollView = scrollView;

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        // hPad is already in pixels and clamps the content to MAX_CONTENT_WIDTH_DP
        // on wide screens, so children below stay MATCH_PARENT with no side margins.
        content.setPadding(hPad, dp(CreangerAuthTheme.GAP_XL_DP), hPad, dp(CreangerAuthTheme.GAP_XXL_DP));
        scrollView.addView(content, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
        this.content = content;

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

        logoTileBg = new GradientDrawable();
        logoTileBg.setShape(GradientDrawable.RECTANGLE);
        logoTileBg.setCornerRadius(dp(CreangerAuthTheme.RADIUS_LG));
        logoTileBg.setColor(Theme.multAlpha(CreangerAuthTheme.accent(), 0.14f));

        FrameLayout logoWrap = new FrameLayout(context);
        brandSection.addView(logoWrap, LayoutHelper.createLinear(64, 64, Gravity.CENTER_HORIZONTAL));
        View logoTile = new View(context);
        logoTile.setBackground(logoTileBg);
        logoWrap.addView(logoTile, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        ImageView logoIcon = new ImageView(context);
        logoIcon.setImageResource(R.drawable.ic_creanger_mark);
        logoIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        logoWrap.addView(logoIcon, LayoutHelper.createFrame(50, 50, Gravity.CENTER));

        logoText = new TextView(context);
        logoText.setText("Creanger");
        logoText.setTextSize(28);
        logoText.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM));
        logoText.setLetterSpacing(-0.02f);
        logoText.setTextColor(CreangerAuthTheme.textPrimary());
        logoText.setGravity(Gravity.CENTER);
        logoText.setPadding(0, dp(10), 0, 0);
        brandSection.addView(logoText, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));

        tagline = new TextView(context);
        tagline.setText(LocaleController.getString(R.string.CreangerTagline));
        tagline.setTextSize(14);
        tagline.setTextColor(CreangerAuthTheme.textTertiary());
        tagline.setGravity(Gravity.CENTER);
        tagline.setPadding(0, dp(4), 0, 0);
        brandSection.addView(tagline, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));

        // ===== FORM (flat page composition — no floating card) =====
        LinearLayout formSection = new LinearLayout(context);
        formSection.setOrientation(LinearLayout.VERTICAL);
        formSection.setPadding(0, dp(CreangerAuthTheme.GAP_DP), 0, 0);
        content.addView(formSection, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));

        welcomeTitle = new TextView(context);
        welcomeTitle.setText(LocaleController.getString(R.string.CreangerWelcomeBack));
        welcomeTitle.setTextSize(24);
        welcomeTitle.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM));
        welcomeTitle.setLetterSpacing(-0.01f);
        welcomeTitle.setTextColor(CreangerAuthTheme.textPrimary());
        welcomeTitle.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        formSection.addView(welcomeTitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        welcomeSub = new TextView(context);
        welcomeSub.setText(LocaleController.getString(R.string.CreangerSignInSubtitle));
        welcomeSub.setTextSize(15);
        welcomeSub.setTextColor(CreangerAuthTheme.textSecondary());
        welcomeSub.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        welcomeSub.setPadding(0, dp(4), 0, dp(CreangerAuthTheme.GAP_XL_DP));
        formSection.addView(welcomeSub, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Identifier label + field (username or email — both still accepted)
        identifierLabel = CreangerAuthTheme.labelText(context, LocaleController.getString(R.string.CreangerEmailOrUsernameLabel), 13);
        formSection.addView(identifierLabel,
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
        identifierField.field.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) scrollToFocusedField(identifierField.row);
        });
        formSection.addView(identifierField.row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, CreangerAuthTheme.FIELD_HEIGHT_DP));

        // Password label + field with the show/hide eye toggle
        pwLabel = CreangerAuthTheme.labelText(context, LocaleController.getString(R.string.CreangerPassword), 13);
        pwLabel.setPadding(0, dp(CreangerAuthTheme.GAP_LG_DP), 0, 0);
        formSection.addView(pwLabel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 0, CreangerAuthTheme.GAP_DP));

        passwordField = CreangerAuthTheme.iconFieldCompat(context, R.drawable.ic_lock_outline, R.string.CreangerPassword,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, EditorInfo.IME_ACTION_DONE);
        passwordField.field.setTransformationMethod(PasswordTransformationMethod.getInstance());
        passwordField.field.setOnEditorActionListener((v, a, e) -> {
            if (a == EditorInfo.IME_ACTION_DONE) attemptLogin();
            return false;
        });
        passwordField.field.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) scrollToFocusedField(passwordField.row);
        });

        passwordToggle = new ImageView(context);
        passwordToggle.setImageResource(R.drawable.ic_eye_off);
        passwordToggle.setColorFilter(CreangerAuthTheme.textTertiary());
        passwordToggle.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        passwordToggle.setPadding(dp(12), dp(12), dp(12), dp(12));
        passwordToggle.setClickable(true);
        passwordToggle.setFocusable(true);
        passwordToggle.setBackground(Theme.createSelectorDrawable(
                Theme.multAlpha(CreangerAuthTheme.textPrimary(), 0.10f), Theme.RIPPLE_MASK_CIRCLE_20DP));
        passwordToggle.setOnClickListener(v -> togglePasswordVisibility());
        refreshPasswordToggleAccessibility();
        // 48dp touch target, icon optically centered inside the field.
        passwordField.row.addView(passwordToggle, LayoutHelper.createLinear(48, 48, Gravity.CENTER_VERTICAL));

        formSection.addView(passwordField.row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, CreangerAuthTheme.FIELD_HEIGHT_DP, 0, 0, 0, 0, CreangerAuthTheme.GAP_DP));

        // Error text
        errorTextView = CreangerAuthTheme.errorText(context);
        formSection.addView(errorTextView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Primary login button
        loginButton = new CreangerAuthButton(context, LocaleController.getString(R.string.CreangerLoginButton),
                CreangerAuthTheme.accent(), CreangerAuthTheme.onAccent());
        loginButton.setOnClickListener(v -> attemptLogin());
        formSection.addView(loginButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, CreangerAuthTheme.BUTTON_HEIGHT_DP, 0, CreangerAuthTheme.GAP_DP, 0, 0));

        // "or" divider
        LinearLayout orSection = new LinearLayout(context);
        orSection.setOrientation(LinearLayout.HORIZONTAL);
        orSection.setGravity(Gravity.CENTER_VERTICAL);
        orSection.setPadding(0, dp(CreangerAuthTheme.GAP_XL_DP), 0, dp(CreangerAuthTheme.GAP_XL_DP));
        formSection.addView(orSection, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

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

        // Google button — official-style: solid surface, hairline border, real "G".
        googleButton = buildGoogleButton(context);
        formSection.addView(googleButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, CreangerAuthTheme.BUTTON_HEIGHT_DP));

        // Flexible spacer: pushes the footer to the bottom edge when the
        // viewport is tall, collapses to zero when the keyboard shrinks it,
        // so the form can scroll naturally without hardcoded offsets.
        Space bottomSpacer = new Space(context);
        bottomSpacer.setClickable(false);
        bottomSpacer.setFocusable(false);
        LinearLayout.LayoutParams spacerParams = LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, 0, 1f, Gravity.CENTER_HORIZONTAL);
        spacerParams.topMargin = dp(CreangerAuthTheme.GAP_LG_DP);
        content.addView(bottomSpacer, spacerParams);

        // ===== FOOTER =====
        footer = CreangerAuthTheme.authFooter(context, this::showPrivacyDialog, this::showTermsDialog);
        content.addView(footer.row, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL, 0, CreangerAuthTheme.GAP_DP, 0, 0));

        // ===== FINISH =====
        fragmentView = root;

        CreangerAuthTheme.slideUp(brandSection, 0);
        CreangerAuthTheme.slideUp(formSection, 80);
        CreangerAuthTheme.slideUp(footer.row, 160);

        identifierField.field.requestFocus();
        AndroidUtilities.runOnUIThread(() -> AndroidUtilities.showKeyboard(identifierField.field));

        return fragmentView;
    }

    /**
     * The Google Sign-In button: bordered surface with the real multicolor "G"
     * and a centered label, following the official Google button guidance.
     */
    private FrameLayout buildGoogleButton(Context context) {
        FrameLayout button = new FrameLayout(context);
        button.setClickable(true);
        button.setFocusable(true);

        googleButtonBg = new GradientDrawable();
        googleButtonBg.setShape(GradientDrawable.RECTANGLE);
        googleButtonBg.setCornerRadius(dp(CreangerAuthTheme.RADIUS_LG));
        applyGoogleButtonColors();
        button.setBackground(googleButtonBg);
        button.setContentDescription(LocaleController.getString(R.string.CreangerGoogleButtonContentDescription));

        LinearLayout googleContent = new LinearLayout(context);
        googleContent.setOrientation(LinearLayout.HORIZONTAL);
        googleContent.setGravity(Gravity.CENTER);
        googleContent.setClickable(false);
        googleContent.setFocusable(false);
        button.addView(googleContent, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        ImageView googleIcon = new ImageView(context);
        googleIcon.setImageResource(R.drawable.ic_google_logo);
        googleIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        googleIcon.setClickable(false);
        googleIcon.setFocusable(false);
        googleContent.addView(googleIcon, LayoutHelper.createLinear(20, 20, Gravity.CENTER_VERTICAL, 0, 0, 12, 0));

        googleLabel = new TextView(context);
        googleLabel.setText(LocaleController.getString(R.string.CreangerContinueWithGoogle));
        googleLabel.setTextSize(15);
        googleLabel.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM));
        googleLabel.setTextColor(CreangerAuthTheme.googleButtonText());
        googleLabel.setClickable(false);
        googleLabel.setFocusable(false);
        googleContent.addView(googleLabel);

        button.setOnClickListener(v -> signInWithGoogle());
        button.setOnTouchListener((v, event) -> {
            if (busy || !button.isEnabled()) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    button.animate().alpha(0.6f).setDuration(80).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    button.animate().alpha(button.isEnabled() ? 1f : 0.5f).setDuration(150).start();
                    break;
            }
            return false;
        });
        return button;
    }

    private void applyGoogleButtonColors() {
        if (googleButtonBg == null) return;
        googleButtonBg.setColor(CreangerAuthTheme.googleButtonBg());
        googleButtonBg.setStroke(dp(1), CreangerAuthTheme.googleButtonBorder());
    }

    private void applyBrandGlowColors() {
        if (brandGlowBg == null) return;
        brandGlowBg.setColors(new int[]{
                Theme.multAlpha(CreangerAuthTheme.accent(), 0.16f),
                Theme.multAlpha(CreangerAuthTheme.accent(), 0.0f)});
    }

    /**
     * Smoothly scrolls the focused field (or its row) into view above the
     * keyboard. Positions come from the real layout pass, so this adapts to
     * any screen size, density or IME height; {@code adjustResize} (see
     * {@link #onResume}) shrinks the ScrollView first and the framework also
     * pans, this just guarantees the field is fully visible with margin.
     */
    private void scrollToFocusedField(View anchor) {
        if (scrollView == null || anchor == null) return;
        scrollView.post(() -> {
            if (scrollView == null) return;
            int[] anchorPos = new int[2];
            int[] scrollPos = new int[2];
            anchor.getLocationOnScreen(anchorPos);
            scrollView.getLocationOnScreen(scrollPos);
            int anchorBottomOnScreen = anchorPos[1] + anchor.getHeight();
            int visibleBottom = scrollPos[1] + scrollView.getHeight() - dp(16);
            if (anchorBottomOnScreen > visibleBottom) {
                scrollView.smoothScrollBy(0, anchorBottomOnScreen - visibleBottom);
            }
        });
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
        applyBrandGlowColors();
        themeToggle.refreshTheme();

        logoText.setTextColor(CreangerAuthTheme.textPrimary());
        tagline.setTextColor(CreangerAuthTheme.textTertiary());
        if (logoTileBg != null) {
            logoTileBg.setColor(Theme.multAlpha(CreangerAuthTheme.accent(), 0.14f));
        }
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
        applyGoogleButtonColors();
        googleLabel.setTextColor(CreangerAuthTheme.googleButtonText());

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
        refreshPasswordToggleAccessibility();
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

    private void refreshPasswordToggleAccessibility() {
        passwordToggle.setContentDescription(LocaleController.getString(
                passwordVisible ? R.string.CreangerPasswordHide : R.string.CreangerPasswordShow));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            passwordToggle.setTooltipText(passwordToggle.getContentDescription());
        }
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

        if (creangerAuth != null && !creangerAuth.getConfig().hasBackendConfig()) {
            showError(LocaleController.getString(R.string.CreangerBackendNotConfigured));
            return;
        }

        setBusy(true);
        creangerAuth.getAsync().loginWithPassword(id, pw, new CreangerAuthAsync.AuthCallback<AuthSession>() {
            @Override public void onSuccess(AuthSession r) {
                setBusy(false);
                if (r != null && r.user != null) {
                    if (!CreangerLoginFlowHelper.proceedWithUser(r.user, getParentActivity())) {
                        showError(LocaleController.getString(R.string.CreangerErrorGeneric));
                    }
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

    /**
     * P0-3: single authoritative source is {@link BuildConfig#GOOGLE_WEB_CLIENT_ID},
     * injected at build time from GOOGLE_WEB_CLIENT_ID (env &gt; Gradle property &gt;
     * local.properties) so debug and release share one value. The
     * {@code CreangerGoogleWebClientId} string resource is legacy-only fallback and
     * MUST stay a placeholder (never a second real ID); a placeholder/empty value
     * means "not configured".
     */
    private String resolveWebClientId() {
        String buildId = null;
        try {
            buildId = BuildConfig.GOOGLE_WEB_CLIENT_ID;
        } catch (Exception e) {
            buildId = null;
        }
        if (buildId != null && !buildId.isEmpty() && !WEB_CLIENT_ID_PLACEHOLDER.equals(buildId)) {
            return buildId;
        }
        if (mContext == null) return null;
        String id;
        try {
            id = mContext.getString(R.string.CreangerGoogleWebClientId);
        } catch (Exception e) {
            return null;
        }
        if (id == null || id.isEmpty() || WEB_CLIENT_ID_PLACEHOLDER.equals(id)) {
            return null;
        }
        return id;
    }

    private void signInWithGoogle() {
        if (busy || googlePickerInFlight) return;
        hideError();
        String webClientId = resolveWebClientId();
        if (webClientId == null) {
            showError(LocaleController.getString(R.string.CreangerGoogleMisconfigured));
            return;
        }
        if (creangerAuth != null && !creangerAuth.getConfig().hasBackendConfig()) {
            showError(LocaleController.getString(R.string.CreangerBackendNotConfigured));
            return;
        }
        Activity activity = getParentActivity();
        if (activity == null || activity.isFinishing()) {
            showError(LocaleController.getString(R.string.CreangerGoogleSignInFailed));
            return;
        }
        try {
            GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                    .requestIdToken(webClientId)
                    .requestEmail()
                    .build();
            GoogleSignInClient client = GoogleSignIn.getClient(activity, gso);
            googlePickerInFlight = true;
            setBusy(true);
            startActivityForResult(client.getSignInIntent(), GOOGLE_SIGN_IN_REQUEST);
        } catch (Exception e) {
            googlePickerInFlight = false;
            setBusy(false);
            showError(LocaleController.getString(R.string.CreangerGoogleSignInFailed));
        }
    }

    @Override
    public void onActivityResultFragment(int req, int res, Intent data) {
        super.onActivityResultFragment(req, res, data);
        if (req != GOOGLE_SIGN_IN_REQUEST) {
            return;
        }
        // The account picker has returned on every path below — release the
        // launch guard and the picker busy state before handling the result.
        googlePickerInFlight = false;
        setBusy(false);
        if (res == Activity.RESULT_CANCELED) {
            // The user dismissed the account picker; stay silent by design.
            return;
        }
        if (res != Activity.RESULT_OK || data == null) {
            showError(LocaleController.getString(R.string.CreangerGoogleSignInFailed));
            return;
        }
        GoogleSignInAccount account;
        try {
            account = GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException.class);
        } catch (ApiException e) {
            onGoogleApiError(e);
            return;
        } catch (Exception e) {
            showError(LocaleController.getString(R.string.CreangerGoogleSignInFailed));
            return;
        }
        if (account == null || account.getIdToken() == null) {
            showError(LocaleController.getString(R.string.CreangerGoogleNoToken));
            return;
        }
        handleGoogle(account);
    }

    private void onGoogleApiError(ApiException e) {
        setBusy(false);
        switch (GoogleSignInErrorMapper.mapStatusCode(e.getStatusCode())) {
            case CANCELLED:
                break;
            case NETWORK_ERROR:
                showError(LocaleController.getString(R.string.CreangerGoogleNetworkError));
                break;
            case CONFIG_ERROR:
                showError(LocaleController.getString(R.string.CreangerGoogleAccountError));
                break;
            case SIGN_IN_FAILED:
            default:
                showError(LocaleController.getString(R.string.CreangerGoogleSignInFailed));
                break;
        }
    }

    private void handleGoogle(GoogleSignInAccount account) {
        if (account == null || account.getIdToken() == null) {
            showError(LocaleController.getString(R.string.CreangerGoogleNoToken));
            return;
        }
        setBusy(true);
        creangerAuth.getAsync().googleAuth(account.getIdToken(), new CreangerAuthAsync.AuthCallback<GoogleAuthResult>() {
            @Override
            public void onSuccess(GoogleAuthResult r) {
                setBusy(false);
                if (r == null) {
                    showError(LocaleController.getString(R.string.CreangerGoogleSignInFailed));
                    return;
                }
                if (r.needsRegistration) {
                    String email = r.session != null && r.session.user != null && r.session.user.email != null
                            ? r.session.user.email : account.getEmail();
                    try {
                        boolean presented = presentFragment(new CreangerGoogleAccountSetupActivity(email, account.getDisplayName()));
                        if (!presented) {
                            showError(LocaleController.getString(R.string.CreangerGoogleSignInFailed));
                        }
                    } catch (Exception e) {
                        showError(LocaleController.getString(R.string.CreangerGoogleSignInFailed));
                    }
                } else if (r.session != null && r.session.user != null) {
                    if (!CreangerLoginFlowHelper.proceedWithUser(r.session.user, getParentActivity())) {
                        showError(LocaleController.getString(R.string.CreangerGoogleSignInFailed));
                    }
                } else {
                    showError(LocaleController.getString(R.string.CreangerGoogleSignInFailed));
                }
            }
            @Override public void onError(CreangerApiException e, Throwable t) { setBusy(false); onErr(e, t); }
        });
    }

    private void onErr(CreangerApiException error, Throwable io) {
        AndroidUtilities.runOnUIThread(() -> {
            // Never drop failures silently: even when the host activity is gone
            // (rotation/background during the network call) the inline error
            // must still be shown if our views are alive, so the user is never
            // left on a dead screen with no feedback.
            if (errorTextView == null || fragmentView == null) return;
            if (getParentActivity() != null && getParentActivity().isFinishing()) return;
            if (error != null && error.is(ApiError.INVALID_CREDENTIALS)) {
                showError(LocaleController.getString(R.string.CreangerErrorInvalidCredentials));
                if (identifierField != null) identifierField.setErrorState(true);
                if (passwordField != null) passwordField.setErrorState(true);
            } else if (error != null && error.is(ApiError.RATE_LIMITED)) {
                showError(LocaleController.getString(R.string.CreangerErrorRateLimited));
            } else if (io != null) {
                showError(LocaleController.getString(R.string.CreangerErrorNetworkError));
            } else {
                showError(CreangerLoginFlowHelper.safeErrorMessage(error, io));
            }
        });
    }

    private void setBusy(boolean b) {
        busy = b;
        // Callbacks can land after the view hierarchy is torn down (rotation /
        // background during the network call) — never crash there, just record
        // the flag so a re-created view starts in the right state.
        if (fragmentView == null) return;
        if (loginButton != null) loginButton.setLoading(b);
        if (googleButton != null) {
            googleButton.setEnabled(!b && !googlePickerInFlight);
            googleButton.setAlpha(b ? 0.5f : 1f);
        }
        if (identifierField != null && identifierField.field != null) identifierField.field.setEnabled(!b);
        if (passwordField != null && passwordField.field != null) passwordField.field.setEnabled(!b);
        if (passwordToggle != null) passwordToggle.setEnabled(!b);
    }

    private void showError(String msg) { CreangerAuthTheme.showError(errorTextView, msg); }
    private void hideError() { CreangerAuthTheme.hideError(errorTextView); }

    @Override public boolean onBackPressed(boolean inv) { if (inv) finishFragment(); return true; }
}
