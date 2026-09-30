package com.qatools.controller;

import com.qatools.service.CriteriaStoreService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/config/criteria")
@CrossOrigin(origins = "*")
public class CriteriaController {

    @Autowired
    private CriteriaStoreService criteriaStore;

    /**
     * 获取评判标准
     */
    @GetMapping
    public Map<String, Object> getCriteria() {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("data", criteriaStore.get());
        return result;
    }

    /**
     * 更新评判标准
     */
    @SuppressWarnings("unchecked")
    @PutMapping
    public Map<String, Object> updateCriteria(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        try {
            List<Map<String, Object>> dimensions = null;
            if (body.containsKey("dimensions")) {
                dimensions = (List<Map<String, Object>>) body.get("dimensions");
            }
            List<Map<String, Object>> pptDimensions = null;
            if (body.containsKey("pptDimensions")) {
                pptDimensions = (List<Map<String, Object>>) body.get("pptDimensions");
            }
            List<Map<String, Object>> htmlDimensions = null;
            if (body.containsKey("htmlDimensions")) {
                htmlDimensions = (List<Map<String, Object>>) body.get("htmlDimensions");
            }
            Integer semimindWindowSize = null;
            if (body.get("semimindWindowSize") instanceof Number number) {
                semimindWindowSize = number.intValue();
            }
            Map<String, String> promptFields = new HashMap<>();
            for (String field : CriteriaStoreService.PROMPT_FIELD_NAMES) {
                if (body.get(field) instanceof String value) {
                    promptFields.put(field, value);
                }
            }
            criteriaStore.update(null, dimensions, pptDimensions, htmlDimensions,
                    semimindWindowSize, promptFields);
            result.put("code", 0);
            result.put("data", criteriaStore.get());
        } catch (Exception e) {
            result.put("code", 500);
            result.put("message", "更新评判标准失败: " + e.getMessage());
        }
        return result;
    }
}
