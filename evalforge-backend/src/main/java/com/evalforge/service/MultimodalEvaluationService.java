package com.evalforge.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.evalforge.config.PlatformDetectionProperties;

/**
 * 多模态评测执行服务。
 *
 * 只承接多模态评测链路，避免 EvaluationService 的原文本评测链路继续膨胀。
 * 当前职责：
 * 1. 执行多模态异步评测和 SSE 流式评测。
 * 2. 从数据集 item 中提取 questionImages / questionFiles / modalities。
 * 3. 保留多模态结果字段，供报告页展示。
 * 4. 按平台分流 SemiClaw / SemiMind 多模态调用，其他平台安全回退文本链路。
 * 5. 无参考答案时不自动评分，结果标记为待人工评分。
 */
@Service
public class MultimodalEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(MultimodalEvaluationService.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String CREDENTIAL_MODE_PERSONAL = "personal";
    private static final String CREDENTIAL_MODE_SERVICE_ACCOUNT = "service-account";

    private static final String EVALUATION_TYPE_TEXT = "text";
    private static final String EVALUATION_TYPE_MULTIMODAL = "multimodal";

    private static final String SCORE_STATUS_AUTO_SCORED = "auto_scored";
    private static final String SCORE_STATUS_MANUAL_REVIEW = "manual_review";
    private static final String SCORE_STATUS_PARTIAL_AUTO_SCORED = "partial_auto_scored";
    private static final String SCORE_STATUS_FAILED = "failed";

    private static final String SCORE_DISPLAY_MANUAL_REVIEW = "待人工评分";
    private static final String SCORE_REASON_NO_EXPECTED_ANSWER = "NO_EXPECTED_ANSWER";

    private static final long MODEL_EVALUATION_WAIT_SECONDS = 180;
    private static final long AGENT_EVALUATION_WAIT_SECONDS = 960;
    private static final long STREAMING_MODEL_WAIT_SECONDS = 480;
    private static final long STREAMING_AGENT_WAIT_SECONDS = 1200;

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
    private SemiclawMultimodalChatService semiclawMultimodalChatService;

    @Autowired
    private SemimindMultimodalChatService semimindMultimodalChatService;

    @Autowired
    private EnvironmentStoreService environmentStore;

    @Autowired
    private UserLoginService userLoginService;

    @Autowired
    private EnvAgentService envAgentService;

    @Autowired
    private PlatformDetectionProperties platformDetection;

    @Autowired
    private EvaluationProgressBroadcaster progressBroadcaster;

    @Autowired
    private EvaluationCancellationRegistry cancellationRegistry;

    @Autowired
    private EvaluationLifecycleHelper lifecycle;

    @Autowired
    private PptScoringService pptScoringService;

    @Autowired
    private HtmlScoringService htmlScoringService;

    @Autowired
    private MinioStorageService minioStorageService;

    @Autowired
    private CriteriaStoreService criteriaStore;

    @SuppressWarnings("unchecked")
    public void executeAsync(String evalId, Map<String, Object> eval) {
        String type = (String) eval.get("type");
        String judgeMode = (String) eval.get("judgeMode");
        String judgeModelId = (String) eval.get("judgeModelId");
        List<Map<String, Object>> targets = (List<Map<String, Object>>) eval.get("targets");
        List<String> datasetIds = (List<String>) eval.get("datasetIds");

        initMultimodalEvalMetadata(eval, Collections.emptyList());

        if (targets == null || targets.isEmpty()) {
            lifecycle.markFailed(eval, "未选择评测目标");
            return;
        }
        if (datasetIds == null || datasetIds.isEmpty()) {
            lifecycle.markFailed(eval, "未选择数据集");
            return;
        }

        List<Map<String, Object>> allItems = collectEvaluationItems(datasetIds);
        if (allItems.isEmpty()) {
            lifecycle.markFailed(eval, "数据集中无题目数据");
            return;
        }
        initMultimodalEvalMetadata(eval, allItems);

        log.info("多模态评测 {} 共 {} 道题，{} 个目标", evalId, allItems.size(), targets.size());

        List<Map<String, Object>> results;
        if ("model".equals(type)) {
            results = executeMultimodalModelEvaluation(targets, allItems, judgeMode, judgeModelId);
        } else {
            results = executeMultimodalAgentEvaluation(targets, allItems, judgeMode, judgeModelId);
        }

        eval.put("results", results);
        eval.put("endTime", LocalDateTime.now().format(FMT));
        applyAccuracy(eval, results);
        lifecycle.markCompleted(eval);

        log.info("多模态评测 {} 执行完成，共 {} 条结果", evalId, results.size());
    }

    public void executeStreaming(String evalId, Map<String, Object> eval, SseEmitter emitter) throws Exception {
        executeStreaming(evalId, eval, emitter, null);
    }

    /**
     * 流式执行多模态评测。targetIndexes 为 null 或空表示重新执行全部题目（保持原有行为）；
     * 非空时只命中其中的题目索引（0-based）真正发起调用并评分，其余题目直接复用已有结果。
     */
    @SuppressWarnings("unchecked")
    public void executeStreaming(String evalId, Map<String, Object> eval, SseEmitter emitter,
            Set<Integer> targetIndexes) throws Exception {
        String type = (String) eval.get("type");
        String judgeMode = (String) eval.get("judgeMode");
        String judgeModelId = (String) eval.get("judgeModelId");
        List<Map<String, Object>> targets = (List<Map<String, Object>>) eval.get("targets");
        List<String> datasetIds = (List<String>) eval.get("datasetIds");

        initMultimodalEvalMetadata(eval, Collections.emptyList());

        if (targets == null || targets.isEmpty()) {
            throw new RuntimeException("未选择评测目标");
        }
        if (datasetIds == null || datasetIds.isEmpty()) {
            throw new RuntimeException("未选择数据集");
        }

        List<Map<String, Object>> allItems = collectEvaluationItems(datasetIds);
        if (allItems.isEmpty()) {
            throw new RuntimeException("数据集中无题目数据");
        }
        initMultimodalEvalMetadata(eval, allItems);

        boolean partialRerun = targetIndexes != null && !targetIndexes.isEmpty();
        List<Map<String, Object>> existingResults = partialRerun
                ? (List<Map<String, Object>>) eval.getOrDefault("results", Collections.emptyList())
                : Collections.emptyList();

        log.info("多模态流式评测 {} 共 {} 道题，{} 个目标，partialRerun={}, targetIndexes={}",
                evalId, allItems.size(), targets.size(), partialRerun, targetIndexes);

        Map<String, Object> initEvent = buildStreamingInitEvent(evalId, allItems.size(), targets.size(), eval);
        lifecycle.safeSend(emitter, "init", initEvent);

        if ("agent".equals(type)) {
            log.info("多模态流式评测 {}：开始 Agent 预登录/参数准备...", evalId);
            prepareAgentAuth(targets);
            log.info("多模态流式评测 {}：Agent 预登录/参数准备完成", evalId);
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

        // SemiMind 多模态会话换 token 计数器：方法局部变量，随本次 executeStreaming 调用生命周期创建/销毁，
        // 保证每一次独立的评测执行（全新评测/断点续跑/单题重跑/低于分量阈值）都从 0 重新计数，
        // 不会在每次循环中重新建会话，也不需要判断“是否是重跑”。
        Map<String, AtomicInteger> semimindWindowCounters = new HashMap<>();

        cancellationRegistry.markActive(evalId);
        try {
            for (Map<String, Object> item : allItems) {
                if (cancellationRegistry.isCancelled(evalId)) {
                    log.info("多模态流式评测 {} 收到终止请求，停止后续题目", evalId);
                    break;
                }

                int currentIndex = itemIndex++;

                // 部分重跑时，未命中目标索引的题目直接复用结果，不重新调用/评分/推送；
                // results 已在循环开始前按全量长度预填 null，只有真正重跑的结果才会写入。
                if (partialRerun && !targetIndexes.contains(currentIndex)) {
                    Map<String, Object> reused = currentIndex < existingResults.size()
                            ? existingResults.get(currentIndex) : null;
                    if (reused != null) {
                        continue;
                    }
                    // 结果缺失（理论上不应发生）时兜底补跑，保证结束后结果列表完整。
                }

                String question = extractQuestionText(item);
                String expectedAnswer = extractExpectedAnswer(item);
                boolean hasExpectedAnswer = hasExpectedAnswer(expectedAnswer)
                        || isDocumentComparisonType(getExpectedType(item, expectedAnswer));
                String itemId = item.get("id") != null ? item.get("id").toString() : "q-" + itemIndex;
                List<String> questionImages = extractQuestionImages(item);
                List<String> questionFiles = extractQuestionFiles(item);
                List<String> modalities = extractModalities(item, questionImages, questionFiles);
                String inputPreview = buildInputPreview(question, questionImages, questionFiles);

                Map<String, Object> progressEvent = buildProgressEvent(
                        itemIndex - 1, allItems.size(), currentIndex, modalities,
                        questionImages, questionFiles, inputPreview, hasExpectedAnswer);
                if (partialRerun) {
                    progressEvent.put("message", "本次重跑共 " + targetIndexes.size() + " 道题，当前重跑第 " + itemIndex + " 题");
                }
                lifecycle.safeSend(emitter, "progress", progressEvent);
                progressBroadcaster.push(evalId, "progress", progressEvent);

                Map<String, Object> outputs = new HashMap<>();
                Map<String, Object> scores = new HashMap<>();

                if ("agent".equals(type)) {
                    executeMultimodalAgentQuestionStreaming(evalId, targets, item, question,
                            expectedAnswer, currentIndex, outputs, scores, emitter,
                            judgeMode, judgeModelId, semimindWindowCounters);
                } else {
                    executeMultimodalModelQuestionStreaming(evalId, targets, item, question,
                            expectedAnswer, currentIndex, outputs, scores, emitter, judgeMode, judgeModelId);
                }

                Map<String, Object> scoreEvent = buildScoreEvent(currentIndex, scores, hasExpectedAnswer);
                lifecycle.safeSend(emitter, "score", scoreEvent);

                Map<String, Object> result = buildResultEntry(
                        item, itemId, question, expectedAnswer, outputs, scores, true);
                // 部分重跑下 results 已预填为全量长度，按索引覆盖；全量执行则按顺序追加。
                if (partialRerun) {
                    results.set(currentIndex, result);
                } else {
                    results.add(result);
                }

                // 每题完成后立即增量落库并推送详情页订阅事件，使历史报告 tab 也能实时看到题目结果。
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

            Map<String, Object> completeEvent = buildCompleteEvent(evalId, eval);
            lifecycle.safeSend(emitter, "complete", completeEvent);
            progressBroadcaster.push(evalId, "complete", completeEvent);
            log.info("多模态流式评测 {} 完成，共 {} 条结果，状态={}", evalId, results.size(), eval.get("status"));
        } finally {
            cancellationRegistry.clear(evalId);
            cancellationRegistry.markInactive(evalId);
            progressBroadcaster.closeAll(evalId);
        }
    }

    private List<Map<String, Object>> executeMultimodalModelEvaluation(
            List<Map<String, Object>> targets, List<Map<String, Object>> items,
            String judgeMode, String judgeModelId) {
        List<Map<String, Object>> results = new ArrayList<>();
        int itemIndex = 0;

        for (Map<String, Object> item : items) {
            itemIndex++;
            String question = extractQuestionText(item);
            String expectedAnswer = extractExpectedAnswer(item);
            String itemId = item.get("id") != null ? item.get("id").toString() : "q-" + itemIndex;
            String callQuestion = buildMultimodalCallQuestion(item, question);
            List<String> questionImages = extractQuestionImages(item);
            List<String> questionFiles = extractQuestionFiles(item);
            Map<String, Object> outputs = new HashMap<>();
            Map<String, Object> scores = new HashMap<>();
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            log.info("多模态模型评测 处理第 {}/{} 题目", itemIndex, items.size());

            for (Map<String, Object> target : targets) {
                String targetId = (String) target.get("id");
                String modelId = (String) target.get("modelId");
                Map<String, Object> modelConfig = modelStore.get(modelId);
                if (modelConfig == null) {
                    Map<String, Object> output = buildOutput(
                            "模型配置不存在: " + modelId, 0, "metadata-only",
                            questionImages, questionFiles, Collections.emptyMap());
                    outputs.put(targetId, output);
                    scores.put(targetId, calculateScore(
                            question, null, expectedAnswer, judgeMode, judgeModelId, item, null));
                    continue;
                }

                final String fTargetId = targetId;
                final String fExpectedAnswer = expectedAnswer;
                final List<String> fQuestionImages = questionImages;
                final List<String> fQuestionFiles = questionFiles;
                CompletableFuture<Void> future = llmService.chatAsync(modelConfig, callQuestion)
                        .thenAccept(llmResult -> {
                            String answer = (String) llmResult.get("answer");
                            long responseTime = llmResult.get("responseTime") instanceof Number
                                    ? ((Number) llmResult.get("responseTime")).longValue() : 0;
                            Map<String, Object> output = buildOutput(
                                    answer, responseTime, "metadata-only",
                                    fQuestionImages, fQuestionFiles, Collections.emptyMap());
                            synchronized (outputs) {
                                outputs.put(fTargetId, output);
                            }
                            Map<String, Object> score = calculateScore(
                                    question, answer, fExpectedAnswer, judgeMode, judgeModelId, item, null);
                            synchronized (scores) {
                                scores.put(fTargetId, score);
                            }
                        });
                futures.add(future);
            }

            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .get(MODEL_EVALUATION_WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("部分多模态模型调用异常: {}", e.getMessage());
            }

            results.add(buildResultEntry(item, itemId, question, expectedAnswer, outputs, scores, true));
        }
        return results;
    }

    private List<Map<String, Object>> executeMultimodalAgentEvaluation(
            List<Map<String, Object>> targets, List<Map<String, Object>> items,
            String judgeMode, String judgeModelId) {
        prepareAgentAuth(targets);
        Map<String, AtomicInteger> semimindWindowCounters = new HashMap<>();
        List<Map<String, Object>> results = new ArrayList<>();
        int itemIndex = 0;

        for (Map<String, Object> item : items) {
            itemIndex++;
            String question = extractQuestionText(item);
            String expectedAnswer = extractExpectedAnswer(item);
            String itemId = item.get("id") != null ? item.get("id").toString() : "q-" + itemIndex;
            String callQuestion = buildMultimodalCallQuestion(item, question);
            List<String> questionImages = extractQuestionImages(item);
            List<String> questionFiles = extractQuestionFiles(item);
            Map<String, Object> outputs = new HashMap<>();
            Map<String, Object> scores = new HashMap<>();
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            log.info("多模态 Agent 评测处理 {}/{} 题目: {}", itemIndex, items.size(),
                    question == null ? "" : question.substring(0, Math.min(30, question.length())) + "...");

            for (Map<String, Object> target : targets) {
                String targetId = (String) target.get("id");
                String agentId = firstNonBlank(stringValue(target.get("agentId")), stringValue(target.get("agent_id")));
                String agentType = stringValue(target.getOrDefault("agentType", "bot"));
                String envId = (String) target.get("envId");
                Map<String, Object> env = environmentStore.get(envId);
                String envUrl = target.get("envUrl") == null
                        ? (env == null ? null : stringValue(env.get("url")))
                        : stringValue(target.get("envUrl"));
                if (envUrl == null) {
                    Map<String, Object> output = buildOutput("环境配置不存在", 0, "metadata-only",
                            questionImages, questionFiles, Collections.emptyMap());
                    outputs.put(targetId, output);
                    scores.put(targetId, calculateScore(question, null, expectedAnswer,
                            judgeMode, judgeModelId, item, null));
                    continue;
                }

                boolean forceMultimodal = isDocumentComparisonType(getExpectedType(item, expectedAnswer));
                String expectedDocType = forceMultimodal ? getExpectedType(item, expectedAnswer) : null;
                CompletableFuture<Map<String, Object>> chatFuture = chatWithMultimodalAgentByPlatform(
                        envUrl, agentId, agentType, targetId, callQuestion,
                        questionImages, questionFiles, target, expectedDocType, semimindWindowCounters);
                CompletableFuture<Void> future = chatFuture.thenAccept(agentResult -> {
                    Map<String, Object> output = buildOutputFromAgentResult(
                            agentResult, questionImages, questionFiles);
                    synchronized (outputs) {
                        outputs.put(targetId, output);
                    }
                    String answer = stringValue(agentResult.get("answer"));
                    Map<String, Object> score = calculateScore(
                            question, answer, expectedAnswer, judgeMode, judgeModelId, item, agentResult);
                    synchronized (scores) {
                        scores.put(targetId, score);
                    }
                });
                futures.add(future);
            }

            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .get(AGENT_EVALUATION_WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("部分多模态 Agent 调用异常: {}", e.getMessage());
            }
            results.add(buildResultEntry(item, itemId, question, expectedAnswer, outputs, scores, true));
        }
        return results;
    }

    private void executeMultimodalAgentQuestionStreaming(String evalId,
            List<Map<String, Object>> targets, Map<String, Object> item, String question,
            String expectedAnswer, int questionIndex, Map<String, Object> outputs,
            Map<String, Object> scores, SseEmitter emitter, String judgeMode,
            String judgeModelId, Map<String, AtomicInteger> semimindWindowCounters) {
        List<String> questionImages = extractQuestionImages(item);
        List<String> questionFiles = extractQuestionFiles(item);
        String callQuestion = buildMultimodalCallQuestion(item, question);
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (Map<String, Object> target : targets) {
            String targetId = (String) target.get("id");
            String agentId = firstNonBlank(stringValue(target.get("agentId")), stringValue(target.get("agent_id")));
            String agentType = stringValue(target.getOrDefault("agentType", "bot"));
            String envUrl = stringValue(target.get("envUrl"));
            if (envUrl == null) {
                Map<String, Object> env = environmentStore.get(stringValue(target.get("envId")));
                envUrl = env == null ? null : stringValue(env.get("url"));
            }
            if (envUrl == null) {
                outputs.put(targetId, buildOutput("环境配置不存在", 0, "metadata-only",
                        questionImages, questionFiles, Collections.emptyMap()));
                scores.put(targetId, calculateScore(question, null, expectedAnswer,
                        judgeMode, judgeModelId, item, null));
                lifecycle.pushStep(evalId, questionIndex, targetId, "scored", "环境配置不存在");
                continue;
            }

            String expectedDocType = isDocumentComparisonType(getExpectedType(item, expectedAnswer))
                    ? getExpectedType(item, expectedAnswer) : null;
            lifecycle.pushStep(evalId, questionIndex, targetId, "calling", "调用中");
            CompletableFuture<Map<String, Object>> chatFuture = chatWithMultimodalAgentByPlatform(
                    envUrl, agentId, agentType, targetId, callQuestion,
                    questionImages, questionFiles, target, expectedDocType, semimindWindowCounters);

            CompletableFuture<Void> future = chatFuture.thenAccept(agentResult -> {
                Map<String, Object> output = buildOutputFromAgentResult(
                        agentResult, questionImages, questionFiles);
                synchronized (outputs) {
                    outputs.put(targetId, output);
                }
                lifecycle.pushStep(evalId, questionIndex, targetId, "answered", "已应答",
                        agentResult.get("responseTime"));

                try {
                    Map<String, Object> resultEvent = buildResultEvent(
                            questionIndex, targetId, agentResult.get("answer"),
                            agentResult.get("responseTime"), questionImages, questionFiles,
                            extractModalities(item, questionImages, questionFiles),
                            output.get("multimodalTransport"));
                    copyOptionalAgentResultFields(agentResult, resultEvent);
                    emitter.send(SseEmitter.event().name("result")
                            .data(objectMapper.writeValueAsString(resultEvent), MediaType.TEXT_PLAIN));
                } catch (Exception e) {
                    log.warn("推送多模态 Agent 结果事件失败: {}", e.getMessage());
                }

                lifecycle.pushStep(evalId, questionIndex, targetId, "scoring", "评分中");
                Map<String, Object> score = calculateScore(question,
                        stringValue(agentResult.get("answer")), expectedAnswer,
                        judgeMode, judgeModelId, item, agentResult);
                synchronized (scores) {
                    scores.put(targetId, score);
                }
                lifecycle.pushStep(evalId, questionIndex, targetId, "scored", "已评分");
            });
            futures.add(future);
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(STREAMING_AGENT_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("部分多模态 Agent 调用超时/异常: {}", e.getMessage());
            for (Map<String, Object> target : targets) {
                String targetId = stringValue(target.get("id"));
                if (!outputs.containsKey(targetId)) {
                    outputs.put(targetId, buildOutput("Agent调用超时（" + STREAMING_AGENT_WAIT_SECONDS
                            + " 秒），可稍后在后台生成中，完成后使用【重跑该题】重新获取结果",
                            0, "metadata-only", questionImages, questionFiles, Collections.emptyMap()));
                    scores.put(targetId, calculateScore(question, null, expectedAnswer,
                            judgeMode, judgeModelId, item, null));
                }
            }
        }
    }

    private void executeMultimodalModelQuestionStreaming(String evalId,
            List<Map<String, Object>> targets, Map<String, Object> item, String question,
            String expectedAnswer, int questionIndex, Map<String, Object> outputs,
            Map<String, Object> scores, SseEmitter emitter, String judgeMode, String judgeModelId) {
        List<String> questionImages = extractQuestionImages(item);
        List<String> questionFiles = extractQuestionFiles(item);
        String callQuestion = buildMultimodalCallQuestion(item, question);
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (Map<String, Object> target : targets) {
            String targetId = (String) target.get("id");
            String modelId = (String) target.get("modelId");
            Map<String, Object> modelConfig = modelStore.get(modelId);
            if (modelConfig == null) {
                outputs.put(targetId, buildOutput("模型配置不存在: " + modelId, 0,
                        "metadata-only", questionImages, questionFiles, Collections.emptyMap()));
                scores.put(targetId, calculateScore(question, null, expectedAnswer,
                        judgeMode, judgeModelId, item, null));
                lifecycle.pushStep(evalId, questionIndex, targetId, "scored", "模型配置不存在");
                continue;
            }

            lifecycle.pushStep(evalId, questionIndex, targetId, "calling", "调用中");
            CompletableFuture<Void> future = llmService.chatAsync(modelConfig, callQuestion)
                    .thenAccept(llmResult -> {
                        String answer = stringValue(llmResult.get("answer"));
                        long responseTime = llmResult.get("responseTime") instanceof Number
                                ? ((Number) llmResult.get("responseTime")).longValue() : 0;
                        Map<String, Object> output = buildOutput(answer, responseTime, "metadata-only",
                                questionImages, questionFiles, Collections.emptyMap());
                        synchronized (outputs) {
                            outputs.put(targetId, output);
                        }
                        lifecycle.pushStep(evalId, questionIndex, targetId, "answered", "已应答", responseTime);

                        try {
                            Map<String, Object> resultEvent = buildResultEvent(
                                    questionIndex, targetId, answer, responseTime,
                                    questionImages, questionFiles,
                                    extractModalities(item, questionImages, questionFiles), "metadata-only");
                            emitter.send(SseEmitter.event().name("result")
                                    .data(objectMapper.writeValueAsString(resultEvent), MediaType.TEXT_PLAIN));
                        } catch (Exception e) {
                            log.warn("推送多模态模型结果事件失败: {}", e.getMessage());
                        }

                        lifecycle.pushStep(evalId, questionIndex, targetId, "scoring", "评分中");
                        Map<String, Object> score = calculateScore(
                                question, answer, expectedAnswer, judgeMode, judgeModelId, item, null);
                        synchronized (scores) {
                            scores.put(targetId, score);
                        }
                        lifecycle.pushStep(evalId, questionIndex, targetId, "scored", "已评分");
                    });
            futures.add(future);
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(STREAMING_MODEL_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("部分多模态模型调用超时/异常: {}", e.getMessage());
            for (Map<String, Object> target : targets) {
                String targetId = stringValue(target.get("id"));
                if (!outputs.containsKey(targetId)) {
                    outputs.put(targetId, buildOutput("模型调用超时（" + STREAMING_MODEL_WAIT_SECONDS
                            + " 秒），可稍后使用【重跑该题】重新获取结果", 0,
                            "metadata-only", questionImages, questionFiles, Collections.emptyMap()));
                    scores.put(targetId, calculateScore(question, null, expectedAnswer,
                            judgeMode, judgeModelId, item, null));
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> collectEvaluationItems(List<String> datasetIds) {
        List<Map<String, Object>> allItems = new ArrayList<>();
        if (datasetIds == null) {
            return allItems;
        }
        for (String datasetId : datasetIds) {
            Map<String, Object> dataset = datasetStore.get(datasetId);
            if (dataset == null || !(dataset.get("items") instanceof List)) {
                continue;
            }
            String datasetName = stringValue(dataset.get("name"));
            String datasetType = firstNonBlank(stringValue(dataset.get("datasetType")),
                    stringValue(dataset.get("dataset_type")), EVALUATION_TYPE_TEXT);
            Object datasetModalities = dataset.get("modalities");
            Object datasetHasMultimodal = firstPresent(dataset, "hasMultimodal", "has_multimodal");
            Object sourceFormat = firstPresent(dataset, "sourceFormat", "source_format");
            for (Map<String, Object> item : (List<Map<String, Object>>) dataset.get("items")) {
                Map<String, Object> copy = new HashMap<>(item);
                copy.putIfAbsent("datasetId", datasetId);
                copy.putIfAbsent("datasetName", datasetName);
                copy.putIfAbsent("datasetType", datasetType);
                copy.putIfAbsent("hasMultimodal", datasetHasMultimodal);
                copy.putIfAbsent("sourceFormat", sourceFormat);
                if (!copy.containsKey("modalities") && datasetModalities != null) {
                    copy.put("modalities", datasetModalities);
                }
                allItems.add(copy);
            }
        }
        return allItems;
    }

    private Map<String, Object> buildResultEntry(Map<String, Object> item, String itemId,
            String question, String expectedAnswer, Map<String, Object> outputs,
            Map<String, Object> scores, boolean multimodal) {
        List<String> questionImages = extractQuestionImages(item);
        List<String> questionFiles = extractQuestionFiles(item);
        List<String> modalities = extractModalities(item, questionImages, questionFiles);
        String expectedType = getExpectedType(item, expectedAnswer);
        boolean hasExpected = hasExpectedAnswer(expectedAnswer) || isDocumentComparisonType(expectedType);
        Map<String, Object> result = new HashMap<>();
        result.put("id", "r-" + itemId);
        result.put("question", itemId);
        result.put("questionText", question);
        result.put("questionImages", questionImages);
        result.put("question_images", questionImages);
        result.put("images", questionImages);
        result.put("questionFiles", questionFiles);
        result.put("question_files", questionFiles);
        result.put("files", questionFiles);
        result.put("expectedAnswer", expectedAnswer);
        result.put("expected", buildExpectedResultObject(item, expectedAnswer, expectedType));
        result.put("outputs", outputs == null ? new HashMap<>() : outputs);
        result.put("scores", scores == null ? new HashMap<>() : scores);
        result.put("hasExpected", hasExpected);
        result.put("hasExpectedAnswer", hasExpected);
        result.put("judgeMode", hasExpected ? "auto" : "manual");
        result.put("scoreStatus", hasExpected ? SCORE_STATUS_AUTO_SCORED : SCORE_STATUS_MANUAL_REVIEW);
        result.put("scoreDisplay", hasExpected ? null : SCORE_DISPLAY_MANUAL_REVIEW);
        result.put("scoreSkipped", !hasExpected);
        result.put("scoringSkipped", !hasExpected);
        result.put("manualReviewRequired", !hasExpected);
        if (!hasExpected) {
            result.put("scoringSkippedReason", SCORE_REASON_NO_EXPECTED_ANSWER);
            result.put("scoreDisabledReason", "缺少参考答案，自动评分已跳过");
        }
        result.put("datasetId", item.get("datasetId"));
        result.put("datasetName", item.get("datasetName"));
        result.put("datasetType", multimodal ? EVALUATION_TYPE_MULTIMODAL : EVALUATION_TYPE_TEXT);
        result.put("evaluationType", multimodal ? EVALUATION_TYPE_MULTIMODAL : EVALUATION_TYPE_TEXT);
        result.put("reportType", multimodal ? EVALUATION_TYPE_MULTIMODAL : EVALUATION_TYPE_TEXT);
        result.put("hasMultimodal", multimodal || modalities.contains("image") || modalities.contains("file"));
        result.put("modalities", modalities);
        result.put("inputPreview", buildInputPreview(question, questionImages, questionFiles));
        result.put("expectedType", expectedType);
        result.put("multimodalTransport", resolveResultTransport(outputs, multimodal));
        result.put("sourceFormat", item.get("sourceFormat"));
        return result;
    }

    private Map<String, Object> buildExpectedResultObject(Map<String, Object> item,
            String expectedAnswer, String expectedType) {
        Map<String, Object> expected = new HashMap<>();
        expected.put("text", hasExpectedAnswer(expectedAnswer) ? expectedAnswer : null);
        expected.put("hasExpected", hasExpectedAnswer(expectedAnswer));
        if (isDocumentComparisonType(expectedType)) {
            Map<String, Object> expectedMap = asMap(item == null ? null : item.get("expected"));
            expected.put("type", expectedType);
            expected.put("fileName", expectedMap == null ? null : stringValue(expectedMap.get("fileName")));
        }
        return expected;
    }

    private boolean isDocumentComparisonType(String expectedType) {
        return PptTypeUtils.isPptExpectedType(expectedType) || HtmlTypeUtils.isHtmlExpectedType(expectedType);
    }

    private String resolveResultTransport(Map<String, Object> outputs, boolean multimodal) {
        if (outputs != null) {
            for (Object value : outputs.values()) {
                Map<String, Object> output = asMap(value);
                String transport = output == null ? null : stringValue(output.get("multimodalTransport"));
                if (!isBlank(transport)) {
                    return transport;
                }
            }
        }
        return multimodal ? "metadata-only" : "text-only";
    }

    private Map<String, Object> buildOutputFromAgentResult(Map<String, Object> agentResult,
            List<String> fallbackImages, List<String> fallbackFiles) {
        Map<String, Object> safeResult = agentResult == null ? new HashMap<>() : agentResult;
        String transport = firstNonBlank(stringValue(safeResult.get("multimodalTransport")), "metadata-only");
        Object resultImages = safeResult.get("questionImages") != null
                ? safeResult.get("questionImages") : fallbackImages;
        Object resultFiles = safeResult.get("questionFiles") != null
                ? safeResult.get("questionFiles") : fallbackFiles;
        Map<String, Object> output = buildOutput(safeResult.get("answer"), safeResult.get("responseTime"),
                transport, resultImages, resultFiles, safeResult);
        copyOptionalAgentResultFields(safeResult, output);
        return output;
    }

    private Map<String, Object> buildOutput(Object answer, Object responseTime, String multimodalTransport,
            Object questionImages, Object questionFiles, Map<String, Object> extraFields) {
        Map<String, Object> output = new HashMap<>();
        output.put("answer", answer);
        output.put("responseTime", responseTime == null ? 0 : responseTime);
        output.put("evaluationType", EVALUATION_TYPE_MULTIMODAL);
        output.put("datasetType", EVALUATION_TYPE_MULTIMODAL);
        output.put("hasMultimodal", true);
        output.put("multimodalTransport", firstNonBlank(multimodalTransport, "metadata-only"));
        Object images = questionImages == null ? Collections.emptyList() : questionImages;
        Object files = questionFiles == null ? Collections.emptyList() : questionFiles;
        output.put("questionImages", images);
        output.put("question_images", images);
        output.put("images", images);
        output.put("questionFiles", files);
        output.put("question_files", files);
        output.put("files", files);
        if (extraFields != null) {
            if (extraFields.get("imgMap") != null) output.put("imgMap", extraFields.get("imgMap"));
            if (extraFields.get("modalities") != null) output.put("modalities", extraFields.get("modalities"));
        }
        return output;
    }

    private String buildMultimodalCallQuestion(Map<String, Object> item, String question) {
        String text = question == null ? "" : question.trim();
        if (!text.isEmpty()) return text;
        List<String> images = extractQuestionImages(item);
        List<String> files = extractQuestionFiles(item);
        String preview = buildInputPreview(text, images, files);
        return preview == null || preview.isEmpty() ? "请根据指定的多模态输入完成回答。" : preview;
    }

    private String buildInputPreview(String question, List<String> images, List<String> files) {
        StringBuilder sb = new StringBuilder();
        if (!isBlank(question)) sb.append(question.trim());
        if (images != null && !images.isEmpty()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("[图片数量: ").append(images.size()).append("] ").append(images);
        }
        if (files != null && !files.isEmpty()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("[文件数量: ").append(files.size()).append("] ").append(files);
        }
        return sb.toString();
    }

    private Map<String, Object> buildStreamingInitEvent(String evalId, int total, int targetCount,
            Map<String, Object> eval) {
        Map<String, Object> event = new HashMap<>();
        event.put("evalId", evalId);
        event.put("total", total);
        event.put("targetCount", targetCount);
        putMultimodalFlags(event);
        event.put("reportType", EVALUATION_TYPE_MULTIMODAL);
        event.put("modalities", eval == null ? Collections.emptyList() : eval.get("modalities"));
        return event;
    }

    private Map<String, Object> buildProgressEvent(int questionIndex, int total, int displayIndex,
            List<String> modalities, List<String> questionImages, List<String> questionFiles,
            String inputPreview, boolean hasExpectedAnswer) {
        Map<String, Object> event = new HashMap<>();
        event.put("questionIndex", questionIndex);
        event.put("total", total);
        event.put("message", "处理第 " + displayIndex + "/" + total + " 道多模态题");
        putMultimodalFlags(event);
        event.put("modalities", modalities);
        putMediaFields(event, questionImages, questionFiles);
        event.put("inputPreview", inputPreview);
        putExpectedScoreFlags(event, hasExpectedAnswer);
        return event;
    }

    private Map<String, Object> buildScoreEvent(int questionIndex, Map<String, Object> scores,
            boolean hasExpectedAnswer) {
        Map<String, Object> event = new HashMap<>();
        event.put("questionIndex", questionIndex);
        event.put("scores", scores);
        putMultimodalFlags(event);
        putExpectedScoreFlags(event, hasExpectedAnswer);
        event.put("scoreDisplay", hasExpectedAnswer ? null : SCORE_DISPLAY_MANUAL_REVIEW);
        return event;
    }

    private Map<String, Object> buildCompleteEvent(String evalId, Map<String, Object> eval) {
        Map<String, Object> event = new HashMap<>();
        event.put("evalId", evalId);
        event.put("status", eval.get("status"));
        event.put("accuracy", eval.get("accuracy"));
        event.put("scoreStatus", eval.get("scoreStatus"));
        event.put("scoreDisplay", eval.get("scoreDisplay"));
        event.put("hasAutoScore", eval.get("hasAutoScore"));
        event.put("manualReviewRequired", eval.get("manualReviewRequired"));
        event.put("endTime", eval.get("endTime"));
        putMultimodalFlags(event);
        event.put("reportType", EVALUATION_TYPE_MULTIMODAL);
        event.put("modalities", eval.get("modalities"));
        return event;
    }

    private Map<String, Object> buildResultEvent(int questionIndex, String targetId, Object answer,
            Object responseTime, Object questionImages, Object questionFiles,
            List<String> modalities, Object multimodalTransport) {
        Map<String, Object> event = new HashMap<>();
        event.put("questionIndex", questionIndex);
        event.put("targetId", targetId);
        event.put("answer", answer);
        event.put("responseTime", responseTime == null ? 0 : responseTime);
        putMultimodalFlags(event);
        putMediaFields(event, questionImages, questionFiles);
        event.put("modalities", modalities);
        event.put("multimodalTransport", firstNonBlank(stringValue(multimodalTransport), "metadata-only"));
        return event;
    }

    private void putMultimodalFlags(Map<String, Object> target) {
        if (target == null) return;
        target.put("evaluationType", EVALUATION_TYPE_MULTIMODAL);
        target.put("datasetType", EVALUATION_TYPE_MULTIMODAL);
        target.put("hasMultimodal", true);
    }

    private void putExpectedScoreFlags(Map<String, Object> target, boolean hasExpectedAnswer) {
        if (target == null) return;
        target.put("hasExpected", hasExpectedAnswer);
        target.put("hasExpectedAnswer", hasExpectedAnswer);
        target.put("scoreStatus", hasExpectedAnswer ? SCORE_STATUS_AUTO_SCORED : SCORE_STATUS_MANUAL_REVIEW);
    }

    private void putMediaFields(Map<String, Object> target, Object questionImages, Object questionFiles) {
        if (target == null) return;
        Object images = questionImages == null ? Collections.emptyList() : questionImages;
        Object files = questionFiles == null ? Collections.emptyList() : questionFiles;
        target.put("questionImages", images);
        target.put("question_images", images);
        target.put("images", images);
        target.put("questionFiles", files);
        target.put("question_files", files);
        target.put("files", files);
    }

    private void initMultimodalEvalMetadata(Map<String, Object> eval, List<Map<String, Object>> items) {
        if (eval == null) return;
        eval.put("evaluationType", EVALUATION_TYPE_MULTIMODAL);
        eval.put("reportType", EVALUATION_TYPE_MULTIMODAL);
        eval.put("datasetType", EVALUATION_TYPE_MULTIMODAL);
        eval.put("hasMultimodal", true);
        eval.put("modalities", collectEvaluationModalities(items));
    }

    private List<String> collectEvaluationModalities(List<Map<String, Object>> items) {
        LinkedHashSet<String> modalities = new LinkedHashSet<>();
        modalities.add("text");
        if (items != null) {
            for (Map<String, Object> item : items) {
                List<String> images = extractQuestionImages(item);
                List<String> files = extractQuestionFiles(item);
                modalities.addAll(extractModalities(item, images, files));
            }
        }
        return new ArrayList<>(modalities);
    }

    private boolean asBoolean(Object value, boolean defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).intValue() != 0;
        String text = value.toString().trim().toLowerCase(Locale.ROOT);
        if ("true".equals(text) || "1".equals(text) || "yes".equals(text) || "y".equals(text)) return true;
        if ("false".equals(text) || "0".equals(text) || "no".equals(text) || "n".equals(text)) return false;
        return defaultValue;
    }

    private String extractQuestionText(Map<String, Object> item) {
        if (item == null) return "";
        return firstNonBlank(stringValue(item.get("question")), stringValue(item.get("prompt")),
                stringValue(item.get("query")), stringValue(item.get("text")),
                extractInputText(item.get("input")), extractQuestionFromMessages(item.get("messages")));
    }

    private String extractInputText(Object inputObj) {
        if (inputObj == null) return null;
        if (inputObj instanceof String || inputObj instanceof Number || inputObj instanceof Boolean) {
            return inputObj.toString();
        }
        Map<String, Object> inputMap = asMap(inputObj);
        if (inputMap == null) return null;
        return firstNonBlank(stringValue(inputMap.get("text")), stringValue(inputMap.get("question")),
                stringValue(inputMap.get("prompt")), stringValue(inputMap.get("query")),
                extractTextFromContent(inputMap.get("content")), extractQuestionFromMessages(inputMap.get("messages")));
    }

    private String extractExpectedAnswer(Map<String, Object> item) {
        if (item == null) return null;
        return firstNonBlank(stringValue(item.get("expectedAnswer")), stringValue(item.get("expected_answer")),
                stringValue(item.get("answer")), stringValue(item.get("expectedResult")),
                stringValue(item.get("expected_result")), stringValue(item.get("reference")),
                stringValue(item.get("referenceAnswer")), extractExpectedText(item.get("expected")),
                extractExpectedText(item.get("output")), extractExpectedText(item.get("target")));
    }

    private String extractExpectedText(Object expectedObj) {
        if (expectedObj == null) return null;
        if (expectedObj instanceof String || expectedObj instanceof Number || expectedObj instanceof Boolean) {
            return expectedObj.toString();
        }
        Map<String, Object> expectedMap = asMap(expectedObj);
        if (expectedMap == null) return null;
        return firstNonBlank(stringValue(expectedMap.get("text")), stringValue(expectedMap.get("value")),
                stringValue(expectedMap.get("answer")), stringValue(expectedMap.get("expectedAnswer")),
                stringValue(expectedMap.get("expected_answer")), extractTextFromContent(expectedMap.get("content")));
    }

    private String getExpectedType(Map<String, Object> item, String expectedAnswer) {
        Map<String, Object> expected = asMap(item == null ? null : item.get("expected"));
        if (expected != null && expected.get("type") != null) return expected.get("type").toString();
        return hasExpectedAnswer(expectedAnswer) ? "text" : "none";
    }

    private boolean hasExpectedAnswer(String expectedAnswer) {
        return !isBlank(expectedAnswer);
    }

    private byte[] extractExpectedPptBytes(Map<String, Object> item) {
        Map<String, Object> expected = asMap(item == null ? null : item.get("expected"));
        if (expected == null) return null;
        String data = firstNonBlank(stringValue(expected.get("data")),
                stringValue(expected.get("fileData")), stringValue(expected.get("base64")));
        if (!isBlank(data)) {
            String base64 = data;
            int comma = data.indexOf(',');
            if (data.startsWith("data:") && comma >= 0) base64 = data.substring(comma + 1);
            try {
                return Base64.getDecoder().decode(base64);
            } catch (Exception e) {
                log.warn("解析标准答案 PPT base64 内容失败: {}", e.getMessage());
                return null;
            }
        }
        String url = stringValue(expected.get("url"));
        if (!isBlank(url)) {
            String objectName = minioStorageService.extractObjectName(url);
            if (objectName != null) {
                try {
                    return minioStorageService.download(objectName);
                } catch (Exception e) {
                    log.warn("从 MinIO 下载标准答案 PPT 失败: url={}, error={}", url, e.getMessage());
                }
            }
        }
        return null;
    }

    private String extractExpectedHtmlText(Map<String, Object> item) {
        Map<String, Object> expected = asMap(item == null ? null : item.get("expected"));
        if (expected == null) return null;
        String url = stringValue(expected.get("url"));
        if (isBlank(url)) return null;
        String objectName = minioStorageService.extractObjectName(url);
        if (objectName == null) return null;
        try {
            byte[] bytes = minioStorageService.download(objectName);
            return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("从 MinIO 下载标准答案 HTML 失败: url={}, error={}", url, e.getMessage());
            return null;
        }
    }

    private List<String> extractQuestionImages(Map<String, Object> item) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (item == null) return new ArrayList<>(result);
        addStringValues(result, item.get("questionImages"));
        addStringValues(result, item.get("question_images"));
        addStringValues(result, item.get("images"));
        addStringValues(result, item.get("image"));
        Map<String, Object> input = asMap(item.get("input"));
        if (input != null) {
            addStringValues(result, input.get("questionImages"));
            addStringValues(result, input.get("question_images"));
            addStringValues(result, input.get("images"));
            addStringValues(result, input.get("image"));
            collectMediaFromMessages(result, input.get("messages"), true);
            collectMediaFromContent(result, input.get("content"), true);
        }
        collectMediaFromMessages(result, item.get("messages"), true);
        collectMediaFromContent(result, item.get("content"), true);
        return new ArrayList<>(result);
    }

    private List<String> extractQuestionFiles(Map<String, Object> item) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (item == null) return new ArrayList<>(result);
        addStringValues(result, item.get("questionFiles"));
        addStringValues(result, item.get("question_files"));
        addStringValues(result, item.get("files"));
        addStringValues(result, item.get("file"));
        addStringValues(result, item.get("attachments"));
        Map<String, Object> input = asMap(item.get("input"));
        if (input != null) {
            addStringValues(result, input.get("questionFiles"));
            addStringValues(result, input.get("question_files"));
            addStringValues(result, input.get("files"));
            addStringValues(result, input.get("file"));
            addStringValues(result, input.get("attachments"));
            collectMediaFromMessages(result, input.get("messages"), false);
            collectMediaFromContent(result, input.get("content"), false);
        }
        collectMediaFromMessages(result, item.get("messages"), false);
        collectMediaFromContent(result, item.get("content"), false);
        return new ArrayList<>(result);
    }

    private List<String> extractModalities(Map<String, Object> item, List<String> images, List<String> files) {
        LinkedHashSet<String> modalities = new LinkedHashSet<>();
        modalities.add("text");
        if (item != null) addNormalizedModalities(modalities, item.get("modalities"));
        if (images != null && !images.isEmpty()) modalities.add("image");
        if (files != null && !files.isEmpty()) modalities.add("file");
        return new ArrayList<>(modalities);
    }

    private void collectMediaFromMessages(Set<String> result, Object messagesObj, boolean image) {
        if (!(messagesObj instanceof Collection<?>)) return;
        for (Object messageObj : (Collection<?>) messagesObj) {
            Map<String, Object> message = asMap(messageObj);
            if (message != null) {
                collectMediaFromContent(result, firstPresent(message, "content", "parts"), image);
            }
        }
    }

    private void collectMediaFromContent(Set<String> result, Object contentObj, boolean image) {
        if (contentObj == null) return;
        if (contentObj instanceof Collection<?>) {
            for (Object part : (Collection<?>) contentObj) collectMediaFromContent(result, part, image);
            return;
        }
        Map<String, Object> part = asMap(contentObj);
        if (part == null) {
            if (contentObj instanceof String) {
                String text = ((String) contentObj).trim();
                if (image ? looksLikeImage(text) : looksLikeFile(text)) result.add(text);
            }
            return;
        }
        String type = stringValue(part.get("type"));
        Object media = firstPresent(part, image
                ? new String[] {"image_url", "imageUrl", "url", "data", "path", "file"}
                : new String[] {"file_url", "fileUrl", "url", "path", "file", "document"});
        boolean matchingType = image
                ? type != null && (type.toLowerCase(Locale.ROOT).contains("image") || type.equalsIgnoreCase("photo"))
                : type != null && (type.toLowerCase(Locale.ROOT).contains("file")
                        || type.toLowerCase(Locale.ROOT).contains("document"));
        if (media != null && (matchingType || looksLikeMediaValue(media, image))) addStringValues(result, media);
        if (part.get("content") != null) collectMediaFromContent(result, part.get("content"), image);
        if (part.get("parts") != null) collectMediaFromContent(result, part.get("parts"), image);
    }

    private boolean looksLikeMediaValue(Object value, boolean image) {
        if (value instanceof Map<?, ?>) {
            Map<String, Object> map = asMap(value);
            return map != null && looksLikeMediaValue(firstPresent(map, "url", "data", "path", "file"), image);
        }
        if (value instanceof Collection<?>) {
            for (Object item : (Collection<?>) value) if (looksLikeMediaValue(item, image)) return true;
            return false;
        }
        String text = stringValue(value);
        return image ? looksLikeImage(text) : looksLikeFile(text);
    }

    private boolean looksLikeImage(String value) {
        if (isBlank(value)) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.startsWith("data:image/") || lower.matches(".*\\.(png|jpe?g|gif|webp|bmp|svg)(\\?.*)?$");
    }

    private boolean looksLikeFile(String value) {
        if (isBlank(value)) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.startsWith("data:") || lower.matches(".*\\.(pdf|docx?|pptx?|xlsx?|csv|txt|html?|zip)(\\?.*)?$");
    }

    private String extractQuestionFromMessages(Object messagesObj) {
        if (!(messagesObj instanceof Collection<?>)) return null;
        String lastUserContent = null;
        for (Object messageObj : (Collection<?>) messagesObj) {
            Map<String, Object> message = asMap(messageObj);
            if (message == null) continue;
            String role = stringValue(message.get("role"));
            if (role != null && !role.isBlank() && !"user".equalsIgnoreCase(role.trim())) continue;
            String text = firstNonBlank(stringValue(message.get("text")), stringValue(message.get("value")),
                    extractTextFromContent(firstPresent(message, "content", "parts")));
            if (text != null && !text.trim().isEmpty()) lastUserContent = text.trim();
        }
        return lastUserContent;
    }

    private String extractTextFromContent(Object contentObj) {
        if (contentObj == null) return null;
        if (contentObj instanceof String || contentObj instanceof Number || contentObj instanceof Boolean) {
            return contentObj.toString();
        }
        if (contentObj instanceof Collection<?>) {
            StringBuilder sb = new StringBuilder();
            for (Object part : (Collection<?>) contentObj) {
                String text = extractTextFromContent(part);
                if (text != null && !text.trim().isEmpty()) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(text.trim());
                }
            }
            return sb.length() == 0 ? null : sb.toString();
        }
        Map<String, Object> part = asMap(contentObj);
        if (part == null) return null;
        if (part.containsKey("role") && part.containsKey("content")) return extractTextFromContent(part.get("content"));
        String type = stringValue(part.get("type"));
        Object text = firstPresent(part, "text", "content", "value");
        if (text == null) return null;
        String normalizedType = type == null ? "" : type.toLowerCase(Locale.ROOT);
        if (!normalizedType.isEmpty() && (normalizedType.contains("image") || normalizedType.contains("file")
                || normalizedType.contains("document") || normalizedType.contains("audio"))) return null;
        return extractTextFromContent(text);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?>)) return null;
        Map<String, Object> result = new HashMap<>();
        ((Map<?, ?>) value).forEach((key, item) -> {
            if (key != null) result.put(key.toString(), item);
        });
        return result;
    }

    private Object firstPresent(Map<String, Object> map, String... keys) {
        if (map == null || keys == null) return null;
        for (String key : keys) {
            if (map.containsKey(key) && map.get(key) != null) return map.get(key);
        }
        return null;
    }

    private void addStringValues(Set<String> result, Object value) {
        if (result == null || value == null) return;
        if (value instanceof Collection<?>) {
            for (Object item : (Collection<?>) value) addStringValues(result, item);
            return;
        }
        Map<String, Object> map = asMap(value);
        if (map != null) {
            addStringValues(result, firstPresent(map, "value", "url", "uri", "href", "path", "file", "data",
                    "image_url", "imageUrl", "file_url", "fileUrl"));
            return;
        }
        String text = value.toString().trim();
        if (!text.isEmpty()) result.add(text);
    }

    private void addNormalizedModalities(Set<String> result, Object value) {
        if (result == null || value == null) return;
        if (value instanceof Collection<?>) {
            for (Object item : (Collection<?>) value) addNormalizedModalities(result, item);
            return;
        }
        String text = value.toString().trim();
        if (text.startsWith("[") && text.endsWith("]")) text = text.substring(1, text.length() - 1);
        for (String part : text.split(",")) {
            String normalized = part.replace("\"", "").replace("'", "").trim().toLowerCase(Locale.ROOT);
            if (normalized.isEmpty()) continue;
            if (normalized.contains("image") || normalized.contains("img") || normalized.contains("picture")) {
                result.add("image");
            } else if (normalized.contains("file") || normalized.contains("document") || normalized.contains("pdf")
                    || normalized.contains("ppt") || normalized.contains("html")) {
                result.add("file");
            } else if (normalized.contains("text")) {
                result.add("text");
            } else {
                result.add(normalized);
            }
        }
    }

    private Map<String, Object> calculateScore(String question, String answer, String expectedAnswer,
            String judgeMode, String judgeModelId, Map<String, Object> item, Map<String, Object> agentResult) {
        String expectedType = getExpectedType(item, expectedAnswer);
        if (PptTypeUtils.isPptExpectedType(expectedType)) return calculatePptScore(item, agentResult, judgeModelId);
        if (HtmlTypeUtils.isHtmlExpectedType(expectedType)) return calculateHtmlScore(item, agentResult, judgeModelId);
        return calculateScore(question, answer, expectedAnswer, judgeMode, judgeModelId);
    }

    private Map<String, Object> calculatePptScore(Map<String, Object> item,
            Map<String, Object> agentResult, String judgeModelId) {
        Map<String, Object> score = new HashMap<>();
        score.put("hasExpected", true);
        score.put("hasExpectedAnswer", true);
        byte[] expectedPptBytes = extractExpectedPptBytes(item);
        if (expectedPptBytes == null || expectedPptBytes.length == 0) {
            score.put("auto", null);
            score.put("manual", null);
            score.put("final", null);
            score.put("scoreStatus", SCORE_STATUS_MANUAL_REVIEW);
            score.put("scoreDisplay", SCORE_DISPLAY_MANUAL_REVIEW);
            score.put("scoreSkipped", true);
            score.put("scoringSkipped", true);
            score.put("manualReviewRequired", true);
            score.put("scoringSkippedReason", "标准答案PPT缺失或无法读取");
            return score;
        }
        score.put("manualReviewRequired", false);
        String generatedFileObjectName = agentResult == null ? null
                : stringValue(agentResult.get("generatedFileObjectName"));
        if (isBlank(generatedFileObjectName)) {
            Map<String, Object> auto = new HashMap<>();
            auto.put("content", 0.0);
            auto.put("structure", 0.0);
            auto.put("visual", 0.0);
            score.put("auto", auto);
            score.put("manual", null);
            score.put("final", 0.0);
            score.put("scoreStatus", SCORE_STATUS_FAILED);
            score.put("reason", "未获取到 Agent 生成的 PPT 文件");
            return score;
        }
        try {
            byte[] generatedPptBytes = minioStorageService.download(generatedFileObjectName);
            Map<String, Object> judgeModelConfig = isBlank(judgeModelId) ? null : modelStore.get(judgeModelId);
            Map<String, Object> pptResult = pptScoringService.score(generatedPptBytes, expectedPptBytes,
                    judgeModelConfig);
            score.put("auto", pptResult.get("dimensions"));
            score.put("manual", null);
            score.put("final", pptResult.get("final"));
            score.put("reason", pptResult.get("reason"));
            score.put("scoreStatus", SCORE_STATUS_AUTO_SCORED);
            return score;
        } catch (Exception e) {
            log.warn("PPT 对比评分失败: {}", e.getMessage());
            score.put("auto", null);
            score.put("manual", null);
            score.put("final", 0.0);
            score.put("scoreStatus", SCORE_STATUS_FAILED);
            score.put("reason", "PPT 对比评分失败: " + e.getMessage());
            return score;
        }
    }

    private Map<String, Object> calculateHtmlScore(Map<String, Object> item,
            Map<String, Object> agentResult, String judgeModelId) {
        Map<String, Object> score = new HashMap<>();
        score.put("hasExpected", true);
        score.put("hasExpectedAnswer", true);
        String expectedHtmlText = extractExpectedHtmlText(item);
        if (isBlank(expectedHtmlText)) {
            score.put("auto", null);
            score.put("manual", null);
            score.put("final", null);
            score.put("scoreStatus", SCORE_STATUS_MANUAL_REVIEW);
            score.put("scoreDisplay", SCORE_DISPLAY_MANUAL_REVIEW);
            score.put("scoreSkipped", true);
            score.put("scoringSkipped", true);
            score.put("manualReviewRequired", true);
            score.put("scoringSkippedReason", "标准答案HTML缺失或无法读取");
            return score;
        }
        score.put("manualReviewRequired", false);
        String generatedFileObjectName = agentResult == null ? null
                : stringValue(agentResult.get("generatedFileObjectName"));
        if (isBlank(generatedFileObjectName)) {
            Map<String, Object> auto = new HashMap<>();
            auto.put("content", 0.0);
            auto.put("structure", 0.0);
            auto.put("visual", 0.0);
            score.put("auto", auto);
            score.put("manual", null);
            score.put("final", 0.0);
            score.put("scoreStatus", SCORE_STATUS_FAILED);
            score.put("reason", "未获取到 Agent 生成的 HTML 文件");
            return score;
        }
        try {
            byte[] generatedBytes = minioStorageService.download(generatedFileObjectName);
            String generatedHtmlText = generatedBytes == null ? null : new String(generatedBytes, StandardCharsets.UTF_8);
            Map<String, Object> judgeModelConfig = isBlank(judgeModelId) ? null : modelStore.get(judgeModelId);
            Map<String, Object> htmlResult = htmlScoringService.score(generatedHtmlText, expectedHtmlText,
                    judgeModelConfig);
            score.put("auto", htmlResult.get("dimensions"));
            score.put("manual", null);
            score.put("final", htmlResult.get("final"));
            score.put("reason", htmlResult.get("reason"));
            score.put("scoreStatus", SCORE_STATUS_AUTO_SCORED);
            return score;
        } catch (Exception e) {
            log.warn("HTML 对比评分失败: {}", e.getMessage());
            score.put("auto", null);
            score.put("manual", null);
            score.put("final", 0.0);
            score.put("scoreStatus", SCORE_STATUS_FAILED);
            score.put("reason", "HTML 对比评分失败: " + e.getMessage());
            return score;
        }
    }

    private Map<String, Object> calculateScore(String question, String answer, String expectedAnswer,
            String judgeMode, String judgeModelId) {
        Map<String, Object> score = new HashMap<>();
        if (!hasExpectedAnswer(expectedAnswer)) {
            score.put("auto", null);
            score.put("manual", null);
            score.put("final", null);
            score.put("scoreStatus", SCORE_STATUS_MANUAL_REVIEW);
            score.put("scoreDisplay", SCORE_DISPLAY_MANUAL_REVIEW);
            score.put("scoreSkipped", true);
            score.put("scoringSkipped", true);
            score.put("manualReviewRequired", true);
            score.put("hasExpected", false);
            score.put("hasExpectedAnswer", false);
            score.put("scoringSkippedReason", SCORE_REASON_NO_EXPECTED_ANSWER);
            score.put("scoreDisabledReason", "缺少参考答案，自动评分已跳过");
            return score;
        }
        score.put("hasExpected", true);
        score.put("hasExpectedAnswer", true);
        score.put("scoreStatus", SCORE_STATUS_AUTO_SCORED);
        score.put("manualReviewRequired", false);
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

        if (isBlank(textForScoring) || textForScoring.startsWith("调用失败")
                || textForScoring.startsWith("调用 SemiClaw 失败")
                || textForScoring.startsWith("调用 SemiMind 失败")
                || textForScoring.startsWith("调用 SemiMind 多模态失败")) {
            Map<String, Object> auto = new HashMap<>();
            auto.put("accuracy", 0.0);
            auto.put("completeness", 0.0);
            auto.put("relevance", 0.0);
            score.put("auto", auto);
            score.put("manual", null);
            score.put("final", 0.0);
            score.put("scoreStatus", SCORE_STATUS_FAILED);
            score.put("reason", "模型调用失败或回答为空");
            return score;
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
            String actualAnswer, String expectedAnswer, double accuracyWeight,
            double completenessWeight, double relevanceWeight) throws Exception {
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

    @SuppressWarnings("unchecked")
    private String extractTextContent(String answer) {
        if (answer == null || answer.isEmpty()) return answer;
        String trimmed = answer.trim();
        if (trimmed.startsWith("{")) {
            try {
                Map<String, Object> json = objectMapper.readValue(trimmed, Map.class);
                Object textContent = json.get("textContent");
                return textContent == null ? answer : textContent.toString();
            } catch (Exception ignored) {
                // 非结构化 JSON 时保留原始回答。
            }
        }
        return answer;
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
        return Math.min(1.0, (double) relevant / answerTokens.size() + 0.3);
    }

    private Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>();
        if (text == null || text.isEmpty()) return tokens;
        String cleaned = text.replaceAll("[\\s\\p{Punct}\\u3000-\\u303F\\uFF00-\\uFFEF\\u2000-\\u206F]", "").trim();
        String[] segments = cleaned.split("\\s+");
        for (String segment : segments) {
            if (segment.length() <= 1) continue;
            for (int i = 0; i < segment.length() - 1; i++) tokens.add(segment.substring(i, i + 2));
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
        String platform = firstNonBlank(stringValue(target.get("platform")),
                stringValue(target.get("targetPlatform")), env == null ? null : stringValue(env.get("platform")));
        if ("semiclaw".equalsIgnoreCase(platform)) return true;
        String envUrl = firstNonBlank(stringValue(target.get("envUrl")),
                env == null ? null : stringValue(env.get("url")), env == null ? null : stringValue(env.get("baseUrl")));
        return isSemiclawUrl(envUrl);
    }

    private boolean isSemimindTarget(Map<String, Object> target, Map<String, Object> env) {
        String platform = firstNonBlank(stringValue(target.get("platform")),
                stringValue(target.get("targetPlatform")), env == null ? null : stringValue(env.get("platform")));
        if ("semimind".equalsIgnoreCase(platform)) return true;
        String envUrl = firstNonBlank(stringValue(target.get("envUrl")),
                env == null ? null : stringValue(env.get("url")), env == null ? null : stringValue(env.get("baseUrl")));
        return isSemimindUrl(envUrl);
    }

    private boolean isSemiclawUrl(String envUrl) {
        if (isBlank(envUrl)) return false;
        String value = envUrl.toLowerCase(Locale.ROOT);
        return platformDetection.isSemiclawUrl(envUrl) || value.contains("semiclaw");
    }

    private boolean isSemimindUrl(String envUrl) {
        if (isBlank(envUrl)) return false;
        String value = envUrl.toLowerCase(Locale.ROOT);
        return platformDetection.isSemimindUrl(envUrl) || value.contains("semimind");
    }

    private CompletableFuture<Map<String, Object>> completedChatFailure(String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("answer", message);
        result.put("responseTime", 0);
        result.put("evaluationType", EVALUATION_TYPE_MULTIMODAL);
        result.put("datasetType", EVALUATION_TYPE_MULTIMODAL);
        result.put("hasMultimodal", true);
        return CompletableFuture.completedFuture(result);
    }

    private static String maskValue(String value) {
        if (value == null || value.isBlank()) return "";
        String trimmed = value.trim();
        int length = trimmed.length();
        if (length <= 4) return "****";
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
                env == null ? null : stringValue(env.get("credentialMode")),
                env == null ? null : stringValue(env.get("credential_mode")),
                env == null ? null : stringValue(env.get("loginMode")),
                env == null ? null : stringValue(env.get("login_mode")));
        if (!isBlank(mode)) return normalizeCredentialMode(mode);
        boolean personal = !isBlank(firstNonBlank(stringValue(target.get("tenantId")),
                stringValue(target.get("tenant_id")), env == null ? null : stringValue(env.get("tenantId")),
                env == null ? null : stringValue(env.get("tenant_id"))))
                && !isBlank(firstNonBlank(stringValue(target.get("username")),
                        env == null ? null : stringValue(env.get("username"))))
                && !isBlank(firstNonBlank(stringValue(target.get("password")),
                        env == null ? null : stringValue(env.get("password"))));
        return personal ? CREDENTIAL_MODE_PERSONAL : CREDENTIAL_MODE_SERVICE_ACCOUNT;
    }

    private String normalizeCredentialMode(String mode) {
        if (isBlank(mode)) return null;
        String value = mode.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if ("service".equals(value) || "service-account".equals(value) || "serviceaccount".equals(value)
                || "backend".equals(value) || "backend-config".equals(value) || "config".equals(value)) {
            return CREDENTIAL_MODE_SERVICE_ACCOUNT;
        }
        if ("personal".equals(value) || "environment".equals(value) || "env".equals(value)
                || "manual".equals(value)) return CREDENTIAL_MODE_PERSONAL;
        return value;
    }

    private String resolveSemimindCredentialMode(Map<String, Object> target, Map<String, Object> env) {
        String credentialMode = normalizeCredentialMode(firstNonBlank(
                stringValue(target.get("credentialMode")), stringValue(target.get("credential_mode")),
                stringValue(target.get("loginMode")), stringValue(target.get("login_mode")),
                env == null ? null : stringValue(env.get("credentialMode")),
                env == null ? null : stringValue(env.get("credential_mode")),
                env == null ? null : stringValue(env.get("loginMode")),
                env == null ? null : stringValue(env.get("login_mode"))));
        if (!isBlank(credentialMode)) return credentialMode;
        boolean hasPersonalCredential = !isBlank(firstNonBlank(stringValue(target.get("tenantId")),
                stringValue(target.get("tenant_id")), env == null ? null : stringValue(env.get("tenantId")),
                env == null ? null : stringValue(env.get("tenant_id"))))
                && !isBlank(firstNonBlank(stringValue(target.get("username")),
                        env == null ? null : stringValue(env.get("username"))))
                && !isBlank(firstNonBlank(stringValue(target.get("password")),
                        env == null ? null : stringValue(env.get("password"))));
        return hasPersonalCredential ? CREDENTIAL_MODE_PERSONAL : CREDENTIAL_MODE_SERVICE_ACCOUNT;
    }

    private List<String> normalizeStringList(List<String> values) {
        if (values == null || values.isEmpty()) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        for (String value : values) if (!isBlank(value)) result.add(value.trim());
        return result;
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) if (!isBlank(value)) return value;
        return null;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    @SuppressWarnings("unchecked")
    private void applyAccuracy(Map<String, Object> eval, List<Map<String, Object>> results) {
        double totalScore = 0;
        int scoreCount = 0;
        int manualReviewCount = 0;
        int targetScoreCount = 0;
        if (results != null) {
            for (Map<String, Object> result : results) {
                if (result == null) continue;
                if (isManualReviewResult(result)) manualReviewCount++;
                Object scoresObj = result.get("scores");
                if (!(scoresObj instanceof Map<?, ?>)) continue;
                Map<?, ?> scores = (Map<?, ?>) scoresObj;
                for (Object value : scores.values()) {
                    if (!(value instanceof Map<?, ?>)) continue;
                    targetScoreCount++;
                    Object finalScore = ((Map<?, ?>) value).get("final");
                    if (finalScore instanceof Number) {
                        totalScore += ((Number) finalScore).doubleValue();
                        scoreCount++;
                    }
                }
            }
        }
        if (scoreCount > 0) {
            eval.put("accuracy", Math.round(totalScore / scoreCount * 100.0) / 100.0);
            eval.put("hasAutoScore", true);
            eval.put("manualReviewRequired", manualReviewCount > 0);
            eval.put("scoreStatus", manualReviewCount > 0
                    ? SCORE_STATUS_PARTIAL_AUTO_SCORED : SCORE_STATUS_AUTO_SCORED);
            eval.put("scoreDisplay", null);
            eval.put("scoredCount", scoreCount);
            eval.put("targetScoreCount", targetScoreCount);
            eval.put("manualReviewCount", manualReviewCount);
        } else {
            eval.put("accuracy", null);
            eval.put("hasAutoScore", false);
            eval.put("manualReviewRequired", true);
            eval.put("scoreStatus", SCORE_STATUS_MANUAL_REVIEW);
            eval.put("scoreDisplay", SCORE_DISPLAY_MANUAL_REVIEW);
            eval.put("scoreSkipped", true);
            eval.put("scoringSkipped", true);
            eval.put("scoringSkippedReason", SCORE_REASON_NO_EXPECTED_ANSWER);
            eval.put("scoredCount", 0);
            eval.put("targetScoreCount", targetScoreCount);
            eval.put("manualReviewCount", Math.max(manualReviewCount, results == null ? 0 : results.size()));
        }
    }

    private boolean isManualReviewResult(Map<String, Object> result) {
        if (result == null) return false;
        Object status = result.get("scoreStatus");
        return SCORE_STATUS_MANUAL_REVIEW.equals(stringValue(status))
                || Boolean.TRUE.equals(result.get("manualReviewRequired"))
                || !Boolean.TRUE.equals(result.get("hasExpectedAnswer"));
    }

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
                if (env != null) target.put("envUrl", env.get("url"));
                String semimindUsername = resolveSemimindRequestUsername(target, env);
                if (!isBlank(semimindUsername)) {
                    target.put("semimindUsername", semimindUsername);
                    target.put("semimind_username", semimindUsername);
                }
                continue;
            }
            String userEmail = stringValue(target.get("userEmail"));
            if (env == null) continue;
            String envUrl = stringValue(env.get("url"));
            try {
                if (isBlank(userEmail)) {
                    Map<String, Object> userInfo = envAgentService.getUserByNickname(envUrl,
                            stringValue(target.get("userName")));
                    if (userInfo != null) userEmail = stringValue(userInfo.get("email"));
                }
                if (!isBlank(userEmail)) {
                    String password = stringValue(target.get("userPassword"));
                    if (isBlank(password)) password = userEmail;
                    Map<String, String> loginResult = userLoginService.login(envUrl, userEmail, password);
                    target.put("authorization", loginResult.get("authorization"));
                    target.put("apiUsername", loginResult.get("apiUsername"));
                    target.put("userEmail", userEmail);
                    String semimindUsername = resolveSemimindRequestUsername(target, env);
                    if (isBlank(semimindUsername)) semimindUsername = loginResult.get("apiUsername");
                    if (!isBlank(semimindUsername)) {
                        target.put("semimindUsername", semimindUsername);
                        target.put("semimind_username", semimindUsername);
                    }
                }
            } catch (Exception e) {
                log.warn("评测登录失败 envUrl={}: {}", envUrl, e.getMessage());
            }
            target.put("envUrl", envUrl);
        }
    }

    private CompletableFuture<Map<String, Object>> chatWithAgentRetryOn401(String envUrl, String agentId,
            String agentType, String targetId, String question, Map<String, Object> target) {
        String authorization = stringValue(target.get("authorization"));
        String apiUsername = stringValue(target.get("apiUsername"));
        CompletableFuture<Map<String, Object>> firstAttempt = isBlank(authorization)
                ? agentChatService.chatWithAgentAsync(envUrl, agentId, agentType, targetId, "", question,
                        null, null, authorization, apiUsername)
                : agentChatService.chatWithAgentAsync(envUrl, agentId, agentType, targetId, "", question);
        return firstAttempt.thenCompose(result -> {
            String answer = result == null ? null : stringValue(result.get("answer"));
            if (answer == null || !answer.contains("401")) return CompletableFuture.completedFuture(result);
            String userEmail = stringValue(target.get("userEmail"));
            if (isBlank(userEmail)) {
                log.warn("401重试失败：无 userEmail，targetId={}", targetId);
                return CompletableFuture.completedFuture(result);
            }
            try {
                userLoginService.invalidateToken(envUrl, userEmail);
                String password = stringValue(target.get("userPassword"));
                if (isBlank(password)) password = userEmail;
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
        String tenantId = firstNonBlank(stringValue(target.get("tenantId")), stringValue(target.get("tenant_id")),
                env == null ? null : stringValue(env.get("tenantId")), env == null ? null : stringValue(env.get("tenant_id")));
        String username = useServiceAccount ? null : firstNonBlank(stringValue(target.get("username")),
                env == null ? null : stringValue(env.get("username")));
        String password = useServiceAccount ? null : firstNonBlank(stringValue(target.get("password")),
                env == null ? null : stringValue(env.get("password")));
        String realAgentId = firstNonBlank(agentId, stringValue(target.get("agentId")), stringValue(target.get("agent_id")));
        String wsCookie = firstNonBlank(stringValue(target.get("wsCookie")), stringValue(target.get("ws_cookie")),
                env == null ? null : stringValue(env.get("wsCookie")), env == null ? null : stringValue(env.get("ws_cookie")));
        if (isBlank(baseUrl)) return completedChatFailure("调用 SemiClaw 失败：未配置 baseUrl");
        if (isBlank(realAgentId)) return completedChatFailure("调用 SemiClaw 失败：未配置 agentId");
        boolean hasPersonalCredential = !isBlank(tenantId) && (!isBlank(username) || !isBlank(password));
        log.info("SemiClaw Agent评测分流: targetId={}, baseUrl={}, credentialMode={}, useServiceAccount={}, "
                        + "hasPersonalCredential={}, tenantId={}, username={}, agentId={}, hasWsCookie={}",
                targetId, maskValue(baseUrl), credentialMode, useServiceAccount, hasPersonalCredential,
                maskValue(tenantId), maskUsername(username), realAgentId, !isBlank(wsCookie));
        return semiclawAgentChatService.chatWithAgentAsync(baseUrl, tenantId, realAgentId,
                question, username, password, wsCookie);
    }

    private CompletableFuture<Map<String, Object>> chatWithMultimodalAgentByPlatform(String envUrl,
            String agentId, String agentType, String targetId, String question,
            List<String> questionImages, List<String> questionFiles, Map<String, Object> target,
            String expectedDocType, Map<String, AtomicInteger> semimindWindowCounters) {
        List<String> safeQuestionImages = normalizeStringList(questionImages);
        List<String> safeQuestionFiles = normalizeStringList(questionFiles);
        Map<String, Object> env = getTargetEnvironment(target);
        boolean hasMedia = !safeQuestionImages.isEmpty() || !safeQuestionFiles.isEmpty();
        if (!hasMedia && expectedDocType == null) {
            log.info("多模态Agent分流: targetId={} 未检测到图片/文件，回退原文本Agent链路", targetId);
            return chatWithAgentByPlatform(envUrl, agentId, agentType, targetId, question, target);
        }
        if (isSemiclawTarget(target, env)) {
            return chatWithSemiclawMultimodal(envUrl, agentId, targetId, question,
                    safeQuestionImages, safeQuestionFiles, target, env, expectedDocType);
        }
        if (isSemimindTarget(target, env)) {
            return chatWithSemimindMultimodalRetryOn401(envUrl, agentId, agentType, targetId, question,
                    safeQuestionImages, safeQuestionFiles, target, env, semimindWindowCounters);
        }
        log.info("多模态Agent分流: targetId={} platform 非 SemiClaw/SemiMind，回退原文本Agent链路，imageCount={}, fileCount={}",
                targetId, safeQuestionImages.size(), safeQuestionFiles.size());
        return chatWithAgentByPlatform(envUrl, agentId, agentType, targetId, question, target);
    }

    private void prepareSemiclawTarget(Map<String, Object> target, Map<String, Object> env) {
        String envUrl = firstNonBlank(stringValue(target.get("envUrl")),
                env == null ? null : stringValue(env.get("url")), env == null ? null : stringValue(env.get("baseUrl")));
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
            String username = firstNonBlank(stringValue(target.get("username")),
                    env == null ? null : stringValue(env.get("username")));
            String password = firstNonBlank(stringValue(target.get("password")),
                    env == null ? null : stringValue(env.get("password")));
            if (!isBlank(tenantId)) {
                target.put("tenantId", tenantId);
                target.put("tenant_id", tenantId);
            }
            if (!isBlank(username)) target.put("username", username);
            if (!isBlank(password)) target.put("password", password);
            target.remove("authorization");
            target.remove("apiUsername");
        }
    }

    private CompletableFuture<Map<String, Object>> chatWithSemiclawMultimodal(String envUrl,
            String agentId, String targetId, String question, List<String> questionImages,
            List<String> questionFiles, Map<String, Object> target, Map<String, Object> env,
            String expectedDocType) {
        prepareSemiclawTarget(target, env);
        String baseUrl = firstNonBlank(stringValue(target.get("envUrl")), envUrl,
                env == null ? null : stringValue(env.get("url")), env == null ? null : stringValue(env.get("baseUrl")));
        String credentialMode = resolveSemiclawCredentialMode(target, env);
        boolean useServiceAccount = CREDENTIAL_MODE_SERVICE_ACCOUNT.equals(credentialMode);
        String tenantId = firstNonBlank(stringValue(target.get("tenantId")), stringValue(target.get("tenant_id")),
                env == null ? null : stringValue(env.get("tenantId")), env == null ? null : stringValue(env.get("tenant_id")));
        String username = useServiceAccount ? null : firstNonBlank(stringValue(target.get("username")),
                env == null ? null : stringValue(env.get("username")));
        String password = useServiceAccount ? null : firstNonBlank(stringValue(target.get("password")),
                env == null ? null : stringValue(env.get("password")));
        String realAgentId = firstNonBlank(agentId, stringValue(target.get("agentId")), stringValue(target.get("agent_id")));
        String wsCookie = firstNonBlank(stringValue(target.get("wsCookie")), stringValue(target.get("ws_cookie")),
                env == null ? null : stringValue(env.get("wsCookie")), env == null ? null : stringValue(env.get("ws_cookie")));
        if (isBlank(baseUrl)) return completedChatFailure("调用 SemiClaw 多模态失败：未配置 baseUrl");
        if (isBlank(realAgentId)) return completedChatFailure("调用 SemiClaw 多模态失败：未配置 agentId");
        boolean hasPersonalCredential = !isBlank(tenantId) && !isBlank(username) && !isBlank(password);
        log.info("SemiClaw 多模态Agent评测分流: targetId={}, baseUrl={}, credentialMode={}, useServiceAccount={}, "
                        + "hasPersonalCredential={}, tenantId={}, username={}, agentId={}, imageCount={}, fileCount={}",
                targetId, maskValue(baseUrl), credentialMode, useServiceAccount, hasPersonalCredential,
                maskValue(tenantId), maskUsername(username), realAgentId, questionImages.size(), questionFiles.size());
        return semiclawMultimodalChatService.chatWithAgentMultimodalAsync(baseUrl, tenantId,
                realAgentId, question, questionImages, questionFiles, username, password, wsCookie, expectedDocType)
                .thenApply(result -> enrichMultimodalChatResult(result, questionImages, questionFiles, "semiclaw-multimodal"));
    }

    private CompletableFuture<Map<String, Object>> chatWithSemimindMultimodalRetryOn401(String envUrl,
            String agentId, String agentType, String targetId, String question, List<String> questionImages,
            List<String> questionFiles, Map<String, Object> target, Map<String, Object> env,
            Map<String, AtomicInteger> semimindWindowCounters) {
        String baseUrl = firstNonBlank(envUrl, stringValue(target.get("envUrl")),
                env == null ? null : stringValue(env.get("url")), env == null ? null : stringValue(env.get("baseUrl")));
        if (isBlank(baseUrl)) return completedChatFailure("调用 SemiMind 多模态失败：未配置 baseUrl");
        String authorization = stringValue(target.get("authorization"));
        String semimindUsername = resolveSemimindRequestUsername(target, env);
        String realAgentId = firstNonBlank(agentId, stringValue(target.get("agentId")), stringValue(target.get("agent_id")));
        if (isBlank(realAgentId)) return completedChatFailure("调用 SemiMind 多模态失败：未配置 agentId");
        String dialogId = resolveSemimindDialogId(target, env);
        AtomicInteger windowCounter = semimindWindowCounters.computeIfAbsent(targetId, key -> new AtomicInteger(0));
        int windowSize = criteriaStore.getSemimindWindowSize();
        boolean forceNewConversation = windowCounter.incrementAndGet() % Math.max(1, windowSize) == 0;
        if (forceNewConversation) dialogId = null;
        log.info("SemiMind 多模态Agent评测分流: targetId={}, baseUrl={}, agentId={}, dialogId={}, username={}, "
                        + "imageCount={}, fileCount={}, hasAuthorization={}, forceNewConversation={}",
                targetId, maskValue(baseUrl), realAgentId, maskValue(dialogId), maskUsername(semimindUsername),
                questionImages.size(), questionFiles.size(), !isBlank(authorization), forceNewConversation);

        final String firstDialogId = dialogId;
        final boolean forceConversation = forceNewConversation;
        CompletableFuture<Map<String, Object>> firstAttempt = semimindMultimodalChatService.chatWithAgentMultimodalAsync(
                baseUrl, realAgentId, agentType, firstDialogId, question, questionImages, questionFiles,
                authorization, semimindUsername, forceConversation);
        return firstAttempt.thenCompose(result -> {
            String answer = result == null ? null : stringValue(result.get("answer"));
            if (!looksLikeUnauthorized(answer)) {
                rememberSemimindDialogId(target, result);
                return CompletableFuture.completedFuture(
                        enrichMultimodalChatResult(result, questionImages, questionFiles, "semimind-multimodal"));
            }
            rememberSemimindDialogId(target, result);
            String userEmail = stringValue(target.get("userEmail"));
            if (isBlank(userEmail)) {
                log.warn("SemiMind 多模态 401 重试失败：无 userEmail，targetId={}", targetId);
                return CompletableFuture.completedFuture(enrichMultimodalChatResult(
                        result, questionImages, questionFiles, "semimind-multimodal-401-no-retry"));
            }
            try {
                userLoginService.invalidateToken(baseUrl, userEmail);
                Map<String, String> retryLoginResult = loginSemimindForTarget(baseUrl, target, targetId);
                String retryAuthorization = retryLoginResult == null ? null : retryLoginResult.get("authorization");
                String retryApiUsername = resolveSemimindRequestUsername(target, env);
                if (isBlank(retryApiUsername) && retryLoginResult != null) {
                    retryApiUsername = retryLoginResult.get("apiUsername");
                }
                final String retryUsername = retryApiUsername;
                log.info("SemiMind 多模态 401 重试：已重新登录，targetId={}, envUrl={}, username={}",
                        targetId, baseUrl, maskUsername(retryUsername));
                return semimindMultimodalChatService.chatWithAgentMultimodalAsync(
                        baseUrl, realAgentId, agentType, firstDialogId, question,
                        questionImages, questionFiles, retryAuthorization, retryUsername, forceConversation)
                        .thenApply(retryResult -> {
                            rememberSemimindDialogId(target, retryResult);
                            return enrichMultimodalChatResult(retryResult, questionImages,
                                    questionFiles, "semimind-multimodal");
                        });
            } catch (Exception e) {
                log.warn("SemiMind 多模态 401 重试失败: targetId={}, error={}", targetId, e.getMessage());
                return CompletableFuture.completedFuture(enrichMultimodalChatResult(
                        result, questionImages, questionFiles, "semimind-multimodal-401-retry-failed"));
            }
        });
    }

    private String resolveSemimindRequestUsername(Map<String, Object> target, Map<String, Object> env) {
        String username = firstNonBlank(target == null ? null : stringValue(target.get("semimindUsername")),
                target == null ? null : stringValue(target.get("semimind_username")),
                target == null ? null : stringValue(target.get("userName")),
                target == null ? null : stringValue(target.get("username")),
                target == null ? null : stringValue(target.get("apiUsername")),
                env == null ? null : stringValue(env.get("semimindUsername")),
                env == null ? null : stringValue(env.get("username")));
        if (!isBlank(username) && target != null) {
            target.put("semimindUsername", username.trim());
            target.put("semimind_username", username.trim());
        }
        return isBlank(username) ? null : username.trim();
    }

    private String resolveSemimindDialogId(Map<String, Object> target, Map<String, Object> env) {
        String dialogId = firstNonBlank(target == null ? null : stringValue(target.get("dialogId")),
                target == null ? null : stringValue(target.get("dialog_id")),
                target == null ? null : stringValue(target.get("dialogID")),
                target == null ? null : stringValue(target.get("dialogid")),
                target == null ? null : stringValue(target.get("semimindDialogId")),
                target == null ? null : stringValue(target.get("semimind_dialog_id")),
                target == null ? null : stringValue(target.get("conversationDialogId")),
                target == null ? null : stringValue(target.get("conversation_dialog_id")),
                target == null ? null : stringValue(target.get("dialog_id_candidate")),
                target == null ? null : stringValue(target.get("rawDialogId")),
                target == null ? null : stringValue(target.get("raw_dialog_id")),
                target == null ? null : resolveNestedSemimindDialogId(target),
                env == null ? null : stringValue(env.get("dialogId")),
                env == null ? null : stringValue(env.get("dialog_id")),
                env == null ? null : stringValue(env.get("dialogID")),
                env == null ? null : stringValue(env.get("dialogid")),
                env == null ? null : stringValue(env.get("semimindDialogId")),
                env == null ? null : stringValue(env.get("semimind_dialog_id")),
                env == null ? null : resolveNestedSemimindDialogId(env));
        String normalizedDialogId = normalizeSemimindDialogId(dialogId);
        if (!isBlank(normalizedDialogId) && target != null) {
            target.put("dialogId", normalizedDialogId);
            target.put("dialog_id", normalizedDialogId);
            target.put("semimindDialogId", normalizedDialogId);
            target.put("semimind_dialog_id", normalizedDialogId);
        }
        return normalizedDialogId;
    }

    private String resolveNestedSemimindDialogId(Map<String, Object> source) {
        if (source == null || source.isEmpty()) return null;
        String direct = firstNonBlank(stringValue(source.get("dialogId")), stringValue(source.get("dialog_id")),
                stringValue(source.get("dialogID")), stringValue(source.get("dialogid")),
                stringValue(source.get("semimindDialogId")), stringValue(source.get("semimind_dialog_id")),
                stringValue(source.get("conversationDialogId")), stringValue(source.get("conversation_dialog_id")),
                stringValue(source.get("dialog_id_candidate")),
                stringValue(source.get("rawDialogId")), stringValue(source.get("raw_dialog_id")));
        if (!isBlank(direct)) return direct;
        Object[] nestedObjects = {source.get("agent"), source.get("agentInfo"), source.get("agent_info"),
                source.get("raw"), source.get("rawAgent"), source.get("raw_agent"), source.get("metadata"),
                source.get("extra"), source.get("data")};
        for (Object nestedObject : nestedObjects) {
            Map<String, Object> nested = asMap(nestedObject);
            if (nested == null) continue;
            String nestedDialogId = firstNonBlank(stringValue(nested.get("dialogId")),
                    stringValue(nested.get("dialog_id")), stringValue(nested.get("dialogID")),
                    stringValue(nested.get("dialogid")), stringValue(nested.get("semimindDialogId")),
                    stringValue(nested.get("semimind_dialog_id")), stringValue(nested.get("conversationDialogId")),
                    stringValue(nested.get("conversation_dialog_id")), stringValue(nested.get("dialog_id_candidate")),
                    stringValue(nested.get("rawDialogId")),
                    stringValue(nested.get("raw_dialog_id")));
            if (!isBlank(nestedDialogId)) return nestedDialogId;
        }
        return null;
    }

    private String normalizeSemimindDialogId(String value) {
        if (isBlank(value)) return null;
        String noHyphen = value.trim().replace("-", "");
        return noHyphen.matches("(?i)^[0-9a-f]{32}$") ? noHyphen : null;
    }

    private void rememberSemimindDialogId(Map<String, Object> target, Map<String, Object> result) {
        if (target == null || result == null) return;
        String dialogId = normalizeSemimindDialogId(firstNonBlank(stringValue(result.get("dialogId")),
                stringValue(result.get("dialog_id")), stringValue(result.get("semimindDialogId")),
                stringValue(result.get("semimind_dialog_id")), stringValue(result.get("publishedDialogId")),
                stringValue(result.get("published_dialog_id")), stringValue(result.get("resolvedDialogId")),
                stringValue(result.get("resolved_dialog_id"))));
        if (isBlank(dialogId)) return;
        target.put("dialogId", dialogId);
        target.put("dialog_id", dialogId);
        target.put("semimindDialogId", dialogId);
        target.put("semimind_dialog_id", dialogId);
    }

    private Map<String, String> loginSemimindForTarget(String envUrl, Map<String, Object> target, String targetId) {
        String userEmail = stringValue(target.get("userEmail"));
        Map<String, Object> env = getTargetEnvironment(target);
        try {
            if (isBlank(userEmail) && env != null) {
                Map<String, Object> userInfo = envAgentService.getUserByNickname(envUrl,
                        stringValue(target.get("userName")));
                if (userInfo != null) userEmail = stringValue(userInfo.get("email"));
            }
            if (isBlank(userEmail)) {
                log.warn("SemiMind 多模态登录跳过：无 userEmail，targetId={}", targetId);
                return null;
            }
            String password = stringValue(target.get("userPassword"));
            if (isBlank(password)) password = userEmail;
            Map<String, String> loginResult = userLoginService.login(envUrl, userEmail, password);
            if (loginResult != null) {
                target.put("authorization", loginResult.get("authorization"));
                target.put("apiUsername", loginResult.get("apiUsername"));
                target.put("userEmail", userEmail);
                String semimindUsername = resolveSemimindRequestUsername(target, env);
                if (isBlank(semimindUsername)) semimindUsername = loginResult.get("apiUsername");
                if (!isBlank(semimindUsername)) {
                    target.put("semimindUsername", semimindUsername);
                    target.put("semimind_username", semimindUsername);
                }
            }
            return loginResult;
        } catch (Exception e) {
            log.warn("SemiMind 多模态登录失败: targetId={}, envUrl={}, error={}", targetId, envUrl, e.getMessage());
            return null;
        }
    }

    private boolean looksLikeUnauthorized(String answer) {
        if (isBlank(answer)) return false;
        String lower = answer.toLowerCase(Locale.ROOT);
        return lower.contains("401") || lower.contains("unauthorized") || lower.contains("未授权")
                || lower.contains("token");
    }

    private Map<String, Object> enrichMultimodalChatResult(Map<String, Object> result,
            List<String> questionImages, List<String> questionFiles, String defaultTransport) {
        Map<String, Object> safeResult = result == null ? new HashMap<>() : result;
        safeResult.putIfAbsent("multimodalTransport", defaultTransport);
        safeResult.putIfAbsent("questionImages", questionImages == null ? Collections.emptyList() : questionImages);
        safeResult.putIfAbsent("question_images", safeResult.get("questionImages"));
        safeResult.putIfAbsent("images", safeResult.get("questionImages"));
        safeResult.putIfAbsent("questionFiles", questionFiles == null ? Collections.emptyList() : questionFiles);
        safeResult.putIfAbsent("question_files", safeResult.get("questionFiles"));
        safeResult.putIfAbsent("files", safeResult.get("questionFiles"));
        putMultimodalFlags(safeResult);
        return safeResult;
    }

    private void copyOptionalAgentResultFields(Map<String, Object> source, Map<String, Object> target) {
        if (source == null || target == null) {
            return;
        }
        copyOptionalAgentResultField(source, target, "uploadFiles");
        copyOptionalAgentResultField(source, target, "uploadFileCount");
        copyOptionalAgentResultField(source, target, "uploadError");
        copyOptionalAgentResultField(source, target, "files");
        copyOptionalAgentResultField(source, target, "file_ids");
        copyOptionalAgentResultField(source, target, "fileUrls");
        copyOptionalAgentResultField(source, target, "file_urls");
        copyOptionalAgentResultField(source, target, "chatId");
        copyOptionalAgentResultField(source, target, "chat_id");
        copyOptionalAgentResultField(source, target, "dialogId");
        copyOptionalAgentResultField(source, target, "dialog_id");
        copyOptionalAgentResultField(source, target, "semimindDialogId");
        copyOptionalAgentResultField(source, target, "semimind_dialog_id");
        copyOptionalAgentResultField(source, target, "publishedDialogId");
        copyOptionalAgentResultField(source, target, "published_dialog_id");
        copyOptionalAgentResultField(source, target, "resolvedDialogId");
        copyOptionalAgentResultField(source, target, "resolved_dialog_id");
        copyOptionalAgentResultField(source, target, "generatedFileUrl");
        copyOptionalAgentResultField(source, target, "generatedFileName");
    }

    private void copyOptionalAgentResultField(Map<String, Object> source, Map<String, Object> target,
            String fieldName) {
        if (source.containsKey(fieldName)) {
            target.put(fieldName, source.get(fieldName));
        }
    }

}
