package com.blueocean;

/**
 * 测试入口：右键 Run 'TestRunner.main()'
 * 前提：Spring Boot 服务已启动（8080 端口）
 */
public class TestRunner {

    public static void main(String[] args) throws Exception {
      //  填写sku1();
        //填写价格库存2();
        上传sku图片();
    }


    public static void 填写sku1() throws Exception {
        test("填写sku", "http://localhost:8080/api/sku-fill/fill-to-page",
                "{\"productDir\":\"C:\\\\Users\\\\46201\\\\Documents\\\\无极RPA文件处理\\\\2026年05月24日14时42分_1688链接\\\\玩具_童车_益智_积木_模型_手工制作_创意DIY_其他手工制作\\\\可穿戴纸箱机器人儿童手工 DIY 拼装涂色机甲铠甲趣味益智玩具\"}");

    }
    public static void 填写价格库存2() throws Exception {
        test("填写价格库存", "http://localhost:8080/api/sku-fill/test-fill-price-stock",
                "{\"productDir\":\"C:\\\\Users\\\\46201\\\\Documents\\\\无极RPA文件处理\\\\2026年05月24日14时42分_1688链接\\\\玩具_童车_益智_积木_模型_手工制作_创意DIY_其他手工制作\\\\可穿戴纸箱机器人儿童手工 DIY 拼装涂色机甲铠甲趣味益智玩具\"}");

    }

    public static void 上传sku图片() throws Exception {
        test("上传sku图片", "http://localhost:8080/api/sku-fill/test-fill-sku-image",
                "{\"productDir\":\"C:\\\\Users\\\\46201\\\\Documents\\\\无极RPA文件处理\\\\2026年05月24日14时42分_1688链接\\\\玩具_童车_益智_积木_模型_手工制作_创意DIY_其他手工制作\\\\可穿戴纸箱机器人儿童手工 DIY 拼装涂色机甲铠甲趣味益智玩具\"}");

    }


    public static void testExtract() throws Exception {
        test("提取SKU属性", "http://localhost:8080/api/sku-fill/test-extract", "{}");
    }

    public static void testFill() throws Exception {
        test("AI补充SKU", "http://localhost:8080/api/sku-fill/fill",
                "{\"title\":\"\",\"pageLevels\":[],\"forceRefetch\":false}");
    }

    private static void test(String name, String url, String body) throws Exception {
        System.out.println("=== " + name + " ===");
        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                .build();
        java.net.http.HttpResponse<String> response = client.send(request,
                java.net.http.HttpResponse.BodyHandlers.ofString());
        System.out.println("状态码: " + response.statusCode());
        System.out.println("响应: " + response.body());
    }
}
