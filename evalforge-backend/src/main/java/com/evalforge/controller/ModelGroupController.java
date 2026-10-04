package com.evalforge.controller;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

import com.evalforge.service.ModelGroupStoreService;
import com.evalforge.service.SubEvaluationStoreService;

/**
 * “模型测评”左侧菜单下的模型分组管理 — 纯组织归类，不参与实际评测执行。
 */
@RestController
@RequestMapping("/api/model-groups")
@CrossOrigin(origins = "*")
public class ModelGroupController {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private ModelGroupStoreService groupStore;

    @Autowired
    private SubEvaluationStoreService subEvalStore;

    @GetMapping
    public Map<String, Object> listGroups() {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("data", groupStore.getAll());
        return result;
    }

    @GetMapping("/{id}")
    public Map<String, Object> getGroup(@PathVariable String id) {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> group = groupStore.get(id);
        if (group == null) {
            result.put("code", 404);
            result.put("message", "模型分组不存在");
            return result;
        }
        result.put("code", 0);
        result.put("data", group);
        return result;
    }

    @PostMapping
    public Map<String, Object> createGroup(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        try {
            String id = groupStore.nextId();
            Map<String, Object> group = new HashMap<>(body);
            group.put("id", id);
            group.put("createdAt", LocalDateTime.now().format(FMT));
            groupStore.put(id, group);
            result.put("code", 0);
            result.put("data", group);
        } catch (Exception e) {
            result.put("code", 500);
            result.put("message", "创建模型分组失败：" + e.getMessage());
        }
        return result;
    }

    @PutMapping("/{id}")
    public Map<String, Object> updateGroup(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> group = groupStore.get(id);
        if (group == null) {
            result.put("code", 404);
            result.put("message", "模型分组不存在");
            return result;
        }
        group.putAll(body);
        group.put("id", id);
        groupStore.put(id, group);
        result.put("code", 0);
        result.put("data", group);
        return result;
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> deleteGroup(@PathVariable String id) {
        Map<String, Object> result = new HashMap<>();
        groupStore.remove(id);
        result.put("code", 0);
        return result;
    }

    /**
     * 复制模型分组：整体克隆分组本身 + 其下所有子评测的完整配置（含被测对象/数据集/评判模型等具体引用），
     * 生成一份独立的新分组供用户直接在此基础上修改，不需要从零重新配置。
     * 不复制历史执行记录（eval_record），新分组的子评测版本号从 0 开始。
     */
    @PostMapping("/{id}/duplicate")
    public Map<String, Object> duplicateGroup(@PathVariable String id) {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> source = groupStore.get(id);
        if (source == null) {
            result.put("code", 404);
            result.put("message", "模型分组不存在");
            return result;
        }

        try {
            String newGroupId = groupStore.nextId();
            String newName = resolveUniqueCopyName(String.valueOf(source.get("name")));

            Map<String, Object> newGroup = new HashMap<>();
            newGroup.put("id", newGroupId);
            newGroup.put("name", newName);
            newGroup.put("remark", source.get("remark"));
            newGroup.put("createdAt", LocalDateTime.now().format(FMT));
            groupStore.put(newGroupId, newGroup);

            for (Map<String, Object> subEval : subEvalStore.getByGroup(id)) {
                String newSubEvalId = subEvalStore.nextId();
                Map<String, Object> copy = new HashMap<>(subEval);
                copy.put("id", newSubEvalId);
                copy.put("groupId", newGroupId);
                copy.remove("latestVersion");
                copy.remove("createdAt");
                copy.remove("updatedAt");
                subEvalStore.put(newSubEvalId, copy);
            }

            result.put("code", 0);
            result.put("data", groupStore.get(newGroupId));
        } catch (Exception e) {
            result.put("code", 500);
            result.put("message", "复制模型分组失败：" + e.getMessage());
        }
        return result;
    }

    /**
     * 生成不与现有分组重名的“xxx 副本”名称，重复则自动编号“xxx 副本2”/“xxx 副本3”…
     */
    private String resolveUniqueCopyName(String sourceName) {
        String base = (sourceName == null ? "分组" : sourceName) + " 副本";
        Set<String> existingNames = new HashSet<>();
        for (Map<String, Object> g : groupStore.getAll()) {
            existingNames.add(String.valueOf(g.get("name")));
        }
        if (!existingNames.contains(base)) {
            return base;
        }
        int suffix = 2;
        while (existingNames.contains(base + suffix)) {
            suffix++;
        }
        return base + suffix;
    }

    /**
     * 该分组下所有子评测（不含历史版本记录，仅配置概要）。
     */
    @GetMapping("/{id}/sub-evaluations")
    public Map<String, Object> listSubEvaluations(@PathVariable String id) {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> list = subEvalStore.getByGroup(id);
        result.put("code", 0);
        result.put("data", list);
        return result;
    }
}
