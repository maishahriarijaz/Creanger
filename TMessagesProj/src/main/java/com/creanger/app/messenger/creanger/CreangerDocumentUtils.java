package com.creanger.app.messenger.creanger;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import com.creanger.app.messenger.AndroidUtilities;
import com.creanger.app.messenger.ApplicationLoader;
import com.creanger.app.messenger.FileLoader;
import com.creanger.app.messenger.MessageObject;
import com.creanger.app.messenger.creanger.api.CreangerDocumentPreview;
import com.creanger.app.ui.ActionBar.Theme;

import java.io.File;

/**
 * HTTPS document open/download for Creanger chats (Phase E2).
 *
 * All Creanger document access goes through Cloudinary/ImageBB HTTPS —
 * never MTProto FileLoader. Flow: local cache (attachPath / FileLoader)
 * → trusted HTTPS check → Android DownloadManager with progress dialog →
 * open via MIME intent. Untrusted URLs and missing URLs toast and stop.
 */
public class CreangerDocumentUtils {

    private CreangerDocumentUtils() {}

    /** True when the message carries a Creanger document HTTPS seam. */
    public static boolean isCreangerDocument(MessageObject message) {
        if (message == null || message.messageOwner == null || message.messageOwner.params == null) {
            return false;
        }
        return message.messageOwner.params.containsKey(CreangerChatDetection.PARAM_DOCUMENT_URL);
    }

    /** Entry point: open from cache or download via HTTPS. */
    public static void openCreangerDocument(MessageObject message, Activity activity,
                                            Theme.ResourcesProvider resourcesProvider, boolean restrict) {
        if (message == null) {
            return;
        }
        String url = message.messageOwner != null && message.messageOwner.params != null
                ? message.messageOwner.params.get(CreangerChatDetection.PARAM_DOCUMENT_URL) : null;
        if (url == null || url.isEmpty() || !CreangerDocumentPreview.isTrustedProviderUrl(url)) {
            toast(activity, "Document download is not available for this file");
            return;
        }
        String fileName = message.getFileName();
        if (fileName == null || fileName.isEmpty()) {
            fileName = "creanger_document";
        }
        // 1. Local attachPath cache.
        String attachPath = message.messageOwner != null ? message.messageOwner.attachPath : null;
        if (attachPath != null && !attachPath.isEmpty()) {
            File cached = new File(attachPath);
            if (cached.exists() && cached.length() > 0) {
                AndroidUtilities.openForView(cached, fileName, mimeOf(fileName), activity, resourcesProvider, restrict);
                return;
            }
        }
        // 2. FileLoader cache dir (same file name).
        File loaderFile = FileLoader.getInstance(message.currentAccount).getPathToMessage(message.messageOwner, false);
        if (loaderFile != null && loaderFile.exists() && loaderFile.length() > 0) {
            AndroidUtilities.openForView(loaderFile, fileName, mimeOf(fileName), activity, resourcesProvider, restrict);
            return;
        }
        // 3. HTTPS download via DownloadManager.
        downloadViaManager(activity, url, fileName, mimeOf(fileName));
    }

    private static void downloadViaManager(Activity activity, String url, String fileName, String mimeType) {
        Context ctx = activity != null ? activity : ApplicationLoader.applicationContext;
        if (ctx == null) {
            return;
        }
        try {
            DownloadManager manager = (DownloadManager) ctx.getSystemService(Context.DOWNLOAD_SERVICE);
            if (manager == null) {
                toast(activity, "Download service unavailable");
                return;
            }
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setTitle(fileName);
            request.setDescription("Downloading document");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            if (mimeType != null) {
                request.setMimeType(mimeType);
            }
            long downloadId = manager.enqueue(request);
            final android.app.AlertDialog progress = progressDialog(activity, fileName);
            monitorDownload(manager, downloadId, activity, progress, fileName, mimeType);
        } catch (Exception e) {
            toast(activity, "Document download failed");
        }
    }

    private static void monitorDownload(DownloadManager manager, long downloadId, Activity activity,
                                        android.app.AlertDialog progressDialog,
                                        String fileName, String mimeType) {
        Handler handler = new Handler(Looper.getMainLooper());
        Runnable poll = new Runnable() {
            @Override
            public void run() {
                DownloadManager.Query query = new DownloadManager.Query().setFilterById(downloadId);
                int status = -1;
                String localUri = null;
                Cursor cursor = null;
                try {
                    cursor = manager.query(query);
                    if (cursor != null && cursor.moveToFirst()) {
                        int statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                        if (statusIdx >= 0) {
                            status = cursor.getInt(statusIdx);
                        }
                        int uriIdx = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI);
                        if (uriIdx >= 0) {
                            localUri = cursor.getString(uriIdx);
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    if (cursor != null) {
                        cursor.close();
                    }
                }
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    dismissDialog(progressDialog);
                    openDownloaded(activity, localUri, fileName, mimeType);
                } else if (status == DownloadManager.STATUS_FAILED) {
                    dismissDialog(progressDialog);
                    toast(activity, "Document download failed");
                } else {
                    handler.postDelayed(this, 500);
                }
            }
        };
        handler.postDelayed(poll, 500);
    }

    private static void openDownloaded(Activity activity, String localUri, String fileName, String mimeType) {
        Context ctx = activity != null ? activity : ApplicationLoader.applicationContext;
        if (ctx == null) {
            return;
        }
        try {
            Uri uri = localUri != null ? Uri.parse(localUri) : null;
            if (uri == null) {
                toast(activity, "Download finished but file is missing");
                return;
            }
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, mimeType != null ? mimeType : "*/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (activity != null) {
                activity.startActivity(intent);
            } else {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(intent);
            }
        } catch (ActivityNotFoundException e) {
            toast(activity, "No app can open this file type");
        } catch (Exception e) {
            toast(activity, "Cannot open downloaded file");
        }
    }

    private static android.app.AlertDialog progressDialog(Activity activity, String fileName) {
        if (activity == null) {
            return null;
        }
        try {
            android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(activity)
                    .setTitle("Downloading")
                    .setMessage(fileName)
                    .setCancelable(true)
                    .create();
            dialog.show();
            return dialog;
        } catch (Exception e) {
            return null;
        }
    }

    private static void dismissDialog(android.app.AlertDialog dialog) {
        if (dialog != null && dialog.isShowing()) {
            try {
                dialog.dismiss();
            } catch (Exception ignored) {
            }
        }
    }

    private static String mimeOf(String fileName) {
        try {
            String ext = MimeTypeMap.getFileExtensionFromUrl(fileName);
            if (ext != null && !ext.isEmpty()) {
                String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase());
                if (mime != null) {
                    return mime;
                }
            }
        } catch (Exception ignored) {
        }
        return "*/*";
    }

    private static void toast(Activity activity, String text) {
        Context ctx = activity != null ? activity : ApplicationLoader.applicationContext;
        if (ctx == null) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            try {
                Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show();
            } catch (Exception ignored) {
            }
        });
    }
}
