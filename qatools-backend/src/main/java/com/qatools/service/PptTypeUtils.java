package com.qatools.service;

/**
 * 判断“标准答案类型”字符串是否属于 PPT 类。供 MultimodalEvaluationService（评测评分分发）
 * 与 DatasetController（数据集是否有参考答案标记）共用，避免两处各自维护一份相同判断逻辑。
 */
public final class PptTypeUtils {

    private PptTypeUtils() {
    }

    public static boolean isPptExpectedType(String expectedType) {
        return "pptx".equalsIgnoreCase(expectedType) || "ppt".equalsIgnoreCase(expectedType);
    }
}
