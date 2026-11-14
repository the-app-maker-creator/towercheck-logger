package nz.towercheck.logger;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {

    private TextView status;
    private TextView testOut;
    private EditText metresBox;
    private Button startStop;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int pad = dp(16);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("TowerCheck Logger");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(title);

        TextView help = new TextView(this);
        help.setText("Saves your GPS position, signal (RSRP) and the phone's transmit power (Tx Pwr from Service Mode) every set number of metres.\n\n"
                + "Do steps 1 and 2 once. Then each drive: set the distance, tap Start, open Service Mode (*#0011#), scroll so Tx Pwr is on screen, and leave it there.");
        help.setPadding(0, dp(8), 0, dp(12));
        col.addView(help);

        col.addView(button("1. Allow location and notifications", v -> askPermissions()));
        col.addView(button("2. Turn on the Service Mode reader", v -> openAccessibility()));

        TextView mLabel = new TextView(this);
        mLabel.setText("Log a point every … metres (0 = every second):");
        mLabel.setPadding(0, dp(12), 0, 0);
        col.addView(mLabel);

        metresBox = new EditText(this);
        metresBox.setInputType(InputType.TYPE_CLASS_NUMBER);
        metresBox.setText(String.valueOf(getPreferences(MODE_PRIVATE).getInt("metres", 10)));
        col.addView(metresBox);

        startStop = button("Start logging", v -> toggle());
        col.addView(startStop);

        col.addView(button("Open dialer with *#0011# (Service Mode)", v -> openServiceMode()));
        col.addView(button("Test: read Service Mode now", v -> testRead()));

        status = new TextView(this);
        status.setPadding(0, dp(12), 0, 0);
        status.setTextSize(16);
        col.addView(status);

        testOut = new TextView(this);
        testOut.setPadding(0, dp(12), 0, 0);
        testOut.setTypeface(Typeface.MONOSPACE);
        testOut.setTextSize(12);
        col.addView(testOut);

        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
    }

    private int dp(int x) {
        return Math.round(x * getResources().getDisplayMetrics().density);
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
    }

    private boolean hasLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void askPermissions() {
        List<String> p = new ArrayList<>();
        p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        p.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS);
        requestPermissions(p.toArray(new String[0]), 1);
    }

    private void openAccessibility() {
        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    private void openServiceMode() {
        Intent i = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode("*#0011#")));
        startActivity(i);
    }

    private void toggle() {
        if (LoggerService.running) {
            startService(new Intent(this, LoggerService.class).setAction(LoggerService.ACTION_STOP));
            return;
        }
        if (!hasLocation()) {
            askPermissions();
            return;
        }
        int m;
        try {
            m = Integer.parseInt(metresBox.getText().toString().trim());
        } catch (Exception e) {
            m = 10;
        }
        if (m < 0) m = 0;
        getPreferences(MODE_PRIVATE).edit().putInt("metres", m).apply();
        Intent i = new Intent(this, LoggerService.class)
                .setAction(LoggerService.ACTION_START)
                .putExtra(LoggerService.EXTRA_METRES, (float) m);
        startForegroundService(i);
    }

    private void testRead() {
        ProbeService p = ProbeService.instance;
        if (p == null) {
            testOut.setText("Service Mode reader is OFF. Do step 2 first.");
            return;
        }
        testOut.setText("Reading in 5 seconds… switch to Service Mode now.");
        handler.postDelayed(() -> {
            ProbeService.Snapshot s = p.read();
            Map<String, String> m = ServiceModeParser.parse(s.text);
            StringBuilder sb = new StringBuilder();
            sb.append("Screen app: ").append(s.packageName).append('\n');
            sb.append("Looks like Service Mode: ").append(s.isServiceMode ? "yes" : "no").append("\n\n");
            for (Map.Entry<String, String> e : m.entrySet()) {
                if (!e.getValue().isEmpty()) sb.append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
            }
            sb.append("\nRaw text:\n").append(s.text);
            testOut.setText(sb.toString());
        }, 5000);
    }

    private void refresh() {
        boolean reader = ProbeService.instance != null;
        StringBuilder sb = new StringBuilder();
        sb.append("Location: ").append(hasLocation() ? "allowed ✓" : "not allowed ✗").append('\n');
        sb.append("Service Mode reader: ").append(reader ? "on ✓" : "off ✗").append('\n');
        sb.append("Logging: ").append(LoggerService.running ? "RUNNING" : "stopped").append('\n');
        if (!LoggerService.lastSummary.isEmpty()) sb.append(LoggerService.lastSummary).append('\n');
        if (!LoggerService.lastSavedTo.isEmpty()) sb.append("Saved to: ").append(LoggerService.lastSavedTo).append('\n');
        status.setText(sb.toString());
        startStop.setText(LoggerService.running ? "Stop logging" : "Start logging");
    }
}
