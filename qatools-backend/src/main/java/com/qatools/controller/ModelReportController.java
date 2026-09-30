package com.qatools.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.qatools.service.ModelReportService;
import com.qatools.service.ModelReportStoreService;

/**
 * 模型评测报告 —— 归属于单个模型分组，选取分组下各子评测的第一版本记录。
 */
@RestController
@RequestMapping("/api/model-reports")
@CrossOrigin(origins = "*")
public class ModelReportController {

    private static final Logger log = LoggerFactory.getLogger(ModelReportController.class);

    @Autowired
    private ModelReportStoreService reportStore;

    @Autowired
    private ModelReportService reportService;

    @Autowired
    private ExecutorService sseExecutor;

    @GetMapping
    public Map<String, Object> listByGroup(@RequestParam(required = false) String groupId) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        if (groupId != null && !groupId.isBlank()) {
            result.put("data", reportStore.getByGroup(groupId));
        } else {
            result.put("data", reportStore.getAll());
        }
        return result;
    }

    @GetMapping("/{id}")
    public Map<String, Object> getReport(@PathVariable String id) {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> report = reportStore.get(id);
        if (report == null) {
            result.put("code", 404);
            result.put("message", "报告不存在");
            return result;
        }
        result.put("code", 0);
        result.put("data", report);
        return result;
    }

    /**
     * 创建报告：body { groupId, judgeModelId, selections: [{subEvalId, evalId}] }。
     * 按每个 subEvalId 只能选一个版本（同一子评测不能重复选择），评审后异步生成。
     */
    @SuppressWarnings("unchecked")
    @PostMapping
    public Map<String, Object> createReport(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        String groupId = (String) body.get("groupId");
        String judgeModelId = (String) body.get("judgeModelId");
        List<Map<String, Object>> selections = (List<Map<String, Object>>) body.get("selections");

        if (groupId == null || groupId.isBlank()) {
            result.put("code", 400);
            result.put("message", "缺少模型分组");
            return result;
        }
        if (judgeModelId == null || judgeModelId.isBlank()) {
            result.put("code", 400);
            result.put("message", "请选择评审模型");
            return result;
        }
        if (selections == null || selections.isEmpty()) {
            result.put("code", 400);
            result.put("message", "请至少选择一个评测版本");
            return result;
        }

        Set<String> seenSubEvalIds = new HashSet<>();
        for (Map<String, Object> selection : selections) {
            String subEvalId = String.valueOf(selection.get("subEvalId"));
            if (!seenSubEvalIds.add(subEvalId)) {
                result.put("code", 400);
                result.put("message", "同一个子评测只能选择一个版本");
                return result;
            }
        }

        try {
            String reportId = reportStore.nextId();
            reportStore.create(reportId, groupId, judgeModelId, new ArrayList<>(selections));
            sseExecutor.submit(() -> {
                try {
                    reportService.generate(reportId, judgeModelId, selections);
                } catch (Throwable e) {
                    log.error("模型评测报告生成异常: reportId={}", reportId, e);
                    reportStore.markFailed(reportId, "生成报告失败：" + e.getMessage());
                }
            });
            result.put("code", 0);
            result.put("data", reportStore.get(reportId));
        } catch (Exception e) {
            log.error("创建模型评测报告失败", e);
            result.put("code", 500);
            result.put("message", "创建报告失败：" + e.getMessage());
        }
        return result;
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> deleteReport(@PathVariable String id) {
        Map<String, Object> result = new HashMap<>();
        reportStore.remove(id);
        result.put("code", 0);
        return result;
    }
}
