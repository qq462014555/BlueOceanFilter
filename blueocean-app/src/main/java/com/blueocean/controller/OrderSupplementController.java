package com.blueocean.controller;

import com.blueocean.config.ShopConfig;
import com.blueocean.entity.OrderSupplement;
import com.blueocean.service.OrderSupplementService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/api/order-supplement")
public class OrderSupplementController {

    private static final Logger log = LoggerFactory.getLogger(OrderSupplementController.class);

    private final OrderSupplementService service;

    public OrderSupplementController(OrderSupplementService service) {
        this.service = service;
    }

    /**
     * 获取店铺列表
     */
    @GetMapping("/shops")
    public ResponseEntity<List<Map<String, String>>> shops() {
        return ResponseEntity.ok(ShopConfig.getShops());
    }

    /**
     * 分页查询
     */
    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String shopId,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sortField,
            @RequestParam(required = false) String sortOrder) {

        LocalDate from = dateFrom != null && !dateFrom.isEmpty() ? LocalDate.parse(dateFrom) : null;
        LocalDate to = dateTo != null && !dateTo.isEmpty() ? LocalDate.parse(dateTo) : null;

        Map<String, Object> result = service.pageQuery(page, size, shopId, from, to, keyword, status, sortField, sortOrder);
        return ResponseEntity.ok(result);
    }

    /**
     * 新增记录
     */
    @PostMapping("/create")
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody OrderSupplement entity) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            service.create(entity);
            result.put("success", true);
            result.put("id", entity.getId());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("新增补单记录失败", e);
            result.put("success", false);
            result.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * 更新记录
     */
    @PostMapping("/update")
    public ResponseEntity<Map<String, Object>> update(@Valid @RequestBody OrderSupplement entity) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            service.update(entity);
            result.put("success", true);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("更新补单记录失败", e);
            result.put("success", false);
            result.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * 删除记录（同步删除图片文件）
     */
    @PostMapping("/delete")
    public ResponseEntity<Map<String, Object>> delete(@RequestBody Map<String, Object> request) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Long id = Long.valueOf(request.get("id").toString());
            service.delete(id);
            result.put("success", true);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("删除补单记录失败", e);
            result.put("success", false);
            result.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * 创建组（归组）
     */
    @PostMapping("/create-group")
    public ResponseEntity<Map<String, Object>> createGroup(@RequestBody Map<String, Object> request) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            @SuppressWarnings("unchecked") List<Integer> ids = (List<Integer>) request.get("recordIds");
            String dateStr = (String) request.get("date");
            String resourceParty = (String) request.get("resourceParty");
            if (ids == null || ids.isEmpty()) {
                result.put("success", false); result.put("error", "请选择记录");
                return ResponseEntity.badRequest().body(result);
            }
            List<Long> recordIds = ids.stream().map(Long::valueOf).toList();
            LocalDate date = dateStr != null && !dateStr.isEmpty() ? LocalDate.parse(dateStr) : null;
            Long groupId = service.createGroup(recordIds, date, resourceParty);
            result.put("success", true);
            result.put("groupId", groupId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("创建组失败", e);
            result.put("success", false); result.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * 导出组内记录到Excel（使用模板）
     */
    @GetMapping("/export-excel")
    public void exportExcel(@RequestParam Long groupId, jakarta.servlet.http.HttpServletResponse response) throws IOException {
        try {
            service.exportToExcel(groupId, response);
        } catch (Exception e) {
            log.error("导出Excel失败", e);
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("导出失败: " + e.getMessage());
        }
    }

    /**
     * 绑定记录到已有组
     */
    @PostMapping("/bind-group")
    public ResponseEntity<Map<String, Object>> bindGroup(@RequestBody Map<String, Object> request) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            @SuppressWarnings("unchecked") List<Integer> ids = (List<Integer>) request.get("recordIds");
            Integer groupId = (Integer) request.get("groupId");
            if (ids == null || ids.isEmpty() || groupId == null) {
                result.put("success", false); result.put("error", "参数错误");
                return ResponseEntity.badRequest().body(result);
            }
            service.bindToGroup(ids.stream().map(Long::valueOf).toList(), groupId.longValue());
            result.put("success", true);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("绑定组失败", e);
            result.put("success", false); result.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * 标记组为已发送
     */
    @PostMapping("/mark-sent")
    public ResponseEntity<Map<String, Object>> markSent(@RequestBody Map<String, Object> request) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Long groupId = Long.valueOf(request.get("groupId").toString());
            service.markGroupSent(groupId);
            result.put("success", true);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("标记已发送失败", e);
            result.put("success", false); result.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * 取消记录与组的绑定
     */
    @PostMapping("/ungroup")
    public ResponseEntity<Map<String, Object>> ungroup(@RequestBody Map<String, Object> request) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Long id = Long.valueOf(request.get("id").toString());
            service.ungroupRecord(id);
            result.put("success", true);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("取消绑定失败", e);
            result.put("success", false); result.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * 模糊搜索资源方
     */
    @GetMapping("/resource-parties")
    public ResponseEntity<List<Map<String, Object>>> searchResourceParties(@RequestParam String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(service.searchResourceParties(keyword.trim()));
    }

    /**
     * 根据组ID生成下单二维码URL
     */
    @PostMapping("/mark-sending")
    public ResponseEntity<Map<String, Object>> markSending(@RequestBody Map<String, Object> request) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Long groupId = Long.valueOf(request.get("groupId").toString());
            service.markGroupSending(groupId);
            result.put("success", true);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("标记补单中失败", e);
            result.put("success", false);
            result.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    @GetMapping("/order-url")
    public ResponseEntity<Map<String, Object>> getOrderUrl(@RequestParam Long groupId) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            String url = service.generateOrderUrl(groupId);
            result.put("success", true);
            result.put("url", url);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * 根据组ID查询组内所有记录
     */
    @GetMapping("/list-by-group")
    public ResponseEntity<List<OrderSupplement>> listByGroupId(@RequestParam Long groupId) {
        return ResponseEntity.ok(service.listByGroupId(groupId));
    }

    /**
     * 上传评论图片
     */
    @PostMapping("/upload-image")
    public ResponseEntity<Map<String, Object>> uploadImage(@RequestBody Map<String, Object> request) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            String imageData = (String) request.get("image");
            String shopId = (String) request.get("shopId");
            String productName = (String) request.get("productName");
            if (imageData == null || shopId == null || productName == null) {
                result.put("success", false);
                result.put("error", "缺少参数");
                return ResponseEntity.badRequest().body(result);
            }
            String relativePath = service.uploadImage(imageData, shopId, productName);
            result.put("success", true);
            result.put("path", relativePath);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("上传评论图片失败", e);
            result.put("success", false);
            result.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * 获取评论图片（根据相对路径返回图片数据）
     */
    @GetMapping("/image")
    public ResponseEntity<byte[]> getImage(@RequestParam String path) {
        try {
            String fullPath = service.getFullImagePath(path);
            java.nio.file.Path file = Paths.get(fullPath);
            if (!Files.exists(file)) return ResponseEntity.notFound().build();
            byte[] data = Files.readAllBytes(file);
            String contentType = fullPath.toLowerCase().endsWith(".png") ? "image/png" : "image/jpeg";
            return ResponseEntity.ok().header("Content-Type", contentType).body(data);
        } catch (IOException e) {
            return ResponseEntity.badRequest().build();
        }
    }
}
