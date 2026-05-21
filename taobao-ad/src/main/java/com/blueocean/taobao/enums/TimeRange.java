package com.blueocean.taobao.enums;

/**
 * 计划管理页面的时间范围选项
 */
public enum TimeRange {
    TODAY("今日"),
    YESTERDAY("昨日"),
    PAST_7_DAYS("过去7天"),
    PAST_30_DAYS("过去30天"),
    PAST_90_DAYS("过去90天");

    private final String buttonText;

    TimeRange(String buttonText) {
        this.buttonText = buttonText;
    }

    public String getButtonText() {
        return buttonText;
    }
}