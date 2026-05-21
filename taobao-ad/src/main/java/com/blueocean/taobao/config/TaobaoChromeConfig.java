package com.blueocean.taobao.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class TaobaoChromeConfig {

    @Value("${app.chrome-port:9224}")
    private int chromePort;

    @Value("${app.taobao-target-url:https://sxkc.wusetech.com/}")
    private String targetUrl;

    public int getChromePort() { return chromePort; }
    public String getTargetUrl() { return targetUrl; }
}
