package com.blueocean.scraper;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class ExcelReporter {

    public String generateSummary(List<ProductData> products) {
        String linkDir = LinkFileReader.findLatestLinkFile().getParent();
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String filename = linkDir + File.separator + "1688商品采集结果_" + timestamp + ".xlsx";

        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("采集汇总");

            // Header style
            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);

            // Header row
            String[] headers = {"商品标题", "类目路径", "主图数", "详情图数", "SKU数", "价格表数"};
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            // Data rows
            for (int i = 0; i < products.size(); i++) {
                ProductData p = products.get(i);
                Row row = sheet.createRow(i + 1);
                row.createCell(0).setCellValue(p.getTitle());
                row.createCell(1).setCellValue(p.getCategoryPath());
                row.createCell(2).setCellValue(p.getMainImages().size());
                row.createCell(3).setCellValue(p.getDetailImages().size());
                row.createCell(4).setCellValue(p.getSkus().size());
                row.createCell(5).setCellValue(!p.getSkus().isEmpty() ? p.getSkus().size() : 0);
            }

            // Auto-size columns
            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
            }

            try (FileOutputStream fos = new FileOutputStream(filename)) {
                wb.write(fos);
            }
        } catch (IOException e) {
            throw new RuntimeException("生成Excel汇总失败", e);
        }

        return filename;
    }
}
