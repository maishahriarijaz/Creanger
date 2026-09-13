package com.creanger.app.ui.Components;

import static com.creanger.app.messenger.AndroidUtilities.dp;
import static com.creanger.app.messenger.AndroidUtilities.dpf2;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.ReplacementSpan;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.creanger.app.messenger.AndroidUtilities;
import com.creanger.app.ui.ActionBar.Theme;

public class BadgeSpan {

    public static class NewSpan extends ReplacementSpan {

        TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        StaticLayout layout;
        float width, height;

        private boolean outline;
        private int color;
        private int fontSize;

        public NewSpan(boolean outline) {
            this(outline, -1);
        }
        public NewSpan(boolean outline, int fontSize) {
            this.outline = outline;
            this.fontSize = fontSize;

            textPaint.setTypeface(AndroidUtilities.bold());
            if (outline) {
                bgPaint.setStyle(Paint.Style.STROKE);
                bgPaint.setStrokeWidth(dpf2(1.33f));
                textPaint.setTextSize(dp(fontSize < 0 ? 10 : fontSize));
                textPaint.setStyle(Paint.Style.FILL_AND_STROKE);
                textPaint.setStrokeWidth(dpf2(0.2f));
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    textPaint.setLetterSpacing(.03f);
                }
            } else {
                bgPaint.setStyle(Paint.Style.FILL);
                textPaint.setTextSize(dp(fontSize < 0 ? 12 : fontSize));
            }
        }

        public void setTypeface(Typeface typeface) {
            textPaint.setTypeface(typeface);
        }

        public NewSpan(float textSize) {
            this.outline = false;
            textPaint.setTypeface(AndroidUtilities.bold());
            bgPaint.setStyle(Paint.Style.FILL);
            textPaint.setTextSize(dp(textSize));
        }

        public void setColor(int color) {
            this.color = color;
        }

        private CharSequence text = "NEW";
        public void setText(CharSequence text) {
            this.text = text;
            if (layout != null) {
                layout = null;
                makeLayout();
            }
        }

        public StaticLayout makeLayout() {
            if (layout == null) {
                layout = new StaticLayout(text, textPaint, AndroidUtilities.displaySize.x, Layout.Alignment.ALIGN_NORMAL, 1, 0, false);
                width = layout.getLineWidth(0);
                height = layout.getHeight();
            }
            return layout;
        }

        @Override
        public int getSize(@NonNull Paint paint, CharSequence text, int start, int end, @Nullable Paint.FontMetricsInt fm) {
            makeLayout();
            return (int) (dp(10) + width);
        }

        public boolean usePaintAlpha;
        @Override
        public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end, float _x, int top, int _y, int bottom, @NonNull Paint paint) {
            makeLayout();

            final float alpha = usePaintAlpha ? paint.getAlpha() / 255.0f : 1.0f;

            int color = this.color;
            if (color == 0) {
                color = paint.getColor();
            }
            bgPaint.setColor(color);
            if (outline) {
                textPaint.setColor(color);
            } else {
                textPaint.setColor(AndroidUtilities.computePerceivedBrightness(color) > .721f ? Color.BLACK : Color.WHITE);
            }
            bgPaint.setAlpha((int) (bgPaint.getAlpha() * alpha));
            textPaint.setAlpha((int) (textPaint.getAlpha() * alpha));

            float x = _x + dp(2), y = _y - height + dp(1);
            AndroidUtilities.rectTmp.set(x, y, x + width, y + height);
            float r;
            if (outline) {
                r = dp(3.66f);
                AndroidUtilities.rectTmp.left -= dp(4);
                AndroidUtilities.rectTmp.top -= dp(2.33f);
                AndroidUtilities.rectTmp.right += dp(3.66f);
                AndroidUtilities.rectTmp.bottom += dp(1.33f);
            } else {
                r = dp(4.4f);
                AndroidUtilities.rectTmp.inset(dp(-4), dp(fontSize == 8 ? -3.66f : -2.33f));
            }
            canvas.drawRoundRect(AndroidUtilities.rectTmp, r, r, bgPaint);

            canvas.save();
            canvas.translate(x, y);
            layout.draw(canvas);
            canvas.restore();
        }
    }

    public static class TextSpan extends ReplacementSpan {

        private final Theme.ResourcesProvider resourcesProvider;
        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int colorKey;
        private Text text;

        public TextSpan(String text, float fontSize, int colorKey, Theme.ResourcesProvider resourcesProvider) {
            this.resourcesProvider = resourcesProvider;
            this.colorKey = colorKey;
            this.text = new Text(text, fontSize, AndroidUtilities.bold());
            bgPaint.setStyle(Paint.Style.FILL);
        }

        @Override
        public int getSize(@NonNull Paint paint, CharSequence text, int start, int end, @Nullable Paint.FontMetricsInt fm) {
            return (int) (dp(9.33f) + this.text.getWidth());
        }

        @Override
        public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end, float _x, int top, int _y, int bottom, @NonNull Paint paint) {
            final int color = Theme.getColor(colorKey, resourcesProvider);
            bgPaint.setColor(Theme.multAlpha(color, .15f));
            final float cy = (bottom + top) / 2f;
            final float height = dp(14.66f);
            AndroidUtilities.rectTmp.set(_x, cy - height / 2f, _x + this.text.getWidth() + dp(9.33f), cy + height / 2f);
            canvas.drawRoundRect(AndroidUtilities.rectTmp, dp(4), dp(4), bgPaint);
            this.text.draw(canvas, _x + dp(4.66f), cy, color, 1.0f);
        }
    }
}
