package com.qatools.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 大模型评测服务
 * 调用配置中心的大模型，对预期答案和实际输出进行评分与差异分析
 */
@Service
public class LlmJudgeService {

    private static final Logger log = LoggerFactory.getLogger(LlmJudgeService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private LlmService llmService;

    @Autowired
    private ModelStoreService modelStore;

    /**
     * 异步调用大模型进行评测打分
     *
     * @param judgeModelId 评判模型ID（来自配置中心）
     * @param question 原始问题（可选，用于给模型更多上下文）
     * @param expectedAnswer 预期答案
     * @param actualAnswer 实际输出
     * @return CompletableFuture<Map<String, Object>> 包含 auto/final/analysis/differences 的评分结果
     */
    public CompletableFuture<Map<String, Object>> judgeAsync(String judgeModelId, String question,
            String expectedAnswer, String actualAnswer) {
        Map<String, Object> modelConfig = modelStore.get(judgeModelId);
        if (modelConfig == null) {
            log.error("评判模型不存在: {}", judgeModelId);
            return CompletableFuture.completedFuture(fallbackScore("评判模型配置不存在: " + judgeModelId));
        }

        String systemPrompt = "你是一个严格的答案评测专家。你的任务是对比【预期答案】和【实际输出】，从以下三个维度对模型的回答质量进行评分（0-1），并分析结果。\n" +
                "评分维度：\n" +
                "1. accuracy（准确性）：实际输出在事实、逻辑、结论上与预期答案的一致程度。\n" +
                "2. completeness（完整性）：实际输出是否覆盖了预期答案中的所有关键要点。\n" +
                "3. relevance（相关性）：实际输出是否紧密围绕问题及预期答案展开，有无跑题或冗余。\n\n" +
                "请以如下JSON格式返回，不要包含其他内容：\n" +
                "{\n" +
                "  \"accuracy\": 0.85,\n" +
                "  \"completeness\": 0.70,\n" +
                "  \"relevance\": 0.90,\n" +
                "  \"analysis\": \"总体评价和差异分析...\",\n" +
                "  \"differences\": [\"差异点1\", \"差异点2\"]\n" +
                "}";

        StringBuilder userPrompt = new StringBuilder();
        if (question != null && !question.isEmpty()) {
            userPrompt.append("问题：\n").append(question).append("\n\n");
        }
        userPrompt.append("预期答案：\n").append(expectedAnswer == null ? "" : expectedAnswer).append("\n\n");
        userPrompt.append("实际输出：\n").append(actualAnswer == null ? "" : actualAnswer).append("\n\n");
        userPrompt.append("请给出JSON格式的评测结果。");

        List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt.toString())
        );

        return llmService.chatAsync(modelConfig, messages)
                .thenApply(result -> {
                    String answer = (String) result.get("answer");
                    return parseJudgeResult(answer);
                })
                .exceptionally(e -> {
                    log.error("大模型评测失败: {}", e.getMessage());
                    return fallbackScore("大模型评测调用失败: " + e.getMessage());
                });
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJudgeResult(String answer) {
        if (answer == null || answer.isEmpty()) {
            return fallbackScore("模型未返回内容");
        }

        String jsonStr = extractJson(answer);
        if (jsonStr == null) {
            log.warn("大模型返回无法解析为JSON: {}", answer.substring(0, Math.min(200, answer.length())));
            return fallbackScore("模型返回格式不符合JSON要求");
        }

        try {
            Map<String, Object> parsed = objectMapper.readValue(jsonStr, Map.class);
            double accuracy = toDouble(parsed.get("accuracy"), 0.0);
            double completeness = toDouble(parsed.get("completeness"), 0.0);
            double relevance = toDouble(parsed.get("relevance"), 0.0);

            Map<String, Object> auto = new HashMap<>();
            auto.put("accuracy", Math.round(accuracy * 100.0) / 100.0);
            auto.put("completeness", Math.round(completeness * 100.0) / 100.0);
            auto.put("relevance", Math.round(relevance * 100.0) / 100.0);

            Map<String, Object> score = new HashMap<>();
            score.put("auto", auto);
            score.put("manual", null);

            double finalScore = accuracy * 0.4 + completeness * 0.3 + relevance * 0.3;
            score.put("final", Math.round(finalScore * 100.0) / 100.0);
            score.put("analysis", parsed.get("analysis"));
            score.put("differences", parsed.get("differences"));
            return score;
        } catch (Exception e) {
            log.warn("解析大模型评测JSON失败: {}", e.getMessage());
            return fallbackScore("解析模型返回失败");
        }
    }


    private String extractJson(String text) {
        if (text == null) return null;
        String trimmed = text.trim();
        if (trimmed.startsWith("```json")) {
            int start = trimmed.indexOf('{');
            int end = trimmed.lastIndexOf('}');
            if (start != -1 && end != -1 && end > start) {
                return trimmed.substring(start, end + 1);
            }
        }
        if (trimmed.startsWith("```")) {
            int start = trimmed.indexOf('{');
            int end = trimmed.lastIndexOf('}');
            if (start != -1 && end != -1 && end > start) {
                return trimmed.substring(start, end + 1);
            }
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start != -1 && end != -1 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return null;
    }

    private Map<String, Object> fallbackScore(String reason) {
        Map<String, Object> score = new HashMap<>();
        Map<String, Object> auto = new HashMap<>();
        auto.put("accuracy", 0.0);
        auto.put("completeness", 0.0);
        auto.put("relevance", 0.0);
        score.put("auto", auto);
        score.put("manual", null);
        score.put("final", 0.0);
        score.put("analysis", reason);
        score.put("differences", List.of(reason));
        return score;
    }

    private double toDouble(Object value, double defaultVal) {
        if (value == null) return defaultVal;
        if (value instanceof Number) return ((Number) value).doubleValue();
        try {
            return Double.parseDouble(value.toString());
        } catch (Exception e) {
            return defaultVal;
        }
    }
}
