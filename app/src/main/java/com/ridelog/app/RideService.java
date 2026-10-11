package com.ridelog.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import com.ridelog.app.ble.BleManager;
import com.ridelog.app.ble.BmsData;
import com.ridelog.app.gps.GpsData;
import com.ridelog.app.gps.GpsManager;
import com.ridelog.app.storage.CsvLogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Locale;

/**
 * 骑行采集前台服务（对抗 vivo 后台查杀的核心）。
 *
 * - startRecord：启动前台通知（标题“骑行记录中”）+ PARTIAL_WAKE_LOCK + GPS + CSV，1Hz 汇总推送
 * - stopRecord：停止全部采集、关闭 CSV、移除通知、释放锁、断开蓝牙
 * - BLE 扫描/连接可独立于记录状态操作
 * - 所有数据/状态经 UiCallback 推给 WebView；异常全部捕获
 */
public class RideService extends Service implements BleManager.Listener, GpsManager.Listener {

    private static final String TAG = "RideService";
    private static final int NOTIF_ID = 1001;
    private static final long TICK_MS = 1000;
    private static final long WAKE_TIMEOUT_MS = 12 * 60 * 60 * 1000L; // 12 小时兜底

    /** 服务 -> H5 数据出口（由 MainActivity 注册） */
    public interface UiCallback {
        void onDataUpdate(String json);
        void onStatusUpdate(String json);
        void onBleDevices(String json);
    }

    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        public RideService getService() {
            return RideService.this;
        }
    }

    private BleManager ble;
    private GpsManager gps;
    private CsvLogger csv;
    private Handler main;
    private PowerManager.WakeLock wakeLock;

    private boolean recording = false;
    private boolean paused = false;       // 手动暂停记录（GPS 保持，不计时不写CSV）
    private long activeMs = 0;            // 骑行有效时长（剔除停车/暂停时间）
    private double distOffset = 0;        // 累计剔除的内部里程（暂停期间漂移等）
    private double shownAtPause = 0;      // 暂停时刻的显示里程
    private volatile BmsData lastBms = null;
    private String bleState = BleManager.STATE_DISCONNECTED;
    private String gpsStatus = "no_fix";
    private UiCallback ui;

    @Override
    public void onCreate() {
        super.onCreate();
        main = new Handler(Looper.getMainLooper());
        ble = new BleManager(this, this);
        gps = new GpsManager(this, this);
        csv = new CsvLogger();
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RideLog:Collect");
        }
        Log.i(TAG, "service created");
        // APP 启动即自动开始 GPS 定位与轨迹记录（无需手动点开始）
        try {
            startRecord();
        } catch (Exception e) {
            Log.e(TAG, "auto start record fail", e);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY; // 被系统回收后尽可能重启
    }

    // ------------------------------------------------------------ 记录控制

    public void setUiCallback(UiCallback cb) {
        ui = cb;
    }

    /** 开始记录：前台服务 + 唤醒锁 + GPS + 新建 CSV + 1Hz 推送。 */
    public void startRecord() {
        if (recording) return;
        recording = true;
        paused = false;
        activeMs = 0;
        distOffset = 0;
        shownAtPause = 0;
        startForeground(NOTIF_ID, buildNotification("骑行记录中，等待GPS…"));
        acquireWake();
        File f = csv.start();
        gps.start();
        main.removeCallbacks(tick);
        main.post(tick);
        pushStatus(f != null
                ? "骑行记录已开始，日志：" + f.getName()
                : "骑行记录已开始（CSV 创建失败，请检查存储空间）");
    }

    /** 暂停记录：GPS 保持运行，暂停计时/里程/CSV 写入。 */
    public void pauseRecord() {
        if (!recording || paused) return;
        paused = true;
        shownAtPause = Math.max(0, gps.snapshot().distance - distOffset);
        pushStatus("已暂停记录（GPS 保持定位）");
    }

    /** 继续记录：恢复计时/里程/CSV 写入，暂停期间的漂移不计入。 */
    public void resumeRecord() {
        if (!recording || !paused) return;
        distOffset = Math.max(0, gps.snapshot().distance - shownAtPause);
        paused = false;
        pushStatus("继续记录");
    }

    public boolean isPaused() {
        return paused;
    }

    /** 停止记录：停采集、关文件、撤通知、放锁、断蓝牙。 */
    public void stopRecord() {
        if (!recording) return;
        recording = false;
        main.removeCallbacks(tick);
        try {
            gps.stop();
        } catch (Exception ignored) {
        }
        File f = csv.stop();
        releaseWake();
        try {
            ble.disconnect();
        } catch (Exception ignored) {
        }
        stopForeground(true);
        pushStatus(f != null
                ? "记录已停止，CSV 已保存到 Download 目录：" + f.getName()
                : "记录已停止");
        if (ui == null) {
            stopSelf(); // 界面已不在且非前台，自动销毁
        }
    }

    public boolean isRecording() {
        return recording;
    }

    // ------------------------------------------------------------ BLE 代理

    public void startBleScan() {
        ble.startScan();
    }

    public void stopBleScan() {
        ble.stopScan();
    }

    public void connectBle(String mac) {
        ble.connect(mac);
    }

    public void disconnectBle() {
        ble.disconnect();
    }

    /** 按设备名前缀自动搜索连接（已连接时跳过）。 */
    public void autoConnectBle(String namePrefix) {
        if (!BleManager.STATE_CONNECTED.equals(ble.getState())) {
            ble.autoConnect(namePrefix);
        }
    }

    /** 当前 GPS 快照（供导航取起点坐标）。 */
    public GpsData getCurrentGps() {
        return gps.snapshot();
    }

    // ------------------------------------------------------------ 1Hz 主循环

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            try {
                GpsData g = gps.snapshot();
                // 骑行时长只累计有效移动状态；停车/暂停/未定位不计时
                if (g.hasFix && !g.stopped && !paused) activeMs += TICK_MS;
                if (!paused) csv.append(lastBms, g);
                pushData(g);
                updateNotification(g);
            } catch (Exception e) {
                Log.e(TAG, "tick fail", e);
            }
            main.postDelayed(this, TICK_MS);
        }
    };

    private void pushData(GpsData g) {
        if (ui == null) return;
        try {
            JSONObject o = new JSONObject();
            long elapsed = activeMs / 1000; // 已骑时长（不含停车/暂停）
            double shownDist = paused
                    ? shownAtPause
                    : Math.max(0, g.distance - distOffset); // 剔除暂停期间漂移
            o.put("recording", recording);
            o.put("paused", paused);
            o.put("stopped", g.stopped);
            o.put("elapsed", elapsed);
            o.put("bleState", bleState);
            o.put("gpsStatus", g.status);
            if (lastBms != null) {
                putNum(o, "voltage", lastBms.voltage, 2);
                putNum(o, "current", lastBms.current, 2);
                putNum(o, "soc", lastBms.soc, 0);
                putNum(o, "temp", lastBms.temp, 1);
                putNum(o, "cellDiff", lastBms.cellDeltaV, 3);
                o.put("bmsTime", lastBms.time);
            } else {
                o.put("voltage", JSONObject.NULL);
                o.put("current", JSONObject.NULL);
                o.put("soc", JSONObject.NULL);
                o.put("temp", JSONObject.NULL);
                o.put("cellDiff", JSONObject.NULL);
            }
            o.put("lat", g.hasFix ? round(g.lat, 6) : JSONObject.NULL);
            o.put("lon", g.hasFix ? round(g.lon, 6) : JSONObject.NULL);
            putNum(o, "altitude", g.hasFix ? g.altitude : Double.NaN, 1);
            o.put("speed", round(g.speed, 1));
            o.put("distance", round(shownDist, 3));
            o.put("grade", round(g.grade, 1));
            putNum(o, "bearing", g.bearing, 0);
            ui.onDataUpdate(o.toString());
        } catch (Exception e) {
            Log.e(TAG, "pushData fail", e);
        }
    }

    private void pushStatus(String message) {
        if (ui == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("bleState", bleState);
            o.put("gpsStatus", gpsStatus);
            o.put("recording", recording);
            o.put("message", message == null ? "" : message);
            ui.onStatusUpdate(o.toString());
        } catch (Exception ignored) {
        }
    }

    private static void putNum(JSONObject o, String key, double v, int scale) throws Exception {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            o.put(key, JSONObject.NULL);
        } else {
            o.put(key, round(v, scale));
        }
    }

    private static double round(double v, int scale) {
        double f = 1;
        for (int i = 0; i < scale; i++) f *= 10;
        return Math.round(v * f) / f;
    }

    // ------------------------------------------------------------ 通知栏

    private Notification buildNotification(String text) {
        Intent it = new Intent(this, MainActivity.class);
        it.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("骑行记录中")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    private void updateNotification(GpsData g) {
        try {
            double shownDist = paused
                    ? shownAtPause
                    : Math.max(0, g.distance - distOffset);
            String text;
            if (paused) {
                text = String.format(Locale.US, "已暂停记录　里程 %.2f km", shownDist);
            } else if (!g.hasFix) {
                text = "等待 GPS 定位…";
            } else if (g.stopped) {
                text = String.format(Locale.US, "停车休息中（暂停计时）　里程 %.2f km", shownDist);
            } else {
                text = String.format(Locale.US, "速度 %.1f km/h　里程 %.2f km", g.speed, shownDist);
            }
            Notification n = buildNotification(text);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, n);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------ 唤醒锁

    private void acquireWake() {
        try {
            if (wakeLock != null && !wakeLock.isHeld()) {
                wakeLock.acquire(WAKE_TIMEOUT_MS);
            }
        } catch (Exception e) {
            Log.w(TAG, "wakeLock acquire fail", e);
        }
    }

    private void releaseWake() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------ BLE 回调

    @Override
    public void onBleState(String state, String message) {
        bleState = state;
        pushStatus(message);
    }

    @Override
    public void onDeviceFound(String name, String mac, int rssi) {
        if (ui == null) return;
        try {
            JSONObject o = new JSONObject();
            JSONArray arr = new JSONArray();
            JSONObject d = new JSONObject();
            d.put("name", name);
            d.put("mac", mac);
            d.put("rssi", rssi);
            arr.put(d);
            o.put("devices", arr);
            ui.onBleDevices(o.toString());
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onBmsData(BmsData d) {
        lastBms = d;
    }

    // ------------------------------------------------------------ GPS 回调

    @Override
    public void onGpsData(GpsData d) {
        // 数据在 1Hz tick 中统一取用，这里无需处理
    }

    @Override
    public void onGpsStatus(String status, String message) {
        gpsStatus = status;
        pushStatus(message);
    }

    // ------------------------------------------------------------ 销毁

    @Override
    public void onDestroy() {
        main.removeCallbacks(tick);
        try {
            gps.stop();
        } catch (Exception ignored) {
        }
        try {
            ble.disconnect();
        } catch (Exception ignored) {
        }
        try {
            csv.stop();
        } catch (Exception ignored) {
        }
        releaseWake();
        Log.i(TAG, "service destroyed");
        super.onDestroy();
    }
}
