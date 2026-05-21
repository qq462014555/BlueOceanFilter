package com.blueocean;

import com.blueocean.entity.KeywordResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.*;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@Service
public class ExcelWriter {

    @Value("${app.output-dir:output}")
    private String outputDir;

    public static void writeKeptFile(List<KeywordResult> kept, String filePath) throws IOException {
        XSSFWorkbook wb = new XSSFWorkbook();
        XSSFSheet sheet = wb.createSheet("合规蓝海词");

        sheet.setColumnWidth(0, 50 * 256);
        sheet.setColumnWidth(1, 15 * 256);

        // Header style
        XSSFCellStyle headerStyle = wb.createCellStyle();
        headerStyle.setFillForegroundColor(new XSSFColor(new byte[]{(byte)46, (byte)125, (byte)50}, null));
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        headerStyle.setAlignment(HorizontalAlignment.CENTER);
        headerStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        XSSFFont headerFont = wb.createFont();
        headerFont.setBold(true);
        headerFont.setColor(new XSSFColor(new byte[]{(byte)255, (byte)255, (byte)255}, null));
        headerFont.setFontHeightInPoints((short) 12);
        headerStyle.setFont(headerFont);

        // Even row style
        XSSFCellStyle evenStyle = wb.createCellStyle();
        evenStyle.setFillForegroundColor(new XSSFColor(new byte[]{(byte)241, (byte)248, (byte)241}, null));
        evenStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        evenStyle.setVerticalAlignment(VerticalAlignment.CENTER);

        // Odd row style
        XSSFCellStyle oddStyle = wb.createCellStyle();
        oddStyle.setFillForegroundColor(new XSSFColor(new byte[]{(byte)255, (byte)255, (byte)255}, null));
        oddStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        oddStyle.setVerticalAlignment(VerticalAlignment.CENTER);

        // Status cell style (green text)
        XSSFCellStyle statusEvenStyle = wb.createCellStyle();
        statusEvenStyle.setFillForegroundColor(new XSSFColor(new byte[]{(byte)241, (byte)248, (byte)241}, null));
        statusEvenStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        statusEvenStyle.setAlignment(HorizontalAlignment.CENTER);
        statusEvenStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        XSSFFont greenFont = wb.createFont();
        greenFont.setColor(new XSSFColor(new byte[]{(byte)27, (byte)94, (byte)32}, null));
        greenFont.setBold(true);
        statusEvenStyle.setFont(greenFont);

        XSSFCellStyle statusOddStyle = wb.createCellStyle();
        statusOddStyle.setFillForegroundColor(new XSSFColor(new byte[]{(byte)255, (byte)255, (byte)255}, null));
        statusOddStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        statusOddStyle.setAlignment(HorizontalAlignment.CENTER);
        statusOddStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        statusOddStyle.setFont(greenFont);

        // Header row
        Row headerRow = sheet.createRow(0);
        headerRow.setHeightInPoints(28);
        Cell h1 = headerRow.createCell(0);
        h1.setCellValue("蓝海词");
        h1.setCellStyle(headerStyle);
        Cell h2 = headerRow.createCell(1);
        h2.setCellValue("状态");
        h2.setCellStyle(headerStyle);

        // Data rows
        for (int i = 0; i < kept.size(); i++) {
            Row row = sheet.createRow(i + 1);
            row.setHeightInPoints(22);
            boolean isEven = (i % 2 == 0);

            Cell c1 = row.createCell(0);
            c1.setCellValue(kept.get(i).getWord());
            c1.setCellStyle(isEven ? evenStyle : oddStyle);

            Cell c2 = row.createCell(1);
            c2.setCellValue("✓ 保留");
            c2.setCellStyle(isEven ? statusEvenStyle : statusOddStyle);
        }

        try (FileOutputStream fos = new FileOutputStream(filePath)) {
            wb.write(fos);
        }
        wb.close();
        System.out.println("✅ 已保存: " + filePath + "（共 " + kept.size() + " 条）");
    }

    public static void writeExcludedFile(List<KeywordResult> excluded, String filePath) throws IOException {
        XSSFWorkbook wb = new XSSFWorkbook();
        XSSFSheet sheet = wb.createSheet("剔除蓝海词");

        sheet.setColumnWidth(0, 50 * 256);
        sheet.setColumnWidth(1, 45 * 256);

        // Header style
        XSSFCellStyle headerStyle = wb.createCellStyle();
        headerStyle.setFillForegroundColor(new XSSFColor(new byte[]{(byte)198, (byte)40, (byte)40}, null));
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        headerStyle.setAlignment(HorizontalAlignment.CENTER);
        headerStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        XSSFFont headerFont = wb.createFont();
        headerFont.setBold(true);
        headerFont.setColor(new XSSFColor(new byte[]{(byte)255, (byte)255, (byte)255}, null));
        headerFont.setFontHeightInPoints((short) 12);
        headerStyle.setFont(headerFont);

        // Row styles
        XSSFCellStyle evenStyle = wb.createCellStyle();
        evenStyle.setFillForegroundColor(new XSSFColor(new byte[]{(byte)255, (byte)243, (byte)243}, null));
        evenStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        evenStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        evenStyle.setWrapText(true);

        XSSFCellStyle oddStyle = wb.createCellStyle();
        oddStyle.setFillForegroundColor(new XSSFColor(new byte[]{(byte)255, (byte)255, (byte)255}, null));
        oddStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        oddStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        oddStyle.setWrapText(true);

        XSSFFont redFont = wb.createFont();
        redFont.setColor(new XSSFColor(new byte[]{(byte)183, (byte)28, (byte)28}, null));

        XSSFCellStyle reasonEvenStyle = wb.createCellStyle();
        reasonEvenStyle.setFillForegroundColor(new XSSFColor(new byte[]{(byte)255, (byte)243, (byte)243}, null));
        reasonEvenStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        reasonEvenStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        reasonEvenStyle.setWrapText(true);
        reasonEvenStyle.setFont(redFont);

        XSSFCellStyle reasonOddStyle = wb.createCellStyle();
        reasonOddStyle.setFillForegroundColor(new XSSFColor(new byte[]{(byte)255, (byte)255, (byte)255}, null));
        reasonOddStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        reasonOddStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        reasonOddStyle.setWrapText(true);
        reasonOddStyle.setFont(redFont);

        // Header row
        Row headerRow = sheet.createRow(0);
        headerRow.setHeightInPoints(28);
        Cell h1 = headerRow.createCell(0);
        h1.setCellValue("蓝海词");
        h1.setCellStyle(headerStyle);
        Cell h2 = headerRow.createCell(1);
        h2.setCellValue("剔除原因");
        h2.setCellStyle(headerStyle);

        // Data rows
        for (int i = 0; i < excluded.size(); i++) {
            Row row = sheet.createRow(i + 1);
            row.setHeightInPoints(22);
            boolean isEven = (i % 2 == 0);

            Cell c1 = row.createCell(0);
            c1.setCellValue(excluded.get(i).getWord());
            c1.setCellStyle(isEven ? evenStyle : oddStyle);

            Cell c2 = row.createCell(1);
            c2.setCellValue(excluded.get(i).getReason());
            c2.setCellStyle(isEven ? reasonEvenStyle : reasonOddStyle);
        }

        try (FileOutputStream fos = new FileOutputStream(filePath)) {
            wb.write(fos);
        }
        wb.close();
        System.out.println("✅ 已保存: " + filePath + "（共 " + excluded.size() + " 条）");
    }
}
