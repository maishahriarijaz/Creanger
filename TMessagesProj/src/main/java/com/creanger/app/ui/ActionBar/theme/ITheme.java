package com.creanger.app.ui.ActionBar.theme;

import com.creanger.app.tgnet.TLRPC;

public interface ITheme {
    long getThemeId();

    TLRPC.ThemeSettings getThemeSettings(int settingsIndex);
    TLRPC.WallPaper getThemeWallPaper(int settingsIndex);
}
