package com.ridelog.app.gps;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * GPS 定位与运算模块。
 *
 * - 订阅 GPS（NETWORK 仅辅助首定位），1Hz 采样
 * - Haversine 球面距离公式累加总里程
 * - 坡度 = 相邻采样点海拔差 / 水平距离 × 100，限幅 ±40%
 * - 海拔滑动平均、速度/坡度指数滑动平均（EMA），抑制 GPS 噪声
 * - 定位状态：no_fix / weak / ok，超时无新点判定信号丢失
 *
 * 注意：手机 GPS 海拔存在天然误差，坡度仅供参考。
 */
@SuppressLint("MissingPermission")
public class GpsManager {

    public interface Listener {
        void onGpsData(GpsData d);
        void onGpsStatus(String status, String message);
    }

    private static final String TAG = "GpsManager";
    private static final long MIN_TIME_MS = 1000;
    private static final float MIN_DISTANCE_M = 0f;
    private static final int ALT_WINDOW = 5;            // 海拔滑动窗口大小
    private static final double GRADE_LIMIT = 40.0;     // 坡度物理限幅 %
    private static final float MAX_ACCURACY_M = 50f;    // 精度差于此值不参与里程/海拔
    private static final float MIN_SEG_M = 1.0f;        // 最小有效位移
    private static final float GRADE_MIN_SEG_M = 5.0f;  // 计算坡度的最小水平距离
    private static final long STALE_MS = 5000;          // 超过该时长无新点视为丢星
    private static final double EARTH_R = 6371000.0;    // 地球半径 m

    // ---- 停车过滤（休息/吃饭等长时间不动的状态） ----
    private static final double STOP_SPEED_KMH = 2.0;   // 低于该速度视为静止候选
    private static final long STOP_CONFIRM_MS = 30000;  // 持续静止 30 秒判定进入停车
    private static final float STOP_RADIUS_M = 15f;     // 停车锚点半径内的漂移不算移动
    private static final float RESUME_DIST_M = 20f;     // 离开锚点超过该距离判定重新出发
    private static final double RESUME_SPEED_KMH = 4.0; // 或连续达到该速度判定重新出发
    private static final int RESUME_STREAK = 3;         // 连续超速点数
    private static final float RESUME_MAX_ACC_M = 25f;  // 精度差时不凭位移恢复，防漂移误判

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LocationManager lm;

    private boolean started = false;
    private final GpsData data = new GpsData();
    private Location lastLoc = null;

    private final double[] altWindow = new double[ALT_WINDOW];
    private int altCount = 0;
    private double smoothedAlt = Double.NaN;
    private double prevSmoothedAlt = Double.NaN;
    private double smoothedSpeed = 0;
    private double smoothedGrade = 0;
    private long lastFixTime = 0;

    // 停车状态机
    private boolean stopped = false;
    private long stopCandidateMs = 0;        // 持续低速的起始时刻，0=非候选
    private boolean hasAnchor = false;       // 是否有停车锚点
    private double anchorLat, anchorLon;     // 停车位置锚点
    private int resumeStreak = 0;            // 连续高速点计数
    private double lastBearing = Double.NaN; // 最近有效航向角（缺失时沿用）

    public GpsManager(Context context, Listener l) {
        ctx = context.getApplicationContext();
        listener = l;
        lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
    }

    public void start() {
        if (started) return;
        if (lm == null) {
            listener.onGpsStatus("no_fix", "本机不支持定位");
            return;
        }
        started = true;
        stopped = false;
        stopCandidateMs = 0;
        hasAnchor = false;
        resumeStreak = 0;
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                        MIN_TIME_MS, MIN_DISTANCE_M, locListener, Looper.getMainLooper());
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,
                        MIN_TIME_MS, MIN_DISTANCE_M, locListener, Looper.getMainLooper());
            }
            Location last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last == null) last = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (last != null) locListener.onLocationChanged(last);
        } catch (Exception e) {
            Log.e(TAG, "gps start fail", e);
        }
        main.removeCallbacks(stalenessTick);
        main.post(stalenessTick);
        if (!data.hasFix) listener.onGpsStatus("no_fix", "正在搜索卫星，请在露天处等待…");
    }

    public void stop() {
        started = false;
        main.removeCallbacks(stalenessTick);
        if (lm != null) {
            try {
                lm.removeUpdates(locListener);
            } catch (Exception ignored) {
            }
        }
    }

    public GpsData snapshot() {
        GpsData c = new GpsData();
        c.hasFix = data.hasFix;
        c.lon = data.lon;
        c.lat = data.lat;
        c.altitude = data.altitude;
        c.speed = data.speed;
        c.distance = data.distance;
        c.grade = data.grade;
        c.accuracy = data.accuracy;
        c.status = data.status;
        c.stopped = data.stopped;
        c.bearing = data.bearing;
        c.time = data.time;
        return c;
    }

    // ---------------------------------------------------------------- 定位回调

    private final LocationListener locListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location loc) {
            handle(loc);
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
            if (LocationManager.GPS_PROVIDER.equals(provider)) {
                listener.onGpsStatus("no_fix", "GPS 已关闭，请打开系统位置开关");
            }
        }
    };

    private void handle(Location loc) {
        if (loc == null) return;
        boolean isGps = LocationManager.GPS_PROVIDER.equals(loc.getProvider());
        // 已有 GPS 稳定定位后，忽略网络粗定位，避免污染里程/坡度
        if (!isGps && data.hasFix && "ok".equals(data.status)) return;

        boolean accurate = loc.hasAccuracy() && loc.getAccuracy() <= MAX_ACCURACY_M;
        double segM = 0;
        if (lastLoc != null) {
            segM = haversine(lastLoc.getLatitude(), lastLoc.getLongitude(),
                    loc.getLatitude(), loc.getLongitude());
        }

        // ---- 瞬时速度：优先 GPS 速度，无则用位移/时间，EMA 平滑 ----
        double instantSpeed = 0;
        if (isGps && loc.hasSpeed() && loc.getSpeed() > 0.1f) {
            instantSpeed = loc.getSpeed() * 3.6;
        } else if (lastLoc != null && accurate) {
            long dtMs = loc.getTime() - lastLoc.getTime();
            if (dtMs > 200) instantSpeed = segM / (dtMs / 1000.0) * 3.6;
        }
        smoothedSpeed = smoothedSpeed * 0.6 + instantSpeed * 0.4;
        if (smoothedSpeed < 0.1) smoothedSpeed = 0;

        // ---- 停车状态机：过滤休息/吃饭等长时间不动的状态 ----
        boolean movingFix = updateStopState(loc, accurate);

        // ---- 里程累加（精度合格 + 位移有效 + 速度合理；停车中不计，防漂移虚增） ----
        if (movingFix && lastLoc != null && accurate && segM >= MIN_SEG_M) {
            long dtMs = loc.getTime() - lastLoc.getTime();
            double vSeg = dtMs > 0 ? segM / (dtMs / 1000.0) * 3.6 : 0;
            if (vSeg < 120) {
                data.distance += segM / 1000.0;
            }
        }

        // ---- 海拔滑动平均 + 坡度（停车中冻结，防漂移污染） ----
        if (movingFix && loc.hasAltitude() && accurate) {
            pushAlt(loc.getAltitude());
            double newSmoothed = avgAlt();
            if (!Double.isNaN(smoothedAlt)) prevSmoothedAlt = smoothedAlt;
            smoothedAlt = newSmoothed;
            if (!Double.isNaN(prevSmoothedAlt) && segM >= GRADE_MIN_SEG_M) {
                double grade = (smoothedAlt - prevSmoothedAlt) / segM * 100.0;
                if (grade > GRADE_LIMIT) grade = GRADE_LIMIT;
                if (grade < -GRADE_LIMIT) grade = -GRADE_LIMIT;
                smoothedGrade = smoothedGrade * 0.7 + grade * 0.3;
            }
        }

        data.hasFix = true;
        data.lon = loc.getLongitude();
        data.lat = loc.getLatitude();
        data.altitude = smoothedAlt;
        data.speed = smoothedSpeed;
        data.grade = smoothedGrade;
        data.accuracy = loc.hasAccuracy() ? loc.getAccuracy() : Double.NaN;
        data.stopped = stopped;
        if (loc.hasBearing() && smoothedSpeed > 1) lastBearing = loc.getBearing();
        data.bearing = lastBearing;
        data.time = System.currentTimeMillis();
        lastFixTime = data.time;
        lastLoc = loc;

        evaluateStatus();
        listener.onGpsData(snapshot());
    }

    /**
     * 停车状态机更新（过滤长时间不移动的状态，如休息、吃饭）。
     *
     * 进入：滤波速度持续低于 2km/h 达 30 秒 → 记录锚点，进入停车；
     * 维持：停车中速度归零、不计里程/海拔/坡度，锚点 15m 内的漂移忽略；
     * 恢复：离开锚点 20m 以上（精度合格）或连续 3 点速度≥4km/h → 重新出发。
     *
     * @param accurate  本点精度是否合格
     * @return true=本点按移动处理（计里程/海拔/坡度）
     */
    private boolean updateStopState(Location loc, boolean accurate) {
        long now = System.currentTimeMillis();

        if (!stopped) {
            if (smoothedSpeed < STOP_SPEED_KMH) {
                if (stopCandidateMs == 0) {
                    stopCandidateMs = now;
                } else if (now - stopCandidateMs >= STOP_CONFIRM_MS) {
                    stopped = true;
                    hasAnchor = true;
                    anchorLat = loc.getLatitude();
                    anchorLon = loc.getLongitude();
                    resumeStreak = 0;
                    listener.onGpsStatus(data.status, "检测到停车，已暂停计时与里程");
                }
            } else {
                stopCandidateMs = 0;
            }
            return true;
        }

        // ---- 停车中：判定是否重新出发 ----
        double fromAnchor = hasAnchor
                ? haversine(anchorLat, anchorLon, loc.getLatitude(), loc.getLongitude())
                : RESUME_DIST_M;
        boolean distResume = accurate && loc.getAccuracy() <= RESUME_MAX_ACC_M
                && fromAnchor >= RESUME_DIST_M;
        if (smoothedSpeed >= RESUME_SPEED_KMH) {
            resumeStreak++;
        } else {
            resumeStreak = 0;
        }
        if (distResume || resumeStreak >= RESUME_STREAK) {
            stopped = false;
            hasAnchor = false;
            stopCandidateMs = 0;
            resumeStreak = 0;
            listener.onGpsStatus(data.status, "重新出发，继续记录");
            return true;
        }
        // 维持停车：速度归零，本点不计里程
        smoothedSpeed = 0;
        return false;
    }

    private final Runnable stalenessTick = new Runnable() {
        @Override
        public void run() {
            evaluateStatus();
            main.postDelayed(this, 2000);
        }
    };

    private void evaluateStatus() {
        String old = data.status;
        String s;
        String msg;
        if (!data.hasFix) {
            s = "no_fix";
            msg = "正在搜索卫星…";
        } else if (System.currentTimeMillis() - lastFixTime > STALE_MS) {
            s = "no_fix";
            msg = "GPS 信号丢失…";
        } else if (!Double.isNaN(data.accuracy) && data.accuracy > 30) {
            s = "weak";
            msg = "GPS 信号弱";
        } else {
            s = "ok";
            msg = "定位成功";
        }
        data.status = s;
        if (!s.equals(old)) listener.onGpsStatus(s, msg);
    }

    // ---------------------------------------------------------------- 算法工具

    /** Haversine 球面距离公式，返回两点间水平距离（米）。 */
    private static double haversine(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dp = Math.toRadians(lat2 - lat1);
        double dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * EARTH_R * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    private void pushAlt(double a) {
        altWindow[altCount % ALT_WINDOW] = a;
        altCount++;
    }

    private double avgAlt() {
        int n = Math.min(altCount, ALT_WINDOW);
        if (n == 0) return Double.NaN;
        double s = 0;
        for (int i = 0; i < n; i++) s += altWindow[i];
        return s / n;
    }
}
