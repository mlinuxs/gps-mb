package com.ridelog.app.pan;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 百度网盘个人云盘 OpenAPI 上传管理（零第三方依赖，纯 HttpsURLConnection）。
 *
 * - 授权：oob 方式，H5 触发 WebView 打开授权页，登录后拦截回调码换取 access_token
 * - 上传：预创建(precreate) -> 分片上传(superfile2，小文件单分片) -> 创建文件(create)
 * - 队列：待上传文件名存 SharedPreferences；网络不可用时不传，恢复后由服务触发续传
 * - 去重：上传成功的文件名记入已传清单，重启也不会重复上传
 * - Token：access_token 30 天有效，过期自动用 refresh_token 续期
 *
 * 远端目录：网盘「我的应用数据/rideLog/」（OpenAPI 仅允许写入 /apps/ 下）。
 */
public class BaiduPanManager {

    public interface Listener {
        /** 上传/授权状态消息（主线程回调），用于透传给 H5 提示。 */
        void onUploadState(String message);
    }

    /* 百度网盘开放平台应用「netdisk」的凭证（https://pan.baidu.com/union） */
    public static final String APP_KEY = "CVf9ZQeSwc4RONmpC5E5lMi5lh15GO4Q";
    public static final String SECRET_KEY = "Six3g4puLt8G5zotn38lecATgLGOK07v";

    private static final String TAG = "BaiduPan";
    private static final String REMOTE_DIR = "/apps/rideLog/";
    private static final String PREFS = "baidupan";
    private static final String K_ACCESS = "access_token";
    private static final String K_REFRESH = "refresh_token";
    private static final String K_EXPIRES = "expires_at";
    private static final String K_DONE = "uploaded_files";
    private static final String K_PENDING = "pending_files";

    private final Context ctx;
    private final Handler main;
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private volatile Listener listener;
    private volatile boolean uploading = false;

    public BaiduPanManager(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.main = new Handler(Looper.getMainLooper());
    }

    public void setListener(Listener l) {
        listener = l;
    }

    private void notifyState(String msg) {
        final String m = msg;
        main.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) listener.onUploadState(m);
            }
        });
    }

    // ------------------------------------------------------------ 配置/授权状态

    /** AppKey 是否已配置（占位符视为未配置）。 */
    public static boolean isConfigured() {
        return APP_KEY != null && APP_KEY.length() > 0 && !APP_KEY.startsWith("YOUR_");
    }

    /** 本地是否已保存授权 token。 */
    public boolean hasToken() {
        return prefs().contains(K_ACCESS);
    }

    /** 待上传文件数。 */
    public int pendingCount() {
        return prefs().getStringSet(K_PENDING, new HashSet<String>()).size();
    }

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 构造授权页地址（oob 回调，display=mobile 适合手机 WebView）。 */
    public static String buildAuthUrl() {
        try {
            return "https://openapi.baidu.com/oauth/2.0/authorize"
                    + "?response_type=code"
                    + "&client_id=" + URLEncoder.encode(APP_KEY, "UTF-8")
                    + "&redirect_uri=oob"
                    + "&scope=basic,netdisk"
                    + "&display=mobile";
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------ 授权码换 token

    /** 用授权码换取 token（后台线程），成功后通知。 */
    public void exchangeCode(final String code) {
        if (!isConfigured() || code == null || code.trim().length() == 0) { return; }
        pool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String url = "https://openapi.baidu.com/oauth/2.0/token"
                            + "?grant_type=authorization_code"
                            + "&code=" + URLEncoder.encode(code.trim(), "UTF-8")
                            + "&client_id=" + URLEncoder.encode(APP_KEY, "UTF-8")
                            + "&client_secret=" + URLEncoder.encode(SECRET_KEY, "UTF-8")
                            + "&redirect_uri=oob";
                    JSONObject o = httpGetJson(url);
                    if (o == null || !o.has("access_token")) {
                        notifyState("网盘授权失败：" + errDesc(o));
                        return;
                    }
                    saveToken(o);
                    notifyState("百度网盘绑定成功，停止记录后自动上传");
                    uploadPendingNow();
                } catch (Exception e) {
                    Log.w(TAG, "exchange code fail", e);
                    notifyState("网盘授权失败：" + e.getMessage());
                }
            }
        });
    }

    /** access_token 过期时用 refresh_token 续期。返回是否可用。 */
    private boolean ensureValidToken() {
        SharedPreferences p = prefs();
        long expiresAt = p.getLong(K_EXPIRES, 0);
        if (p.contains(K_ACCESS) && System.currentTimeMillis() < expiresAt - 60000L) {
            return true;
        }
        String rt = p.getString(K_REFRESH, null);
        if (rt == null || rt.length() == 0) { return false; }
        try {
            String url = "https://openapi.baidu.com/oauth/2.0/token"
                    + "?grant_type=refresh_token"
                    + "&refresh_token=" + URLEncoder.encode(rt, "UTF-8")
                    + "&client_id=" + URLEncoder.encode(APP_KEY, "UTF-8")
                    + "&client_secret=" + URLEncoder.encode(SECRET_KEY, "UTF-8");
            JSONObject o = httpGetJson(url);
            if (o != null && o.has("access_token")) {
                saveToken(o);
                return true;
            }
            if (o != null && o.has("error")) {
                // refresh_token 已失效（如 long 期未用被吊销）：清空本地授权，让用户重新绑定
                prefs().edit().remove(K_ACCESS).remove(K_REFRESH).remove(K_EXPIRES).apply();
                notifyState("网盘授权已过期，请点【网盘】重新绑定");
            }
        } catch (Exception e) {
            Log.w(TAG, "refresh token fail", e);
        }
        return false;
    }

    private void saveToken(JSONObject o) {
        try {
            prefs().edit()
                    .putString(K_ACCESS, o.getString("access_token"))
                    .putString(K_REFRESH, o.getString("refresh_token"))
                    .putLong(K_EXPIRES, System.currentTimeMillis()
                            + o.getLong("expires_in") * 1000L)
                    .apply();
        } catch (Exception e) {
            Log.w(TAG, "save token fail", e);
        }
    }

    // ------------------------------------------------------------ 上传队列

    /** 把已停止的 CSV 加入待上传队列。 */
    public void queueUpload(File f) {
        if (f == null || !isConfigured()) { return; }
        String name = f.getName();
        Set<String> done = prefs().getStringSet(K_DONE, new HashSet<String>());
        if (done.contains(name)) { return; } // 已传过，跳过
        Set<String> pending = new HashSet<String>(prefs().getStringSet(K_PENDING, new HashSet<String>()));
        pending.add(name);
        prefs().edit().putStringSet(K_PENDING, pending).apply();
        notifyState("已加入网盘上传队列（" + pending.size() + " 个待传）");
        uploadPendingNow();
    }

    /** 触发上传待传清单（有网+已授权才真正上传；单线程串行防并发）。 */
    public void uploadPendingNow() {
        if (!isConfigured() || uploading) { return; }
        uploading = true;
        pool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    loopPending();
                } finally {
                    uploading = false;
                }
            }
        });
    }

    private void loopPending() {
        if (!networkOk()) {
            return; // 无网络：留在队列，等网络恢复再传
        }
        if (!hasToken()) {
            return; // 未授权：等用户绑定后自动续传
        }
        int okCount = 0;
        while (true) {
            Set<String> pending = new HashSet<String>(prefs().getStringSet(K_PENDING, new HashSet<String>()));
            if (pending.isEmpty()) { break; }
            String name = pending.iterator().next();
            File base = new File(android.os.Environment
                    .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                    "");
            File f = new File(base, name);
            if (!f.exists()) {
                // 文件已不存在（被清理）：直接视为完成，避免死循环
                removePending(name);
                continue;
            }
            String err = upload(f, REMOTE_DIR + name);
            if (err == null) {
                removePending(name);
                addDone(name);
                okCount++;
                notifyState("已上传网盘：" + name);
            } else {
                Log.w(TAG, "upload fail: " + name + " -> " + err);
                notifyState("网盘上传失败，稍后自动重试：" + err);
                break; // 一个失败即停，等下次触发（网络恢复/停止记录/手动）
            }
        }
        if (okCount > 0 && prefs().getStringSet(K_PENDING, new HashSet<String>()).isEmpty()) {
            notifyState("网盘全部上传完成（" + okCount + " 个）");
        }
    }

    private void removePending(String name) {
        Set<String> pending = new HashSet<String>(prefs().getStringSet(K_PENDING, new HashSet<String>()));
        pending.remove(name);
        prefs().edit().putStringSet(K_PENDING, pending).apply();
    }

    private void addDone(String name) {
        Set<String> done = new HashSet<String>(prefs().getStringSet(K_DONE, new HashSet<String>()));
        done.add(name);
        prefs().edit().putStringSet(K_DONE, done).apply();
    }

    private boolean networkOk() {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) { return false; }
            NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------ 三步上传

    /**
     * 预创建 -> 上传分片 -> 创建文件。成功返回 null，失败返回错误描述。
     * CSV 均小于 4MB，单分片即可；超出时按 4MB 切片循环。
     */
    private String upload(File f, String remotePath) {
        try {
            if (!ensureValidToken()) { return "授权过期，请重新绑定网盘"; }
            byte[] data = readFile(f);
            String md5 = md5Hex(data);

            /* 1. 预创建 */
            String preBody = "path=" + URLEncoder.encode(remotePath, "UTF-8")
                    + "&size=" + data.length
                    + "&isdir=0"
                    + "&block_list=" + URLEncoder.encode("[\"" + md5 + "\"]", "UTF-8")
                    + "&rtype=3"; // 3 = 同名文件覆盖
            JSONObject pre = httpPostJson(
                    "https://pan.baidu.com/rest/2.0/xpan/file?method=precreate&access_token="
                            + accessToken(), preBody,
                    "application/x-www-form-urlencoded");
            if (pre == null) { return "网络请求失败"; }
            int errno = pre.optInt("errno", -1);
            if (errno != 0) { return errDesc(pre); }
            if (pre.optInt("return_type", 0) == 2) {
                return null; // 秒传成功
            }
            String uploadId = pre.optString("uploadid", null);
            if (uploadId == null || uploadId.length() == 0) { return "未获取到 uploadid"; }

            /* 2. 分片上传（<4MB 单片 partseq=0；更大时按 4MB 切片） */
            int chunk = 4 * 1024 * 1024;
            int parts = (data.length + chunk - 1) / chunk;
            for (int seq = 0; seq < parts; seq++) {
                int from = seq * chunk;
                int len = Math.min(chunk, data.length - from);
                JSONObject up = uploadPart(remotePath, uploadId, seq, data, from, len);
                if (up == null || up.optInt("errno", -1) != 0) { return errDesc(up); }
            }

            /* 3. 创建文件 */
            String createBody = "path=" + URLEncoder.encode(remotePath, "UTF-8")
                    + "&size=" + data.length
                    + "&isdir=0"
                    + "&block_list=" + URLEncoder.encode("[\"" + md5 + "\"]", "UTF-8")
                    + "&uploadid=" + URLEncoder.encode(uploadId, "UTF-8")
                    + "&rtype=3";
            JSONObject create = httpPostJson(
                    "https://pan.baidu.com/rest/2.0/xpan/file?method=create&access_token="
                            + accessToken(), createBody,
                    "application/x-www-form-urlencoded");
            if (create == null) { return "网络请求失败"; }
            if (create.optInt("errno", -1) != 0) { return errDesc(create); }
            return null; // 成功
        } catch (Exception e) {
            Log.w(TAG, "upload exception", e);
            return e.getMessage();
        }
    }

    private JSONObject uploadPart(String path, String uploadId, int seq,
                                  byte[] data, int from, int len) throws Exception {
        String boundary = "----rideLog" + System.currentTimeMillis();
        String url = "https://d.pcs.baidu.com/rest/2.0/pcs/superfile2?method=upload"
                + "&access_token=" + accessToken()
                + "&type=tmp"
                + "&path=" + URLEncoder.encode(path, "UTF-8")
                + "&uploadid=" + URLEncoder.encode(uploadId, "UTF-8")
                + "&partseq=" + seq;
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        OutputStream os = conn.getOutputStream();
        StringBuilder head = new StringBuilder();
        head.append("--").append(boundary).append("\r\n");
        head.append("Content-Disposition: form-data; name=\"file\"; filename=\"chunk\"\r\n");
        head.append("Content-Type: application/octet-stream\r\n\r\n");
        os.write(head.toString().getBytes("UTF-8"));
        os.write(data, from, len);
        os.write(("\r\n--" + boundary + "--\r\n").getBytes("UTF-8"));
        os.flush();
        os.close();
        return parseJson(conn);
    }

    private String accessToken() {
        // 调用前由 ensureValidToken() 保证 token 有效性
        return prefs().getString(K_ACCESS, "");
    }

    // ------------------------------------------------------------ HTTP 工具

    private JSONObject httpGetJson(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestMethod("GET");
        return parseJson(conn);
    }

    private JSONObject httpPostJson(String urlStr, String body, String contentType) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", contentType);
        OutputStream os = conn.getOutputStream();
        os.write(body.getBytes("UTF-8"));
        os.flush();
        os.close();
        return parseJson(conn);
    }

    private JSONObject parseJson(HttpURLConnection conn) {
        try {
            int code = conn.getResponseCode();
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while (is != null && (n = is.read(buf)) > 0) { bos.write(buf, 0, n); }
            if (is != null) { is.close(); }
            String s = bos.toString("UTF-8");
            if (s.length() == 0) { return null; }
            return new JSONObject(s);
        } catch (Exception e) {
            Log.w(TAG, "parse json fail", e);
            return null;
        } finally {
            conn.disconnect();
        }
    }

    private static String errDesc(JSONObject o) {
        if (o == null) { return "网络请求失败"; }
        String oauthErr = o.optString("error", null); // OAuth 错误响应（如 invalid_grant）
        if (oauthErr != null && oauthErr.length() > 0) { return oauthErr; }
        int errno = o.optInt("errno", -1);
        String msg;
        switch (errno) {
            case 0: msg = "成功"; break;
            case 111: msg = "token 失效"; break;
            case -6: msg = "身份验证失败"; break;
            case 31064: msg = "文件不存在"; break;
            case 2: msg = "参数错误"; break;
            case 12: msg = "无权限访问"; break;
            default: msg = "错误码 " + errno; break;
        }
        return msg;
    }

    // ------------------------------------------------------------ 文件工具

    private static byte[] readFile(File f) throws Exception {
        FileInputStream fis = new FileInputStream(f);
        ByteArrayOutputStream bos = new ByteArrayOutputStream((int) f.length());
        byte[] buf = new byte[8192];
        int n;
        while ((n = fis.read(buf)) > 0) { bos.write(buf, 0, n); }
        fis.close();
        return bos.toByteArray();
    }

    private static String md5Hex(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] d = md.digest(data);
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) {
            String h = Integer.toHexString(b & 0xFF);
            if (h.length() == 1) { sb.append('0'); }
            sb.append(h);
        }
        return sb.toString();
    }
}
