package com.creanger.app.messenger.creanger.ui;

import static com.creanger.app.messenger.AndroidUtilities.dp;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

import com.creanger.app.messenger.AndroidUtilities;
import com.creanger.app.messenger.LocaleController;
import com.creanger.app.messenger.NotificationCenter;
import com.creanger.app.messenger.R;
import com.creanger.app.ui.ActionBar.Theme;
import com.creanger.app.ui.Components.EditTextBoldCursor;
import com.creanger.app.ui.Components.LayoutHelper;
import com.creanger.app.ui.Components.RLottieDrawable;
import com.creanger.app.ui.Components.RLottieImageView;
import com.creanger.app.ui.Components.RadialProgressView;
import com.creanger.app.ui.DialogsActivity;

/**
 * Shared design system for the Creanger auth flow (login / register / Google
 * account setup).
 *
 * IMPORTANT: this is a *visual* layer only. Nothing in this file talks to the
 * network, Supabase, or CreangerAuth - it only builds/styles Android Views.
 *
 * Every colour is a live read from the app's own theme engine
 * ({@link Theme#getColor(int)}), so the auth screens are painted by whatever
 * theme the user picked - light, dark or custom - exactly like the rest of the
 * app. There is no auth-scoped palette and no auth-scoped dark preference: the
 * toggle built by {@link #createThemeToggle(Context)} flips the app's real
 * day/night theme through {@link NotificationCenter#needSetDayNightTheme},
 * which is the same switch (and the same circular-reveal animation) used
 * everywhere else.
 *
 * Because accessors read live, callers only need to invalidate/re-apply after a
 * theme change - see each fragment's {@code getThemeDescriptions()}.
 */
final class CreangerAuthTheme {

    /** Day/night theme pair used by the app's own pre-login switch. */
    private static final String DAY_THEME_NAME = "Blue";
    private static final String NIGHT_THEME_NAME = "Night";

    static final int RADIUS_SM = 8;
    static final int RADIUS_MD = 12;
    static final int RADIUS_LG = 16;
    static final int RADIUS_CARD = 26;
    static final int RADIUS_FULL = 9999;

    static final int ELEVATION_0 = 0;
    static final int ELEVATION_1 = 1;
    static final int ELEVATION_2 = 3;
    static final int ELEVATION_3 = 6;
    static final int ELEVATION_4 = 12;

    static final int MAX_CONTENT_WIDTH_DP = 420;
    static final int CONTENT_PADDING_DP = 24;
    static final int FIELD_HEIGHT_DP = 54;
    static final int BUTTON_HEIGHT_DP = 54;
    static final int ICON_SIZE_DP = 20;
    static final int GAP_DP = 8;
    static final int GAP_LG_DP = 16;
    static final int GAP_XL_DP = 24;
    static final int GAP_XXL_DP = 32;

    static final long DURATION_FAST = 120;
    static final long DURATION_NORMAL = 200;
    static final long DURATION_SLOW = 300;
    static final long DURATION_SPRING = 400;

    private CreangerAuthTheme() {}

    // ================= Colors: every value comes from the app's theme engine =================

    /** True when the app's currently selected theme is a dark one. */
    static boolean isDark() {
        return Theme.isCurrentThemeDark();
    }

    static int screenBg() { return Theme.getColor(Theme.key_windowBackgroundGray); }
    static int cardBg() { return Theme.getColor(Theme.key_windowBackgroundWhite); }
    static int cardBorder() { return Theme.getColor(Theme.key_divider); }
    static int textPrimary() { return Theme.getColor(Theme.key_windowBackgroundWhiteBlackText); }
    static int textSecondary() { return Theme.getColor(Theme.key_windowBackgroundWhiteGrayText); }
    static int textTertiary() { return Theme.getColor(Theme.key_windowBackgroundWhiteHintText); }
    static int accent() { return Theme.getColor(Theme.key_featuredStickers_addButton); }
    /** Text/icon colour that sits on top of {@link #accent()}. */
    static int onAccent() { return Theme.getColor(Theme.key_featuredStickers_buttonText); }
    static int errorColor() { return Theme.getColor(Theme.key_text_RedRegular); }
    static int successColor() { return Theme.getColor(Theme.key_windowBackgroundWhiteGreenText2); }
    static int warningColor() { return Theme.getColor(Theme.key_color_orange); }
    static int dividerColor() { return Theme.getColor(Theme.key_divider); }
    static int strengthBg() { return Theme.getColor(Theme.key_divider); }
    static int inputBorder() { return Theme.getColor(Theme.key_windowBackgroundWhiteInputField); }
    static int inputBorderFocused() { return Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated); }
    static int googleBorder() { return Theme.getColor(Theme.key_divider); }

    /**
     * Blended rather than read from a single key so the field always stays
     * subtly recessed against the card - in a light, dark or custom theme a raw
     * key can end up identical to the card colour.
     */
    static int inputBg() { return ColorUtils.blendARGB(cardBg(), textPrimary(), 0.05f); }
    static int googleBg() { return inputBg(); }
    static int errorFieldBg() { return ColorUtils.blendARGB(cardBg(), errorColor(), 0.08f); }
    static int accountBg() { return ColorUtils.blendARGB(cardBg(), accent(), 0.08f); }
    static int accountBorder() { return Theme.multAlpha(accent(), 0.25f); }
    static int focusGlow() { return Theme.multAlpha(accent(), 0.25f); }

    // ================= Layout helpers =================

    static int contentHPad() {
        int shortSide = Math.min(AndroidUtilities.displaySize.x, AndroidUtilities.displaySize.y);
        return Math.max(dp(CONTENT_PADDING_DP), (shortSide - dp(MAX_CONTENT_WIDTH_DP)) / 2);
    }

    static int contentVPad() {
        return dp(CONTENT_PADDING_DP);
    }

    // ================= Theme toggle (the app's real day/night switch) =================

    /**
     * The app's own day/night switch, ported from
     * {@code IntroActivity} - same {@code R.raw.sun} lottie and the same
     * circular-reveal transition, which {@code LaunchActivity} runs when it
     * receives {@link NotificationCenter#needSetDayNightTheme}.
     */
    static final class ThemeToggle {
        final FrameLayout row;
        final RLottieImageView icon;
        private final RLottieDrawable drawable;

        ThemeToggle(FrameLayout row, RLottieImageView icon, RLottieDrawable drawable) {
            this.row = row;
            this.icon = icon;
            this.drawable = drawable;
        }

        /**
         * Re-tints the icon after a theme change. The lottie frame is only
         * re-pinned when no switch animation is running, so our own toggle
         * animation is never cut short mid-flight.
         */
        void refreshTheme() {
            drawable.setColorFilter(new PorterDuffColorFilter(accent(), PorterDuff.Mode.SRC_IN));
            row.setBackground(Theme.createSelectorDrawable(
                    Theme.multAlpha(accent(), 0.12f), Theme.RIPPLE_MASK_CIRCLE_20DP));
            if (!DialogsActivity.switchingTheme) {
                pinFrameToCurrentTheme();
            }
            icon.setContentDescription(LocaleController.getString(isDark()
                    ? R.string.AccDescrSwitchToDayTheme : R.string.AccDescrSwitchToNightTheme));
            icon.invalidate();
        }

        private void pinFrameToCurrentTheme() {
            int frame = isDark() ? drawable.getFramesCount() - 1 : 0;
            drawable.setCurrentFrame(frame, false);
            drawable.setCustomEndFrame(frame);
        }
    }

    static ThemeToggle createThemeToggle(Context context) {
        final RLottieImageView icon = new RLottieImageView(context);
        final RLottieDrawable drawable = new RLottieDrawable(R.raw.sun, String.valueOf(R.raw.sun),
                dp(28), dp(28), true, null);
        drawable.setPlayInDirectionOfCustomEndFrame(true);
        drawable.beginApplyLayerColors();
        drawable.commitApplyLayerColors();
        icon.setAnimation(drawable);

        final FrameLayout row = new FrameLayout(context);
        row.setClickable(true);
        row.setFocusable(true);
        row.addView(icon, LayoutHelper.createFrame(28, 28, Gravity.CENTER));

        final ThemeToggle toggle = new ThemeToggle(row, icon, drawable);
        toggle.refreshTheme();

        row.setOnClickListener(v -> {
            if (DialogsActivity.switchingTheme) {
                return;
            }
            boolean toDark = !Theme.isCurrentThemeDark();
            Theme.ThemeInfo themeInfo = Theme.getTheme(toDark ? NIGHT_THEME_NAME : DAY_THEME_NAME);
            if (themeInfo == null) {
                return;
            }
            DialogsActivity.switchingTheme = true;

            Theme.selectedAutoNightType = Theme.AUTO_NIGHT_TYPE_NONE;
            Theme.saveAutoNightThemeConfig();
            Theme.cancelAutoNightThemeCallbacks();

            drawable.setCustomEndFrame(toDark ? drawable.getFramesCount() - 1 : 0);
            icon.playAnimation();

            int[] pos = new int[2];
            icon.getLocationInWindow(pos);
            pos[0] += icon.getMeasuredWidth() / 2;
            pos[1] += icon.getMeasuredHeight() / 2;
            NotificationCenter.getGlobalInstance().postNotificationName(
                    NotificationCenter.needSetDayNightTheme, themeInfo, false, pos, -1, toDark, icon);

            icon.setContentDescription(LocaleController.getString(toDark
                    ? R.string.AccDescrSwitchToDayTheme : R.string.AccDescrSwitchToNightTheme));
        });
        return toggle;
    }

    // ================= Fields =================

    static EditTextBoldCursor filledField(Context c, int hintRes, int inputType, int ime) {
        return filledField(c, hintRes, inputType, ime, false);
    }

    static EditTextBoldCursor filledField(Context c, int hintRes, int inputType, int ime, boolean isOtp) {
        EditTextBoldCursor e = new EditTextBoldCursor(c) {
            private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint focusRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final RectF rect = new RectF();
            private final Path path = new Path();
            private float focusProgress = 0f;
            private boolean hasErrorState = false;

            {
                bgPaint.setStyle(Paint.Style.FILL);
                borderPaint.setStyle(Paint.Style.STROKE);
                borderPaint.setStrokeWidth(dp(1.5f));
                focusRingPaint.setStyle(Paint.Style.STROKE);
                focusRingPaint.setStrokeWidth(dp(2));
            }

            @Override
            protected void onDraw(Canvas canvas) {
                int w = getWidth();
                int h = getHeight();
                rect.set(0, 0, w, h);
                path.rewind();
                int radius = isOtp ? dp(RADIUS_MD) : dp(RADIUS_LG);
                path.addRoundRect(rect, radius, radius, Path.Direction.CW);

                boolean focused = hasFocus();
                focusRingPaint.setColor(focusGlow());

                if (hasErrorState) {
                    bgPaint.setColor(errorFieldBg());
                    borderPaint.setColor(errorColor());
                } else if (focused) {
                    bgPaint.setColor(cardBg());
                    borderPaint.setColor(inputBorderFocused());
                } else {
                    bgPaint.setColor(inputBg());
                    borderPaint.setColor(inputBorder());
                }

                canvas.drawPath(path, bgPaint);
                canvas.drawPath(path, borderPaint);

                if (focused && !hasErrorState) {
                    RectF ringRect = new RectF(-dp(2), -dp(2), w + dp(2), h + dp(2));
                    Path ringPath = new Path();
                    ringPath.addRoundRect(ringRect, radius + dp(2), radius + dp(2), Path.Direction.CW);
                    canvas.drawPath(ringPath, focusRingPaint);
                }

                super.onDraw(canvas);
            }

            @Override
            protected void onFocusChanged(boolean focused, int direction, android.graphics.Rect previouslyFocusedRect) {
                super.onFocusChanged(focused, direction, previouslyFocusedRect);
                animateFocus(focused);
            }

            @Override
            public void setEnabled(boolean enabled) {
                super.setEnabled(enabled);
                setAlpha(enabled ? 1f : 0.6f);
                invalidate();
            }

            private void animateFocus(boolean focused) {
                ValueAnimator anim = ValueAnimator.ofFloat(focusProgress, focused ? 1f : 0f);
                anim.setDuration(focused ? DURATION_NORMAL : DURATION_SLOW);
                anim.setInterpolator(new DecelerateInterpolator());
                anim.addUpdateListener(a -> {
                    focusProgress = (float) a.getAnimatedValue();
                    invalidate();
                });
                anim.start();
            }
        };
        e.setHint(hintRes);
        e.setTextSize(15);
        e.setTextColor(textPrimary());
        e.setHintTextColor(textTertiary());
        e.setInputType(inputType);
        e.setImeOptions(ime);
        e.setSingleLine(true);
        e.setPadding(dp(16), dp(16), dp(16), dp(16));
        e.setBackground(null);
        e.setCursorColor(accent());
        e.setCursorWidth(dp(2));
        e.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        if (isOtp) {
            e.setTextSize(24);
            e.setGravity(Gravity.CENTER);
            e.setLetterSpacing(0.3f);
            e.setMaxLines(1);
        }
        return e;
    }

    // ================= Icon-prefixed field row (email/password/username/etc) =================

    /**
     * A rounded, bordered row containing a leading icon + EditText. Supports
     * focus/error border states and can refresh after a theme change.
     */
    static final class FieldWithIconCompat {
        final LinearLayout row;
        final ImageView icon;
        final EditTextBoldCursor field;
        private final GradientDrawable bg;
        private boolean errorState;

        FieldWithIconCompat(LinearLayout row, ImageView icon, EditTextBoldCursor field, GradientDrawable bg) {
            this.row = row;
            this.icon = icon;
            this.field = field;
            this.bg = bg;
        }

        void setErrorState(boolean error) {
            errorState = error;
            refreshTheme();
        }

        /** Re-applies colors for the current focus/error state; call again after a theme change. */
        void refreshTheme() {
            icon.setColorFilter(errorState ? errorColor() : textTertiary());
            field.setTextColor(textPrimary());
            field.setHintTextColor(textTertiary());
            field.setCursorColor(accent());
            if (errorState) {
                bg.setColor(errorFieldBg());
                bg.setStroke(dp(1.5f), errorColor());
            } else if (field.hasFocus()) {
                bg.setColor(cardBg());
                bg.setStroke(dp(1.5f), inputBorderFocused());
            } else {
                bg.setColor(inputBg());
                bg.setStroke(dp(1.5f), inputBorder());
            }
        }
    }

    static FieldWithIconCompat iconFieldCompat(Context c, int iconRes, int hintRes, int inputType, int ime) {
        return iconFieldCompat(c, iconRes, hintRes, inputType, ime, false);
    }

    static FieldWithIconCompat iconFieldCompat(Context c, int iconRes, int hintRes, int inputType, int ime, boolean isOtp) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), 0, dp(8), 0);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(isOtp ? RADIUS_MD : RADIUS_LG));
        bg.setColor(inputBg());
        bg.setStroke(dp(1.5f), inputBorder());
        row.setBackground(bg);

        ImageView icon = new ImageView(c);
        icon.setImageResource(iconRes);
        icon.setColorFilter(textTertiary());
        row.addView(icon, LayoutHelper.createLinear(ICON_SIZE_DP, ICON_SIZE_DP, Gravity.CENTER_VERTICAL));

        EditTextBoldCursor field = new EditTextBoldCursor(c);
        field.setHint(hintRes);
        field.setTextSize(isOtp ? 20 : 15);
        field.setTextColor(textPrimary());
        field.setHintTextColor(textTertiary());
        field.setInputType(inputType);
        field.setImeOptions(ime);
        field.setSingleLine(true);
        field.setPadding(dp(12), dp(16), dp(8), dp(16));
        field.setBackground(null);
        field.setCursorColor(accent());
        field.setCursorWidth(dp(2));
        if (isOtp) {
            field.setGravity(Gravity.CENTER);
            field.setLetterSpacing(0.2f);
        }
        row.addView(field, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f, Gravity.CENTER_VERTICAL));

        FieldWithIconCompat holder = new FieldWithIconCompat(row, icon, field, bg);
        field.setOnFocusChangeListener((v, hasFocus) -> holder.refreshTheme());
        return holder;
    }

    // ================= Text styles =================

    static TextView linkText(Context c, CharSequence text, float sizeSp) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(sizeSp);
        t.setTextColor(accent());
        t.setGravity(Gravity.CENTER);
        t.setClickable(true);
        t.setFocusable(true);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    static TextView captionText(Context c, CharSequence text, float sizeSp) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(sizeSp);
        t.setTextColor(textSecondary());
        t.setGravity(Gravity.CENTER);
        return t;
    }

    static TextView labelText(Context c, CharSequence text, float sizeSp) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(sizeSp);
        t.setTextColor(textSecondary());
        t.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    static TextView errorText(Context c) {
        TextView t = new TextView(c);
        t.setTextSize(13);
        t.setTextColor(errorColor());
        t.setGravity(Gravity.CENTER);
        t.setVisibility(View.GONE);
        t.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        t.setPadding(dp(12), dp(8), dp(12), dp(8));
        return t;
    }

    static TextView helperText(Context c, CharSequence text, float sizeSp) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(sizeSp);
        t.setTextColor(textTertiary());
        t.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        t.setPadding(dp(4), dp(4), dp(4), 0);
        return t;
    }

    static void showError(TextView errorView, String message) {
        if (errorView == null) return;
        errorView.setText(message);
        errorView.setTextColor(errorColor());
        errorView.setVisibility(View.VISIBLE);
        errorView.setAlpha(0f);
        errorView.animate()
                .alpha(1f)
                .translationY(0)
                .setDuration(DURATION_NORMAL)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    static void hideError(TextView errorView) {
        if (errorView == null) return;
        errorView.animate()
                .alpha(0f)
                .translationY(-dp(8))
                .setDuration(DURATION_FAST)
                .setInterpolator(new FastOutSlowInInterpolator())
                .withEndAction(() -> errorView.setVisibility(View.GONE))
                .start();
    }

    static void shake(View v) {
        PropertyValuesHolder tx = PropertyValuesHolder.ofFloat(View.TRANSLATION_X,
                0, dp(10), -dp(10), dp(6), -dp(6), dp(3), -dp(3), 0);
        PropertyValuesHolder alpha = PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0.7f, 1f);
        ObjectAnimator anim = ObjectAnimator.ofPropertyValuesHolder(v, tx, alpha);
        anim.setDuration(500);
        anim.setInterpolator(new FastOutSlowInInterpolator());
        anim.start();
    }

    static void pulseError(View v) {
        PropertyValuesHolder scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.02f, 1f);
        PropertyValuesHolder scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.02f, 1f);
        ObjectAnimator anim = ObjectAnimator.ofPropertyValuesHolder(v, scaleX, scaleY);
        anim.setDuration(300);
        anim.setInterpolator(new FastOutSlowInInterpolator());
        anim.start();
    }

    static void fadeIn(View v, long delay) {
        v.setAlpha(0f);
        v.setTranslationY(dp(20));
        v.animate()
                .alpha(1f)
                .translationY(0)
                .setDuration(DURATION_SLOW)
                .setStartDelay(delay)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    static void fadeOut(View v, Runnable endAction) {
        v.animate()
                .alpha(0f)
                .translationY(-dp(10))
                .setDuration(DURATION_FAST)
                .setInterpolator(new FastOutSlowInInterpolator())
                .withEndAction(endAction)
                .start();
    }

    static void slideUp(View v, long delay) {
        v.setAlpha(0f);
        v.setTranslationY(dp(30));
        v.animate()
                .alpha(1f)
                .translationY(0)
                .setDuration(DURATION_SPRING)
                .setStartDelay(delay)
                .setInterpolator(new DecelerateInterpolator(1.2f))
                .start();
    }

    // ================= Password strength meter (register / setup screens) =================

    static final class PasswordStrength {
        final int score;      // 0..4
        final int progress;   // 0..100
        final int color;
        final String label;

        PasswordStrength(int score, int progress, int color, String label) {
            this.score = score;
            this.progress = progress;
            this.color = color;
            this.label = label;
        }
    }

    static PasswordStrength computeStrength(String pw) {
        if (pw == null) pw = "";
        int score = 0;
        if (pw.length() >= 8) score++;
        if (pw.matches(".*[a-z].*") && pw.matches(".*[A-Z].*")) score++;
        if (pw.matches(".*\\d.*")) score++;
        if (pw.matches(".*[^a-zA-Z0-9].*")) score++;

        int progress = (score * 100) / 4;
        int color;
        String label;
        if (score >= 4) {
            label = LocaleController.getString(R.string.CreangerStrengthStrong);
            color = successColor();
        } else if (score == 3) {
            label = LocaleController.getString(R.string.CreangerStrengthGood);
            color = warningColor();
        } else if (score == 2) {
            label = LocaleController.getString(R.string.CreangerStrengthFair);
            color = warningColor();
        } else {
            label = LocaleController.getString(R.string.CreangerStrengthWeak);
            color = errorColor();
        }
        return new PasswordStrength(score, progress, color, label);
    }

    /** Builds the thin horizontal strength bar + label row. */
    static final class StrengthMeter {
        final LinearLayout row;
        final ProgressBar bar;
        final TextView label;
        private String lastPassword = "";

        StrengthMeter(LinearLayout row, ProgressBar bar, TextView label) {
            this.row = row;
            this.bar = bar;
            this.label = label;
        }

        void update(String password) {
            lastPassword = password == null ? "" : password;
            bar.setProgressBackgroundTintList(ColorStateList.valueOf(strengthBg()));
            if (lastPassword.isEmpty()) {
                bar.setProgress(0);
                bar.setProgressTintList(ColorStateList.valueOf(warningColor()));
                label.setText(LocaleController.getString(R.string.CreangerStrengthWeak));
                label.setTextColor(textTertiary());
                return;
            }
            PasswordStrength s = computeStrength(lastPassword);
            bar.setProgress(s.progress);
            bar.setProgressTintList(ColorStateList.valueOf(s.color));
            label.setText(s.label);
            label.setTextColor(s.color);
        }

        /** Re-applies colors for the current strength after a theme change. */
        void refreshTheme() {
            update(lastPassword);
        }
    }

    static StrengthMeter strengthMeter(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        ProgressBar bar = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(0);
        bar.setProgressTintList(ColorStateList.valueOf(warningColor()));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(strengthBg()));
        row.addView(bar, LayoutHelper.createLinear(0, 4, 1f, Gravity.CENTER_VERTICAL));

        TextView label = new TextView(c);
        label.setTextSize(12);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setTextColor(textTertiary());
        label.setText(LocaleController.getString(R.string.CreangerStrengthWeak));
        row.addView(label, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, dp(12), 0, 0, 0));

        return new StrengthMeter(row, bar, label);
    }

    // ================= Terms & privacy checkbox row =================

    static final class TermsRow {
        final LinearLayout row;
        final CheckBox checkbox;
        final TextView text;

        TermsRow(LinearLayout row, CheckBox checkbox, TextView text) {
            this.row = row;
            this.checkbox = checkbox;
            this.text = text;
        }

        /** Re-applies colors after a theme change. */
        void refreshTheme() {
            checkbox.setButtonTintList(ColorStateList.valueOf(accent()));
            text.setTextColor(textSecondary());
        }
    }

    static TermsRow termsCheckboxRow(Context c, CharSequence text) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));

        CheckBox checkbox = new CheckBox(c);
        checkbox.setButtonTintList(ColorStateList.valueOf(accent()));
        row.addView(checkbox, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(textSecondary());
        tv.setLineSpacing(0, 1.2f);
        row.addView(tv, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, dp(4), 0, 0, 0));

        return new TermsRow(row, checkbox, tv);
    }

    // ================= Google / verified account summary card =================

    /**
     * The "verified with Google" summary shown above the create-account form.
     * Returns a holder so the caller can re-theme it without rebuilding.
     */
    static final class AccountSummary {
        final LinearLayout card;
        final TextView avatar;
        final TextView email;
        final ImageView check;
        final TextView verified;
        private final GradientDrawable bg;
        private final GradientDrawable avatarBg;

        AccountSummary(LinearLayout card, TextView avatar, TextView email, ImageView check,
                       TextView verified, GradientDrawable bg, GradientDrawable avatarBg) {
            this.card = card;
            this.avatar = avatar;
            this.email = email;
            this.check = check;
            this.verified = verified;
            this.bg = bg;
            this.avatarBg = avatarBg;
        }

        void refreshTheme() {
            bg.setColor(accountBg());
            bg.setStroke(dp(1), accountBorder());
            avatarBg.setColor(accent());
            avatar.setTextColor(onAccent());
            email.setTextColor(textPrimary());
            check.setColorFilter(successColor());
            verified.setTextColor(textTertiary());
        }
    }

    static AccountSummary accountSummaryCard(Context c, String avatarInitials, String email, String verifiedLabel) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(14));
        card.setBackground(bg);

        TextView avatar = new TextView(c);
        avatar.setText(avatarInitials);
        avatar.setTextSize(18);
        avatar.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        avatar.setGravity(Gravity.CENTER);
        GradientDrawable avatarBg = new GradientDrawable();
        avatarBg.setShape(GradientDrawable.OVAL);
        avatar.setBackground(avatarBg);
        card.addView(avatar, LayoutHelper.createLinear(42, 42));

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        card.addView(col, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, dp(14), 0, 0, 0));

        TextView emailView = new TextView(c);
        emailView.setText(email);
        emailView.setTextSize(15);
        emailView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        emailView.setSingleLine(true);
        col.addView(emailView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        LinearLayout verifiedRow = new LinearLayout(c);
        verifiedRow.setOrientation(LinearLayout.HORIZONTAL);
        verifiedRow.setGravity(Gravity.CENTER_VERTICAL);
        verifiedRow.setPadding(0, dp(2), 0, 0);
        col.addView(verifiedRow, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        ImageView check = new ImageView(c);
        check.setImageResource(R.drawable.ic_check_circle);
        verifiedRow.addView(check, LayoutHelper.createLinear(14, 14, Gravity.CENTER_VERTICAL, 0, 0, dp(4), 0));

        TextView verified = new TextView(c);
        verified.setText(verifiedLabel);
        verified.setTextSize(12);
        verifiedRow.addView(verified, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        AccountSummary summary = new AccountSummary(card, avatar, emailView, check, verified, bg, avatarBg);
        summary.refreshTheme();
        return summary;
    }

    // ================= Footer (copyright / privacy / terms) =================

    /** Footer row plus its labels, so the caller can re-tint after a theme change. */
    static final class AuthFooter {
        final LinearLayout row;
        private final TextView[] labels;

        AuthFooter(LinearLayout row, TextView[] labels) {
            this.row = row;
            this.labels = labels;
        }

        void refreshTheme() {
            for (TextView t : labels) {
                t.setTextColor(textTertiary());
            }
        }
    }

    static AuthFooter authFooter(Context c, Runnable onPrivacy, Runnable onTerms) {
        LinearLayout footer = new LinearLayout(c);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER);

        TextView copyright = dotText(c, LocaleController.getString(R.string.CreangerFooterCopyright));
        footer.addView(copyright);
        TextView sep1 = dotSeparator(c);
        footer.addView(sep1);
        TextView privacy = dotText(c, LocaleController.getString(R.string.CreangerFooterPrivacy));
        privacy.setPadding(dp(4), dp(4), dp(4), dp(4));
        privacy.setClickable(true);
        privacy.setOnClickListener(v -> { if (onPrivacy != null) onPrivacy.run(); });
        footer.addView(privacy);
        TextView sep2 = dotSeparator(c);
        footer.addView(sep2);
        TextView terms = dotText(c, LocaleController.getString(R.string.CreangerFooterTerms));
        terms.setPadding(dp(4), dp(4), dp(4), dp(4));
        terms.setClickable(true);
        terms.setOnClickListener(v -> { if (onTerms != null) onTerms.run(); });
        footer.addView(terms);

        return new AuthFooter(footer, new TextView[]{copyright, sep1, privacy, sep2, terms});
    }

    private static TextView dotText(Context c, CharSequence text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(textTertiary());
        return t;
    }

    private static TextView dotSeparator(Context c) {
        TextView t = new TextView(c);
        t.setText("•");
        t.setTextSize(12);
        t.setTextColor(textTertiary());
        t.setAlpha(0.4f);
        t.setPadding(dp(10), 0, dp(10), 0);
        return t;
    }
}

class CreangerAuthButton extends FrameLayout {

    private final TextView label;
    private final RadialProgressView progress;
    private final Paint buttonPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path buttonPath = new Path();
    private final RectF buttonRect = new RectF();
    private final Paint ripplePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] rippleState = {0f, 0f, 0f}; // x, y, progress
    private int backgroundColor;
    private int textColor;
    private CharSequence restingText;
    private boolean loading;
    private boolean pressed;
    private ValueAnimator rippleAnim;

    CreangerAuthButton(Context c, CharSequence text, int bgColor, int textColor) {
        super(c);
        backgroundColor = bgColor;
        this.textColor = textColor;
        restingText = text;

        setClickable(true);
        setFocusable(true);
        setWillNotDraw(false);

        label = new TextView(c);
        label.setText(text);
        label.setTextSize(16);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setGravity(Gravity.CENTER);
        addView(label, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        progress = new RadialProgressView(c);
        progress.setSize(dp(22));
        progress.setVisibility(GONE);
        addView(progress, LayoutHelper.createFrame(28, 28, Gravity.CENTER));

        ripplePaint.setStyle(Paint.Style.FILL);
        applyColors();

        setOnTouchListener((v, event) -> {
            if (loading || !isClickable()) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    pressed = true;
                    rippleState[0] = event.getX();
                    rippleState[1] = event.getY();
                    rippleState[2] = 0f;
                    startRipple();
                    label.animate().alpha(0.7f).scaleX(0.98f).scaleY(0.98f).setDuration(80).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    pressed = false;
                    label.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(150).start();
                    break;
            }
            return false;
        });
    }

    /** Refresh both colors after a theme change. */
    void setColors(int bgColor, int labelColor) {
        backgroundColor = bgColor;
        textColor = labelColor;
        applyColors();
        invalidate();
    }

    private void applyColors() {
        label.setTextColor(textColor);
        progress.setProgressColor(textColor);
        ripplePaint.setColor(Color.argb(40, Color.red(textColor), Color.green(textColor), Color.blue(textColor)));
    }

    private void startRipple() {
        if (rippleAnim != null) rippleAnim.cancel();
        rippleAnim = ValueAnimator.ofFloat(0f, 1f);
        rippleAnim.setDuration(300);
        rippleAnim.setInterpolator(new DecelerateInterpolator());
        rippleAnim.addUpdateListener(a -> {
            rippleState[2] = (float) a.getAnimatedValue();
            invalidate();
        });
        rippleAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                rippleState[2] = 0f;
                invalidate();
            }
        });
        rippleAnim.start();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        buttonPath.rewind();
        buttonRect.set(0, 0, w, h);
        buttonPath.addRoundRect(buttonRect, dp(CreangerAuthTheme.RADIUS_LG), dp(CreangerAuthTheme.RADIUS_LG), Path.Direction.CW);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        buttonPaint.setColor(backgroundColor);
        if (loading) {
            buttonPaint.setAlpha(200);
        } else if (!isEnabled()) {
            buttonPaint.setAlpha(120);
        } else {
            buttonPaint.setAlpha(255);
        }
        canvas.drawPath(buttonPath, buttonPaint);

        if (rippleState[2] > 0) {
            float radius = Math.max(getWidth(), getHeight()) * rippleState[2];
            ripplePaint.setAlpha((int) (40 * (1 - rippleState[2])));
            canvas.drawCircle(rippleState[0], rippleState[1], radius, ripplePaint);
        }

        super.onDraw(canvas);
    }

    void setLoading(boolean value) {
        if (loading == value) return;
        loading = value;
        label.setText(value ? "" : restingText);
        label.setAlpha(1f);
        label.setScaleX(1f);
        label.setScaleY(1f);
        progress.setVisibility(value ? VISIBLE : GONE);
        progress.setNoProgress(!value);
        setEnabled(!value);
        setAlpha(value ? 0.85f : 1f);
    }

    boolean isLoading() {
        return loading;
    }

    void setText(CharSequence text) {
        restingText = text;
        if (!loading) {
            label.setText(text);
        }
    }
}

class CreangerAuthCard extends FrameLayout {

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path cardPath = new Path();
    private final RectF cardRect = new RectF();

    CreangerAuthCard(Context c) {
        super(c);
        setWillNotDraw(false);
        setClipToOutline(true);
        bgPaint.setStyle(Paint.Style.FILL);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(1));
        refreshTheme();
    }

    /** Call after a theme change to refresh this card's colors. */
    void refreshTheme() {
        bgPaint.setColor(CreangerAuthTheme.cardBg());
        borderPaint.setColor(CreangerAuthTheme.cardBorder());
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        cardPath.rewind();
        cardRect.set(0, 0, w, h);
        int radius = dp(CreangerAuthTheme.RADIUS_CARD);
        cardPath.addRoundRect(cardRect, radius, radius, Path.Direction.CW);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawPath(cardPath, bgPaint);
        canvas.drawPath(cardPath, borderPaint);
        super.onDraw(canvas);
    }
}
