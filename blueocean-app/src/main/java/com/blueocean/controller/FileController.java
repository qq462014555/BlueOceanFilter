package com.blueocean.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
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

    // Base directory for all product files
    private static final String BASE_DIR = "C:\\Users\\46201\\Documents\\无极RPA文件处理";

    /**
     * Serve any file from the RPA directory via URL-encoded path
     * GET /api/files/view?path=URL_ENCODED_PATH
     */
    @GetMapping("/view")
    public ResponseEntity<byte[]> serveFile(@RequestParam("path") String encodedPath) throws IOException {
        String rawPath = java.net.URLDecoder.decode(encodedPath, java.nio.charset.StandardCharsets.UTF_8);
        Path path = resolvePath(rawPath);

        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            return ResponseEntity.notFound().build();
        }

        String fileName = path.getFileName().toString().toLowerCase();
        MediaType contentType = getContentType(fileName);

        byte[] data = Files.readAllBytes(path);
        return ResponseEntity.ok()
                .contentType(contentType)
                .body(data);
    }

    /**
     * Delete a file via URL-encoded path
     */
    @DeleteMapping("/view")
    public ResponseEntity<?> deleteFile(@RequestParam("path") String encodedPath) throws IOException {
        String rawPath = java.net.URLDecoder.decode(encodedPath, java.nio.charset.StandardCharsets.UTF_8);
        Path filePath = resolvePath(rawPath);

        if (!Files.exists(filePath)) {
            return ResponseEntity.badRequest().body("文件不存在: " + rawPath);
        }

        Files.delete(filePath);
        log.info("已删除文件: {}", filePath);
        return ResponseEntity.ok(Collections.singletonMap("message", "已删除"));
    }

    /**
     * Read product attributes JSON
     */
    @PostMapping("/read-attributes")
    public ResponseEntity<?> readAttributes(@RequestBody Map<String, String> request) {
        String productDir = request.get("productDir");
        if (productDir == null) {
            return ResponseEntity.badRequest().body("缺少 productDir");
        }

        Path attrPath = Paths.get(productDir, "商品属性.json");
        if (!Files.exists(attrPath)) {
            return ResponseEntity.ok(Collections.emptyMap());
        }

        try {
            String json = Files.readString(attrPath);
            Map<String, Object> attrs = mapper.readValue(json, Map.class);
            return ResponseEntity.ok(attrs);
        } catch (IOException e) {
            return ResponseEntity.badRequest().body("读取属性失败: " + e.getMessage());
        }
    }

    /**
     * Update product attributes JSON
     */
    @PostMapping("/update-attributes")
    public ResponseEntity<?> updateAttributes(@RequestBody Map<String, Object> request) {
        String productDir = (String) request.get("productDir");
        if (productDir == null) {
            return ResponseEntity.badRequest().body("缺少 productDir");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> attributes = (Map<String, Object>) request.get("attributes");

        Path attrPath = Paths.get(productDir, "商品属性.json");
        try {
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(attributes);
            Files.writeString(attrPath, json, java.nio.charset.StandardCharsets.UTF_8);
            log.info("已更新属性: {}", attrPath);
            return ResponseEntity.ok(Collections.singletonMap("message", "已更新"));
        } catch (IOException e) {
            return ResponseEntity.badRequest().body("更新属性失败: " + e.getMessage());
        }
    }

    /**
     * Update product title (rename directory)
     */
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

        Path parent = oldPath.getParent();
        Path newPath = parent.resolve(sanitizeDirName(newTitle));

        try {
            Files.move(oldPath, newPath, StandardCopyOption.ATOMIC_MOVE);
            log.info("目录已重命名: {} -> {}", oldPath, newPath);
            Map<String, String> result = new LinkedHashMap<>();
            result.put("message", "已更新");
            result.put("newPath", newPath.toString());
            return ResponseEntity.ok(result);
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("error", "重命名失败: " + e.getMessage()));
        }
    }

    /**
     * Open a directory in the system file explorer
     */
    @PostMapping("/open-dir")
    public ResponseEntity<?> openDir(@RequestBody Map<String, String> request) {
        String dirPath = request.get("dirPath");
        if (dirPath == null) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("error", "缺少 dirPath"));
        }
        try {
            Path path = Paths.get(dirPath);
            if (!Files.exists(path) || !Files.isDirectory(path)) {
                return ResponseEntity.badRequest().body(Collections.singletonMap("error", "目录不存在: " + dirPath));
            }
            // Open in file explorer (cross-platform)
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                Runtime.getRuntime().exec(new String[]{"explorer", dirPath});
            } else if (os.contains("mac")) {
                Runtime.getRuntime().exec(new String[]{"open", dirPath});
            } else {
                Runtime.getRuntime().exec(new String[]{"xdg-open", dirPath});
            }
            return ResponseEntity.ok(Collections.singletonMap("message", "已打开目录"));
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("error", "打开失败: " + e.getMessage()));
        }
    }

    /**
     * List all images in a product directory (main images, detail images)
     */
    @PostMapping("/list-images")
    public ResponseEntity<?> listImages(@RequestBody Map<String, String> request) {
        String productDir = request.get("productDir");
        if (productDir == null) {
            return ResponseEntity.badRequest().body("缺少 productDir");
        }

        Map<String, List<Map<String, String>>> result = new LinkedHashMap<>();
        result.put("mainImages", listImagesInDir(productDir, "主图"));
        result.put("detailImages", listImagesInDir(productDir, "详情图"));
        result.put("skuImages", listImagesInDir(productDir, "SKU"));

        return ResponseEntity.ok(result);
    }

    private List<Map<String, String>> listImagesInDir(String productDir, String subDir) {
        List<Map<String, String>> images = new ArrayList<>();
        Path dir = Paths.get(productDir, subDir);
        if (!Files.exists(dir)) return images;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir,
                p -> {
                    String name = p.getFileName().toString().toLowerCase();
                    return name.endsWith(".jpg") || name.endsWith(".png") || name.endsWith(".jpeg") || name.endsWith(".webp");
                })) {
            for (Path p : stream) {
                Map<String, String> img = new LinkedHashMap<>();
                img.put("name", p.getFileName().toString());
                img.put("path", p.toString());
                img.put("relativePath", p.toString().replace(BASE_DIR + File.separator, ""));
                images.add(img);
            }
            images.sort(Comparator.comparing(m -> m.get("name")));
        } catch (IOException e) {
            log.warn("读取目录 {} 失败: {}", subDir, e.getMessage());
        }
        return images;
    }

    /**
     * Replace an image: upload a new image, overwrite the existing file (filename stays the same)
     * POST /api/files/replace-image
     * { targetPath: "...", base64: "data:image/jpeg;base64,..." }
     * or multipart file upload: POST /api/files/replace-image with targetPath as form field + file
     */
    @PostMapping("/replace-image")
    public ResponseEntity<?> replaceImage(
            @RequestParam("targetPath") String targetPath,
            @RequestParam("file") MultipartFile file) throws IOException {

        Path filePath = Paths.get(targetPath);
        if (!Files.exists(filePath)) {
            return ResponseEntity.badRequest().body("目标文件不存在: " + targetPath);
        }

        byte[] bytes = file.getBytes();
        long oldSize = Files.size(filePath);
        log.info("替换图片: targetPath={}, 上传大小={} bytes, 原大小={} bytes",
                filePath, bytes.length, oldSize);

        // Use FileOutputStream to ensure content is flushed to disk on Windows
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(filePath.toFile())) {
            fos.write(bytes);
            fos.flush();
            fos.getFD().sync(); // force sync to disk
        }

        long newSize = Files.size(filePath);
        log.info("替换完成: {} bytes", newSize);
        return ResponseEntity.ok(Collections.singletonMap("message", "图片已替换 (" + newSize + " bytes)"));
    }

    /**
     * Replace an image via multipart upload
     * POST /api/files/replace-image-base64
     * form: targetPath=... + file
     */
    @PostMapping("/replace-image-base64")
    public ResponseEntity<?> replaceImageBase64(
            @RequestParam("targetPath") String targetPath,
            @RequestParam("file") MultipartFile file) throws IOException {

        Path filePath = Paths.get(targetPath);
        if (!Files.exists(filePath)) {
            return ResponseEntity.badRequest().body("目标文件不存在: " + targetPath);
        }

        Files.write(filePath, file.getBytes());
        log.info("已替换图片(multipart): {}", filePath);
        return ResponseEntity.ok(Collections.singletonMap("message", "图片已替换"));
    }

    /**
     * Upload a new image to a product subdirectory
     */
    @PostMapping("/upload-image")
    public ResponseEntity<?> uploadImage(
            @RequestParam("productDir") String productDir,
            @RequestParam("subDir") String subDir,
            @RequestParam("file") MultipartFile file) throws IOException {

        Path dir = Paths.get(productDir, subDir);
        Files.createDirectories(dir);

        Path filePath = dir.resolve(file.getOriginalFilename());
        Files.write(filePath, file.getBytes());
        log.info("已上传图片: {}", filePath);
        return ResponseEntity.ok(Collections.singletonMap("path", filePath.toString()));
    }

    /**
     * Create a new image at a specific path via multipart upload
     * POST /api/files/create-image
     * form: targetPath=... + file
     */
    @PostMapping("/create-image")
    public ResponseEntity<?> createImage(
            @RequestParam("targetPath") String targetPath,
            @RequestParam("file") MultipartFile file) throws IOException {

        Path filePath = Paths.get(targetPath);
        Files.createDirectories(filePath.getParent());

        byte[] bytes = file.getBytes();
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(filePath.toFile())) {
            fos.write(bytes);
            fos.flush();
        }
        log.info("已创建图片: {} ({} bytes)", filePath, bytes.length);
        return ResponseEntity.ok(Collections.singletonMap("path", filePath.toString()));
    }

    /**
     * Reorder detail images by renaming files to sequential names based on new order.
     * POST /api/files/reorder-detail-images
     * { productDir: "...", orderedPaths: ["path1", "path2", ...] }
     */
    @PostMapping("/reorder-detail-images")
    public ResponseEntity<?> reorderDetailImages(@RequestBody Map<String, Object> request) throws IOException {
        String productDir = (String) request.get("productDir");
        @SuppressWarnings("unchecked")
        List<String> orderedPaths = (List<String>) request.get("orderedPaths");
        if (productDir == null || orderedPaths == null || orderedPaths.isEmpty()) {
            return ResponseEntity.badRequest().body("缺少 productDir 或 orderedPaths");
        }

        Path detailDir = Paths.get(productDir, "详情图");
        if (!Files.exists(detailDir)) {
            return ResponseEntity.badRequest().body("详情图目录不存在: " + productDir);
        }

        // Two-pass rename to avoid collisions
        // Pass 1: rename to temporary names
        List<Path> tempPaths = new ArrayList<>();
        for (int i = 0; i < orderedPaths.size(); i++) {
            Path oldPath = Paths.get(orderedPaths.get(i));
            if (!Files.exists(oldPath)) continue;
            String ext = "";
            String name = oldPath.getFileName().toString();
            int dotIdx = name.lastIndexOf('.');
            if (dotIdx >= 0) ext = name.substring(dotIdx);
            Path tempPath = oldPath.resolveSibling("__reorder_tmp_" + i + ext);
            Files.move(oldPath, tempPath, StandardCopyOption.ATOMIC_MOVE);
            tempPaths.add(tempPath);
        }

        // Pass 2: rename to final sequential names
        for (int i = 0; i < tempPaths.size(); i++) {
            Path tempPath = tempPaths.get(i);
            String ext = "";
            String name = tempPath.getFileName().toString();
            int dotIdx = name.lastIndexOf('.');
            if (dotIdx >= 0) ext = name.substring(dotIdx);
            String newName = "详情图_" + String.format("%02d", i + 1) + ext;
            Path newPath = tempPath.resolveSibling(newName);
            Files.move(tempPath, newPath, StandardCopyOption.ATOMIC_MOVE);
        }

        log.info("已重排序详情图: {} 个文件", tempPaths.size());
        return ResponseEntity.ok(Collections.singletonMap("message", "已重排序"));
    }

    /**
     * Load products by date from RPA directory
     */
    @GetMapping("/load-products")
    public ResponseEntity<?> loadProductsByDate(@RequestParam String date) {
        try {
            String linkDir = BASE_DIR;

            Path basePath = Paths.get(linkDir);
            if (!Files.exists(basePath)) {
                return ResponseEntity.badRequest().body("目录不存在: " + linkDir);
            }

            List<Map<String, Object>> products = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(basePath,
                    p -> Files.isDirectory(p) && p.getFileName().toString().contains(date))) {
                for (Path dayDir : stream) {
                    try (DirectoryStream<Path> catStream = Files.newDirectoryStream(dayDir, Files::isDirectory)) {
                        for (Path catDir : catStream) {
                            try (DirectoryStream<Path> prodStream = Files.newDirectoryStream(catDir, Files::isDirectory)) {
                                for (Path prodDir : prodStream) {
                                    Map<String, Object> product = new LinkedHashMap<>();
                                    product.put("productDir", prodDir.toString());
                                    product.put("dirTitle", prodDir.getFileName().toString());

                                    // Try to load full product JSON first
                                    Path productJsonPath = prodDir.resolve("商品数据.json");
                                    boolean hasFullJson = Files.exists(productJsonPath);
                                    if (hasFullJson) {
                                        try {
                                            String json = Files.readString(productJsonPath);
                                            Map<String, Object> fullData = mapper.readValue(json, Map.class);
                                            // scrapedTitle from JSON, dirTitle always from directory name
                                            product.put("scrapedTitle", fullData.get("title"));
                                            product.put("dirTitle", prodDir.getFileName().toString());
                                            // Merge remaining fields except title and image paths
                                            for (Map.Entry<String, Object> entry : fullData.entrySet()) {
                                                String key = entry.getKey();
                                                if (!"title".equals(key) && !"productDir".equals(key) && !"mainImages".equals(key) && !"detailImages".equals(key) && !"skuImages".equals(key) && !"videoUrl".equals(key)) {
                                                    product.put(key, entry.getValue());
                                                }
                                            }
                                            // Rebuild image/video paths based on current directory name
                                            product.put("mainImages", listMainImagesWithSlots(prodDir));
                                            product.put("detailImages", listImagePaths(prodDir, "详情图"));
                                            product.put("skuImages", listImagePaths(prodDir, "SKU图"));
                                            String videoFile = findVideoInDir(prodDir);
                                            product.put("videoUrl", videoFile != null ? videoFile : "");
                                        } catch (Exception e) {
                                            log.warn("读取商品数据JSON失败: {}", e.getMessage());
                                            hasFullJson = false;
                                        }
                                    }

                                    if (!hasFullJson) {
                                        // Fallback: load individual files
                                        product.put("scrapedTitle", null);
                                        product.put("dirTitle", prodDir.getFileName().toString());
                                        Path attrPath = prodDir.resolve("商品属性.json");
                                        if (Files.exists(attrPath)) {
                                            String json = Files.readString(attrPath);
                                            Map<String, Object> attrs = mapper.readValue(json, Map.class);
                                            product.put("attributes", attrs);
                                        } else {
                                            product.put("attributes", Collections.emptyMap());
                                        }
                                        product.put("mainImages", listMainImagesWithSlots(prodDir));
                                        product.put("detailImages", listImagePaths(prodDir, "详情图"));
                                        String videoFileFallback = findVideoInDir(prodDir);
                                        product.put("videoUrl", videoFileFallback != null ? videoFileFallback : "");
                                    }

                                    // Load SKU images
                                    List<String> skuImages = listImagePaths(prodDir, "SKU图");
                                    product.put("skuImages", skuImages);

                                    // Load SKU data from CSV if not already in JSON
                                    if (!product.containsKey("skus")) {
                                        // Get attributes for spec info (like 颜色)
                                        @SuppressWarnings("unchecked")
                                        Map<String, Object> attrs = (Map<String, Object>) product.get("attributes");
                                        product.put("skus", loadSkusFromCsv(prodDir, attrs));
                                    }

                                    // Load pack info
                                    if (!product.containsKey("packInfo")) {
                                        Path packPath = prodDir.resolve("包装信息.json");
                                        if (Files.exists(packPath)) {
                                            String packJson = Files.readString(packPath);
                                            product.put("packInfo", mapper.readValue(packJson, List.class));
                                        } else {
                                            product.put("packInfo", Collections.emptyList());
                                        }
                                    }

                                    // Load video
                                    String videoFile = findVideoInDir(prodDir);
                                    product.put("videoUrl", videoFile != null ? videoFile : "");

                                    products.add(product);
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

    private List<String> listImagePaths(Path dir, String subDirName) {
        List<String> paths = new ArrayList<>();
        Path subDir = dir.resolve(subDirName);
        if (!Files.exists(subDir)) return paths;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(subDir,
                p -> {
                    String name = p.getFileName().toString().toLowerCase();
                    return name.endsWith(".jpg") || name.endsWith(".png") || name.endsWith(".jpeg") || name.endsWith(".webp");
                })) {
            for (Path p : stream) {
                paths.add(p.toString());
            }
            paths.sort(String::compareTo);
        } catch (IOException e) {
            log.warn("读取 {} 目录失败: {}", subDirName, e.getMessage());
        }
        return paths;
    }

    /**
     * Load main images with exactly 5 slots, filling missing ones with null.
     * Returns a list of 5 entries where null means the slot is empty.
     */
    private List<String> listMainImagesWithSlots(Path prodDir) {
        List<String> existing = listImagePaths(prodDir, "主图");
        List<String> slots = new ArrayList<>(Collections.nCopies(5, null));

        // Map each existing image to its slot based on filename
        for (String path : existing) {
            String name = new File(path).getName().toLowerCase();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("主图_(\\d{2})").matcher(name);
            if (m.find()) {
                int slotIdx = Integer.parseInt(m.group(1)) - 1;
                if (slotIdx >= 0 && slotIdx < 5) {
                    slots.set(slotIdx, path);
                }
            }
        }

        // If any image didn't match a named slot, fill remaining slots in order
        int nextSlot = 0;
        for (int i = 0; i < existing.size(); i++) {
            String path = existing.get(i);
            boolean placed = false;
            for (int j = 0; j < 5; j++) {
                if (slots.get(j) != null && slots.get(j).equals(path)) { placed = true; break; }
            }
            if (!placed) {
                while (nextSlot < 5 && slots.get(nextSlot) != null) nextSlot++;
                if (nextSlot < 5) { slots.set(nextSlot, path); nextSlot++; }
            }
        }

        return slots;
    }

    /**
     * Parse SKU data from 价格表.csv
     */
    private List<Map<String, Object>> loadSkusFromCsv(Path prodDir, Map<String, Object> attributes) {
        List<Map<String, Object>> skus = new ArrayList<>();
        Path csvPath = prodDir.resolve("价格表.csv");
        if (!Files.exists(csvPath)) return skus;

        // Extract 颜色 as spec info
        String colorSpec = attributes != null ? (String) attributes.get("颜色") : null;
        String[] colorValues = null;
        if (colorSpec != null && !colorSpec.isEmpty()) {
            colorValues = colorSpec.split(",");
        }

        try {
            List<String> lines = Files.readAllLines(csvPath);
            if (lines.size() < 2) return skus;

            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.isEmpty()) continue;

                String[] parts = line.split(",", -1);
                if (parts.length < 9) continue;

                Map<String, Object> sku = new LinkedHashMap<>();
                sku.put("skuId", parts[0].trim());
                sku.put("specName", parts[1].trim());
                // 规格：SKU名称 = 颜色值
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

                // Try to find SKU image
                String skuImgPath = prodDir.resolve("SKU图").resolve("SKU图_" + String.format("%02d", i) + ".jpg").toString();
                if (Files.exists(Paths.get(skuImgPath))) {
                    sku.put("imageUrl", skuImgPath);
                } else {
                    sku.put("imageUrl", "");
                }

                skus.add(sku);
            }
        } catch (IOException e) {
            log.warn("读取价格表CSV失败: {}", e.getMessage());
        }
        return skus;
    }

    private double parseDouble(String s) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return 0.0; }
    }

    private int parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    private String findVideoInDir(Path dir) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir,
                p -> {
                    String name = p.getFileName().toString().toLowerCase();
                    return name.endsWith(".mp4") || name.endsWith(".webm");
                })) {
            for (Path p : stream) {
                return p.toString();
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    /**
     * Resolve a URL path to a file path. Handles both relative and absolute paths.
     */
    private Path resolvePath(String urlPath) {
        String normalized = urlPath.replace("/", File.separator).replace("\\\\", "\\");
        if (normalized.matches("^[A-Za-z]:[\\\\/].*")) {
            return Paths.get(normalized);
        }
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
}
