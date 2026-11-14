package nz.towercheck.logger;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.telephony.CellIdentityLte;
import android.telephony.CellInfo;
import android.telephony.CellInfoLte;
import android.telephony.CellSignalStrength;
import android.telephony.CellSignalStrengthLte;
import android.telephony.CellSignalStrengthNr;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Foreground service: listens to GPS, and every N metres saves one row with
 * position, Android's own cell readings, and whatever Service Mode shows.
 */
public class LoggerService extends Service implements LocationListener {

    public static final String ACTION_START = "nz.towercheck.logger.START";
    public static final String ACTION_STOP = "nz.towercheck.logger.STOP";
    public static final String EXTRA_METRES = "metres";

    private static final String CHANNEL = "logging";
    private static final int NOTIF_ID = 1;

    // Simple shared state for the screen.
    static volatile boolean running;
    static volatile int points;
    static volatile String lastSummary = "";
    static volatile String lastSavedTo = "";

    private float metres = 10f;
    private Location lastLogged;
    private BufferedWriter writer;
    private File file;
    private LocationManager lm;
    private TelephonyManager tm;
    private PowerManager.WakeLock wakeLock;

    private static final String[] BASE = {
            "time", "lat", "lon", "gps_accuracy_m", "speed_kmh", "altitude_m", "step_m",
            "api_rsrp", "api_rsrq", "api_rssi", "api_sinr", "api_pci", "api_ci", "api_tac",
            "api_earfcn", "api_band", "api_nr_ss_rsrp", "api_nr_ss_sinr"
    };
    private static final String[] TAIL = {"sm_seen", "sm_package", "sm_raw"};

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopLogging();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action) && !running) {
            metres = intent.getFloatExtra(EXTRA_METRES, 10f);
            startLogging();
        }
        return START_NOT_STICKY;
    }

    private void startLogging() {
        createChannel();
        Notification n = buildNotification("Starting… waiting for GPS");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIF_ID, n);
        }

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            lastSummary = "No location permission";
            stopSelf();
            return;
        }

        try {
            String stamp = new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss", Locale.US).format(new Date());
            File dir = getExternalFilesDir(null);
            if (dir == null) dir = getFilesDir();
            file = new File(dir, "TowerCheck_" + stamp + ".csv");
            writer = new BufferedWriter(new FileWriter(file));
            StringBuilder h = new StringBuilder();
            for (String s : BASE) h.append(s).append(',');
            for (String s : ServiceModeParser.KEYS) h.append(s).append(',');
            for (int i = 0; i < TAIL.length; i++) {
                h.append(TAIL[i]);
                if (i < TAIL.length - 1) h.append(',');
            }
            writer.write(h.toString());
            writer.newLine();
            writer.flush();
        } catch (Exception e) {
            lastSummary = "Could not create file: " + e.getMessage();
            stopSelf();
            return;
        }

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "towercheck:log");
        wakeLock.acquire(6 * 60 * 60 * 1000L);

        tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper());
        } catch (SecurityException e) {
            lastSummary = "Location blocked";
            stopSelf();
            return;
        }

        points = 0;
        lastLogged = null;
        lastSavedTo = "";
        running = true;
        lastSummary = "Waiting for GPS…";
    }

    @Override
    public void onLocationChanged(Location loc) {
        if (!running || writer == null) return;
        // Ignore poor GPS fixes (indoors / just started) so jitter doesn't create fake moves.
        if (loc.hasAccuracy() && loc.getAccuracy() > 25f) {
            lastSummary = "Waiting for good GPS (±" + Math.round(loc.getAccuracy()) + " m)";
            return;
        }
        float step = lastLogged == null ? 0f : loc.distanceTo(lastLogged);
        if (lastLogged != null && step < metres) return;
        lastLogged = loc;
        try {
            writeRow(loc, step);
        } catch (Exception e) {
            lastSummary = "Write error: " + e.getMessage();
        }
    }

    private static String v(int x) {
        return (x == Integer.MAX_VALUE || x == CellInfo.UNAVAILABLE) ? "" : String.valueOf(x);
    }

    /** LTE band from the downlink channel number (bands used in NZ). */
    private static String bandFromEarfcn(int e) {
        if (e < 0 || e == Integer.MAX_VALUE) return "";
        if (e < 600) return "1";
        if (e >= 1200 && e < 1950) return "3";
        if (e >= 2750 && e < 3450) return "7";
        if (e >= 3450 && e < 3800) return "8";
        if (e >= 9210 && e < 9660) return "28";
        if (e >= 37750 && e < 38250) return "38";
        if (e >= 38650 && e < 39650) return "40";
        if (e >= 41590 && e < 43590) return "42";
        return "";
    }

    private static String csv(String s) {
        if (s == null) return "";
        String t = s.replace("\r", "").replace("\n", " | ").replace("\"", "\"\"");
        return "\"" + t + "\"";
    }

    private void writeRow(Location loc, float step) throws Exception {
        String[] api = new String[11];
        Arrays.fill(api, "");
        // Signal strengths straight from Android.
        try {
            SignalStrength ss = tm.getSignalStrength();
            if (ss != null) {
                for (CellSignalStrength c : ss.getCellSignalStrengths()) {
                    if (c instanceof CellSignalStrengthLte) {
                        CellSignalStrengthLte l = (CellSignalStrengthLte) c;
                        api[0] = v(l.getRsrp());
                        api[1] = v(l.getRsrq());
                        api[2] = v(l.getRssi());
                        api[3] = v(l.getRssnr());
                    } else if (c instanceof CellSignalStrengthNr) {
                        CellSignalStrengthNr nr = (CellSignalStrengthNr) c;
                        api[9] = v(nr.getSsRsrp());
                        api[10] = v(nr.getSsSinr());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        // Which LTE cell we are registered on.
        try {
            List<CellInfo> cells = tm.getAllCellInfo();
            if (cells != null) {
                for (CellInfo ci : cells) {
                    if (ci instanceof CellInfoLte && ci.isRegistered()) {
                        CellIdentityLte id = ((CellInfoLte) ci).getCellIdentity();
                        api[4] = v(id.getPci());
                        api[5] = v(id.getCi());
                        api[6] = v(id.getTac());
                        api[7] = v(id.getEarfcn());
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            int[] bands = id.getBands();
                            if (bands != null && bands.length > 0) api[8] = String.valueOf(bands[0]);
                        }
                        if (api[8].isEmpty()) api[8] = bandFromEarfcn(id.getEarfcn());
                        break;
                    }
                }
            }
        } catch (SecurityException ignored) {
        } catch (Exception ignored) {
        }

        // Service Mode screen.
        ProbeService.Snapshot snap = null;
        ProbeService probe = ProbeService.instance;
        if (probe != null) {
            try {
                snap = probe.read();
            } catch (Exception ignored) {
            }
        }
        String smText = snap == null ? "" : snap.text;
        Map<String, String> sm = ServiceModeParser.parse(snap != null && snap.isServiceMode ? smText : "");

        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(loc.getTime()));
        StringBuilder r = new StringBuilder();
        r.append(time).append(',');
        r.append(String.format(Locale.US, "%.6f,%.6f,", loc.getLatitude(), loc.getLongitude()));
        r.append(loc.hasAccuracy() ? String.format(Locale.US, "%.0f", loc.getAccuracy()) : "").append(',');
        r.append(loc.hasSpeed() ? String.format(Locale.US, "%.0f", loc.getSpeed() * 3.6f) : "").append(',');
        r.append(loc.hasAltitude() ? String.format(Locale.US, "%.0f", loc.getAltitude()) : "").append(',');
        r.append(String.format(Locale.US, "%.1f", step)).append(',');
        // api_rsrp..api_sinr, api_pci, api_ci, api_tac, api_earfcn, api_band, nr rsrp, nr sinr
        r.append(api[0]).append(',').append(api[1]).append(',').append(api[2]).append(',').append(api[3]).append(',');
        r.append(api[4]).append(',').append(api[5]).append(',').append(api[6]).append(',').append(api[7]).append(',');
        r.append(api[8]).append(',').append(api[9]).append(',').append(api[10]).append(',');
        for (String k : ServiceModeParser.KEYS) r.append(sm.get(k)).append(',');
        boolean seen = snap != null && snap.isServiceMode;
        r.append(seen ? "yes" : "no").append(',');
        r.append(csv(snap == null ? "" : snap.packageName)).append(',');
        r.append(csv(seen ? smText : ""));
        writer.write(r.toString());
        writer.newLine();
        writer.flush();

        points++;
        String tx = sm.get("sm_tx_pwr");
        String rsrp = !api[0].isEmpty() ? api[0] : sm.get("sm_rsrp");
        StringBuilder sum = new StringBuilder();
        sum.append(points).append(" points");
        sum.append(" · RSRP ").append(rsrp.isEmpty() ? "?" : rsrp);
        if (seen) {
            sum.append(" · Tx ").append(tx.isEmpty() ? "not on screen" : ("idle".equals(tx) ? "idle (not sending)" : tx + " dBm"));
        } else {
            sum.append(" · Service Mode not visible");
        }
        lastSummary = sum.toString();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIF_ID, buildNotification(lastSummary));
    }

    private void stopLogging() {
        running = false;
        try {
            if (lm != null) lm.removeUpdates(this);
        } catch (Exception ignored) {
        }
        try {
            if (writer != null) writer.close();
        } catch (Exception ignored) {
        }
        writer = null;
        if (file != null && file.exists()) {
            String saved = copyToDownloads(file);
            lastSavedTo = saved != null ? saved : file.getAbsolutePath();
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        lastSummary = "Stopped. " + points + " points saved.";
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    /** Copy the finished CSV into Downloads/TowerCheck so it is easy to find. */
    private String copyToDownloads(File src) {
        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME, src.getName());
            cv.put(MediaStore.Downloads.MIME_TYPE, "text/csv");
            cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/TowerCheck");
            ContentResolver cr = getContentResolver();
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) return null;
            try (InputStream in = new FileInputStream(src); OutputStream out = cr.openOutputStream(uri)) {
                if (out == null) return null;
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            return "Downloads/TowerCheck/" + src.getName();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void onDestroy() {
        if (running) stopLogging();
        super.onDestroy();
    }

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Logging", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, LoggerService.class).setAction(ACTION_STOP);
        PendingIntent ps = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("TowerCheck logging")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi)
                .addAction(new Notification.Action.Builder((android.graphics.drawable.Icon) null, "Stop", ps).build())
                .build();
    }

    // Older Android versions need these LocationListener methods.
    @Override
    public void onProviderEnabled(String provider) {
    }

    @Override
    public void onProviderDisabled(String provider) {
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onStatusChanged(String provider, int status, android.os.Bundle extras) {
    }
}
