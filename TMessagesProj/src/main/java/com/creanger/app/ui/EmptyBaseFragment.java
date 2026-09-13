package com.creanger.app.ui;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

import com.creanger.app.ui.ActionBar.BaseFragment;
import com.creanger.app.ui.Components.SizeNotifierFrameLayout;

public class EmptyBaseFragment extends BaseFragment {

    @Override
    public View createView(Context context) {
        return fragmentView = new SizeNotifierFrameLayout(context);
    }

}
