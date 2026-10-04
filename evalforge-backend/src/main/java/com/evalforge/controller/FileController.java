package com.evalforge.controller;

import com.evalforge.service.FileParseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/files")
@CrossOrigin(origins = "*")
public class FileController {

    @Autowired
    private FileParseService fileParseService;

    @PostMapping("/parse")
    public Map<String, Object> parseFile(@RequestParam("file") MultipartFile file) {
        Map<String, Object> result = new HashMap<>();
        try {
            // 通用文件解析：返回文件基本信息
            String filePath = fileParseService.saveFile(file);
            String fileName = file.getOriginalFilename();

            result.put("code", 0);
            result.put("data", Map.of(
                    "content", "[文件已上传：" + fileName + "]",
                    "fileName", fileName,
                    "filePath", filePath
            ));
        } catch (Exception e) {
            result.put("code", 500);
            result.put("message", "文件解析失败：" + e.getMessage());
        }
        return result;
    }
}
