package com.blueocean.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private String dashscopeApiKey;
    private int batchSize = 150;
    private int retryTimes = 3;
    private long sleepMs = 1000;
    private String outputDir = "output";
    private String paddleocrEndpoint = "http://127.0.0.1:8866";
    private String chromePath = "C:\\Users\\46201\\AppData\\Local\\Google\\Chrome\\Application\\chrome.exe";
    private Map<Integer, String> chromeDebug = new HashMap<>();
    private String taobaoTargetUrl = "https://sxkc.wusetech.com/";

    public String getDashscopeApiKey() { return dashscopeApiKey; }
    public void setDashscopeApiKey(String dashscopeApiKey) { this.dashscopeApiKey = dashscopeApiKey; }

    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }

    public int getRetryTimes() { return retryTimes; }
    public void setRetryTimes(int retryTimes) { this.retryTimes = retryTimes; }

    public long getSleepMs() { return sleepMs; }
    public void setSleepMs(long sleepMs) { this.sleepMs = sleepMs; }

    public String getOutputDir() { return outputDir; }
    public void setOutputDir(String outputDir) { this.outputDir = outputDir; }

    public String getPaddleocrEndpoint() { return paddleocrEndpoint; }
    public void setPaddleocrEndpoint(String paddleocrEndpoint) { this.paddleocrEndpoint = paddleocrEndpoint; }

    public String getChromePath() { return chromePath; }
    public void setChromePath(String chromePath) { this.chromePath = chromePath; }

    public Map<Integer, String> getChromeDebug() { return chromeDebug; }
    public void setChromeDebug(Map<Integer, String> chromeDebug) { this.chromeDebug = chromeDebug; }

    public String getTaobaoTargetUrl() { return taobaoTargetUrl; }
    public void setTaobaoTargetUrl(String taobaoTargetUrl) { this.taobaoTargetUrl = taobaoTargetUrl; }
}
