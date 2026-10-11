package com.ridelog.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.WindowManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import com.ridelog.app.ble.BleManager;

/**
 * 唯一界面：全屏 WebView 加载 assets/index.html（file:///android_asset/index.html）。
 * 负责绑定 RideService、注册数据回调、屏幕常亮、JS 调用分发、高德 APP 导航兜底。
 */
public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";
    private static final int PERM_REQ = 1001;

    private WebView webView;
    private RideService service;
    private boolean bound = false;
    private boolean serviceStarted = false;

    private final RideService.UiCallback uiCallback = new RideService.UiCallback() {
        @Override
        public void onDataUpdate(String json) {
            callJs("onDataUpdate", json);
        }

        @Override
        public void onStatusUpdate(String json) {
            callJs("onStatusUpdate", json);
        }

        @Override
        public void onBleDevices(String json) {
            callJs("onBleDevices", json);
        }

        @Override
        public void onUploadUpdate(String message) {
            callJs("onUploadUpdate", message);
        }
    };

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((RideService.LocalBinder) binder).getService();
            service.setUiCallback(uiCallback);
            bound = true;
            // 启动即自动搜索连接 LABMS 系列保护板
            service.autoConnectBle(BleManager.TARGET_DEVICE_NAME);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            bound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        webView.setWebViewClient(new WebViewClient());
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        // 放宽 file:// 页面资源访问限制（高德 JS 地图需要访问网络资源）
        try {
            webView.getSettings().setAllowFileAccessFromFileURLs(true);
            webView.getSettings().setAllowUniversalAccessFromFileURLs(true);
        } catch (Exception ignored) {
        }
        webView.addJavascriptInterface(new JsBridge(this), "AndroidBridge");
        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");

        // 授权后才启动采集服务：服务 onCreate 会立即开始 GPS 记录并创建 CSV
        if (hasCollectPermissions()) {
            startCollectService();
        } else {
            requestCollectPermissions();
        }
    }

    // ------------------------------------------------------------ 运行时权限（Android 6.0+）

    /** 采集所需权限：定位（GPS + BLE 扫描）与存储（CSV 落盘）。 */
    private static String[] collectPermissions() {
        return new String[]{
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                android.Manifest.permission.READ_EXTERNAL_STORAGE
        };
    }

    /** Android 6.0 以下为安装时授权，直接视为已授权。 */
    private boolean hasCollectPermissions() {
        if (Build.VERSION.SDK_INT < 23) { return true; }
        try {
            java.lang.reflect.Method check =
                    Context.class.getMethod("checkSelfPermission", String.class);
            for (String p : collectPermissions()) {
                Object r = check.invoke(this, p);
                if (!(r instanceof Integer) || ((Integer) r) != PackageManager.PERMISSION_GRANTED) {
                    return false;
                }
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "checkSelfPermission fail, assume granted", t);
            return true;
        }
    }

    /** compileSdk=21 无 requestPermissions，用反射调用（Android 6.0+ 运行时存在该方法）。 */
    private void requestCollectPermissions() {
        try {
            java.lang.reflect.Method req =
                    Activity.class.getMethod("requestPermissions", String[].class, int.class);
            req.invoke(this, collectPermissions(), PERM_REQ);
        } catch (Throwable t) {
            Log.w(TAG, "requestPermissions fail", t);
            startCollectService();
        }
    }

    /**
     * 系统权限回调。compileSdk=21 无法写 @Override，但运行期签名一致，
     * Android 6.0+ 会正常回调到这里（Activity 的实现本身为空，无需 super）。
     */
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != PERM_REQ) { return; }
        if (hasCollectPermissions()) {
            startCollectService();
        } else {
            Toast.makeText(this, "缺少定位/存储权限，无法记录骑行数据，请在系统设置中授予",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void startCollectService() {
        if (serviceStarted) { return; }
        serviceStarted = true;
        Intent svc = new Intent(this, RideService.class);
        try {
            startService(svc);
            bindService(svc, conn, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            Log.e(TAG, "start/bind service fail", e);
        }
    }

    // ------------------------------------------------------------ H5 指令入口

    public void onStartRecord() {
        if (service != null) service.startRecord();
    }

    public void onStopRecord() {
        if (service != null) service.stopRecord();
    }

    public void onPauseRecord() {
        if (service != null) service.pauseRecord();
    }

    public void onResumeRecord() {
        if (service != null) service.resumeRecord();
    }

    public void onStartBleScan() {
        if (service != null) service.startBleScan();
    }

    public void onStopBleScan() {
        if (service != null) service.stopBleScan();
    }

    public void onConnectBle(String mac) {
        if (service != null) service.connectBle(mac);
    }

    public void onDisconnectBle() {
        if (service != null) service.disconnectBle();
    }

    public void onSetScreenKeepOn(boolean enable) {
        if (enable) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    /**
     * 唤起高德 APP 骑行导航（t=3）。有目的地坐标时直接规划到目的地；
     * 未安装高德时退回网页版导航 URI。
     */
    public void onOpenNavigation(String dlat, String dlon, String dname) {
        try {
            StringBuilder uri = new StringBuilder("amapuri://route/plan/?sourceApplication=ridelog")
                    .append("&t=3") // 3 = 骑行
                    .append("&dev=0");
            if (dlat != null && dlat.trim().length() > 0
                    && dlon != null && dlon.trim().length() > 0) {
                uri.append("&dlat=").append(dlat.trim())
                        .append("&dlon=").append(dlon.trim());
            }
            if (dname != null && dname.trim().length() > 0) {
                uri.append("&dname=").append(java.net.URLEncoder.encode(dname.trim(), "UTF-8"));
            }
            Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri.toString()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            // 未安装高德 APP：退回网页版骑行导航
            try {
                String url = "https://uri.amap.com/navigation?to="
                        + (dlon == null ? "" : dlon.trim()) + ","
                        + (dlat == null ? "" : dlat.trim())
                        + "," + java.net.URLEncoder.encode(
                                dname == null ? "目的地" : dname.trim(), "UTF-8")
                        + "&mode=ride&policy=&src=ridelog&coordinate=gaode&callnative=0";
                Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception e2) {
                Log.w(TAG, "open navigation fail", e2);
                Toast.makeText(this, "无法打开导航，请安装高德地图", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ------------------------------------------------------------ 百度网盘

    /**
     * H5 点【网盘】：未配置 AppKey 提示；已绑定则立即续传；否则弹出授权 WebView。
     */
    public void onBindBaiduPan() {
        if (service == null) { return; }
        if (!service.isBaiduConfigured()) {
            Toast.makeText(this,
                    "请先在 pan.baidu.com/union 创建应用，把 AppKey/SecretKey 填入 BaiduPanManager",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (service.isBaiduBound()) {
            Toast.makeText(this,
                    "网盘已绑定，待传 " + service.baiduPendingCount() + " 个，正在上传…",
                    Toast.LENGTH_SHORT).show();
            service.uploadBaiduPendingNow();
            return;
        }
        showBaiduAuthDialog();
    }

    /** 全屏 WebView 打开百度授权页；登录授权后自动拦截回调码，无需手动复制。 */
    private void showBaiduAuthDialog() {
        final String url = com.ridelog.app.pan.BaiduPanManager.buildAuthUrl();
        if (url == null) { return; }
        final Dialog dlg = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        WebView wv = new WebView(this);
        wv.getSettings().setJavaScriptEnabled(true);
        wv.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String u) {
                if (u != null && u.contains("/oauth/2.0/oob")) {
                    try {
                        android.net.Uri cu = android.net.Uri.parse(u);
                        String code = cu.getQueryParameter("code");
                        dlg.dismiss();
                        if (code != null && code.trim().length() > 0) {
                            Toast.makeText(MainActivity.this, "正在绑定网盘…", Toast.LENGTH_SHORT).show();
                            service.exchangeBaiduCode(code.trim());
                        }
                    } catch (Exception ignored) {
                    }
                    return true; // 阻止 WebView 实际加载 oob 回调页
                }
                return false;
            }
        });
        wv.loadUrl(url);
        dlg.setContentView(wv);
        dlg.show();
    }

    // ------------------------------------------------------------ Java -> H5

    private void callJs(final String fn, final String json) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (webView == null) return;
                try {
                    webView.evaluateJavascript("javascript:" + fn + "(" + jsQuote(json) + ");", null);
                } catch (Exception e) {
                    Log.w(TAG, "callJs fail: " + fn, e);
                }
            }
        });
    }

    /** 把 JSON 字符串包成安全的 JS 单引号字符串字面量。 */
    private static String jsQuote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        sb.append('\'');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\'':
                    sb.append("\\'");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                default:
                    sb.append(c);
            }
        }
        sb.append('\'');
        return sb.toString();
    }

    @Override
    protected void onDestroy() {
        if (service != null) {
            try {
                service.setUiCallback(null);
            } catch (Exception ignored) {
            }
        }
        if (bound) {
            try {
                unbindService(conn);
            } catch (Exception ignored) {
            }
            bound = false;
        }
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
