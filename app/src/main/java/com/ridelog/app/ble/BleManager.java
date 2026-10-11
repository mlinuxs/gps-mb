package com.ridelog.app.ble;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 锂安/LABMS 低功耗 BLE 保护板连接 + Modbus-RTU 读取。
 *
 * 蓝牙层：扫描（API21+ BluetoothLeScanner 按设备名过滤，仅 BLE）-> 自动连接目标设备 ->
 * 发现 2760 服务 -> 订阅通知特征 -> 开始读取。
 *
 * 数据层：保护板为“请求-应答”Modbus（0x03 读保持寄存器），非主动上报帧。
 * 连接就绪后每约 1.4 秒发送一组读请求（仪表盘寄存器块），把通知返回的数据按帧切分、
 * 解析并解码成 BmsData 回调，供 RideService 推送 UI。
 *
 * 目标设备名在 TARGET_DEVICE_NAME；断线自动重连 3 次，之后回到扫描循环直到连上。
 * 所有状态/数据回调都切到主线程；异常全部捕获防崩溃。
 */
@SuppressLint("MissingPermission")
public class BleManager {

    public static final String STATE_OFF = "off";
    public static final String STATE_SCANNING = "scanning";
    public static final String STATE_CONNECTING = "connecting";
    public static final String STATE_CONNECTED = "connected";
    public static final String STATE_DISCONNECTED = "disconnected";

    public interface Listener {
        void onBleState(String state, String message);
        void onDeviceFound(String name, String mac, int rssi);
        void onBmsData(BmsData d);
    }

    private static final String TAG = "BleManager";
    private static final UUID UUID_CCC = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final int SCAN_TIMEOUT_MS = 30000;
    private static final int MAX_RETRY = 3;
    private static final long RETRY_DELAY_MS = 2000;
    private static final long RESCAN_DELAY_MS = 3000;
    /** 轮询周期（一次仪表盘读取结束后间隔） */
    private static final long POLL_GAP_MS = 1300;
    /** 仪表盘寄存器读块：电压/电流/温度 + SOC/电芯串数 */
    private static final int READ_BASE_START = 0;
    private static final int READ_BASE_COUNT = 8;
    private static final int READ_HEAD_START = 20;
    private static final int READ_HEAD_COUNT = 5;
    private static final int CELL_REG_START = 32;

    /** 锂安 BMS 服务 UUID（含 2760） */
    private static final String BMS_SERVICE_UUID = "00002760-08C2-11E1-9073-0E8AC72E1001";
    /** 默认自动连接的保护板设备名（LABMS 系列低功耗 BLE 保护板，启动即直接搜索该名称）。 */
    public static final String TARGET_DEVICE_NAME = "LABMS-46191G38155";
    /** 固定目标 MAC（用户提供的保护板地址）。部分工具会按小端打印，此处两种字节序均匹配。 */
    private static final String TARGET_MAC_HEX = "B141000B3336"; // B1:41:00:0B:33:36

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final BluetoothAdapter adapter;
    private BluetoothLeScanner leScanner;
    private final Map<String, BluetoothDevice> found = new LinkedHashMap<String, BluetoothDevice>();

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeChar;
    private BluetoothGattCharacteristic notifyChar;
    private String state = STATE_DISCONNECTED;
    private String targetMac;
    private boolean userDisconnect = true;
    private int retryCount = 0;
    private boolean scanning = false;
    /** 自动连接目标设备名；非 null 时扫描到即连，失败后循环回扫。 */
    private String autoTarget = null;

    // ---- Modbus 轮询状态 ----
    private boolean polling = false;
    private boolean subscribed = false;
    private byte[] rxBuf = new byte[0];
    private int lastCellCount = 0;
    private int cycleId = 0;          // 轮询代次，断线重连时使残留任务失效

    public BleManager(Context context, Listener l) {
        ctx = context.getApplicationContext();
        listener = l;
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bm != null ? bm.getAdapter() : BluetoothAdapter.getDefaultAdapter();
    }

    public String getState() {
        return state;
    }

    public boolean isBluetoothReady() {
        return adapter != null && adapter.isEnabled();
    }

    // ---------------------------------------------------------------- 扫描

    /** 开始 BLE 扫描：API21+ 用 BluetoothLeScanner（带目标名过滤），旧机型回退 startLeScan。 */
    public void startScan() {
        if (adapter == null) {
            postState(STATE_OFF, "本机不支持蓝牙");
            return;
        }
        if (!adapter.isEnabled()) {
            postState(STATE_OFF, "蓝牙未开启，请先打开蓝牙");
            try {
                Intent it = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(it);
            } catch (Exception e) {
                Log.w(TAG, "request enable bluetooth fail", e);
            }
            return;
        }
        cancelScan();
        found.clear();
        // 不做系统级过滤（部分设备广播格式特殊会过滤不到），改为全量 LE 扫描后在回调里
        // 按“名字含 LABMS / BMS”或“广播 2760 服务”识别并连接。
        List<ScanFilter> filters = new ArrayList<ScanFilter>();
        try {
            leScanner = adapter.getBluetoothLeScanner();
            if (leScanner != null) {
                ScanSettings st = new ScanSettings.Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                        .build();
                leScanner.startScan(filters, st, scanCb21);
                scanning = true;
            } else {
                scanning = adapter.startLeScan(leScanCb);
            }
        } catch (Exception e) {
            Log.e(TAG, "startLeScan fail", e);
            scanning = false;
        }
        if (scanning) {
            postState(STATE_SCANNING,
                    autoTarget != null
                            ? "正在搜索 LABMS 开头的低功耗(BLE)保护板…"
                            : "正在扫描低功耗 BLE 设备…");
            main.removeCallbacks(scanTimeout);
            main.postDelayed(scanTimeout, SCAN_TIMEOUT_MS);
        } else {
            postState(STATE_DISCONNECTED, "蓝牙扫描启动失败");
        }
    }

    /** 停止当前扫描（不播报状态）。 */
    private void cancelScan() {
        if (leScanner != null) {
            try {
                leScanner.stopScan(scanCb21);
            } catch (Exception e) {
                Log.w(TAG, "stopLeScan fail", e);
            }
            leScanner = null;
        }
        if (adapter != null && scanning) {
            try {
                adapter.stopLeScan(leScanCb);
            } catch (Exception e) {
                Log.w(TAG, "stopLeScan fail", e);
            }
        }
        scanning = false;
        main.removeCallbacks(scanTimeout);
    }

    public void stopScan() {
        cancelScan();
        if (STATE_SCANNING.equals(state)) {
            postState(STATE_DISCONNECTED, "扫描结束，请选择设备");
        }
    }

    private final Runnable scanTimeout = new Runnable() {
        @Override
        public void run() {
            boolean keepAuto = autoTarget != null && !userDisconnect;
            stopScan();
            if (keepAuto && !STATE_CONNECTED.equals(state) && !STATE_CONNECTING.equals(state)) {
                // 自动模式：本轮没搜到目标，稍后继续下一轮扫描
                main.postDelayed(rescanTask, RESCAN_DELAY_MS);
            }
        }
    };

    private final Runnable rescanTask = new Runnable() {
        @Override
        public void run() {
            if (autoTarget != null && !userDisconnect
                    && !STATE_CONNECTED.equals(state) && !STATE_CONNECTING.equals(state)) {
                startScan();
            }
        }
    };

    private final BluetoothAdapter.LeScanCallback leScanCb = new BluetoothAdapter.LeScanCallback() {
        @Override
        public void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord) {
            // 旧接口拿不到广播服务列表，仅按名称判定
            handleScanResult(device, rssi, false);
        }
    };

    private final ScanCallback scanCb21 = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (result != null) {
                boolean svc = false;
                try {
                    if (result.getScanRecord() != null
                            && result.getScanRecord().getServiceUuids() != null) {
                        for (ParcelUuid u : result.getScanRecord().getServiceUuids()) {
                            if (u != null && u.toString().toUpperCase().contains("2760")) {
                                svc = true;
                                break;
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
                handleScanResult(result.getDevice(), result.getRssi(), svc);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    scanning = false;
                    if (!STATE_CONNECTED.equals(state)) {
                        postState(STATE_DISCONNECTED, "BLE 扫描失败，请重试");
                    }
                }
            });
        }
    };

    /** 统一处理扫描结果：所有 BLE 设备都上报（便于诊断），自动连接仅针对候选设备。 */
    private void handleScanResult(final BluetoothDevice device, final int rssi, final boolean svc2760) {
        if (device == null || device.getAddress() == null) return;
        final String mac = device.getAddress();
        main.post(new Runnable() {
            @Override
            public void run() {
                if (found.containsKey(mac)) return;
                found.put(mac, device);
                String nm2 = device.getName();
                if (nm2 == null || nm2.length() == 0) nm2 = "(未命名设备)";
                listener.onDeviceFound(nm2, mac, rssi);
                // 自动模式：命中即发起连接（名称含 LABMS/BMS、广播 2760 服务，或目标 MAC）
                if (autoTarget != null && (isCandidate(nm2, svc2760) || macIsTarget(mac))
                        && !STATE_CONNECTING.equals(state) && !STATE_CONNECTED.equals(state)) {
                    connectInternal(mac);
                }
            }
        });
    }

    /** 候选判定：广播里带 2760 BMS 服务，或设备名含 LABMS / BMS（与小程序扫描口径一致）。 */
    private boolean isCandidate(String name, boolean svc2760) {
        if (svc2760) return true;
        if (name == null) return false;
        String u = name.toUpperCase();
        return u.contains("LABMS") || u.contains("BMS");
    }

    /** MAC 是否为目标保护板：正序/反序（不同工具打印字节序可能相反）任一匹配即视为目标。 */
    private boolean macIsTarget(String mac) {
        if (mac == null) return false;
        String a = mac.replace(":", "").trim().toUpperCase();
        if (a.length() != 12) return false;
        String t = TARGET_MAC_HEX.toUpperCase();
        if (a.equals(t)) return true;
        String rev = new StringBuilder(t).reverse().toString();
        return a.equals(rev);
    }

    // ---------------------------------------------------------------- 连接

    /** 手动按 MAC 地址连接保护板（取消自动模式），失败自动重连最多 3 次。 */
    public void connect(String mac) {
        autoTarget = null;
        connectInternal(mac);
    }

    /** 按设备名自动搜索并连接（APP 启动即连 LABMS 保护板）。 */
    public void autoConnect(String namePrefix) {
        autoTarget = namePrefix;
        userDisconnect = false;
        retryCount = 0;
        startScan();
    }

    /** 建立连接（自动/手动共用入口）。 */
    private void connectInternal(String mac) {
        if (adapter == null || mac == null || mac.trim().length() == 0) {
            postState(STATE_DISCONNECTED, "MAC 地址无效");
            return;
        }
        stopPolling();
        cancelScan();
        closeGatt();
        targetMac = mac.trim().toUpperCase();
        userDisconnect = false;
        retryCount = 0;
        doConnect();
    }

    /** 用户主动断开（取消自动模式，不再重连）。 */
    public void disconnect() {
        userDisconnect = true;
        autoTarget = null;
        main.removeCallbacks(retryTask);
        main.removeCallbacks(rescanTask);
        stopPolling();
        stopScan();
        closeGatt();
        postState(STATE_DISCONNECTED, "蓝牙已断开");
    }

    private void doConnect() {
        postState(STATE_CONNECTING,
                retryCount > 0 ? ("连接断开，重连中(" + retryCount + "/" + MAX_RETRY + ")…") : "正在连接保护板…");
        try {
            BluetoothDevice dev = adapter.getRemoteDevice(targetMac);
            BluetoothGatt g = dev.connectGatt(ctx, true, gattCb);
            if (g == null) {
                scheduleRetryOrFail();
                return;
            }
            gatt = g;
        } catch (Exception e) {
            Log.e(TAG, "connectGatt fail", e);
            scheduleRetryOrFail();
        }
    }

    private final Runnable retryTask = new Runnable() {
        @Override
        public void run() {
            if (!userDisconnect) doConnect();
        }
    };

    private void scheduleRetryOrFail() {
        stopPolling();
        closeGatt();
        if (userDisconnect) {
            postState(STATE_DISCONNECTED, "蓝牙已断开");
            return;
        }
        if (retryCount < MAX_RETRY) {
            retryCount++;
            postState(STATE_CONNECTING, "连接失败，重连中(" + retryCount + "/" + MAX_RETRY + ")…");
            main.removeCallbacks(retryTask);
            main.postDelayed(retryTask, RETRY_DELAY_MS);
        } else if (autoTarget != null) {
            // 自动模式：3 次重试耗尽后回到扫描，设备回到范围内自动再连
            retryCount = 0;
            postState(STATE_DISCONNECTED, "重连失败，继续搜索 " + autoTarget + "…");
            main.removeCallbacks(rescanTask);
            main.postDelayed(rescanTask, RESCAN_DELAY_MS);
        } else {
            retryCount = 0;
            postState(STATE_DISCONNECTED, "重连 3 次仍失败，请重新连接");
        }
    }

    private void closeGatt() {
        subscribed = false;
        writeChar = null;
        notifyChar = null;
        rxBuf = new byte[0];
        if (gatt != null) {
            try {
                gatt.disconnect();
            } catch (Exception ignored) {
            }
            try {
                gatt.close();
            } catch (Exception ignored) {
            }
            gatt = null;
        }
    }

    // ---------------------------------------------------------------- GATT

    private final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, final int status, final int newState) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    Log.i(TAG, "onConnectionStateChange status=" + status + " newState=" + newState);
                    if (newState == BluetoothProfile.STATE_CONNECTED
                            && status == BluetoothGatt.GATT_SUCCESS) {
                        retryCount = 0;
                        try {
                            // 优先提升 MTU（大帧响应一次收全，同小程序 setBLEMTU 185）
                            boolean ok = gatt.requestMtu(185);
                            if (!ok) {
                                gatt.discoverServices();
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "requestMtu fail", e);
                            try {
                                gatt.discoverServices();
                            } catch (Exception e2) {
                                scheduleRetryOrFail();
                            }
                        }
                    } else {
                        closeGatt();
                        if (userDisconnect) {
                            postState(STATE_DISCONNECTED, "蓝牙已断开");
                        } else {
                            scheduleRetryOrFail();
                        }
                    }
                }
            });
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, final int mtu, final int status) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    Log.i(TAG, "onMtuChanged mtu=" + mtu + " status=" + status);
                    try {
                        gatt.discoverServices();
                    } catch (Exception e) {
                        Log.e(TAG, "discoverServices fail", e);
                        scheduleRetryOrFail();
                    }
                }
            });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, final int status) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        Log.w(TAG, "onServicesDiscovered status=" + status);
                        scheduleRetryOrFail();
                        return;
                    }
                    if (!findBmsChars()) {
                        postState(STATE_DISCONNECTED, "未找到 BMS(2760) 服务特征");
                        scheduleRetryOrFail();
                        return;
                    }
                    postState(STATE_CONNECTED, "蓝牙已连接，正在初始化 BMS 通信…");
                    subscribeNotifyChar();
                }
            });
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    subscribed = true;
                    startPolling(); // 通知已启用，开始周期读取
                }
            });
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch) {
            // 通知数据（Modbus 响应帧），切回主线程统一处理
            final byte[] value = ch.getValue();
            if (value == null || value.length == 0) return;
            main.post(new Runnable() {
                @Override
                public void run() {
                    appendRx(value);
                }
            });
        }
    };

    /**
     * 从服务表中定位 BMS 写入/通知特征：优先 2760 服务下 0001(写)/0002(通知)，
     * 找不到时退回首个可写/可通知特征。
     */
    private boolean findBmsChars() {
        if (gatt == null) return false;
        BluetoothGattService svc2760 = null;
        for (BluetoothGattService s : gatt.getServices()) {
            if (s.getUuid() != null && s.getUuid().toString().toUpperCase().contains("2760")) {
                svc2760 = s;
                break;
            }
        }
        List<BluetoothGattService> pool = new ArrayList<BluetoothGattService>();
        if (svc2760 != null) pool.add(svc2760);
        else pool.addAll(gatt.getServices());

        // 第一遍：优先 UUID 后缀 0001/0002
        for (BluetoothGattService s : pool) {
            for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                int p = c.getProperties();
                String u = c.getUuid().toString().toUpperCase();
                boolean writable = (p & (BluetoothGattCharacteristic.PROPERTY_WRITE
                        | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0;
                boolean notifiable = (p & (BluetoothGattCharacteristic.PROPERTY_NOTIFY
                        | BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0;
                if (writeChar == null && writable && u.endsWith("0001")) writeChar = c;
                if (notifyChar == null && notifiable && u.endsWith("0002")) notifyChar = c;
            }
        }
        // 第二遍：任意可写/可通知兜底
        if (writeChar == null || notifyChar == null) {
            for (BluetoothGattService s : pool) {
                for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                    int p = c.getProperties();
                    boolean writable = (p & (BluetoothGattCharacteristic.PROPERTY_WRITE
                            | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0;
                    boolean notifiable = (p & (BluetoothGattCharacteristic.PROPERTY_NOTIFY
                            | BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0;
                    if (writeChar == null && writable) writeChar = c;
                    if (notifyChar == null && notifiable) notifyChar = c;
                }
            }
        }
        return writeChar != null && notifyChar != null;
    }

    private void subscribeNotifyChar() {
        if (gatt == null || notifyChar == null) return;
        try {
            subscribed = true;
            gatt.setCharacteristicNotification(notifyChar, true);
            BluetoothGattDescriptor desc = notifyChar.getDescriptor(UUID_CCC);
            if (desc == null) {
                startPolling();
                return;
            }
            boolean indicate = (notifyChar.getProperties()
                    & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;
            desc.setValue(indicate
                    ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            boolean ok = gatt.writeDescriptor(desc);
            if (!ok) {
                // 写 CCC 失败：等待片刻后仍启动轮询兜底（onDescriptorWrite 幂等）
                main.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (polling == false) startPolling();
                    }
                }, 600);
            }
        } catch (Exception e) {
            Log.e(TAG, "subscribe notify fail", e);
            startPolling();
        }
        // 即便 CCC 写成功也未见 onDescriptorWrite，则在 1.5s 后兜底启动
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (polling == false) startPolling();
            }
        }, 1500);
    }

    // ---------------------------------------------------------------- Modbus 周期读取

    private final Runnable pollTick = new Runnable() {
        @Override
        public void run() {
            if (polling) startCycle();
        }
    };

    private void startPolling() {
        if (polling) return;
        polling = true;
        lastCellCount = 0;
        rxBuf = new byte[0];
        main.removeCallbacks(pollTick);
        main.postDelayed(pollTick, 200);
    }

    private void stopPolling() {
        polling = false;
        cycleId++;            // 使旧周期遗留的延迟任务失效
        main.removeCallbacks(pollTick);
    }

    // ---- Modbus 请求-应答串行事务状态（同小程序 bms-client：一问一答，最稳） ----
    private final List<int[]> txReq = new ArrayList<int[]>();
    private int txIdx = 0;
    private byte[] accBase = null;
    private byte[] accHead = null;
    private byte[] accCells = null;

    /** 开始一轮仪表盘事务：base -> head -> (cells 按需)。 */
    private void startCycle() {
        if (gatt == null || writeChar == null || notifyChar == null || !subscribed) {
            if (polling) main.postDelayed(pollTick, POLL_GAP_MS);
            return;
        }
        txReq.clear();
        txReq.add(new int[]{READ_BASE_START, READ_BASE_COUNT});
        txReq.add(new int[]{READ_HEAD_START, READ_HEAD_COUNT});
        int n = lastCellCount;
        if (n > 0 && n <= 32) {
            for (int i = 0; i < n; i += LianModbus.MAX_REGS_PER_READ) {
                txReq.add(new int[]{CELL_REG_START + i,
                        Math.min(LianModbus.MAX_REGS_PER_READ, n - i)});
            }
        }
        txIdx = 0;
        accBase = null;
        accHead = null;
        accCells = null;
        final int cid = cycleId;
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (cid == cycleId && polling) sendNext(cid);
            }
        }, 100);
    }

    /** 发送当前请求并进入等待响应状态。 */
    private void sendNext(final int cid) {
        if (txIdx >= txReq.size()) {
            finishCycle(cid);
            return;
        }
        int[] r = txReq.get(txIdx);
        sendRead(r[0], r[1]);
        waitFrame(cid, txIdx, 0);
    }

    /** 每 120ms 轮询缓冲是否有完整响应帧；取到后按序归位并发送下一请求。 */
    private void waitFrame(final int cid, final int expectIdx, final int attempt) {
        if (cid != cycleId || !polling) return;
        int off = 0;
        while (off + 2 <= rxBuf.length) {
            int len = LianModbus.scanFrameLen(rxBuf, off, rxBuf.length - off);
            if (len == -1) break;
            if (len == -2) {
                off++;
                continue;
            }
            byte[] data = consumeFirstFrame(off, len);
            if (data != null) {
                int st = txReq.get(expectIdx)[0];
                if (st == READ_BASE_START) accBase = data;
                else if (st == READ_HEAD_START) accHead = data;
                else if (st >= CELL_REG_START) {
                    accCells = (accCells == null) ? data : concat(accCells, data);
                }
                txIdx = expectIdx + 1;
                sendNext(cid);
                return;
            }
        }
        if (attempt >= 18) {   // ~2.2s 无响应，本轮跳过，下轮重试
            txIdx = txReq.size();
            if (polling) main.postDelayed(pollTick, POLL_GAP_MS);
            return;
        }
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                waitFrame(cid, expectIdx, attempt + 1);
            }
        }, 120);
    }

    /** 从缓冲取走第 off 处整帧，返回其负载数据。 */
    private byte[] consumeFirstFrame(int off, int len) {
        if (len <= 0 || off + len > rxBuf.length) return null;
        int dataLen = 0;
        int dataOff = 0;
        try {
            int bc = rxBuf[off + 2] & 0xFF;
            if ((5 + bc) == len) {          // 标准短帧
                dataOff = off + 3;
                dataLen = bc;
            } else if ((7 + (rxBuf[off + 4] & 0xFF)) == len) { // 扩展帧
                dataOff = off + 5;
                dataLen = rxBuf[off + 4] & 0xFF;
            } else {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
        byte[] data = new byte[dataLen];
        System.arraycopy(rxBuf, dataOff, data, 0, dataLen);
        byte[] rest = new byte[rxBuf.length - off - len];
        System.arraycopy(rxBuf, off + len, rest, 0, rest.length);
        rxBuf = rest;
        return data;
    }

    /** 一轮完成：base+head 齐即解码回调，然后安排下一轮。 */
    private void finishCycle(int cid) {
        if (cid != cycleId || !polling) return;
        BmsData d = decodeCycle();
        if (d != null) {
            listener.onBmsData(d);
        }
        if (polling) {
            main.postDelayed(pollTick, POLL_GAP_MS);
        }
    }

    /** 解码（base 必含电压/电流/温度，head 必含 SOC/串数）。 */
    private BmsData decodeCycle() {
        if (accBase == null || accHead == null) return null;
        BmsData d = new BmsData();
        d.time = System.currentTimeMillis();
        d.voltage = LianModbus.regU32(accBase, 4) / 1000.0;
        d.current = LianModbus.regI32(accBase, 6) / 1000.0;
        d.temp = LianModbus.k2c(LianModbus.regU16(accBase, 1));
        int socRaw = LianModbus.regU16(accHead, 0) & 0xFF;
        d.soc = Math.min(socRaw, 100);
        int nCells = (LianModbus.regU16(accHead, 4) >> 8) & 0xFF;
        if (nCells > 32) nCells = 0;
        d.cellCount = nCells;
        if (nCells > 0 && accCells != null && accCells.length >= 2 * nCells) {
            int min = 65535, max = 0;
            for (int i = 0; i < nCells; i++) {
                int mv = LianModbus.regU16(accCells, i);
                if (mv < min) min = mv;
                if (mv > max) max = mv;
            }
            d.cellDeltaV = (max - min) / 1000.0;
        }
        lastCellCount = nCells;
        return d;
    }

    /** 发送一条 0x03 读请求。
        实测该 BMS 只响应 write-without-response(0x04) 特征的“无响应写”
        （同 esp32/bleak response=False），故优先 WRITE_NO_RESPONSE，仅不支持时用带响应写。 */
    private void sendRead(int start, int count) {
        if (gatt == null || writeChar == null) return;
        try {
            BluetoothGattCharacteristic c = writeChar;
            byte[] frame = LianModbus.read03(start, count);
            if ((c.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            } else {
                c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            }
            c.setValue(frame);
            boolean ok = gatt.writeCharacteristic(c);
            if (!ok) {
                Log.w(TAG, "writeCharacteristic false start=" + start);
            }
        } catch (Exception e) {
            Log.w(TAG, "sendRead fail start=" + start, e);
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private void appendRx(byte[] v) {
        rxBuf = concat(rxBuf, v);
    }

    // ---------------------------------------------------------------- 状态分发

    private void postState(final String s, final String msg) {
        state = s;
        main.post(new Runnable() {
            @Override
            public void run() {
                listener.onBleState(s, msg);
            }
        });
    }
}
