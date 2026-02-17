package com.github.catvod.spider;

import android.content.Context;
import android.os.Build;
import android.text.TextUtils;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.github.catvod.spider.Init.get;

public class GoProxyManager {

    private static final ExecutorService executor = Executors.newFixedThreadPool(5);
    static final AtomicBoolean isProxyRunning = new AtomicBoolean(false);
    private static final int PROXY_PORT = 5575;
    private static final String HEALTH_CHECK_URL = "http://127.0.0.1:" + PROXY_PORT + "/health";
    private static String goProxyExecutableName = "";

    private static Timer healthCheckTimer;
    private static final Object healthCheckLock = new Object();
    private static long lastSuccessTime = 0;
    private static final long RESTART_DELAY_THRESHOLD = 10000; // 10秒阈值

    /**
     * 唯一的公共初始化入口
     * @param context 应用上下文
     */
    public static void initialize(Context context) {
        startGoProxyOnce(context);
    }

    /**
     * 启动独立的后台健康检查定时器
     * @param context 应用上下文
     */
    private static void startHealthCheck(Context context) {
        synchronized (healthCheckLock) {
            if (healthCheckTimer != null) {
                healthCheckTimer.cancel();
                SpiderDebug.log("旧的 GoProxy 健康检查定时器已停止。");
            }

            SpiderDebug.log("🚀 启动 GoProxy 后台健康检查...");
            lastSuccessTime = System.currentTimeMillis(); // 记录初始成功时间

            healthCheckTimer = new Timer("GoProxyHealthCheckTimer", true);
            healthCheckTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    try {
                        if (!isProxyHealthy()) {
                            long currentTime = System.currentTimeMillis();
                            long timeSinceLastSuccess = currentTime - lastSuccessTime;
                            
                            SpiderDebug.log("GoProxy 健康检查失败，距离上次成功时间: " + timeSinceLastSuccess + "ms");

                            if (timeSinceLastSuccess >= RESTART_DELAY_THRESHOLD) {
                                SpiderDebug.log("GoProxy 健康检查失败且距离上次成功超过 " + (RESTART_DELAY_THRESHOLD/1000) + " 秒，准备重启...");
                                if (isProxyRunning.get()) {
                                    isProxyRunning.set(false);
                                }

                                // Trigger the restart process.
                                startGoProxyOnce(context.getApplicationContext());
                            }
                        } else {
                            lastSuccessTime = System.currentTimeMillis(); // 更新最后成功时间
                            if (!isProxyRunning.get()) {
                                SpiderDebug.log("GoProxy 健康检查成功，同步状态为运行中");
                                isProxyRunning.set(true);
                            }
                        }
                    } catch (Exception e) {
                        SpiderDebug.log("❌ GoProxy 健康检查任务异常: " + e.getMessage());
                    }
                }
            }, 2000, 5000); // 2秒后开始，每5秒检查一次
        }
    }

    static void startGoProxyOnce(Context context) {
        execute(() -> {
            synchronized (isProxyRunning) {
                // 如果检查不健康，但状态仍是 running，则强制设置为 false
                if (isProxyRunning.get()) {
                    SpiderDebug.log("GoProxy 状态与健康检查不符，强制更新状态为未运行");
                    isProxyRunning.set(false);
                }

                SpiderDebug.log("GoProxy 未运行，开始启动流程...");
                try {
                    if (TextUtils.isEmpty(goProxyExecutableName)) {
                        List<String> abs = Arrays.asList(Build.SUPPORTED_ABIS);
                        goProxyExecutableName = abs.contains("arm64-v8a") ? "goProxy-arm64" : "goProxy-arm";
                    }

                    File file = new File(context.getCacheDir(), goProxyExecutableName);

                    Process exec = Runtime.getRuntime().exec("/system/bin/sh");
                    try (DataOutputStream dos = new DataOutputStream(exec.getOutputStream())) {
                        if (!file.exists()) {
                            if (!file.createNewFile()) throw new Exception("创建文件失败 " + file);

                            // 获取资源输入流
                            InputStream is = Objects.requireNonNull(get().getClass().getClassLoader()).getResourceAsStream("assets/" + goProxyExecutableName);
                            if (is == null) {
                                throw new Exception("资源文件不存在: assets/" + goProxyExecutableName);
                            }

                            try (FileOutputStream fos = new FileOutputStream(file)) {
                                byte[] buffer = new byte[8192];
                                int read;
                                while ((read = is.read(buffer)) != -1) fos.write(buffer, 0, read);
                            }
                            if (!file.setExecutable(true)) throw new Exception(goProxyExecutableName + " setExecutable is false");
                            dos.writeBytes("chmod 777 " + file.getAbsolutePath() + "\n");
                            dos.flush();
                        }

                        SpiderDebug.log("启动 " + file);
                        dos.writeBytes("kill $(ps -ef | grep '" + goProxyExecutableName + "' | grep -v grep | awk '{print $2}')\n");
                        dos.flush();
                        dos.writeBytes("nohup " + file.getAbsolutePath() + "\n");
                        dos.flush();
                        dos.writeBytes("exit\n");
                        dos.flush();
                    }

                    Thread.sleep(3000); // 等待代理有足够的时间来启动

                    if (isProxyHealthy()) {
                        SpiderDebug.log("GoProxy 启动成功！");
                        isProxyRunning.set(true);
                        // **关键逻辑**: 只有在首次确认启动成功后，才启动健康检查来监控它
                        startHealthCheck(context);
                    } else {
                        SpiderDebug.log("GoProxy 启动后健康检查失败，请检查日志。");
                        isProxyRunning.set(false);
                    }

                } catch (Exception e) {
                    // 如果在这里捕获到异常（如文件找不到、权限问题），健康检查将不会被启动
                    SpiderDebug.log("启动 GoProxy 过程中发生严重异常，已停止后续操作: " + e.getMessage());
                    isProxyRunning.set(false);
                }
            }
        });
    }

    public static synchronized boolean isProxyHealthy() {
        try {
            String response = OkHttp.string(HEALTH_CHECK_URL, 1000);
            SpiderDebug.log("GoProxy 健康检查原始响应: " + response);

            // 支持原版，检查是否为简单的健康状态字符串
            if ("ok".equalsIgnoreCase(response.trim())) {
                return true;
            }

            // 首先尝试解析为JSON对象
            try {
                JsonObject json = new Gson().fromJson(response, JsonObject.class);
                if (json != null && json.has("status")) {
                    return "healthy".equals(json.get("status").getAsString());
                }
            } catch (Exception jsonEx) {
                // JSON解析失败，继续尝试其他格式
                SpiderDebug.log("GoProxy 健康检查异常： " + jsonEx.getMessage());
            }

            return false;
        } catch (Exception e) {
            SpiderDebug.log("GoProxy 健康检查异常: " + e.getMessage());
            return false;
        }
    }

    public static void execute(Runnable runnable) {
        executor.execute(runnable);
    }

    /**
     * 检查Go代理可执行文件是否存在于assets中
     * @return true 如果资源文件存在
     */
    public static boolean isGoProxyAssetExists() {
        try {
            if (TextUtils.isEmpty(goProxyExecutableName)) {
                List<String> abs = Arrays.asList(Build.SUPPORTED_ABIS);
                goProxyExecutableName = abs.contains("arm64-v8a") ? "goProxy-arm64" : "goProxy-arm";
            }
            InputStream is = Objects.requireNonNull(get().getClass().getClassLoader()).getResourceAsStream("assets/" + goProxyExecutableName);
            if (is != null) {
                is.close();
                return true;
            }
        } catch (Exception e) {
            SpiderDebug.log("检查Go代理资源文件失败: " + e.getMessage());
        }
        return false;
    }
}