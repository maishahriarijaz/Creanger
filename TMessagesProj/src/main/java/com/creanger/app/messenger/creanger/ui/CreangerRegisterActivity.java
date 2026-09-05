package com.creanger.app.messenger.creanger.ui;

import static com.creanger.app.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Color;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import com.creanger.app.messenger.AndroidUtilities;
import com.creanger.app.messenger.ApplicationLoader;
import com.creanger.app.messenger.LocaleController;
import com.creanger.app.messenger.R;
import com.creanger.app.messenger.creanger.CreangerAuth;
import com.creanger.app.messenger.creanger.CreangerUsernamePolicy;
import com.creanger.app.messenger.creanger.api.CreangerApiException;
import com.creanger.app.messenger.creanger.auth.CreangerAuthAsync;
import com.creanger.app.messenger.creanger.model.ApiError;
import com.creanger.app.messenger.creanger.model.AuthModels.SignupResult;
import com.creanger.app.ui.ActionBar.ActionBar;
import com.creanger.app.ui.ActionBar.BaseFragment;
import com.creanger.app.ui.ActionBar.Theme;
import com.creanger.app.ui.ActionBar.ThemeDescription;
import com.creanger.app.ui.Components.LayoutHelper;
import com.creanger.app.ui.Components.SimpleThemeDescription;
import com.creanger.app.ui.Components.SizeNotifierFrameLayout;

import java.util.ArrayList;

/**
 * Email + password register screen (icon fields, password strength meter,
 * terms &amp; privacy checkbox). Every colour is read from the app's theme
 * engine through {@link CreangerAuthTheme}, so this screen follows the user's
 * chosen day/night or custom theme like the rest of the app.
 *
 * Auth logic (username availability polling, registerWithPassword, error
 * mapping) is unchanged. There is no OTP step: when the backend creates the
 * account without returning a session (email confirmation enabled), the screen
 * says so inline and returns to the login page.
 */
public class CreangerRegisterActivity extends BaseFragment {

    private static final long AVAILABILITY_DEBOUNCE_MS = 300;
    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final long FINISH_AFTER_SIGNUP_MS = 2200;

    /**
     * Inline-message kinds. The kind is recorded instead of a raw colour so a
     * theme change can re-tint the label without re-running any availability
     * logic (no debounce reset, no extra network call).
     */
    private static final int STATUS_NEUTRAL = 0;
    private static final int STATUS_SUCCESS = 1;
    private static final int STATUS_ERROR = 2;

    private CreangerAuthTheme.FieldWithIconCompat emailField;
    private CreangerAuthTheme.FieldWithIconCompat usernameField;
    private CreangerAuthTheme.FieldWithIconCompat passwordField;
    private CreangerAuthTheme.FieldWithIconCompat confirmPasswordField;
    private ImageView passwordToggle;
    private ImageView confirmToggle;
    private TextView usernameStatus;
    private CreangerAuthTheme.StrengthMeter strengthMeter;
    private CreangerAuthTheme.TermsRow termsRow;
    private CreangerAuthButton registerButton;
    private TextView loginLink;
    private TextView errorTextView;
    private CreangerAuthCard card;
    private SizeNotifierFrameLayout root;
    private TextView logo;
    private TextView subtitle;

    private boolean busy;
    private boolean passwordVisible;
    private boolean confirmVisible;
    private boolean normalizingUsername;
    private boolean usernameAvailable;
    private int availabilityGeneration;
    private int usernameStatusKind = STATUS_NEUTRAL;
    private int noticeKind = STATUS_ERROR;
    private String lastCheckedUsername;
    private String lastAvailableUsername;
    private final Runnable availabilityRunnable = this::requestAvailability;
    private final Runnable finishAfterSignupRunnable = () -> {
        if (getParentActivity() == null || getParentActivity().isFinishing()) return;
        finishFragment();
    };
    private CreangerAuth creangerAuth;

    @Override
    public View createView(Context context) {
        creangerAuth = CreangerAuth.getInstance(ApplicationLoader.applicationContext);

        actionBar.setTitle(LocaleController.getString(R.string.CreangerRegisterTitle));
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) onBackPressed(true); }
        });

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
        // hPad is in pixels and clamps content to MAX_CONTENT_WIDTH_DP on wide
        // screens, so children stay MATCH_PARENT with no horizontal margins.
        content.setPadding(hPad, 0, hPad, dp(CreangerAuthTheme.GAP_XL_DP));
        scrollView.addView(content, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));

        // Brand header
        LinearLayout brandSection = new LinearLayout(context);
        brandSection.setOrientation(LinearLayout.VERTICAL);
        brandSection.setGravity(Gravity.CENTER_HORIZONTAL);
        brandSection.setPadding(0, dp(CreangerAuthTheme.GAP_XL_DP), 0, dp(CreangerAuthTheme.GAP_LG_DP));
        content.addView(brandSection, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));

        logo = new TextView(context);
        logo.setText("Creanger");
        logo.setTextSize(34);
        logo.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM));
        logo.setLetterSpacing(0.02f);
        logo.setTextColor(CreangerAuthTheme.textPrimary());
        logo.setGravity(Gravity.CENTER);
        brandSection.addView(logo, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));

        subtitle = CreangerAuthTheme.captionText(context, LocaleController.getString(R.string.CreangerRegisterSubtitle), 15);
        subtitle.setPadding(0, dp(CreangerAuthTheme.GAP_DP), 0, 0);
        brandSection.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));

        // Card container
        card = new CreangerAuthCard(context);
        LinearLayout cardContent = new LinearLayout(context);
        cardContent.setOrientation(LinearLayout.VERTICAL);
        cardContent.setGravity(Gravity.CENTER_HORIZONTAL);
        int cardPad = dp(CreangerAuthTheme.GAP_XL_DP);
        cardContent.setPadding(cardPad, cardPad, cardPad, cardPad);
        card.addView(cardContent, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        content.addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));

        // Email field
        emailField = CreangerAuthTheme.iconFieldCompat(context, R.drawable.ic_mail_outline, R.string.CreangerEmail,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, EditorInfo.IME_ACTION_NEXT);
        emailField.field.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_NEXT) usernameField.field.requestFocus();
            return false;
        });
        cardContent.addView(emailField.row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, CreangerAuthTheme.FIELD_HEIGHT_DP));

        // Username field with live availability
        usernameField = CreangerAuthTheme.iconFieldCompat(context, R.drawable.ic_user_outline, R.string.CreangerUsername,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, EditorInfo.IME_ACTION_NEXT);
        usernameField.field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                if (normalizingUsername) return;
                String normalized = CreangerUsernamePolicy.normalize(s.toString());
                if (!normalized.equals(s.toString())) {
                    normalizingUsername = true;
                    s.replace(0, s.length(), normalized);
                    usernameField.field.setSelection(normalized.length());
                    normalizingUsername = false;
                }
                onUsernameChanged();
            }
        });
        usernameField.field.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_NEXT) passwordField.field.requestFocus();
            return false;
        });
        cardContent.addView(usernameField.row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, CreangerAuthTheme.FIELD_HEIGHT_DP,
                0, 0, CreangerAuthTheme.GAP_DP, 0, 0));

        // Username status
        usernameStatus = new TextView(context);
        usernameStatus.setTextSize(13);
        usernameStatus.setVisibility(View.GONE);
        usernameStatus.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        usernameStatus.setPadding(dp(4), dp(6), dp(4), 0);
        usernameStatus.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM));
        cardContent.addView(usernameStatus, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Password field
        passwordField = CreangerAuthTheme.iconFieldCompat(context, R.drawable.ic_lock_outline, R.string.CreangerPassword,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, EditorInfo.IME_ACTION_NEXT);
        passwordField.field.setTransformationMethod(PasswordTransformationMethod.getInstance());
        passwordField.field.setOnEditorActionListener((v, a, e) -> { if (a == EditorInfo.IME_ACTION_NEXT) confirmPasswordField.field.requestFocus(); return false; });
        passwordField.field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                strengthMeter.update(s.toString());
                updateRegisterButtonState();
            }
        });
        passwordToggle = createPasswordToggle(context);
        passwordToggle.setOnClickListener(v -> {
            passwordVisible = !passwordVisible;
            passwordField.field.setTransformationMethod(passwordVisible ? HideReturnsTransformationMethod.getInstance() : PasswordTransformationMethod.getInstance());
            passwordToggle.setImageResource(passwordVisible ? R.drawable.ic_eye : R.drawable.ic_eye_off);
            passwordToggle.setContentDescription(LocaleController.getString(passwordVisible ? R.string.CreangerPasswordHide : R.string.CreangerPasswordShow));
            passwordField.field.setSelection(passwordField.field.length());
        });
        passwordField.row.addView(passwordToggle, LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL));
        cardContent.addView(passwordField.row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, CreangerAuthTheme.FIELD_HEIGHT_DP,
                0, 0, CreangerAuthTheme.GAP_DP, 0, 0));

        // Password strength meter
        strengthMeter = CreangerAuthTheme.strengthMeter(context);
        strengthMeter.row.setPadding(dp(4), dp(6), dp(4), 0);
        cardContent.addView(strengthMeter.row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Confirm password field
        confirmPasswordField = CreangerAuthTheme.iconFieldCompat(context, R.drawable.ic_lock_outline, R.string.CreangerConfirmPassword,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, EditorInfo.IME_ACTION_DONE);
        confirmPasswordField.field.setTransformationMethod(PasswordTransformationMethod.getInstance());
        confirmPasswordField.field.setOnEditorActionListener((v, a, e) -> { if (a == EditorInfo.IME_ACTION_DONE) attemptRegister(); return false; });
        confirmToggle = createPasswordToggle(context);
        confirmToggle.setOnClickListener(v -> {
            confirmVisible = !confirmVisible;
            confirmPasswordField.field.setTransformationMethod(confirmVisible ? HideReturnsTransformationMethod.getInstance() : PasswordTransformationMethod.getInstance());
            confirmToggle.setImageResource(confirmVisible ? R.drawable.ic_eye : R.drawable.ic_eye_off);
            confirmToggle.setContentDescription(LocaleController.getString(confirmVisible ? R.string.CreangerPasswordHide : R.string.CreangerPasswordShow));
            confirmPasswordField.field.setSelection(confirmPasswordField.field.length());
        });
        confirmPasswordField.row.addView(confirmToggle, LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL));
        cardContent.addView(confirmPasswordField.row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, CreangerAuthTheme.FIELD_HEIGHT_DP,
                0, 0, CreangerAuthTheme.GAP_DP, 0, 0));

        // Terms & privacy checkbox
        termsRow = CreangerAuthTheme.termsCheckboxRow(context, LocaleController.getString(R.string.CreangerTermsCheckboxText));
        termsRow.checkbox.setOnCheckedChangeListener((buttonView, isChecked) -> updateRegisterButtonState());
        cardContent.addView(termsRow.row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                0, 0, CreangerAuthTheme.GAP_LG_DP, 0, 0));

        // Inline notice (errors, and the "confirm your email" success message)
        errorTextView = CreangerAuthTheme.errorText(context);
        errorTextView.setGravity(Gravity.CENTER);
        cardContent.addView(errorTextView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Register button
        registerButton = new CreangerAuthButton(context, LocaleController.getString(R.string.CreangerRegisterButton),
                CreangerAuthTheme.accent(), CreangerAuthTheme.onAccent());
        registerButton.setOnClickListener(v -> attemptRegister());
        cardContent.addView(registerButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, CreangerAuthTheme.BUTTON_HEIGHT_DP,
                0, 0, CreangerAuthTheme.GAP_DP, 0, 0));

        // Login link
        loginLink = CreangerAuthTheme.linkText(context, LocaleController.getString(R.string.CreangerAlreadyHaveAccount), 14);
        loginLink.setPadding(dp(CreangerAuthTheme.GAP_LG_DP), dp(CreangerAuthTheme.GAP_DP), dp(CreangerAuthTheme.GAP_LG_DP), dp(CreangerAuthTheme.GAP_DP));
        applyLinkRipple(loginLink);
        loginLink.setOnClickListener(v -> finishFragment());
        cardContent.addView(loginLink, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL, 0, CreangerAuthTheme.GAP_DP, 0, 0));

        fragmentView = root;

        CreangerAuthTheme.slideUp(brandSection, 0);
        CreangerAuthTheme.slideUp(card, 100);

        emailField.field.requestFocus();
        AndroidUtilities.runOnUIThread(() -> AndroidUtilities.showKeyboard(emailField.field));
        updateRegisterButtonState();

        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        // Lets the ScrollView lift the focused field above the keyboard.
        AndroidUtilities.requestAdjustResize(getParentActivity(), classGuid);
    }

    private static ImageView createPasswordToggle(Context c) {
        ImageView t = new ImageView(c);
        t.setImageResource(R.drawable.ic_eye_off);
        t.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        t.setContentDescription(LocaleController.getString(R.string.CreangerPasswordShow));
        applyToggleTint(t);
        return t;
    }

    private static void applyToggleTint(ImageView t) {
        t.setColorFilter(CreangerAuthTheme.textTertiary());
        t.setBackground(Theme.createSelectorDrawable(
                Theme.multAlpha(CreangerAuthTheme.textPrimary(), 0.10f), Theme.RIPPLE_MASK_CIRCLE_20DP));
    }

    private static void applyLinkRipple(TextView v) {
        v.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(CreangerAuthTheme.RADIUS_SM),
                Color.TRANSPARENT, Theme.multAlpha(CreangerAuthTheme.accent(), 0.12f)));
    }

    private static int statusColor(int kind) {
        switch (kind) {
            case STATUS_SUCCESS:
                return CreangerAuthTheme.successColor();
            case STATUS_ERROR:
                return CreangerAuthTheme.errorColor();
            default:
                return CreangerAuthTheme.textTertiary();
        }
    }

    /** Re-applies every colour from the app theme; used as the ThemeDescription delegate. */
    private void onThemeChanged() {
        if (root == null) return;
        root.setBackgroundColor(CreangerAuthTheme.screenBg());

        actionBar.setBackgroundColor(Theme.getColor(Theme.key_actionBarDefault));
        actionBar.setTitleColor(Theme.getColor(Theme.key_actionBarDefaultTitle));
        actionBar.setItemsColor(Theme.getColor(Theme.key_actionBarDefaultIcon), false);
        actionBar.setItemsBackgroundColor(Theme.getColor(Theme.key_actionBarDefaultSelector), false);

        logo.setTextColor(CreangerAuthTheme.textPrimary());
        subtitle.setTextColor(CreangerAuthTheme.textSecondary());
        card.refreshTheme();

        registerButton.setColors(CreangerAuthTheme.accent(), CreangerAuthTheme.onAccent());
        emailField.refreshTheme();
        usernameField.refreshTheme();
        passwordField.refreshTheme();
        confirmPasswordField.refreshTheme();
        applyToggleTint(passwordToggle);
        applyToggleTint(confirmToggle);
        strengthMeter.refreshTheme();
        termsRow.refreshTheme();

        loginLink.setTextColor(CreangerAuthTheme.accent());
        applyLinkRipple(loginLink);

        // Re-tint the inline notice and the username status from their recorded
        // kinds - no validation or availability logic is re-run here.
        if (errorTextView.getVisibility() == View.VISIBLE) {
            errorTextView.setTextColor(statusColor(noticeKind));
        }
        if (usernameStatus.getVisibility() == View.VISIBLE) {
            usernameStatus.setTextColor(statusColor(usernameStatusKind));
        }
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
                Theme.key_windowBackgroundWhiteGreenText2,
                Theme.key_color_orange,
                Theme.key_divider,
                Theme.key_actionBarDefault,
                Theme.key_actionBarDefaultTitle,
                Theme.key_actionBarDefaultIcon,
                Theme.key_actionBarDefaultSelector);
    }

    @Override
    public boolean isLightStatusBar() {
        return ColorUtils.calculateLuminance(Theme.getColor(Theme.key_actionBarDefault, null, true)) > 0.7f;
    }

    private void onUsernameChanged() {
        availabilityGeneration++;
        AndroidUtilities.cancelRunOnUIThread(availabilityRunnable);
        hideError();

        String value = usernameField.field.getText().toString();
        CreangerUsernamePolicy.Result result = CreangerUsernamePolicy.validateNormalized(value);
        if (!result.ok()) {
            usernameAvailable = false;
            usernameField.setErrorState(true);
            showUsernameStatus(STATUS_ERROR, statusMessageFor(result));
            updateRegisterButtonState();
            return;
        }
        if (value.equals(lastAvailableUsername)) {
            usernameAvailable = true;
            usernameField.setErrorState(false);
            showUsernameStatus(STATUS_SUCCESS, LocaleController.formatString(R.string.CreangerUsernameAvailable, "@" + value));
            updateRegisterButtonState();
            return;
        }
        usernameAvailable = false;
        usernameField.setErrorState(false);
        showUsernameStatus(STATUS_NEUTRAL, LocaleController.getString(R.string.CreangerUsernameChecking));
        AndroidUtilities.runOnUIThread(availabilityRunnable, AVAILABILITY_DEBOUNCE_MS);
        updateRegisterButtonState();
    }

    private void requestAvailability() {
        final String value = usernameField.field.getText().toString();
        CreangerUsernamePolicy.Result local = CreangerUsernamePolicy.validateNormalized(value);
        if (!local.ok() || value.equals(lastCheckedUsername)) return;
        lastCheckedUsername = value;
        final int generation = ++availabilityGeneration;
        showUsernameStatus(STATUS_NEUTRAL, LocaleController.getString(R.string.CreangerUsernameChecking));
        creangerAuth.getAsync().checkUsername(value, new CreangerAuthAsync.AuthCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean available) {
                if (generation != availabilityGeneration || !value.equals(usernameField.field.getText().toString())) return;
                if (Boolean.TRUE.equals(available)) {
                    usernameAvailable = true;
                    lastAvailableUsername = value;
                    lastCheckedUsername = null;
                    usernameField.setErrorState(false);
                    showUsernameStatus(STATUS_SUCCESS, LocaleController.formatString(R.string.CreangerUsernameAvailable, "@" + value));
                } else {
                    usernameAvailable = false;
                    usernameField.setErrorState(true);
                    showUsernameStatus(STATUS_ERROR, LocaleController.getString(R.string.CreangerErrorUsernameTaken));
                }
                updateRegisterButtonState();
            }

            @Override
            public void onError(CreangerApiException error, Throwable ioError) {
                if (generation != availabilityGeneration) return;
                usernameAvailable = false;
                usernameField.setErrorState(true);
                showUsernameStatus(STATUS_ERROR,
                        error != null && error.is(ApiError.RATE_LIMITED)
                                ? LocaleController.getString(R.string.CreangerErrorRateLimited)
                                : LocaleController.getString(R.string.CreangerErrorNetworkError));
                updateRegisterButtonState();
            }
        });
    }

    private String statusMessageFor(CreangerUsernamePolicy.Result result) {
        switch (result.code) {
            case CreangerUsernamePolicy.Result.EMPTY:
            case CreangerUsernamePolicy.Result.TOO_SHORT:
                return LocaleController.getString(R.string.CreangerUsernameTooShort);
            case CreangerUsernamePolicy.Result.TOO_LONG:
                return LocaleController.getString(R.string.CreangerUsernameTooLong);
            case CreangerUsernamePolicy.Result.BAD_PERIOD_PLACEMENT:
                return LocaleController.getString(R.string.CreangerUsernameBadPeriods);
            default:
                return LocaleController.getString(R.string.CreangerUsernameInvalidCharacters);
        }
    }

    private void showUsernameStatus(int kind, CharSequence text) {
        usernameStatusKind = kind;
        usernameStatus.setText(text);
        usernameStatus.setTextColor(statusColor(kind));
        if (usernameStatus.getVisibility() != View.VISIBLE) {
            usernameStatus.setAlpha(0f);
            usernameStatus.setTranslationY(-dp(8));
            usernameStatus.setVisibility(View.VISIBLE);
            usernameStatus.animate().alpha(1f).translationY(0)
                    .setDuration(CreangerAuthTheme.DURATION_NORMAL)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        } else {
            usernameStatus.animate().cancel();
            usernameStatus.setAlpha(1f);
            usernameStatus.setTranslationY(0);
        }
    }

    private void updateRegisterButtonState() {
        boolean ready = isFormValidLocally();
        float targetAlpha = ready ? 1f : 0.45f;
        if (registerButton.getAlpha() != targetAlpha && !busy) {
            registerButton.animate().alpha(targetAlpha).setDuration(160).start();
        }
    }

    private boolean isFormValidLocally() {
        if (emailField.field.getText().toString().trim().isEmpty()) return false;
        String username = usernameField.field.getText().toString();
        if (!CreangerUsernamePolicy.validateNormalized(username).ok()) return false;
        if (!usernameAvailable) return false;
        String pw = passwordField.field.getText().toString();
        if (pw.length() < MIN_PASSWORD_LENGTH) return false;
        if (!confirmPasswordField.field.getText().toString().equals(pw)) return false;
        return termsRow.checkbox.isChecked();
    }

    private void attemptRegister() {
        if (busy) return;
        hideError();

        String email = emailField.field.getText().toString().trim();
        String username = usernameField.field.getText().toString();
        String pw = passwordField.field.getText().toString();
        String confirm = confirmPasswordField.field.getText().toString();

        boolean hasError = false;
        if (email.isEmpty() || !isValidEmail(email)) {
            showError(LocaleController.getString(R.string.CreangerErrorEmptyEmail));
            emailField.setErrorState(true);
            hasError = true;
        }
        CreangerUsernamePolicy.Result policy = CreangerUsernamePolicy.validateNormalized(username);
        if (!policy.ok()) {
            if (!hasError) showError(statusMessageFor(policy));
            usernameField.setErrorState(true);
            hasError = true;
        }
        if (!usernameAvailable) {
            if (!hasError) showError(LocaleController.getString(R.string.CreangerErrorUsernameTaken));
            usernameField.setErrorState(true);
            hasError = true;
        }
        if (pw.length() < MIN_PASSWORD_LENGTH) {
            if (!hasError) showError(LocaleController.getString(R.string.CreangerErrorPasswordTooShort));
            passwordField.setErrorState(true);
            hasError = true;
        }
        if (!confirm.equals(pw)) {
            if (!hasError) showError(LocaleController.getString(R.string.CreangerErrorPasswordMismatch));
            confirmPasswordField.setErrorState(true);
            hasError = true;
        }
        if (!termsRow.checkbox.isChecked()) {
            if (!hasError) showError(LocaleController.getString(R.string.CreangerErrorTermsRequired));
            hasError = true;
        }
        if (hasError) return;

        setBusy(true);

        creangerAuth.getAsync().registerWithPassword(email, username, pw,
                new CreangerAuthAsync.AuthCallback<SignupResult>() {
                    @Override
                    public void onSuccess(SignupResult result) {
                        setBusy(false);
                        AndroidUtilities.runOnUIThread(() -> {
                            if (getParentActivity() == null || getParentActivity().isFinishing()) return;
                            if (result != null && result.session != null && result.session.user != null) {
                                CreangerLoginFlowHelper.proceedWithUser(result.session.user, getParentActivity());
                            } else if (result != null) {
                                // Account created, but the backend returned no
                                // session (email confirmation is enabled). There
                                // is no OTP step: say so inline, lock the form so
                                // it cannot be submitted twice, then go back to
                                // the login screen.
                                showNotice(LocaleController.getString(R.string.CreangerRegisterConfirmEmail), STATUS_SUCCESS);
                                lockFormAfterSignup();
                                AndroidUtilities.runOnUIThread(finishAfterSignupRunnable, FINISH_AFTER_SIGNUP_MS);
                            } else {
                                showError(LocaleController.getString(R.string.CreangerErrorGeneric));
                            }
                        });
                    }

                    @Override
                    public void onError(CreangerApiException error, Throwable ioError) {
                        setBusy(false);
                        AndroidUtilities.runOnUIThread(() -> {
                            if (getParentActivity() == null || getParentActivity().isFinishing()) return;
                            if (error != null && error.is(ApiError.EMAIL_ALREADY_REGISTERED)) {
                                showError(LocaleController.getString(R.string.CreangerErrorEmailRegistered));
                                emailField.setErrorState(true);
                            } else if (error != null && error.is(ApiError.VALIDATION_ERROR)) {
                                showError(LocaleController.getString(R.string.CreangerErrorPasswordTooShort));
                                passwordField.setErrorState(true);
                            } else if (error != null && error.is(ApiError.RATE_LIMITED)) {
                                showError(LocaleController.getString(R.string.CreangerErrorRateLimited));
                            } else {
                                showError(CreangerLoginFlowHelper.safeErrorMessage(error, ioError));
                            }
                        });
                    }
                });
    }

    private static boolean isValidEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 0 || at == email.length() - 1) return false;
        int dot = email.lastIndexOf('.');
        return dot > at + 1 && dot < email.length() - 1;
    }

    private void setBusy(boolean loading) {
        busy = loading;
        registerButton.setLoading(loading);
        emailField.field.setEnabled(!loading);
        usernameField.field.setEnabled(!loading);
        passwordField.field.setEnabled(!loading);
        confirmPasswordField.field.setEnabled(!loading);
        passwordToggle.setEnabled(!loading);
        confirmToggle.setEnabled(!loading);
        loginLink.setEnabled(!loading);
        if (!loading) updateRegisterButtonState();
    }

    /**
     * Signup succeeded without a session - keep the form visible but inert
     * while the confirm-email notice is on screen.
     */
    private void lockFormAfterSignup() {
        busy = true;
        emailField.field.setEnabled(false);
        usernameField.field.setEnabled(false);
        passwordField.field.setEnabled(false);
        confirmPasswordField.field.setEnabled(false);
        passwordToggle.setEnabled(false);
        confirmToggle.setEnabled(false);
        termsRow.checkbox.setEnabled(false);
        registerButton.setEnabled(false);
        registerButton.animate().alpha(0.45f).setDuration(CreangerAuthTheme.DURATION_FAST).start();
        AndroidUtilities.hideKeyboard(root);
    }

    private void showError(String message) {
        noticeKind = STATUS_ERROR;
        CreangerAuthTheme.showError(errorTextView, message);
    }

    private void showNotice(String message, int kind) {
        noticeKind = kind;
        CreangerAuthTheme.showError(errorTextView, message);
        errorTextView.setTextColor(statusColor(kind));
    }

    private void hideError() { CreangerAuthTheme.hideError(errorTextView); }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        AndroidUtilities.cancelRunOnUIThread(availabilityRunnable);
        AndroidUtilities.cancelRunOnUIThread(finishAfterSignupRunnable);
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (invoked) finishFragment();
        return true;
    }
}
