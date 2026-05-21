package com.blueocean.util;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * Chrome 调试浏览器启动工具
 */
@Slf4j
public class ChromeLauncher {

    /**
     * 启动 Chrome 调试浏览器
     * @param chromePath Chrome 可执行文件路径
     * @param port 远程调试端口
     * @param debugDir 用户数据目录
     * @param targetUrl 启动后自动打开的网址，null 则不打开
     * @return 启动结果信息
     */
    public static String launch(String chromePath, int port, String debugDir, String targetUrl) throws IOException {
        launchProcess(chromePath, port, debugDir, targetUrl);
        if (targetUrl != null && !targetUrl.isEmpty()) {
            try (Playwright playwright = Playwright.create()) {
                waitForPort(port, 15);
                Browser browser = playwright.chromium().connectOverCDP("http://127.0.0.1:" + port);
                BrowserContext context = browser.contexts().get(0);

                // 关闭默认空白 tab（如果有）
                List<Page> pages = context.pages();
                if (pages.size() > 1) {
                    for (int i = pages.size() - 1; i > 0; i--) {
                        pages.get(i).close();
                    }
                    log.info("已关闭 {} 个多余 tab", pages.size() - 1);
                }

                // 用第一个 tab 打开目标网址
                Page firstPage = context.pages().get(0);
                firstPage.navigate(targetUrl);
                log.info("已通过 Playwright 打开网址: {}", targetUrl);

                browser.close();
            } catch (Exception e) {
                log.warn("通过 Playwright 打开网址失败: {}，请手动访问", targetUrl, e);
            }
        }
        return "Chrome 调试浏览器已启动，端口 " + port + "，数据目录: " + debugDir;
    }

    /**
     * 启动 Chrome 并返回 Process，用于后续关闭
     */
    public static Process launchProcess(String chromePath, int port, String debugDir, String targetUrl) throws IOException {
        File dir = new File(debugDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        List<String> args = new ArrayList<>();
        args.add(chromePath);
        args.add("--remote-debugging-port=" + port);
        args.add("--user-data-dir=" + debugDir);
        args.add("--no-first-run");
        // Windows 上用 cmd start 启动，让 Chrome 成为独立进程组，不随 JVM 退出
        if (System.getProperty("os.name").toLowerCase().contains("windows")) {
            List<String> cmdArgs = new ArrayList<>();
            cmdArgs.add("cmd.exe");
            cmdArgs.add("/c");
            cmdArgs.add("start");
            cmdArgs.add("\"\"");
            cmdArgs.addAll(args);
            return new ProcessBuilder(cmdArgs).start();
        }
        return new ProcessBuilder(args).start();
    }

    /**
     * 等待调试端口就绪
     */
    private static void waitForPort(int port, int maxRetries) throws InterruptedException {
        for (int i = 0; i < maxRetries; i++) {
            try (Socket socket = new Socket()) {
                socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 1000);
                return;
            } catch (IOException e) {
                Thread.sleep(500);
            }
        }
    }
}
