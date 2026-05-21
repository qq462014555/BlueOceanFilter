package com.blueocean.controller;

import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

@RestController
@RequestMapping("/api/openclaw")
@Slf4j
public class OpenClawController {

    private static Process openclawProcess = null;
    private static final StringBuilder logBuffer = new StringBuilder();
    private static final Object lock = new Object();
    private static final int OPENCLAW_PORT = 18789;

    private static final String WSL = "C:\\Windows\\System32\\wsl.exe";
    private static volatile boolean wasRunning = false;

    @PostConstruct
    public void init() {
        wasRunning = checkPortOpen();
        String time = new SimpleDateFormat("HH:mm:ss").format(new Date());
        synchronized (lock) {
            logBuffer.append("[").append(time).append("] [系统] OpenClaw 监控已启动，当前状态: ")
                     .append(wasRunning ? "运行中" : "未运行").append("\n");
        }
    }

    // 每10秒自动监控OpenClaw状态
    @Scheduled(fixedRate = 10000)
    public void monitorStatus() {
        boolean nowRunning = checkPortOpen();
        String time = new SimpleDateFormat("HH:mm:ss").format(new Date());

        // 检测状态变化
        if (!wasRunning && nowRunning) {
            synchronized (lock) {
                logBuffer.append("[").append(time).append("] [监控] OpenClaw 已自动启动，端口 18789 已监听\n");
            }
            log.info("[OpenClaw Monitor] started");
        } else if (wasRunning && !nowRunning) {
            synchronized (lock) {
                logBuffer.append("[").append(time).append("] [监控] OpenClaw 已停止\n");
            }
            log.info("[OpenClaw Monitor] stopped");
        }

        wasRunning = nowRunning;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> result = new HashMap<>();
        boolean running = checkPortOpen();

        if (openclawProcess != null) {
            try {
                openclawProcess.exitValue();
            } catch (IllegalThreadStateException e) {
                running = true;
            }
        }

        result.put("running", running);
        String logText = "";
        synchronized (lock) {
            if (!logBuffer.isEmpty()) {
                int start = Math.max(0, logBuffer.length() - 5000);
                logText = logBuffer.substring(start);
            }
        }
        result.put("log", logText);
        result.put("pid", openclawProcess != null ? openclawProcess.pid() : null);
        return result;
    }

    private boolean checkPortOpen() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", OPENCLAW_PORT), 1000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @PostMapping("/start")
    public Map<String, Object> start() {
        Map<String, Object> result = new HashMap<>();

        if (checkPortOpen()) {
            result.put("error", "OpenClaw 已在运行中（端口 18789 已监听）");
            return result;
        }

        try {
            synchronized (lock) {
                logBuffer.setLength(0);
                logBuffer.append("[系统] 正在启动 OpenClaw...\n");
                logBuffer.append("[系统] 命令: openclaw gateway run --allow-unconfigured\n");
            }

            // 使用 bash -i 加载交互式 shell（加载 .bashrc 中的 nvm）
            ProcessBuilder pb = new ProcessBuilder(WSL, "bash", "-i", "-c", "openclaw gateway run --allow-unconfigured &");
            pb.redirectErrorStream(true);
            openclawProcess = pb.start();

            // 等待启动进程退出（它后台化后就会退出）
            pb.redirectErrorStream(true);
            openclawProcess.waitFor(30, TimeUnit.SECONDS);

            synchronized (lock) {
                logBuffer.append("[系统] 等待服务启动...\n");
            }
            Thread.sleep(5000);

            if (checkPortOpen()) {
                synchronized (lock) {
                    logBuffer.append("[系统] 启动成功，端口 18789 已监听\n");
                }
            } else {
                ProcessBuilder readLog = new ProcessBuilder(WSL, "bash", "-c", "cat /tmp/openclaw-gateway.log 2>/dev/null");
                readLog.redirectErrorStream(true);
                Process logProc = readLog.start();
                logProc.waitFor(5, TimeUnit.SECONDS);
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(logProc.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        synchronized (lock) {
                            logBuffer.append(line).append("\n");
                        }
                    }
                }
            }

            result.put("success", true);
            result.put("message", "已启动 OpenClaw");
        } catch (Exception e) {
            log.error("OpenClaw start failed", e);
            result.put("error", "启动失败: " + e.getMessage());
            synchronized (lock) {
                logBuffer.append("错误: ").append(e.getMessage()).append("\n");
            }
        }
        return result;
    }

    @PostMapping("/stop")
    public Map<String, Object> stop() {
        Map<String, Object> result = new HashMap<>();

        if (!checkPortOpen()) {
            result.put("error", "OpenClaw 未在运行");
            return result;
        }

        try {
            synchronized (lock) {
                logBuffer.append("[系统] 正在停止 OpenClaw 服务...\n");
            }

            ProcessBuilder stopPb = new ProcessBuilder(WSL, "bash", "-i", "-c", "pkill -f 'openclaw/dist/index.js' || true");
            stopPb.redirectErrorStream(true);
            Process stopProcess = stopPb.start();
            stopProcess.waitFor(10, TimeUnit.SECONDS);

            synchronized (lock) {
                logBuffer.append("[系统] 已停止 OpenClaw\n");
            }

            Thread.sleep(1000);
            boolean stillRunning = checkPortOpen();
            synchronized (lock) {
                logBuffer.append("[系统] 端口状态: ").append(stillRunning ? "仍在运行" : "已关闭").append("\n");
            }

            result.put("success", true);
            result.put("message", "已停止 OpenClaw");
        } catch (Exception e) {
            log.error("OpenClaw stop failed", e);
            result.put("error", "停止失败: " + e.getMessage());
            synchronized (lock) {
                logBuffer.append("错误: ").append(e.getMessage()).append("\n");
            }
        }
        return result;
    }
}
