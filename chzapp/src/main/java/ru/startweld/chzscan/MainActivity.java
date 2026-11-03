package ru.startweld.chzscan;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Vibrator;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class MainActivity extends Activity {
    private static final char GS = 29;
    private static final String INTERNAL_KI = "chz_ki.txt";
    private static final String INTERNAL_RAW = "chz_raw.txt";
    private static final String XLSX_NAME = "ЧестныйЗнак_списание.xlsx";
    private static final String RAW_NAME = "ЧестныйЗнак_сканы.txt";

    private final Handler handler = new Handler();
    private final LinkedHashSet<String> codes = new LinkedHashSet<String>();
    private final ArrayList<String> rawScans = new ArrayList<String>();
    private final ArrayList<String> displayCodes = new ArrayList<String>();

    private TextView countText;
    private TextView statusText;
    private EditText scanInput;
    private ListView listView;
    private ArrayAdapter<String> adapter;
    private boolean internalClear = false;
    private String lastAdded = "";
    private long lastProcessedAt = 0L;
    private String lastProcessedRaw = "";

    private final Runnable delayedScan = new Runnable() {
        @Override public void run() {
            if (scanInput != null) processScan(scanInput.getText().toString());
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        loadInternal();
        buildUi();
        refreshUi();
        exportFiles();
        focusScanner();
    }

    private int dp(int value) {
        float d = getResources().getDisplayMetrics().density;
        return (int)(value * d + 0.5f);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), dp(10), dp(10), dp(10));
        root.setBackgroundColor(Color.rgb(244,246,248));

        TextView title = new TextView(this);
        title.setText("Честный знак — сканер");
        title.setTextSize(24);
        title.setTextColor(Color.BLACK);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Сканируйте Data Matrix. В XLSX сохраняется только КИ (01…21…), без криптохвоста.");
        subtitle.setTextSize(13);
        subtitle.setTextColor(Color.DKGRAY);
        subtitle.setPadding(0, dp(4), 0, dp(10));
        root.addView(subtitle);

        countText = new TextView(this);
        countText.setTextSize(28);
        countText.setTextColor(Color.BLACK);
        countText.setGravity(Gravity.CENTER);
        countText.setPadding(dp(8), dp(10), dp(8), dp(10));
        countText.setBackgroundColor(Color.rgb(238,242,245));
        root.addView(countText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView prompt = new TextView(this);
        prompt.setText("Сканирование:");
        prompt.setTextSize(17);
        prompt.setTextColor(Color.BLACK);
        prompt.setPadding(0, dp(10), 0, 0);
        root.addView(prompt);

        scanInput = new EditText(this);
        scanInput.setTextSize(18);
        scanInput.setSingleLine(true);
        scanInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        scanInput.setHint("Наведите сканер на Data Matrix");
        root.addView(scanInput, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));
        try {
            java.lang.reflect.Method m = EditText.class.getMethod("setShowSoftInputOnFocus", boolean.class);
            m.invoke(scanInput, false);
        } catch (Throwable ignored) {}

        statusText = new TextView(this);
        statusText.setTextSize(17);
        statusText.setGravity(Gravity.CENTER_VERTICAL);
        statusText.setPadding(dp(10), dp(10), dp(10), dp(10));
        root.addView(statusText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout buttons1 = new LinearLayout(this);
        buttons1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, dp(52), 1f);

        Button undo = new Button(this);
        undo.setText("Отменить");
        undo.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { undoLast(); }
        });
        buttons1.addView(undo, bp);

        Button export = new Button(this);
        export.setText("Обновить файл");
        export.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (exportFiles()) setStatus("Файлы обновлены в DCIM", 2);
                focusScanner();
            }
        });
        buttons1.addView(export, bp);
        root.addView(buttons1);

        LinearLayout buttons2 = new LinearLayout(this);
        buttons2.setOrientation(LinearLayout.HORIZONTAL);

        Button show = new Button(this);
        show.setText("Показать КИ");
        show.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                listView.setVisibility(listView.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                focusScanner();
            }
        });
        buttons2.addView(show, bp);

        Button reset = new Button(this);
        reset.setText("Начать заново");
        reset.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmReset(); }
        });
        buttons2.addView(reset, bp);
        root.addView(buttons2);

        listView = new ListView(this);
        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, displayCodes);
        listView.setAdapter(adapter);
        listView.setVisibility(View.GONE);
        root.addView(listView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView path = new TextView(this);
        path.setText("Файл для загрузки: DCIM/" + XLSX_NAME + "\nРезерв: DCIM/" + RAW_NAME);
        path.setTextSize(12);
        path.setTextColor(Color.DKGRAY);
        path.setPadding(0, dp(8), 0, 0);
        root.addView(path);

        setContentView(root);

        scanInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (internalClear) return;
                handler.removeCallbacks(delayedScan);
                if (s.length() > 0) handler.postDelayed(delayedScan, 220L);
            }
        });

        scanInput.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_UP && keyCode == KeyEvent.KEYCODE_ENTER) {
                    processScan(scanInput.getText().toString());
                    return true;
                }
                return false;
            }
        });
    }

    private void processScan(String raw) {
        handler.removeCallbacks(delayedScan);
        if (raw == null) return;
        raw = trimScannerEnding(raw);
        if (raw.length() == 0) return;

        long now = System.currentTimeMillis();
        if (raw.equals(lastProcessedRaw) && now - lastProcessedAt < 900L) {
            clearInput();
            return;
        }
        lastProcessedRaw = raw;
        lastProcessedAt = now;

        String ki = extractKi(raw);
        if (ki == null || ki.length() == 0) {
            appendRawBackup(raw);
            rewriteInternal();
            exportRawTxt();
            setStatus("НЕ УДАЛОСЬ ВЫДЕЛИТЬ КИ. Скан: " + preview(raw), -1);
            vibrate(180L);
            clearInput();
            return;
        }

        if (codes.contains(ki)) {
            setStatus("ДУБЛЬ: " + printable(ki), 1);
            vibrate(140L);
            clearInput();
            return;
        }

        codes.add(ki);
        rawScans.add(raw);
        lastAdded = ki;
        rewriteInternal();
        refreshUi();
        boolean exported = exportFiles();
        if (exported) {
            setStatus("ДОБАВЛЕН: " + printable(ki) + "   Всего: " + codes.size(), 2);
        } else {
            setStatus("КИ принят, но не удалось записать файл в DCIM", -1);
        }
        vibrate(55L);
        clearInput();
    }

    private String trimScannerEnding(String raw) {
        int start = 0;
        int end = raw.length();
        while (start < end && (raw.charAt(start) == '\r' || raw.charAt(start) == '\n' || raw.charAt(start) == ' ' || raw.charAt(start) == '\t')) start++;
        while (end > start && (raw.charAt(end - 1) == '\r' || raw.charAt(end - 1) == '\n' || raw.charAt(end - 1) == ' ' || raw.charAt(end - 1) == '\t')) end--;
        String s = raw.substring(start, end);
        if (s.startsWith("]d2") || s.startsWith("]D2")) s = s.substring(3);
        return s;
    }

    private String extractKi(String raw) {
        String s = trimScannerEnding(raw);
        int p = findAi01(s);
        if (p < 0) return null;

        int gtinStart = p + 2;
        int gtinEnd = gtinStart + 14;
        if (gtinEnd + 2 > s.length()) return null;

        for (int i = gtinStart; i < gtinEnd; i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return null;
        }
        if (s.charAt(gtinEnd) != '2' || s.charAt(gtinEnd + 1) != '1') return null;

        int serialStart = gtinEnd + 2;
        if (serialStart >= s.length()) return null;

        // Correct GS1 stream: KI ends at ASCII 29 before AI 91/92/93.
        int gs = s.indexOf(GS, serialStart);
        if (gs > serialStart) {
            return s.substring(p, gs);
        }

        // Some TSD keyboard-wedge modes silently remove ASCII 29.
        // Then detect the crypto tail from the GS1 AI sequence and keep only 01+GTIN+21+serial.
        String candidate = s.substring(p);
        if (containsControl(candidate)) return null;

        int crypto = findCryptoStartWithoutGs(candidate, serialStart - p);
        if (crypto > serialStart - p) {
            return candidate.substring(0, crypto);
        }

        // KI-only scans are also valid. Official structures commonly have 24, 25 or 31 characters.
        int[] knownKiLengths = new int[]{31, 25, 24};
        for (int len : knownKiLengths) {
            if (candidate.length() == len) return candidate;
        }

        return null;
    }

    private int findCryptoStartWithoutGs(String candidate, int serialOffset) {
        int[] knownKiLengths = new int[]{31, 25, 24};

        // Prefer exact official KI boundaries; this avoids confusing "91" inside a serial number with AI 91.
        for (int len : knownKiLengths) {
            if (candidate.length() >= len + 8 &&
                    startsAt(candidate, len, "91") &&
                    startsAt(candidate, len + 6, "92")) {
                return len;
            }
            if (candidate.length() == len + 6 && startsAt(candidate, len, "93")) {
                return len;
            }
        }

        // Fallback for other product groups: AI 91 has exactly 4 characters before AI 92.
        int min = serialOffset + 4;
        for (int i = min; i + 8 <= candidate.length(); i++) {
            if (startsAt(candidate, i, "91") && startsAt(candidate, i + 6, "92")) {
                return i;
            }
        }

        // Short crypto form: AI 93 + 4 verification characters at the end.
        for (int i = min; i + 6 == candidate.length(); i++) {
            if (startsAt(candidate, i, "93")) return i;
        }

        return -1;
    }

    private boolean startsAt(String s, int index, String token) {
        if (index < 0 || index + token.length() > s.length()) return false;
        return s.regionMatches(index, token, 0, token.length());
    }

    private String preview(String raw) {
        String s = printable(raw);
        s = s.replace("\r", " ").replace("\n", " ");
        if (s.length() > 55) return s.substring(0, 55) + "…";
        return s;
    }

    private int findAi01(String s) {
        for (int i = 0; i + 18 <= s.length(); i++) {
            if (s.charAt(i) == '0' && s.charAt(i + 1) == '1') {
                boolean digits = true;
                for (int j = i + 2; j < i + 16; j++) {
                    char c = s.charAt(j);
                    if (c < '0' || c > '9') { digits = false; break; }
                }
                if (digits && s.charAt(i + 16) == '2' && s.charAt(i + 17) == '1') return i;
            }
        }
        return -1;
    }

    private boolean containsControl(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 32 || c == 127) return true;
        }
        return false;
    }

    private void appendRawBackup(String raw) {
        rawScans.add(raw);
    }

    private void undoLast() {
        if (lastAdded == null || lastAdded.length() == 0 || !codes.contains(lastAdded)) {
            setStatus("Нечего отменять", 1);
            focusScanner();
            return;
        }
        codes.remove(lastAdded);
        if (!rawScans.isEmpty()) rawScans.remove(rawScans.size() - 1);
        String removed = lastAdded;
        lastAdded = "";
        rewriteInternal();
        refreshUi();
        exportFiles();
        setStatus("УДАЛЁН ПОСЛЕДНИЙ: " + printable(removed), 1);
        focusScanner();
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
            .setTitle("Начать заново?")
            .setMessage("Все накопленные КИ и резервные сканы будут удалены, а XLSX будет создан заново.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сбросить", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) {
                    codes.clear();
                    rawScans.clear();
                    lastAdded = "";
                    rewriteInternal();
                    refreshUi();
                    exportFiles();
                    setStatus("Список очищен", 0);
                    focusScanner();
                }
            }).show();
    }

    private void refreshUi() {
        displayCodes.clear();
        int n = 1;
        for (String code : codes) {
            displayCodes.add(n + ". " + printable(code));
            n++;
        }
        countText.setText("КИ: " + codes.size());
        if (adapter != null) adapter.notifyDataSetChanged();
        if (statusText != null && statusText.getText().length() == 0) setStatus("Готов к сканированию", 0);
    }

    private String printable(String s) {
        return s == null ? "" : s.replace(GS, ' ');
    }

    private void setStatus(String text, int kind) {
        statusText.setText(text);
        if (kind == 2) {
            statusText.setBackgroundColor(Color.rgb(25,135,84));
            statusText.setTextColor(Color.WHITE);
        } else if (kind == -1) {
            statusText.setBackgroundColor(Color.rgb(198,40,40));
            statusText.setTextColor(Color.WHITE);
        } else if (kind == 1) {
            statusText.setBackgroundColor(Color.rgb(179,107,0));
            statusText.setTextColor(Color.WHITE);
        } else {
            statusText.setBackgroundColor(Color.rgb(238,242,245));
            statusText.setTextColor(Color.BLACK);
        }
    }

    private void clearInput() {
        internalClear = true;
        scanInput.setText("");
        internalClear = false;
        focusScanner();
    }

    private void focusScanner() {
        if (scanInput == null) return;
        scanInput.requestFocus();
        try {
            InputMethodManager imm = (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(scanInput.getWindowToken(), 0);
        } catch (Throwable ignored) {}
    }

    private void vibrate(long ms) {
        try {
            Vibrator v = (Vibrator)getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.vibrate(ms);
        } catch (Throwable ignored) {}
    }

    private void loadInternal() {
        codes.clear();
        rawScans.clear();
        readLines(new File(getFilesDir(), INTERNAL_KI), codes, null);
        readLines(new File(getFilesDir(), INTERNAL_RAW), null, rawScans);
    }

    private void readLines(File file, LinkedHashSet<String> set, ArrayList<String> list) {
        if (!file.exists()) return;
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.length() == 0) continue;
                if (set != null) set.add(unescapeLine(line));
                if (list != null) list.add(unescapeLine(line));
            }
            r.close();
        } catch (Throwable ignored) {}
    }

    private void rewriteInternal() {
        writeInternalLines(new File(getFilesDir(), INTERNAL_KI), new ArrayList<String>(codes));
        writeInternalLines(new File(getFilesDir(), INTERNAL_RAW), rawScans);
    }

    private void writeInternalLines(File file, ArrayList<String> lines) {
        try {
            BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file, false), "UTF-8"));
            for (String line : lines) {
                w.write(escapeLine(line));
                w.newLine();
            }
            w.flush();
            w.close();
        } catch (Throwable ignored) {}
    }

    private String escapeLine(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n").replace(String.valueOf(GS), "\\x1D");
    }

    private String unescapeLine(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                if (n == '\\') { out.append('\\'); i++; continue; }
                if (n == 'r') { out.append('\r'); i++; continue; }
                if (n == 'n') { out.append('\n'); i++; continue; }
                if (n == 'x' && i + 4 < s.length() && "1D".equals(s.substring(i + 2, i + 4))) {
                    out.append(GS); i += 3; continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    private boolean exportFiles() {
        boolean a = exportXlsx();
        boolean b = exportRawTxt();
        return a && b;
    }

    private File dcim() {
        File d = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private boolean exportRawTxt() {
        try {
            File f = new File(dcim(), RAW_NAME);
            BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f, false), "UTF-8"));
            for (String raw : rawScans) {
                w.write(escapeLine(raw));
                w.newLine();
            }
            w.flush();
            w.close();
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private boolean exportXlsx() {
        File temp = new File(dcim(), XLSX_NAME + ".tmp");
        File target = new File(dcim(), XLSX_NAME);
        try {
            ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(temp, false));
            putZip(zip, "[Content_Types].xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
                "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
                "</Types>");
            putZip(zip, "_rels/.rels",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
                "</Relationships>");
            putZip(zip, "xl/workbook.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
                "<sheets><sheet name=\"Коды\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
            putZip(zip, "xl/_rels/workbook.xml.rels",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
                "</Relationships>");

            StringBuilder sheet = new StringBuilder();
            sheet.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
            sheet.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
            int row = 1;
            for (String code : codes) {
                sheet.append("<row r=\"").append(row).append("\"><c r=\"A").append(row)
                     .append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                     .append(xmlEscape(code))
                     .append("</t></is></c></row>");
                row++;
            }
            sheet.append("</sheetData></worksheet>");
            putZip(zip, "xl/worksheets/sheet1.xml", sheet.toString());
            zip.finish();
            zip.close();

            if (target.exists() && !target.delete()) return false;
            if (!temp.renameTo(target)) return false;
            return true;
        } catch (Throwable e) {
            try { temp.delete(); } catch (Throwable ignored) {}
            return false;
        }
    }

    private void putZip(ZipOutputStream zip, String name, String text) throws Exception {
        ZipEntry e = new ZipEntry(name);
        zip.putNextEntry(e);
        byte[] b = text.getBytes("UTF-8");
        zip.write(b, 0, b.length);
        zip.closeEntry();
    }

    private String xmlEscape(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&') b.append("&amp;");
            else if (c == '<') b.append("&lt;");
            else if (c == '>') b.append("&gt;");
            else if (c == '\"') b.append("&quot;");
            else if (c == '\'') b.append("&apos;");
            else if (c >= 32 || c == '\t' || c == '\n' || c == '\r') b.append(c);
        }
        return b.toString();
    }

    @Override protected void onResume() {
        super.onResume();
        focusScanner();
    }
}
