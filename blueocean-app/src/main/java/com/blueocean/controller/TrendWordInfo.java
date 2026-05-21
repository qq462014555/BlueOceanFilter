package com.blueocean.controller;

public class TrendWordInfo {
    public String word;
    public String burstMonths;
    public String layoutMonths;
    public String remark;
    public int usageCount = 1;

    public TrendWordInfo(String word, String burstMonths, String layoutMonths, String remark) {
        this.word = word;
        this.burstMonths = burstMonths;
        this.layoutMonths = layoutMonths;
        this.remark = remark;
    }
}
