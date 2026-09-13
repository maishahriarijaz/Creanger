package com.creanger.app.messenger.creanger.ui;

import static com.creanger.app.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;
import androidx.core.widget.CompoundButtonCompat;

import com.creanger.app.messenger.AndroidUtilities;
import com.creanger.app.messenger.ApplicationLoader;
import com.creanger.app.messenger.LocaleController;
import com.creanger.app.messenger.R;
import com.creanger.app.messenger.creanger.CreangerAuth;
import com.creanger.app.messenger.creanger.CreangerUsernamePolicy;
import com.creanger.app.messenger.creanger.api.CreangerApiException;
import com.creanger.app.messenger.creanger.auth.CreangerAuthAsync;
import com.creanger.app.messenger.creanger.model.ApiError;
import com.creanger.app.messenger.creanger.model.AuthModels.AuthSession;
import com.creanger.app.ui.ActionBar.ActionBar;
import com.creanger.app.ui.ActionBar.BaseFragment;
import com.creanger.app.ui.ActionBar.Theme;
import com.creanger.app.ui.ActionBar.ThemeDescription;
import com.creanger.app.ui.Components.LayoutHelper;
import com.creanger.app.ui.Components.SimpleThemeDescription;
import com.creanger.app.ui.Components.SizeNotifierFrameLayout;

import java.util.ArrayList;

/**
 * Google account setup ("finish creating your account") screen - the
 * create-account step reached when {@code googleAuth} reports that the verified
 * Google email has no Creanger account yet. The email is already verified by
 * Google, so there is no OTP step: full name + display name + username (with
 * live availability) + password + agreement checkbox, then
 * {@code googleAuthComplete} and straight into the chat list.
 *
 * Every colour comes from the app's theme engine through
 * {@link CreangerAuthTheme}. Auth logic (username availability,
 * googleAuthComplete, error mapping) is unchanged.
 */
public class CreangerGoogleAccountSetupActivity extends BaseFragment {

    private static final String ARG_GOOGLE_EMAIL = "google_email";
    private static final String ARG_GOOGLE_NAME = "google_name";
    private static final long AVAILABILITY_DEBOUNCE_MS = 300;
    private static final int MIN_PASSWORD_LENGTH = 8;

    /**
     * Username-status kinds. The kind is recorded instead of a raw colour so a
     * theme change can re-tint the label without re-running any availability
     * logic (no debounce reset, no extra network call).
     */
    private static final int STATUS_NEUTRAL = 0;
    private static final int STATUS_SUCCESS = 1;
    private static final int STATUS_ERROR = 2;

    private CreangerAuthTheme.FieldWithIconCompat nameField;
    private CreangerAuthTheme.FieldWithIconCompat displayNameField;
    private CreangerAuthTheme.FieldWithIconCompat usernameField;
    private CreangerAuthTheme.FieldWithIconCompat passwordField;
    private CreangerAuthTheme.FieldWithIconCompat confirmPasswordField;
    private ImageView passwordToggle;
    private ImageView confirmToggle;
    private TextView usernameStatus;
    private CreangerAuthTheme.StrengthMeter strengthMeter;
    private CreangerAuthButton createButton;
    private CheckBox agreementCheck;
    private TextView agreementText;
    private TextView errorTextView;
    private CreangerAuthCard card;
    private SizeNotifierFrameLayout root;
    private CreangerAuthTheme.AccountSummary accountCard;
    private TextView logo;
    private TextView subtitle;

    private String googleEmail;
    private boolean busy;
    private boolean passwordVisible;
    private boolean confirmVisible;
    private boolean agreementChecked;
    private boolean normalizingUsername;
    private boolean usernameAvailable;
    private int availabilityGeneration;
    private int usernameStatusKind = STATUS_NEUTRAL;
    private String lastCheckedUsername;
    private String lastAvailableUsername;
    private final Runnable availabilityRunnable = this::requestAvailability;

    private CreangerAuth creangerAuth;

    public CreangerGoogleAccountSetupActivity() {}

    public CreangerGoogleAccountSetupActivity(String googleEmail, String googleName) {
        Bundle args = new Bundle();
        args.putString(ARG_GOOGLE_EMAIL, googleEmail == null ? "" : googleEmail);
        args.putString(ARG_GOOGLE_NAME, googleName == null ? "" : googleName);
        this.arguments = args;
    }

    @Override
    public View createView(Context context) {
        creangerAuth = CreangerAuth.getInstance(ApplicationLoader.applicationContext);
        if (getArguments() != null) {
            googleEmail = getArguments().getString(ARG_GOOGLE_EMAIL, "");
        }
        String prefillName = getArguments() != null ? getArguments().getString(ARG_GOOGLE_NAME, "") : "";

        actionBar.setTitle(LocaleController.getString(R.string.CreangerGoogleSetupTitle));
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) onBackPressed(true); }
        });

        int hPad = CreangerAuthTheme.contentHPad();

        root = new SizeNotifierFrameLayout(context);
        root.setBackgroundColor(CreangerAuthTheme.screenBg());
        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        scrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);
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

        subtitle = CreangerAuthTheme.captionText(context, LocaleController.getString(R.string.CreangerSetupSubtitleGeneric), 14);
        subtitle.setPadding(0, dp(CreangerAuthTheme.GAP_DP), 0, 0);
        brandSection.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));

        // Verified Google account summary card
        String initials = initialsFor(prefillName, googleEmail);
        accountCard = CreangerAuthTheme.accountSummaryCard(context, initials,
                googleEmail == null ? "" : googleEmail, LocaleController.getString(R.string.CreangerVerifiedWithGoogle));
        content.addView(accountCard.card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL, 0, CreangerAuthTheme.GAP_DP, 0, 0));

        // Card container for form
        card = new CreangerAuthCard(context);
        LinearLayout cardContent = new LinearLayout(context);
        cardContent.setOrientation(LinearLayout.VERTICAL);
        cardContent.setGravity(Gravity.CENTER_HORIZONTAL);
        int cardPad = dp(CreangerAuthTheme.GAP_XL_DP);
        cardContent.setPadding(cardPad, cardPad, cardPad, cardPad);
        card.addView(cardContent, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        content.addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL, 0, CreangerAuthTheme.GAP_LG_DP, 0, 0));

        // Full name field
        nameField = CreangerAuthTheme.iconFieldCompat(context, R.drawable.ic_user_outline, R.string.CreangerFullNameLabel,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, EditorInfo.IME_ACTION_NEXT);
        if (prefillName != null && !prefillName.isEmpty()) {
            nameField.field.setText(prefillName);
            nameField.field.setSelection(nameField.field.length());
        }
        nameField.field.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_NEXT) displayNameField.field.requestFocus();
            return false;
        });
        nameField.field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                nameField.setErrorState(false);
                updateCreateButtonState();
            }
        });
        cardContent.addView(nameField.row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, CreangerAuthTheme.FIELD_HEIGHT_DP, 0, 0, 0, 0, 0));

        // Display name field
        displayNameField = CreangerAuthTheme.iconFieldCompat(context, R.drawable.ic_user_outline, R.string.CreangerDisplayNameLabel,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, EditorInfo.IME_ACTION_NEXT);
        displayNameField.field.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_NEXT) usernameField.field.requestFocus();
            return false;
        });
        displayNameField.field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                displayNameField.setErrorState(false);
                updateCreateButtonState();
            }
        });
        cardContent.addView(displayNameField.row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, CreangerAuthTheme.FIELD_HEIGHT_DP, 0, 0, 0, 0, 0));

        // Username field with live availability
        usernameField = CreangerAuthTheme.iconFieldCompat(context, R.drawable.ic_layers_outline, R.string.CreangerUsername,
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
        cardContent.addView(usernameField.row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, CreangerAuthTheme.FIELD_HEIGHT_DP, 0, 0, CreangerAuthTheme.GAP_DP, 0, 0));

        // Username status
        usernameStatus = new TextView(context);
        usernameStatus.setTextSize(13);
        usernameStatus.setVisibility(View.GONE);
        usernameStatus.setPadding(dp(4), dp(6), dp(4), 0);
        usernameStatus.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        usernameStatus.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM));
        cardContent.addView(usernameStatus, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Password field
        passwordField = CreangerAuthTheme.iconFieldCompat(context, R.drawable.ic_lock_outline, R.string.CreangerNewPassword,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, EditorInfo.IME_ACTION_NEXT);
        passwordField.field.setTransformationMethod(PasswordTransformationMethod.getInstance());
        passwordField.field.setOnEditorActionListener((v, a, e) -> { if (a == EditorInfo.IME_ACTION_NEXT) confirmPasswordField.field.requestFocus(); return false; });
        passwordField.field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                strengthMeter.update(s.toString());
                updateCreateButtonState();
            }
        });
        passwordToggle = createToggle(context);
        passwordToggle.setOnClickListener(v -> {
            passwordVisible = !passwordVisible;
            passwordField.field.setTransformationMethod(passwordVisible ? HideReturnsTransformationMethod.getInstance() : PasswordTransformationMethod.getInstance());
            passwordToggle.setImageResource(passwordVisible ? R.drawable.ic_eye : R.drawable.ic_eye_off);
            passwordField.field.setSelection(passwordField.field.length());
        });
        passwordField.row.addView(passwordToggle, LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL));
        cardContent.addView(passwordField.row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, CreangerAuthTheme.FIELD_HEIGHT_DP, 0, 0, CreangerAuthTheme.GAP_DP, 0, 0));

        // Password strength meter
        strengthMeter = CreangerAuthTheme.strengthMeter(context);
        strengthMeter.row.setPadding(dp(4), dp(6), dp(4), 0);
        cardContent.addView(strengthMeter.row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Confirm password field
        confirmPasswordField = CreangerAuthTheme.iconFieldCompat(context, R.drawable.ic_lock_outline, R.string.CreangerConfirmPassword,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, EditorInfo.IME_ACTION_DONE);
        confirmPasswordField.field.setTransformationMethod(PasswordTransformationMethod.getInstance());
        confirmPasswordField.field.setOnEditorActionListener((v, a, e) -> { if (a == EditorInfo.IME_ACTION_DONE) attemptCreate(); return false; });
        confirmPasswordField.field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                confirmPasswordField.setErrorState(false);
                updateCreateButtonState();
            }
        });
        confirmToggle = createToggle(context);
        confirmToggle.setOnClickListener(v -> {
            confirmVisible = !confirmVisible;
            confirmPasswordField.field.setTransformationMethod(confirmVisible ? HideReturnsTransformationMethod.getInstance() : PasswordTransformationMethod.getInstance());
            confirmToggle.setImageResource(confirmVisible ? R.drawable.ic_eye : R.drawable.ic_eye_off);
            confirmPasswordField.field.setSelection(confirmPasswordField.field.length());
        });
        confirmPasswordField.row.addView(confirmToggle, LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL));
        cardContent.addView(confirmPasswordField.row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, CreangerAuthTheme.FIELD_HEIGHT_DP, 0, 0, CreangerAuthTheme.GAP_DP, 0, 0));

        // Agreement checkbox — unchecked by default; required to create the account.
        LinearLayout agreementRow = new LinearLayout(context);
        agreementRow.setOrientation(LinearLayout.HORIZONTAL);
        agreementRow.setGravity(Gravity.CENTER_VERTICAL);
        agreementRow.setPadding(dp(4), dp(CreangerAuthTheme.GAP_DP), dp(4), 0);
        cardContent.addView(agreementRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        agreementCheck = new CheckBox(context);
        agreementCheck.setChecked(false);
        agreementCheck.setClickable(true);
        agreementCheck.setFocusable(true);
        applyAgreementTint();
        agreementRow.addView(agreementCheck, LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL, 0, 0, 0, 0));

        agreementText = new TextView(context);
        agreementText.setText(LocaleController.getString(R.string.CreangerTermsCheckboxText));
        agreementText.setTextSize(13);
        agreementText.setTextColor(CreangerAuthTheme.textSecondary());
        agreementText.setClickable(true);
        agreementText.setFocusable(true);
        agreementText.setPadding(dp(4), dp(10), dp(4), dp(10));
        agreementText.setOnClickListener(v -> agreementCheck.toggle());
        agreementRow.addView(agreementText, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));

        agreementCheck.setOnCheckedChangeListener((buttonView, isChecked) -> {
            agreementChecked = isChecked;
            hideError();
            updateCreateButtonState();
        });

        // Error text
        errorTextView = CreangerAuthTheme.errorText(context);
        errorTextView.setGravity(Gravity.CENTER);
        cardContent.addView(errorTextView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Create button
        createButton = new CreangerAuthButton(context, LocaleController.getString(R.string.CreangerRegisterButton),
                CreangerAuthTheme.accent(), CreangerAuthTheme.onAccent());
        createButton.setOnClickListener(v -> attemptCreate());
        cardContent.addView(createButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, CreangerAuthTheme.BUTTON_HEIGHT_DP, 0, 0, CreangerAuthTheme.GAP_DP, 0, 0));

        fragmentView = root;

        CreangerAuthTheme.slideUp(brandSection, 0);
        CreangerAuthTheme.slideUp(accountCard.card, 60);
        CreangerAuthTheme.slideUp(card, 120);

        nameField.field.requestFocus();
        AndroidUtilities.runOnUIThread(() -> AndroidUtilities.showKeyboard(nameField.field));
        updateCreateButtonState();

        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        // Lets the ScrollView lift the focused field above the keyboard.
        AndroidUtilities.requestAdjustResize(getParentActivity(), classGuid);
    }

    private static String initialsFor(String name, String email) {
        String source = (name != null && !name.trim().isEmpty()) ? name.trim() : (email != null ? email : "");
        if (source.isEmpty()) return "?";
        String[] parts = source.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(2, parts.length); i++) {
            if (!parts[i].isEmpty()) sb.append(Character.toUpperCase(parts[i].charAt(0)));
        }
        return sb.length() > 0 ? sb.toString() : source.substring(0, 1).toUpperCase();
    }

    private static ImageView createToggle(Context c) {
        ImageView t = new ImageView(c);
        t.setImageResource(R.drawable.ic_eye_off);
        t.setColorFilter(CreangerAuthTheme.textTertiary());
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

    /** Tints the agreement checkbox with the theme accent in both states. */
    private void applyAgreementTint() {
        if (agreementCheck == null) return;
        int accent = CreangerAuthTheme.accent();
        int unchecked = CreangerAuthTheme.textTertiary();
        ColorStateList tint = new ColorStateList(
                new int[][]{
                        new int[]{android.R.attr.state_checked},
                        new int[]{-android.R.attr.state_checked}},
                new int[]{accent, unchecked});
        CompoundButtonCompat.setButtonTintList(agreementCheck, tint);
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
        accountCard.refreshTheme();
        card.refreshTheme();

        createButton.setColors(CreangerAuthTheme.accent(), CreangerAuthTheme.onAccent());
        nameField.refreshTheme();
        displayNameField.refreshTheme();
        usernameField.refreshTheme();
        passwordField.refreshTheme();
        confirmPasswordField.refreshTheme();
        applyToggleTint(passwordToggle);
        applyToggleTint(confirmToggle);
        applyAgreementTint();
        agreementText.setTextColor(CreangerAuthTheme.textSecondary());
        strengthMeter.refreshTheme();

        if (errorTextView.getVisibility() == View.VISIBLE) {
            errorTextView.setTextColor(CreangerAuthTheme.errorColor());
        }
        // Re-tint the username status from its recorded kind - no availability
        // logic is re-run here.
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
            updateCreateButtonState();
            return;
        }
        if (value.equals(lastAvailableUsername)) {
            usernameAvailable = true;
            usernameField.setErrorState(false);
            showUsernameStatus(STATUS_SUCCESS, LocaleController.formatString(R.string.CreangerUsernameAvailable, "@" + value));
            updateCreateButtonState();
            return;
        }
        usernameAvailable = false;
        usernameField.setErrorState(false);
        showUsernameStatus(STATUS_NEUTRAL, LocaleController.getString(R.string.CreangerUsernameChecking));
        AndroidUtilities.runOnUIThread(availabilityRunnable, AVAILABILITY_DEBOUNCE_MS);
        updateCreateButtonState();
    }

    private void requestAvailability() {
        if (fragmentView == null || usernameField == null || usernameField.field == null) return;
        final String value = usernameField.field.getText().toString();
        CreangerUsernamePolicy.Result local = CreangerUsernamePolicy.validateNormalized(value);
        if (!local.ok() || value.equals(lastCheckedUsername)) return;
        lastCheckedUsername = value;
        final int generation = ++availabilityGeneration;
        showUsernameStatus(STATUS_NEUTRAL, LocaleController.getString(R.string.CreangerUsernameChecking));
        creangerAuth.getAsync().checkUsername(value, new CreangerAuthAsync.AuthCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean available) {
                if (fragmentView == null || usernameField == null || usernameField.field == null) return;
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
                updateCreateButtonState();
            }

            @Override
            public void onError(CreangerApiException error, Throwable ioError) {
                if (fragmentView == null || usernameField == null) return;
                if (generation != availabilityGeneration) return;
                usernameAvailable = false;
                usernameField.setErrorState(true);
                if (error != null && error.is(ApiError.USERNAME_TAKEN)) {
                    showUsernameStatus(STATUS_ERROR, LocaleController.getString(R.string.CreangerErrorUsernameTaken));
                } else {
                    showUsernameStatus(STATUS_ERROR, LocaleController.getString(R.string.CreangerErrorNetworkError));
                }
                updateCreateButtonState();
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

    private void updateCreateButtonState() {
        boolean ready = isFormValidLocally();
        float targetAlpha = ready ? 1f : 0.45f;
        createButton.setEnabled(ready && !busy);
        if (createButton.getAlpha() != targetAlpha && !busy) {
            createButton.animate().alpha(targetAlpha).setDuration(160).start();
        } else if (busy) {
            createButton.setAlpha(1f);
        }
    }

    private boolean isFormValidLocally() {
        String name = nameField.field.getText().toString().trim();
        if (name.isEmpty()) return false;
        String displayName = displayNameField.field.getText().toString().trim();
        if (displayName.isEmpty()) return false;
        String username = usernameField.field.getText().toString();
        if (!CreangerUsernamePolicy.validateNormalized(username).ok()) return false;
        if (!usernameAvailable) return false;
        String pw = passwordField.field.getText().toString();
        if (pw.length() < MIN_PASSWORD_LENGTH) return false;
        String confirm = confirmPasswordField.field.getText().toString();
        if (!confirm.equals(pw)) return false;
        return agreementChecked;
    }

    private void attemptCreate() {
        if (busy || !createButton.isEnabled()) return;
        hideError();

        String name = nameField.field.getText().toString().trim();
        String displayName = displayNameField.field.getText().toString().trim();
        String username = usernameField.field.getText().toString();
        String pw = passwordField.field.getText().toString();
        String confirm = confirmPasswordField.field.getText().toString();

        boolean hasError = false;
        if (name.isEmpty()) {
            showError(LocaleController.getString(R.string.CreangerErrorEmptyName));
            nameField.setErrorState(true);
            hasError = true;
        }
        if (displayName.isEmpty()) {
            if (!hasError) showError(LocaleController.getString(R.string.CreangerErrorEmptyName));
            displayNameField.setErrorState(true);
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
        if (!agreementChecked) {
            if (!hasError) showError(LocaleController.getString(R.string.CreangerErrorTermsRequired));
            hasError = true;
        }
        if (hasError) return;

        setBusy(true);
        final long termsAcceptedAt = System.currentTimeMillis();
        creangerAuth.getAsync().googleAuthComplete(username, name, displayName, pw, termsAcceptedAt,
                new CreangerAuthAsync.AuthCallback<AuthSession>() {
                    @Override
                    public void onSuccess(AuthSession result) {
                        setBusy(false);
                        if (result != null && result.user != null) {
                            if (fragmentView != null) AndroidUtilities.hideKeyboard(fragmentView);
                            if (!CreangerLoginFlowHelper.proceedWithUser(result.user, getParentActivity())) {
                                showError(LocaleController.getString(R.string.CreangerErrorGeneric));
                            }
                        } else {
                            showError(LocaleController.getString(R.string.CreangerErrorGeneric));
                        }
                    }

                    @Override
                    public void onError(CreangerApiException error, Throwable ioError) {
                        setBusy(false);
                        AndroidUtilities.runOnUIThread(() -> {
                            if (fragmentView == null || errorTextView == null) return;
                            if (getParentActivity() != null && getParentActivity().isFinishing()) return;
                            if (error != null && error.is(ApiError.USERNAME_TAKEN)) {
                                usernameAvailable = false;
                                lastAvailableUsername = null;
                                if (usernameField != null) {
                                    usernameField.setErrorState(true);
                                    showUsernameStatus(STATUS_ERROR, LocaleController.getString(R.string.CreangerErrorUsernameTaken));
                                    updateCreateButtonState();
                                    if (usernameField.row != null) CreangerAuthTheme.shake(usernameField.row);
                                } else {
                                    showError(LocaleController.getString(R.string.CreangerErrorUsernameTaken));
                                }
                            } else if (error != null && error.is(ApiError.RATE_LIMITED)) {
                                showError(LocaleController.getString(R.string.CreangerErrorRateLimited));
                            } else {
                                showError(CreangerLoginFlowHelper.safeErrorMessage(error, ioError));
                            }
                        });
                    }
                });
    }

    private void setBusy(boolean loading) {
        busy = loading;
        if (fragmentView == null) return;
        if (createButton != null) createButton.setLoading(loading);
        if (nameField != null && nameField.field != null) nameField.field.setEnabled(!loading);
        if (displayNameField != null && displayNameField.field != null) displayNameField.field.setEnabled(!loading);
        if (usernameField != null && usernameField.field != null) usernameField.field.setEnabled(!loading);
        if (passwordField != null && passwordField.field != null) passwordField.field.setEnabled(!loading);
        if (confirmPasswordField != null && confirmPasswordField.field != null) confirmPasswordField.field.setEnabled(!loading);
        if (passwordToggle != null) passwordToggle.setEnabled(!loading);
        if (confirmToggle != null) confirmToggle.setEnabled(!loading);
        if (agreementCheck != null) agreementCheck.setEnabled(!loading);
        if (agreementText != null) agreementText.setEnabled(!loading);
        if (!loading) updateCreateButtonState();
    }

    private void showError(String message) { CreangerAuthTheme.showError(errorTextView, message); }
    private void hideError() { CreangerAuthTheme.hideError(errorTextView); }

    @Override
    public boolean onBackPressed(boolean invoked) {
        AndroidUtilities.cancelRunOnUIThread(availabilityRunnable);
        if (invoked) finishFragment();
        return true;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        AndroidUtilities.cancelRunOnUIThread(availabilityRunnable);
    }
}
