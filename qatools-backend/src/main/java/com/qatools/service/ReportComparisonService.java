package com.qatools.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 模型测评报告对比生成服务 —— 选取两份及以上历史测试报告（同模型不同版本，或不同模型分组），
 * 按各报告共有的子评测类型（subType）交叉对比差异，最后汇总各报告综合得分给出结论与排名。
 * 提示词模板来自 CriteriaStoreService（配置中心“评判标准”编辑）。
 */
@Service
public class ReportComparisonService {

    private static final Logger log = LoggerFactory.getLogger(ReportComparisonService.class);

    // 各子评测维度的对比互不依赖，并发调用评审模型；仅用于本服务内部，与评测执行/SSE 线程池隔离。
    private final ExecutorService comparisonExecutor = Executors.newFixedThreadPool(4);

    @Autowired
    private ReportComparisonStoreService comparisonStore;

    @Autowired
    private ModelReportStoreService reportStore;

    @Autowired
    private ModelGroupStoreService groupStore;

    @Autowired
    private SubEvalTypeStoreService subEvalTypeStore;

    @Autowired
    private ModelStoreService modelStore;

    @Autowired
    private CriteriaStoreService criteriaStore;

    @Autowired
    private LlmService llmService;

    /**
     * 生成报告对比主流程：同步执行（由 Controller 丢到线程池异步触发），逐步落库最终结果，
     * 任一关键环节异常整体标记为 failed。
     */
    @SuppressWarnings("unchecked")
    public void generate(String comparisonId, String judgeModelId, List<String> reportIds, String comparisonMode) {
        try {
            Map<String, Object> judgeModelConfig = modelStore.get(judgeModelId);
            if (judgeModelConfig == null) {
                comparisonStore.markFailed(comparisonId, "评审模型不存在，请重新选择");
                return;
            }

            // 获取参与对比的所有报告（含所属模型分组名称，用于展示模型名）
            List<Map<String, Object>> reports = new ArrayList<>();
            for (String reportId : reportIds) {
                Map<String, Object> report = reportStore.get(reportId);
                if (report == null) {
                    log.warn("报告对比：找不到报告，跳过: reportId={}", reportId);
                    continue;
                }
                report.put("groupName", resolveGroupName(asText(report.get("groupId"))));
                reports.add(report);
            }

            if (reports.size() < 2) {
                comparisonStore.markFailed(comparisonId, "至少需要两份有效报告才能进行对比");
                return;
            }

            // 按 subType 分组：同一评测类型下收集各报告的子评测摘要
            Map<String, List<Map<String, Object>>> bySubtype = new LinkedHashMap<>();
            for (Map<String, Object> report : reports) {
                List<Map<String, Object>> summaries =
                        (List<Map<String, Object>>) report.get("subEvalSummaries");
                if (summaries == null) continue;
                for (Map<String, Object> summary : summaries) {
                    String subtype = asText(summary.get("subType"));
                    if (subtype == null) continue;
                    Map<String, Object> entry = new HashMap<>(summary);
                    entry.put("groupName", report.get("groupName"));
                    entry.put("reportId", report.get("id"));
                    bySubtype.computeIfAbsent(subtype, k -> new ArrayList<>()).add(entry);
                }
            }

            String subtypePromptTpl = criteriaStore.getSubtypeComparisonPrompt();
            String overallPromptTpl = criteriaStore.getOverallComparisonPrompt(comparisonMode);

            // 只对至少有两份报告覆盖的子评测类型生成交叉对比
            List<CompletableFuture<Map<String, Object>>> futures = new ArrayList<>();
            for (Map.Entry<String, List<Map<String, Object>>> entry : bySubtype.entrySet()) {
                if (entry.getValue().size() < 2) continue;
                String subtype = entry.getKey();
                List<Map<String, Object>> entries = entry.getValue();
                futures.add(CompletableFuture.supplyAsync(
                        () -> buildSubtypeComparison(subtype, entries, judgeModelConfig, subtypePromptTpl),
                        comparisonExecutor));
            }

            List<Map<String, Object>> subtypeComparisons = new ArrayList<>();
            for (CompletableFuture<Map<String, Object>> future : futures) {
                Map<String, Object> item = future.join();
                if (item != null) {
                    subtypeComparisons.add(item);
                }
            }

            if (subtypeComparisons.isEmpty()) {
                comparisonStore.markFailed(comparisonId, "所选报告之间没有共同的子评测类型，无法进行对比");
                return;
            }

            Map<String, String> overallPlaceholders = new HashMap<>();
            overallPlaceholders.put("subtypeComparisonList", buildSubtypeComparisonListText(subtypeComparisons));
            overallPlaceholders.put("reportOverallList", buildReportOverallListText(reports));
            String overallPrompt = LlmJsonScoreUtils.fillTemplate(overallPromptTpl, overallPlaceholders);

            String overallConclusion;
            try {
                overallConclusion = llmService.chat(judgeModelConfig, overallPrompt);
            } catch (Exception e) {
                log.warn("生成报告对比总结论失败: comparisonId={}, error={}", comparisonId, e.getMessage());
                comparisonStore.markFailed(comparisonId, "生成总结论失败: " + e.getMessage());
                return;
            }

            Map<String, Object> promptSnapshot = new HashMap<>();
            promptSnapshot.put("subtypeComparisonPrompt", subtypePromptTpl);
            promptSnapshot.put("overallComparisonPrompt", overallPromptTpl);

            comparisonStore.markCompleted(comparisonId, subtypeComparisons,
                    overallConclusion == null ? "" : overallConclusion.trim(), promptSnapshot);
        } catch (Exception e) {
            log.error("生成报告对比失败: comparisonId={}", comparisonId, e);
            comparisonStore.markFailed(comparisonId, "生成报告对比失败: " + e.getMessage());
        }
    }

    /**
     * 对某一子评测类型下的多份报告调用评审模型，生成差异分析文本。
     */
    private Map<String, Object> buildSubtypeComparison(String subtype, List<Map<String, Object>> entries,
                                                        Map<String, Object> judgeModelConfig, String promptTpl) {
        String subtypeName = subEvalTypeLabel(subtype);
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("subTypeName", subtypeName);
        placeholders.put("reportList", buildReportListText(entries));
        String prompt = LlmJsonScoreUtils.fillTemplate(promptTpl, placeholders);

        String comparisonText;
        try {
            comparisonText = llmService.chat(judgeModelConfig, prompt);
        } catch (Exception e) {
            log.warn("子评测维度对比生成失败: subType={}, error={}", subtype, e.getMessage());
            comparisonText = "生成对比失败: " + e.getMessage();
        }

        Map<String, Object> result = new HashMap<>();
        result.put("subType", subtype);
        result.put("subTypeName", subtypeName);
        List<Map<String, Object>> participants = new ArrayList<>();
        for (Map<String, Object> entry : entries) {
            Map<String, Object> participant = new HashMap<>();
            participant.put("groupName", entry.get("groupName"));
            participant.put("reportId", entry.get("reportId"));
            participant.put("accuracy", entry.get("accuracy"));
            participant.put("durationMs", entry.get("durationMs"));
            participants.add(participant);
        }
        result.put("participants", participants);
        result.put("comparison", comparisonText == null ? "" : comparisonText.trim());
        return result;
    }

    private String buildReportListText(List<Map<String, Object>> entries) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> entry : entries) {
            sb.append("模型：").append(entry.get("groupName")).append("\n");
            Double accuracy = LlmJsonScoreUtils.toDoubleOrNull(entry.get("accuracy"));
            sb.append("综合得分：")
                    .append(accuracy != null ? String.format("%.1f分（百分制）", accuracy * 100) : "无")
                    .append("\n");
            sb.append("能力评价：").append(entry.get("summary")).append("\n\n");
        }
        return sb.toString();
    }

    private String buildSubtypeComparisonListText(List<Map<String, Object>> subtypeComparisons) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> item : subtypeComparisons) {
            sb.append("【").append(item.get("subTypeName")).append("】\n");
            sb.append(item.get("comparison")).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 拼接所有参与报告的平均分清单，供模型性能排名/回归场景的总体结论使用。
     */
    @SuppressWarnings("unchecked")
    private String buildReportOverallListText(List<Map<String, Object>> reports) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> report : reports) {
            List<Map<String, Object>> summaries = (List<Map<String, Object>>) report.get("subEvalSummaries");
            Double overall = averageAccuracy(summaries);
            sb.append("模型：").append(report.get("groupName")).append("\n");
            sb.append("本次试验综合得分：").append(overall != null ? String.format("%.1f分（百分制）", overall * 100) : "无").append("\n\n");
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private Double averageAccuracy(List<Map<String, Object>> summaries) {
        if (summaries == null || summaries.isEmpty()) return null;
        double sum = 0;
        int count = 0;
        for (Map<String, Object> summary : summaries) {
            Double accuracy = LlmJsonScoreUtils.toDoubleOrNull(summary.get("accuracy"));
            if (accuracy != null) {
                sum += accuracy;
                count++;
            }
        }
        return count == 0 ? null : sum / count;
    }

    private String resolveGroupName(String groupId) {
        if (groupId == null) return "未知模型";
        Map<String, Object> group = groupStore.get(groupId);
        return group != null ? asText(group.get("name")) : "未知模型";
    }

    private String subEvalTypeLabel(String subtype) {
        Map<String, Object> type = subEvalTypeStore.get(subtype);
        return type != null && asText(type.get("name")) != null ? asText(type.get("name")) : subtype;
    }

    private String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
