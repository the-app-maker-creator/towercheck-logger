package nz.towercheck.logger;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;
import java.util.Locale;

/**
 * Accessibility service that reads the text shown on Samsung's Service Mode
 * screen (*#0011#) on demand. It does nothing on its own; LoggerService asks it
 * for a snapshot each time a log point is taken.
 */
public class ProbeService extends AccessibilityService {

    static volatile ProbeService instance;

    /** Result of one screen read. */
    public static class Snapshot {
        public String packageName = "";
        public String text = "";
        public boolean isServiceMode;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Nothing to do: we read on demand.
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    private static boolean looksLikeServiceMode(CharSequence pkg) {
        if (pkg == null) return false;
        String p = pkg.toString().toLowerCase(Locale.ROOT);
        return p.contains("rilservicemode") || p.contains("servicemodeapp");
    }

    /** Read all visible text, preferring the Service Mode window if it is on screen. */
    public Snapshot read() {
        Snapshot s = new Snapshot();
        AccessibilityNodeInfo root = null;
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo w : windows) {
                    AccessibilityNodeInfo r = w.getRoot();
                    if (r != null && looksLikeServiceMode(r.getPackageName())) {
                        root = r;
                        break;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        if (root == null) {
            try {
                root = getRootInActiveWindow();
            } catch (Exception ignored) {
            }
        }
        if (root == null) return s;

        s.packageName = root.getPackageName() == null ? "" : root.getPackageName().toString();
        s.isServiceMode = looksLikeServiceMode(root.getPackageName());
        StringBuilder sb = new StringBuilder();
        collect(root, sb, 0);
        s.text = sb.toString();
        return s;
    }

    private void collect(AccessibilityNodeInfo node, StringBuilder sb, int depth) {
        if (node == null || depth > 40) return;
        CharSequence t = node.getText();
        if (t != null && t.length() > 0) {
            sb.append(t).append('\n');
        }
        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            AccessibilityNodeInfo c = node.getChild(i);
            if (c != null) collect(c, sb, depth + 1);
        }
    }
}
