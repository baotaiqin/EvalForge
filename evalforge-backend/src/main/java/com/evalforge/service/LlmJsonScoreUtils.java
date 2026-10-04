package com.evalforge.service;

/**
 * LLM 评分返回结果的通用解析工具：从可能夹杂解释文字的响应中提取 JSON 片段，
 * 将 JSON 值中常见的 double 分数，在 EvaluationService/MultimodalEvaluationService/
 * PptScoringService 等处共用，避免各评分场景的解析规则不一致。
 */
final class LlmJsonScoreUtils {

    private LlmJsonScoreUtils() {
    }

    static String extractJsonFromResponse(String response) {
        if (response == null) return null;
        String trimmed = response.trim();
        if (trimmed.startsWith("```") && trimmed.endsWith("```")) {
            return trimmed.substring(3, trimmed.length() - 3).trim();
        }
        int start = trimmed.indexOf("{");
        int end = trimmed.lastIndexOf("}");
        if (start != -1 && end != -1 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return null;
    }

    static double toDoubleDefault(Object value, double defaultVal) {
        if (value == null) return defaultVal;
        if (value instanceof Number) return ((Number) value).doubleValue();
        try {
            return Double.parseDouble(value.toString());
        } catch (Exception e) {
            return defaultVal;
        }
    }

    /**
     * 与 toDoubleDefault 类似，但在无法解析时返回 null 而不是默认值，
     * 供需要区分“缺失分数”和“无法解析”两种语义的场景使用。
     */
    static Double toDoubleOrNull(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).doubleValue();
        try {
            return Double.parseDouble(value.toString());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 用给定占位符替换 prompt 模板中的 {{key}} 占位符，未匹配到的占位符保留。
     */
    static String fillTemplate(String template, java.util.Map<String, String> placeholders) {
        if (template == null) return null;
        String result = template;
        for (java.util.Map.Entry<String, String> entry : placeholders.entrySet()) {
            String value = entry.getValue() == null ? "" : entry.getValue();
            result = result.replace("{{" + entry.getKey() + "}}", value);
        }
        return result;
    }

    private static final java.time.format.DateTimeFormatter DATETIME_FMT =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 将 eval_record 的 startTime/endTime（"yyyy-MM-dd HH:mm:ss" 字符串）计算耗时（毫秒）；
     * 任一时间缺失、格式无法解析、或结束早于开始（数据异常）时返回 null。
     * ModelReportService/ReportComparisonService 深拷贝子评测/对比报告时的耗时展示复用。
     */
    static Long computeDurationMs(Object startTime, Object endTime) {
        if (startTime == null || endTime == null) return null;
        try {
            java.time.LocalDateTime start = java.time.LocalDateTime.parse(startTime.toString(), DATETIME_FMT);
            java.time.LocalDateTime end = java.time.LocalDateTime.parse(endTime.toString(), DATETIME_FMT);
            long millis = java.time.Duration.between(start, end).toMillis();
            return millis < 0 ? null : millis;
        } catch (Exception e) {
            return null;
        }
    }
}
