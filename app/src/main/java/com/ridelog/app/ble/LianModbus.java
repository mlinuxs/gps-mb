package com.ridelog.app.ble;

/**
 * 锂安/LABMS BMS 保护板 Modbus-RTU over BLE 协议（移植自微信小程序 protocol.js）。
 *
 * 帧结构： [从机地址][功能码][数据...][CRC低][CRC高]
 *   * CRC-16/XMODEM：初值 0x0000，多项式 0x1021，高位在前、无反转，低字节在前追加
 *   * 功能码 0x03 读保持寄存器；单次最多 8 个寄存器；寄存器数据大端
 *   * 响应有两种格式：
 *       标准短帧 [addr][03][字节数][数据][CRC]（无起始地址回显）
 *       扩展帧   [addr][03][起始Hi][起始Lo][字节数][数据][CRC]
 *     部分 0x0804 区域响应功能码回显为 0x00，帧仍为扩展格式（本应用不读该区，解析时一并容忍）
 */
public final class LianModbus {

    /** 默认从机地址（22 = 0x16） */
    public static final int SLAVE = 22;
    public static final int FC_READ = 0x03;
    /** 单次读请求最大寄存器数 */
    public static final int MAX_REGS_PER_READ = 8;

    private LianModbus() {
    }

    // ------------------------------------------------------------ CRC

    /** CRC-16/XMODEM：poly=0x1021, init=0x0000, 无反转。 */
    public static int crc16(byte[] data, int off, int len) {
        int crc = 0;
        for (int i = off; i < off + len; i++) {
            crc ^= (data[i] & 0xFF) << 8;
            for (int b = 0; b < 8; b++) {
                if ((crc & 0x8000) != 0) {
                    crc = ((crc << 1) ^ 0x1021) & 0xFFFF;
                } else {
                    crc = (crc << 1) & 0xFFFF;
                }
            }
        }
        return crc & 0xFFFF;
    }

    private static boolean crcOk(byte[] buf, int off, int len) {
        if (len < 3) return false;
        int expect = (buf[off + len - 2] & 0xFF) | ((buf[off + len - 1] & 0xFF) << 8);
        return crc16(buf, off, len - 2) == expect;
    }

    // ------------------------------------------------------------ 帧构造

    /** 构造 0x03 读寄存器请求帧。 */
    public static byte[] read03(int start, int count) {
        byte[] p = new byte[6];
        p[0] = (byte) (SLAVE & 0xFF);
        p[1] = (byte) FC_READ;
        p[2] = (byte) ((start >> 8) & 0xFF);
        p[3] = (byte) (start & 0xFF);
        p[4] = (byte) ((count >> 8) & 0xFF);
        p[5] = (byte) (count & 0xFF);
        return appendCrc(p);
    }

    private static byte[] appendCrc(byte[] p) {
        int c = crc16(p, 0, p.length);
        byte[] out = new byte[p.length + 2];
        System.arraycopy(p, 0, out, 0, p.length);
        out[p.length] = (byte) (c & 0xFF);
        out[p.length + 1] = (byte) ((c >> 8) & 0xFF);
        return out;
    }

    /**
     * 流式收包判定：从 buf[off] 起判断是否已有完整响应帧。
     *
     * @return 完整帧长度；-1 表示数据不足需继续等待；-2 表示当前字节不是合法帧头。
     */
    public static int scanFrameLen(byte[] buf, int off, int avail) {
        if (avail < 2) return -1;
        int func = buf[off + 1] & 0xFF;
        if ((func & 0x80) != 0) {               // 异常响应
            if (avail < 5) return -1;
            return crcOk(buf, off, 5) ? 5 : -2;
        }
        if (func != FC_READ && func != 0x00) return -2;
        // 标准短帧 [addr][03][bc][data][crc]：len = 5 + bc
        if (avail >= 3) {
            int bc = buf[off + 2] & 0xFF;
            if (bc >= 0 && bc <= 252 && (bc & 1) == 0) {
                int len = 5 + bc;
                if (avail >= len && crcOk(buf, off, len)) return len;
            }
        }
        // 扩展帧 [addr][03][startHi][startLo][bc][data][crc]：len = 7 + bc
        if (avail >= 5) {
            int bc = buf[off + 4] & 0xFF;
            if (bc >= 0 && bc <= 250 && (bc & 1) == 0) {
                int len = 7 + bc;
                if (avail >= len && crcOk(buf, off, len)) return len;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------ 数据解码

    public static int u16(byte[] data, int byteOff) {
        return ((data[byteOff] & 0xFF) << 8) | (data[byteOff + 1] & 0xFF);
    }

    /** 寄存器字下标（从 0 起）读取大端 u16。 */
    public static int regU16(byte[] block, int regIndex) {
        return u16(block, regIndex * 2);
    }

    public static long regU32(byte[] block, int regIndex) {
        int i = regIndex * 2;
        return ((long) (block[i] & 0xFF) << 24) | ((long) (block[i + 1] & 0xFF) << 16)
                | ((long) (block[i + 2] & 0xFF) << 8) | (long) (block[i + 3] & 0xFF);
    }

    public static long regI32(byte[] block, int regIndex) {
        long v = regU32(block, regIndex);
        if (v >= 0x80000000L) v -= 0x100000000L;
        return v;
    }

    /** 0.1K -> 摄氏度 */
    public static double k2c(int raw) {
        return (raw - 2731) / 10.0;
    }
}
