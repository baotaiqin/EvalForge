package com.qatools.service;


import java.time.LocalDateTime;

import java.time.format.DateTimeFormatter;

import java.util.ArrayList;

import java.util.Collection;

import java.util.Collections;

import java.util.HashMap;

import java.util.HashSet;

import java.util.LinkedHashMap;

import java.util.List;

import java.util.Locale;

import java.util.Map;

import java.util.Set;

import java.util.concurrent.CompletableFuture;

import java.util.concurrent.ExecutorService;

import java.util.concurrent.Executors;

import java.util.concurrent.TimeUnit;


import org.slf4j.Logger;

import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.http.MediaType;

import org.springframework.stereotype.Service;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;


import com.fasterxml.jackson.databind.ObjectMapper;

import com.qatools.config.PlatformDetectionProperties;


/**
 * 评测执行服务
 * 负责异步执行评测任务，遍历数据集、调用模型/Agent、收集结果、更新状态
 */
@Service
public class EvaluationService {

    private static final Logger log = LoggerFactory.getLogger(EvaluationService.class);

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");


    private static final String CREDENTIAL_MODE_PERSONAL = "personal";

    private static final String CREDENTIAL_MODE_SERVICE_ACCOUNT = "service-account";

    private static final String EVALUATION_TYPE_TEXT = "text";

    private static final String EVALUATION_TYPE_MULTIMODAL = "multimodal";


    private final ExecutorService evalExecutor = Executors.newFixedThreadPool(3);

    private final ObjectMapper objectMapper = new ObjectMapper();


    @Autowired
    private EvaluationStoreService evalStore;

    @Autowired
    private DatasetStoreService datasetStore;

    @Autowired
    private ModelStoreService modelStore;

    @Autowired
    private LlmService llmService;

    @Autowired
    private AgentChatService agentChatService;

    @Autowired
    private SemiclawAgentChatService semiclawAgentChatService;

    @Autowired
    private EnvironmentStoreService environmentStore;

    @Autowired
    private UserLoginService userLoginService;

    @Autowired
    private EnvAgentService envAgentService;

    @Autowired
    private MultimodalEvaluationService multimodalEvaluationService;

    @Autowired
    private PlatformDetectionProperties platformDetection;

    @Autowired
    private EvaluationProgressBroadcaster progressBroadcaster;

    @Autowired
    private EvaluationCancellationRegistry cancellationRegistry;

    @Autowired
    private EvaluationLifecycleHelper lifecycle;

    @Autowired
    private CriteriaStoreService criteriaStore;


    /** 异步执行评测任务 */
    @SuppressWarnings("unchecked")
    public void executeAsync(String evalId) {
        evalExecutor.submit(() -> {
            try {
                log.info("开始执行评测: {}", evalId);

                Map<String, Object> eval = evalStore.get(evalId);

                if (eval == null) {
                    log.error("评测记录不存在: {}", evalId);

                    return;

                }
                if (isMultimodalEvaluation(eval)) {
                    multimodalEvaluationService.executeAsync(evalId, eval);

                    return;

                }

                String type = (String) eval.get("type");

                String judgeMode = (String) eval.get("judgeMode");

                String judgeModelId = (String) eval.get("judgeModelId");

                List<Map<String, Object>> targets = (List<Map<String, Object>>) eval.get("targets");

                List<String> datasetIds = (List<String>) eval.get("datasetIds");

                if (targets == null || targets.isEmpty()) {
                    lifecycle.markFailed(eval, "未选择评测目标");

                    return;

                }
                if (datasetIds == null || datasetIds.isEmpty()) {
                    lifecycle.markFailed(eval, "未选择数据集");

                    return;

                }

                List<Map<String, Object>> allItems = collectItems(datasetIds);

                if (allItems.isEmpty()) {
                    lifecycle.markFailed(eval, "数据集中无题目数据");

                    return;

                }
                log.info("评测 {} 共 {} 道题，{} 个目标", evalId, allItems.size(), targets.size());

                List<Map<String, Object>> results = "model".equals(type)
                        ? executeModelEvaluation(targets, allItems, judgeMode, judgeModelId)
                        : executeAgentEvaluation(targets, allItems, judgeMode, judgeModelId);

                eval.put("results", results);

                eval.put("endTime", LocalDateTime.now().format(FMT));

                applyAccuracy(eval, results);

                lifecycle.markCompleted(eval);

                log.info("评测 {} 执行完成，共 {} 条结果", evalId, results.size());

            } catch (Exception e) {
                log.error("评测执行异常: {}", evalId, e);

                Map<String, Object> eval = evalStore.get(evalId);

                if (eval != null) {
                    lifecycle.markFailed(eval, "执行异常：" + e.getMessage());

                }
            }
        });

    }

    /** SSE 流式执行评测 - 逐题将 Agent 推送结果到前端 */
    public void executeStreaming(String evalId, SseEmitter emitter) throws Exception {
        executeStreaming(evalId, emitter, null);

    }

    /**
     * 流式执行评测。targetIndexes 为 null 或空表示需执行全部题目（保持原有行为）。
     * 非空时只命中其中的题目索引(0-based)真正发起调用并评分，其余题目直接使用已有旧结果。
     */
    @SuppressWarnings("unchecked")
    public void executeStreaming(String evalId, SseEmitter emitter, Set<Integer> targetIndexes) throws Exception {
        Map<String, Object> eval = evalStore.get(evalId);

        if (eval == null) {
            throw new RuntimeException("评测记录不存在: " + evalId);

        }
        if (isMultimodalEvaluation(eval)) {
            multimodalEvaluationService.executeStreaming(evalId, eval, emitter, targetIndexes);

            return;

        }

        String type = (String) eval.get("type");

        String judgeMode = (String) eval.get("judgeMode");

        String judgeModelId = (String) eval.get("judgeModelId");

        List<Map<String, Object>> targets = (List<Map<String, Object>>) eval.get("targets");

        List<String> datasetIds = (List<String>) eval.get("datasetIds");

        if (targets == null || targets.isEmpty()) {
            throw new RuntimeException("未选择评测目标");

        }
        if (datasetIds == null || datasetIds.isEmpty()) {
            throw new RuntimeException("未选择数据集");

        }

        List<Map<String, Object>> allItems = collectItems(datasetIds);

        if (allItems.isEmpty()) {
            throw new RuntimeException("数据集中无题目数据");

        }
        boolean partialRerun = targetIndexes != null && !targetIndexes.isEmpty();

        List<Map<String, Object>> existingResults = partialRerun
                ? (List<Map<String, Object>>) eval.getOrDefault("results", Collections.emptyList())
                : Collections.emptyList();

        log.info("流式评测 {} 共 {} 道题，{} 个目标，partialRerun={}, targetIndexes={}",
                evalId, allItems.size(), targets.size(), partialRerun, targetIndexes);


        Map<String, Object> initEvent = new HashMap<>();

        initEvent.put("evalId", evalId);

        initEvent.put("total", allItems.size());

        initEvent.put("targetCount", targets.size());

        initEvent.put("evaluationType", EVALUATION_TYPE_TEXT);

        lifecycle.safeSend(emitter, "init", initEvent);


        if ("agent".equals(type)) {
            log.info("流式评测 {}: 开始Agent预登录/参数准备...", evalId);

            prepareAgentAuth(targets);

            log.info("流式评测 {}: Agent预登录/参数准备完成", evalId);

        }

        List<Map<String, Object>> results;

        if (partialRerun) {
            results = new ArrayList<>(existingResults);

            while (results.size() < allItems.size()) {
                results.add(null);

            }
        } else {
            results = new ArrayList<>();

        }

        int itemIndex = 0;

        cancellationRegistry.markActive(evalId);

        try {
            for (Map<String, Object> item : allItems) {
                if (cancellationRegistry.isCancelled(evalId)) {
                    log.info("流式评测 {} 收到终止请求，停止后续题目", evalId);

                    break;

                }
                int currentIndex = itemIndex++;

                if (partialRerun && !targetIndexes.contains(currentIndex)) {
                    Map<String, Object> reused = currentIndex < existingResults.size()
                            ? existingResults.get(currentIndex) : null;

                    if (reused != null) {
                        continue;

                    }
                }

                String question = stringValue(item.get("question"));

                String expectedAnswer = stringValue(item.get("expectedAnswer"));

                String itemId = item.get("id") != null ? item.get("id").toString() : "q-" + itemIndex;


                Map<String, Object> progressEvent = new HashMap<>();

                progressEvent.put("questionIndex", currentIndex);

                progressEvent.put("total", allItems.size());

                progressEvent.put("message", partialRerun
                        ? "本次重跑共 " + targetIndexes.size() + " 道题，当前重跑 " + itemIndex + " 题"
                        : "处理第 " + itemIndex + "/" + allItems.size() + " 道题");

                progressEvent.put("question", question);

                progressEvent.put("questionImages", item.getOrDefault("images", new ArrayList<>()));

                progressEvent.put("hasExpected", !isBlank(expectedAnswer));

                lifecycle.safeSend(emitter, "progress", progressEvent);

                progressBroadcaster.push(evalId, "progress", progressEvent);


                Map<String, Object> outputs = new HashMap<>();

                Map<String, Object> scores = new HashMap<>();

                if ("agent".equals(type)) {
                    executeAgentQuestionStreaming(evalId, targets, question, expectedAnswer, currentIndex,
                            outputs, scores, emitter, judgeMode, judgeModelId);

                } else {
                    executeModelQuestionStreaming(evalId, targets, question, expectedAnswer, currentIndex,
                            outputs, scores, emitter, judgeMode, judgeModelId);

                }

                Map<String, Object> scoreEvent = new HashMap<>();

                scoreEvent.put("questionIndex", currentIndex);

                scoreEvent.put("scores", scores);

                lifecycle.safeSend(emitter, "score", scoreEvent);


                Map<String, Object> result = buildResult(itemId, question, item, expectedAnswer, outputs, scores);

                if (partialRerun) {
                    results.set(currentIndex, result);

                } else {
                    results.add(result);

                }
                eval.put("results", new ArrayList<>(results));

                evalStore.put(evalId, eval);

                Map<String, Object> questionEvent = new HashMap<>();

                questionEvent.put("questionIndex", currentIndex);

                questionEvent.put("result", result);

                progressBroadcaster.push(evalId, "question", questionEvent);

            }

            boolean stopped = cancellationRegistry.isCancelled(evalId);

            eval.put("results", results);

            eval.put("endTime", LocalDateTime.now().format(FMT));

            applyAccuracy(eval, results);

            if (stopped) {
                lifecycle.markStopped(eval);

            } else {
                lifecycle.markCompleted(eval);

            }

            Map<String, Object> completeEvent = new HashMap<>();

            completeEvent.put("evalId", evalId);

            completeEvent.put("status", eval.get("status"));

            completeEvent.put("accuracy", eval.get("accuracy"));

            completeEvent.put("endTime", eval.get("endTime"));

            completeEvent.put("evaluationType", EVALUATION_TYPE_TEXT);

            lifecycle.safeSend(emitter, "complete", completeEvent);

            progressBroadcaster.push(evalId, "complete", completeEvent);

            log.info("流式评测 {} 完成，共 {} 条结果，状态={}", evalId, results.size(), eval.get("status"));

        } finally {
            cancellationRegistry.clear(evalId);

            cancellationRegistry.markInactive(evalId);

            progressBroadcaster.closeAll(evalId);

        }
    }

    private List<Map<String, Object>> collectItems(List<String> datasetIds) {
        List<Map<String, Object>> items = new ArrayList<>();

        for (String datasetId : datasetIds) {
            Map<String, Object> dataset = datasetStore.get(datasetId);

            if (dataset != null && dataset.get("items") instanceof List<?>) {
                for (Object item : (List<?>) dataset.get("items")) {
                    if (item instanceof Map<?, ?>) {
                        items.add(toStringObjectMap(item));

                    }
                }
            }
        }
        return items;

    }

    private Map<String, Object> buildResult(String itemId, String question, Map<String, Object> item,
            String expectedAnswer, Map<String, Object> outputs, Map<String, Object> scores) {
        Map<String, Object> result = new HashMap<>();

        result.put("id", "r-" + itemId);

        result.put("questionId", itemId);

        result.put("question", question);

        result.put("questionImages", item.getOrDefault("images", new ArrayList<>()));

        result.put("expectedAnswer", expectedAnswer);

        result.put("outputs", outputs);

        result.put("scores", scores);

        result.put("hasExpected", !isBlank(expectedAnswer));

        result.put("judgeMode", isBlank(expectedAnswer) ? "auto" : "manual");

        return result;

    }

    private boolean isMultimodalEvaluation(Map<String, Object> eval) {
        if (eval == null) {
            return false;

        }
        String evaluationType = normalizeEvaluationType(eval.get("evaluationType"), null);

        if (EVALUATION_TYPE_MULTIMODAL.equals(evaluationType)) {
            return true;

        }
        String reportType = normalizeEvaluationType(eval.get("reportType"), null);

        if (EVALUATION_TYPE_MULTIMODAL.equals(reportType)) {
            return true;

        }
        if (asBoolean(eval.get("hasMultimodal"), false)) {
            return true;

        }
        return isMultimodalByDatasets(eval);

    }

    private String normalizeEvaluationType(Object value, String defaultValue) {
        if (value == null) {
            return defaultValue;

        }
        String text = value.toString().trim().toLowerCase(Locale.ROOT);

        if (EVALUATION_TYPE_MULTIMODAL.equals(text)) {
            return EVALUATION_TYPE_MULTIMODAL;

        }
        if (EVALUATION_TYPE_TEXT.equals(text)) {
            return EVALUATION_TYPE_TEXT;

        }
        return defaultValue;

    }

    private boolean asBoolean(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;

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
        return defaultValue;

    }

    @SuppressWarnings("unchecked")
    private boolean isMultimodalByDatasets(Map<String, Object> eval) {
        List<String> datasetIds = toStringList(eval.get("datasetIds"));

        if (datasetIds.isEmpty()) {
            return false;

        }
        for (String datasetId : datasetIds) {
            if (isBlank(datasetId)) {
                continue;

            }
            try {
                Map<String, Object> dataset = datasetStore.get(datasetId);

                if (dataset == null) {
                    continue;

                }
                String reason = detectDatasetMultimodalReason(dataset);

                if (!isBlank(reason)) {
                    eval.put("evaluationType", EVALUATION_TYPE_MULTIMODAL);

                    eval.put("reportType", EVALUATION_TYPE_MULTIMODAL);

                    eval.put("hasMultimodal", true);

                    log.info("评测自动识别为多模态: evalId={}, datasetId={}, reason={}",
                            stringValue(eval.get("id")), datasetId, reason);

                    return true;

                }
            } catch (Exception e) {
                log.warn("评测自动识别多模态失败: evalId={}, datasetId={}, error={}",
                        stringValue(eval.get("id")), datasetId, e.getMessage());

            }
        }
        return false;

    }

    @SuppressWarnings("unchecked")
    private String detectDatasetMultimodalReason(Map<String, Object> dataset) {
        if (dataset == null || dataset.isEmpty()) {
            return null;

        }
        String datasetType = normalizeEvaluationType(firstNonBlank(
                stringValue(dataset.get("datasetType")), stringValue(dataset.get("type")),
                stringValue(dataset.get("evaluationType")), stringValue(dataset.get("reportType"))), null);

        if (EVALUATION_TYPE_MULTIMODAL.equals(datasetType)) {
            return "datasetType/evaluationType=multimodal";

        }
        if (asBoolean(dataset.get("hasMultimodal"), false)) {
            return "dataset.hasMultimodal=true";

        }
        if (containsMultimodalModality(dataset.get("modalities"))
                || containsMultimodalModality(dataset.get("modality"))) {
            return "dataset.modalities contains image/file";

        }
        if (hasAnyNonEmptyValue(dataset, "questionImages", "question_images", "images", "image",
                "imageUrl", "image_url", "imageUrls", "image_urls", "questionFiles", "question_files",
                "files", "file", "fileUrl", "file_url", "fileUrls", "file_urls", "attachments", "media")) {
            return "dataset root contains image/file fields";

        }
        Object itemsObj = dataset.get("items");

        if (itemsObj instanceof Collection<?>) {
            int index = 0;

            for (Object itemObj : (Collection<?>) itemsObj) {
                index++;

                if (!(itemObj instanceof Map<?, ?>)) {
                    continue;

                }
                String itemReason = detectItemMultimodalReason(toStringObjectMap(itemObj));

                if (!isBlank(itemReason)) {
                    return "item[" + index + "] " + itemReason;

                }
            }
        }
        return null;

    }

    private String detectItemMultimodalReason(Map<String, Object> item) {
        if (item == null || item.isEmpty()) {
            return null;

        }
        if (asBoolean(item.get("hasMultimodal"), false) || asBoolean(item.get("multimodal"), false)) {
            return "hasMultimodal/multimodal=true";

        }
        if (containsMultimodalModality(item.get("modalities"))
                || containsMultimodalModality(item.get("modality"))
                || containsMultimodalModality(item.get("inputType"))
                || containsMultimodalModality(item.get("input_type"))) {
            return "modality/inputType contains image/file";

        }
        if (hasAnyNonEmptyValue(item, "questionImages", "question_images", "images", "image",
                "imageUrl", "image_url", "imageUrls", "image_urls", "questionFiles", "question_files",
                "files", "file", "fileUrl", "file_url", "fileUrls", "file_urls", "attachments", "media")) {
            return "contains image/file fields";

        }
        Object expected = item.get("expected");

        if (expected instanceof Map<?, ?>) {
            Object expectedType = ((Map<?, ?>) expected).get("type");

            if (expectedType != null && PptTypeUtils.isPptExpectedType(expectedType.toString())) {
                return "expected.type=pptx/ppt（标准答案PPT）";

            }
            if (expectedType != null && HtmlTypeUtils.isHtmlExpectedType(expectedType.toString())) {
                return "expected.type=html（标准答案HTML报告）";

            }
        }
        return messagesContainMultimodal(item.get("messages")) ? "messages contain image/file content" : null;

    }

    private boolean messagesContainMultimodal(Object messagesObj) {
        if (!(messagesObj instanceof Collection<?>)) {
            return false;

        }
        for (Object messageObj : (Collection<?>) messagesObj) {
            if (!(messageObj instanceof Map<?, ?>)) {
                continue;

            }
            Map<String, Object> message = toStringObjectMap(messageObj);

            if (hasAnyNonEmptyValue(message, "questionImages", "question_images", "images", "image",
                    "imageUrl", "image_url", "imageUrls", "image_urls", "questionFiles", "question_files",
                    "files", "file", "fileUrl", "file_url", "fileUrls", "file_urls", "attachments", "media")) {
                return true;

            }
            Object contentObj = message.get("content");

            if (contentObj instanceof Collection<?>) {
                for (Object partObj : (Collection<?>) contentObj) {
                    if (!(partObj instanceof Map<?, ?>)) {
                        continue;

                    }
                    Map<String, Object> part = toStringObjectMap(partObj);

                    if (containsMultimodalModality(part.get("type"))
                            || hasAnyNonEmptyValue(part, "image", "image_url", "imageUrl", "url",
                            "file", "file_url", "fileUrl", "path")) {
                        return true;

                    }
                }
            }
        }
        return false;

    }

    private boolean containsMultimodalModality(Object value) {
        if (value == null) {
            return false;

        }
        if (value instanceof Collection<?>) {
            for (Object item : (Collection<?>) value) {
                if (containsMultimodalModality(item)) {
                    return true;

                }
            }
            return false;

        }
        if (value instanceof Map<?, ?>) {
            for (Object item : ((Map<?, ?>) value).values()) {
                if (containsMultimodalModality(item)) {
                    return true;

                }
            }
            return false;

        }
        String text = value.toString().trim().toLowerCase(Locale.ROOT);

        return !text.isEmpty() && (text.contains("multimodal") || text.contains("multi-modal")
                || text.contains("image") || text.contains("picture") || text.contains("vision")
                || text.contains("file") || text.contains("document") || text.contains("pdf")
                || text.contains("video") || text.contains("audio") || text.contains("图片")
                || text.contains("图像") || text.contains("文件"));

    }

    private boolean hasAnyNonEmptyValue(Map<String, Object> map, String... keys) {
        if (map == null || map.isEmpty() || keys == null) {
            return false;

        }
        for (String key : keys) {
            if (key != null && isNonEmptyValue(map.get(key))) {
                return true;

            }
        }
        return false;

    }

    private boolean isNonEmptyValue(Object value) {
        if (value == null) {
            return false;

        }
        if (value instanceof Boolean) {
            return (Boolean) value;

        }
        if (value instanceof Number) {
            return true;

        }
        if (value instanceof CharSequence) {
            String text = value.toString().trim();

            return !text.isEmpty() && !"[]".equals(text) && !"{}".equals(text)
                    && !"null".equalsIgnoreCase(text) && !"undefined".equalsIgnoreCase(text);

        }
        if (value instanceof Collection<?>) {
            for (Object item : (Collection<?>) value) {
                if (isNonEmptyValue(item)) {
                    return true;

                }
            }
            return false;

        }
        if (value instanceof Map<?, ?>) {
            return !((Map<?, ?>) value).isEmpty();

        }
        return true;

    }

    private List<String> toStringList(Object value) {
        if (value == null) {
            return Collections.emptyList();

        }
        List<String> result = new ArrayList<>();

        if (value instanceof Collection<?>) {
            for (Object item : (Collection<?>) value) {
                String text = stringValue(item);

                if (!isBlank(text)) {
                    result.add(text.trim());

                }
            }
            return result;

        }
        String text = stringValue(value);

        if (isBlank(text)) {
            return Collections.emptyList();

        }
        if (text.contains(",")) {
            for (String part : text.split(",")) {
                if (!isBlank(part)) {
                    result.add(part.trim());

                }
            }
        } else {
            result.add(text.trim());

        }
        return result;

    }

    private Map<String, Object> toStringObjectMap(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();

        if (!(value instanceof Map<?, ?>)) {
            return result;

        }
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            if (entry.getKey() != null) {
                result.put(entry.getKey().toString(), entry.getValue());

            }
        }
        return result;

    }

    private void applyAccuracy(Map<String, Object> eval, List<Map<String, Object>> results) {
        double totalScore = 0;

        int scoreCount = 0;

        for (Map<String, Object> result : results) {
            if (result == null || !(result.get("scores") instanceof Map<?, ?>)) {
                continue;

            }
            for (Object value : ((Map<?, ?>) result.get("scores")).values()) {
                if (!(value instanceof Map<?, ?>)) {
                    continue;

                }
                Object finalScore = ((Map<?, ?>) value).get("final");

                if (finalScore instanceof Number) {
                    totalScore += ((Number) finalScore).doubleValue();

                    scoreCount++;

                }
            }
        }
        if (scoreCount > 0) {
            eval.put("accuracy", Math.round(totalScore / scoreCount * 100.0) / 100.0);

        }
    }

    /** Agent 评测：为每个 target 准备 auth 信息。 */
    @SuppressWarnings("unchecked")
    private void prepareAgentAuth(List<Map<String, Object>> targets) {
        for (Map<String, Object> target : targets) {
            Map<String, Object> env = getTargetEnvironment(target);

            if (isSemiclawTarget(target, env)) {
                prepareSemiclawTarget(target, env);

                continue;

            }
            String authorization = stringValue(target.get("authorization"));

            if (!isBlank(authorization)) {
                if (env != null) {
                    target.put("envUrl", env.get("url"));

                }
                continue;

            }
            String userEmail = stringValue(target.get("userEmail"));

            if (env == null) {
                continue;

            }
            String envUrl = stringValue(env.get("url"));

            try {
                if (isBlank(userEmail)) {
                    Map<String, Object> userInfo = envAgentService.getUserByNickname(envUrl,
                            stringValue(target.get("userName")));

                    if (userInfo != null) {
                        userEmail = stringValue(userInfo.get("email"));

                    }
                }
                if (!isBlank(userEmail)) {
                    String password = stringValue(target.get("userPassword"));

                    if (isBlank(password)) {
                        password = userEmail;

                    }
                    Map<String, String> loginResult = userLoginService.login(envUrl, userEmail, password);

                    target.put("authorization", loginResult.get("authorization"));

                    target.put("apiUsername", loginResult.get("apiUsername"));

                    target.put("userEmail", userEmail);

                }
            } catch (Exception e) {
                log.warn("评测登录失败 envUrl={}: {}", envUrl, e.getMessage());

            }
            target.put("envUrl", envUrl);

        }
    }

    /** 调用 Agent 并在遇到 401 时自动清除缓存、重新登录、重试一次。 */
    private CompletableFuture<Map<String, Object>> chatWithAgentRetryOn401(String envUrl, String agentId,
            String agentType, String targetId, String question, Map<String, Object> target) {
        String authorization = stringValue(target.get("authorization"));

        String apiUsername = stringValue(target.get("apiUsername"));

        CompletableFuture<Map<String, Object>> firstAttempt = isBlank(authorization)
                ? agentChatService.chatWithAgentAsync(envUrl, agentId, agentType, targetId, "", question,
                        null, null, authorization, apiUsername)
                : agentChatService.chatWithAgentAsync(envUrl, agentId, agentType, targetId, "", question);

        return firstAttempt.thenCompose(result -> {
            String answer = stringValue(result.get("answer"));

            if (answer == null || !answer.contains("401")) {
                return CompletableFuture.completedFuture(result);

            }
            String userEmail = stringValue(target.get("userEmail"));

            if (isBlank(userEmail)) {
                log.warn("401重试失败：无 userEmail，targetId={}", targetId);

                return CompletableFuture.completedFuture(result);

            }
            try {
                userLoginService.invalidateToken(envUrl, userEmail);

                String password = stringValue(target.get("userPassword"));

                if (isBlank(password)) {
                    password = userEmail;

                }
                Map<String, String> loginResult = userLoginService.login(envUrl, userEmail, password);

                String newAuth = loginResult.get("authorization");

                String newUsername = loginResult.get("apiUsername");

                target.put("authorization", newAuth);

                target.put("apiUsername", newUsername);

                return agentChatService.chatWithAgentAsync(envUrl, agentId, agentType, targetId, "", question,
                        null, null, newAuth, newUsername);

            } catch (Exception e) {
                log.warn("401重试：重新登录失败，targetId={}, error={}", targetId, e.getMessage());

                return CompletableFuture.completedFuture(result);

            }
        });

    }

    /** 根据平台分流调用 Agent。 */
    private CompletableFuture<Map<String, Object>> chatWithAgentByPlatform(String envUrl, String agentId,
            String agentType, String targetId, String question, Map<String, Object> target) {
        Map<String, Object> env = getTargetEnvironment(target);

        if (!isSemiclawTarget(target, env)) {
            return chatWithAgentRetryOn401(envUrl, agentId, agentType, targetId, question, target);

        }
        prepareSemiclawTarget(target, env);

        String baseUrl = firstNonBlank(stringValue(target.get("envUrl")), envUrl,
                env == null ? null : stringValue(env.get("url")), env == null ? null : stringValue(env.get("baseUrl")));

        String credentialMode = resolveSemiclawCredentialMode(target, env);

        boolean useServiceAccount = CREDENTIAL_MODE_SERVICE_ACCOUNT.equals(credentialMode);

        String tenantId = null;

        String username = null;

        String password = null;

        if (useServiceAccount) {
            tenantId = firstNonBlank(stringValue(target.get("tenantId")), stringValue(target.get("tenant_id")),
                    env == null ? null : stringValue(env.get("tenantId")), env == null ? null : stringValue(env.get("tenant_id")));

        } else {
            tenantId = firstNonBlank(stringValue(target.get("tenantId")), stringValue(target.get("tenant_id")),
                    env == null ? null : stringValue(env.get("tenantId")), env == null ? null : stringValue(env.get("tenant_id")));

            username = firstNonBlank(stringValue(target.get("username")), env == null ? null : stringValue(env.get("username")));

            password = firstNonBlank(stringValue(target.get("password")), env == null ? null : stringValue(env.get("password")));

        }
        String realAgentId = firstNonBlank(agentId, stringValue(target.get("agentId")), stringValue(target.get("agent_id")));

        String wsCookie = firstNonBlank(stringValue(target.get("wsCookie")), stringValue(target.get("ws_cookie")),
                env == null ? null : stringValue(env.get("wsCookie")), env == null ? null : stringValue(env.get("ws_cookie")));

        if (isBlank(baseUrl)) {
            return completedChatFailure("调用 SemiClaw 失败：未配置 baseUrl");

        }
        if (isBlank(realAgentId)) {
            return completedChatFailure("调用 SemiClaw 失败：未配置 agentId");

        }
        boolean hasPersonalCredential = !isBlank(tenantId) && (!isBlank(username) || !isBlank(password));

        log.info("SemiClaw Agent评测分流: targetId={}, baseUrl={}, credentialMode={}, useServiceAccount={}, "
                        + "hasPersonalCredential={}, tenantId={}, username={}, agentId={}, hasWsCookie={}", targetId,
                maskValue(baseUrl), credentialMode, useServiceAccount, hasPersonalCredential, maskValue(tenantId),
                maskUsername(username), realAgentId, !isBlank(wsCookie));

        return semiclawAgentChatService.chatWithAgentAsync(baseUrl, tenantId, realAgentId, question,
                username, password, wsCookie);

    }

    /** 流式评测单题：并行调用所有 Agent，每个完成后立即推送 result 事件。 */
    @SuppressWarnings("unchecked")
    private void executeAgentQuestionStreaming(String evalId, List<Map<String, Object>> targets, String question,
            String expectedAnswer, int questionIndex, Map<String, Object> outputs, Map<String, Object> scores,
            SseEmitter emitter, String judgeMode, String judgeModelId) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (Map<String, Object> target : targets) {
            String targetId = stringValue(target.get("id"));

            String agentId = firstNonBlank(stringValue(target.get("agentId")), stringValue(target.get("agent_id")));

            String agentType = firstNonBlank(stringValue(target.get("agentType")), "bot");

            String envUrl = stringValue(target.get("envUrl"));

            if (isBlank(envUrl)) {
                Map<String, Object> env = getTargetEnvironment(target);

                envUrl = env == null ? null : stringValue(env.get("url"));

            }
            if (isBlank(envUrl)) {
                Map<String, Object> output = new HashMap<>();

                output.put("answer", "环境配置不存在");

                output.put("responseTime", 0);

                outputs.put(targetId, output);

                scores.put(targetId, calculateScore(question, null, expectedAnswer, judgeMode, judgeModelId));

                lifecycle.pushStep(evalId, questionIndex, targetId, "scored", "环境配置不存在");

                continue;

            }
            final String finalEnvUrl = envUrl;

            final String finalAgentId = agentId;

            final String finalAgentType = agentType;

            final String finalTargetId = targetId;

            lifecycle.pushStep(evalId, questionIndex, finalTargetId, "calling", "调用中");

            CompletableFuture<Void> future = chatWithAgentByPlatform(finalEnvUrl, finalAgentId, finalAgentType,
                    finalTargetId, question, target).thenAccept(agentResult -> {
                Map<String, Object> output = new HashMap<>();

                output.put("answer", agentResult.get("answer"));

                output.put("responseTime", agentResult.get("responseTime"));

                if (agentResult.get("imgMap") != null) {
                    output.put("imgMap", agentResult.get("imgMap"));

                }
                synchronized (outputs) {
                    outputs.put(finalTargetId, output);

                }
                lifecycle.pushStep(evalId, questionIndex, finalTargetId, "answered", "已应答");

                try {
                    Map<String, Object> resultEvent = new HashMap<>();

                    resultEvent.put("questionIndex", questionIndex);

                    resultEvent.put("targetId", finalTargetId);

                    resultEvent.put("answer", agentResult.get("answer"));

                    resultEvent.put("responseTime", agentResult.get("responseTime"));

                    if (agentResult.get("imgMap") != null) {
                        resultEvent.put("imgMap", agentResult.get("imgMap"));

                    }
                    emitter.send(SseEmitter.event().name("result")
                            .data(objectMapper.writeValueAsString(resultEvent), MediaType.TEXT_PLAIN));

                } catch (Exception e) {
                    log.warn("推送Agent结果事件失败: {}", e.getMessage());

                }
                lifecycle.pushStep(evalId, questionIndex, finalTargetId, "scoring", "评分中");

                Map<String, Object> score = calculateScore(question, stringValue(agentResult.get("answer")),
                        expectedAnswer, judgeMode, judgeModelId);

                synchronized (scores) {
                    scores.put(finalTargetId, score);

                }
                lifecycle.pushStep(evalId, questionIndex, finalTargetId, "scored", "已评分");

            });

            futures.add(future);

        }
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(480, TimeUnit.SECONDS);

        } catch (Exception e) {
            log.warn("部分Agent调用异常: {}", e.getMessage());

        }
    }

    /** 流式评测单题：并行调用所有模型，每个完成后立即推送 result 事件。 */
    private void executeModelQuestionStreaming(String evalId, List<Map<String, Object>> targets, String question,
            String expectedAnswer, int questionIndex, Map<String, Object> outputs, Map<String, Object> scores,
            SseEmitter emitter, String judgeMode, String judgeModelId) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (Map<String, Object> target : targets) {
            String targetId = stringValue(target.get("id"));

            String modelId = stringValue(target.get("modelId"));

            Map<String, Object> modelConfig = modelStore.get(modelId);

            if (modelConfig == null) {
                Map<String, Object> output = new HashMap<>();

                output.put("answer", "模型配置不存在: " + modelId);

                output.put("responseTime", 0);

                outputs.put(targetId, output);

                scores.put(targetId, calculateScore(question, null, expectedAnswer, judgeMode, judgeModelId));

                lifecycle.pushStep(evalId, questionIndex, targetId, "scored", "模型配置不存在");

                continue;

            }
            final String finalTargetId = targetId;

            final String finalExpected = expectedAnswer;

            lifecycle.pushStep(evalId, questionIndex, finalTargetId, "calling", "调用中");

            CompletableFuture<Void> future = llmService.chatAsync(modelConfig, question).thenAccept(llmResult -> {
                String answer = stringValue(llmResult.get("answer"));

                long responseTime = llmResult.get("responseTime") instanceof Number
                        ? ((Number) llmResult.get("responseTime")).longValue() : 0;

                Map<String, Object> output = new HashMap<>();

                output.put("answer", answer);

                output.put("responseTime", responseTime);

                synchronized (outputs) {
                    outputs.put(finalTargetId, output);

                }
                lifecycle.pushStep(evalId, questionIndex, finalTargetId, "answered", "已应答", responseTime);

                try {
                    Map<String, Object> resultEvent = new HashMap<>();

                    resultEvent.put("questionIndex", questionIndex);

                    resultEvent.put("targetId", finalTargetId);

                    resultEvent.put("answer", answer);

                    resultEvent.put("responseTime", responseTime);

                    emitter.send(SseEmitter.event().name("result")
                            .data(objectMapper.writeValueAsString(resultEvent), MediaType.TEXT_PLAIN));

                } catch (Exception e) {
                    log.warn("推送模型结果事件失败: {}", e.getMessage());

                }
                Map<String, Object> score = calculateScore(question, answer, finalExpected, judgeMode, judgeModelId);

                synchronized (scores) {
                    scores.put(finalTargetId, score);

                }
                lifecycle.pushStep(evalId, questionIndex, finalTargetId, "scored", "已评分");

            });

            futures.add(future);

        }
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(480, TimeUnit.SECONDS);

        } catch (Exception e) {
            log.warn("部分模型调用异常: {}", e.getMessage());

        }
    }

    private List<Map<String, Object>> executeAgentEvaluation(List<Map<String, Object>> targets,
            List<Map<String, Object>> items, String judgeMode, String judgeModelId) {
        prepareAgentAuth(targets);

        List<Map<String, Object>> results = new ArrayList<>();

        int itemIndex = 0;

        for (Map<String, Object> item : items) {
            itemIndex++;

            String question = stringValue(item.get("question"));

            String expectedAnswer = stringValue(item.get("expectedAnswer"));

            String itemId = item.get("id") != null ? item.get("id").toString() : "q-" + itemIndex;

            Map<String, Object> outputs = new HashMap<>();

            Map<String, Object> scores = new HashMap<>();

            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (Map<String, Object> target : targets) {
                String targetId = stringValue(target.get("id"));

                String agentId = firstNonBlank(stringValue(target.get("agentId")), stringValue(target.get("agent_id")));

                String agentType = firstNonBlank(stringValue(target.get("agentType")), "bot");

                String envUrl = stringValue(target.get("envUrl"));

                if (isBlank(envUrl)) {
                    Map<String, Object> env = getTargetEnvironment(target);

                    envUrl = env == null ? null : stringValue(env.get("url"));

                }
                CompletableFuture<Void> future = chatWithAgentByPlatform(envUrl, agentId, agentType, targetId,
                        question, target).thenAccept(agentResult -> {
                    Map<String, Object> output = new HashMap<>();

                    output.put("answer", agentResult.get("answer"));

                    output.put("responseTime", agentResult.get("responseTime"));

                    if (agentResult.get("imgMap") != null) {
                        output.put("imgMap", agentResult.get("imgMap"));

                    }
                    synchronized (outputs) { outputs.put(targetId, output); }
                    synchronized (scores) {
                        scores.put(targetId, calculateScore(question, stringValue(agentResult.get("answer")),
                                expectedAnswer, judgeMode, judgeModelId));

                    }
                });

                futures.add(future);

            }
            waitAll(futures, 180);

            results.add(buildResult(itemId, question, item, expectedAnswer, outputs, scores));

        }
        return results;

    }

    private List<Map<String, Object>> executeModelEvaluation(List<Map<String, Object>> targets,
            List<Map<String, Object>> items, String judgeMode, String judgeModelId) {
        List<Map<String, Object>> results = new ArrayList<>();

        int itemIndex = 0;

        for (Map<String, Object> item : items) {
            itemIndex++;

            String question = stringValue(item.get("question"));

            String expectedAnswer = stringValue(item.get("expectedAnswer"));

            String itemId = item.get("id") != null ? item.get("id").toString() : "q-" + itemIndex;

            Map<String, Object> outputs = new HashMap<>();

            Map<String, Object> scores = new HashMap<>();

            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (Map<String, Object> target : targets) {
                String targetId = stringValue(target.get("id"));

                String modelId = stringValue(target.get("modelId"));

                Map<String, Object> modelConfig = modelStore.get(modelId);

                if (modelConfig == null) {
                    Map<String, Object> output = new HashMap<>();

                    output.put("answer", "模型配置不存在: " + modelId);

                    output.put("responseTime", 0);

                    outputs.put(targetId, output);

                    scores.put(targetId, calculateScore(question, null, expectedAnswer, judgeMode, judgeModelId));

                    continue;

                }
                CompletableFuture<Void> future = llmService.chatAsync(modelConfig, question).thenAccept(llmResult -> {
                    String answer = stringValue(llmResult.get("answer"));

                    Map<String, Object> output = new HashMap<>();

                    output.put("answer", answer);

                    output.put("responseTime", llmResult.get("responseTime"));

                    synchronized (outputs) { outputs.put(targetId, output); }
                    synchronized (scores) { scores.put(targetId, calculateScore(question, answer, expectedAnswer,
                            judgeMode, judgeModelId)); }
                });

                futures.add(future);

            }
            waitAll(futures, 180);

            results.add(buildResult(itemId, question, item, expectedAnswer, outputs, scores));

        }
        return results;

    }

    private void waitAll(List<CompletableFuture<Void>> futures, long timeoutSeconds) {
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(timeoutSeconds, TimeUnit.SECONDS);

        } catch (Exception e) {
            log.warn("部分评测调用异常: {}", e.getMessage());

        }
    }

    /** 从 Agent 回答中提取结构化 JSON 的 textContent。 */
    @SuppressWarnings("unchecked")
    private String extractTextContent(String answer) {
        if (answer == null || answer.isEmpty()) {
            return answer;

        }
        String trimmed = answer.trim();

        if (trimmed.startsWith("{")) {
            try {
                Map<String, Object> json = objectMapper.readValue(trimmed, Map.class);

                Object textContent = json.get("textContent");

                return textContent == null ? answer : textContent.toString();

            } catch (Exception ignored) {
                // 不是结构化 JSON，返回原文
            }
        }
        return answer;

    }

    /** 计算评分：有预期答案时自动评分，无预期答案时留给人工判定。 */
    private Map<String, Object> calculateScore(String question, String answer, String expectedAnswer,
            String judgeMode, String judgeModelId) {
        Map<String, Object> score = new HashMap<>();

        if (isBlank(expectedAnswer)) {
            score.put("auto", null);

            score.put("manual", null);

            score.put("final", null);

            return score;

        }
        String textForScoring = extractTextContent(answer);

        Map<String, Double> weights = criteriaStore.getDimensionWeights();

        double accuracyWeight = weights.getOrDefault("accuracy", 0.4);

        double completenessWeight = weights.getOrDefault("completeness", 0.3);

        double relevanceWeight = weights.getOrDefault("relevance", 0.3);

        if ("llm".equals(judgeMode) && !isBlank(judgeModelId)) {
            Map<String, Object> modelConfig = modelStore.get(judgeModelId);

            if (modelConfig != null) {
                try {
                    Map<String, Object> llmScore = scoreWithLlm(modelConfig, question, textForScoring,
                            expectedAnswer, accuracyWeight, completenessWeight, relevanceWeight);

                    if (llmScore != null) {
                        score.put("auto", llmScore.get("auto"));

                        score.put("manual", null);

                        score.put("final", llmScore.get("final"));

                        score.put("reason", llmScore.get("reason"));

                        return score;

                    }
                } catch (Exception e) {
                    log.warn("大模型评分失败，回退到公式评分: {}", e.getMessage());

                }
            }
        }
        double accuracy = textSimilarity(textForScoring, expectedAnswer);

        double completeness = coverageScore(textForScoring, expectedAnswer);

        double relevance = relevanceScore(textForScoring, expectedAnswer);

        Map<String, Object> auto = new HashMap<>();

        auto.put("accuracy", Math.round(accuracy * 100.0) / 100.0);

        auto.put("completeness", Math.round(completeness * 100.0) / 100.0);

        auto.put("relevance", Math.round(relevance * 100.0) / 100.0);

        score.put("auto", auto);

        score.put("manual", null);

        score.put("final", Math.round((accuracy * accuracyWeight + completeness * completenessWeight
                + relevance * relevanceWeight) * 100.0) / 100.0);

        return score;

    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> scoreWithLlm(Map<String, Object> modelConfig, String question,
            String actualAnswer, String expectedAnswer, double accuracyWeight, double completenessWeight,
            double relevanceWeight) throws Exception {
        String template = criteriaStore.getTextJudgePrompt();

        Map<String, String> placeholders = new HashMap<>();

        placeholders.put("question", question == null ? "" : question);

        placeholders.put("expected", expectedAnswer);

        placeholders.put("answer", actualAnswer);

        String prompt = LlmJsonScoreUtils.fillTemplate(template, placeholders);

        String response = llmService.chat(modelConfig, prompt);

        String jsonStr = LlmJsonScoreUtils.extractJsonFromResponse(response);

        if (jsonStr == null) {
            log.warn("大模型评分返回无法解析: {}", response);

            return null;

        }
        Map<String, Object> parsed = objectMapper.readValue(jsonStr, Map.class);

        double accuracy = LlmJsonScoreUtils.toDoubleDefault(parsed.get("accuracy"), 0);

        double completeness = LlmJsonScoreUtils.toDoubleDefault(parsed.get("completeness"), 0);

        double relevance = LlmJsonScoreUtils.toDoubleDefault(parsed.get("relevance"), 0);

        Map<String, Object> auto = new HashMap<>();

        auto.put("accuracy", Math.round(accuracy * 100.0) / 100.0);

        auto.put("completeness", Math.round(completeness * 100.0) / 100.0);

        auto.put("relevance", Math.round(relevance * 100.0) / 100.0);

        Map<String, Object> result = new HashMap<>();

        result.put("auto", auto);

        result.put("final", Math.round((accuracy * accuracyWeight + completeness * completenessWeight
                + relevance * relevanceWeight) * 100.0) / 100.0);

        result.put("reason", parsed.get("reason"));

        return result;

    }

    private double textSimilarity(String a, String b) {
        if (a == null || b == null) return 0;

        Set<String> setA = tokenize(a);

        Set<String> setB = tokenize(b);

        if (setA.isEmpty() && setB.isEmpty()) return 1.0;

        if (setA.isEmpty() || setB.isEmpty()) return 0;

        Set<String> intersection = new HashSet<>(setA);

        intersection.retainAll(setB);

        Set<String> union = new HashSet<>(setA);

        union.addAll(setB);

        return (double) intersection.size() / union.size();

    }

    private double coverageScore(String answer, String expected) {
        Set<String> expectedTokens = tokenize(expected);

        Set<String> answerTokens = tokenize(answer);

        if (expectedTokens.isEmpty()) return 1.0;

        long covered = expectedTokens.stream().filter(answerTokens::contains).count();

        return (double) covered / expectedTokens.size();

    }

    private double relevanceScore(String answer, String expected) {
        Set<String> expectedTokens = tokenize(expected);

        Set<String> answerTokens = tokenize(answer);

        if (answerTokens.isEmpty()) return 0;

        long relevant = answerTokens.stream().filter(expectedTokens::contains).count();

        double raw = (double) relevant / answerTokens.size();

        return Math.min(1.0, raw + 0.3);

    }

    /** 中文分词（按 2-gram 切分，并保留较长片段）。 */
    private Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>();

        if (text == null || text.isEmpty()) return tokens;

        String cleaned = text.replaceAll("[\\s\\p{Punct}\\u3000-\\u303F\\uFF00-\\uFFEF\\u2000-\\u206F]", "").trim();

        String[] segments = cleaned.split("\\s+");

        for (String segment : segments) {
            if (segment.length() <= 1) continue;

            for (int i = 0; i < segment.length() - 1; i++) {
                tokens.add(segment.substring(i, i + 2));

            }
            if (segment.length() >= 6) tokens.add(segment);

        }
        return tokens;

    }

    private Map<String, Object> getTargetEnvironment(Map<String, Object> target) {
        String envId = stringValue(target.get("envId"));

        if (isBlank(envId)) return null;

        try {
            return environmentStore.get(envId);

        } catch (Exception e) {
            log.warn("读取环境配置失败 envId={}: {}", envId, e.getMessage());

            return null;

        }
    }

    private boolean isSemiclawTarget(Map<String, Object> target, Map<String, Object> env) {
        String platform = firstNonBlank(stringValue(target.get("platform")), stringValue(target.get("targetPlatform")),
                env == null ? null : stringValue(env.get("platform")));

        if ("semiclaw".equalsIgnoreCase(platform)) return true;

        String envUrl = firstNonBlank(stringValue(target.get("envUrl")), env == null ? null : stringValue(env.get("url")),
                env == null ? null : stringValue(env.get("baseUrl")));

        return isSemiclawUrl(envUrl);

    }

    private void prepareSemiclawTarget(Map<String, Object> target, Map<String, Object> env) {
        String envUrl = firstNonBlank(stringValue(target.get("envUrl")), env == null ? null : stringValue(env.get("url")),
                env == null ? null : stringValue(env.get("baseUrl")));

        String credentialMode = resolveSemiclawCredentialMode(target, env);

        target.put("platform", "semiclaw");

        target.put("targetPlatform", "semiclaw");

        if (!isBlank(envUrl)) {
            target.put("envUrl", envUrl);

            target.put("baseUrl", envUrl);

            target.put("url", envUrl);

        }
        target.put("credentialMode", credentialMode);

        target.put("credential_mode", credentialMode);

        target.put("loginMode", credentialMode);

        target.put("login_mode", credentialMode);

        if (CREDENTIAL_MODE_SERVICE_ACCOUNT.equals(credentialMode)) {
            target.remove("tenantId");

            target.remove("tenant_id");

            target.remove("username");

            target.remove("password");

        } else {
            String tenantId = firstNonBlank(stringValue(target.get("tenantId")), stringValue(target.get("tenant_id")),
                    env == null ? null : stringValue(env.get("tenantId")), env == null ? null : stringValue(env.get("tenant_id")));

            String username = firstNonBlank(stringValue(target.get("username")), env == null ? null : stringValue(env.get("username")));

            String password = firstNonBlank(stringValue(target.get("password")), env == null ? null : stringValue(env.get("password")));

            if (!isBlank(tenantId)) { target.put("tenantId", tenantId); target.put("tenant_id", tenantId); }
            if (!isBlank(username)) target.put("username", username);

            if (!isBlank(password)) target.put("password", password);

            target.remove("authorization");

            target.remove("apiUsername");

        }
    }

    private boolean isSemiclawUrl(String envUrl) {
        if (isBlank(envUrl)) return false;

        String value = envUrl.toLowerCase(Locale.ROOT);

        return platformDetection.isSemiclawUrl(envUrl) || value.contains("semiclaw");

    }

    private CompletableFuture<Map<String, Object>> completedChatFailure(String message) {
        Map<String, Object> result = new HashMap<>();

        result.put("answer", message);

        result.put("responseTime", 0);

        return CompletableFuture.completedFuture(result);

    }

    /** 日志中隐藏 token、Authorization 等敏感值。 */
    private static String maskValue(String value) {
        if (value == null || value.isBlank()) {
            return "";

        }
        String trimmed = value.trim();

        int length = trimmed.length();

        if (length <= 4) {
            return "****";

        }
        return trimmed.substring(0, 2) + "****" + trimmed.substring(length - 2);

    }

    private String maskUsername(String username) {
        if (isBlank(username)) return "";

        String value = username.trim();

        if (value.length() == 1) return value.charAt(0) + "***";

        if (value.length() == 2) return value.substring(0, 1) + "***";

        return value.substring(0, 1) + "***" + value.substring(value.length() - 1);

    }

    private String resolveSemiclawCredentialMode(Map<String, Object> target, Map<String, Object> env) {
        String mode = firstNonBlank(stringValue(target.get("credentialMode")), stringValue(target.get("credential_mode")),
                stringValue(target.get("loginMode")), stringValue(target.get("login_mode")),
                env == null ? null : stringValue(env.get("credentialMode")), env == null ? null : stringValue(env.get("credential_mode")),
                env == null ? null : stringValue(env.get("loginMode")), env == null ? null : stringValue(env.get("login_mode")));

        if (!isBlank(mode)) return normalizeCredentialMode(mode);

        boolean personal = !isBlank(firstNonBlank(stringValue(target.get("tenantId")), stringValue(target.get("tenant_id")),
                env == null ? null : stringValue(env.get("tenantId")), env == null ? null : stringValue(env.get("tenant_id"))))
                && !isBlank(firstNonBlank(stringValue(target.get("username")), env == null ? null : stringValue(env.get("username"))))
                && !isBlank(firstNonBlank(stringValue(target.get("password")), env == null ? null : stringValue(env.get("password"))));

        return personal ? CREDENTIAL_MODE_PERSONAL : CREDENTIAL_MODE_SERVICE_ACCOUNT;

    }

    private String normalizeCredentialMode(String mode) {
        String value = mode.trim().toLowerCase(Locale.ROOT).replace('_', '-');

        if ("service".equals(value) || "service-account".equals(value) || "serviceaccount".equals(value)
                || "backend".equals(value) || "backend-config".equals(value) || "config".equals(value)) {
            return CREDENTIAL_MODE_SERVICE_ACCOUNT;

        }
        if ("personal".equals(value) || "environment".equals(value) || "env".equals(value) || "manual".equals(value)) {
            return CREDENTIAL_MODE_PERSONAL;

        }
        return value;

    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();

    }

    private String firstNonBlank(String... values) {
        if (values == null) return null;

        for (String value : values) {
            if (!isBlank(value)) return value;

        }
        return null;

    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();

    }
}

