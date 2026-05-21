package com.blueocean.util;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
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
        if (targetUrl != null && !targetUrl.isEmpty()) {
            args.add(targetUrl);
        }
        return new ProcessBuilder(args).start();
    }
}
