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
 * 子评测配置存储服务 — "模型测评"分组下的子评测（如 CP低良率评测/UTC评测/AI-ptp评测）。
 * 子评测本身是配置模板：一次性固化被测对象(targets)/数据集(datasetIds)等，
 * 每次"开始测评"复用该配置生成一条新的 eval_record，版本号(latest_version)递增。
 */
@Service
public class SubEvaluationStoreService {

    private static final Logger log = LoggerFactory.getLogger(SubEvaluationStoreService.class);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicInteger idCounter = new AtomicInteger(1);

    @PostConstruct
    public void init() {
        try {
            Integer max = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(MAX(CAST(SUBSTRING_INDEX(id, '-', -1) AS UNSIGNED)), 0) FROM sub_evaluation",
                    Integer.class);
            if (max != null) {
                idCounter.set(max + 1);
            }
        } catch (Exception e) {
            log.warn("初始化子评测ID计数器失败，使用默认值：{}", e.getMessage());
        }

        // 应用层 checkDuplicateType 只是 check-then-act，这里补一道数据库唯一索引兜底，
        // 防止并发请求下产生同一分组同类型的重复子评测；索引已存在或已有重复数据导致创建失败时静默忽略
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE sub_evaluation ADD UNIQUE INDEX uk_sub_evaluation_group_type (group_id, sub_type)");
            log.info("sub_evaluation 表新增唯一索引成功: uk_sub_evaluation_group_type");
        } catch (Exception e) {
            log.debug("uk_sub_evaluation_group_type 索引已存在或添加失败: {}", e.getMessage());
        }
    }

    public String nextId() {
        return "subeval-" + idCounter.getAndIncrement();
    }

    public Map<String, Object> get(String id) {
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM sub_evaluation WHERE id = ?", id);
            return rowToMap(row);
        } catch (Exception e) {
            return null;
        }
    }

    public void put(String id, Map<String, Object> subEval) {
        try {
            String groupId = asString(subEval.get("groupId"));
            String name = asString(subEval.get("name"));
            String subType = asString(subEval.get("subType"));
            String targetType = asString(subEval.get("targetType"));
            String judgeMode = asString(subEval.get("judgeMode"));
            String judgeModelId = asString(subEval.get("judgeModelId"));
            String remark = asString(subEval.get("remark"));
            // createdAt 若来自 get() 回读的旧配置（如编辑保存场景），MySQL DATETIME 列经
            // JdbcTemplate 映射为 LocalDateTime 对象而非字符串，需先转字符串再交给 SQL 层解析
            String createdAt = asString(subEval.get("createdAt"));

            String targetsJson = toJson(subEval.get("targets"));
            String datasetIdsJson = toJson(subEval.get("datasetIds"));
            String datasetNamesJson = toJson(subEval.get("datasetNames"));
            String datasetMappingJson = toJson(subEval.get("datasetMapping"));

            jdbcTemplate.update(
                    "INSERT INTO sub_evaluation (" +
                            "id, group_id, name, sub_type, target_type, targets, " +
                            "dataset_ids, dataset_names, dataset_mapping, judge_mode, judge_model_id, remark, created_at" +
                            ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, COALESCE(?, CURRENT_TIMESTAMP))" +
                            "ON DUPLICATE KEY UPDATE " +
                            "name=VALUES(name), sub_type=VALUES(sub_type), target_type=VALUES(target_type), " +
                            "targets=VALUES(targets), dataset_ids=VALUES(dataset_ids), dataset_names=VALUES(dataset_names), " +
                            "dataset_mapping=VALUES(dataset_mapping), judge_mode=VALUES(judge_mode), " +
                            "judge_model_id=VALUES(judge_model_id), remark=VALUES(remark)",
                    id, groupId, name, subType, targetType, targetsJson,
                    datasetIdsJson, datasetNamesJson, datasetMappingJson, judgeMode, judgeModelId, remark, createdAt);
        } catch (Exception e) {
            log.error("保存子评测配置失败: id={}", id, e);
            throw new RuntimeException("保存子评测配置失败", e);
        }
    }

    public void remove(String id) {
        jdbcTemplate.update("DELETE FROM sub_evaluation WHERE id = ?", id);
    }

    /**
     * 同一分组下每种类型只能存在一条子评测；excludeId 在编辑场景下排除自身。
     * 只查 id 列，避免为了查重把整行(含 targets/datasetIds 等 JSON 字段)取出来反序列化。
     */
    public boolean existsByGroupAndType(String groupId, String subType, String excludeId) {
        try {
            List<String> ids = jdbcTemplate.queryForList(
                    "SELECT id FROM sub_evaluation WHERE group_id = ? AND sub_type = ?",
                    String.class, groupId, subType);
            for (String id : ids) {
                if (!id.equals(excludeId)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            log.error("查重子评测类型失败: groupId={}, subType={}", groupId, subType, e);
            return false;
        }
    }

    public List<Map<String, Object>> getByGroup(String groupId) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT * FROM sub_evaluation WHERE group_id = ? ORDER BY created_at DESC", groupId);
            List<Map<String, Object>> result = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                result.add(rowToMap(row));
            }
            return result;
        } catch (Exception e) {
            log.error("查询分组下子评测列表失败: groupId={}", groupId, e);
            return new ArrayList<>();
        }
    }

    public List<Map<String, Object>> getAll() {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT * FROM sub_evaluation ORDER BY created_at DESC");
            List<Map<String, Object>> result = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                result.add(rowToMap(row));
            }
            return result;
        } catch (Exception e) {
            log.error("查询子评测列表失败", e);
            return new ArrayList<>();
        }
    }

    /**
     * 版本号自增并返回自增后的新版本号，用于"开始测评"时生成新一轮 eval_record。
     */
    public synchronized int incrementVersion(String id) {
        jdbcTemplate.update("UPDATE sub_evaluation SET latest_version = latest_version + 1 WHERE id = ?", id);
        Integer version = jdbcTemplate.queryForObject(
                "SELECT latest_version FROM sub_evaluation WHERE id = ?", Integer.class, id);
        return version == null ? 1 : version;
    }

    /**
     * 按 eval_record 实际剩余的最大版本号重新校准 latest_version，用于删除某个版本记录后
     *（尤其是删除最新版本时）让下一次"开始测评"从正确的版本号继续，而不是无限递增。
     * 全部记录都被删除时归零，下次从 v1 重新开始。
     */
    public synchronized void syncLatestVersion(String id) {
        Integer maxVersion = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(sub_eval_version), 0) FROM eval_record WHERE sub_eval_id = ?",
                Integer.class, id);
        jdbcTemplate.update("UPDATE sub_evaluation SET latest_version = ? WHERE id = ?", maxVersion, id);
    }

    private Map<String, Object> rowToMap(Map<String, Object> row) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", row.get("id"));
        map.put("groupId", row.get("group_id"));
        map.put("name", row.get("name"));
        map.put("subType", row.get("sub_type"));
        map.put("targetType", row.get("target_type"));
        map.put("targets", fromJson(row.get("targets"), new TypeReference<List<Map<String, Object>>>() {}));
        map.put("datasetIds", fromJson(row.get("dataset_ids"), new TypeReference<List<String>>() {}));
        map.put("datasetNames", fromJson(row.get("dataset_names"), new TypeReference<List<String>>() {}));
        map.put("datasetMapping", fromJson(row.get("dataset_mapping"), new TypeReference<Map<String, Object>>() {}));
        map.put("judgeMode", row.get("judge_mode"));
        map.put("judgeModelId", row.get("judge_model_id"));
        map.put("remark", row.get("remark"));
        Object latestVersion = row.get("latest_version");
        map.put("latestVersion", latestVersion == null ? 0 : latestVersion);
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

    /**
     * 安全字符串转换，兼容数据库回读值为 LocalDateTime 等非 String 类型的场景
     *（直接强转 (String) 会抛 ClassCastException，见 put() 编辑场景下 createdAt 的坑）。
     */
    private String asString(Object value) {
        return value == null ? null : value.toString();
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
