package com.qatools.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTML 报告对比评分服务（用于“低代码分析”这类标准答案 HTML 报告 vs Agent 生成 HTML 报告的评测场景）。
 * 从三个维度打分并加权（权重可在配置中心“评测标准”页调整，默认 content=0.4/structure=0.4/visual=0.2）：
 * 1. content（内容）：抽取双方正文文本，交给 LLM 评审内容准确性/完整性/相关性
 * 2. structure（结构/标题层级）：对比双方 h1~h6 标题字序列，缺失/多余标题惩罚集合相似度（主），
 *    顺序颠倒但内容都在只轻微扣分（用最长公共子序列衡量，权重较低）
 * 3. visual（图片）：对比双方内嵌 base64 图片的感知哈希相似度
 */
@Service
public class HtmlScoringService {

    private static final Logger log = LoggerFactory.getLogger(HtmlScoringService.class);

    // 结构维度内部：标题集合相似度（缺失/多余） + 顺序相似度（顺序颠倒，辅）
    private static final double TITLE_SET_WEIGHT = 0.7;
    private static final double TITLE_ORDER_WEIGHT = 0.3;

    private static final Pattern BASE64_IMAGE_PATTERN =
            Pattern.compile("data:image/[a-zA-Z0-9.+-]+;base64,([a-zA-Z0-9+/=]+)");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private LlmService llmService;

    @Autowired
    private CriteriaStoreService criteriaStore;

    /**
     * 对比生成 HTML 与标准答案 HTML，返回各维度分数与加权总分（0-1）。
     *
     * @param generatedHtml 生成的 HTML 文本内容
     * @param expectedHtml  标准答案 HTML 文本内容
     * @param judgeModelConfig 用于内容评审的 LLM 模型配置，为 null 时跳过内容维度，按结构+视觉维度重新归一化计算总分
     */
    public Map<String, Object> score(String generatedHtml, String expectedHtml,
            Map<String, Object> judgeModelConfig) {
        Map<String, Object> result = new HashMap<>();

        if (generatedHtml == null || generatedHtml.isBlank()) {
            result.put("final", 0.0);
            result.put("reason", "未获取到 Agent 生成的 HTML 文件");
            return result;
        }

        HtmlContent generated = parseHtml(generatedHtml);
        HtmlContent expected = parseHtml(expectedHtml);

        double structureScore = calculateStructureScore(generated, expected);
        double visualScore = ImageHashUtils.compareImageSets(generated.images, expected.images);

        Map<String, Double> weights = criteriaStore.getHtmlDimensionWeights();
        double llmWeight = weights.getOrDefault("content", 0.4);
        double structureWeight = weights.getOrDefault("structure", 0.4);
        double visualWeight = weights.getOrDefault("visual", 0.2);

        Map<String, Object> dimensions = new HashMap<>();
        dimensions.put("structure", round(structureScore));
        dimensions.put("visual", round(visualScore));

        Double llmScore = null;
        String llmReason = null;
        if (judgeModelConfig != null) {
            try {
                Map<String, Object> llmResult = scoreContentWithLlm(generated, expected, judgeModelConfig);
                if (llmResult != null) {
                    llmScore = (Double) llmResult.get("score");
                    llmReason = (String) llmResult.get("reason");
                }
            } catch (Exception e) {
                log.warn("HTML 内容 LLM 评分失败: {}", e.getMessage());
            }
        }

        double finalScore;
        if (llmScore != null) {
            dimensions.put("content", round(llmScore));
            finalScore = llmScore * llmWeight + structureScore * structureWeight + visualScore * visualWeight;
        } else {
            // 内容维度不可用（未配置评审模型或调用失败时），按结构+视觉重新归一化权重，避免总分无意义空降。
            // 写入 null 占位，让前端知道该维度不可用，但总分仍可基于有效维度产出。
            dimensions.put("content", null);
            double remainingWeight = structureWeight + visualWeight;
            finalScore = (structureScore * structureWeight + visualScore * visualWeight) / remainingWeight;
        }

        result.put("dimensions", dimensions);
        result.put("final", round(finalScore));
        if (llmReason != null) {
            result.put("reason", llmReason);
        }
        result.put("generatedTitleCount", generated.titles.size());
        result.put("expectedTitleCount", expected.titles.size());
        return result;
    }

    // ========== HTML 解析 ==========

    private HtmlContent parseHtml(String html) {
        HtmlContent content = new HtmlContent();
        if (html == null || html.isBlank()) {
            return content;
        }

        try {
            Document doc = Jsoup.parse(html);

            Elements headings = doc.select("h1, h2, h3, h4, h5, h6");
            for (Element heading : headings) {
                String text = heading.text();
                if (text != null && !text.isBlank()) {
                    content.titles.add(text.trim());
                }
            }
            // 正文纯文本用于内容维度 LLM 评审，img 标签与嵌入图片已被 Jsoup.text() 自动忽略
            content.bodyText = doc.body() != null ? doc.body().text() : doc.text();
            content.images = extractBase64Images(html);
        } catch (Exception e) {
            log.warn("解析 HTML 文件失败: {}", e.getMessage());
        }
        return content;
    }

    /** 从 HTML 文本中提取所有 base64 内嵌图片 */
    private List<byte[]> extractBase64Images(String html) {
        List<byte[]> images = new ArrayList<>();
        Matcher matcher = BASE64_IMAGE_PATTERN.matcher(html);
        while (matcher.find()) {
            try {
                images.add(Base64.getDecoder().decode(matcher.group(1)));
            } catch (Exception e) {
                log.debug("解码内嵌 base64 图片失败，跳过: {}", e.getMessage());
            }
        }
        return images;
    }

    // ========== 结构维度：标题集合相似度（缺失/多余，主） + 顺序相似度（顺序颠倒，辅） ==========

    private double calculateStructureScore(HtmlContent generated, HtmlContent expected) {
        if (expected.titles.isEmpty()) {
            // 标准答案没有可比对的标题信息，结构维度不参与惩罚
            return 1.0;
        }
        if (generated.titles.isEmpty()) {
            return 0.0;
        }

        double setScore = titleSetSimilarity(generated.titles, expected.titles);
        double orderScore = titleOrderSimilarity(generated.titles, expected.titles);

        return setScore * TITLE_SET_WEIGHT + orderScore * TITLE_ORDER_WEIGHT;
    }
    /**
     * 标题集合相似度：衡量缺失/多余，与具体顺序无关。用归一化后的标题文本做 Jaccard 相似度。
     */
    private double titleSetSimilarity(List<String> generatedTitles, List<String> expectedTitles) {
        Set<String> setA = new HashSet<>();
        for (String title : generatedTitles) {
            setA.add(normalizeTitle(title));
        }
        Set<String> setB = new HashSet<>();
        for (String title : expectedTitles) {
            setB.add(normalizeTitle(title));
        }

        if (setA.isEmpty() && setB.isEmpty()) return 1.0;
        if (setA.isEmpty() || setB.isEmpty()) return 0.0;

        Set<String> intersection = new HashSet<>(setA);
        intersection.retainAll(setB);
        Set<String> union = new HashSet<>(setA);
        union.addAll(setB);

        return (double) intersection.size() / union.size();
    }

    /**
     * 标题顺序相似度：用最长公共子序列（LCS）衡量双方标题排列顺序的一致程度，
     * 只统计双方都出现过的标题（缺失/多余已由集合相似度惩罚，这里只关心“有的部分顺序对不对”）。
     */
    private double titleOrderSimilarity(List<String> generatedTitles, List<String> expectedTitles) {
        List<String> a = new ArrayList<>();
        for (String title : generatedTitles) {
            a.add(normalizeTitle(title));
        }
        List<String> b = new ArrayList<>();
        for (String title : expectedTitles) {
            b.add(normalizeTitle(title));
        }

        if (a.isEmpty() || b.isEmpty()) {
            return a.isEmpty() && b.isEmpty() ? 1.0 : 0.0;
        }

        int lcsLen = longestCommonSubsequence(a, b);
        int maxLen = Math.max(a.size(), b.size());
        return maxLen == 0 ? 1.0 : (double) lcsLen / maxLen;
    }
    private int longestCommonSubsequence(List<String> a, List<String> b) {
        int n = a.size();
        int m = b.size();
        int[][] dp = new int[n + 1][m + 1];
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                if (a.get(i - 1).equals(b.get(j - 1))) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
                }
            }
        }
        return dp[n][m];
    }

    private String normalizeTitle(String title) {
        if (title == null) return "";
        return title.trim().toLowerCase().replaceAll("\\s+", "");
    }

    // ========== 内容维度：LLM 评审 ==========

    @SuppressWarnings("unchecked")
    private Map<String, Object> scoreContentWithLlm(HtmlContent generated, HtmlContent expected,
            Map<String, Object> modelConfig) {
        String prompt = buildContentJudgePrompt(generated, expected);
        String response = llmService.chat(modelConfig, prompt);
        String jsonStr = LlmJsonScoreUtils.extractJsonFromResponse(response);
        if (jsonStr == null) {
            log.warn("HTML 内容评分返回无法解析: {}", response);
            return null;
        }

        try {
            Map<String, Object> parsed = objectMapper.readValue(jsonStr, Map.class);
            double contentScore = LlmJsonScoreUtils.toDoubleDefault(parsed.get("score"), 0);

            Map<String, Object> result = new HashMap<>();
            result.put("score", round(contentScore));
            result.put("reason", parsed.get("reason"));
            return result;
        } catch (Exception e) {
            log.warn("解析 HTML 内容评分结果失败: {}", e.getMessage());
            return null;
        }
    }

    private String buildContentJudgePrompt(HtmlContent generated, HtmlContent expected) {
        String template = criteriaStore.getHtmlJudgePrompt();
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("expectedContent", expected.bodyText);
        placeholders.put("generatedContent", generated.bodyText);
        return LlmJsonScoreUtils.fillTemplate(template, placeholders);
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static class HtmlContent {
        List<String> titles = new ArrayList<>();
        String bodyText = "";
        List<byte[]> images = Collections.emptyList();
    }
}
