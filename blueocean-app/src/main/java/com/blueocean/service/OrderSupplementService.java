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
            case "date" -> wrapper.orderBy(asc, asc, OrderSupplement::getCreateTime);
            default -> wrapper.orderByDesc(OrderSupplement::getCreateTime);
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

        // 按组日期排序：空日期在最顶部，有日期的按日期倒序
        boolean sortByDate = sortField == null || "date".equals(sortField);
        if (sortByDate) {
            asc = "asc".equalsIgnoreCase(sortOrder);
            boolean finalAsc = asc;
            records.sort((a, b) -> {
                LocalDate da = a.getDate();
                LocalDate db = b.getDate();
                if (da == null && db == null) return 0;
                if (da == null) return -1;
                if (db == null) return 1;
                return finalAsc ? da.compareTo(db) : db.compareTo(da);
            });
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
                    // 去掉尺寸后缀（|1435×1914）
                    String cleanPath = img.contains("|") ? img.substring(0, img.indexOf("|")) : img;
                    try {
                        Path fullPath = Paths.get(imageBaseDir, cleanPath);
                        Files.deleteIfExists(fullPath);
                        log.info("已删除图片: {}", fullPath);
                    } catch (IOException e) {
                        log.warn("删除图片失败: {}", cleanPath, e);
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
     * 标记组内所有记录为"补单中"（已发给刷手）
     */
    @Transactional
    public void markGroupSending(Long groupId) {
        SupplementGroup group = new SupplementGroup();
        group.setId(groupId);
        group.setStatus("已发送");
        group.setUpdateTime(java.time.LocalDateTime.now());
        groupMapper.updateById(group);

        LambdaQueryWrapper<OrderSupplement> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OrderSupplement::getGroupId, groupId);
        OrderSupplement update = new OrderSupplement();
        update.setStatus("补单中");
        update.setUpdateTime(java.time.LocalDateTime.now());
        mapper.update(update, wrapper);
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
     * 生成组的下单二维码URL
     */
    public String generateOrderUrl(Long groupId) {
        List<OrderSupplement> records = listByGroupId(groupId);
        String params = records.stream()
                .map(r -> r.getProductId() + "_1_" + (r.getSkuId() != null ? r.getSkuId() : ""))
                .filter(p -> !p.startsWith("_1_"))
                .collect(java.util.stream.Collectors.joining(","));
        if (params.isEmpty()) {
            throw new RuntimeException("组内没有包含商品ID的记录");
        }

        String url = "https://main.m.taobao.com/order/index.html?buyNow=false&buyParam="
                + params + "&abtest_module=dx2native.settlement-bar.0";
        log.info("下单二维码URL: {}", url);
        return url;
    }

    /**
     * 根据组ID查询组内所有记录
     */
    public List<OrderSupplement> listByGroupId(Long groupId) {
        LambdaQueryWrapper<OrderSupplement> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OrderSupplement::getGroupId, groupId);
        List<OrderSupplement> records = mapper.selectList(wrapper);
        // 填充组信息
        if (!records.isEmpty()) {
            SupplementGroup group = groupMapper.selectById(groupId);
            if (group != null) {
                for (OrderSupplement r : records) {
                    r.setDate(group.getDate());
                    r.setResourceParty(group.getResourceParty());
                }
            }
        }
        return records;
    }

    /**
     * 导出组内记录到Excel（使用模板 + 二维码）
     */
    @Transactional
    public void exportToExcel(Long groupId, jakarta.servlet.http.HttpServletResponse response) throws Exception {
        List<OrderSupplement> records = listByGroupId(groupId);
        String qrUrl = generateOrderUrl(groupId);

        // 读取模板
        java.nio.file.Path templatePath = java.nio.file.Paths.get("docs/templates/补单模板.xlsx");
        org.apache.poi.xssf.usermodel.XSSFWorkbook wb;
        if (java.nio.file.Files.exists(templatePath)) {
            wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(java.nio.file.Files.newInputStream(templatePath));
        } else {
            wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
            wb.createSheet("补单");
        }
        org.apache.poi.ss.usermodel.Sheet sheet = wb.getSheetAt(0);

        // 1) 二维码放入 A1:D20 区域
        if (qrUrl != null && !qrUrl.isEmpty()) {
            try {
                // 用在线 API 生成二维码图片
                java.net.URL qrApi = new java.net.URL("https://api.qrserver.com/v1/create-qr-code/?size=250x250&data="
                        + java.net.URLEncoder.encode(qrUrl, "UTF-8"));
                byte[] imgBytes;
                try (java.io.InputStream in = qrApi.openStream()) {
                    imgBytes = in.readAllBytes();
                }
                log.info("二维码图片下载成功, {}bytes", imgBytes.length);

                int pictureIdx = wb.addPicture(imgBytes, org.apache.poi.ss.usermodel.Workbook.PICTURE_TYPE_PNG);
                org.apache.poi.ss.usermodel.Drawing drawing = sheet.createDrawingPatriarch();
                org.apache.poi.ss.usermodel.ClientAnchor anchor = wb.getCreationHelper().createClientAnchor();
                anchor.setAnchorType(org.apache.poi.ss.usermodel.ClientAnchor.AnchorType.DONT_MOVE_AND_RESIZE);
                anchor.setCol1(0); anchor.setRow1(0);
                anchor.setCol2(3); anchor.setRow2(19);
                drawing.createPicture(anchor, pictureIdx);
                log.info("二维码已插入Excel");
            } catch (Exception e) {
                log.error("二维码插入Excel失败: {}", e.getMessage());
                // 备选：把 URL 写入 A1
                try {
                    org.apache.poi.ss.usermodel.Row urlRow = sheet.getRow(0);
                    if (urlRow == null) urlRow = sheet.createRow(0);
                    urlRow.createCell(0).setCellValue(qrUrl);
                } catch (Exception ignored) {}
            }
        }

        // 2) 获取组信息（日期）
        SupplementGroup group = groupMapper.selectById(groupId);
        String groupDate = group != null && group.getDate() != null ? group.getDate().toString() : "";

        // 设置数据列的列宽
        int[] colWidths = {14, 18, 16, 60, 12, 18, 12, 60, 16};
        for (int i = 0; i < colWidths.length; i++) {
            sheet.setColumnWidth(i, colWidths[i] * 256);
        }

        // 数据行居中样式
        org.apache.poi.ss.usermodel.CellStyle centerStyle = wb.createCellStyle();
        centerStyle.setVerticalAlignment(org.apache.poi.ss.usermodel.VerticalAlignment.CENTER);

        // 3) 商品数据从第22行开始
        // 模板列：A=日期, B=店铺ID, C=店铺名, D=商品标题, E=商家价格, F=订单号, G=用户实付金额, H=评价文案, I=评价图
        int rowIdx = 21;
        for (OrderSupplement r : records) {
            org.apache.poi.ss.usermodel.Row row = sheet.getRow(rowIdx);
            if (row == null) row = sheet.createRow(rowIdx);
            row.setHeightInPoints(150);

            // 解析 shopId 格式：平台/店铺名/shopId
            String shopIdFull = r.getShopId() != null ? r.getShopId() : "";
            String[] parts = shopIdFull.split("/");
            String shopName = parts.length >= 2 ? parts[parts.length - 2] : "";
            String shopId = parts.length >= 3 ? parts[parts.length - 1] : shopIdFull;

            org.apache.poi.ss.usermodel.Cell c0 = row.createCell(0, org.apache.poi.ss.usermodel.CellType.STRING);
            c0.setCellValue(groupDate); c0.setCellStyle(centerStyle);
            org.apache.poi.ss.usermodel.Cell c1 = row.createCell(1, org.apache.poi.ss.usermodel.CellType.STRING);
            c1.setCellValue(shopId); c1.setCellStyle(centerStyle);
            org.apache.poi.ss.usermodel.Cell c2 = row.createCell(2, org.apache.poi.ss.usermodel.CellType.STRING);
            c2.setCellValue(shopName); c2.setCellStyle(centerStyle);
            org.apache.poi.ss.usermodel.Cell c3 = row.createCell(3, org.apache.poi.ss.usermodel.CellType.STRING);
            c3.setCellValue(r.getProductName() != null ? r.getProductName() : ""); c3.setCellStyle(centerStyle);
            org.apache.poi.ss.usermodel.Cell c4 = row.createCell(4, org.apache.poi.ss.usermodel.CellType.NUMERIC);
            c4.setCellValue(r.getPrice() != null ? r.getPrice().doubleValue() : 0); c4.setCellStyle(centerStyle);
            org.apache.poi.ss.usermodel.Cell c5 = row.createCell(5, org.apache.poi.ss.usermodel.CellType.STRING);
            c5.setCellValue(""); c5.setCellStyle(centerStyle);
            org.apache.poi.ss.usermodel.Cell c6 = row.createCell(6, org.apache.poi.ss.usermodel.CellType.NUMERIC);
            c6.setCellValue(0); c6.setCellStyle(centerStyle);
            org.apache.poi.ss.usermodel.Cell c7 = row.createCell(7, org.apache.poi.ss.usermodel.CellType.STRING);
            c7.setCellValue(r.getReviewText() != null ? r.getReviewText() : ""); c7.setCellStyle(centerStyle);

            // I: 评价图 — 插入实际图片
            String reviewImages = r.getReviewImage();
            if (reviewImages != null && !reviewImages.isEmpty()) {
                String[] imgPaths = reviewImages.split(",");
                for (int pi = 0; pi < imgPaths.length && pi < 3; pi++) { // 最多3张
                    try {
                        String relPath = imgPaths[pi].trim().split("\\|")[0];
                        java.nio.file.Path fullPath = java.nio.file.Paths.get(imageBaseDir, relPath);
                        if (java.nio.file.Files.exists(fullPath)) {
                            byte[] imgBytes = java.nio.file.Files.readAllBytes(fullPath);
                            int picType = relPath.toLowerCase().endsWith(".png")
                                    ? org.apache.poi.ss.usermodel.Workbook.PICTURE_TYPE_PNG
                                    : org.apache.poi.ss.usermodel.Workbook.PICTURE_TYPE_JPEG;
                            int picIdx = wb.addPicture(imgBytes, picType);
                            int colOff = pi;
                            org.apache.poi.ss.usermodel.Drawing drawing = sheet.createDrawingPatriarch();
                            org.apache.poi.xssf.usermodel.XSSFClientAnchor anchor = new org.apache.poi.xssf.usermodel.XSSFClientAnchor();
                            anchor.setAnchorType(org.apache.poi.ss.usermodel.ClientAnchor.AnchorType.MOVE_DONT_RESIZE);
                            anchor.setCol1(8 + colOff); anchor.setRow1(rowIdx);
                            anchor.setCol2(9 + colOff); anchor.setRow2(rowIdx + 1);
                            drawing.createPicture(anchor, picIdx);
                        }
                    } catch (Exception e) {
                        log.warn("评论图插入失败: {}", imgPaths[pi], e);
                    }
                }
            }

            rowIdx++;
        }

        // 底部汇总
        int lastRow = rowIdx - 1;
        if (lastRow >= 21) {
            // 合计行
            org.apache.poi.ss.usermodel.Row sumRow = sheet.createRow(rowIdx);
            sumRow.createCell(3).setCellValue("合计");
            sumRow.createCell(4, org.apache.poi.ss.usermodel.CellType.FORMULA).setCellFormula("SUM(E21:E" + (lastRow + 1) + ")");
            sumRow.createCell(6, org.apache.poi.ss.usermodel.CellType.FORMULA).setCellFormula("SUM(G21:G" + (lastRow + 1) + ")");

            // 佣金行（用户手动输入，不设公式）
            org.apache.poi.ss.usermodel.Row commRow = sheet.createRow(rowIdx + 1);
            commRow.createCell(3).setCellValue("佣金");

            // 佣金合计行
            org.apache.poi.ss.usermodel.Row subRow2 = sheet.createRow(rowIdx + 2);
            subRow2.createCell(3).setCellValue("佣金合计");
            subRow2.createCell(6, org.apache.poi.ss.usermodel.CellType.FORMULA).setCellFormula("G" + (rowIdx + 1) + "+G" + (rowIdx + 2)); // = 合计G + 佣金G
        }

        // 输出
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=order_export.xlsx");
        wb.write(response.getOutputStream());
        wb.close();
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

            // 店铺子目录名：将 平台/店铺名/shopId 的 / 替换为 _
            String shopDirName = shopId.replace("/", "_");
            Path dir = Paths.get(imageBaseDir, dateStr, shopDirName);
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
                long maxSeq = files.filter(f -> f.getFileName().toString().startsWith(safeName))
                        .map(f -> {
                            String name = f.getFileName().toString();
                            int dot = name.lastIndexOf('.');
                            String numPart = dot > 0 ? name.substring(dot - 2, dot) : "00";
                            try { return Integer.parseInt(numPart); } catch (Exception e) { return 0; }
                        }).max(Integer::compareTo).orElse(0);
                seq = (int) maxSeq + 1;
            }

            String fileName = String.format("%s_%02d.jpg", safeName, seq);
            Path target = dir.resolve(fileName);
            Files.write(target, bytes);

            // 返回相对路径 + 尺寸信息（用 | 分隔）
            String relativePath = dateStr + "/" + shopDirName + "/" + fileName;
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
