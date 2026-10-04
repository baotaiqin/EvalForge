package com.evalforge.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 模型测评报告生成服务 — 对用户勾选的多个子评测版本，读取对应 eval_record 的逐题评分说明，
 * 再汇总子评测得分与评价，生成“模型总评”。
 */
@Service
public class ModelReportService {

    private static final Logger log = LoggerFactory.getLogger(ModelReportService.class);

    // 各子评测的评价互不依赖，并发调用评审模型；仅用于本服务内部，与评测执行/SSE 线程隔离。
    private final ExecutorService reportExecutor = Executors.newFixedThreadPool(4);

    @Autowired
    private ModelReportStoreService reportStore;

    @Autowired
    private EvaluationStoreService evalStore;

    @Autowired
    private SubEvaluationStoreService subEvalStore;

    @Autowired
    private ModelStoreService modelStore;

    @Autowired
    private CriteriaStoreService criteriaStore;

    @Autowired
    private LlmService llmService;

    /**
     * 生成报告主流程：同步执行（由调用方丢线程池异步触发），逐步落库最终结果，
     * 任一环节异常整体标记为 failed，不做单点重试。
     */
    public void generate(String reportId, String judgeModelId, List<Map<String, Object>> selections) {
        try {
            Map<String, Object> judgeModelConfig = modelStore.get(judgeModelId);
            if (judgeModelConfig == null) {
                reportStore.markFailed(reportId, "评审模型不存在，请重新选择");
                return;
            }

            String subEvalPromptTpl = criteriaStore.getSubEvalReportPrompt();
            String overallPromptTpl = criteriaStore.getOverallReportPrompt();

            // 各子评测评价彼此独立，并发调用评审模型，避免 N 个子评测顺序等待
            List<CompletableFuture<Map<String, Object>>> futures = new ArrayList<>();
            for (Map<String, Object> selection : selections) {
                futures.add(CompletableFuture.supplyAsync(
                        () -> buildSubEvalSummary(selection, judgeModelConfig, subEvalPromptTpl), reportExecutor));
            }

            List<Map<String, Object>> subEvalSummaries = new ArrayList<>();
            for (CompletableFuture<Map<String, Object>> future : futures) {
                Map<String, Object> item = future.join();
                if (item != null) {
                    subEvalSummaries.add(item);
                }
            }

            if (subEvalSummaries.isEmpty()) {
                reportStore.markFailed(reportId, "所选子评测版本均无法读取到评测记录");
                return;
            }

            Map<String, String> overallPlaceholders = new HashMap<>();
            overallPlaceholders.put("subEvalSummaryList", buildSubEvalSummaryListText(subEvalSummaries));
            String overallPrompt = LlmJsonScoreUtils.fillTemplate(overallPromptTpl, overallPlaceholders);

            String overallSummary;
            try {
                overallSummary = llmService.chat(judgeModelConfig, overallPrompt);
            } catch (Exception e) {
                log.warn("生成模型总评价失败: reportId={}, error={}", reportId, e.getMessage());
                reportStore.markFailed(reportId, "生成模型总评价失败: " + e.getMessage());
                return;
            }

            Map<String, Object> promptSnapshot = new HashMap<>();
            promptSnapshot.put("subEvalReportPrompt", subEvalPromptTpl);
            promptSnapshot.put("overallReportPrompt", overallPromptTpl);

            reportStore.markCompleted(reportId, subEvalSummaries,
                    overallSummary == null ? "" : overallSummary.trim(), promptSnapshot);
        } catch (Exception e) {
            log.error("生成模型测评报告失败: reportId={}", reportId, e);
            reportStore.markFailed(reportId, "生成报告失败：" + e.getMessage());
        }
    }

    /**
     * 为单个子评测版本生成评价，读不到评测记录时返回 null（由调用方过滤）。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> buildSubEvalSummary(Map<String, Object> selection,
            Map<String, Object> judgeModelConfig, String subEvalPromptTpl) {
        String subEvalId = asText(selection.get("subEvalId"));
        String evalId = asText(selection.get("evalId"));

        Map<String, Object> eval = evalStore.get(evalId);
        if (eval == null) {
            log.warn("模型测评报告：找不到评测记录，跳过该子评测: subEvalId={}, evalId={}", subEvalId, evalId);
            return null;
        }

        Map<String, Object> subEval = subEvalStore.get(subEvalId);
        String subEvalName = subEval != null ? asText(subEval.get("name")) : asText(eval.get("name"));
        Double accuracy = LlmJsonScoreUtils.toDoubleOrNull(eval.get("accuracy"));

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("subEvalName", subEvalName == null ? "" : subEvalName);
        // accuracy 内部存储为 0-1 小数，拼进提示词前统一换算成百分制展示，
        // 否则 LLM 会误以为满分是 1（而非 100），导致总评/对比里的“综合得分”表达失真。
        placeholders.put("accuracy", accuracy != null ? String.format("%.1f分（百分制）", accuracy * 100) : "无");
        placeholders.put("questionList", buildQuestionListText(eval));
        String prompt = LlmJsonScoreUtils.fillTemplate(subEvalPromptTpl, placeholders);

        String summary;
        try {
            summary = llmService.chat(judgeModelConfig, prompt);
        } catch (Exception e) {
            log.warn("生成子评测评价失败: subEvalId={}, evalId={}, error={}", subEvalId, evalId, e.getMessage());
            summary = "生成评价失败: " + e.getMessage();
        }

        Map<String, Object> item = new HashMap<>();
        item.put("subEvalId", subEvalId);
        item.put("subEvalName", subEvalName);
        item.put("subType", subEval != null ? subEval.get("subType") : null);
        item.put("evalId", evalId);
        item.put("subEvalVersion", eval.get("subEvalVersion"));
        item.put("accuracy", accuracy);
        item.put("durationMs", LlmJsonScoreUtils.computeDurationMs(eval.get("startTime"), eval.get("endTime")));
        item.put("summary", summary == null ? "" : summary.trim());
        return item;
    }

    /**
     * 拼接单个子评测下所有题目的文本清单，供 LLM 生成该子评测评价。
     * 每题包含问题、预期答案（仅纯文本类型标准答案展开）、得分、评分说明。
     * 评分说明仅在该题确实是 LLM 评分（scores 中存在 reason）时附带；公式评分题目只给得分。
     */
    @SuppressWarnings("unchecked")
    private String buildQuestionListText(Map<String, Object> eval) {
        List<Map<String, Object>> results = (List<Map<String, Object>>) eval.get("results");
        if (results == null || results.isEmpty()) {
            return "（该版本暂无评测结果）";
        }

        List<Map<String, Object>> targets = (List<Map<String, Object>>) eval.get("targets");
        // 按 targets 声明顺序依次尝试取分，保证 target 场景下取值顺序确定（不依赖 HashMap 遍历顺序）
        List<String> targetIdsInOrder = new ArrayList<>();
        if (targets != null) {
            for (Map<String, Object> target : targets) {
                String id = asText(target.get("id"));
                if (id != null) {
                    targetIdsInOrder.add(id);
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        int index = 0;
        for (Map<String, Object> result : results) {
            index++;
            String question = asText(result.get("question"));
            Object expected = result.get("expectedAnswer");

            Map<String, Object> scores = (Map<String, Object>) result.get("scores");
            Map<String, Object> score = firstScoreInOrder(scores, targetIdsInOrder);
            Double finalScore = score != null ? LlmJsonScoreUtils.toDoubleOrNull(score.get("final")) : null;
            String reason = score != null ? asText(score.get("reason")) : null;

            sb.append("题目").append(index).append("：").append(question == null ? "" : question).append("\n");
            if (expected instanceof String && !((String) expected).isBlank()) {
                sb.append("预期答案：").append(expected).append("\n");
            }
            sb.append("得分：").append(finalScore != null ? finalScore : "无").append("\n");
            if (reason != null && !reason.isBlank()) {
                sb.append("评分说明：").append(reason).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String buildSubEvalSummaryListText(List<Map<String, Object>> subEvalSummaries) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> item : subEvalSummaries) {
            sb.append("【").append(item.get("subEvalName")).append("】\n");
            Double accuracy = LlmJsonScoreUtils.toDoubleOrNull(item.get("accuracy"));
            // accuracy 内部存储为 0-1 小数，拼进提示词前统一换算成百分制展示。
            sb.append("综合得分: ").append(accuracy != null ? String.format("%.1f分（百分制）", accuracy * 100) : "无").append("\n");
            sb.append("能力评价: ").append(item.get("summary")).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 按 targetIdsInOrder 顺序取第一个存在的分数；targets 为空（历史数据缺失）时退化为
     * scores 中任意一项，仅作兜底，不保证在多 target 场景下选中固定的一个。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> firstScoreInOrder(Map<String, Object> scores, List<String> targetIdsInOrder) {
        if (scores == null || scores.isEmpty()) {
            return null;
        }
        for (String targetId : targetIdsInOrder) {
            Object score = scores.get(targetId);
            if (score instanceof Map) {
                return (Map<String, Object>) score;
            }
        }
        for (Object value : scores.values()) {
            if (value instanceof Map) {
                return (Map<String, Object>) value;
            }
        }
        return null;
    }

    private String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
