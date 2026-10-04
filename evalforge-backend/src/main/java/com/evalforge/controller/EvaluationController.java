package com.evalforge.controller;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.evalforge.service.DatasetStoreService;
import com.evalforge.service.EvaluationCancellationRegistry;
import com.evalforge.service.EvaluationLifecycleHelper;
import com.evalforge.service.EvaluationProgressBroadcaster;
import com.evalforge.service.EvaluationService;
import com.evalforge.service.EvaluationStatusBroadcaster;
import com.evalforge.service.EvaluationStoreService;

import jakarta.servlet.http.HttpServletResponse;

@RestController
@RequestMapping("/api/evaluations")
@CrossOrigin(origins = "*")
public class EvaluationController {

    private static final Logger log = LoggerFactory.getLogger(EvaluationController.class);

    @Autowired
    private EvaluationStoreService evalStore;

    @Autowired
    private EvaluationService evaluationService;

    @Autowired
    private DatasetStoreService datasetStore;

    @Autowired
    private EvaluationStatusBroadcaster statusBroadcaster;

    @Autowired
    private EvaluationProgressBroadcaster progressBroadcaster;

    @Autowired
    private EvaluationCancellationRegistry cancellationRegistry;

    @Autowired
    private EvaluationLifecycleHelper lifecycle;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private ExecutorService sseExecutor;

    /**
     * 获取评测记录列表（不含 results 详情）
     */
    @GetMapping
    public Map<String, Object> listEvaluations() {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> eval : evalStore.getAll()) {
            Map<String, Object> brief = new HashMap<>(eval);
            brief.remove("results");
            list.add(brief);
        }
        result.put("code", 0);
        result.put("data", list);
        return result;
    }

    /**
     * 获取单个评测记录详情（含 results）
     */
    @GetMapping("/{id}")
    public Map<String, Object> getEvaluation(@PathVariable String id) {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> eval = evalStore.get(id);
        if (eval == null) {
            result.put("code", 404);
            result.put("message", "评测记录不存在");
            return result;
        }
        result.put("code", 0);
        result.put("data", eval);
        return result;
    }

    /**
     * 获取评测记录状态变化推送（SSE）。
     * 前端列表页订阅该端点，任意一条评测记录状态变化(running/completed/failed)时会收到一条
     * event: status - {"evalId":"...", "status":"..."} 事件，收到后应重新拉取列表刷新展示，
     * 可用于替代原先 /api/evaluations 的定时轮询。
     */
    @GetMapping("/stream/status")
    public SseEmitter streamStatus() {
        return statusBroadcaster.subscribe();
    }

    /**
     * 订阅指定评测记录的实时逐题进度（SSE）。
     * 详情页在评测状态为 running 时订阅该端点，收到 progress/question/complete 事件后
     * 增量更新页面展示，不要求是发起评测的浏览器标签页，也无需刷新整个页面。
     */
    @GetMapping("/{id}/stream/progress")
    public SseEmitter streamProgress(@PathVariable String id) {
        return progressBroadcaster.subscribe(id);
    }

    /**
     * 创建评测记录并异步执行评测
     */
    @PostMapping
    public Map<String, Object> createEvaluation(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        try {
            String id = evalStore.nextId();
            Map<String, Object> eval = new HashMap<>(body);
            eval.put("id", id);
            eval.put("status", EvaluationStatusBroadcaster.STATUS_RUNNING);
            eval.put("startTime", LocalDateTime.now().format(FMT));
            eval.putIfAbsent("endTime", null);
            eval.putIfAbsent("accuracy", null);
            eval.putIfAbsent("results", new ArrayList<>());
            lifecycle.normalizeEvaluationMetadata(eval);
            evalStore.put(id, eval);
            statusBroadcaster.broadcast(id, EvaluationStatusBroadcaster.STATUS_RUNNING);

            // 异步执行评测：本轮不改执行链路，仍然由 EvaluationService 原入口处理
            evaluationService.executeAsync(id);

            result.put("code", 0);
            result.put("data", eval);
        } catch (Exception e) {
            result.put("code", 500);
            result.put("message", "创建评测记录失败：" + e.getMessage());
        }
        return result;
    }

    /**
     * SSE 流式评测 - 创建评测记录并通过 SSE 逐题推送结果
     *
     * SSE 事件协议：
     * event: init     - {"evalId":"...", "total": 10} 评测初始化
     * event: progress - {"questionIndex":0, "total":10, "message":"处理第1/10道题"}
     * event: result   - {"questionIndex":0, "targetId":"...", "responseTime":1234}
     * event: score    - {"questionIndex":0, "scores":{...}}
     * event: complete  - {"evalId":"...", "status":"completed", "accuracy":0.85}
     * event: error    - {"message":"..."}
     */
    @PostMapping("/stream")
    public SseEmitter streamEvaluation(@RequestBody Map<String, Object> body, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        SseEmitter emitter = new SseEmitter(60 * 60 * 1000L);
        try {
            String id = evalStore.nextId();
            Map<String, Object> eval = new HashMap<>(body);
            eval.put("id", id);
            eval.put("status", EvaluationStatusBroadcaster.STATUS_RUNNING);
            eval.put("startTime", LocalDateTime.now().format(FMT));
            eval.putIfAbsent("endTime", null);
            eval.putIfAbsent("accuracy", null);
            eval.putIfAbsent("results", new ArrayList<>());
            lifecycle.normalizeEvaluationMetadata(eval);
            evalStore.put(id, eval);
            statusBroadcaster.broadcast(id, EvaluationStatusBroadcaster.STATUS_RUNNING);

            sseExecutor.submit(() -> {
                try {
                    evaluationService.executeStreaming(id, emitter);
                    emitter.complete();
                } catch (Throwable e) {
                    log.error("流式评测失败: evalId={}, errorType={}, error={}", id,
                            e.getClass().getName(), e.getMessage(), e);
                    Map<String, Object> failedEval = evalStore.get(id);
                    if (failedEval != null && EvaluationStatusBroadcaster.STATUS_RUNNING.equals(failedEval.get("status"))) {
                        lifecycle.markFailed(failedEval, "执行线程异常：" + e.getMessage());
                    }
                    try {
                        String errMsg = e.getMessage() != null ? e.getMessage() : "未知错误";
                        if (errMsg.length() > 200) {
                            errMsg = errMsg.substring(0, 200) + "...";
                        }
                        emitter.send(SseEmitter.event().name("error")
                                .data("{\"message\":\"" + errMsg.replace("\"", "'") + "\"}",
                                        org.springframework.http.MediaType.TEXT_PLAIN));
                    } catch (Exception ignored) {
                    }
                    emitter.completeWithError(e);
                }
            });
            return emitter;
        } catch (Exception e) {
            log.error("创建流式评测失败：{}", e.getMessage());
            emitter.completeWithError(e);
            return emitter;
        }
    }

    /**
     * 终止一条正在执行(running)的评测记录。
     * 若后台尚未存在正在跑这条记录的执行线程(cancellationRegistry.isActive)，只登记终止请求，
     * 实际的循环检测、结果保留与状态落库由执行线程在下一题开始前完成。
     * 若数据库状态是 running 但当前没有任何执行线程(僵尸记录，常见于进程/容器被重启导致执行线程被强制终止)，
     * 登记终止请求不会有任何任务检查，因此直接在这里降为 stopped，避免该记录永远卡在 running。
     */
    @PostMapping("/{id}/stop")
    public Map<String, Object> stopEvaluation(@PathVariable String id) {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> eval = evalStore.get(id);
        if (eval == null) {
            result.put("code", 404);
            result.put("message", "评测记录不存在");
            return result;
        }
        if (!EvaluationStatusBroadcaster.STATUS_RUNNING.equals(eval.get("status"))) {
            result.put("code", 400);
            result.put("message", "评测记录当前不是执行中状态，无法终止");
            return result;
        }
        if (cancellationRegistry.isActive(id)) {
            cancellationRegistry.requestCancel(id);
            result.put("code", 0);
            result.put("message", "已发送终止请求，正在等待当前题目执行完成");
        } else {
            log.warn("评测记录 {} 状态为 running 但无实际执行线程（僵尸记录），直接终止", id);
            eval.put("endTime", LocalDateTime.now().format(FMT));
            lifecycle.markStopped(eval);
            Map<String, Object> completeEvent = new HashMap<>();
            completeEvent.put("evalId", id);
            completeEvent.put("status", eval.get("status"));
            completeEvent.put("accuracy", eval.get("accuracy"));
            completeEvent.put("endTime", eval.get("endTime"));
            progressBroadcaster.push(id, "complete", completeEvent);
            progressBroadcaster.closeAll(id);
            result.put("code", 0);
            result.put("message", "该记录无实际执行进程（可能是历史遗留），已直接终止");
        }
        return result;
    }

    /**
     * 重新测评：清空该记录已有的题目结果，保留原有的被测对象/数据集/评测方式等设置，
     * 复用同一条记录 id 重新发起整轮评测（开始时间更新为本次重新发起时间）。
     */
    @PostMapping("/{id}/restart")
    public Map<String, Object> restartEvaluation(@PathVariable String id) {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> eval = evalStore.get(id);
        if (eval == null) {
            result.put("code", 404);
            result.put("message", "评测记录不存在");
            return result;
        }
        if (EvaluationStatusBroadcaster.STATUS_RUNNING.equals(eval.get("status"))) {
            result.put("code", 400);
            result.put("message", "评测记录正在执行中，无法重新评测");
            return result;
        }
        cancellationRegistry.clear(id);
        eval.put("status", EvaluationStatusBroadcaster.STATUS_RUNNING);
        eval.put("results", new ArrayList<>());
        eval.put("startTime", LocalDateTime.now().format(FMT));
        eval.put("endTime", null);
        eval.put("accuracy", null);
        evalStore.put(id, eval);
        statusBroadcaster.broadcast(id, EvaluationStatusBroadcaster.STATUS_RUNNING);

        // 复用现有的流式执行链路(executeStreaming)，避免为重新测评单独维护一套非流式执行逻辑。
        // 这里的 emitter 不对应任何 HTTP 响应，仅用于满足方法签名；实时进度由 progressBroadcaster 推送给详情页端。
        SseEmitter internalEmitter = new SseEmitter(60 * 60 * 1000L);
        sseExecutor.submit(() -> {
            try {
                evaluationService.executeStreaming(id, internalEmitter);
                internalEmitter.complete();
            } catch (Throwable e) {
                log.error("重新测评失败: evalId={}, error={}", id, e.getMessage(), e);
                Map<String, Object> failedEval = evalStore.get(id);
                if (failedEval != null && EvaluationStatusBroadcaster.STATUS_RUNNING.equals(failedEval.get("status"))) {
                    lifecycle.markFailed(failedEval, "执行线程异常：" + e.getMessage());
                }
                internalEmitter.completeWithError(e);
            }
        });
        result.put("code", 0);
        result.put("data", eval);
        return result;
    }

    /**
     * 断点续跑：从指定题号(1-based，用户输入直观题号)开始重新执行到末尾，之前题目的结果保留不变。
     * 与 restart 的区别是不清空 results，只对 [fromIndex-1, total) 范围内的题目重新发起调用/评分。
     */
    @PostMapping("/{id}/resume")
    @SuppressWarnings("unchecked")
    public Map<String, Object> resumeEvaluation(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> eval = evalStore.get(id);
        if (eval == null) {
            result.put("code", 404);
            result.put("message", "评测记录不存在");
            return result;
        }
        if (EvaluationStatusBroadcaster.STATUS_RUNNING.equals(eval.get("status"))) {
            result.put("code", 400);
            result.put("message", "评测记录正在执行中，无法断点续跑");
            return result;
        }
        int fromIndex = body.get("fromIndex") instanceof Number
                ? ((Number) body.get("fromIndex")).intValue() : 0;
        int total = resolveEvaluationTotalItems(eval);
        if (fromIndex < 1 || fromIndex > total) {
            result.put("code", 400);
            result.put("message", "题号超出范围，当前共 " + total + " 道题");
            return result;
        }
        Set<Integer> targetIndexes = new LinkedHashSet<>();
        for (int i = fromIndex - 1; i < total; i++) {
            targetIndexes.add(i);
        }
        cancellationRegistry.clear(id);
        eval.put("status", EvaluationStatusBroadcaster.STATUS_RUNNING);
        eval.put("endTime", null);
        eval.put("accuracy", null);
        evalStore.put(id, eval);
        statusBroadcaster.broadcast(id, EvaluationStatusBroadcaster.STATUS_RUNNING);

        SseEmitter internalEmitter = new SseEmitter(60 * 60 * 1000L);
        sseExecutor.submit(() -> {
            try {
                evaluationService.executeStreaming(id, internalEmitter, targetIndexes);
                internalEmitter.complete();
            } catch (Throwable e) {
                log.error("断点续跑失败: evalId={}, fromIndex={}, error={}", id, fromIndex, e.getMessage(), e);
                Map<String, Object> failedEval = evalStore.get(id);
                if (failedEval != null && EvaluationStatusBroadcaster.STATUS_RUNNING.equals(failedEval.get("status"))) {
                    lifecycle.markFailed(failedEval, "执行线程异常：" + e.getMessage());
                }
                internalEmitter.completeWithError(e);
            }
        });
        result.put("code", 0);
        result.put("data", eval);
        return result;
    }

    /**
     * 低于分数重跑：对该记录（须已完成）所有存在某个被测对象最终得分低于阈值的题目批量重新发起调用/评分，
     * 其余题目结果保留不变。阈值为 0-100 的实际分数（与 final 字段的 0-1 存储口径换算）。
     */
    @PostMapping("/{id}/rerun-below-score")
    @SuppressWarnings("unchecked")
    public Map<String, Object> rerunBelowScore(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> eval = evalStore.get(id);
        if (eval == null) {
            result.put("code", 404);
            result.put("message", "评测记录不存在");
            return result;
        }
        if (!EvaluationStatusBroadcaster.STATUS_COMPLETED.equals(eval.get("status"))) {
            result.put("code", 400);
            result.put("message", "仅已完成的评测记录支持低于分数重跑");
            return result;
        }
        double threshold = body.get("threshold") instanceof Number
                ? ((Number) body.get("threshold")).doubleValue() : 20;
        double thresholdRatio = threshold / 100.0;
        List<Map<String, Object>> results = (List<Map<String, Object>>) eval.get("results");
        Set<Integer> targetIndexes = new LinkedHashSet<>();
        if (results != null) {
            for (int i = 0; i < results.size(); i++) {
                Map<String, Object> r = results.get(i);
                Map<String, Object> scores = r == null ? null : (Map<String, Object>) r.get("scores");
                if (scores == null) continue;
                for (Object scoreObj : scores.values()) {
                    if (!(scoreObj instanceof Map)) continue;
                    Object finalScore = ((Map<String, Object>) scoreObj).get("final");
                    if (finalScore instanceof Number && ((Number) finalScore).doubleValue() < thresholdRatio) {
                        targetIndexes.add(i);
                        break;
                    }
                }
            }
        }
        if (targetIndexes.isEmpty()) {
            result.put("code", 400);
            result.put("message", "没有低于该分数的题目，无需重跑");
            return result;
        }
        cancellationRegistry.clear(id);
        eval.put("status", EvaluationStatusBroadcaster.STATUS_RUNNING);
        eval.put("endTime", null);
        eval.put("accuracy", null);
        evalStore.put(id, eval);
        statusBroadcaster.broadcast(id, EvaluationStatusBroadcaster.STATUS_RUNNING);
        SseEmitter internalEmitter = new SseEmitter(60 * 60 * 1000L);
        sseExecutor.submit(() -> {
            try {
                evaluationService.executeStreaming(id, internalEmitter, targetIndexes);
                internalEmitter.complete();
            } catch (Throwable e) {
                log.error("低于分数重跑失败: evalId={}, threshold={}, error={}", id, threshold, e.getMessage(), e);
                Map<String, Object> failedEval = evalStore.get(id);
                if (failedEval != null && EvaluationStatusBroadcaster.STATUS_RUNNING.equals(failedEval.get("status"))) {
                    lifecycle.markFailed(failedEval, "执行线程异常：" + e.getMessage());
                }
                internalEmitter.completeWithError(e);
            }
        });
        result.put("code", 0);
        Map<String, Object> data = new HashMap<>(eval);
        data.put("rerunCount", targetIndexes.size());
        result.put("data", data);
        return result;
    }

    /**
     * 单题重测：只对指定题号(1-based)重新发起调用/评分，其余题目结果保留不变。
     */
    @PostMapping("/{id}/rerun-question")
    public Map<String, Object> rerunQuestion(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> eval = evalStore.get(id);
        if (eval == null) {
            result.put("code", 404);
            result.put("message", "评测记录不存在");
            return result;
        }
        if (EvaluationStatusBroadcaster.STATUS_RUNNING.equals(eval.get("status"))) {
            result.put("code", 400);
            result.put("message", "评测记录正在执行中，无法重新评测该题");
            return result;
        }
        int questionIndex = body.get("questionIndex") instanceof Number
                ? ((Number) body.get("questionIndex")).intValue() : 0;
        int total = resolveEvaluationTotalItems(eval);
        if (questionIndex < 1 || questionIndex > total) {
            result.put("code", 400);
            result.put("message", "题号超出范围，当前共 " + total + " 道题");
            return result;
        }
        Set<Integer> targetIndexes = Set.of(questionIndex - 1);
        cancellationRegistry.clear(id);
        eval.put("status", EvaluationStatusBroadcaster.STATUS_RUNNING);
        eval.put("endTime", null);
        eval.put("accuracy", null);
        evalStore.put(id, eval);
        statusBroadcaster.broadcast(id, EvaluationStatusBroadcaster.STATUS_RUNNING);
        SseEmitter internalEmitter = new SseEmitter(60 * 60 * 1000L);
        sseExecutor.submit(() -> {
            try {
                evaluationService.executeStreaming(id, internalEmitter, targetIndexes);
                internalEmitter.complete();
            } catch (Throwable e) {
                log.error("单题重测失败: evalId={}, questionIndex={}, error={}", id, questionIndex, e.getMessage(), e);
                Map<String, Object> failedEval = evalStore.get(id);
                if (failedEval != null && EvaluationStatusBroadcaster.STATUS_RUNNING.equals(failedEval.get("status"))) {
                    lifecycle.markFailed(failedEval, "执行线程异常：" + e.getMessage());
                }
                internalEmitter.completeWithError(e);
            }
        });
        result.put("code", 0);
        result.put("data", eval);
        return result;
    }

    /**
     * 计算该评测记录关联数据集的题目总数，用于断点续跑/单题重测的题号范围校验。
     * 计算口径与 EvaluationService/MultimodalEvaluationService 的 executeStreaming() 收集题目的逻辑保持一致。
     */
    @SuppressWarnings("unchecked")
    private int resolveEvaluationTotalItems(Map<String, Object> eval) {
        List<String> datasetIds = (List<String>) eval.get("datasetIds");
        if (datasetIds == null || datasetIds.isEmpty()) return 0;
        int total = 0;
        for (String dsId : datasetIds) {
            Map<String, Object> dataset = datasetStore.get(dsId);
            if (dataset != null && dataset.get("items") instanceof List) {
                total += ((List<?>) dataset.get("items")).size();
            }
        }
        return total;
    }

    /**
     * 更新评测记录
     */
    @PutMapping("/{id}")
    public Map<String, Object> updateEvaluation(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> eval = evalStore.get(id);
        if (eval == null) {
            result.put("code", 404);
            result.put("message", "评测记录不存在");
            return result;
        }
        eval.putAll(body);
        eval.put("id", id);
        lifecycle.normalizeEvaluationMetadata(eval);
        evalStore.put(id, eval);
        result.put("code", 0);
        result.put("data", eval);
        return result;
    }

    /**
     * 删除评测记录
     */
    @DeleteMapping("/{id}")
    public Map<String, Object> deleteEvaluation(@PathVariable String id) {
        Map<String, Object> result = new HashMap<>();
        evalStore.remove(id);
        result.put("code", 0);
        return result;
    }

    /**
     * 人工复判打分
     */
    @PutMapping("/{evalId}/results/{resultId}/score")
    @SuppressWarnings("unchecked")
    public Map<String, Object> updateScore(
            @PathVariable String evalId,
            @PathVariable String resultId,
            @RequestBody Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> eval = evalStore.get(evalId);
        if (eval == null) {
            result.put("code", 404);
            result.put("message", "评测记录不存在");
            return result;
        }
        // 在 results 中找到对应条目并更新分数
        List<Map<String, Object>> results = (List<Map<String, Object>>) eval.get("results");
        if (results != null) {
            for (Map<String, Object> r : results) {
                if (resultId.equals(r.get("id"))) {
                    String targetId = (String) r.get("targetId");
                    Map<String, Object> scores = (Map<String, Object>) r.get("scores");
                    if (scores != null && targetId != null) {
                        Map<String, Object> targetScore = (Map<String, Object>) scores.get(targetId);
                        if (targetScore != null) {
                            // 前端 ScoreEditor.vue 提交的人工复判分数字段名为 scores（非 manual），
                            // 与请求体保持一致，避免现有保存逻辑把 manual 写成 null（历史 bug，2026-08-21 修复）
                            targetScore.put("manual", body.get("scores"));
                            if (body.get("final") != null) targetScore.put("final", body.get("final"));
                            if (body.get("remark") != null) targetScore.put("remark", body.get("remark"));
                        }
                    }
                    break;
                }
            }
        }
        evalStore.put(evalId, eval);
        Map<String, Object> data = new HashMap<>(body);
        data.put("evalId", evalId);
        data.put("resultId", resultId);
        result.put("code", 0);
        result.put("data", data);
        return result;
    }

    // ---------------------------------------------------------------------------
    // 评测类型无信息推断
    // ---------------------------------------------------------------------------
}
