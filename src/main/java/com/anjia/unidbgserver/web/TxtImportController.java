package com.anjia.unidbgserver.web;

import com.anjia.unidbgserver.service.TxtImportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * TXT 小说导入控制器
 * POST /api/txt/import 上传 TXT 文件，解析后存入 Redis
 */
@Slf4j
@RestController
@RequestMapping("/api/txt")
public class TxtImportController {

    @Autowired
    private TxtImportService txtImportService;

    /**
     * 上传并导入 TXT 小说
     *
     * @param file 上传的 TXT 文件（UTF-8 编码，章节格式：第N章 标题）
     * @return 导入结果信息
     */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> importTxt(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "文件不能为空"));
        }
        try {
            byte[] bytes = file.getBytes();
            String result = txtImportService.importTxt(bytes);
            boolean success = result.startsWith("导入成功");
            return ResponseEntity.ok(Map.of("success", success, "message", result));
        } catch (Exception e) {
            log.error("TXT 导入失败 - file: {}", file.getOriginalFilename(), e);
            return ResponseEntity.internalServerError().body(Map.of("success", false, "message", "导入异常: " + e.getMessage()));
        }
    }
}
