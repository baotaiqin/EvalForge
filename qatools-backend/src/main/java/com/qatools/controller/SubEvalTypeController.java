package com.qatools.controller;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.qatools.service.SubEvalTypeStoreService;

/**
 * “模型测评”子评测类型管理 — 全局共享，用户可自由新建/改名/删除。
 * 删除个做级联检查（与 model_group 删除处理方式一致，软引用）；若已被某个评测引用，
 * 该子评测在前端展示层仍可见。
 */
@RestController
@RequestMapping("/api/sub-eval-types")
@CrossOrigin(origins = "*")
public class SubEvalTypeController {

    @Autowired
    private SubEvalTypeStoreService typeStore;

    @GetMapping
    public Map<String, Object> listTypes() {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("data", typeStore.getAll());
        return result;
    }

    @PostMapping
    public Map<String, Object> createType(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        String name = body.get("name") == null ? null : body.get("name").toString().trim();
        if (name == null || name.isEmpty()) {
            result.put("code", 400);
            result.put("message", "类型名称不能为空");
            return result;
        }
        try {
            String id = typeStore.nextId();
            Map<String, Object> type = new HashMap<>();
            type.put("name", name);
            typeStore.put(id, type);
            result.put("code", 0);
            result.put("data", typeStore.get(id));
        } catch (Exception e) {
            result.put("code", 500);
            result.put("message", "创建类型失败：" + e.getMessage());
        }
        return result;
    }

    @PutMapping("/{id}")
    public Map<String, Object> updateType(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> type = typeStore.get(id);
        if (type == null) {
            result.put("code", 404);
            result.put("message", "类型不存在");
            return result;
        }
        String name = body.get("name") == null ? null : body.get("name").toString().trim();
        if (name == null || name.isEmpty()) {
            result.put("code", 400);
            result.put("message", "类型名称不能为空");
            return result;
        }
        type.put("name", name);
        typeStore.put(id, type);
        result.put("code", 0);
        result.put("data", typeStore.get(id));
        return result;
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> deleteType(@PathVariable String id) {
        Map<String, Object> result = new HashMap<>();
        typeStore.remove(id);
        result.put("code", 0);
        return result;
    }
}
