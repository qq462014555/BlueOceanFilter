package com.blueocean.service;

import java.util.List;

/**
 * Image2 图生成策略接口
 */
public interface Image2Provider {
    List<String> generate(String prompt, int n, String size, List<String> refImages) throws Exception;
}
