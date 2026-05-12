package com.blueocean.scraper;

import java.util.ArrayList;
import java.util.List;

public class ProductData {
    private String title;
    private String categoryPath;
    private String url;
    private String layout;
    private List<String> mainImages = new ArrayList<>();
    private List<String> detailImages = new ArrayList<>();
    private List<SkuData> skus = new ArrayList<>();
    private double shippingFee;
    private String productDir;

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
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
    public List<SkuData> getSkus() { return skus; }
    public void setSkus(List<SkuData> skus) { this.skus = skus; }
    public double getShippingFee() { return shippingFee; }
    public void setShippingFee(double shippingFee) { this.shippingFee = shippingFee; }
    public String getProductDir() { return productDir; }
    public void setProductDir(String productDir) { this.productDir = productDir; }
}
