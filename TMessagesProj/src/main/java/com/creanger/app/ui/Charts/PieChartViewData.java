package com.creanger.app.ui.Charts;

import android.animation.Animator;

import com.creanger.app.ui.Charts.data.ChartData;
import com.creanger.app.ui.Charts.view_data.StackLinearViewData;

public class PieChartViewData extends StackLinearViewData {

    float selectionA;
    float drawingPart;
    Animator animator;

    public PieChartViewData(ChartData.Line line) {
        super(line);
    }
}
