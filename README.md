# TowerCheck Logger

A small Android app for drive-testing on Samsung phones. Every set number of metres it saves one CSV row with:

- GPS position, speed, accuracy
- Android's own LTE/5G readings (RSRP, RSRQ, RSSI, SINR, PCI, cell ID, TAC, EARFCN, band)
- What Samsung's **Service Mode** screen (`*#0011#`) shows — most importantly **Tx Pwr**, the phone's own transmit power in dBm

Tx Pwr isn't available to normal apps, so the app uses an accessibility service to read the numbers off the Service Mode screen while it is open.

## Install

1. On the phone, open **Releases → latest** on this repo and download `towercheck-logger.apk`.
2. Open it and allow "install unknown apps" for your browser when asked.

## One-time setup

1. Open the app and tap **1. Allow location and notifications** (choose *While using the app* / *Precise*).
2. Tap **2. Turn on the Service Mode reader** → *Installed apps* → **TowerCheck Service Mode reader** → turn on.
   - If Android says it's a **restricted setting**: go to *Settings → Apps → TowerCheck Logger → ⋮ (top right) → Allow restricted settings*, then try again.

## Each drive

1. Set the screen timeout to 10 min (or keep the phone plugged in).
2. In the app, set the distance (e.g. 10 m; 0 = every GPS fix, about once a second) and tap **Start logging**.
3. Dial `*#0011#` to open Service Mode. Scroll down so **Tx Pwr** is on screen. Leave it there.
4. Drive. The notification shows the point count and the latest Tx Pwr.
5. Tap **Stop** in the notification. The CSV is copied to **Downloads/TowerCheck/**.

Use **Test: read Service Mode now** to check it's working: tap it, switch to Service Mode within 5 seconds, then come back.

## CSV columns

`time, lat, lon, gps_accuracy_m, speed_kmh, altitude_m, step_m, api_*` (Android readings), `sm_*` (Service Mode readings, e.g. `sm_tx_pwr`, `sm_rsrp`, `sm_ul_rb`), `sm_seen`, `sm_package`, `sm_raw` (all text read from the screen).

Tx Pwr is in dBm (`sm_tx_mw` gives milliwatts): 23 dBm ≈ 200 mW (max), 20 ≈ 100 mW, 10 ≈ 10 mW, 0 ≈ 1 mW. "idle" means Service Mode showed `--`: the phone was not transmitting.
