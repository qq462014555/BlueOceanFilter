package com.blueocean.taobao.util;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class ChromeLauncher {

    public static String launch(String chromePath, int port, String debugDir, String targetUrl) throws IOException {
        launchProcess(chromePath, port, debugDir, targetUrl);
        return "Chrome 已启动，端口 " + port;
    }

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
