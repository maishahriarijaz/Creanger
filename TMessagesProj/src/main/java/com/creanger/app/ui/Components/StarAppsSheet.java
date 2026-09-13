package com.creanger.app.ui.Components;

import static com.creanger.app.messenger.LocaleController.getString;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.creanger.app.messenger.R;

public class StarAppsSheet extends BottomSheetWithRecyclerListView {

    public StarAppsSheet(Context context) {
        super(context, null, true, false, false, null);

        fixNavigationBar();
        handleOffset = true;
        setShowHandle(true);

        setSlidingActionBar();

        recyclerListView.setPadding(backgroundPaddingLeft, 0, backgroundPaddingLeft, 0);
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.SearchAppsExamples);
    }

    @Override
    protected RecyclerListView.SelectionAdapter createAdapter(RecyclerListView listView) {
        return new RecyclerListView.SelectionAdapter() {
            @Override
            public boolean isEnabled(RecyclerView.ViewHolder holder) {
                return false;
            }

            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                return new RecyclerListView.Holder(new View(parent.getContext()));
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            }

            @Override
            public int getItemCount() {
                return 0;
            }
        };
    }

}
