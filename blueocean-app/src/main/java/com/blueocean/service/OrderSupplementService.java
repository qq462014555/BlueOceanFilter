package com.blueocean.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.blueocean.entity.OrderSupplement;
import com.blueocean.entity.SupplementGroup;
import com.blueocean.mapper.OrderSupplementMapper;
import com.blueocean.mapper.SupplementGroupMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class OrderSupplementService {

    private static final Logger log = LoggerFactory.getLogger(OrderSupplementService.class);

    private final OrderSupplementMapper mapper;
    private final SupplementGroupMapper groupMapper;

    @Value("${app.supplement-image-dir:C:\\Users\\46201\\Pictures\\补单}")
    private String imageBaseDir;

    public OrderSupplementService(OrderSupplementMapper mapper, SupplementGroupMapper groupMapper) {
        this.mapper = mapper;
        this.groupMapper = groupMapper;
    }

    /**
     * 分页查询，支持多条件组合筛选 + 排序
     */
    public Map<String, Object> pageQuery(int page, int size,
                                          String shopId, LocalDate dateFrom, LocalDate dateTo,
                                          String keyword, String status,
                                          String sortField, String sortOrder) {
        Page<OrderSupplement> pageObj = new Page<>(page, size);
        LambdaQueryWrapper<OrderSupplement> wrapper = new LambdaQueryWrapper<>();

        // 店铺筛选
        if (shopId != null && !shopId.isEmpty()) {
            wrapper.eq(OrderSupplement::getShopId, shopId);
        }
        // 日期范围
        if (dateFrom != null) {
            wrapper.ge(OrderSupplement::getDate, dateFrom);
        }
        if (dateTo != null) {
            wrapper.le(OrderSupplement::getDate, dateTo);
        }
        // 关键词模糊匹配（商品名称、商品ID、SKU ID）
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.and(w -> w.like(OrderSupplement::getProductName, keyword)
                    .or().like(OrderSupplement::getProductId, keyword)
                    .or().like(OrderSupplement::getSkuId, keyword));
        }
        // 状态筛选
        if (status != null && !status.isEmpty()) {
            wrapper.eq(OrderSupplement::getStatus, status);
        }

        // 排序
        String field = sortField != null ? sortField : "date";
        boolean asc = "asc".equalsIgnoreCase(sortOrder);
        switch (field) {
            case "shopId" -> wrapper.orderBy(asc, asc, OrderSupplement::getShopId);
            case "productName" -> wrapper.orderBy(asc, asc, OrderSupplement::getProductName);
            case "productId" -> wrapper.orderBy(asc, asc, OrderSupplement::getProductId);
            case "skuId" -> wrapper.orderBy(asc, asc, OrderSupplement::getSkuId);
            case "price" -> wrapper.orderBy(asc, asc, OrderSupplement::getPrice);
            case "resourceParty" -> wrapper.orderBy(asc, asc, OrderSupplement::getResourceParty);
            case "status" -> wrapper.orderBy(asc, asc, OrderSupplement::getStatus);
            default -> wrapper.orderBy(asc, asc, OrderSupplement::getDate); // date 默认
        }

        Page<OrderSupplement> resultPage = mapper.selectPage(pageObj, wrapper);

        // 批量填充 date 和 resourceParty（来自 group 表）
        List<OrderSupplement> records = resultPage.getRecords();
        if (records != null && !records.isEmpty()) {
            Map<Long, SupplementGroup> groupMap = new HashMap<>();
            for (OrderSupplement r : records) {
                if (r.getGroupId() != null) groupMap.put(r.getGroupId(), null);
            }
            if (!groupMap.isEmpty()) {
                List<SupplementGroup> groups = groupMapper.selectBatchIds(groupMap.keySet());
                for (SupplementGroup g : groups) {
                    groupMap.put(g.getId(), g);
                }
                for (OrderSupplement r : records) {
                    if (r.getGroupId() != null) {
                        SupplementGroup g = groupMap.get(r.getGroupId());
                        if (g != null) {
                            r.setDate(g.getDate());
                            r.setResourceParty(g.getResourceParty());
                        }
                    }
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", resultPage.getTotal());
        result.put("page", resultPage.getCurrent());
        result.put("size", resultPage.getSize());
        result.put("records", records);
        return result;
    }

    /**
     * 新增记录
     */
    @Transactional
    public void create(OrderSupplement entity) {
        entity.setCreateTime(java.time.LocalDateTime.now());
        entity.setUpdateTime(java.time.LocalDateTime.now());
        if (entity.getStatus() == null || entity.getStatus().isEmpty()) {
            entity.setStatus("待补单");
        }
        mapper.insert(entity);
    }

    /**
     * 更新记录
     */
    @Transactional
    public void update(OrderSupplement entity) {
        entity.setUpdateTime(java.time.LocalDateTime.now());
        mapper.updateById(entity);
    }

    /**
     * 删除记录，同步删除关联的图片文件
     */
    @Transactional
    public void delete(Long id) {
        OrderSupplement record = mapper.selectById(id);
        if (record == null) return;

        // 删除关联的图片文件
        String images = record.getReviewImage();
        if (images != null && !images.isEmpty()) {
            for (String img : images.split(",")) {
                img = img.trim();
                if (!img.isEmpty()) {
                    try {
                        Path fullPath = Paths.get(imageBaseDir, img);
                        Files.deleteIfExists(fullPath);
                        log.info("已删除图片: {}", fullPath);
                    } catch (IOException e) {
                        log.warn("删除图片失败: {}", img, e);
                    }
                }
            }
        }

        mapper.deleteById(id);
    }

    /**
     * 创建组，将指定记录归入该组，并填充日期和资源方
     * @param recordIds 记录ID列表
     * @param date 下单日期
     * @param resourceParty 资源方
     * @return 组ID
     */
    @Transactional
    public Long createGroup(List<Long> recordIds, LocalDate date, String resourceParty) {
        SupplementGroup group = new SupplementGroup();
        group.setDate(date);
        group.setResourceParty(resourceParty);
        group.setStatus("待发送");
        group.setCreateTime(java.time.LocalDateTime.now());
        group.setUpdateTime(java.time.LocalDateTime.now());
        groupMapper.insert(group);

        // 更新每条记录的 group_id
        for (Long id : recordIds) {
            OrderSupplement record = new OrderSupplement();
            record.setId(id);
            record.setGroupId(group.getId());
            record.setUpdateTime(java.time.LocalDateTime.now());
            mapper.updateById(record);
        }
        return group.getId();
    }

    /**
     * 标记组为已发送，同时批量更新组内记录状态为已补单
     */
    @Transactional
    public void markGroupSent(Long groupId) {
        SupplementGroup group = new SupplementGroup();
        group.setId(groupId);
        group.setStatus("已发送");
        group.setUpdateTime(java.time.LocalDateTime.now());
        groupMapper.updateById(group);

        // 批量更新组内记录状态
        LambdaQueryWrapper<OrderSupplement> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OrderSupplement::getGroupId, groupId);
        OrderSupplement update = new OrderSupplement();
        update.setStatus("已补单");
        update.setUpdateTime(java.time.LocalDateTime.now());
        mapper.update(update, wrapper);
    }

    /**
     * 取消记录与组的绑定
     */
    @Transactional
    public void ungroupRecord(Long id) {
        OrderSupplement record = mapper.selectById(id);
        if (record == null || record.getGroupId() == null) return;
        Long groupId = record.getGroupId();
        // updateById 会忽略 null 字段，所以用 UpdateWrapper 显式 SET group_id = NULL
        mapper.update(null, new LambdaUpdateWrapper<OrderSupplement>()
                .set(OrderSupplement::getGroupId, null)
                .set(OrderSupplement::getUpdateTime, java.time.LocalDateTime.now())
                .eq(OrderSupplement::getId, id));
        // 如果组内没有其他记录了，删除组
        LambdaQueryWrapper<OrderSupplement> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OrderSupplement::getGroupId, groupId);
        Long count = mapper.selectCount(wrapper);
        if (count == 0) {
            groupMapper.deleteById(groupId);
        }
    }

    /**
     * 将记录绑定到已有组
     */
    @Transactional
    public void bindToGroup(List<Long> recordIds, Long groupId) {
        for (Long id : recordIds) {
            mapper.update(null, new LambdaUpdateWrapper<OrderSupplement>()
                    .set(OrderSupplement::getGroupId, groupId)
                    .set(OrderSupplement::getUpdateTime, java.time.LocalDateTime.now())
                    .eq(OrderSupplement::getId, id));
        }
    }

    /**
     * 模糊搜索已有资源方
     */
    public List<Map<String, Object>> searchResourceParties(String keyword) {
        return groupMapper.selectMaps(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SupplementGroup>()
                .select("id, resource_party")
                .like("resource_party", keyword)
                .isNotNull("resource_party")
                .ne("resource_party", "")
                .orderByDesc("id")
                .last("LIMIT 20"));
    }

    /**
     * 上传评论图片，保存到磁盘
     * @param imageData base64 或 URL
     * @param shopId 店铺ID
     * @param productName 商品名称
     * @return 相对路径
     */
    public String uploadImage(String imageData, String shopId, String productName) {
        try {
            LocalDate today = LocalDate.now();
            String dateStr = today.toString();
            Path dir = Paths.get(imageBaseDir, dateStr);
            Files.createDirectories(dir);

            // 解码图片数据
            byte[] bytes;
            if (imageData.startsWith("http://") || imageData.startsWith("https://")) {
                var url = new java.net.URL(imageData);
                try (var in = url.openStream()) { bytes = in.readAllBytes(); }
            } else {
                String b64 = imageData.contains(",") ? imageData.substring(imageData.indexOf(",") + 1) : imageData;
                bytes = java.util.Base64.getDecoder().decode(b64);
            }

            // 获取图片宽高
            String dimension = "";
            try {
                java.io.ByteArrayInputStream bis = new java.io.ByteArrayInputStream(bytes);
                java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(bis);
                if (img != null) {
                    dimension = img.getWidth() + "×" + img.getHeight();
                }
            } catch (Exception ignored) {}

            // 计算序号
            String safeName = sanitizeFileName(productName);
            int seq = 1;
            try (var files = Files.list(dir)) {
                long maxSeq = files.filter(f -> f.getFileName().toString().startsWith(shopId + "_" + safeName))
                        .map(f -> {
                            String name = f.getFileName().toString();
                            int dot = name.lastIndexOf('.');
                            String numPart = dot > 0 ? name.substring(dot - 2, dot) : "00";
                            try { return Integer.parseInt(numPart); } catch (Exception e) { return 0; }
                        }).max(Integer::compareTo).orElse(0);
                seq = (int) maxSeq + 1;
            }

            String fileName = String.format("%s_%s_%02d.jpg", shopId, safeName, seq);
            Path target = dir.resolve(fileName);
            Files.write(target, bytes);

            // 返回相对路径 + 尺寸信息（用 | 分隔）
            String relativePath = dateStr + "/" + fileName;
            if (!dimension.isEmpty()) {
                relativePath += "|" + dimension;
            }

            return relativePath;
        } catch (Exception e) {
            log.error("上传评论图片失败", e);
            throw new RuntimeException("上传图片失败: " + e.getMessage());
        }
    }

    /**
     * 获取完整图片路径
     */
    public String getFullImagePath(String relativePath) {
        // 去掉尺寸后缀
        String path = relativePath.contains("|") ? relativePath.split("\\|")[0] : relativePath;
        return Paths.get(imageBaseDir, path).toString();
    }

    private String sanitizeFileName(String name) {
        if (name == null) return "unknown";
        return name.replaceAll("[\\\\/:*?\"<>|]", "").trim();
    }
}
