package com.evalforge.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 模型分组存储服务 —— “模型测评”左侧菜单下的分组，纯组织归类，不参与实际测评执行。
 */
@Service
public class ModelGroupStoreService {

    private static final Logger log = LoggerFactory.getLogger(ModelGroupStoreService.class);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final AtomicInteger idCounter = new AtomicInteger(1);

    @PostConstruct
    public void init() {
        try {
            Integer max = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(MAX(CAST(SUBSTRING_INDEX(id, '_', -1) AS UNSIGNED)), 0) FROM model_group",
                    Integer.class);
            if (max != null) idCounter.set(max + 1);
        } catch (Exception e) {
            log.warn("初始化模型分组ID计数器失败，使用默认值: {}", e.getMessage());
        }
    }



    public String nextId() {
        return "group_" + idCounter.getAndIncrement();
    }

    public Map<String, Object> get(String id) {
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM model_group WHERE id = ?", id);
            return rowToMap(row);
        } catch (Exception e) {
            return null;
        }
    }

    public void put(String id, Map<String, Object> group) {
        String name = asString(group.get("name"));
        String remark = asString(group.get("remark"));
        // created 来自 get() 返回的原始值（通常保存字符串），MySQL DATETIME 列
        // JdbcTemplate 映射为 LocalDateTime 时直接传回去可能导致 preparedStatement 类型不兼容，显式转回字符串让 SQL 层解析
        String createdAt = asString(group.get("created_at"));

        try {
            jdbcTemplate.update(
                    "INSERT INTO model_group (id, name, remark, created_at) VALUES (?, ?, ?, COALESCE(?, CURRENT_TIMESTAMP)) " +
                            "ON DUPLICATE KEY UPDATE name=VALUES(name), remark=VALUES(remark), created_at=VALUES(created_at)",
                    id, name, remark, createdAt);
        } catch (Exception e) {
            log.error("保存模型分组失败: id={}", id, e);
            throw new RuntimeException("保存模型分组失败", e);
        }
    }

    public void remove(String id) {
        jdbcTemplate.update("DELETE FROM model_group WHERE id = ?", id);
    }

    public List<Map<String, Object>> getAll() {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT * FROM model_group ORDER BY created_at DESC");
            List<Map<String, Object>> result = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                result.add(rowToMap(row));
            }
            return result;
        } catch (Exception e) {
            log.error("查询模型分组列表失败", e);
            return new ArrayList<>();
        }
    }

    private Map<String, Object> rowToMap(Map<String, Object> row) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", row.get("id"));
        map.put("name", row.get("name"));
        map.put("remark", row.get("remark"));
        map.put("created_at", row.get("created_at"));
        map.put("updated_at", row.get("updated_at"));
        return map;
    }

    /**
     * 安全转字符串，兼容数据库回读值为 LocalDateTime 等非 String 类型的场景
     * （直接强转 String 会 ClassCastException，见 put() 编辑场景下 created_at 的处理）。
     */
    private String asString(Object value) {
        return value == null ? null : value.toString();
    }
}
