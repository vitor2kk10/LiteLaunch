package com.leve.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.AlarmClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Leve — launcher minimalista. Sem ícones, sem widgets, sem bibliotecas.
 * Tela inicial: relógio, data e apps fixados (em texto).
 * Deslize para cima: lista de todos os apps com busca.
 * Deslize para baixo: abre as notificações.
 */
public class MainActivity extends Activity {

    static class App {
        String label, key, search;
        ComponentName cn;
    }

    private final List<App> all = new ArrayList<>();
    private final List<App> shown = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private SharedPreferences prefs;
    private LinearLayout home, favBox;
    private LinearLayout drawer;
    private EditText search;
    private ListView list;
    private BaseAdapter adapter;
    private GestureDetector gestures;
    private int pad;

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { loadApps(); }
    };

    // ---------------------------------------------------------------- ciclo de vida

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("leve", MODE_PRIVATE);
        pad = dp(28);

        Window w = getWindow();
        w.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        FrameLayout root = new FrameLayout(this);
        root.addView(buildHome(), match());
        root.addView(buildDrawer(), match());
        drawer.setVisibility(View.GONE);
        setContentView(root);

        root.setOnApplyWindowInsetsListener((v, ins) -> {
            int top = ins.getSystemWindowInsetTop();
            int bottom = ins.getSystemWindowInsetBottom(); // inclui o teclado
            home.setPadding(pad, top + dp(56), pad, bottom + dp(24));
            drawer.setPadding(pad, top + dp(20), pad, bottom);
            return ins;
        });

        gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null) return false;
                float dy = e2.getY() - e1.getY(), dx = e2.getX() - e1.getX();
                if (Math.abs(dy) < Math.abs(dx) || Math.abs(dy) < dp(60)) return false;
                if (dy < 0) openDrawer(); else expandNotifications();
                return true;
            }
        });

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(pkgReceiver, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(pkgReceiver, f);

        loadApps();
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(pkgReceiver); } catch (Exception ignored) { }
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        closeDrawer(true); // botão Home volta para a tela inicial
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (drawer.getVisibility() == View.VISIBLE) closeDrawer(false);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (drawer.getVisibility() == View.VISIBLE) closeDrawer(true);
        // na tela inicial, "voltar" não faz nada (comportamento de launcher)
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (drawer.getVisibility() != View.VISIBLE) gestures.onTouchEvent(ev);
        return super.dispatchTouchEvent(ev);
    }

    // ---------------------------------------------------------------- tela inicial

    private View buildHome() {
        home = new LinearLayout(this);
        home.setOrientation(LinearLayout.VERTICAL);
        // leve escurecido em cima e embaixo para o texto ficar legível em qualquer papel de parede
        home.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x55000000, 0x00000000, 0x00000000, 0x88000000}));
        home.setLongClickable(true);
        home.setOnLongClickListener(v -> { settingsMenu(); return true; });

        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("h:mm");
        style(clock, 78, "sans-serif-thin", 0xFFFFFFFF);
        clock.setLetterSpacing(-0.02f);
        clock.setOnClickListener(v -> startSafe(new Intent(AlarmClock.ACTION_SHOW_ALARMS)));
        home.addView(clock);

        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEEE, d 'de' MMMM");
        date.setFormat12Hour("EEEE, d 'de' MMMM");
        style(date, 17, "sans-serif-light", 0xD9FFFFFF);
        date.setPadding(dp(4), 0, 0, 0);
        date.setOnClickListener(v -> startSafe(new Intent(Intent.ACTION_VIEW,
                Uri.parse("content://com.android.calendar/time"))));
        home.addView(date);

        View spacer = new View(this);
        home.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));

        favBox = new LinearLayout(this);
        favBox.setOrientation(LinearLayout.VERTICAL);
        home.addView(favBox);

        TextView hint = new TextView(this);
        hint.setText("︿");
        style(hint, 16, "sans-serif-light", 0x80FFFFFF);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(28), 0, 0);
        home.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        return home;
    }

    private void renderFavs() {
        favBox.removeAllViews();
        for (String key : favKeys()) {
            App a = find(key);
            if (a == null) continue;
            TextView t = row(a.label, 26, "sans-serif-light");
            t.setPadding(0, dp(9), 0, dp(9));
            t.setOnClickListener(v -> launch(a));
            t.setOnLongClickListener(v -> { appMenu(a); return true; });
            favBox.addView(t);
        }
        if (favBox.getChildCount() == 0) {
            TextView t = row("Deslize para cima e segure um app\npara fixá-lo aqui.", 16, "sans-serif-light");
            t.setTextColor(0xB3FFFFFF);
            favBox.addView(t);
        }
    }

    // ---------------------------------------------------------------- gaveta de apps

    private View buildDrawer() {
        drawer = new LinearLayout(this);
        drawer.setOrientation(LinearLayout.VERTICAL);
        drawer.setBackgroundColor(0xEB0E0E12);
        drawer.setClickable(true);

        search = new EditText(this);
        search.setHint("Buscar app");
        search.setHintTextColor(0x66FFFFFF);
        style(search, 22, "sans-serif-light", 0xFFFFFFFF);
        search.setBackground(null);
        search.setSingleLine(true);
        search.setPadding(0, dp(12), 0, dp(12));
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setImeOptions(EditorInfo.IME_ACTION_GO);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable s) { filter(s.toString()); }
        });
        search.setOnEditorActionListener((v, id, ev) -> {
            if (!shown.isEmpty()) launch(shown.get(0));
            return true;
        });
        drawer.addView(search, new LinearLayout.LayoutParams(-1, -2));

        View line = new View(this);
        line.setBackgroundColor(0x22FFFFFF);
        drawer.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));

        list = new ListView(this);
        list.setDivider(null);
        list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        list.setVerticalScrollBarEnabled(false);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setClipToPadding(false);
        list.setPadding(0, dp(8), 0, dp(24));
        adapter = new BaseAdapter() {
            public int getCount() { return shown.size(); }
            public Object getItem(int p) { return shown.get(p); }
            public long getItemId(int p) { return p; }
            public View getView(int p, View convert, ViewGroup parent) {
                TextView t = convert instanceof TextView ? (TextView) convert
                        : row("", 20, "sans-serif-light");
                t.setText(shown.get(p).label);
                t.setPadding(0, dp(11), 0, dp(11));
                return t;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> launch(shown.get(pos)));
        list.setOnItemLongClickListener((p, v, pos, id) -> { appMenu(shown.get(pos)); return true; });
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            public void onScrollStateChanged(AbsListView v, int state) {
                if (state == SCROLL_STATE_TOUCH_SCROLL) hideKeyboard();
            }
            public void onScroll(AbsListView v, int a, int b, int c) { }
        });
        drawer.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));
        return drawer;
    }

    private void openDrawer() {
        if (drawer.getVisibility() == View.VISIBLE) return;
        search.setText("");
        list.setSelection(0);
        drawer.setAlpha(0f);
        drawer.setTranslationY(dp(40));
        drawer.setVisibility(View.VISIBLE);
        drawer.animate().alpha(1f).translationY(0).setDuration(160).start();
    }

    private void closeDrawer(boolean animate) {
        hideKeyboard();
        search.clearFocus();
        if (!animate) { drawer.animate().cancel(); drawer.setVisibility(View.GONE); return; }
        drawer.animate().alpha(0f).translationY(dp(40)).setDuration(120)
                .withEndAction(() -> drawer.setVisibility(View.GONE)).start();
    }

    private void filter(String q) {
        String n = norm(q);
        shown.clear();
        Set<String> hidden = hiddenKeys();
        for (App a : all) {
            if (n.isEmpty()) { if (!hidden.contains(a.key)) shown.add(a); }
            else if (a.search.contains(n)) shown.add(a); // na busca, até os ocultos aparecem
        }
        adapter.notifyDataSetChanged();
    }

    // ---------------------------------------------------------------- apps

    private void loadApps() {
        new Thread(() -> {
            PackageManager pm = getPackageManager();
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> ris = pm.queryIntentActivities(i, 0);
            List<App> out = new ArrayList<>();
            for (ResolveInfo ri : ris) {
                if (getPackageName().equals(ri.activityInfo.packageName)) continue;
                App a = new App();
                a.cn = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
                a.key = a.cn.flattenToString();
                a.label = String.valueOf(ri.loadLabel(pm)).trim();
                a.search = norm(a.label);
                out.add(a);
            }
            Collections.sort(out, (x, y) -> x.search.compareTo(y.search));
            ui.post(() -> {
                all.clear();
                all.addAll(out);
                filter(search.getText().toString());
                renderFavs();
            });
        }).start();
    }

    private App find(String key) {
        for (App a : all) if (a.key.equals(key)) return a;
        return null;
    }

    private void launch(App a) {
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(a.cn)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        startSafe(i);
    }

    private void startSafe(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Não foi possível abrir", Toast.LENGTH_SHORT).show();
        }
    }

    @SuppressWarnings({"WrongConstant", "JavaReflectionMemberAccess"})
    private void expandNotifications() {
        try {
            Object sb = getSystemService("statusbar");
            Class.forName("android.app.StatusBarManager")
                    .getMethod("expandNotificationsPanel").invoke(sb);
        } catch (Exception ignored) { }
    }

    // ---------------------------------------------------------------- menus

    private void appMenu(App a) {
        boolean fav = favKeys().contains(a.key);
        boolean hid = hiddenKeys().contains(a.key);
        String[] opts = {
                fav ? "Desafixar da tela inicial" : "Fixar na tela inicial",
                hid ? "Mostrar na lista" : "Ocultar da lista",
                "Informações do app",
                "Desinstalar"
        };
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(a.label)
                .setItems(opts, (d, which) -> {
                    Uri pkg = Uri.parse("package:" + a.cn.getPackageName());
                    switch (which) {
                        case 0: toggle("favs", a.key); renderFavs(); break;
                        case 1: toggle("hidden", a.key); filter(search.getText().toString()); break;
                        case 2: startSafe(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)); break;
                        case 3: startSafe(new Intent(Intent.ACTION_DELETE, pkg)); break;
                    }
                }).show();
    }

    private void settingsMenu() {
        String[] opts = {"Trocar papel de parede", "Reexibir apps ocultos", "Escolher launcher padrão"};
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Leve")
                .setItems(opts, (d, which) -> {
                    switch (which) {
                        case 0: startSafe(Intent.createChooser(
                                new Intent(Intent.ACTION_SET_WALLPAPER), "Papel de parede")); break;
                        case 1: prefs.edit().remove("hidden").apply();
                                filter(search.getText().toString()); break;
                        case 2: startSafe(new Intent(Settings.ACTION_HOME_SETTINGS)); break;
                    }
                }).show();
    }

    // ---------------------------------------------------------------- preferências

    private List<String> favKeys() { return readList("favs"); }

    private Set<String> hiddenKeys() { return new HashSet<>(readList("hidden")); }

    private List<String> readList(String name) {
        List<String> out = new ArrayList<>();
        for (String s : prefs.getString(name, "").split("\n")) if (!s.isEmpty()) out.add(s);
        return out;
    }

    private void toggle(String name, String key) {
        LinkedHashSet<String> set = new LinkedHashSet<>(readList(name));
        if (!set.remove(key)) set.add(key);
        prefs.edit().putString(name, String.join("\n", set)).apply();
    }

    // ---------------------------------------------------------------- utilidades

    private TextView row(String text, int sp, String font) {
        TextView t = new TextView(this);
        t.setText(text);
        style(t, sp, font, 0xFFFFFFFF);
        t.setSingleLine(!text.contains("\n"));
        t.setLayoutParams(new AbsListView.LayoutParams(-1, -2));
        return t;
    }

    private void style(TextView t, int sp, String font, int color) {
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Typeface.create(font, Typeface.NORMAL));
        t.setTextColor(color);
        t.setShadowLayer(10f, 0f, 1f, 0x66000000);
        t.setIncludeFontPadding(false);
    }

    private static String norm(String s) {
        return Normalizer.normalize(s.toLowerCase(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(-1, -1);
    }
}
