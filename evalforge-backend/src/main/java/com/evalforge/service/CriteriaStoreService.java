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

/**
 * 评判标准存储服务 — 基于 MySQL 持久化（单例配置）
 * 同时承载 LLM 评分提示词模板（文本类/PPT内容类），供 EvaluationService/MultimodalEvaluationService/
 * PptScoringService 在调用大模型评分时读取，替代此前硬编码在各 Service 里的固定提示词。
 */
@Service
public class CriteriaStoreService {

    private static final Logger log = LoggerFactory.getLogger(CriteriaStoreService.class);

    /** 文本类评分提示词默认模板：占位符 {{question}}/{{expected}}/{{answer}} */
    public static final String DEFAULT_TEXT_JUDGE_PROMPT =
            "你是一个严格的评测打分专家。请结合\"问题\"、\"预期答案\"和\"实际答案\"，从以下几个维度打分（0-1分，保留两位小数）：\n" +
            "1. accuracy（准确性）：实际答案的内容是否正确，与预期答案的事实一致性\n" +
            "2. completeness（完整性）：实际答案是否涵盖了预期答案的所有要点\n" +
            "3. relevance（相关性）：实际答案是否与问题相关，有没有答非所问或冗余内容\n\n" +
            "请以JSON格式返回，不要包含其他解释：\n" +
            "{\n  \"accuracy\": 0.85,\n  \"completeness\": 0.70,\n  \"relevance\": 0.90,\n  \"reason\": \"简要说明评分理由\"\n}\n\n" +
            "问题：\n{{question}}\n\n预期答案：\n{{expected}}\n\n实际答案：\n{{answer}}";

    /** PPT 内容评分提示词默认模板：占位符 {{expectedSlides}}/{{generatedSlides}} */
    public static final String DEFAULT_PPT_JUDGE_PROMPT =
            "你是一个严格的 PPT 内容评审专家。请对比\"标准答案PPT\"和\"生成PPT\"的文字内容，" +
            "从内容准确性、完整性、逻辑相关性综合打一个0-1分（保留两位小数）。\n" +
            "请以JSON格式返回，不要包含其他解释：\n{\n  \"score\": 0.85,\n  \"reason\": \"简要说明评分理由\"\n}\n\n" +
            "标准答案PPT内容（按幻灯片顺序）：\n{{expectedSlides}}\n\n生成PPT内容（按幻灯片顺序）：\n{{generatedSlides}}";

    /** HTML 内容评分提示词默认模板：占位符 {{expectedContent}}/{{generatedContent}} */
    public static final String DEFAULT_HTML_JUDGE_PROMPT =
            "你是一个严格的 HTML 报告内容评审专家。请对比\"标准答案报告\"和\"生成报告\"的正文内容，" +
            "从内容准确性、完整性、逻辑相关性综合打一个0-1分（保留两位小数）。\n" +
            "请以JSON格式返回，不要包含其他解释：\n{\n  \"score\": 0.85,\n  \"reason\": \"简要说明评分理由\"\n}\n\n" +
            "标准答案报告内容：\n{{expectedContent}}\n\n生成报告内容：\n{{generatedContent}}";

    /** PPT 对比评测评分维度默认权重（对应 PptScoringService 的 content/structure/visual），key 固定不可增删，仅权重可调 */
    public static final String DEFAULT_PPT_DIMENSIONS =
            "[{\"key\":\"content\",\"label\":\"内容\",\"weight\":0.5}," +
            "{\"key\":\"structure\",\"label\":\"结构\",\"weight\":0.3}," +
            "{\"key\":\"visual\",\"label\":\"视觉\",\"weight\":0.2}]";

    /** HTML 对比评测评分维度默认权重（对应 HtmlScoringService 的 content/structure/visual），key 固定不可增删，仅权重可调 */
    public static final String DEFAULT_HTML_DIMENSIONS =
            "[{\"key\":\"content\",\"label\":\"内容\",\"weight\":0.4}," +
            "{\"key\":\"structure\",\"label\":\"结构\",\"weight\":0.4}," +
            "{\"key\":\"visual\",\"label\":\"视觉\",\"weight\":0.2}]";

    /** 模型测评报告 - 单个子评测评价提示词默认模板：占位符 {{subEvalName}}/{{accuracy}}/{{questionList}} */
    public static final String DEFAULT_SUB_EVAL_REPORT_PROMPT =
            "你是一个严格的模型能力评审专家。以下是子评测\"{{subEvalName}}\"的评测结果（综合得分：{{accuracy}}分）。" +
            "包含每道题目的问题、预期答案、实际回答、评分理由。请通读全部题目，总结该子评测下模型的能力表现，" +
            "指出做得好的地方和存在的问题，给出一段简明扼要的评价（200字以内，不需要重复罗列每道题）。\n" +
            "直接输出评价文本，不要输出JSON或额外说明。\n\n" +
            "{{questionList}}";

    /** 模型测评报告 - 模型总评价提示词默认模板：占位符 {{subEvalSummaryList}} */
    public static final String DEFAULT_OVERALL_REPORT_PROMPT =
            "你是一个严格的模型能力评审专家。以下是该模型在各个子评测维度下的综合得分与能力评价，" +
            "请通读后给出该模型的整体能力总评（300字以内），需指出该模型的优势维度、薄弱维度，并给出总体结论。\n" +
            "直接输出评价文本，不要输出JSON或额外说明。\n\n" +
            "{{subEvalSummaryList}}";

    /** 报告对比 - 同子评测类型下多份报告对比提示词默认模板：占位符 {{subTypeName}}/{{reportList}} */
    public static final String DEFAULT_SUBTYPE_COMPARISON_PROMPT =
            "你是一个严格的模型能力评审专家。以下是多个模型在子评测\"{{subTypeName}}\"下的综合得分与能力评价。" +
            "请对比分析各模型在该子评测维度下的表现差异（150字以内），指出哪个表现更好、差异体现在哪些方面。\n" +
            "直接输出对比分析文本，不要输出JSON或额外说明。\n\n" +
            "{{reportList}}";

    /** 报告对比 - 多份报告总结论提示词默认模板（模型性能比较场景）：占位符 {{subtypeComparisonList}}/{{reportOverallList}} */
    public static final String DEFAULT_OVERALL_COMPARISON_PROMPT =
            "你是一个严格的模型能力评审专家。以下是多个模型分组在各子评测维度下的对比分析，以及各模型分组的综合得分。\n" +
            "请通读后给出这些模型之间的总体结论与差异总结（300字以内），并按综合能力给出排名。\n" +
            "直接输出结论文本，不要输出JSON或额外说明。\n\n" +
            "各子评测维度对比：\n{{subtypeComparisonList}}\n\n各模型综合得分：\n{{reportOverallList}}";

    /** 报告对比 - 多份报告总结论提示词默认模板（模型回归验证场景：同于同一模型不同批次/结果对比）：占位符同上 */
    public static final String DEFAULT_OVERALL_COMPARISON_PROMPT_REGRESSION =
            "你是一个严格的模型回归测试审核专家。以下是同一模型在不同批次/不同时间点的多次测评，各子评测维度下的对比分析，以及各次测评的综合得分。\n" +
            "请通读后判断这些测评结果之间是否存在明显差异（300字以内）：综合得分或者各子评测得分上下浮动5分以内视为正常波动。\n" +
            "应判定为\"结果基本一致，未发现明显回归问题\"；只有超出5分的差异才需要具体指出体现在哪些子评测维度、分数变化方向及可能原因，帮助判断是否存在能力退化。\n" +
            "直接输出结论文本，不要输出JSON或额外说明。\n\n" +
            "各子评测维度对比：\n{{subtypeComparisonList}}\n\n各次测评综合得分：\n{{reportOverallList}}";

    /** SemiMind 多模态会话轮换默认阈值：每题累计达到该数量后强制切换新对话窗口，避免单一窗口消息过多超限 */
    public static final int DEFAULT_SEMIMIND_WINDOW_SIZE = 50;

    /** update() 里可批量更新的提示词字段：Java 字段名 -> 数据库列名。新增提示词模板只需在此加一行，无需再扩 update() 参数列表 */
    private static final Map<String, String> PROMPT_FIELD_COLUMNS = Map.ofEntries(
            Map.entry("textJudgePrompt", "text_judge_prompt"),
            Map.entry("pptJudgePrompt", "ppt_judge_prompt"),
            Map.entry("htmlJudgePrompt", "html_judge_prompt"),
            Map.entry("subEvalReportPrompt", "sub_eval_report_prompt"),
            Map.entry("overallReportPrompt", "overall_report_prompt"),
            Map.entry("subtypeComparisonPrompt", "subtype_comparison_prompt"),
            Map.entry("overallComparisonPrompt", "overall_comparison_prompt"),
            Map.entry("overallComparisonPromptRegression", "overall_comparison_prompt_regression")
    );

    /** 供 CriteriaController 从请求体里筛选出合法的提示词字段名，不需要重复维护一份同样的字段清单 */
    public static final Set<String> PROMPT_FIELD_NAMES = PROMPT_FIELD_COLUMNS.keySet();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void init() {
        try {
            tryAddColumn("text_judge_prompt", "TEXT COMMENT '文本类LLM评分提示词模板'");
            tryAddColumn("ppt_judge_prompt", "TEXT COMMENT 'PPT内容LLM评分提示词模板'");
            tryAddColumn("html_judge_prompt", "TEXT COMMENT 'HTML内容LLM评分提示词模板'");
            tryAddColumn("sub_eval_report_prompt", "TEXT COMMENT '模型测评报告-子评测评价提示词模板'");
            tryAddColumn("overall_report_prompt", "TEXT COMMENT '模型测评报告-总评价提示词模板'");
            tryAddColumn("subtype_comparison_prompt", "TEXT COMMENT '报告对比-子评测维度对比提示词模板'");
            tryAddColumn("overall_comparison_prompt", "TEXT COMMENT '报告对比-总结论提示词模板(模型性能比较场景)'");
            tryAddColumn("overall_comparison_prompt_regression", "TEXT COMMENT '报告对比-总结论提示词模板(模型回归验证场景)'");
            tryAddColumn("ppt_dimensions", "TEXT COMMENT 'PPT对比评测维度权重(key固定为content/structure/visual)'");
            tryAddColumn("html_dimensions", "TEXT COMMENT 'HTML对比评测维度权重(key固定为content/structure/visual)'");
            tryAddColumn("semimind_window_size", "INT DEFAULT 50 COMMENT 'SemiMind多模态会话最大轮换题数'");

            // 确保默认配置存在
            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM criteria_config WHERE id = 1", Integer.class);
            if (count == null || count == 0) {
                String defaultDimensions = "[{\"key\":\"accuracy\",\"label\":\"准确性\",\"weight\":0.4}," +
                        "{\"key\":\"completeness\",\"label\":\"完整性\",\"weight\":0.3}," +
                        "{\"key\":\"relevance\",\"label\":\"相关性\",\"weight\":0.3}]";
                jdbcTemplate.update(
                        "INSERT INTO criteria_config (id, similarity_threshold, dimensions, text_judge_prompt, ppt_judge_prompt, " +
                                "html_judge_prompt, sub_eval_report_prompt, overall_report_prompt, subtype_comparison_prompt, overall_comparison_prompt, " +
                                "overall_comparison_prompt_regression, ppt_dimensions, html_dimensions, semimind_window_size) " +
                                "VALUES (1, 0.8, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        defaultDimensions, DEFAULT_TEXT_JUDGE_PROMPT, DEFAULT_PPT_JUDGE_PROMPT, DEFAULT_HTML_JUDGE_PROMPT,
                        DEFAULT_SUB_EVAL_REPORT_PROMPT, DEFAULT_OVERALL_REPORT_PROMPT,
                        DEFAULT_SUBTYPE_COMPARISON_PROMPT, DEFAULT_OVERALL_COMPARISON_PROMPT, DEFAULT_OVERALL_COMPARISON_PROMPT_REGRESSION,
                        DEFAULT_PPT_DIMENSIONS, DEFAULT_HTML_DIMENSIONS, DEFAULT_SEMIMIND_WINDOW_SIZE);
            } else {
                // 已有旧记录时补齐新增列的默认值，避免 get() 读到 null 后每次都退化到内置默认值（无法持久化用户后续的修改历史前的初始状态）
                jdbcTemplate.update(
                        "UPDATE criteria_config SET ppt_dimensions = COALESCE(ppt_dimensions, ?), " +
                                "html_dimensions = COALESCE(html_dimensions, ?), " +
                                "overall_comparison_prompt_regression = COALESCE(overall_comparison_prompt_regression, ?), " +
                                "semimind_window_size = COALESCE(semimind_window_size, ?) WHERE id = 1",
                        DEFAULT_PPT_DIMENSIONS, DEFAULT_HTML_DIMENSIONS, DEFAULT_OVERALL_COMPARISON_PROMPT_REGRESSION, DEFAULT_SEMIMIND_WINDOW_SIZE);
            }
        } catch (Exception e) {
            log.warn("初始化评判标准失败: {}", e.getMessage());
        }
    }

    private void tryAddColumn(String columnName, String columnDef) {
        try {
            Integer exists = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() " +
                            "AND TABLE_NAME = 'criteria_config' AND COLUMN_NAME = ?",
                    Integer.class, columnName);
            if (exists == null || exists == 0) {
                jdbcTemplate.execute("ALTER TABLE criteria_config ADD COLUMN " + columnName + " " + columnDef);
            }
        } catch (Exception e) {
            log.warn("检查/新增列 {} 失败: {}", columnName, e.getMessage());
        }
    }

    public Map<String, Object> get() {
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM criteria_config WHERE id = 1");
            Map<String, Object> data = new HashMap<>();
            data.put("similarityThreshold", row.get("similarity_threshold"));
            data.put("dimensions", fromJson(row.get("dimensions"),
                    new TypeReference<List<Map<String, Object>>>() {}));
            data.put("pptDimensions", firstNonEmpty(
                    fromJson(row.get("ppt_dimensions"), new TypeReference<List<Map<String, Object>>>() {}),
                    fromJson(DEFAULT_PPT_DIMENSIONS, new TypeReference<List<Map<String, Object>>>() {})));
            data.put("htmlDimensions", firstNonEmpty(
                    fromJson(row.get("html_dimensions"), new TypeReference<List<Map<String, Object>>>() {}),
                    fromJson(DEFAULT_HTML_DIMENSIONS, new TypeReference<List<Map<String, Object>>>() {})));
            data.put("semimindWindowSize", row.get("semimind_window_size") != null
                    ? row.get("semimind_window_size") : DEFAULT_SEMIMIND_WINDOW_SIZE);
            for (Map.Entry<String, String> entry : PROMPT_FIELD_COLUMNS.entrySet()) {
                data.put(entry.getKey(), firstNonBlank((String) row.get(entry.getValue()), defaultPromptFor(entry.getKey())));
            }
            return data;
        } catch (Exception e) {
            log.error("查询评判标准失败", e);
            // 返回默认值
            Map<String, Object> data = new HashMap<>();
            data.put("similarityThreshold", 0.8);
            data.put("dimensions", List.of(
                    Map.of("key", "accuracy", "label", "准确性", "weight", 0.4),
                    Map.of("key", "completeness", "label", "完整性", "weight", 0.3),
                    Map.of("key", "relevance", "label", "相关性", "weight", 0.3)));
            data.put("pptDimensions", fromJson(DEFAULT_PPT_DIMENSIONS, new TypeReference<List<Map<String, Object>>>() {}));
            data.put("htmlDimensions", fromJson(DEFAULT_HTML_DIMENSIONS, new TypeReference<List<Map<String, Object>>>() {}));
            data.put("semimindWindowSize", DEFAULT_SEMIMIND_WINDOW_SIZE);
            for (String field : PROMPT_FIELD_COLUMNS.keySet()) {
                data.put(field, defaultPromptFor(field));
            }
            return data;
        }
    }

    private List<Map<String, Object>> firstNonEmpty(List<Map<String, Object>> value, List<Map<String, Object>> fallback) {
        return (value == null || value.isEmpty()) ? fallback : value;
    }

    /**
     * 各提示词字段对应的内置默认模板，get() 查询失败/字段为空时兜底使用。
     */
    private String defaultPromptFor(String field) {
        return switch (field) {
            case "textJudgePrompt" -> DEFAULT_TEXT_JUDGE_PROMPT;
            case "pptJudgePrompt" -> DEFAULT_PPT_JUDGE_PROMPT;
            case "htmlJudgePrompt" -> DEFAULT_HTML_JUDGE_PROMPT;
            case "subEvalReportPrompt" -> DEFAULT_SUB_EVAL_REPORT_PROMPT;
            case "overallReportPrompt" -> DEFAULT_OVERALL_REPORT_PROMPT;
            case "subtypeComparisonPrompt" -> DEFAULT_SUBTYPE_COMPARISON_PROMPT;
            case "overallComparisonPrompt" -> DEFAULT_OVERALL_COMPARISON_PROMPT;
            case "overallComparisonPromptRegression" -> DEFAULT_OVERALL_COMPARISON_PROMPT_REGRESSION;
            default -> "";
        };
    }

    /**
     * 获取文本类评分维度权重表：key -> weight。查询失败或未配置时返回默认权重。
     */
    public Map<String, Double> getDimensionWeights() {
        Object dimensionsObj = get().get("dimensions");
        Map<String, Double> weights = new HashMap<>();
        if (dimensionsObj instanceof List) {
            for (Object item : (List<?>) dimensionsObj) {
                if (item instanceof Map) {
                    Map<?, ?> dim = (Map<?, ?>) item;
                    Object key = dim.get("key");
                    Object weight = dim.get("weight");
                    if (key != null && weight instanceof Number) {
                        weights.put(key.toString(), ((Number) weight).doubleValue());
                    }
                }
            }
        }
        if (weights.isEmpty()) {
            weights.put("accuracy", 0.4);
            weights.put("completeness", 0.3);
            weights.put("relevance", 0.3);
        }
        return weights;
    }

    /**
     * 获取 PPT 对比评测维度权重表：key -> weight（key 固定为 content/structure/visual，不支持增删）。
     * 查询失败或未配置时返回 PptScoringService 原硬编码的默认权重。
     */
    public Map<String, Double> getPptDimensionWeights() {
        return extractDimensionWeights(get().get("pptDimensions"), 0.5, 0.3, 0.2);
    }

    /**
     * 获取 HTML 对比评测维度权重表：key -> weight（key 固定为 content/structure/visual，不支持增删）。
     * 查询失败或未配置时返回 HtmlScoringService 原硬编码的默认权重。
     */
    public Map<String, Double> getHtmlDimensionWeights() {
        return extractDimensionWeights(get().get("htmlDimensions"), 0.4, 0.4, 0.2);
    }

    private Map<String, Double> extractDimensionWeights(Object dimensionsObj, double defaultContent,
                                                         double defaultStructure, double defaultVisual) {
        Map<String, Double> weights = new HashMap<>();
        if (dimensionsObj instanceof List) {
            for (Object item : (List<?>) dimensionsObj) {
                if (item instanceof Map) {
                    Map<?, ?> dim = (Map<?, ?>) item;
                    Object key = dim.get("key");
                    Object weight = dim.get("weight");
                    if (key != null && weight instanceof Number) {
                        weights.put(key.toString(), ((Number) weight).doubleValue());
                    }
                }
            }
        }
        if (weights.isEmpty()) {
            weights.put("content", defaultContent);
            weights.put("structure", defaultStructure);
            weights.put("visual", defaultVisual);
        }
        return weights;
    }

    public String getTextJudgePrompt() {
        return firstNonBlank((String) get().get("textJudgePrompt"), DEFAULT_TEXT_JUDGE_PROMPT);
    }

    public String getPptJudgePrompt() {
        return firstNonBlank((String) get().get("pptJudgePrompt"), DEFAULT_PPT_JUDGE_PROMPT);
    }

    public String getHtmlJudgePrompt() {
        return firstNonBlank((String) get().get("htmlJudgePrompt"), DEFAULT_HTML_JUDGE_PROMPT);
    }

    public String getSubEvalReportPrompt() {
        return firstNonBlank((String) get().get("subEvalReportPrompt"), DEFAULT_SUB_EVAL_REPORT_PROMPT);
    }

    public String getOverallReportPrompt() {
        return firstNonBlank((String) get().get("overallReportPrompt"), DEFAULT_OVERALL_REPORT_PROMPT);
    }

    public String getSubtypeComparisonPrompt() {
        return firstNonBlank((String) get().get("subtypeComparisonPrompt"), DEFAULT_SUBTYPE_COMPARISON_PROMPT);
    }

    public String getOverallComparisonPrompt() {
        return firstNonBlank((String) get().get("overallComparisonPrompt"), DEFAULT_OVERALL_COMPARISON_PROMPT);
    }

    public String getOverallComparisonPromptRegression() {
        return firstNonBlank((String) get().get("overallComparisonPromptRegression"), DEFAULT_OVERALL_COMPARISON_PROMPT_REGRESSION);
    }

    /**
     * 按对比场景取总结论提示词模板："regression"（模型回归验证）取回归验证模板，
     * 其余（含 null，默认"performance"模型性能比较）取性能比较模板。
     */
    public String getOverallComparisonPrompt(String comparisonMode) {
        return "regression".equals(comparisonMode) ? getOverallComparisonPromptRegression() : getOverallComparisonPrompt();
    }

    /**
     * 获取 SemiMind 多模态会话轮换阈值：每题累计达到该数量后强制切换新对话窗口。
     * 查询失败或未配置、或值非正数时返回默认值 50。
     */
    public int getSemimindWindowSize() {
        Object value = get().get("semimindWindowSize");
        if (value instanceof Number) {
            int size = ((Number) value).intValue();
            if (size > 0) {
                return size;
            }
        }
        return DEFAULT_SEMIMIND_WINDOW_SIZE;
    }

    private String firstNonBlank(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    /**
     * 更新评判标准。similarityThreshold/dimensions/pptDimensions/htmlDimensions/semimindWindowSize 类型特殊单独处理；
     * 其余提示词字段通过 promptFields（key 为 PROMPT_FIELD_COLUMNS 里声明的 Java 字段名）批量更新，
     * 新增提示词模板只需在 PROMPT_FIELD_COLUMNS 里加一行映射，此方法签名无需再变动。
     */
    public void update(Double similarityThreshold, List<Map<String, Object>> dimensions,
                       List<Map<String, Object>> pptDimensions, List<Map<String, Object>> htmlDimensions,
                       Integer semimindWindowSize, Map<String, String> promptFields) {
        try {
            List<Object> params = new ArrayList<>();
            List<String> sets = new ArrayList<>();
            if (similarityThreshold != null) {
                sets.add("similarity_threshold = ?");
                params.add(similarityThreshold);
            }
            if (dimensions != null) {
                sets.add("dimensions = ?");
                params.add(toJson(dimensions));
            }
            if (pptDimensions != null) {
                sets.add("ppt_dimensions = ?");
                params.add(toJson(pptDimensions));
            }
            if (htmlDimensions != null) {
                sets.add("html_dimensions = ?");
                params.add(toJson(htmlDimensions));
            }
            if (semimindWindowSize != null) {
                sets.add("semimind_window_size = ?");
                params.add(semimindWindowSize);
            }
            if (promptFields != null) {
                for (Map.Entry<String, String> entry : promptFields.entrySet()) {
                    String column = PROMPT_FIELD_COLUMNS.get(entry.getKey());
                    if (column != null && entry.getValue() != null) {
                        sets.add(column + " = ?");
                        params.add(entry.getValue());
                    }
                }
            }
            if (sets.isEmpty()) {
                return;
            }
            String sql = "UPDATE criteria_config SET " + String.join(", ", sets) + " WHERE id = 1";
            jdbcTemplate.update(sql, params.toArray());
        } catch (Exception e) {
            log.error("更新评判标准失败", e);
            throw new RuntimeException("更新评判标准失败", e);
        }
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
