package com.creanger.app.messenger.creanger;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.widget.Toast;

import com.creanger.app.messenger.AndroidUtilities;
import com.creanger.app.messenger.ApplicationLoader;
import com.creanger.app.messenger.FileLoader;
import com.creanger.app.messenger.LocaleController;
import com.creanger.app.messenger.MessageObject;
import com.creanger.app.ui.ActionBar.Theme;

import java.io.File;

public class CreangerDocumentUtils {

    public static void openCreangerDocument(MessageObject message, Activity activity, Theme.ResourcesProvider resourcesProvider, boolean restrict) {
    }

    private static void monitorDownload(long downloadId, Activity activity, android.app.AlertDialog progressDialog, String fileName, String mimeType) {
    }

    private static void dismissDialog(android.app.AlertDialog dialog) {
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
    }
}
