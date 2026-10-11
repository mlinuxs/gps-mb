package com.ridelog.app.gps;

/**
 * GPS 定位与运算结果快照。
 */
public class GpsData {
    /** 是否已有定位 */
    public boolean hasFix = false;
    /** 经度（WGS84） */
    public double lon = Double.NaN;
    /** 纬度（WGS84） */
    public double lat = Double.NaN;
    /** 滑动平均后的海拔，单位 m */
    public double altitude = Double.NaN;
    /** 瞬时速度（滤波后），单位 km/h */
    public double speed = 0;
    /** 累计总里程，单位 km */
    public double distance = 0;
    /** 坡度（滤波后），单位 %（仅作参考） */
    public double grade = 0;
    /** 定位精度半径，单位 m */
    public double accuracy = Double.NaN;
    /** 状态：no_fix（无信号/丢失） / weak（信号弱） / ok（定位成功） */
    public String status = "no_fix";
    /** 停车状态（长时间不移动，如休息/吃饭）：不计里程、不累计骑行时长 */
    public boolean stopped = false;
    /** 航向角（正北顺时针 0-360°），用于界面行进箭头 */
    public double bearing = Double.NaN;
    /** 数据时间戳 */
    public long time = 0;
}
