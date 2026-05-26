package com.blueocean.controller;

import com.blueocean.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

@RestController
@RequestMapping("/api/files")
public class FileController {

    private static final Logger log = LoggerFactory.getLogger(FileController.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String BASE_DIR = "C:\\Users\\46201\\Documents\\无极RPA文件处理";

    private final MainImageService mainImageService;
    private final DetailImageService detailImageService;
    private final SkuImageService skuImageService;
    private final VideoService videoService;
    private final ImageFileService imageFileService;
    private final SkuRenameService skuRenameService;

    public FileController(MainImageService mainImageService,
                          DetailImageService detailImageService,
                          SkuImageService skuImageService,
                          VideoService videoService,
                          ImageFileService imageFileService,
                          SkuRenameService skuRenameService) {
        this.mainImageService = mainImageService;
        this.detailImageService = detailImageService;
        this.skuImageService = skuImageService;
        this.videoService = videoService;
        this.imageFileService = imageFileService;
        this.skuRenameService = skuRenameService;
    }

    // ==================== File Serve / Delete ====================

    @GetMapping("/view")
    public ResponseEntity<byte[]> serveFile(@RequestParam("path") String encodedPath) throws IOException {
        Path path = resolvePath(encodedPath);
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            return ResponseEntity.notFound().build();
        }
        byte[] data = Files.readAllBytes(path);
        return ResponseEntity.ok().contentType(getContentType(path.getFileName().toString())).body(data);
    }

    @DeleteMapping("/view")
    public ResponseEntity<?> deleteFile(@RequestParam("path") String encodedPath) throws IOException {
        String rawPath = java.net.URLDecoder.decode(encodedPath, java.nio.charset.StandardCharsets.UTF_8);
        Path filePath = Paths.get(rawPath);
        if (!Files.exists(filePath)) {
            return ResponseEntity.badRequest().body("文件不存在: " + rawPath);
        }
        Files.delete(filePath);
        log.info("已删除文件: {}", filePath);
        return ResponseEntity.ok(Collections.singletonMap("message", "已删除"));
    }

    // ==================== Image Create / Replace (通用) ====================

    @PostMapping("/replace-image")
    public ResponseEntity<?> replaceImage(
            @RequestParam("targetPath") String targetPath,
            @RequestParam("file") MultipartFile file) throws IOException {
        try {
            imageFileService.replaceFile(Paths.get(targetPath), file.getBytes());
            return ResponseEntity.ok(Collections.singletonMap("message", "图片已替换"));
        } catch (IOException e) {
            return ResponseEntity.badRequest().body("替换失败: " + e.getMessage());
        }
    }

    @PostMapping("/create-image")
    public ResponseEntity<?> createImage(
            @RequestParam("targetPath") String targetPath,
            @RequestParam("file") MultipartFile file) throws IOException {
        try {
            Path path = imageFileService.createFileWithAutoNumber(Paths.get(targetPath), file.getBytes());
            return ResponseEntity.ok(Collections.singletonMap("path", path.toString()));
        } catch (IOException e) {
            return ResponseEntity.badRequest().body("创建失败: " + e.getMessage());
        }
    }

    // ==================== Video Create / Replace ====================

    @PostMapping("/create-video")
    public ResponseEntity<?> createVideo(
            @RequestParam("targetPath") String targetPath,
            @RequestParam("file") MultipartFile file) throws IOException {
        try {
            String path = videoService.createVideo(targetPath, file.getBytes());
            return ResponseEntity.ok(Collections.singletonMap("path", path));
        } catch (IOException e) {
            return ResponseEntity.badRequest().body("创建失败: " + e.getMessage());
        }
    }

    @PostMapping("/replace-video")
    public ResponseEntity<?> replaceVideo(
            @RequestParam("targetPath") String targetPath,
            @RequestParam("file") MultipartFile file) throws IOException {
        try {
            videoService.replaceVideo(targetPath, file.getBytes());
            return ResponseEntity.ok(Collections.singletonMap("message", "视频已替换"));
        } catch (IOException e) {
            return ResponseEntity.badRequest().body("替换失败: " + e.getMessage());
        }
    }

    // ==================== Main Image ====================

    @PostMapping("/reorder-main-images")
    public ResponseEntity<?> reorderMainImages(@RequestBody Map<String, Object> request) throws IOException {
        String productDir = (String) request.get("productDir");
        @SuppressWarnings("unchecked")
        List<String> orderedPaths = (List<String>) request.get("orderedPaths");
        if (productDir == null || orderedPaths == null || orderedPaths.isEmpty()) {
            return ResponseEntity.badRequest().body("缺少 productDir 或 orderedPaths");
        }
        try {
            mainImageService.reorder(productDir, orderedPaths);
            return ResponseEntity.ok(Collections.singletonMap("message", "已重排序"));
        } catch (IOException e) {
            return ResponseEntity.badRequest().body("重排序失败: " + e.getMessage());
        }
    }

    @PostMapping("/swap-main-images")
    public ResponseEntity<?> swapMainImages(@RequestBody Map<String, String> request) throws IOException {
        String filePath1 = request.get("filePath1");
        String filePath2 = request.get("filePath2");
        if (filePath1 == null || filePath2 == null) {
            return ResponseEntity.badRequest().body("缺少 filePath1 或 filePath2");
        }
        try {
            mainImageService.swap(filePath1, filePath2);
            return ResponseEntity.ok(Collections.singletonMap("message", "已交换"));
        } catch (IOException e) {
            return ResponseEntity.badRequest().body("交换失败: " + e.getMessage());
        }
    }

    // ==================== Detail Image ====================

    @PostMapping("/list-images")
    public ResponseEntity<?> listImages(@RequestBody Map<String, String> request) {
        String productDir = request.get("productDir");
        if (productDir == null) return ResponseEntity.badRequest().body("缺少 productDir");
        Path prodDir = Paths.get(productDir);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("detailImages", detailImageService.list(prodDir).stream()
                .map(p -> Collections.singletonMap("path", p))
                .toList());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/reorder-detail-images")
    public ResponseEntity<?> reorderDetailImages(@RequestBody Map<String, Object> request) throws IOException {
        String productDir = (String) request.get("productDir");
        @SuppressWarnings("unchecked")
        List<String> orderedPaths = (List<String>) request.get("orderedPaths");
        if (productDir == null || orderedPaths == null || orderedPaths.isEmpty()) {
            return ResponseEntity.badRequest().body("缺少 productDir 或 orderedPaths");
        }
        try {
            detailImageService.reorder(productDir, orderedPaths);
            return ResponseEntity.ok(Collections.singletonMap("message", "已重排序"));
        } catch (IOException e) {
            return ResponseEntity.badRequest().body("重排序失败: " + e.getMessage());
        }
    }

    // ==================== SKU Image ====================

    @PostMapping("/reorder-sku-images")
    public ResponseEntity<?> reorderSkuImages(@RequestBody Map<String, Object> request) throws IOException {
        String productDir = (String) request.get("productDir");
        @SuppressWarnings("unchecked")
        List<String> orderedPaths = (List<String>) request.get("orderedPaths");
        if (productDir == null || orderedPaths == null || orderedPaths.isEmpty()) {
            return ResponseEntity.badRequest().body("缺少 productDir 或 orderedPaths");
        }
        try {
            skuImageService.reorder(productDir, orderedPaths);
            return ResponseEntity.ok(Collections.singletonMap("message", "已重排序"));
        } catch (IOException e) {
            return ResponseEntity.badRequest().body("重排序失败: " + e.getMessage());
        }
    }

    // ==================== SKU Row Reorder ====================

    @PostMapping("/reorder-skus")
    public ResponseEntity<?> reorderSkus(@RequestBody Map<String, Object> request) throws IOException {
        String productDir = (String) request.get("productDir");
        @SuppressWarnings("unchecked")
        List<String> orderedSkuIds = (List<String>) request.get("orderedSkuIds");
        if (productDir == null || orderedSkuIds == null || orderedSkuIds.isEmpty()) {
            return ResponseEntity.badRequest().body("缺少 productDir 或 orderedSkuIds");
        }

        Path prodDir = Paths.get(productDir);
        Path jsonPath = prodDir.resolve("商品数据.json");
        if (!Files.exists(jsonPath)) {
            return ResponseEntity.badRequest().body("商品数据.json不存在");
        }

        String jsonContent = Files.readString(jsonPath);
        Map<String, Object> jsonData = mapper.readValue(jsonContent, Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> currentSkus = (List<Map<String, Object>>) jsonData.get("skus");
        if (currentSkus == null || currentSkus.isEmpty()) {
            return ResponseEntity.badRequest().body("没有SKU数据");
        }

        Map<String, Integer> skuIdToIndex = new LinkedHashMap<>();
        for (int i = 0; i < currentSkus.size(); i++) {
            skuIdToIndex.put(String.valueOf(currentSkus.get(i).get("skuId")), i);
        }

        List<Map<String, Object>> reorderedSkus = new ArrayList<>();
        for (String skuId : orderedSkuIds) {
            Integer idx = skuIdToIndex.get(skuId);
            if (idx != null) reorderedSkus.add(currentSkus.get(idx));
        }
        for (int i = 0; i < currentSkus.size(); i++) {
            String skuId = String.valueOf(currentSkus.get(i).get("skuId"));
            if (!orderedSkuIds.contains(skuId)) reorderedSkus.add(currentSkus.get(i));
        }

        // Reorder SKU image files
        skuImageService.reorder(productDir, orderedSkuIds.stream()
                .map(id -> {
                    Integer idx = skuIdToIndex.get(id);
                    return idx != null ? prodDir.resolve("SKU图").resolve("SKU图_" + String.format("%02d", idx + 1) + ".jpg").toString() : null;
                })
                .filter(Objects::nonNull)
                .filter(p -> Files.exists(Paths.get(p)))
                .toList());

        // Update image URLs
        for (int i = 0; i < reorderedSkus.size(); i++) {
            String skuImgPath = prodDir.resolve("SKU图").resolve("SKU图_" + String.format("%02d", i + 1) + ".jpg").toString();
            reorderedSkus.get(i).put("imageUrl", Files.exists(Paths.get(skuImgPath)) ? skuImgPath : "");
        }

        // Write back JSON
        jsonData.put("skus", reorderedSkus);
        Files.writeString(jsonPath, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonData));

        // Rewrite CSV
        Path csvPath = prodDir.resolve("价格表.csv");
        if (Files.exists(csvPath) && reorderedSkus.size() > 0) {
            List<String> csvLines = Files.readAllLines(csvPath);
            String header = csvLines.get(0);
            StringBuilder sb = new StringBuilder(header).append("\n");
            for (Map<String, Object> sku : reorderedSkus) {
                sb.append(formatSkuCsvLine(sku)).append("\n");
            }
            Path tmpCsv = csvPath.resolveSibling("价格表_tmp_.csv");
            Files.write(tmpCsv, sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Files.move(tmpCsv, csvPath, StandardCopyOption.REPLACE_EXISTING);
        }

        return ResponseEntity.ok(Collections.singletonMap("message", "已重排序"));
    }

    private String formatSkuCsvLine(Map<String, Object> sku) {
        String skuId = String.valueOf(sku.getOrDefault("skuId", ""));
        String specName = String.valueOf(sku.getOrDefault("specName", ""));
        double originalPrice = ((Number) sku.getOrDefault("originalPrice", 0.0)).doubleValue();
        double shippingFee = ((Number) sku.getOrDefault("shippingFee", 0.0)).doubleValue();
        double finalPrice = ((Number) sku.getOrDefault("finalPrice", 0.0)).doubleValue();
        double discountPrice = ((Number) sku.getOrDefault("discountPrice", 0.0)).doubleValue();
        double profit = ((Number) sku.getOrDefault("profit", 0.0)).doubleValue();
        int stock = ((Number) sku.getOrDefault("stock", 0)).intValue();
        return String.format("%s,%s,%.1f,%.1f,(%.1f×1.5+0.0)+%.1f×1.2,%.2f,%.2f,%.2f,%d",
                skuId, specName, originalPrice, shippingFee, originalPrice, shippingFee,
                finalPrice, discountPrice, profit, stock);
    }

    // ==================== SKU Rename ====================

    @PostMapping("/rename-sku")
    public ResponseEntity<?> renameSku(@RequestBody Map<String, Object> request) {
        try {
            log.info("收到 rename-sku 请求: {}", request);
            String productDir = (String) request.get("productDir");
            String oldName = (String) request.get("oldName");
            String newName = (String) request.get("newName");
            log.info("解析参数: productDir=[{}], oldName=[{}], newName=[{}]", productDir, oldName, newName);
            if (productDir == null || oldName == null || newName == null) {
                return ResponseEntity.badRequest().body("缺少参数");
            }
            if (oldName.equals(newName)) {
                return ResponseEntity.ok(Collections.singletonMap("message", "名称未变更"));
            }
            skuRenameService.renameSku(productDir, oldName, newName);
            return ResponseEntity.ok(Collections.singletonMap("message", "已更新"));
        } catch (Exception e) {
            log.error("rename-sku 异常: ", e);
            return ResponseEntity.badRequest().body("重命名失败: " + e.getMessage());
        }
    }

    // ==================== Product Loading ====================

    @GetMapping("/load-products")
    public ResponseEntity<?> loadProductsByDate(@RequestParam String date) {
        try {
            Path basePath = Paths.get(BASE_DIR);
            if (!Files.exists(basePath)) {
                return ResponseEntity.badRequest().body("目录不存在: " + BASE_DIR);
            }

            List<Map<String, Object>> products = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(basePath,
                    p -> Files.isDirectory(p) && p.getFileName().toString().contains(date))) {
                for (Path dayDir : stream) {
                    try (DirectoryStream<Path> catStream = Files.newDirectoryStream(dayDir, Files::isDirectory)) {
                        for (Path catDir : catStream) {
                            try (DirectoryStream<Path> prodStream = Files.newDirectoryStream(catDir,
                                    p -> Files.isDirectory(p) && !imageFileService.isKnownSubDir(p.getFileName().toString()))) {
                                for (Path prodDir : prodStream) {
                                    products.add(loadProduct(prodDir));
                                }
                            }
                        }
                    }
                }
            }
            return ResponseEntity.ok(products);
        } catch (IOException e) {
            return ResponseEntity.badRequest().body("读取目录失败: " + e.getMessage());
        }
    }

    private Map<String, Object> loadProduct(Path prodDir) throws IOException {
        Map<String, Object> product = new LinkedHashMap<>();
        product.put("productDir", prodDir.toString());
        product.put("dirTitle", prodDir.getFileName().toString());

        Path productJsonPath = prodDir.resolve("商品数据.json");
        if (Files.exists(productJsonPath)) {
            try {
                String json = Files.readString(productJsonPath);
                Map<String, Object> fullData = mapper.readValue(json, Map.class);
                product.put("scrapedTitle", fullData.get("title"));
                for (Map.Entry<String, Object> entry : fullData.entrySet()) {
                    String key = entry.getKey();
                    if (!Set.of("title", "productDir", "mainImages", "detailImages", "skuImages", "videoUrl").contains(key)) {
                        product.put(key, entry.getValue());
                    }
                }
            } catch (Exception e) {
                log.warn("读取商品数据JSON失败: {}", e.getMessage());
            }
        }

        // Image paths via services
        product.put("mainImages", mainImageService.listWithSlots(prodDir));
        product.put("detailImages", detailImageService.list(prodDir));
        product.put("skuImages", skuImageService.list(prodDir));

        // Video
        product.put("videoUrl", Optional.ofNullable(videoService.findVideo(prodDir)).orElse(""));

        // SKU data from CSV if not in JSON
        if (!product.containsKey("skus")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> attrs = (Map<String, Object>) product.get("attributes");
            product.put("skus", loadSkusFromCsv(prodDir, attrs));
        }

        // Fix SKU image URLs in skus array
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> skusList = (List<Map<String, Object>>) product.get("skus");
        if (skusList != null) {
            for (int i = 0; i < skusList.size(); i++) {
                String skuImgPath = prodDir.resolve("SKU图").resolve("SKU图_" + String.format("%02d", i + 1) + ".jpg").toString();
                skusList.get(i).put("imageUrl", Files.exists(Paths.get(skuImgPath)) ? skuImgPath : "");
            }
        }

        // Pack info
        if (!product.containsKey("packInfo")) {
            Path packPath = prodDir.resolve("包装信息.json");
            if (Files.exists(packPath)) {
                product.put("packInfo", mapper.readValue(Files.readString(packPath), List.class));
            } else {
                product.put("packInfo", Collections.emptyList());
            }
        }

        return product;
    }

    private List<Map<String, Object>> loadSkusFromCsv(Path prodDir, Map<String, Object> attributes) {
        List<Map<String, Object>> skus = new ArrayList<>();
        Path csvPath = prodDir.resolve("价格表.csv");
        if (!Files.exists(csvPath)) return skus;

        String colorSpec = attributes != null ? (String) attributes.get("颜色") : null;
        String[] colorValues = null;
        if (colorSpec != null && !colorSpec.isEmpty()) colorValues = colorSpec.split(",");

        try {
            List<String> lines = Files.readAllLines(csvPath);
            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split(",", -1);
                if (parts.length < 9) continue;

                Map<String, Object> sku = new LinkedHashMap<>();
                sku.put("skuId", parts[0].trim());
                sku.put("specName", parts[1].trim());
                if (colorValues != null && i <= colorValues.length) {
                    sku.put("detailFields", "颜色=" + colorValues[i - 1].trim());
                } else {
                    sku.put("detailFields", "");
                }
                sku.put("originalPrice", parseDouble(parts[2]));
                sku.put("shippingFee", parseDouble(parts[3]));
                sku.put("finalPrice", parseDouble(parts[5]));
                sku.put("discountPrice", parseDouble(parts[6]));
                sku.put("profit", parseDouble(parts[7]));
                sku.put("stock", parseInt(parts[8]));

                String skuImgPath = prodDir.resolve("SKU图").resolve("SKU图_" + String.format("%02d", i) + ".jpg").toString();
                sku.put("imageUrl", Files.exists(Paths.get(skuImgPath)) ? skuImgPath : "");
                skus.add(sku);
            }
        } catch (IOException e) {
            log.warn("读取价格表CSV失败: {}", e.getMessage());
        }
        return skus;
    }

    // ==================== Attribute Read/Write ====================

    @PostMapping("/read-attributes")
    public ResponseEntity<?> readAttributes(@RequestBody Map<String, String> request) {
        String productDir = request.get("productDir");
        if (productDir == null) return ResponseEntity.badRequest().body("缺少 productDir");
        Path attrPath = Paths.get(productDir, "商品属性.json");
        if (!Files.exists(attrPath)) return ResponseEntity.ok(Collections.emptyMap());
        try {
            String json = Files.readString(attrPath);
            return ResponseEntity.ok(mapper.readValue(json, Map.class));
        } catch (IOException e) {
            return ResponseEntity.badRequest().body("读取属性失败: " + e.getMessage());
        }
    }

    @PostMapping("/update-attributes")
    public ResponseEntity<?> updateAttributes(@RequestBody Map<String, Object> request) {
        String productDir = (String) request.get("productDir");
        if (productDir == null) return ResponseEntity.badRequest().body("缺少 productDir");
        @SuppressWarnings("unchecked")
        Map<String, Object> attributes = (Map<String, Object>) request.get("attributes");
        Path attrPath = Paths.get(productDir, "商品属性.json");
        try {
            Files.writeString(attrPath, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(attributes));
            return ResponseEntity.ok(Collections.singletonMap("message", "已更新"));
        } catch (IOException e) {
            return ResponseEntity.badRequest().body("更新属性失败: " + e.getMessage());
        }
    }

    @PostMapping("/update-title")
    public ResponseEntity<?> updateTitle(@RequestBody Map<String, String> request) {
        String oldDir = request.get("productDir");
        String newTitle = request.get("title");
        if (oldDir == null || newTitle == null) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("error", "缺少参数"));
        }
        Path oldPath = Paths.get(oldDir);
        if (!Files.exists(oldPath)) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("error", "目录不存在: " + oldDir));
        }
        try {
            Path newPath = oldPath.getParent().resolve(sanitizeDirName(newTitle));
            Files.move(oldPath, newPath, StandardCopyOption.ATOMIC_MOVE);
            Map<String, String> result = new LinkedHashMap<>();
            result.put("message", "已更新");
            result.put("newPath", newPath.toString());
            return ResponseEntity.ok(result);
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("error", "重命名失败: " + e.getMessage()));
        }
    }

    @PostMapping("/open-dir")
    public ResponseEntity<?> openDir(@RequestBody Map<String, String> request) {
        String dirPath = request.get("dirPath");
        if (dirPath == null) return ResponseEntity.badRequest().body(Collections.singletonMap("error", "缺少 dirPath"));
        try {
            Path path = Paths.get(dirPath);
            if (!Files.exists(path) || !Files.isDirectory(path)) {
                return ResponseEntity.badRequest().body(Collections.singletonMap("error", "目录不存在"));
            }
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) Runtime.getRuntime().exec(new String[]{"explorer", dirPath});
            else if (os.contains("mac")) Runtime.getRuntime().exec(new String[]{"open", dirPath});
            else Runtime.getRuntime().exec(new String[]{"xdg-open", dirPath});
            return ResponseEntity.ok(Collections.singletonMap("message", "已打开目录"));
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("error", "打开失败: " + e.getMessage()));
        }
    }

    // ==================== Helpers ====================

    private Path resolvePath(String encodedPath) {
        String rawPath = java.net.URLDecoder.decode(encodedPath, java.nio.charset.StandardCharsets.UTF_8);
        String normalized = rawPath.replace("/", File.separator).replace("\\\\", "\\");
        if (normalized.matches("^[A-Za-z]:[\\\\/].*")) return Paths.get(normalized);
        return Paths.get(BASE_DIR, normalized);
    }

    private MediaType getContentType(String fileName) {
        if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) return MediaType.IMAGE_JPEG;
        if (fileName.endsWith(".png")) return MediaType.IMAGE_PNG;
        if (fileName.endsWith(".gif")) return MediaType.IMAGE_GIF;
        if (fileName.endsWith(".webp")) return MediaType.valueOf("image/webp");
        if (fileName.endsWith(".mp4")) return MediaType.valueOf("video/mp4");
        if (fileName.endsWith(".json")) return MediaType.APPLICATION_JSON;
        if (fileName.endsWith(".xlsx") || fileName.endsWith(".xls")) return MediaType.valueOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    private String sanitizeDirName(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    private double parseDouble(String s) { try { return Double.parseDouble(s.trim()); } catch (Exception e) { return 0.0; } }
    private int parseInt(String s) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; } }
}
