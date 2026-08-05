package com.example.requirementrag.service;

import com.example.requirementrag.model.DoubtBatch;
import com.example.requirementrag.model.RequirementDoubt;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * 存疑批次 Excel 导出服务。
 */
@Service
public class DoubtExportService {

    /**
     * 将存疑批次导出为 XLSX 字节数组，表头与产品评审模板对齐。
     *
     * @param batch   待导出的存疑批次，逐条存疑生成一行数据
     * @param version 需求版本，拼入工作表名作为后缀
     * @return XLSX 文件字节
     * @throws IOException 工作簿写入失败时抛出
     */
    public byte[] toXlsx(DoubtBatch batch, String version) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet(sheetName(version));
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("功能分类");
            header.createCell(1).setCellValue("功能点");
            header.createCell(2).setCellValue("细化描述");
            header.createCell(3).setCellValue("存疑/问题");
            header.createCell(4).setCellValue("状态");
            header.createCell(5).setCellValue("产品回复");

            int rowIndex = 1;
            for (RequirementDoubt doubt : batch.doubts()) {
                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(cleanModule(doubt.module()));
                row.createCell(1).setCellValue(nullToEmpty(doubt.feature()));
                row.createCell(2).setCellValue(nullToEmpty(doubt.sourceLocation()));
                row.createCell(3).setCellValue(nullToEmpty(doubt.question()));
                row.createCell(4).setCellValue(toStatusLabel(doubt));
                row.createCell(5).setCellValue("");
            }

            for (int column = 0; column < 6; column++) {
                sheet.autoSizeColumn(column);
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }

    /** 空值转为空字符串，避免 Excel 单元格显示 null。 */
    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** 去除 [历史版本] 前缀，使功能分类列显示纯模块名。 */
    private String cleanModule(String module) {
        if (module == null) return "";
        return module.startsWith("[历史版本]") ? module.substring("[历史版本]".length()).trim() : module;
    }

    /** 将存疑状态映射为 Excel 中的中文标签。 */
    private String toStatusLabel(RequirementDoubt doubt) {
        if (doubt.status() == null) return "待确认";
        return switch (doubt.status()) {
            case UNANSWERED -> "待确认";
            case AMBIGUOUS  -> "待确认";
            default         -> "已明确";
        };
    }

    /** 生成工作表名「需求存疑[-版本]」，并截断到 Excel 允许的 31 字符上限。 */
    private String sheetName(String version) {
        String suffix = version == null || version.isBlank() ? "" : "-" + version.trim();
        String name = "需求存疑" + suffix;
        return name.length() <= 31 ? name : name.substring(0, 31);
    }

}