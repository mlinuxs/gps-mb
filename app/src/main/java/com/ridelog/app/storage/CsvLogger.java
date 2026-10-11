package com.ridelog.app.storage;

import android.os.Environment;
import android.util.Log;

import com.ridelog.app.ble.BmsData;
import com.ridelog.app.gps.GpsData;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * CSV 日志记录器。
 *
 * - 保存路径：公共下载目录 /RideLog_yyyyMMdd_HHmmss.csv
 * - UTF-8 编码并写入 BOM，Excel 直接打开中文不乱码
 * - 每条记录后立即 flush，异常断电/崩溃最多丢失 1 秒数据
 * - 所有 IO 异常捕获，存储报错不会导致程序崩溃
 */
public class CsvLogger {

    private static final String TAG = "CsvLogger";
    private static final char BOM = (char) 0xFEFF;
    /** 停车（休息）时段降频写入间隔：30 秒 1 条，避免大量重复静止点 */
    private static final long STOP_ROW_INTERVAL_MS = 30000;
    private static final String HEADER =
            "时间,电压(V),电流(A),电量(%),温度(℃),经度,纬度,海拔(m),瞬时速度(km/h),总里程(km),坡度(%),状态\n";

    private Writer writer;
    private File file;
    private long lastStopRowMs = 0;   // 停车时段上一条写入时间
    private boolean lastMoving = true;
    private final SimpleDateFormat tsFmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
    private final SimpleDateFormat nameFmt = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);

    /** 开始记录：在 Download 目录新建 CSV 并写表头。成功返回文件，失败返回 null。 */
    public File start() {
        try {
            lastStopRowMs = 0;
            lastMoving = true;
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (dir != null && !dir.exists()) dir.mkdirs();
            file = new File(dir, "RideLog_" + nameFmt.format(new Date()) + ".csv");
            FileOutputStream fos = new FileOutputStream(file, true);
            writer = new OutputStreamWriter(fos, "UTF-8");
            writer.write(BOM); // UTF-8 BOM，保证 Excel 打开中文不乱码
            writer.write(HEADER);
            writer.flush();
            Log.i(TAG, "csv started: " + file.getAbsolutePath());
            return file;
        } catch (Exception e) {
            Log.e(TAG, "csv start fail", e);
            writer = null;
            file = null;
            return null;
        }
    }

    /** 追加一条 1Hz 记录。无数据的字段留空；停车时段降频为 30 秒 1 条。 */
    public synchronized void append(BmsData bms, GpsData gps) {
        if (writer == null) return;
        try {
            // ---- 降频：未定位/停车时段，进入立即写 1 条，之后每 30 秒 1 条，恢复移动立即恢复 1Hz ----
            boolean moving = gps != null && gps.hasFix && !gps.stopped;
            long now = System.currentTimeMillis();
            if (!moving) {
                if (lastMoving) {
                    lastStopRowMs = 0; // 刚进入停车
                }
                if (lastStopRowMs != 0 && now - lastStopRowMs < STOP_ROW_INTERVAL_MS) {
                    lastMoving = false;
                    return;
                }
                lastStopRowMs = now;
            }
            lastMoving = moving;

            StringBuilder sb = new StringBuilder(160);
            sb.append(tsFmt.format(new Date())).append(',');

            if (bms != null && !Double.isNaN(bms.voltage)) {
                sb.append(String.format(Locale.US, "%.2f", bms.voltage));
            }
            sb.append(',');
            if (bms != null && !Double.isNaN(bms.current)) {
                sb.append(String.format(Locale.US, "%.2f", bms.current));
            }
            sb.append(',');
            if (bms != null && !Double.isNaN(bms.soc)) {
                sb.append((int) bms.soc);
            }
            sb.append(',');
            if (bms != null && !Double.isNaN(bms.temp)) {
                sb.append(String.format(Locale.US, "%.1f", bms.temp));
            }
            sb.append(',');

            // 经度,纬度,海拔,速度,里程,坡度 共 6 列
            if (gps != null && gps.hasFix) {
                sb.append(String.format(Locale.US, "%.6f", gps.lon)).append(',');
                sb.append(String.format(Locale.US, "%.6f", gps.lat)).append(',');
                if (!Double.isNaN(gps.altitude)) {
                    sb.append(String.format(Locale.US, "%.1f", gps.altitude));
                }
                sb.append(',');
                sb.append(String.format(Locale.US, "%.1f", gps.speed)).append(',');
                sb.append(String.format(Locale.US, "%.3f", gps.distance)).append(',');
                sb.append(String.format(Locale.US, "%.1f", gps.grade));
            } else {
                sb.append(",,,,,");
            }
            sb.append(',').append(moving ? "骑行" : "静止");
            sb.append('\n');

            writer.write(sb.toString());
            writer.flush();
        } catch (Exception e) {
            Log.e(TAG, "csv append fail", e);
        }
    }

    /** 停止记录并关闭文件句柄，返回已保存的文件。 */
    public synchronized File stop() {
        File f = file;
        if (writer != null) {
            try {
                writer.flush();
                writer.close();
            } catch (Exception ignored) {
            }
            writer = null;
        }
        file = null;
        if (f != null) Log.i(TAG, "csv saved: " + f.getAbsolutePath());
        return f;
    }

    public String getFilePath() {
        return file != null ? file.getAbsolutePath() : null;
    }
}
