package com.blueocean.scraper;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ProductData {
    private String title;
    private String categoryPath;
    private String url;
    private String layout;
    private List<String> mainImages = new ArrayList<>();
    private List<String> detailImages = new ArrayList<>();
    private List<String> skuImages = new ArrayList<>();
    private List<SkuData> skus = new ArrayList<>();
    private Map<String, String> attributes = new LinkedHashMap<>();
    private List<Map<String, String>> packInfo = new ArrayList<>();
    private double shippingFee;
    private String productDir;
    private String videoUrl;

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDirTitle() { return productDir != null ? Paths.get(productDir).getFileName().toString() : null; }
    public String getCategoryPath() { return categoryPath; }
    public void setCategoryPath(String categoryPath) { this.categoryPath = categoryPath; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getLayout() { return layout; }
    public void setLayout(String layout) { this.layout = layout; }
    public List<String> getMainImages() { return mainImages; }
    public void setMainImages(List<String> mainImages) { this.mainImages = mainImages; }
    public List<String> getDetailImages() { return detailImages; }
    public void setDetailImages(List<String> detailImages) { this.detailImages = detailImages; }
    public List<String> getSkuImages() { return skuImages; }
    public void setSkuImages(List<String> skuImages) { this.skuImages = skuImages; }
    public List<SkuData> getSkus() { return skus; }
    public void setSkus(List<SkuData> skus) { this.skus = skus; }
    public Map<String, String> getAttributes() { return attributes; }
    public void setAttributes(Map<String, String> attributes) { this.attributes = attributes; }
    public List<Map<String, String>> getPackInfo() { return packInfo; }
    public void setPackInfo(List<Map<String, String>> packInfo) { this.packInfo = packInfo; }
    public double getShippingFee() { return shippingFee; }
    public void setShippingFee(double shippingFee) { this.shippingFee = shippingFee; }
    public String getProductDir() { return productDir; }
    public void setProductDir(String productDir) { this.productDir = productDir; }
    public String getVideoUrl() { return videoUrl; }
    public void setVideoUrl(String videoUrl) { this.videoUrl = videoUrl; }
}
