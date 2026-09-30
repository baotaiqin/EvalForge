package com.qatools.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFGraphicFrame;
import org.apache.poi.xslf.usermodel.XSLFGroupShape;
import org.apache.poi.xslf.usermodel.XSLFPictureData;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PPT 对比评测服务。
 * 对比"标准答案PPT"与 Agent 生成 PPT，从三个维度打分并加权（权重可在配置中心"评判标准"处调整，
 * 默认 content=0.5/structure=0.3/visual=0.2）
 * 1. content（内容）：抽取双方幻灯片文字，交给 LLM 评审内容准确性/完整性/相关性
 * 2. structure（结构）：幻灯片数量 + 逐页文字重合度的合并比较
 * 3. visual（视觉）：对比双方幻灯片中嵌入图片的感知哈希相似度（无需渲染整页 PPT）
 */
@Service
public class PptScoringService {

    private static final Logger log = LoggerFactory.getLogger(PptScoringService.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private LlmService llmService;

    @Autowired
    private CriteriaStoreService criteriaStore;

    /**
     * 对比生成 PPT 与标准答案 PPT，返回各维度分数与加权总分（0-1）。
     *
     * @param generatedPpt 生成的 PPT 二进制内容
     * @param expectedPpt 标准答案 PPT 二进制内容
     * @param judgeModelConfig 用于内容评审的 LLM 模型配置；为 null 或调用失败时，内容维度跳过，
     *                         按结构和视觉维度的权重重新归一化计算总分
     * @return 各维度分数、总分及幻灯片数量
     */
    public Map<String, Object> score(byte[] generatedPpt, byte[] expectedPpt, Map<String, Object> judgeModelConfig) {
        Map<String, Object> result = new HashMap<>();
        if (generatedPpt == null || generatedPpt.length == 0) {
            result.put("final", 0.0);
            result.put("reason", "未获取到 Agent 生成的 PPT 文件");
            return result;
        }

        PptContent generated;
        PptContent expected;
        try {
            generated = parsePpt(generatedPpt);
            expected = parsePpt(expectedPpt);
        } catch (Exception e) {
            log.warn("解析 PPT 文件失败: {}", e.getMessage());
            result.put("final", 0.0);
            result.put("reason", "PPT 文件解析失败: " + e.getMessage());
            return result;
        }

        double structureScore = calculateStructureScore(generated, expected);
        double visualScore = calculateVisualScore(generated, expected);
        Map<String, Double> weights = criteriaStore.getPptDimensionWeights();
        double contentWeight = weights.getOrDefault("content", 0.5);
        double structureWeight = weights.getOrDefault("structure", 0.3);
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
                log.warn("PPT 内容 LLM 评分失败: {}", e.getMessage());
            }
        }

        double finalScore;
        if (llmScore != null) {
            dimensions.put("content", round(llmScore));
            finalScore = llmScore * contentWeight
                    + structureScore * structureWeight
                    + visualScore * visualWeight;
        } else {
            // 内容维度不可用时，按结构和视觉维度的权重重新归一化，避免总分失真。
            // 仍写入 null 占位，前端可区分未评分与维度不存在。
            dimensions.put("content", null);
            double remainingWeight = structureWeight + visualWeight;
            finalScore = (structureScore * structureWeight + visualScore * visualWeight) / remainingWeight;
        }

        result.put("dimensions", dimensions);
        result.put("final", round(finalScore));
        if (llmReason != null) {
            result.put("reason", llmReason);
        }
        result.put("generatedSlideCount", generated.slideTexts.size());
        result.put("expectedSlideCount", expected.slideTexts.size());
        return result;
    }

    // -------- PPT 解析 --------

    private PptContent parsePpt(byte[] data) throws Exception {
        PptContent content = new PptContent();
        if (data == null || data.length == 0) {
            return content;
        }
        try (XMLSlideShow slideShow = new XMLSlideShow(new ByteArrayInputStream(data))) {
            for (XSLFSlide slide : slideShow.getSlides()) {
                StringBuilder slideText = new StringBuilder();
                collectShapeContent(slide.getShapes(), slideText, content.images);
                content.slideTexts.add(slideText.toString().trim());
            }
        }
        return content;
    }

    /**
     * 遍历收集形状中的文字与图片，覆盖普通文本框、表格（XSLFTable）和分组形状（XSLFGroupShape，可多层嵌套）。
     */
    private void collectShapeContent(Iterable<XSLFShape> shapes, StringBuilder slideText, List<byte[]> images) {
        for (XSLFShape shape : shapes) {
            if (shape instanceof XSLFTable) {
                for (XSLFTableRow row : (XSLFTable) shape) {
                    for (XSLFTableCell cell : row) {
                        String text = cell.getText();
                        if (text != null && !text.isBlank()) {
                            slideText.append(text.trim()).append("\n");
                        }
                    }
                }
            } else if (shape instanceof XSLFGroupShape) {
                collectShapeContent(((XSLFGroupShape) shape).getShapes(), slideText, images);
            } else if (shape instanceof XSLFGraphicFrame) {
                // 非表格的图形框（如图表）不含可对比的文本/图片内容，跳过。
            } else if (shape instanceof XSLFTextShape) {
                String text = ((XSLFTextShape) shape).getText();
                if (text != null && !text.isBlank()) {
                    slideText.append(text.trim()).append('\n');
                }
            } else if (shape instanceof XSLFPictureShape) {
                XSLFPictureData pictureData = ((XSLFPictureShape) shape).getPictureData();
                if (pictureData != null) {
                    images.add(pictureData.getData());
                }
            }
        }
    }

    // -------- 结构维度：幻灯片数量 + 逐页文字重合度 --------

    private double calculateStructureScore(PptContent generated, PptContent expected) {
        int expectedCount = expected.slideTexts.size();
        int generatedCount = generated.slideTexts.size();
        if (expectedCount == 0) {
            // 标准答案没有幻灯片信息，结构维度不参与惩罚。
            return 1.0;
        }

        double slideCountScore = 1.0 - Math.min(1.0,
                Math.abs(generatedCount - expectedCount) / (double) expectedCount);
        int comparableSlides = Math.min(generatedCount, expectedCount);
        double outlineScore = 0.0;
        if (comparableSlides > 0) {
            double outlineScoreSum = 0.0;
            for (int i = 0; i < comparableSlides; i++) {
                outlineScoreSum += jaccardSimilarity(generated.slideTexts.get(i), expected.slideTexts.get(i));
            }
            outlineScore = outlineScoreSum / comparableSlides;
        }
        return slideCountScore * 0.4 + outlineScore * 0.6;
    }

    private double jaccardSimilarity(String a, String b) {
        Set<String> tokensA = tokenize(a);
        Set<String> tokensB = tokenize(b);
        if (tokensA.isEmpty() && tokensB.isEmpty()) {
            return 1.0;
        }
        if (tokensA.isEmpty() || tokensB.isEmpty()) {
            return 0.0;
        }

        Set<String> intersection = new HashSet<>(tokensA);
        intersection.retainAll(tokensB);
        Set<String> union = new HashSet<>(tokensA);
        union.addAll(tokensB);
        return intersection.size() / (double) union.size();
    }

    private Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>();
        if (text == null || text.isBlank()) {
            return java.util.Collections.emptySet();
        }

        Matcher matcher = Pattern.compile("[\\u4e00-\\u9fa5]|[a-zA-Z0-9]+").matcher(text);
        while (matcher.find()) {
            tokens.add(matcher.group().toLowerCase());
        }
        return tokens;
    }

    // -------- 视觉维度：嵌入图片感知哈希对比 --------

    private double calculateVisualScore(PptContent generated, PptContent expected) {
        return ImageHashUtils.compareImages(generated.images, expected.images);
    }

    // -------- 内容维度：LLM 评审 --------

    @SuppressWarnings("unchecked")
    private Map<String, Object> scoreContentWithLlm(PptContent generated, PptContent expected, Map<String, Object> modelConfig) {
        String prompt = buildContentJudgePrompt(generated, expected);
        String response = llmService.chat(modelConfig, prompt);
        String jsonStr = LlmJsonScoreUtils.extractJsonFromResponse(response);
        if (jsonStr == null) {
            log.warn("PPT 内容评分返回无法解析: {}", response);
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
            log.warn("解析 PPT 内容评分结果失败: {}", e.getMessage());
            return null;
        }
    }

    private String buildContentJudgePrompt(PptContent generated, PptContent expected) {
        String template = criteriaStore.getPptJudgePrompt();
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("expectedSlides", joinSlides(expected));
        placeholders.put("generatedSlides", joinSlides(generated));
        return LlmJsonScoreUtils.fillTemplate(template, placeholders);
    }

    private String joinSlides(PptContent content) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < content.slideTexts.size(); i++) {
            sb.append("第").append(i + 1).append("页：")
                    .append(content.slideTexts.get(i)).append("\n");
        }
        return sb.toString();
    }

    private double round(double value) {
        return Math.round(value * 100) / 100.0;
    }

    private static class PptContent {
        private final List<String> slideTexts = new ArrayList<>();
        private final List<byte[]> images = new ArrayList<>();
    }
}
