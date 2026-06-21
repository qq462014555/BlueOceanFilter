package com.blueocean.service;

import com.blueocean.config.ChromeDebugConfig;
import com.blueocean.util.ChromeLauncher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 定时检测 9224 端口 Chrome 调试浏览器是否在线，离线则自动启动
 */
@Slf4j
@Service
public class ChromePortMonitor {

    private static final int TARGET_PORT = 9224;

    private final ChromeDebugConfig chromeDebugConfig;

    private volatile boolean launching = false;

    public ChromePortMonitor(ChromeDebugConfig chromeDebugConfig) {
        this.chromeDebugConfig = chromeDebugConfig;
    }

    //@Scheduled(fixedRate = 60_000)
    public void checkAndLaunch() {
        if (isPortListening(TARGET_PORT)) {
            return;
        }

        log.info("端口 {} 未监听，准备启动 Chrome...", TARGET_PORT);

        if (launching) {
            log.info("Chrome 正在启动中，跳过本轮");
            return;
        }
        launching = true;

        try {
            String debugDir = chromeDebugConfig.getDebugDir(TARGET_PORT);
            if (debugDir == null) {
                log.error("未配置端口 {} 的数据目录", TARGET_PORT);
                return;
            }
            String targetUrl = chromeDebugConfig.getTargetUrl(TARGET_PORT);
            String msg = ChromeLauncher.launch(chromeDebugConfig.getChromePath(), TARGET_PORT, debugDir, targetUrl);
            log.info("Chrome 启动成功: {}", msg);
        } catch (IOException e) {
            log.error("启动 Chrome 失败 (port={})", TARGET_PORT, e);
        } finally {
            launching = false;
        }
    }

    private boolean isPortListening(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 2000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}