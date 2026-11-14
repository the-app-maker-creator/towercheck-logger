package nz.towercheck.logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pulls the useful numbers out of Samsung Service Mode text. */
public final class ServiceModeParser {

    private ServiceModeParser() {
    }

    /** Column names, in CSV order. */
    public static final String[] KEYS = {
            "sm_tx_pwr", "sm_rsrp", "sm_rsrq", "sm_rssi", "sm_sinr",
            "sm_band", "sm_bw", "sm_dl_earfcn", "sm_ul_earfcn", "sm_pci", "sm_cell_id", "sm_tac",
            "sm_rrc", "sm_endc", "sm_nr_rsrp", "sm_nr_rsrq",
            "sm_ul_rb", "sm_ul_mcs", "sm_ant_rsrp_diff"
    };

    private static final int M = Pattern.MULTILINE | Pattern.CASE_INSENSITIVE;

    private static final Pattern TX = Pattern.compile("Tx\\s*Pwr\\s*:\\s*(-?\\d+)", M);
    private static final Pattern RSRP_LINE = Pattern.compile("^\\s*RSRP\\s*:\\s*(-?\\d+)(.*)$", M);
    private static final Pattern RSRQ = Pattern.compile("RSRQ\\s*:\\s*(-?\\d+)", M);
    private static final Pattern RSSI = Pattern.compile("RSSI\\s*:\\s*(-?\\d+)", M);
    private static final Pattern SINR = Pattern.compile("(?<![-\\w])SINR\\s*:\\s*(-?\\d+)", M);
    private static final Pattern BAND = Pattern.compile("Band\\s*:\\s*(\\d+)", M);
    private static final Pattern BW = Pattern.compile("BW\\s*:\\s*(\\d+)\\s*MHz", M);
    private static final Pattern FREQ = Pattern.compile("DL\\s*&\\s*UL\\s*Frequency\\s*:\\s*(\\d+)\\s*/\\s*(\\d+)", M);
    private static final Pattern PCI = Pattern.compile("(?<![A-Z])PCI\\s*:\\s*(\\d+)", M);
    private static final Pattern CELL = Pattern.compile("ServingCellID\\s*:\\s*(0x[0-9A-F]+|\\d+)", M);
    private static final Pattern TAC = Pattern.compile("TAC\\s*:\\s*(\\d+)", M);
    private static final Pattern RRC = Pattern.compile("RRC\\s*:\\s*([A-Z_]+)", M);
    private static final Pattern ENDC = Pattern.compile("ENDC\\s*Status\\s*:\\s*(\\w+)", M);
    private static final Pattern NR_RSRP = Pattern.compile("^\\s*NR\\s+RSRP\\s*:\\s*(-?\\d+)", M);
    private static final Pattern NR_RSRQ = Pattern.compile("^\\s*NR\\s+RSRP\\s*:[^,\\n]*,\\s*RSRQ\\s*:\\s*(-?\\d+)", M);
    private static final Pattern UL_RB = Pattern.compile("^\\s*RB\\s*\\(DL/UL\\)\\s*:\\s*(\\d+)\\s*/\\s*(\\d+)", M);
    private static final Pattern UL_MCS = Pattern.compile("UL\\s*MCS1\\s*:\\s*(\\d+)", M);
    private static final Pattern ANT = Pattern.compile("Ant\\s*RSRP\\s*Diff\\s*:\\s*(-?\\d+)", M);

    private static String first(Pattern p, String text, int group) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group(group) : "";
    }

    public static Map<String, String> parse(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String k : KEYS) out.put(k, "");
        if (text == null || text.isEmpty()) return out;

        out.put("sm_tx_pwr", first(TX, text, 1));

        Matcher rl = RSRP_LINE.matcher(text);
        if (rl.find()) {
            out.put("sm_rsrp", rl.group(1));
            String rest = rl.group(2);
            out.put("sm_rsrq", first(RSRQ, rest, 1));
            out.put("sm_rssi", first(RSSI, rest, 1));
        }
        out.put("sm_sinr", first(SINR, text, 1));
        out.put("sm_band", first(BAND, text, 1));
        out.put("sm_bw", first(BW, text, 1));
        Matcher f = FREQ.matcher(text);
        if (f.find()) {
            out.put("sm_dl_earfcn", f.group(1));
            out.put("sm_ul_earfcn", f.group(2));
        }
        out.put("sm_pci", first(PCI, text, 1));
        out.put("sm_cell_id", first(CELL, text, 1));
        out.put("sm_tac", first(TAC, text, 1));
        out.put("sm_rrc", first(RRC, text, 1));
        out.put("sm_endc", first(ENDC, text, 1));
        out.put("sm_nr_rsrp", first(NR_RSRP, text, 1));
        out.put("sm_nr_rsrq", first(NR_RSRQ, text, 1));
        out.put("sm_ul_rb", first(UL_RB, text, 2));
        out.put("sm_ul_mcs", first(UL_MCS, text, 1));
        out.put("sm_ant_rsrp_diff", first(ANT, text, 1));
        return out;
    }
}
