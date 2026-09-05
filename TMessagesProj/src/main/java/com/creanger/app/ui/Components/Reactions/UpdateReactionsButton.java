package com.creanger.app.ui.Components.Reactions;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.SpannableStringBuilder;

import com.creanger.app.messenger.LocaleController;
import com.creanger.app.messenger.R;
import com.creanger.app.ui.ActionBar.Theme;
import com.creanger.app.ui.Stories.recorder.ButtonWithCounterView;

@SuppressLint("ViewConstructor")
public class UpdateReactionsButton extends ButtonWithCounterView {

    public UpdateReactionsButton(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context, resourcesProvider);
    }

    public UpdateReactionsButton(Context context, boolean filled, Theme.ResourcesProvider resourcesProvider) {
        super(context, filled, resourcesProvider);
    }

    public void setDefaultState() {
        setText(new SpannableStringBuilder(LocaleController.getString(R.string.ReactionUpdateReactionsBtn)), false);
    }
}
