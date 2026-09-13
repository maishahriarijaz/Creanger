package com.creanger.app.ui.Components.voip;

import com.creanger.app.messenger.ApplicationLoader;
import com.creanger.app.messenger.BuildVars;
import com.creanger.app.messenger.FileLog;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Locale;

public class VoIPHelper {

	public static long lastCallTime = 0;

	public static File getLogsDir() {
		File logsDir = new File(ApplicationLoader.applicationContext.getCacheDir(), "voip_logs");
		if (!logsDir.exists()) {
			logsDir.mkdirs();
		}
		return logsDir;
	}

	public static String getLogFilePath(String name) {
		final Calendar c = Calendar.getInstance();
		final File externalFilesDir = ApplicationLoader.applicationContext.getExternalFilesDir(null);
		return new File(externalFilesDir, String.format(Locale.US, "logs/%02d_%02d_%04d_%02d_%02d_%02d_%s.txt",
				c.get(Calendar.DATE), c.get(Calendar.MONTH) + 1, c.get(Calendar.YEAR), c.get(Calendar.HOUR_OF_DAY),
				c.get(Calendar.MINUTE), c.get(Calendar.SECOND), name)).getAbsolutePath();
	}

	public static String getLogFilePath(String callId, boolean stats) {
		final File logsDir = getLogsDir();
		if (!BuildVars.DEBUG_VERSION) {
			final File[] _logs = logsDir.listFiles();
			if (_logs != null) {
				final ArrayList<File> logs = new ArrayList<>(Arrays.asList(_logs));
				while (logs.size() > 20) {
					File oldest = logs.get(0);
					for (File file : logs) {
						if (file.getName().endsWith(".log") && file.lastModified() < oldest.lastModified()) {
							oldest = file;
						}
					}
					oldest.delete();
					logs.remove(oldest);
				}
			}
		}
		if (stats) {
			return new File(logsDir, callId + "_stats.log").getAbsolutePath();
		} else {
			return new File(logsDir, callId + ".log").getAbsolutePath();
		}
	}
}