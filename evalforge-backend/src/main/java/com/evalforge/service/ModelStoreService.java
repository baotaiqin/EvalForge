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
 * 模型数据存储服务 — 基于 MySQL 持久化
 */
@Service
public class ModelStoreService {

    private static final Logger log = LoggerFactory.getLogger(ModelStoreService.class);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicInteger idCounter = new AtomicInteger(1);

    @PostConstruct
    public void init() {
        try {
            Integer max = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(MAX(CAST(SUBSTRING_INDEX(id, '_', -1) AS UNSIGNED)), 0) FROM model_config",
                    Integer.class);
            if (max != null) {
                idCounter.set(max + 1);
            }
        } catch (Exception e) {
            log.warn("初始化模型ID计数器失败，使用默认值: {}", e.getMessage());
        }
    }

    public String nextId() {
        return "model_" + idCounter.getAndIncrement();
    }

    public Map<String, Object> get(String id) {
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM model_config WHERE id = ?", id);
            return rowToMap(row);
        } catch (Exception e) {
            return null;
        }
    }

    public void put(String id, Map<String, Object> model) {
        try {
            String name = (String) model.get("name");
            String provider = (String) model.get("provider");
            String baseUrl = (String) model.get("baseUrl");
            String apiKey = (String) model.get("apiKey");
            String params = toJson(model.get("params"));
            String createdAt = (String) model.get("createdAt");

            jdbcTemplate.update(
                    "INSERT INTO model_config (id, name, provider, base_url, api_key, params, created_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, COALESCE(?, CURRENT_TIMESTAMP)) " +
                            "ON DUPLICATE KEY UPDATE name=VALUES(name), provider=VALUES(provider), " +
                            "base_url=VALUES(base_url), api_key=VALUES(api_key), params=VALUES(params), " +
                            "created_at=VALUES(created_at)",
                    id, name, provider, baseUrl, apiKey, params, createdAt);
        } catch (Exception e) {
            log.error("保存模型配置失败: id={}", id, e);
            throw new RuntimeException("保存模型配置失败", e);
        }
    }

    public void remove(String id) {
        jdbcTemplate.update("DELETE FROM model_config WHERE id = ?", id);
    }

    public List<Map<String, Object>> getAll() {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT * FROM model_config ORDER BY created_at DESC");
            List<Map<String, Object>> result = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                result.add(rowToMap(row));
            }
            return result;
        } catch (Exception e) {
            log.error("查询模型列表失败", e);
            return new ArrayList<>();
        }
    }

    private Map<String, Object> rowToMap(Map<String, Object> row) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", row.get("id"));
        map.put("name", row.get("name"));
        map.put("provider", row.get("provider"));
        map.put("baseUrl", row.get("base_url"));
        map.put("apiKey", row.get("api_key"));
        map.put("params", fromJson(row.get("params"), new TypeReference<Map<String, Object>>() {}));
        map.put("createdAt", row.get("created_at"));
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
            return objectMapper.readValue(jsonObj.toString(), typeRef);
        } catch (Exception e) {
            log.warn("JSON反序列化失败", e);
            return null;
        }
    }
}
