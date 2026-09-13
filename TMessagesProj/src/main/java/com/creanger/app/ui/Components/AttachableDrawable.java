package com.creanger.app.ui.Components;

import android.view.View;

import com.creanger.app.messenger.ImageReceiver;

public interface AttachableDrawable {
    void onAttachedToWindow(ImageReceiver parent);
    void onDetachedFromWindow(ImageReceiver parent);

    default void setParent(View view) {}
}
