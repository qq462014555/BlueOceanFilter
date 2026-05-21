package com.blueocean.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class ExcelReader {

    /**
     * 从 xlsx 文件读取所有非空单元格值作为关键词列表
     * 自动跳过第一行（通常是说明/规则文字）和"蓝海词"表头
     */
    public List<String> readKeywordsFromXlsx(String filePath) throws IOException {
        List<String> keywords = new ArrayList<>();
        try (FileInputStream fis = new FileInputStream(filePath);
             Workbook wb = new XSSFWorkbook(fis)) {

            Sheet sheet = wb.getSheetAt(0);
            boolean firstRow = true;
            for (Row row : sheet) {
                if (firstRow) {
                    firstRow = false;
                    continue;
                }
                Cell cell = row.getCell(0);
                if (cell != null && cell.getCellType() == CellType.STRING) {
                    String val = cell.getStringCellValue().trim();
                    if (!val.isEmpty() && !val.equals("蓝海词") && !val.startsWith("严格按")) {
                        keywords.add(val);
                    }
                }
            }
        }
        log.info("文件中-蓝海词总共：{}条",keywords);

        return keywords;
    }
}
