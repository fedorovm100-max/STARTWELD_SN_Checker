package ru.startweld.sncheck;

import android.app.Application;
import android.content.SharedPreferences;
import android.media.MediaScannerConnection;
import android.os.Environment;
import android.os.Handler;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;

public class RemainderFileApplication extends Application {
    private static final String PREFS = "sn_check_state_v1";
    private static final String PREF_SCANNED = "scanned";
    private static final int FIRST = 201;
    private static final int LAST = 600;
    private static final long CHECK_INTERVAL_MS = 1000L;

    private final Handler handler = new Handler();
    private String lastWrittenContent = null;

    private final Runnable syncTask = new Runnable() {
        @Override public void run() {
            try {
                syncRemainderFile();
            } catch (Throwable ignored) {
                // Keep the scanner usable even if external storage is temporarily unavailable.
            }
            handler.postDelayed(this, CHECK_INTERVAL_MS);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        handler.post(syncTask);
    }

    private String makeSn(int n) {
        return String.format(Locale.US, "SN260724N500%03d", n);
    }

    private String buildRemainderText() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        Set<String> scanned = prefs.getStringSet(PREF_SCANNED, Collections.<String>emptySet());
        StringBuilder out = new StringBuilder();
        for (int i = FIRST; i <= LAST; i++) {
            String sn = makeSn(i);
            if (scanned == null || !scanned.contains(sn)) {
                if (out.length() > 0) out.append('\n');
                out.append(sn);
            }
        }
        if (out.length() > 0) out.append('\n');
        return out.toString();
    }

    private void syncRemainderFile() throws Exception {
        String content = buildRemainderText();
        if (content.equals(lastWrittenContent)) return;

        File dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
        if (!dcim.exists() && !dcim.mkdirs() && !dcim.isDirectory()) {
            throw new IllegalStateException("Cannot create DCIM directory");
        }

        File target = new File(dcim, "остаток.txt");
        File temp = new File(dcim, "остаток.tmp");

        FileOutputStream stream = new FileOutputStream(temp, false);
        OutputStreamWriter writer = new OutputStreamWriter(stream, "UTF-8");
        try {
            writer.write(content);
            writer.flush();
            stream.getFD().sync();
        } finally {
            try { writer.close(); } catch (Throwable ignored) { }
        }

        if (target.exists() && !target.delete()) {
            throw new IllegalStateException("Cannot replace remainder file");
        }
        if (!temp.renameTo(target)) {
            // Fallback for file systems where rename is not available.
            FileOutputStream directStream = new FileOutputStream(target, false);
            OutputStreamWriter directWriter = new OutputStreamWriter(directStream, "UTF-8");
            try {
                directWriter.write(content);
                directWriter.flush();
                directStream.getFD().sync();
            } finally {
                try { directWriter.close(); } catch (Throwable ignored) { }
            }
            temp.delete();
        }

        lastWrittenContent = content;
        try {
            MediaScannerConnection.scanFile(
                getApplicationContext(),
                new String[]{ target.getAbsolutePath() },
                new String[]{ "text/plain" },
                null
            );
        } catch (Throwable ignored) { }
    }
}
