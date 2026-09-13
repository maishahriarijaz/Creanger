package com.creanger.app.ui.Components.glass;

import static com.creanger.app.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.math.MathUtils;

import com.creanger.app.messenger.LiteMode;
import com.creanger.app.ui.ActionBar.Theme;
import com.creanger.app.ui.Components.LayoutHelper;
import com.creanger.app.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import com.creanger.app.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import com.creanger.app.ui.Components.blur3.drawable.color.BlurredBackgroundColorProviderThemed;
import com.creanger.app.ui.Components.blur3.source.BlurredBackgroundSourceColor;
import com.creanger.app.ui.Components.blur3.source.BlurredBackgroundSourceRenderNode;

public class GlassTabsView extends FrameLayout {

    public final LinearLayout linearLayout;

    public GlassTabsView(@NonNull Context context) {
        super(context);
        setPadding(dp(8), dp(8), dp(8), dp(8));

        linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(LinearLayout.HORIZONTAL);
        addView(linearLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
    }

    private float lensVisibility;

    private final Rect lensBounds = new Rect();
    private final Rect lensBoundsForeground = new Rect();
    private final Paint lensPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    protected void setLensColor(int lensBackgroundColor, int lensForegroundColor) {
        lensPaint.setColor(lensBackgroundColor);
    }

    protected void setLensBounds(int l, int t, int r, int b) {
        lensBounds.set(l, t, r, b);
        checkBounds();
    }

    protected void setLensVisibility(float visibility) {
        lensVisibility = visibility;
        checkBounds();
    }

    private void checkBounds() {
        int i = dp(7f * lensVisibility);
        lensBoundsForeground.set(lensBounds);
        lensBoundsForeground.inset(-i, -i);
    }

}
