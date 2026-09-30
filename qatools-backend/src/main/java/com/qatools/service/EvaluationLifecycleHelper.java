package com.qatools.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 评测记录结束态收尾 + SSE 安全推送的公共逻辑。
 * EvaluationService/MultimodalEvaluationService 均通过此类完成 completed/stopped/failed，
 * 三种结束态的落库与状态广播，以及带异常保护的 SSE 事件推送，避免两个 Service 各自维护一份重复实现。
 *
 * 同时承载评测记录创建/更新时的类型元信息推断(normalizeEvaluationMetadata)，
 * 供 EvaluationController(Agent评测)和 SubEvaluationController(模型测评开始测评)两处
 * 创建 eval_record 的入口共用，避免复制粘贴一份推断逻辑。
 */
@Component
public class EvaluationLifecycleHelper {

    private static final Logger log = LoggerFactory.getLogger(EvaluationLifecycleHelper.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String EVALUATION_TYPE_TEXT = "text";
    private static final String EVALUATION_TYPE_MULTIMODAL = "multimodal";
    private static final String REPORT_TYPE_TEXT = "text";
    private static final String REPORT_TYPE_MULTIMODAL = "multimodal";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private EvaluationStoreService evalStore;

    @Autowired
    private EvaluationStatusBroadcaster statusBroadcaster;

    @Autowired
    private EvaluationProgressBroadcaster progressBroadcaster;

    @Autowired
    private DatasetStoreService datasetStore;

    public void markCompleted(Map<String, Object> eval) {
        eval.put("status", EvaluationStatusBroadcaster.STATUS_COMPLETED);
        persistAndBroadcast(eval, EvaluationStatusBroadcaster.STATUS_COMPLETED);
    }

    public void markStopped(Map<String, Object> eval) {
        eval.put("status", EvaluationStatusBroadcaster.STATUS_STOPPED);
        persistAndBroadcast(eval, EvaluationStatusBroadcaster.STATUS_STOPPED);
    }

    public void markFailed(Map<String, Object> eval, String reason) {
        eval.put("status", EvaluationStatusBroadcaster.STATUS_FAILED);
        eval.put("endTime", LocalDateTime.now().format(FMT));
        eval.put("remark", reason);
        persistAndBroadcast(eval, EvaluationStatusBroadcaster.STATUS_FAILED);
        log.error("评测失败：{}", reason);
    }

    private void persistAndBroadcast(Map<String, Object> eval, String status) {
        String evalId = (String) eval.get("id");
        if (evalId != null) {
            evalStore.put(evalId, eval);
            statusBroadcaster.broadcast(evalId, status);
        }
    }

    /**
     * 安全推送 SSE 事件：客户端断开(Broken pipe)等异常只记录日志，不向上抛出，
     * 避免一次推送失败导致整个评测后台任务中断(剩余题目跑不完、评测记录卡在 running)。
     */
    public void safeSend(SseEmitter emitter, String eventName, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(eventName)
                    .data(objectMapper.writeValueAsString(payload), MediaType.TEXT_PLAIN));
        } catch (Exception e) {
            log.warn("推送{}事件失败，评测将继续在后台执行：{}", eventName, e.getMessage());
        }
    }

    /**
     * 推送单个 target 在当前题目下的执行步骤(调用中/已应答/评分中/已评分)，
     * 供详情页在题目真正完成前也能展示实时进展，而不必等到整题结束
     */
    public void pushStep(String evalId, int questionIndex, String targetId, String phase, String message) {
        pushStep(evalId, questionIndex, targetId, phase, message, null);
    }

    public void pushStep(String evalId, int questionIndex, String targetId, String phase, String message,
                         Object responseTime) {
        Map<String, Object> stepEvent = new HashMap<>();
        stepEvent.put("questionIndex", questionIndex);
        stepEvent.put("targetId", targetId);
        stepEvent.put("phase", phase);
        stepEvent.put("message", message);
        if (responseTime != null) {
            stepEvent.put("responseTime", responseTime);
        }
        progressBroadcaster.push(evalId, "step", stepEvent);
    }

    // ----------------------------------------------------------------
    // 评测类型元信息推断（从 EvaluationController 抽取，供 SubEvaluationController 复用）
    // ----------------------------------------------------------------

    public void normalizeEvaluationMetadata(Map<String, Object> eval) {
        if (eval == null) {
            return;
        }

        String explicitEvaluationType = normalizeEvaluationType(firstPresent(eval, "evaluationType", "evaluation_type"));
        String explicitReportType = normalizeReportType(firstPresent(eval, "reportType", "report_type"));
        Boolean explicitHasMultimodal = toBooleanObject(firstPresent(eval, "hasMultimodal", "has_multimodal"));

        boolean inferredHasMultimodal = inferHasMultimodal(eval);

        boolean hasMultimodal = inferredHasMultimodal
                || Boolean.TRUE.equals(explicitHasMultimodal)
                || EVALUATION_TYPE_MULTIMODAL.equals(explicitEvaluationType);

        String evaluationType = explicitEvaluationType != null
                ? explicitEvaluationType
                : (hasMultimodal ? EVALUATION_TYPE_MULTIMODAL : EVALUATION_TYPE_TEXT);

        if (EVALUATION_TYPE_MULTIMODAL.equals(evaluationType)) {
            hasMultimodal = true;
        }

        String reportType = explicitReportType != null
                ? explicitReportType
                : (EVALUATION_TYPE_MULTIMODAL.equals(evaluationType) ? REPORT_TYPE_MULTIMODAL : REPORT_TYPE_TEXT);

        eval.put("evaluationType", evaluationType);
        eval.put("reportType", reportType);
        eval.put("hasMultimodal", hasMultimodal);

        eval.remove("evaluation_type");
        eval.remove("report_type");
        eval.remove("has_multimodal");
    }

    private boolean inferHasMultimodal(Map<String, Object> eval) {
        if (eval == null) {
            return false;
        }

        if (isDatasetLikeMultimodal(eval)) {
            return true;
        }

        Set<String> datasetIds = extractDatasetIds(eval);
        for (String datasetId : datasetIds) {
            Map<String, Object> dataset = datasetStore.get(datasetId);
            if (isDatasetLikeMultimodal(dataset)) {
                return true;
            }
        }

        return false;
    }

    private Set<String> extractDatasetIds(Map<String, Object> eval) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();

        collectDatasetIds(firstPresent(eval, "datasetIds", "dataset_ids"), ids, true);
        collectDatasetIds(firstPresent(eval, "datasetId", "dataset_id"), ids, true);
        collectDatasetIds(firstPresent(eval, "datasetMapping", "dataset_mapping"), ids, false);
        collectDatasetIds(firstPresent(eval, "targets"), ids, false);

        return ids;
    }

    private void collectDatasetIds(Object value, Set<String> ids, boolean directDatasetField) {
        if (value == null || ids == null) {
            return;
        }

        if (value instanceof String) {
            String text = ((String) value).trim();
            if (text.isEmpty()) {
                return;
            }

            if (text.contains(",")) {
                String[] parts = text.split(",");
                for (String part : parts) {
                    collectDatasetIds(part, ids, directDatasetField);
                }
                return;
            }

            if (directDatasetField || text.startsWith("ds-")) {
                ids.add(text);
            }
            return;
        }

        if (value instanceof Collection<?>) {
            for (Object item : (Collection<?>) value) {
                collectDatasetIds(item, ids, directDatasetField);
            }
            return;
        }

        if (value instanceof Map<?, ?>) {
            Map<?, ?> map = (Map<?, ?>) value;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = entry.getKey() == null ? "" : entry.getKey().toString().toLowerCase(Locale.ROOT);
                boolean nextDirect = directDatasetField || key.contains("dataset");
                collectDatasetIds(entry.getValue(), ids, nextDirect);
            }
        }
    }

    private boolean isDatasetLikeMultimodal(Map<String, Object> map) {
        if (map == null) {
            return false;
        }

        Object hasMultimodalValue = firstPresent(map, "hasMultimodal", "has_multimodal");
        Boolean hasMultimodal = toBooleanObject(hasMultimodalValue);
        if (Boolean.TRUE.equals(hasMultimodal)) {
            return true;
        }

        String datasetType = asString(firstPresent(map, "datasetType", "dataset_type"));
        if (datasetType != null && EVALUATION_TYPE_MULTIMODAL.equals(datasetType.trim().toLowerCase(Locale.ROOT))) {
            return true;
        }

        List<String> modalities = normalizeModalities(firstPresent(map, "modalities"));
        return modalities.contains("image") || modalities.contains("file");
    }

    private Object firstPresent(Map<String, Object> map, String... keys) {
        if (map == null || keys == null) {
            return null;
        }

        for (String key : keys) {
            if (map.containsKey(key)) {
                return map.get(key);
            }
        }

        return null;
    }

    private String normalizeEvaluationType(Object value) {
        String text = asString(value);
        if (text == null || text.trim().isEmpty()) {
            return null;
        }

        text = text.trim().toLowerCase(Locale.ROOT);
        if (EVALUATION_TYPE_MULTIMODAL.equals(text)) {
            return EVALUATION_TYPE_MULTIMODAL;
        }
        if (EVALUATION_TYPE_TEXT.equals(text)) {
            return EVALUATION_TYPE_TEXT;
        }

        return null;
    }

    private String normalizeReportType(Object value) {
        String text = asString(value);
        if (text == null || text.trim().isEmpty()) {
            return null;
        }

        text = text.trim().toLowerCase(Locale.ROOT);
        if (REPORT_TYPE_MULTIMODAL.equals(text)) {
            return REPORT_TYPE_MULTIMODAL;
        }
        if (REPORT_TYPE_TEXT.equals(text)) {
            return REPORT_TYPE_TEXT;
        }

        return null;
    }

    private Boolean toBooleanObject(Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof Boolean) {
            return (Boolean) value;
        }

        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }

        String text = value.toString().trim().toLowerCase(Locale.ROOT);
        if ("true".equals(text) || "1".equals(text) || "yes".equals(text) || "y".equals(text)) {
            return true;
        }
        if ("false".equals(text) || "0".equals(text) || "no".equals(text) || "n".equals(text)) {
            return false;
        }

        return null;
    }

    private List<String> normalizeModalities(Object value) {
        LinkedHashSet<String> result = new LinkedHashSet<>();

        if (value == null) {
            return new ArrayList<>(result);
        }

        if (value instanceof Collection<?>) {
            for (Object item : (Collection<?>) value) {
                addNormalizedModality(result, item);
            }
            return new ArrayList<>(result);
        }

        String text = value.toString().trim();
        if (text.isEmpty()) {
            return new ArrayList<>(result);
        }

        String cleaned = text.replace("[", "")
                .replace("]", "")
                .replace("\"", "")
                .replace("'", "");

        String[] parts = cleaned.split(",");
        for (String part : parts) {
            addNormalizedModality(result, part);
        }

        return new ArrayList<>(result);
    }

    private void addNormalizedModality(Set<String> result, Object value) {
        if (result == null || value == null) {
            return;
        }

        String text = value.toString().trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return;
        }

        if (text.contains("image") || text.contains("img") || text.contains("picture")) {
            result.add("image");
            return;
        }

        if (text.contains("file") || text.contains("document") || text.contains("doc") || text.contains("pdf")) {
            result.add("file");
            return;
        }

        if (text.contains("text")) {
            result.add("text");
            return;
        }

        result.add(text);
    }

    private String asString(Object value) {
        return value == null ? null : value.toString();
    }
}
