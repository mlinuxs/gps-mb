package com.ridelog.app.ble;

/**
 * BMS 电池保护板解析结果（锂安 Modbus 仪表盘寄存器解码）。
 * 数值为 NaN 表示该字段当前无效（尚未收到数据）。
 */
public class BmsData {
    /** 电池包总电压，单位 V */
    public double voltage = Double.NaN;
    /** 电流，单位 A（符号：充电为正/放电为负，由保护板固件定义，原样透传） */
    public double current = Double.NaN;
    /** 剩余电量，单位 %（0-100） */
    public double soc = Double.NaN;
    /** 电池温度（温度1），单位 ℃ */
    public double temp = Double.NaN;
    /** 单体电压最大压差，单位 V（有电芯数据时有效） */
    public double cellDeltaV = Double.NaN;
    /** 电芯串数（0=尚未读到） */
    public int cellCount = 0;
    /** 接收时间戳（System.currentTimeMillis） */
    public long time = 0;
}
