package com.blueocean.config;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Chrome 调试浏览器配置
 */
@Component
public class ChromeDebugConfig {

    private final AppProperties props;

    public ChromeDebugConfig(AppProperties props) {
        this.props = props;
    }

    public String getChromePath() {
        return props.getChromePath();
    }

    public String getDebugDir(int port) {
        return props.getChromeDebug().get(port);
    }

    /**
     * 根据端口返回启动后自动打开的网址
     */
    public String getTargetUrl(int port) {
        return switch (port) {
            case 9224 -> props.getTaobaoTargetUrl();
            default -> null;
        };
    }
}
