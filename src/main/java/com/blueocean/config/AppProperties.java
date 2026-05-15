package com.blueocean.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private String dashscopeApiKey;
    private int batchSize = 150;
    private int retryTimes = 3;
    private long sleepMs = 1000;
    private String outputDir = "output";
    private String paddleocrEndpoint = "http://127.0.0.1:8866";

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
}
