package com.blueocean.scraper;

import java.util.Objects;

public class LinkEntry {
    private String categoryPath;
    private String url;
    private String title;

    public LinkEntry(String categoryPath, String url) {
        this.categoryPath = categoryPath;
        this.url = url;
    }

    public String getCategoryPath() { return categoryPath; }
    public String getUrl() { return url; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    @Override
    public String toString() {
        return categoryPath + " -> " + url;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LinkEntry linkEntry = (LinkEntry) o;
        return Objects.equals(categoryPath, linkEntry.categoryPath) && Objects.equals(url, linkEntry.url);
    }

    @Override
    public int hashCode() {
        return Objects.hash(categoryPath, url);
    }
}
