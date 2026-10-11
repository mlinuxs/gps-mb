# 骑行导航（手机版）

> 本仓库为**手机版**（vivo Y51A 等安卓手机）。手表版（OPPO Watch 3 Pro 单屏 + 地图轮换）见 https://github.com/mlinuxs/gps-wb

通过 **BLE 蓝牙电池保护板** 读取电压/电流/电量/温度，通过 **手机 GPS** 记录位置、速度、里程、坡度，
数据实时显示在内置仪表盘（WebView + H5），并持续写入 **CSV 日志**（手机 Download 目录），可导出归档分析。

- 目标设备：**vivo Y51A（Android 5.0 / API-21，Funtouch OS 2.5，540×960）**，兼容其他 Android 5.0+ 手机
- 技术架构：**Java 原生 + WebView 套壳（H5 纯 ES5，无前端框架，离线运行）**
- 构建环境：**Gradle 5.6.4 + AGP 3.6.4 + JDK8**，零第三方依赖，`gradlew assembleDebug` 直接出 APK
- `minSdkVersion 21 / targetSdkVersion 23`（Android 6.0+ 运行时权限，见 MainActivity 反射实现）

---

## 一、功能一览

| 模块 | 功能 |
|---|---|
| BLE 蓝牙 | **启动即自动搜索并连接 LABMS 系列保护板**（断线重连 3 次，失败后循环回扫，回到范围内自动再连）；也可弹窗列表选择或手动输入 MAC 连接；自动订阅 Notify 特征、二进制协议解析（电压/电流/电量/温度）、状态实时上报 |
| GPS 定位 | **APP 启动即自动开始定位记录**；持续接收经纬度/海拔/速度/时间戳；Haversine 公式累加里程；坡度=海拔差/水平距离；海拔滑动平均、速度/坡度 EMA 滤波；无信号/信号弱/正常三态提示 |
| 停车过滤 | **自动识别停车休息状态**（如吃饭/拍照）：滤波速度 <2km/h 持续 30 秒判定停车 → 暂停计时、冻结里程/坡度（防 GPS 漂移虚增）；离开停车点 20m 或连续 3 点速度 ≥4km/h 自动恢复记录 |
| 骑行导航 | **高德真实底图 + 骑行轨迹叠加**（JS API 1.4.x）：绿色轨迹线/蓝色航向箭头跟随/比例尺；**目的地关键字搜索**（AMap.PlaceSearch，输入后下方出候选列表，点选即开始导航），**默认骑行模式规划路线**（AMap.Riding，橙色路线叠加在地图上，导航条实时显示剩余里程/预计时间）；右上角小按钮可切「轨迹图/高德地图」；**无网络或未配置 Key 自动回退离线自绘轨迹图**（绿色轨迹/行进箭头/起终点/比例尺/指北针），离线时目的地退化为直线方向与距离；弹窗保留「高德APP导航」兜底（骑行模式 t=3）。**高德 Key 在 `assets/index.html` 顶部 `AMAP_KEY` 处替换**（2021-12 后申请的 Key 同时填 `AMAP_SECURITY`） |
| 界面布局 | **三区单屏免滚动**：顶部 运动卡（大字体速度 + 右侧 里程/时长/坡度/海拔，约 1/4 屏）→ 中部 实时轨迹导航图（约 4 成屏，上方为导航信息条：目的地/剩余里程/预计时间）→ 底部 电池卡（**剩余电量超大字体** + 右侧 电压/电流/温度/压差）→ 小按钮栏【暂停/继续】【停止/开始】【目的地】【蓝牙】【常亮】；整体以 360px 设计宽度等比缩放适配不同分辨率 |
| 前台服务 | **启动即进入前台采集**，状态栏常驻通知「骑行记录中」（实时显示速度/里程，停车时提示休息中），PARTIAL_WAKE_LOCK 保持 CPU，最大限度对抗后台查杀 |
| 屏幕常亮 | H5 开关控制 `FLAG_KEEP_SCREEN_ON`，骑行模式屏幕不熄 |
| CSV 记录 | 1 秒 1 行（**停车/未定位时段自动降频为 30 秒 1 条**，末列标注「骑行/静止」状态；暂停记录时不写入），UTF-8（带 BOM，Excel 直接打开），保存到 `Download/RideLog_yyyyMMdd_HHmmss.csv`，每条 flush |
| 暂停记录 | 小按钮【暂停】：GPS 保持定位，暂停计时/里程/CSV 写入（暂停期间漂移不计入）；【继续】无缝接续 |
| 网盘备份 | **点【停止】后 CSV 自动上传百度网盘**（OpenAPI，远端目录「我的应用数据/rideLog/」）：点【网盘】首次弹出授权页登录百度账号（授权码自动换取，无需手动复制）；**无网络时留在待传队列，网络恢复自动续传**（NetworkCallback）；上传成功记录本地已传清单，**重启不重复上传**；access_token 30 天有效，过期自动用 refresh_token 续期。凭证在 `BaiduPanManager.java` 顶部 `APP_KEY/SECRET_KEY` |
| JS Bridge | H5→Java：`startRecord / stopRecord / pauseRecord / resumeRecord / setScreenKeepOn / startBleScan / stopBleScan / connectBle / disconnectBle / openNavigation / bindBaiduPan`；Java→H5：`onDataUpdate / onStatusUpdate / onBleDevices / onUploadUpdate`，1Hz 节流推送 |

---

## 二、工程目录

```
Gps-phone/
├── build.gradle                 # 顶层构建（AGP 3.6.4）
├── settings.gradle
├── gradle.properties
├── gradlew / gradlew.bat        # Gradle Wrapper 5.6.4
├── gradle/wrapper/...
└── app/
    ├── build.gradle             # minSdk 21 / targetSdk 23（运行时权限见 MainActivity），Java 8
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml  # 权限+Activity+前台服务
        ├── assets/index.html    # H5 仪表盘（ES5，高德地图+Canvas轨迹，全部内联）
        ├── res/                 # 图标、主题
        └── java/com/ridelog/app/
            ├── MainActivity.java       # WebView 承载、JS桥、运行时权限、高德APP导航兜底
            ├── JsBridge.java           # H5->Java @JavascriptInterface
            ├── RideService.java        # 前台服务、1Hz 聚合、通知、WakeLock
            ├── ble/BleManager.java     # BLE 扫描/连接/重连/GATT 订阅
            ├── ble/BmsParser.java      # 二进制协议解析（粘包/断帧/校验）
            ├── ble/BmsData.java
            ├── ble/LianModbus.java     # Modbus-RTU 帧构造（CRC-16/XMODEM）
            ├── gps/GpsManager.java     # 定位/里程/坡度/滤波/停车过滤
            ├── gps/GpsData.java
            └── storage/CsvLogger.java  # CSV 文件写入
```

---

## 三、编译步骤（VSCode + 命令行）

1. 安装 **JDK 8**，设置 `JAVA_HOME` 指向 JDK8 目录。
2. 安装 Android SDK 命令行工具（commandlinetools），用 sdkmanager 安装：
   ```
   sdkmanager "platforms;android-21" "build-tools;28.0.3"
   ```
3. 在**项目根目录**新建 `local.properties`，写入 SDK 路径（Windows 用正斜杠或双反斜杠）：
   ```
   sdk.dir=D:/Android/Sdk
   ```
4. 项目根目录执行：
   ```
   gradlew.bat assembleDebug
   ```
   首次编译需联网下载 Gradle/AGP 依赖。
5. 产物路径：
   ```
   app/build/outputs/apk/debug/app-debug.apk
   ```

> 说明：本工程未使用 AndroidX 与任何第三方库，全部为 Android 系统 API。

---

## 四、安装与使用

**安装**

- 命令行：`adb install app-debug.apk`
- 或把 APK 传到手机，文件管理器点击安装（需在系统设置中允许「未知来源」安装）。

**使用流程**

1. 打开 APP → **自动开始 GPS 定位与轨迹记录，同时自动搜索连接 LABMS 保护板**（全程无需操作；状态栏显示「骑行记录中」即正常。设备不在范围/连接失败时每 3 秒回扫，回到范围自动再连）。也可点【蓝牙】手动选列表或输 MAC。
2. 界面三区显示：顶部 速度/里程/时长/坡度/海拔；**中部实时轨迹导航图**（绿色骑行轨迹、蓝色行进箭头、比例尺、指北针）；底部 电池电压/电流/电量/温度。
3. 需要导航：点【目的地】→ 输入目的地关键字（如「人民公园」）→ 下方出现候选列表 → **点选一个即自动按骑行模式规划路线开始导航**：地图叠加橙色骑行路线，导航条实时显示剩余里程与预计时间（需手机联网）；无网络时地图上退化为目的地直线方向与距离。也可在弹窗中点「高德APP导航」唤起高德APP骑行导航。
4. 中途停车休息（吃饭等）超过 30 秒自动识别：暂停计时、里程冻结；重新骑走自动继续。也可随时手动点【暂停】/【继续】。
5. 打开【常亮】可防熄屏。
6. 点【停止】：停止采集，CSV 已保存到手机 **Download** 目录，文件名形如 `RideLog_20260904_120530.csv`（静止时段仅每 30 秒 1 条并标注状态）；停止后可点【开始】重新记录。

---

## 五、vivo 手机保活设置（必做）

Android 5.1 与 vivo 系统对后台应用管控严格，为获得更稳定的记录体验，请在系统中完成以下设置：

1. **允许自启动**：i管家 / 设置 → 权限管理 → 自启动 → 允许「骑行数据采集」自启动。
2. **允许后台高耗电**：设置 → 电池 → 后台耗电管理（高耗电）→ 允许本应用后台持续运行。
3. **任务卡片加锁**：打开 APP 后按「多任务键」→ 下拉或长按应用卡片，点锁头图标锁定，防止一键清理。
4. **关闭省电限制**：设置 → 电池 → 后台省电/智能省电中，将本应用加入白名单。
5. 采集时**保持 APP 在前台并开启屏幕常亮**；通知栏常驻「骑行记录中」即前台服务存活。

> 注：熄屏后在 Android 5.1/vivo 上的持续采集受系统深度限制，**不做保证**；
> 建议长距离骑行时外接充电宝并保持常亮，停车熄屏前先确认 GPS 已定位。

---

## 六、电池保护板 BLE 协议适配

本机（锂安/LABMS）保护板为 **Modbus-RTU over BLE（0x03 读保持寄存器）**，非主动上报帧。
连接 2760 服务后，由 App 周期发送读寄存器请求，把通知返回的响应帧解析成仪表盘数据。

- BLE 服务/特征：服务 `00002760-...-1001`，写入 `...-0001`（WRITE_NO_RESPONSE），通知 `...-0002`
- 帧结构：`[从机=22][0x03][起始Hi][起始Lo][数量Hi][数量Lo][CRC低][CRC高]`
- CRC：CRC-16/XMODEM（初值 0x0000，poly 0x1021，高位在前，低字节在前追加），见 [LianModbus.java](app/src/main/java/com/ridelog/app/ble/LianModbus.java)
- 单次最多读 8 个寄存器；轮询读取：寄存器 0-7（电压 u32 mV / 电流 i32 mA / 温度 0.1K）、寄存器 20-24（SOC / 电芯串数）、寄存器 32 起按串数读单体电压，见 [BleManager.java](app/src/main/java/com/ridelog/app/ble/BleManager.java) 周期引擎

若你的保护板寄存器表不同，只需调整 `LianModbus` 解码常量与 `BleManager` 的读块起始地址/映射即可。

---

## 七、常见问题

| 现象 | 处理 |
|---|---|
| GPS 一直「无信号」 | 到露天环境等待 30 秒~2 分钟；确认系统「位置信息」开关已打开 |
| 搜不到蓝牙设备 | 打开手机蓝牙；Android 6+ 需同时打开定位开关；确认保护板已通电；App 只显示目标设备名 LABMS |
| 连接后无电池数据 | 多为寄存器表不同，按第六节核对 `LianModbus`/`BleManager` 读块 |
| 地图不显示 | 未配置 `AMAP_KEY` 或无网络，自动用离线轨迹图；到 [高德开放平台](https://lbs.amap.com/) 申请 Web端(JS) Key 填入 `assets/index.html` |
| 电流显示负数 | 充/放电方向由保护板固件定义，数值符号按原始数据透传 |
| 坡度跳动/不准 | 手机 GPS 海拔误差较大，坡度仅作参考，已做滑动平均+±40% 限幅 |
| CSV 在电脑哪里 | 手机存储 `Download` 目录；用 Excel 直接打开（已带 UTF-8 BOM） |
