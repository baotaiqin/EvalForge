package com.evalforge.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.web.bind.annotation.RestController;

import com.evalforge.service.ReportComparisonService;
import com.evalforge.service.ReportComparisonStoreService;

/**
 * 模型评测报告对比 —— 选取两份及以上历史报告（同模型不同版本、或不同模型分组）。
 * 按共有子评测类型差异对比生成，前端由轮询接口维护状态。
 */
@RestController
@RequestMapping("/api/report-comparisons")
@CrossOrigin(origins = "*")
public class ReportComparisonController {

    private static final Logger log = LoggerFactory.getLogger(ReportComparisonController.class);

    @Autowired
    private ReportComparisonStoreService comparisonStore;

    @Autowired
    private ReportComparisonService comparisonService;

    @Autowired
    private ExecutorService sseExecutor;

    @GetMapping
    public Map<String, Object> listAll() {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("data", comparisonStore.getAll());
        return result;
    }

    @GetMapping("/{id}")
    public Map<String, Object> getComparison(@PathVariable String id) {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> comparison = comparisonStore.get(id);
        if (comparison == null) {
            result.put("code", 404);
            result.put("message", "对比记录不存在");
            return result;
        }
        result.put("code", 0);
        result.put("data", comparison);
        return result;
    }

    /**
     * 创建对比：body { judgeModelId, reportIds: [reportId, ...], comparisonMode }，至少选择两份报告。
     * comparisonMode 可选，默认 "performance"（模型性能比较）。
     */
    @SuppressWarnings("unchecked")
    @PostMapping
    public Map<String, Object> createComparison(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        String judgeModelId = (String) body.get("judgeModelId");
        List<String> reportIds = (List<String>) body.get("reportIds");
        String comparisonMode = (String) body.getOrDefault("comparisonMode", "performance");

        if (judgeModelId == null || judgeModelId.isBlank()) {
            result.put("code", 400);
            result.put("message", "请选择评审模型");
            return result;
        }
        if (reportIds == null || reportIds.size() < 2) {
            result.put("code", 400);
            result.put("message", "请至少选择两份历史报告进行对比");
            return result;
        }

        try {
            String comparisonId = comparisonStore.nextId();
            comparisonStore.create(comparisonId, judgeModelId, new ArrayList<>(reportIds), comparisonMode);
            sseExecutor.submit(() -> {
                try {
                    comparisonService.generate(comparisonId, judgeModelId, reportIds, comparisonMode);
                } catch (Throwable e) {
                    log.error("报告对比生成异常: comparisonId={}", comparisonId, e);
                    comparisonStore.markFailed(comparisonId, "生成对比失败：" + e.getMessage());
                }
            });
            result.put("code", 0);
            result.put("data", comparisonStore.get(comparisonId));
        } catch (Exception e) {
            log.error("创建报告对比失败", e);
            result.put("code", 500);
            result.put("message", "创建对比失败：" + e.getMessage());
        }
        return result;
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> deleteComparison(@PathVariable String id) {
        Map<String, Object> result = new HashMap<>();
        comparisonStore.remove(id);
        result.put("code", 0);
        return result;
    }
}
