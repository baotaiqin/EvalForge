package com.qatools.service;

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
 * 模型测评报告存储服务 — 归属于单个模型分组，选取分组下子评测的某一版本记录，
 * 由 LLM 生成“子评测评价 + 模型总评”的综合报告。表结构需手动建表（model_report），
 * 见部署文档说明，@PostConstruct 只负责已有表的兼容性检查，不会自动创建新表。
 */
@Service
public class ModelReportStoreService {

    private static final Logger log = LoggerFactory.getLogger(ModelReportStoreService.class);

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
            Integer max = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(MAX(CAST(SUBSTRING_INDEX(id, '-', -1) AS UNSIGNED)), 0) FROM model_report",
                    Integer.class);
            if (max != null) {
                idCounter.set(max + 1);
            }
        } catch (Exception e) {
            log.warn("初始化模型测评报告ID计数器失败（表可能尚未创建，需手动建表）: {}", e.getMessage());
        }
    }

    public String nextId() {
        return "report-" + idCounter.getAndIncrement();
    }

    public Map<String, Object> get(String id) {
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM model_report WHERE id = ?", id);
            return rowToMap(row);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 创建报告初始记录（status=generating），由 Controller 触发异步生成前先落库占位。
     */
    public void create(String id, String groupId, String judgeModelId, List<Map<String, Object>> selections) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO model_report (id, group_id, judge_model_id, status, selections) VALUES (?, ?, ?, ?, ?)",
                    id, groupId, judgeModelId, STATUS_GENERATING, toJson(selections));
        } catch (Exception e) {
            log.error("创建模型测评报告失败: id={}", id, e);
            throw new RuntimeException("创建模型测评报告失败", e);
        }
    }

    /**
     * 报告生成成功：写入各子评测评价、总评、实际使用的提示词快照，状态置为 completed。
     */
    public void markCompleted(String id, List<Map<String, Object>> subEvalSummaries,
            String overallSummary, Map<String, Object> promptSnapshot) {
        jdbcTemplate.update(
                "UPDATE model_report SET status = ?, sub_eval_summaries = ?, overall_summary = ?, prompt_snapshot = ?, error_message = NULL WHERE id = ?",
                STATUS_COMPLETED, toJson(subEvalSummaries), overallSummary, toJson(promptSnapshot), id);
    }

    public void markFailed(String id, String errorMessage) {
        jdbcTemplate.update(
                "UPDATE model_report SET status = ?, error_message = ? WHERE id = ?",
                STATUS_FAILED, errorMessage, id);
    }

    public void remove(String id) {
        jdbcTemplate.update("DELETE FROM model_report WHERE id = ?", id);
    }

    /**
     * 该分组下所有历史报告（按创建时间倒序），列表页用，不含大字段。
     */
    public List<Map<String, Object>> getByGroup(String groupId) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT id, group_id, judge_model_id, status, error_message, selections, created_at, updated_at " +
                            "FROM model_report WHERE group_id = ? ORDER BY created_at DESC",
                    groupId);
            List<Map<String, Object>> result = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                result.add(rowToMap(row));
            }
            return result;
        } catch (Exception e) {
            log.error("查询分组下模型测评报告列表失败: groupId={}", groupId, e);
            return new ArrayList<>();
        }
    }

    /**
     * 全局所有历史报告（按创建时间倒序），列表页用，不含大字段。供报告对比选择用。
     */
    public List<Map<String, Object>> getAll() {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT id, group_id, judge_model_id, status, error_message, selections, created_at, updated_at " +
                            "FROM model_report ORDER BY created_at DESC");
            List<Map<String, Object>> result = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                result.add(rowToMap(row));
            }
            return result;
        } catch (Exception e) {
            log.error("查询全局模型测评报告列表失败", e);
            return new ArrayList<>();
        }
    }

    private Map<String, Object> rowToMap(Map<String, Object> row) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", row.get("id"));
        map.put("groupId", row.get("group_id"));
        map.put("judgeModelId", row.get("judge_model_id"));
        map.put("status", row.get("status"));
        map.put("errorMessage", row.get("error_message"));
        map.put("selections", fromJson(row.get("selections"), new TypeReference<List<Map<String, Object>>>() {}));
        if (row.containsKey("sub_eval_summaries")) {
            map.put("subEvalSummaries", fromJson(row.get("sub_eval_summaries"), new TypeReference<List<Map<String, Object>>>() {}));
        }
        if (row.containsKey("overall_summary")) {
            map.put("overallSummary", row.get("overall_summary"));
        }
        if (row.containsKey("prompt_snapshot")) {
            map.put("promptSnapshot", fromJson(row.get("prompt_snapshot"), new TypeReference<Map<String, Object>>() {}));
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
