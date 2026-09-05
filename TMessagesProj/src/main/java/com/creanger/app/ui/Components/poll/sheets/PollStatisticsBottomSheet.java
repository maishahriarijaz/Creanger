package com.creanger.app.ui.Components.poll.sheets;

import static com.creanger.app.messenger.AndroidUtilities.dp;
import static com.creanger.app.messenger.LocaleController.getString;

import android.content.Context;

import com.creanger.app.messenger.AndroidUtilities;
import com.creanger.app.messenger.LocaleController;
import com.creanger.app.messenger.MessagesController;
import com.creanger.app.messenger.R;
import com.creanger.app.messenger.Utilities;
import com.creanger.app.tgnet.ConnectionsManager;
import com.creanger.app.tgnet.tl.TL_stats;
import com.creanger.app.ui.ActionBar.ActionBarMenu;
import com.creanger.app.ui.ActionBar.Theme;
import com.creanger.app.ui.Components.BottomSheetWithRecyclerListView;
import com.creanger.app.ui.Components.RecyclerListView;
import com.creanger.app.ui.Components.UItem;
import com.creanger.app.ui.Components.UniversalAdapter;
import com.creanger.app.ui.StatisticActivity;

import java.util.ArrayList;

public class PollStatisticsBottomSheet extends BottomSheetWithRecyclerListView {

    private UniversalAdapter adapter;
    private final StatisticActivity.ChartViewData chartViewData;

    public PollStatisticsBottomSheet(Context context, Theme.ResourcesProvider resourcesProvider, TL_stats.TL_statsPollStats stats) {
        super(context, null, true, false, false, false, ActionBarType.SLIDING, resourcesProvider);
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray, resourcesProvider));
        occupyNavigationBar = true;
        drawNavigationBar = false;
        ignoreTouchActionBar = false;
        headerMoveTop = dp(12);

        chartViewData = StatisticActivity.createViewData(stats.votes_graph, getString(R.string.PollV2StatsVoteTimeline), 2);

        recyclerListView.setPadding(backgroundPaddingLeft, 0, backgroundPaddingLeft, AndroidUtilities.navigationBarHeight);
        recyclerListView.setClipToPadding(false);
        recyclerListView.setSections(true);

        ActionBarMenu m = actionBar.createMenu();
        m.addItem(-1, R.drawable.ic_close_white);
        m.setTranslationX(-dp(5));

        adapter.update(false);
    }

    public static int loadStatistics(int currentAccount, long dialogId, int messageId, Utilities.Callback<TL_stats.TL_statsPollStats> onResult) {
        final TL_stats.TL_statsGetPollStats req = new TL_stats.TL_statsGetPollStats();
        req.peer = MessagesController.getInstance(currentAccount).getInputPeer(dialogId);
        req.msg_id = messageId;
        return ConnectionsManager.getInstance(currentAccount).sendRequestTyped(req, AndroidUtilities::runOnUIThread, (res, err) -> {
            onResult.run(res);
        });
    }

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.PollV2StatsPollStats);
    }

    @Override
    protected RecyclerListView.SelectionAdapter createAdapter(RecyclerListView listView) {
        adapter = new UniversalAdapter(listView, getContext(), currentAccount, 0, true, this::fillItems, resourcesProvider);
        adapter.setApplyBackground(false);
        return adapter;
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (chartViewData != null) {
            items.add(UItem.asSpace(dp(12)));
            items.add(UItem.asChart(StatisticActivity.VIEW_TYPE_LINEAR, 0, chartViewData));
        }
    }
}
