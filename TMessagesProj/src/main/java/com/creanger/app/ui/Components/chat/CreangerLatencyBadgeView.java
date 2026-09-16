package com.creanger.app.ui.Components.chat;

import static com.creanger.app.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.view.View;

import androidx.annotation.NonNull;

import com.creanger.app.messenger.creanger.realtime.RealtimeLatency;
import com.creanger.app.ui.ActionBar.Theme;

/**
 * Live realtime-latency badge for the open Creanger chat: a small pill under
 * the action bar that shows the smoothed delivery latency in milliseconds and
 * colors it by connection quality (green → yellow → orange → red).
 *
 * Data source: {@link com.creanger.app.messenger.creanger.realtime.RealtimeLatencySink}.
 * Preferred number is the server-timestamp E2E delivery latency (what the user
 * perceives); the socket RTT is the fallback until the first message arrives.
 * Samples arrive already smoothed (EMA); this view only renders and animates.
 *
 * Tap the badge to flip between the live value and a one-line legend
 * ({@code E2E 320ms · RTT 45ms}).
 */
public class CreangerLatencyBadgeView extends View {

    /** Emitted when the user taps the badge (legend toggle). */
    public interface OnLegendToggleListener {
        void onLegendToggle();
    }

    private static final int COLOR_EXCELLENT = 0xFF2ECC71;
    private static final int COLOR_GOOD = 0xFFF1C40F;
    private static final int COLOR_SLOW = 0xFFE67E22;
    private static final int COLOR_VERY_SLOW = 0xFFE74C3C;
    private static final int COLOR_OFFLINE = 0xFF95A5A6;
    private static final int COLOR_TEXT_ON_PILL = 0xFFFFFFFF;

    /** Hides the badge when the newest sample is older than this (dead socket). */
    private static final long STALE_AFTER_MS = 95_000L;

    private final Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF pillRect = new RectF();

    private long latencyMs = -1;
    private long rttMs = -1;
    private long lastSampleAtMs;
    private boolean showLegend;
    private OnLegendToggleListener legendToggleListener;

    public CreangerLatencyBadgeView(Context context) {
        super(context);
        pillPaint.setStyle(Paint.Style.FILL);
        textPaint.setColor(COLOR_TEXT_ON_PILL);
        textPaint.setTextSize(dp(11));
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);
        setVisibility(GONE);
    }

    /**
     * Consumes taps on the pill itself (legend toggle) and passes everything
     * else through to the chat underneath — the strip view spans the screen
     * width, but returning false outside the pill keeps scrolling and taps on
     * messages working.
     */
    @Override
    public boolean onTouchEvent(@NonNull android.view.MotionEvent event) {
        if (getVisibility() != VISIBLE) {
            return false;
        }
        if (event.getAction() == android.view.MotionEvent.ACTION_UP
                && pillRect.contains(event.getX(), event.getY())) {
            showLegend = !showLegend;
            invalidate();
            OnLegendToggleListener l = legendToggleListener;
            if (l != null) {
                l.onLegendToggle();
            }
            return true;
        }
        return false;
    }

    public void setOnLegendToggleListener(OnLegendToggleListener listener) {
        this.legendToggleListener = listener;
    }

    /** Applies one smoothed sample to the badge (already on the UI thread). */
    public void onLatencySample(RealtimeLatency.Kind kind, long ms) {
        if (kind == RealtimeLatency.Kind.RTT) {
            if (latencyMs >= 0) {
                return; // E2E takes precedence once known
            }
            rttMs = ms;
        } else {
            latencyMs = ms;
        }
        lastSampleAtMs = System.currentTimeMillis();
        updateVisibilityAndText();
    }

    /** Fallback display when no fresh sample exists (e.g. right after open). */
    public void setRttMs(long ms) {
        rttMs = ms;
        updateVisibilityAndText();
    }

    /** Hides the badge (chat teardown, account switch). */
    public void reset() {
        latencyMs = -1;
        rttMs = -1;
        lastSampleAtMs = 0;
        showLegend = false;
        setVisibility(GONE);
    }

    /**
     * Re-checks staleness without waiting for a draw: called on chat resume
     * so a badge left stale in the background hides immediately.
     */
    public void onForegroundCheck() {
        if (getVisibility() == VISIBLE && lastSampleAtMs > 0
                && System.currentTimeMillis() - lastSampleAtMs > STALE_AFTER_MS) {
            setVisibility(GONE);
        }
    }

    /**
     * Called each frame while visible: hides the badge when samples stop
     * (socket dropped and backoff exceeded) without extra handlers.
     */
    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        if (getVisibility() != VISIBLE) {
            return;
        }
        if (lastSampleAtMs > 0 && System.currentTimeMillis() - lastSampleAtMs > STALE_AFTER_MS) {
            setVisibility(GONE);
            return;
        }
        String text = displayText();
        if (text == null) {
            setVisibility(GONE);
            return;
        }
        float textWidth = textPaint.measureText(text);
        float pillWidth = textWidth + dp(16);
        float pillHeight = dp(20);
        pillRect.set(
                (getWidth() - pillWidth) / 2f,
                dp(3),
                (getWidth() + pillWidth) / 2f,
                dp(3) + pillHeight);
        pillPaint.setColor(colorForCurrent());
        canvas.drawRoundRect(pillRect, pillHeight / 2f, pillHeight / 2f, pillPaint);
        float textY = pillRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f;
        canvas.drawText(text, pillRect.centerX(), textY, textPaint);
    }

    /** Show the badge only when a fresh sample exists. */
    private void updateVisibilityAndText() {
        if (lastSampleAtMs > 0 && System.currentTimeMillis() - lastSampleAtMs <= STALE_AFTER_MS
                && displayText() != null) {
            if (getVisibility() != VISIBLE) {
                setVisibility(VISIBLE);
            }
        }
        invalidate();
    }

    /** The pill text, or null when there is nothing to show. */
    private String displayText() {
        if (showLegend) {
            String e2e = latencyMs >= 0 ? latencyMs + "ms" : "–";
            String rtt = rttMs >= 0 ? rttMs + "ms" : "–";
            return "E2E " + e2e + " · RTT " + rtt;
        }
        long v = latencyMs >= 0 ? latencyMs : rttMs;
        return v >= 0 ? v + "ms" : null;
    }

    private int colorForCurrent() {
        return colorForBucket(RealtimeLatency.bucketOf(latencyMs >= 0 ? latencyMs : rttMs));
    }

    private static int colorForBucket(int bucket) {
        switch (bucket) {
            case RealtimeLatency.BUCKET_EXCELLENT:
                return COLOR_EXCELLENT;
            case RealtimeLatency.BUCKET_GOOD:
                return COLOR_GOOD;
            case RealtimeLatency.BUCKET_SLOW:
                return COLOR_SLOW;
            case RealtimeLatency.BUCKET_VERY_SLOW:
                return COLOR_VERY_SLOW;
            default:
                return COLOR_OFFLINE;
        }
    }
}
