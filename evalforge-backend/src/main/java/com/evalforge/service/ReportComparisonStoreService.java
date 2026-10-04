package com.evalforge.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 模型测评报告对比存储服务 —— 选取两份及以上历史测试报告（同模型不同版本、或不同模型），
 * 按子评测类型（subType）分组做交叉对比，并给出总体结论与排名。表结构需手动建表
 * (model_report_comparison)，见部署文档；@PostConstruct 只负责已有表的兼容性检查，
 * 不会自动创建全新表。
 */
@Service
public class ReportComparisonStoreService {

    private static final Logger log = LoggerFactory.getLogger(ReportComparisonStoreService.class);

    public static final String STATUS_GENERATING = "generating";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicInteger idCounter = new AtomicInteger(1);

    @PostConstruct
    public void init() {
        try {
            tryAddColumn("comparison_mode", "VARCHAR(20) COMMENT '对比场景:performance模型性能比较/regression模型回归验证'");
            Integer max = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(MAX(CAST(SUBSTRING_INDEX(id, '-', -1) AS UNSIGNED)), 0) FROM model_report_comparison",
                    Integer.class);
            if (max != null) {
                idCounter.set(max + 1);
            }
        } catch (Exception e) {
            log.warn("初始化报告对比ID计数器失败（表可能尚未创建，需手动建表）: {}", e.getMessage());
        }
    }

    private void tryAddColumn(String columnName, String columnDef) {
        try {
            Integer exists = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() " +
                            "AND TABLE_NAME = 'model_report_comparison' AND COLUMN_NAME = ?",
                    Integer.class, columnName);
            if (exists == null || exists == 0) {
                jdbcTemplate.execute("ALTER TABLE model_report_comparison ADD COLUMN " + columnName + " " + columnDef);
            }
        } catch (Exception e) {
            log.warn("检查/新增 {} 失败（表可能尚未创建）: {}", columnName, e.getMessage());
        }
    }

    public String nextId() {
        return "comparison-" + idCounter.getAndIncrement();
    }

    public Map<String, Object> get(String id) {
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT * FROM model_report_comparison WHERE id = ?", id);
            return rowToMap(row);
        } catch (Exception e) {
            return null;
        }
    }

    public void create(String id, String judgeModelId, List<String> reportIds, String comparisonMode) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO model_report_comparison (id, judge_model_id, status, report_ids, comparison_mode) " +
                            "VALUES (?, ?, ?, ?, ?)",
                    id, judgeModelId, STATUS_GENERATING, toJson(reportIds), comparisonMode);
        } catch (Exception e) {
            log.error("创建报告对比记录失败: id={}", id, e);
            throw new RuntimeException("创建报告对比记录失败", e);
        }
    }

    public void markCompleted(String id, List<Map<String, Object>> subtypeComparisons,
                              String overallConclusion, Map<String, Object> promptSnapshot) {
        jdbcTemplate.update(
                "UPDATE model_report_comparison SET status = ?, subtype_comparisons = ?, overall_conclusion = ?, " +
                        "prompt_snapshot = ?, error_message = NULL WHERE id = ?",
                STATUS_COMPLETED, toJson(subtypeComparisons), overallConclusion, toJson(promptSnapshot), id);
    }

    public void markFailed(String id, String errorMessage) {
        jdbcTemplate.update(
                "UPDATE model_report_comparison SET status = ?, error_message = ? WHERE id = ?",
                STATUS_FAILED, errorMessage, id);
    }

    public void remove(String id) {
        jdbcTemplate.update("DELETE FROM model_report_comparison WHERE id = ?", id);
    }

    /**
     * 全局所有历史报告对比（按创建时间倒序），列表页用，不含大字段。
     */
    public List<Map<String, Object>> getAll() {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT id, judge_model_id, status, error_message, report_ids, comparison_mode, created_at, updated_at " +
                            "FROM model_report_comparison ORDER BY created_at DESC");
            List<Map<String, Object>> result = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                result.add(rowToMap(row));
            }
            return result;
        } catch (Exception e) {
            log.error("查询报告对比列表失败", e);
            return new ArrayList<>();
        }
    }

    private Map<String, Object> rowToMap(Map<String, Object> row) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", row.get("id"));
        map.put("judgeModelId", row.get("judge_model_id"));
        map.put("status", row.get("status"));
        map.put("errorMessage", row.get("error_message"));
        map.put("reportIds", fromJson(row.get("report_ids"), new TypeReference<List<String>>() {}));
        if (row.containsKey("comparison_mode")) {
            map.put("comparisonMode", row.get("comparison_mode"));
        }
        if (row.containsKey("subtype_comparisons")) {
            map.put("subtypeComparisons", fromJson(row.get("subtype_comparisons"),
                    new TypeReference<List<Map<String, Object>>>() {}));
        }
        if (row.containsKey("overall_conclusion")) {
            map.put("overallConclusion", row.get("overall_conclusion"));
        }
        if (row.containsKey("prompt_snapshot")) {
            map.put("promptSnapshot", fromJson(row.get("prompt_snapshot"),
                    new TypeReference<Map<String, Object>>() {}));
        }
        map.put("createdAt", row.get("created_at"));
        map.put("updatedAt", row.get("updated_at"));
        return map;
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("JSON序列化失败", e);
            return null;
        }
    }

    private <T> T fromJson(Object jsonObj, TypeReference<T> typeRef) {
        if (jsonObj == null) return null;
        try {
            String json = jsonObj.toString();
            if (json.trim().isEmpty()) {
                return null;
            }
            return objectMapper.readValue(json, typeRef);
        } catch (Exception e) {
            log.warn("JSON反序列化失败", e);
            return null;
        }
    }
}
