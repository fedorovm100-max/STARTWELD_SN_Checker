package ru.startweld.sncheck;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
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
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final String PREFS = "sn_check_state_v1";
    private static final String PREF_SCANNED = "scanned";
    private static final String PREF_LAST = "last";
    private static final int FIRST = 201;
    private static final int LAST = 600;
    private static final int TOTAL = LAST - FIRST + 1;
    private static final Pattern FULL = Pattern.compile("SN260724N500([0-9]{3})");
    private static final Pattern NO_SN = Pattern.compile("260724N500([0-9]{3})");
    private static final Pattern SHORT = Pattern.compile("N500([0-9]{3})");

    private final Handler handler = new Handler();
    private final HashSet<String> scanned = new HashSet<String>();
    private final ArrayList<String> missing = new ArrayList<String>();

    private TextView totalText;
    private TextView foundText;
    private TextView leftText;
    private TextView statusText;
    private EditText scanInput;
    private ListView listView;
    private ArrayAdapter<String> listAdapter;
    private Button toggleListButton;
    private String lastAccepted = "";
    private boolean internalClear = false;
    private long lastProcessedAt = 0L;
    private String lastProcessedSn = "";

    private final Runnable delayedScan = new Runnable() {
        @Override public void run() {
            if (scanInput != null) processRaw(scanInput.getText().toString());
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        loadState();
        buildUi();
        refreshAll();
        focusScanner();
    }

    private int dp(int value) {
        float d = getResources().getDisplayMetrics().density;
        return (int)(value * d + 0.5f);
    }

    private LinearLayout makeStat(String value, String label) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(4), dp(8), dp(4), dp(8));
        box.setBackgroundColor(Color.rgb(238,242,245));

        TextView n = new TextView(this);
        n.setText(value);
        n.setTextSize(28);
        n.setTextColor(Color.BLACK);
        n.setGravity(Gravity.CENTER);
        box.addView(n, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(12);
        l.setTextColor(Color.DKGRAY);
        l.setGravity(Gravity.CENTER);
        box.addView(l, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if ("в списке".equals(label)) totalText = n;
        if ("найдено".equals(label)) foundText = n;
        if ("осталось".equals(label)) leftText = n;
        return box;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10),dp(10),dp(10),dp(10));
        root.setBackgroundColor(Color.rgb(244,246,248));

        TextView title = new TextView(this);
        title.setText("Проверка серийных номеров");
        title.setTextSize(24);
        title.setTextColor(Color.BLACK);
        title.setPadding(0,0,0,dp(10));
        root.addView(title);

        LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        stats.setPadding(0,0,0,dp(10));
        LinearLayout.LayoutParams statParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        statParams.setMargins(dp(2),0,dp(2),0);
        stats.addView(makeStat("400","в списке"), statParams);
        stats.addView(makeStat("0","найдено"), statParams);
        stats.addView(makeStat("400","осталось"), statParams);
        root.addView(stats);

        TextView prompt = new TextView(this);
        prompt.setText("Сканируйте серийный номер:");
        prompt.setTextSize(17);
        prompt.setTextColor(Color.BLACK);
        root.addView(prompt);

        scanInput = new EditText(this);
        scanInput.setTextSize(22);
        scanInput.setSingleLine(true);
        scanInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        scanInput.setHint("SN260724N500201");
        root.addView(scanInput, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));
        try {
            java.lang.reflect.Method m = EditText.class.getMethod("setShowSoftInputOnFocus", boolean.class);
            m.invoke(scanInput, false);
        } catch (Throwable ignored) { }

        statusText = new TextView(this);
        statusText.setTextSize(18);
        statusText.setTextColor(Color.BLACK);
        statusText.setGravity(Gravity.CENTER_VERTICAL);
        statusText.setPadding(dp(10),dp(10),dp(10),dp(10));
        statusText.setText("Готов к сканированию");
        statusText.setBackgroundColor(Color.rgb(238,242,245));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.setMargins(0,dp(6),0,dp(8));
        root.addView(statusText,statusParams);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, dp(52), 1f);
        Button undo = new Button(this);
        undo.setText("Отменить последнее");
        undo.setOnClickListener(new View.OnClickListener(){ @Override public void onClick(View v){ undoLast(); }});
        Button copy = new Button(this);
        copy.setText("Копировать остаток");
        copy.setOnClickListener(new View.OnClickListener(){ @Override public void onClick(View v){ copyMissing(); }});
        row1.addView(undo,bp); row1.addView(copy,bp); root.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        toggleListButton = new Button(this);
        toggleListButton.setText("Показать остаток");
        toggleListButton.setOnClickListener(new View.OnClickListener(){ @Override public void onClick(View v){ toggleList(); }});
        Button reset = new Button(this);
        reset.setText("Начать заново");
        reset.setOnClickListener(new View.OnClickListener(){ @Override public void onClick(View v){ confirmReset(); }});
        row2.addView(toggleListButton,bp); row2.addView(reset,bp); root.addView(row2);

        listView = new ListView(this);
        listAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, missing);
        listView.setAdapter(listAdapter);
        listView.setVisibility(View.GONE);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener(){
            @Override public void onItemClick(AdapterView<?> parent, View view, int position, long id){
                if(position >= 0 && position < missing.size()) setStatus("Не найден: " + missing.get(position),0);
            }
        });
        root.addView(listView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f));

        TextView hint = new TextView(this);
        hint.setText("Сканировать можно без Enter. Прогресс сохраняется автоматически.");
        hint.setTextSize(12);
        hint.setTextColor(Color.DKGRAY);
        hint.setPadding(0,dp(8),0,0);
        root.addView(hint);

        setContentView(root);

        scanInput.addTextChangedListener(new TextWatcher(){
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            @Override public void onTextChanged(CharSequence s,int start,int before,int count){}
            @Override public void afterTextChanged(Editable s){
                if(internalClear) return;
                handler.removeCallbacks(delayedScan);
                String raw = s.toString();
                String normalized = normalize(raw);
                if(isValidOriginal(normalized)) processRaw(raw);
                else if(raw.length() > 0) handler.postDelayed(delayedScan,250L);
            }
        });
        scanInput.setOnKeyListener(new View.OnKeyListener(){
            @Override public boolean onKey(View v,int keyCode,KeyEvent event){
                if(event.getAction()==KeyEvent.ACTION_UP && keyCode==KeyEvent.KEYCODE_ENTER){
                    processRaw(scanInput.getText().toString()); return true;
                }
                return false;
            }
        });
    }

    private String makeSn(int n){ return String.format(Locale.US,"SN260724N500%03d",n); }

    private boolean isValidOriginal(String sn){
        if(sn==null) return false;
        Matcher m = FULL.matcher(sn);
        if(!m.matches()) return false;
        try {
            int n = Integer.parseInt(m.group(1));
            return n>=FIRST && n<=LAST && sn.equals(makeSn(n));
        } catch(Exception e){ return false; }
    }

    private String normalize(String raw){
        if(raw==null) return "";
        String s = raw.toUpperCase(Locale.US).replaceAll("[^A-Z0-9]","");
        Matcher m = FULL.matcher(s); if(m.find()) return "SN260724N500"+m.group(1);
        m = NO_SN.matcher(s); if(m.find()) return "SN260724N500"+m.group(1);
        m = SHORT.matcher(s); if(m.find()) return "SN260724N500"+m.group(1);
        return s;
    }

    private void processRaw(String raw){
        handler.removeCallbacks(delayedScan);
        String sn = normalize(raw);
        if(sn.length()==0) return;
        long now = System.currentTimeMillis();
        if(sn.equals(lastProcessedSn) && now-lastProcessedAt<800L){ clearInput(); return; }
        lastProcessedSn = sn; lastProcessedAt = now;
        if(!isValidOriginal(sn)){
            setStatus("НЕТ В СПИСКЕ: "+sn,-1); vibrate(180L); clearInput(); return;
        }
        if(scanned.contains(sn)){
            setStatus("УЖЕ СКАНИРОВАЛИ: "+sn,1); vibrate(160L); clearInput(); return;
        }
        scanned.add(sn); lastAccepted = sn; saveState(); refreshAll();
        int left = TOTAL - scanned.size();
        setStatus(left==0 ? "ГОТОВО: найдены все 400 СН" : "НАЙДЕН: "+sn+"   Осталось: "+left,2);
        vibrate(55L); clearInput();
    }

    private void clearInput(){
        internalClear=true; scanInput.setText(""); internalClear=false; focusScanner();
    }

    private void focusScanner(){
        if(scanInput==null) return;
        scanInput.requestFocus();
        try {
            InputMethodManager imm=(InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
            if(imm!=null) imm.hideSoftInputFromWindow(scanInput.getWindowToken(),0);
        } catch(Throwable ignored) { }
    }

    private void setStatus(String text,int kind){
        statusText.setText(text);
        if(kind==2){ statusText.setBackgroundColor(Color.rgb(25,135,84)); statusText.setTextColor(Color.WHITE); }
        else if(kind==-1){ statusText.setBackgroundColor(Color.rgb(198,40,40)); statusText.setTextColor(Color.WHITE); }
        else if(kind==1){ statusText.setBackgroundColor(Color.rgb(179,107,0)); statusText.setTextColor(Color.WHITE); }
        else { statusText.setBackgroundColor(Color.rgb(238,242,245)); statusText.setTextColor(Color.BLACK); }
    }

    private void vibrate(long ms){
        try { Vibrator v=(Vibrator)getSystemService(Context.VIBRATOR_SERVICE); if(v!=null) v.vibrate(ms); } catch(Throwable ignored) { }
    }

    private void refreshAll(){
        missing.clear();
        for(int i=FIRST;i<=LAST;i++){ String sn=makeSn(i); if(!scanned.contains(sn)) missing.add(sn); }
        totalText.setText(String.valueOf(TOTAL));
        foundText.setText(String.valueOf(scanned.size()));
        leftText.setText(String.valueOf(missing.size()));
        if(listAdapter!=null) listAdapter.notifyDataSetChanged();
    }

    private void undoLast(){
        if(lastAccepted==null || lastAccepted.length()==0 || !scanned.contains(lastAccepted)){
            setStatus("Нечего отменять",1); focusScanner(); return;
        }
        String sn=lastAccepted; scanned.remove(sn); lastAccepted=""; saveState(); refreshAll();
        setStatus("ВОЗВРАЩЕН В СПИСОК: "+sn,1); focusScanner();
    }

    private void toggleList(){
        if(listView.getVisibility()==View.VISIBLE){ listView.setVisibility(View.GONE); toggleListButton.setText("Показать остаток"); }
        else { refreshAll(); listView.setVisibility(View.VISIBLE); toggleListButton.setText("Скрыть остаток"); }
        focusScanner();
    }

    private void copyMissing(){
        refreshAll();
        StringBuilder b=new StringBuilder();
        for(String sn:missing){ if(b.length()>0) b.append('\n'); b.append(sn); }
        try {
            ClipboardManager cm=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
            if(cm!=null) cm.setPrimaryClip(ClipData.newPlainText("Недостающие СН",b.toString()));
            setStatus("Остаток скопирован: "+missing.size()+" СН",2);
        } catch(Throwable e){ setStatus("Не удалось скопировать остаток",-1); }
        focusScanner();
    }

    private void confirmReset(){
        new AlertDialog.Builder(this)
            .setTitle("Начать заново?")
            .setMessage("Все отметки о найденных СН будут удалены.")
            .setNegativeButton("Отмена",null)
            .setPositiveButton("Сбросить",new DialogInterface.OnClickListener(){
                @Override public void onClick(DialogInterface dialog,int which){
                    scanned.clear(); lastAccepted=""; saveState(); refreshAll(); setStatus("Проверка начата заново",0); focusScanner();
                }
            }).show();
    }

    private void loadState(){
        SharedPreferences p=getSharedPreferences(PREFS,MODE_PRIVATE);
        Set<String> saved=p.getStringSet(PREF_SCANNED,null);
        if(saved!=null){ for(String sn:saved){ if(isValidOriginal(sn)) scanned.add(sn); } }
        String last=p.getString(PREF_LAST,"");
        if(last!=null && scanned.contains(last)) lastAccepted=last;
    }

    private void saveState(){
        getSharedPreferences(PREFS,MODE_PRIVATE).edit()
            .putStringSet(PREF_SCANNED,new HashSet<String>(scanned))
            .putString(PREF_LAST,lastAccepted==null?"":lastAccepted)
            .commit();
    }

    @Override protected void onResume(){ super.onResume(); focusScanner(); }
}
