package com.blueocean.entity;

public class KeywordResult {
    private String word;
    private boolean keep;
    private String reason;

    public KeywordResult() {}

    public KeywordResult(String word, boolean keep, String reason) {
        this.word = word;
        this.keep = keep;
        this.reason = reason;
    }

    public String getWord() { return word; }
    public void setWord(String word) { this.word = word; }

    public boolean isKeep() { return keep; }
    public void setKeep(boolean keep) { this.keep = keep; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    @Override
    public String toString() {
        return "KeywordResult{word='" + word + "', keep=" + keep + ", reason='" + reason + "'}";
    }
}
