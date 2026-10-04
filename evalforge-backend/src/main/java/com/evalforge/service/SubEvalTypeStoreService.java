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
 * 子评测类型存储服务 — 全局共享的类型定义（如 CP低良率评测/UTC评测/AI-ptp评测/SemiMind评测——），
 * 用户可自由新建/改名/删除，不再区分“是否已挂载”。所有类型均复用同一套 Agent 评测执行链路
 *（EvaluationService.executeStreaming()），类型本身只是组织/展示用的标签。
 */
@Service
public class SubEvalTypeStoreService {

    private static final Logger log = LoggerFactory.getLogger(SubEvalTypeStoreService.class);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final AtomicInteger idCounter = new AtomicInteger(1);

    @PostConstruct
    public void init() {
        try {
            // 预置手写数据使用固定字符串 id（如 cp_low_yield），只统计用户新建类型(type-N 格式)的最大序号，
            // 以避免 CAST 非数字后缀字符串导致异常
            Integer max = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(MAX(CAST(SUBSTRING_INDEX(id, '-', -1) AS UNSIGNED)), 0) " +
                            "FROM sub_eval_type WHERE id LIKE 'type-%'",
                    Integer.class);
            if (max != null) {
                idCounter.set(max + 1);
            }
        } catch (Exception e) {
            log.warn("初始化子评测类型ID计数器失败，使用默认值：{}", e.getMessage());
        }
    }

    public String nextId() {
        return "type-" + idCounter.getAndIncrement();
    }

    public Map<String, Object> get(String id) {
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM sub_eval_type WHERE id = ?", id);
            return rowToMap(row);
        } catch (Exception e) {
            return null;
        }
    }

    public void put(String id, Map<String, Object> type) {
        try {
            String name = (String) type.get("name");
            jdbcTemplate.update(
                    "INSERT INTO sub_eval_type (id, name) VALUES (?, ?) " +
                            "ON DUPLICATE KEY UPDATE name=VALUES(name)",
                    id, name);
        } catch (Exception e) {
            log.error("保存子评测类型失败: id={}", id, e);
            throw new RuntimeException("保存子评测类型失败", e);
        }
    }

    public void remove(String id) {
        jdbcTemplate.update("DELETE FROM sub_eval_type WHERE id = ?", id);
    }

    public List<Map<String, Object>> getAll() {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT * FROM sub_eval_type ORDER BY created_at ASC");
            List<Map<String, Object>> result = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                result.add(rowToMap(row));
            }
            return result;
        } catch (Exception e) {
            log.error("查询子评测类型列表失败", e);
            return new ArrayList<>();
        }
    }

    private Map<String, Object> rowToMap(Map<String, Object> row) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", row.get("id"));
        map.put("name", row.get("name"));
        map.put("createdAt", row.get("created_at"));
        map.put("updatedAt", row.get("updated_at"));
        return map;
    }
}
