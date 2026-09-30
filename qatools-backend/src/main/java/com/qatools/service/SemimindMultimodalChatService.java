package com.qatools.service;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PreDestroy;

/**
 * SemiMind 多模态图文问答服务。
 *
 * 该服务只负责 SemiMind 图文/文件问答，不影响原 SemiMind 纯文本 AgentChatService，
 * 也不影响已跑通的 SemiClaw 图文链路。
 *
 * 当前根据 SemiMind 官方页面抓包实现：
 * 1. 智能体：POST /v1/bot/publish，将 agentId 转成真实 dialog_id。
 * 2. 工作流：POST /v1/canvas/publish，将 canvas/workflowId 转成真实 dialog_id。
 * 3. 如果 publish 返回 401 或失败，降级 GET /v1/dialog/list 按 agent_id / agent_type 查找已有 dialog_id。
 * 4. POST /v1/conversation/file_upload 上传图片/文件：
 *    conversation_id 优先使用 /v1/conversation/list 返回的既有会话；
 *    如果列表为空，必须先调用 /v1/conversation/set（is_new=true）在服务端真正创建新会话
 *    （本地生成的临时 UUID 未经创建会被 file_upload 拒绝，报 retcode=102 data not found）。
 * 5. POST /v1/conversation/chat 发送问题并解析 SSE。
 * 6. chat 成功后 best-effort 再次调用 /v1/conversation/set 更新会话标题；
 *    set 失败只记录日志，不影响本题评测结果。
 */
@Service
public class SemimindMultimodalChatService {

    private static final Logger log = LoggerFactory.getLogger(SemimindMultimodalChatService.class);

    private static final long CONNECT_TIMEOUT_SECONDS = 20;
    private static final long UPLOAD_TIMEOUT_SECONDS = 60;
    private static final long CHAT_TIMEOUT_SECONDS = 240;
    private static final long UUID_EPOCH_OFFSET_100NS = 0x01B21DD213814000L;
    private static final AtomicLong LAST_UUID_TIMESTAMP_100NS = new AtomicLong(0L);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService chatExecutor = Executors.newFixedThreadPool(4);
    private final HttpClient httpClient;

    /**
     * QATools 后端对外可访问地址。
     *
     * 当 questionImages / questionFiles 是 /api/... 或 images/... 这类相对路径时，
     * 用该地址拼成完整 URL 后下载二进制，再上传到 SemiMind。
     *
     * 建议配置：
     * qatools.public-base-url=http://127.0.0.1:8081
     */
    @Value("${qatools.public-base-url:}")
    private String qatoolsPublicBaseUrl;

    @Value("${server.port:8081}")
    private String serverPort;

    /**
     * SemiMind 官方页面中的 dialog_id。
     *
     * 注意：dialog_id 通常不是本系统里保存的 agentId。
     * 如果 agentId 不是 SemiMind 的真实 dialog_id，可以在配置中显式指定：
     * semimind.multimodal.dialog-id=17fecc449c111f1884f5079307c73e
     */
    @Value("${semimind.multimodal.dialog-id:}")
    private String configuredDialogId;

    public SemimindMultimodalChatService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * 异步调用 SemiMind 多模态问答。
     *
     * 推荐由 MultimodalEvaluationService 在 platform=semimind 时调用。
     *
     * @param baseUrl SemiMind 环境地址，例如 http://demo-prod.example.invalid:8111 或 demo-prod.example.invalid:8111
     * @param agentId 预留参数，当前 SemiMind 抓包接口未直接使用 agentId，但保留便于后续兼容
     * @param question 用户问题
     * @param questionImages 图片路径、URL、data URI 或 base64
     * @param questionFiles 文件路径、URL、data URI 或 base64
     * @param authorization SemiMind 登录后的 Authorization；有值时原样放入请求头
     * @param apiUsername 预留参数，当前接口未直接使用
     */
    public CompletableFuture<Map<String, Object>> chatWithAgentMultimodalAsync(String baseUrl,
                                                                                String agentId,
                                                                                String question,
                                                                                List<String> questionImages,
                                                                                List<String> questionFiles,
                                                                                String authorization,
                                                                                String apiUsername) {
        return chatWithAgentMultimodalAsync(
                baseUrl,
                agentId,
                null,
                null,
                question,
                questionImages,
                questionFiles,
                authorization,
                apiUsername,
                false
        );
    }

    /**
     * 兼容旧调用方式：agentId 保存 QATools / SemiMind 侧的 Agent 或 Workflow 主键，dialogId 单独传 SemiMind 真实 dialog_id。
     */
    public CompletableFuture<Map<String, Object>> chatWithAgentMultimodalAsync(String baseUrl,
                                                                                String agentId,
                                                                                String dialogId,
                                                                                String question,
                                                                                List<String> questionImages,
                                                                                List<String> questionFiles,
                                                                                String authorization,
                                                                                String apiUsername) {
        return chatWithAgentMultimodalAsync(
                baseUrl,
                agentId,
                null,
                dialogId,
                question,
                questionImages,
                questionFiles,
                authorization,
                apiUsername,
                false
        );
    }

    /**
     * 推荐调用方式：显式传入 agentType，便于区分 SemiMind 智能体和工作流；同时支持 forceNewConversation
     * 用于会话轮换（每题累计达到阈值后跳过复用已有会话，强制创建新对话窗口）。
     *
     * 抓包确认：
     * - agent / bot 使用 POST /v1/bot/publish 获取真实 dialog_id。
     * - workflow / canvas 使用 POST /v1/canvas/publish 获取真实 dialog_id。
     */
    public CompletableFuture<Map<String, Object>> chatWithAgentMultimodalAsync(String baseUrl,
                                                                                String agentId,
                                                                                String agentType,
                                                                                String dialogId,
                                                                                String question,
                                                                                List<String> questionImages,
                                                                                List<String> questionFiles,
                                                                                String authorization,
                                                                                String apiUsername,
                                                                                boolean forceNewConversation) {
        long start = System.currentTimeMillis();

        List<String> safeQuestionImages = normalizeStringList(questionImages);
        List<String> safeQuestionFiles = normalizeStringList(questionFiles);

        return CompletableFuture
                .supplyAsync(() -> doChatMultimodal(
                        baseUrl,
                        agentId,
                        agentType,
                        dialogId,
                        question,
                        safeQuestionImages,
                        safeQuestionFiles,
                        authorization,
                        apiUsername,
                        start,
                        forceNewConversation
                ), chatExecutor)
                .exceptionally(e -> buildFailureResult(e, start));
    }

    /**
     * 兼容不传 apiUsername 的调用方式。
     */
    public CompletableFuture<Map<String, Object>> chatWithAgentMultimodalAsync(String baseUrl,
                                                                                String agentId,
                                                                                String question,
                                                                                List<String> questionImages,
                                                                                List<String> questionFiles,
                                                                                String authorization) {
        return chatWithAgentMultimodalAsync(
                baseUrl,
                agentId,
                question,
                questionImages,
                questionFiles,
                authorization,
                null
        );
    }

    private Map<String, Object> doChatMultimodal(String baseUrl,
                                                  String agentId,
                                                  String agentType,
                                                  String dialogId,
                                                  String question,
                                                  List<String> questionImages,
                                                  List<String> questionFiles,
                                                  String authorization,
                                                  String apiUsername,
                                                  long startTime,
                                                  boolean forceNewConversation) {
        validateRequired("semimind baseUrl", baseUrl);

        List<String> safeQuestionImages = normalizeStringList(questionImages);
        List<String> safeQuestionFiles = normalizeStringList(questionFiles);
        String finalQuestion = normalizeMultimodalQuestion(question, safeQuestionImages, safeQuestionFiles);
        String normalizedBaseUrl = normalizeBaseUrl(baseUrl);

        if (safeQuestionImages.isEmpty() && safeQuestionFiles.isEmpty()) {
            throw new IllegalArgumentException("SemiMind 多模态调用缺少图片或文件");
        }

        /*
         * SemiMind 官方页面抓包确认：
         * 1. 智能体 agent/bot：      POST /v1/bot/publish -> dialog_id。
         * 2. 工作流 workflow/canvas：POST /v1/canvas/publish -> dialog_id。
         * 3. /v1/conversation/file_upload 的 chat_id 必须是 conversation/list 返回的已存在 conversation_id，
         *    不能直接使用后端随机 UUID。
         *
         * 因此主链路固定为：publish/dialog-list -> conversation/list 或前端风格 chatId -> file_upload -> chat -> best-effort set。
         */
        AtomicReference<String> publishFailureReasonRef = new AtomicReference<>();
        List<String> publishedDialogIds = publishAndResolveDialogIdsBestEffort(
                normalizedBaseUrl,
                agentId,
                agentType,
                authorization,
                apiUsername,
                publishFailureReasonRef
        );

        List<String> dialogIdCandidates = new ArrayList<>();
        for (String candidate : publishedDialogIds) {
            addDialogIdCandidate(dialogIdCandidates, candidate);
        }
        for (String candidate : buildDialogIdCandidates(dialogId)) {
            addDialogIdCandidate(dialogIdCandidates, candidate);
        }

        // publish/dialog-list 阶段的原始失败原因（如 401 Unauthorized）必须在这里就抛出，而不是留到
        // resolveConversationForUpload() 再包装成一句通用的“缺少真实 dialog_id”——那句话不含 401/
        // unauthorized 关键字，会导致 MultimodalEvaluationService.looksLikeUnauthorized() 判定为
        // “非鉴权失败”，从而永远不会触发清缓存重新登录重试，200 道题会用同一个失效 token 反复失败到底。
        if (dialogIdCandidates.isEmpty()) {
            String reason = publishFailureReasonRef.get();
            throw new RuntimeException(isBlank(reason)
                    ? "SemiMind 缺少真实 dialog_id，无法获取可用于 file_upload 的 conversation_id"
                    : "SemiMind 缺少真实 dialog_id，无法获取可用于 file_upload 的 conversation_id: " + reason);
        }

        String publishedDialogId = publishedDialogIds.isEmpty() ? null : publishedDialogIds.get(0);
        String firstDialogId = dialogIdCandidates.isEmpty() ? "" : dialogIdCandidates.get(0);

        log.info("SemiMind 多模态评测调用开始，baseUrl={}, agentId={}, agentType={}, targetDialogId={}, publishedDialogId={}, dialogIdCandidate={}, dialogCandidateCount={}, imageCount={}, fileCount={}, hasAuthorization={}, apiUsername={}",
                sanitizeUrl(normalizedBaseUrl),
                agentId,
                normalizeSemimindAgentType(agentType),
                maskValue(dialogId),
                maskValue(publishedDialogId),
                maskValue(firstDialogId),
                dialogIdCandidates.size(),
                safeQuestionImages.size(),
                safeQuestionFiles.size(),
                !isBlank(authorization),
                maskUsername(apiUsername));

        ConversationContext conversationContext = resolveConversationForUpload(
                normalizedBaseUrl,
                dialogIdCandidates,
                finalQuestion,
                authorization,
                apiUsername,
                forceNewConversation
        );
        String chatId = conversationContext.conversationId;
        String usedDialogId = firstNonBlank(conversationContext.dialogId, firstDialogId, normalizeSemimindId(dialogId));

        if (isBlank(chatId)) {
            throw new RuntimeException("SemiMind 未获得可用于 file_upload 的 conversation_id");
        }

        List<MediaResource> resources = collectMediaResources(safeQuestionImages, safeQuestionFiles);
        if (resources.isEmpty()) {
            throw new RuntimeException("没有可上传到 SemiMind 的图片或文件资源");
        }

        List<UploadedFile> uploadedFiles = new ArrayList<>();
        for (MediaResource resource : resources) {
            UploadedFile uploadedFile = uploadFile(
                    normalizedBaseUrl,
                    chatId,
                    resource,
                    authorization,
                    apiUsername
            );
            uploadedFiles.add(uploadedFile);
        }

        List<String> fileIds = new ArrayList<>();
        List<String> fileUrls = new ArrayList<>();
        for (UploadedFile uploadedFile : uploadedFiles) {
            if (!isBlank(uploadedFile.fileId)) {
                fileIds.add(uploadedFile.fileId);
            }
            if (!isBlank(uploadedFile.fileUrl)) {
                fileUrls.add(uploadedFile.fileUrl);
            }
        }

        if (fileIds.isEmpty()) {
            throw new RuntimeException("SemiMind 文件上传成功但未获得 file_id");
        }

        String answer = chat(
                normalizedBaseUrl,
                chatId,
                fileIds,
                finalQuestion,
                authorization,
                apiUsername
        );

        ConversationContext savedConversationContext = saveConversationAfterChatBestEffort(
                normalizedBaseUrl,
                dialogIdCandidates,
                chatId,
                finalQuestion,
                authorization,
                apiUsername
        );
        usedDialogId = firstNonBlank(
                savedConversationContext == null ? null : savedConversationContext.dialogId,
                usedDialogId
        );

        long responseTime = System.currentTimeMillis() - startTime;

        Map<String, Object> result = new HashMap<>();
        result.put("answer", answer);
        result.put("responseTime", responseTime);
        result.put("multimodalTransport", "semimind-file-upload");
        result.put("chatId", chatId);
        result.put("chat_id", chatId);
        result.put("dialogId", usedDialogId);
        result.put("dialog_id", usedDialogId);
        result.put("agentType", normalizeSemimindAgentType(agentType));
        result.put("agent_type", normalizeSemimindAgentType(agentType));
        result.put("publishedDialogId", publishedDialogId);
        result.put("published_dialog_id", publishedDialogId);
        result.put("fileIds", fileIds);
        result.put("file_ids", fileIds);
        result.put("fileUrls", fileUrls);
        result.put("file_urls", fileUrls);
        result.put("questionImages", safeQuestionImages);
        result.put("questionFiles", safeQuestionFiles);
        result.put("uploadedFileCount", uploadedFiles.size());

        log.info("SemiMind 多模态评测调用完成，agentId={}, chatId={}, dialogId={}, responseTime={}ms, uploadedFileCount={}, answerLength={}",
                agentId,
                maskValue(chatId),
                maskValue(usedDialogId),
                responseTime,
                uploadedFiles.size(),
                answer == null ? 0 : answer.length());

        return result;
    }

    /**
     * 获取 file_upload 可用的 conversation_id。
     *
     * SemiMind 的 file_upload 会严格校验 conversation_id 是否已在服务端真实存在（本地生成的 UUID 即使格式
     * 完全合法，未经 /v1/conversation/set 创建也会被 file_upload 拒绝，报 retcode=102 data not found）。
     * 因此，优先使用 /v1/conversation/list 返回的既有会话；如果列表为空，必须先调用
     * /v1/conversation/set（is_new=true）在服务端真正创建一个新会话，再使用其返回的 conversation_id
     * 继续 file_upload，不能仅凭本地生成的临时 chat_id 直接上传。
     *
     * forceNewConversation 为 true 时（会话轮换达到阈值），跳过“复用已有会话”分支，
     * 直接走创建新会话逻辑，避免单一对话窗口消息累计过多导致上下文超限。
     */
    private ConversationContext resolveConversationForUpload(String normalizedBaseUrl,
                                                               List<String> dialogIdCandidates,
                                                               String question,
                                                               String authorization,
                                                               String apiUsername,
                                                               boolean forceNewConversation) {
        List<String> candidates = dialogIdCandidates == null ? Collections.emptyList() : dialogIdCandidates;
        if (candidates.isEmpty()) {
            throw new RuntimeException("SemiMind 缺少真实 dialog_id，无法获取可用于 file_upload 的 conversation_id");
        }

        String firstDialogId = null;
        RuntimeException lastCreateError = null;
        for (String dialogId : candidates) {
            if (isBlank(dialogId)) {
                continue;
            }
            if (isBlank(firstDialogId)) {
                firstDialogId = dialogId;
            }

            if (!forceNewConversation) {
                List<String> conversationIds = loadConversationIdsFromList(
                        normalizedBaseUrl,
                        dialogId,
                        authorization,
                        apiUsername
                );

                if (!conversationIds.isEmpty()) {
                    String conversationId = conversationIds.get(0);
                    log.info("SemiMind 使用 conversation/list 返回的已有 conversation_id，dialogId={}, chatId={}, conversationCount={}",
                            maskValue(dialogId),
                            maskValue(conversationId),
                            conversationIds.size());
                    return new ConversationContext(conversationId, dialogId);
                }

                log.info("SemiMind conversation/list 为空，尝试调用 conversation/set 创建新会话，dialogId={}",
                        maskValue(dialogId));
            } else {
                log.info("SemiMind 会话轮换达到阈值，强制调用 conversation/set 创建新会话，dialogId={}",
                        maskValue(dialogId));
            }

            try {
                ConversationContext created = createConversationOnce(
                        normalizedBaseUrl,
                        dialogId,
                        null,
                        question,
                        authorization,
                        apiUsername,
                        true
                );
                log.info("SemiMind conversation/set 创建新会话成功（服务端分配ID）：dialogId={}, chatId={}",
                        maskValue(dialogId),
                        maskValue(created.conversationId));
                return created;
            } catch (RuntimeException byServerIdError) {
                log.warn("SemiMind conversation/set 创建新会话失败（服务端分配ID方案），尝试回退本地生成ID：dialogId={}, reason={}",
                        maskValue(dialogId),
                        byServerIdError.getMessage());
                try {
                    ConversationContext created = createConversationOnce(
                            normalizedBaseUrl,
                            dialogId,
                            generateConversationId(),
                            question,
                            authorization,
                            apiUsername,
                            true
                    );
                    log.info("SemiMind conversation/set 创建新会话成功（本地生成ID回退方案）：dialogId={}, chatId={}",
                            maskValue(dialogId),
                            maskValue(created.conversationId));
                    return created;
                } catch (RuntimeException byLocalIdError) {
                    lastCreateError = byLocalIdError;
                    log.warn("SemiMind conversation/set 创建新会话失败（两种方案均失败）：dialogId={}, reason={}",
                            maskValue(dialogId),
                            byLocalIdError.getMessage());
                }
            }
        }

        if (lastCreateError != null) {
            throw new RuntimeException("SemiMind 未能创建可用于 file_upload 的会话: " + lastCreateError.getMessage(), lastCreateError);
        }

        String generatedChatId = generateConversationId();
        log.info("SemiMind 未找到已有 conversation_id，使用前端兼容 chat_id 继续 file_upload：dialogId={}, chatId={}",
                maskValue(firstDialogId),
                maskValue(generatedChatId));
        return new ConversationContext(generatedChatId, firstDialogId);
    }

    /**
     * chat 成功后保存/更新会话标题。该动作不影响本题评测结果。
     */
    private ConversationContext saveConversationAfterChatBestEffort(String normalizedBaseUrl,
                                                                     List<String> dialogIdCandidates,
                                                                     String chatId,
                                                                     String question,
                                                                     String authorization,
                                                                     String apiUsername) {
        List<String> candidates = dialogIdCandidates == null ? Collections.emptyList() : dialogIdCandidates;
        if (candidates.isEmpty() || isBlank(chatId)) {
            return new ConversationContext(chatId, null);
        }

        RuntimeException lastError = null;
        for (String dialogId : candidates) {
            if (isBlank(dialogId)) {
                continue;
            }

            try {
                ConversationContext context = createConversationOnce(
                        normalizedBaseUrl,
                        dialogId,
                        chatId,
                        question,
                        authorization,
                        apiUsername,
                        false
                );
                log.info("SemiMind conversation set 后置保存完成：dialogId={}, chatId={}",
                        maskValue(dialogId),
                        maskValue(chatId));
                return context;
            } catch (RuntimeException e) {
                lastError = e;
                log.warn("SemiMind conversation set 后置保存失败但不影响评测：dialogId={}, chatId={}, reason={}",
                        maskValue(dialogId),
                        maskValue(chatId),
                        e.getMessage());
            }
        }

        if (lastError != null) {
            log.warn("SemiMind conversation set 后置保存全部失败，已忽略：chatId={}, reason={}",
                    maskValue(chatId),
                    lastError.getMessage());
        }
        return new ConversationContext(chatId, candidates.isEmpty() ? null : candidates.get(0));
    }

    /**
     * 根据 SemiMind 对象类型发布并解析真实 dialog_id。
     *
     * 抓包结果：
     * - 智能体：POST /v1/bot/publish，body: {"id":"1243","publish_type":"personal"}
     * - 工作流：POST /v1/canvas/publish，body: {"id":"1c4a...","publish_type":"personal"}
     *
     * 注意：32 位 agentId 不能直接当 dialog_id。工作流/canvas 自身也是 32 位 ID，必须经 canvas publish。
     */
    private List<String> publishAndResolveDialogIdsBestEffort(String normalizedBaseUrl,
                                                               String agentId,
                                                               String agentType,
                                                               String authorization,
                                                               String apiUsername,
                                                               AtomicReference<String> failureReasonRef) {
        List<String> result = new ArrayList<>();
        if (isBlank(agentId)) {
            log.info("SemiMind publish 跳过：agentId 为空");
            return result;
        }

        List<PublishEndpoint> endpoints = buildPublishEndpoints(agentId, agentType);
        RuntimeException lastError = null;
        for (PublishEndpoint endpoint : endpoints) {
            try {
                String dialogId = publishAndResolveDialogId(
                        normalizedBaseUrl,
                        endpoint,
                        agentId,
                        authorization,
                        apiUsername
                );
                addDialogIdCandidate(result, dialogId);
                if (!isBlank(dialogId)) {
                    log.info("SemiMind {} publish 完成：agentId={}, dialogId={}",
                            endpoint.label,
                            agentId,
                            maskValue(dialogId));
                    return result;
                }
            } catch (Exception e) {
                lastError = new RuntimeException(e.getMessage(), e);
                log.warn("SemiMind {} publish 失败：agentId={}, agentType={}, reason={}",
                        endpoint.label,
                        agentId,
                        normalizeSemimindAgentType(agentType),
                        e.getMessage());
            }
        }

        List<String> dialogListMatches = loadDialogIdsFromDialogList(
                normalizedBaseUrl,
                agentId,
                agentType,
                authorization,
                apiUsername
        );
        for (String dialogId : dialogListMatches) {
            addDialogIdCandidate(result, dialogId);
        }
        if (!result.isEmpty()) {
            log.info("SemiMind publish 失败后通过 dialog/list 找到 dialog_id：agentId={}, dialogIdCount={}, firstDialogId={}",
                    agentId,
                    result.size(),
                    maskValue(result.get(0)));
            return result;
        }

        if (lastError != null) {
            log.warn("SemiMind publish 与 dialog/list 均未解析到 dialog_id，将尝试使用显式 dialogId / 配置兜底：agentId={}, reason={}",
                    agentId,
                    lastError.getMessage());
            // 把 publish 阶段的原始失败原因（含 retcode/retmsg，如 401 Unauthorized）透传给上层，
            // 使调用方能够据此正确识别鉴权失败并触发重新登录重试，而不是被后续统一的“缺少 dialog_id”文案掩盖。
            failureReasonRef.set(lastError.getMessage());
        }
        return result;
    }

    private List<PublishEndpoint> buildPublishEndpoints(String agentId, String agentType) {
        List<PublishEndpoint> endpoints = new ArrayList<>();
        String normalizedType = normalizeSemimindAgentType(agentType);

        if (isWorkflowType(normalizedType)) {
            endpoints.add(new PublishEndpoint("canvas", "/v1/canvas/publish"));
            return endpoints;
        }

        if (isAgentType(normalizedType)) {
            endpoints.add(new PublishEndpoint("bot", "/v1/bot/publish"));
            return endpoints;
        }

        /*
         * 未传 agentType 时做安全推断：
         * - 数字 ID 通常是 ai_bot.id，先走 bot publish。
         * - 32 位 ID 可能是 workflow/canvas id，不能当 dialog_id，先走 canvas publish。
         * - 失败后再尝试另一个 publish 端点，避免之前推断错误导致数据缺少 agentType 时不可用。
         */
        if (looksLikeSemimindDialogId(agentId) && !agentId.trim().matches("^\\d+$")) {
            endpoints.add(new PublishEndpoint("canvas", "/v1/canvas/publish"));
            endpoints.add(new PublishEndpoint("bot", "/v1/bot/publish"));
        } else {
            endpoints.add(new PublishEndpoint("bot", "/v1/bot/publish"));
            endpoints.add(new PublishEndpoint("canvas", "/v1/canvas/publish"));
        }
        return endpoints;
    }

    @SuppressWarnings("unchecked")
    private String publishAndResolveDialogId(String normalizedBaseUrl,
                                             PublishEndpoint endpoint,
                                             String agentId,
                                             String authorization,
                                             String apiUsername) throws Exception {
        URI uri = URI.create(normalizedBaseUrl + endpoint.path);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", agentId);
        payload.put("publish_type", "personal");

        String body = objectMapper.writeValueAsString(payload);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(UPLOAD_TIMEOUT_SECONDS))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

        applySemimindHeaders(requestBuilder, authorization, apiUsername);

        log.info("调用 SemiMind {} publish 接口：url={}, agentId={}, username={}",
                endpoint.label,
                sanitizeUrl(uri.toString()),
                agentId,
                maskUsername(apiUsername));

        HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        String responseBody = response.body();

        log.info("SemiMind {} publish 响应：status={}, bodyLength={}",
                endpoint.label,
                status,
                responseBody == null ? 0 : responseBody.length());
        log.debug("SemiMind {} publish 响应体摘要：{}", endpoint.label, summarizeBody(responseBody));

        if (status < 200 || status >= 300) {
            throw new RuntimeException("SemiMind " + endpoint.label + " publish 失败：status="
                    + status + ", bodySummary=" + summarizeBody(responseBody));
        }

        if (isBlank(responseBody)) {
            throw new RuntimeException("SemiMind " + endpoint.label + " publish 响应体为空");
        }

        Map<String, Object> parsed = objectMapper.readValue(responseBody, Map.class);
        int retcode = toInt(parsed.get("retcode"), 0);
        if (retcode != 0) {
            throw new RuntimeException("SemiMind " + endpoint.label + " publish 返回失败：retcode="
                    + retcode + ", retmsg=" + stringValue(parsed.get("retmsg")));
        }

        String dialogId = extractDialogIdFromPublishData(parsed.get("data"));
        if (isBlank(dialogId)) {
            throw new RuntimeException("SemiMind " + endpoint.label + " publish 成功但未返回 dialog_id");
        }

        return dialogId;
    }

    private String extractDialogIdFromPublishData(Object dataObj) {
        if (dataObj == null) {
            return null;
        }

        if (dataObj instanceof String || dataObj instanceof Number || dataObj instanceof Boolean) {
            String value = normalizeSemimindId(dataObj.toString());
            return isBlank(value) ? null : value;
        }

        if (dataObj instanceof Map<?, ?>) {
            Map<?, ?> data = (Map<?, ?>) dataObj;
            return firstNonBlank(
                    normalizeSemimindId(stringValue(data.get("dialog_id"))),
                    normalizeSemimindId(stringValue(data.get("dialogId"))),
                    normalizeSemimindId(stringValue(data.get("id"))),
                    normalizeSemimindId(stringValue(data.get("conversation_id"))),
                    normalizeSemimindId(stringValue(data.get("conversationId")))
            );
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    private List<String> loadDialogIdsFromDialogList(String normalizedBaseUrl,
                                                     String agentId,
                                                     String agentType,
                                                     String authorization,
                                                     String apiUsername) {
        List<String> result = new ArrayList<>();
        if (isBlank(agentId)) {
            return result;
        }

        URI uri = URI.create(normalizedBaseUrl + "/v1/dialog/list");
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(UPLOAD_TIMEOUT_SECONDS))
                    .header("Accept", "application/json")
                    .GET();

            applySemimindHeaders(requestBuilder, authorization, apiUsername);

            log.info("调用 SemiMind dialog/list 接口兜底解析 dialog_id: url={}, agentId={}, agentType={}, username={}",
                    sanitizeUrl(uri.toString()),
                    agentId,
                    normalizeSemimindAgentType(agentType),
                    maskUsername(apiUsername));

            HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            String responseBody = response.body();

            log.info("SemiMind dialog/list 响应: status={}, bodyLength={}",
                    status,
                    responseBody == null ? 0 : responseBody.length());
            log.debug("SemiMind dialog/list 响应体摘要: {}", summarizeBody(responseBody));

            if (status < 200 || status >= 300 || isBlank(responseBody)) {
                return result;
            }

            Map<String, Object> parsed = objectMapper.readValue(responseBody, Map.class);
            int retcode = toInt(parsed.get("retcode"), 0);
            if (retcode != 0) {
                log.warn("SemiMind dialog/list 返回失败: retcode={}, retmsg={}",
                        retcode,
                        stringValue(parsed.get("retmsg")));
                return result;
            }

            Object dataObj = parsed.get("data");
            collectDialogIdsFromDialogListData(result, dataObj, agentId, agentType);

            log.info("SemiMind dialog/list 兜底解析完成: agentId={}, matchedDialogCount={}",
                    agentId,
                    result.size());
        } catch (Exception e) {
            log.warn("SemiMind dialog/list 兜底解析失败: agentId={}, reason={}",
                    agentId,
                    e.getMessage());
        }
        return result;
    }

    private void collectDialogIdsFromDialogListData(List<String> result,
                                                     Object dataObj,
                                                     String agentId,
                                                     String agentType) {
        if (dataObj instanceof Collection<?>) {
            for (Object item : (Collection<?>) dataObj) {
                if (item instanceof Map<?, ?>) {
                    addMatchedDialogIdFromDialogMap(result, (Map<?, ?>) item, agentId, agentType);
                }
            }
            return;
        }

        if (dataObj instanceof Map<?, ?>) {
            Map<?, ?> data = (Map<?, ?>) dataObj;
            addMatchedDialogIdFromDialogMap(result, data, agentId, agentType);
            Object listObj = firstPresent(data, "list", "items", "records", "rows");
            if (listObj instanceof Collection<?>) {
                for (Object item : (Collection<?>) listObj) {
                    if (item instanceof Map<?, ?>) {
                        addMatchedDialogIdFromDialogMap(result, (Map<?, ?>) item, agentId, agentType);
                    }
                }
            }
        }
    }

    private void addMatchedDialogIdFromDialogMap(List<String> result,
                                                  Map<?, ?> dialog,
                                                  String agentId,
                                                  String agentType) {
        if (dialog == null || isBlank(agentId)) {
            return;
        }

        String itemAgentId = firstNonBlank(
                stringValue(dialog.get("agent_id")),
                stringValue(dialog.get("agentId")),
                stringValue(dialog.get("bot_id")),
                stringValue(dialog.get("botId")),
                stringValue(dialog.get("canvas_id")),
                stringValue(dialog.get("canvasId")),
                stringValue(dialog.get("workflow_id")),
                stringValue(dialog.get("workflowId"))
        );
        if (!sameSemimindId(agentId, itemAgentId)) {
            return;
        }

        String expectedType = normalizeSemimindAgentType(agentType);
        String itemType = normalizeSemimindAgentType(firstNonBlank(
                stringValue(dialog.get("agent_type")),
                stringValue(dialog.get("agentType")),
                stringValue(dialog.get("type"))
        ));
        if (!"unknown".equals(expectedType)
                && !"unknown".equals(itemType)
                && !expectedType.equals(itemType)) {
            return;
        }

        addDialogIdCandidate(result, firstNonBlank(
                stringValue(dialog.get("dialog_id")),
                stringValue(dialog.get("dialogId")),
                stringValue(dialog.get("id"))
        ));
    }

    private boolean sameSemimindIdOrText(String left, String right) {
        if (isBlank(left) || isBlank(right)) {
            return false;
        }

        String l = left.trim();
        String r = right.trim();
        if (l.equals(r)) {
            return true;
        }

        String nl = normalizeSemimindId(l);
        String nr = normalizeSemimindId(r);
        return !isBlank(nl) && nl.equals(nr);
    }

    @SuppressWarnings("unchecked")
    private List<String> loadConversationIdsFromList(String normalizedBaseUrl,
                                                      String dialogId,
                                                      String authorization,
                                                      String apiUsername) {
        List<String> result = new ArrayList<>();
        if (isBlank(dialogId)) {
            return result;
        }

        String encodedDialogId = URLEncoder.encode(dialogId.trim(), StandardCharsets.UTF_8);
        URI uri = URI.create(normalizedBaseUrl + "/v1/conversation/list?dialog_id=" + encodedDialogId);
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(UPLOAD_TIMEOUT_SECONDS))
                    .header("Accept", "application/json")
                    .GET();

            applySemimindHeaders(requestBuilder, authorization, apiUsername);

            log.info("调用 SemiMind conversation list 接口: url={}, dialogId={}, username={}",
                    sanitizeUrl(uri.toString()),
                    maskValue(dialogId),
                    maskUsername(apiUsername));

            HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            String responseBody = response.body();

            log.info("SemiMind conversation list 响应: status={}, bodyLength={}",
                    status,
                    responseBody == null ? 0 : responseBody.length());
            log.debug("SemiMind conversation list 响应体摘要: {}", summarizeBody(responseBody));

            if (status < 200 || status >= 300) {
                log.warn("SemiMind conversation list 失败: dialogId={}, status={}, bodySummary={}",
                        maskValue(dialogId),
                        status,
                        summarizeBody(responseBody));
                return result;
            }

            if (isBlank(responseBody)) {
                return result;
            }

            Map<String, Object> parsed = objectMapper.readValue(responseBody, Map.class);
            int retcode = toInt(parsed.get("retcode"), 0);
            if (retcode != 0) {
                log.warn("SemiMind conversation list 返回失败: dialogId={}, retcode={}, retmsg={}",
                        maskValue(dialogId),
                        retcode,
                        stringValue(parsed.get("retmsg")));
                return result;
            }

            Object dataObj = parsed.get("data");
            if (dataObj instanceof Collection<?>) {
                for (Object item : (Collection<?>) dataObj) {
                    if (!(item instanceof Map<?, ?>)) {
                        continue;
                    }

                    Map<?, ?> conversation = (Map<?, ?>) item;
                    addConversationIdCandidate(result, firstNonBlank(
                            stringValue(conversation.get("id")),
                            stringValue(conversation.get("conversation_id")),
                            stringValue(conversation.get("conversationId")),
                            stringValue(conversation.get("chat_id")),
                            stringValue(conversation.get("chatId"))
                    ));
                }
            } else if (dataObj instanceof Map<?, ?>) {
                Map<?, ?> data = (Map<?, ?>) dataObj;
                addConversationIdCandidate(result, firstNonBlank(
                        stringValue(data.get("id")),
                        stringValue(data.get("conversation_id")),
                        stringValue(data.get("conversationId")),
                        stringValue(data.get("chat_id")),
                        stringValue(data.get("chatId"))
                ));
                Object listObj = firstPresent(data, "list", "items", "records", "rows");
                if (listObj instanceof Collection<?>) {
                    for (Object item : (Collection<?>) listObj) {
                        if (!(item instanceof Map<?, ?>)) {
                            continue;
                        }
                        Map<?, ?> conversation = (Map<?, ?>) item;
                        addConversationIdCandidate(result, firstNonBlank(
                                stringValue(conversation.get("id")),
                                stringValue(conversation.get("conversation_id")),
                                stringValue(conversation.get("conversationId")),
                                stringValue(conversation.get("chat_id")),
                                stringValue(conversation.get("chatId"))
                        ));
                    }
                }
            }

            log.info("SemiMind conversation list 解析完成: dialogId={}, conversationCount={}",
                    maskValue(dialogId),
                    result.size());
        } catch (Exception e) {
            log.warn("SemiMind conversation list 请求失败: dialogId={}, reason={}",
                    maskValue(dialogId),
                    e.getMessage());
        }

        return result;
    }

    private void addConversationIdCandidate(List<String> result, String value) {
        if (result == null || isBlank(value)) {
            return;
        }

        String normalized = value.trim().replace("-", "");
        if (!normalized.matches("(?i)^[0-9a-f-]{32,36}$")) {
            normalized = value.trim();
        }

        if (isBlank(normalized)) {
            return;
        }

        for (String existing : result) {
            if (normalized.equals(existing)) {
                return;
            }
        }
        result.add(normalized);
    }

    private Object firstPresent(Map<?, ?> map, String... keys) {
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

    @SuppressWarnings("unchecked")
    private ConversationContext createConversationOnce(String normalizedBaseUrl,
                                                       String dialogId,
                                                       String conversationId,
                                                       String question,
                                                       String authorization,
                                                       String apiUsername,
                                                       boolean isNew) {
        URI uri = URI.create(normalizedBaseUrl + "/v1/conversation/set");

        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("dialog_id", dialogId);
            if (!isBlank(conversationId)) {
                payload.put("conversation_id", conversationId);
            }
            payload.put("name", buildConversationName(question));

            Map<String, Object> setting = new LinkedHashMap<>();
            setting.put("is_new", isNew);
            payload.put("setting", setting);

            String body = objectMapper.writeValueAsString(payload);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(UPLOAD_TIMEOUT_SECONDS))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

            applySemimindHeaders(requestBuilder, authorization, apiUsername);

            log.info("调用 SemiMind conversation set 接口: url={}, chatId={}, dialogId={}, isNew={}, username={}, nameLength={}",
                    sanitizeUrl(uri.toString()),
                    maskValue(conversationId),
                    maskValue(dialogId),
                    isNew,
                    maskUsername(apiUsername),
                    question == null ? 0 : question.length());

            HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            String responseBody = response.body();

            log.info("SemiMind conversation set 响应: status={}, bodyLength={}",
                    status,
                    responseBody == null ? 0 : responseBody.length());
            log.debug("SemiMind conversation set 响应体摘要: {}", summarizeBody(responseBody));

            if (status < 200 || status >= 300) {
                throw new RuntimeException("SemiMind conversation set 失败: status="
                        + status + ", bodySummary=" + summarizeBody(responseBody));
            }

            if (isBlank(responseBody)) {
                throw new RuntimeException("SemiMind conversation set 响应为空");
            }

            Map<String, Object> parsed = objectMapper.readValue(responseBody, Map.class);
            int retcode = toInt(parsed.get("retcode"), 0);
            if (retcode != 0) {
                throw new RuntimeException("SemiMind conversation set 返回失败: retcode="
                        + retcode + ", retmsg=" + stringValue(parsed.get("retmsg")));
            }

            Map<?, ?> data = null;
            Object dataObj = parsed.get("data");
            if (dataObj instanceof Map<?, ?>) {
                data = (Map<?, ?>) dataObj;
            }

            String returnedConversationId = firstNonBlank(
                    data == null ? null : stringValue(data.get("id")),
                    data == null ? null : stringValue(data.get("conversation_id")),
                    data == null ? null : stringValue(data.get("conversationId")),
                    conversationId
            );
            String returnedDialogId = firstNonBlank(
                    data == null ? null : stringValue(data.get("dialog_id")),
                    data == null ? null : stringValue(data.get("dialogId")),
                    dialogId
            );

            if (isBlank(returnedConversationId)) {
                throw new RuntimeException("SemiMind conversation set 成功但未获得 conversation id");
            }

            log.info("SemiMind conversation set 完成: chatId={}, dialogId={}, isNew={}",
                    maskValue(returnedConversationId),
                    maskValue(returnedDialogId),
                    isNew);

            return new ConversationContext(returnedConversationId, returnedDialogId);
        } catch (Exception e) {
            throw new RuntimeException("SemiMind conversation set 请求失败: " + e.getMessage(), e);
        }
    }
    private UploadedFile uploadFile(String normalizedBaseUrl,
                                    String chatId,
                                    MediaResource resource,
                                    String authorization,
                                    String apiUsername) {
        String fileId = UUID.randomUUID().toString();
        String boundary = "----QAToolsSemiMindBoundary" + UUID.randomUUID().toString().replace("-", "");
        URI uri = URI.create(normalizedBaseUrl + "/v1/conversation/file_upload");

        try {
            byte[] body = buildMultipartBody(boundary, chatId, fileId, resource);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(UPLOAD_TIMEOUT_SECONDS))
                    .header("Accept", "*/*")
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));

            applySemimindHeaders(requestBuilder, authorization, apiUsername);

            log.info("调用 SemiMind file upload 接口: url={}, chatId={}, fileId={}, username={}, filename={}, contentType={}, bodyLength={}",
                    sanitizeUrl(uri.toString()),
                    maskValue(chatId),
                    maskValue(fileId),
                    maskUsername(apiUsername),
                    resource.filename,
                    resource.contentType,
                    body.length);

            HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            String responseBody = response.body();

            log.info("SemiMind file_upload 响应: status={}, bodyLength={}",
                    status,
                    responseBody == null ? 0 : responseBody.length());
            log.debug("SemiMind file_upload 响应体摘要: {}", summarizeBody(responseBody));

            if (status < 200 || status >= 300) {
                throw new RuntimeException("SemiMind file_upload 失败: status="
                        + status + ", bodySummary=" + summarizeBody(responseBody));
            }

            UploadedFile parsed = parseUploadResponse(responseBody, fileId, resource.filename);
            if (isBlank(parsed.fileId)) {
                parsed.fileId = fileId;
            }
            if (isBlank(parsed.filename)) {
                parsed.filename = resource.filename;
            }

            log.info("SemiMind file_upload 完成: chatId={}, fileId={}, fileUrl={}, filename={}",
                    maskValue(chatId),
                    maskValue(parsed.fileId),
                    summarizeUrl(parsed.fileUrl),
                    parsed.filename);

            return parsed;
        } catch (Exception e) {
            throw new RuntimeException("SemiMind file_upload 请求失败: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private UploadedFile parseUploadResponse(String responseBody,
                                             String fallbackFileId,
                                             String fallbackFilename) throws Exception {
        if (isBlank(responseBody)) {
            throw new RuntimeException("SemiMind file_upload 响应体为空");
        }

        Map<String, Object> parsed = objectMapper.readValue(responseBody, Map.class);

        int retcode = toInt(parsed.get("retcode"), 0);
        if (retcode != 0) {
            throw new RuntimeException("SemiMind file_upload 返回失败: retcode="
                    + retcode + ", retmsg=" + stringValue(parsed.get("retmsg")));
        }

        Object dataObj = parsed.get("data");
        if (!(dataObj instanceof Collection<?>)) {
            throw new RuntimeException("SemiMind file_upload 响应 data 不是数组");
        }

        Collection<?> data = (Collection<?>) dataObj;
        for (Object item : data) {
            if (!(item instanceof Map<?, ?>)) {
                continue;
            }

            Map<?, ?> fileMap = (Map<?, ?>) item;
            boolean status = asBoolean(fileMap.get("status"), true);
            String fileUrl = firstNonBlank(
                    stringValue(fileMap.get("file_url")),
                    stringValue(fileMap.get("fileUrl")),
                    stringValue(fileMap.get("url"))
            );

            if (!status) {
                continue;
            }

            UploadedFile uploadedFile = new UploadedFile();
            uploadedFile.fileUrl = fileUrl;
            uploadedFile.fileId = extractFileIdFromFileUrl(fileUrl, fallbackFileId);
            uploadedFile.filename = fallbackFilename;
            return uploadedFile;
        }

        throw new RuntimeException("SemiMind file_upload 响应中没有成功的文件记录");
    }

    private String chat(String normalizedBaseUrl,
                        String chatId,
                        List<String> fileIds,
                        String prompt,
                        String authorization,
                        String apiUsername) {
        URI uri = URI.create(normalizedBaseUrl + "/v1/conversation/chat");

        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("chat_id", chatId);
            payload.put("file_ids", fileIds);
            payload.put("prompt", prompt);

            String body = objectMapper.writeValueAsString(payload);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(CHAT_TIMEOUT_SECONDS))
                    .header("Accept", "application/json, text/event-stream")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

            applySemimindHeaders(requestBuilder, authorization, apiUsername);

            log.info("调用 SemiMind chat 接口: url={}, chatId={}, username={}, fileCount={}, promptLength={}",
                    sanitizeUrl(uri.toString()),
                    maskValue(chatId),
                    maskUsername(apiUsername),
                    fileIds == null ? 0 : fileIds.size(),
                    prompt == null ? 0 : prompt.length());

            HttpResponse<InputStream> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            String contentType = response.headers().firstValue("Content-Type").orElse("");

            log.info("SemiMind chat 响应: status={}, contentType={}", status, contentType);

            if (status < 200 || status >= 300) {
                String errorBody = readAll(response.body());
                throw new RuntimeException("SemiMind chat 失败: status="
                        + status + ", bodySummary=" + summarizeBody(errorBody));
            }

            String answer;
            if (contentType.toLowerCase().contains("text/event-stream")) {
                answer = parseEventStreamAnswer(response.body());
            } else {
                String responseBody = readAll(response.body());
                answer = parseJsonOrPlainAnswer(responseBody);
            }

            return answer == null ? "" : answer;
        } catch (Exception e) {
            throw new RuntimeException("SemiMind chat 请求失败: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private String parseEventStreamAnswer(InputStream inputStream) throws IOException {
        StringBuilder answer = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (isBlank(line)) {
                    continue;
                }

                String trimmed = line.trim();
                if (!trimmed.startsWith("data:")) {
                    continue;
                }

                String data = trimmed.substring("data:".length()).trim();
                if (isBlank(data) || "[DONE]".equalsIgnoreCase(data)) {
                    continue;
                }

                try {
                    Map<String, Object> event = objectMapper.readValue(data, Map.class);
                    Object eventDataObj = event.get("data");

                    if (eventDataObj instanceof Map<?, ?>) {
                        Map<?, ?> eventData = (Map<?, ?>) eventDataObj;
                        String type = stringValue(eventData.get("type"));
                        String mdContent = firstNonBlank(
                                stringValue(eventData.get("md_content")),
                                stringValue(eventData.get("m_content")),
                                stringValue(eventData.get("content")),
                                stringValue(eventData.get("text"))
                        );

                        if (!isBlank(mdContent)
                                && (isBlank(type) || "text".equalsIgnoreCase(type))) {
                            answer.append(mdContent);
                        }
                    } else {
                        String mdContent = firstNonBlank(
                                stringValue(event.get("md_content")),
                                stringValue(event.get("m_content")),
                                stringValue(event.get("content")),
                                stringValue(event.get("text"))
                        );
                        if (!isBlank(mdContent)) {
                            answer.append(mdContent);
                        }
                    }
                } catch (Exception parseException) {
                    log.debug("忽略无法解析的 SemiMind SSE 行: {}", summarizeBody(data));
                }
            }
        }

        return answer.toString();
    }

    @SuppressWarnings("unchecked")
    private String parseJsonOrPlainAnswer(String responseBody) {
        if (isBlank(responseBody)) {
            return "";
        }

        String trimmed = responseBody.trim();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return responseBody;
        }

        try {
            Object parsed = objectMapper.readValue(trimmed, Object.class);
            if (parsed instanceof Map<?, ?>) {
                Map<?, ?> map = (Map<?, ?>) parsed;
                Object dataObj = map.get("data");
                if (dataObj instanceof Map<?, ?>) {
                    Map<?, ?> data = (Map<?, ?>) dataObj;
                    return firstNonBlank(
                            stringValue(data.get("md_content")),
                            stringValue(data.get("m_content")),
                            stringValue(data.get("content")),
                            stringValue(data.get("answer")),
                            stringValue(data.get("text"))
                    );
                }
                return firstNonBlank(
                        stringValue(map.get("md_content")),
                        stringValue(map.get("m_content")),
                        stringValue(map.get("content")),
                        stringValue(map.get("answer")),
                        stringValue(map.get("text")),
                        responseBody
                );
            }
            return responseBody;
        } catch (Exception e) {
            return responseBody;
        }
    }

    private List<MediaResource> collectMediaResources(List<String> questionImages,
                                                      List<String> questionFiles) {
        List<MediaResource> resources = new ArrayList<>();

        for (String image : normalizeStringList(questionImages)) {
            try {
                resources.add(resolveMediaResource(image, "image"));
            } catch (Exception e) {
                log.warn("读取 SemiMind 图片上传资源失败: source={}, reason={}",
                        summarizeMediaSource(image), e.getMessage());
            }
        }

        for (String file : normalizeStringList(questionFiles)) {
            try {
                resources.add(resolveMediaResource(file, "file"));
            } catch (Exception e) {
                log.warn("读取 SemiMind 文件上传资源失败: source={}, reason={}",
                        summarizeMediaSource(file), e.getMessage());
            }
        }

        return resources;
    }

    private MediaResource resolveMediaResource(String source, String type) throws Exception {
        validateRequired("semimind media source", source);

        String filename = extractFileName(source);
        if (isBlank(filename) || filename.startsWith("data:")) {
            filename = "image".equals(type) ? "image.png" : "file.bin";
        }

        if (isDataUri(source)) {
            DataUriResource dataUriResource = parseDataUri(source);
            return new MediaResource(
                    filenameWithExtension(filename, dataUriResource.contentType),
                    dataUriResource.contentType,
                    dataUriResource.bytes,
                    source
            );
        }

        if (isBase64Like(source)) {
            byte[] bytes = Base64.getMimeDecoder().decode(source);
            String contentType = guessContentType(filename, type);
            return new MediaResource(
                    filenameWithExtension(filename, contentType),
                    contentType,
                    bytes,
                    source
            );
        }

        Path localPath = tryResolveLocalPath(source);
        if (localPath != null) {
            byte[] bytes = Files.readAllBytes(localPath);
            String localFileName = localPath.getFileName() == null
                    ? filename
                    : localPath.getFileName().toString();
            String contentType = guessContentType(localFileName, type);
            return new MediaResource(
                    filenameWithExtension(localFileName, contentType),
                    contentType,
                    bytes,
                    source
            );
        }

        List<URI> candidates = buildMediaUriCandidates(source);
        Exception lastError = null;
        for (URI candidate : candidates) {
            try {
                FetchMediaResult fetchResult = fetchMediaBytes(candidate);
                return new MediaResource(
                        filenameWithExtension(filename, fetchResult.contentType),
                        firstNonBlank(fetchResult.contentType, guessContentType(filename, type)),
                        fetchResult.bytes,
                        candidate.toString()
                );
            } catch (Exception e) {
                lastError = e;
                log.debug("尝试下载 SemiMind 媒体资源失败: url={}, reason={}",
                        sanitizeUrl(candidate.toString()), e.getMessage());
            }
        }
        throw lastError == null
                ? new RuntimeException("无法解析媒体资源地址: " + summarizeMediaSource(source))
                : lastError;
    }

    private List<URI> buildMediaUriCandidates(String source) {
        List<URI> candidates = new ArrayList<>();
        String value = source == null ? "" : source.trim();

        if (value.startsWith("http://") || value.startsWith("https://")) {
            candidates.add(URI.create(value));
            return candidates;
        }

        String baseUrl = normalizePublicBaseUrl();
        if (isBlank(baseUrl)) {
            return candidates;
        }

        if (value.startsWith("/")) {
            candidates.add(URI.create(baseUrl + value));
        } else {
            candidates.add(URI.create(baseUrl + "/" + value));
            candidates.add(URI.create(baseUrl + "/api/datasets/images/" + value));
        }

        return candidates;
    }

    private FetchMediaResult fetchMediaBytes(URI uri) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(UPLOAD_TIMEOUT_SECONDS))
                .header("Accept", "*/*")
                .GET()
                .build();

        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        int status = response.statusCode();

        if (status < 200 || status >= 300) {
            throw new RuntimeException("下载媒体资源失败: status=" + status
                    + ", url=" + sanitizeUrl(uri.toString()));
        }

        byte[] bytes = response.body();
        if (bytes == null || bytes.length == 0) {
            throw new RuntimeException("下载媒体资源为空: url=" + sanitizeUrl(uri.toString()));
        }

        String contentType = response.headers()
                .firstValue("Content-Type")
                .orElse(null);

        return new FetchMediaResult(bytes, contentType);
    }

    private byte[] buildMultipartBody(String boundary,
                                     String chatId,
                                     String fileId,
                                     MediaResource resource) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        writeFormField(out, boundary, "chat_id", chatId);
        writeFormField(out, boundary, "file_id", fileId);

        writeString(out, "--" + boundary + "\r\n");
        writeString(out, "Content-Disposition: form-data; name=\"file\"; filename=\""
                + escapeMultipartValue(resource.filename) + "\"\r\n");
        writeString(out, "Content-Type: " + firstNonBlank(resource.contentType, "application/octet-stream") + "\r\n\r\n");

        out.write(resource.bytes);
        writeString(out, "\r\n");

        writeString(out, "--" + boundary + "--\r\n");
        return out.toByteArray();
    }

    private void writeFormField(ByteArrayOutputStream out,
                                String boundary,
                                String name,
                                String value) throws IOException {
        writeString(out, "--" + boundary + "\r\n");
        writeString(out, "Content-Disposition: form-data; name=\"" + escapeMultipartValue(name) + "\"\r\n\r\n");
        writeString(out, value == null ? "" : value);
        writeString(out, "\r\n");
    }

    private void writeString(ByteArrayOutputStream out, String value) throws IOException {
        out.write(value.getBytes(StandardCharsets.UTF_8));
    }

    private void applySemimindHeaders(HttpRequest.Builder requestBuilder,
                                      String authorization,
                                      String username) {
        if (!isBlank(authorization)) {
            requestBuilder.header("Authorization", authorization.trim());
        }
        if (!isBlank(username)) {
            requestBuilder.header("Username", username.trim());
        }
    }

    private void applyAuthorization(HttpRequest.Builder requestBuilder, String authorization) {
        if (!isBlank(authorization)) {
            requestBuilder.header("Authorization", authorization.trim());
        }
    }


    private DataUriResource parseDataUri(String dataUri) {
        int commaIndex = dataUri.indexOf(',');
        if (commaIndex < 0) {
            throw new IllegalArgumentException("非法 data URI");
        }

        String meta = dataUri.substring(5, commaIndex);
        String data = dataUri.substring(commaIndex + 1);

        String contentType = "application/octet-stream";
        boolean base64 = false;

        String[] parts = meta.split(";");
        if (parts.length > 0 && !isBlank(parts[0])) {
            contentType = parts[0];
        }

        for (String part : parts) {
            if ("base64".equalsIgnoreCase(part)) {
                base64 = true;
                break;
            }
        }

        byte[] bytes = base64
                ? Base64.getMimeDecoder().decode(data)
                : java.net.URLDecoder.decode(data, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8);

        return new DataUriResource(bytes, contentType);
    }

    private Path tryResolveLocalPath(String source) {
        if (isBlank(source)) {
            return null;
        }

        try {
            Path path = Paths.get(source);
            if (Files.exists(path) && Files.isRegularFile(path)) {
                return path;
            }
        } catch (Exception ignored) {
        }

        return null;
    }

    private String normalizePublicBaseUrl() {
        String baseUrl = firstNonBlank(
                qatoolsPublicBaseUrl,
                "http://127.0.0.1:" + serverPort
        );

        if (isBlank(baseUrl)) {
            return null;
        }

        String normalized = baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        return normalized;
    }

    private String normalizeBaseUrl(String baseUrl) {
        String url = baseUrl == null ? "" : baseUrl.trim();

        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }

        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }

        return "http://" + url;
    }



    private String normalizeSemimindAgentType(String agentType) {
        if (isBlank(agentType)) {
            return "unknown";
        }

        String value = agentType.trim().toLowerCase()
                .replace('-', '_');
        if ("canvas".equals(value) || "workflow".equals(value) || "flow".equals(value) || "work_flow".equals(value)) {
            return "workflow";
        }
        if ("bot".equals(value) || "agent".equals(value) || "assistant".equals(value)) {
            return "agent";
        }
        return value;
    }
    private boolean isWorkflowType(String normalizedAgentType) {
        return "workflow".equals(normalizedAgentType)
                || "canvas".equals(normalizedAgentType)
                || "flow".equals(normalizedAgentType);
    }

    private boolean isAgentType(String normalizedAgentType) {
        return "agent".equals(normalizedAgentType)
                || "bot".equals(normalizedAgentType)
                || "assistant".equals(normalizedAgentType);
    }

    /**
     * 解析 SemiMind dialog_id 候选值。
     *
     * 只接受明确来源的 dialogId 和临时配置的 configuredDialogId，不再把 agentId 自动当成 dialog_id。
     * 原因：SemiMind 工作流/canvas id 本身也是 32 位，直接当 dialog_id 会导致 conversation/list 为空。
     */
    private List<String> buildDialogIdCandidates(String dialogId) {
        List<String> result = new ArrayList<>();
        addDialogIdCandidate(result, dialogId);
        addDialogIdCandidate(result, configuredDialogId);
        return result;
    }

    private void addDialogIdCandidate(List<String> result, String value) {
        if (result == null || isBlank(value)) {
            return;
        }

        String normalized = normalizeSemimindId(value);
        if (isBlank(normalized)) {
            return;
        }

        for (String existing : result) {
            if (normalized.equals(existing)) {
                return;
            }
        }
        result.add(normalized);
    }

    private boolean looksLikeSemimindDialogId(String value) {
        if (isBlank(value)) {
            return false;
        }
        return value.trim().replace("-", "").matches("(?i)^[0-9a-f]{32}$");
    }

    private String normalizeSemimindId(String value) {
        if (isBlank(value)) {
            return null;
        }

        String normalized = value.trim();
        String noHyphen = normalized.replace("-", "");
        if (noHyphen.matches("(?i)^[0-9a-f]{32}$")) {
            return noHyphen;
        }
        return normalized;
    }

    private String buildConversationName(String question) {
        String name = question == null ? "" : question.trim();
        if (name.isEmpty()) {
            return "多模态评测";
        }

        name = name.replaceAll("\\s+", " ").trim();
        return name.length() <= 80 ? name : name.substring(0, 80);
    }

    private String normalizeMultimodalQuestion(String question,
                                               List<String> questionImages,
                                               List<String> questionFiles) {
        if (!isBlank(question)) {
            return question.trim();
        }

        if (questionImages != null && !questionImages.isEmpty()) {
            return "请根据上传的图片回答问题。";
        }

        if (questionFiles != null && !questionFiles.isEmpty()) {
            return "请根据上传的文件回答问题。";
        }

        return "请根据指定的多模态输入完成回答。";
    }

    private String filenameWithExtension(String filename, String contentType) {
        if (isBlank(filename)) {
            filename = "file";
        }

        String lower = filename.toLowerCase();
        if (lower.endsWith(".png")
                || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg")
                || lower.endsWith(".gif")
                || lower.endsWith(".webp")
                || lower.endsWith(".pdf")
                || lower.endsWith(".txt")
                || lower.endsWith(".csv")
                || lower.endsWith(".xlsx")
                || lower.endsWith(".xls")
                || lower.endsWith(".doc")
                || lower.endsWith(".docx")) {
            return filename;
        }

        String extension = extensionFromContentType(contentType);
        return isBlank(extension) ? filename : filename + extension;
    }

    private String extensionFromContentType(String contentType) {
        if (isBlank(contentType)) {
            return "";
        }

        String value = contentType.toLowerCase();
        if (value.contains("image/png")) return ".png";
        if (value.contains("image/jpeg")) return ".jpg";
        if (value.contains("image/gif")) return ".gif";
        if (value.contains("image/webp")) return ".webp";
        if (value.contains("application/pdf")) return ".pdf";
        if (value.contains("text/plain")) return ".txt";
        if (value.contains("text/csv")) return ".csv";
        return "";
    }

    private String guessContentType(String filename, String type) {
        String lower = filename == null ? "" : filename.toLowerCase();

        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".csv")) return "text/csv";

        if ("image".equalsIgnoreCase(type)) {
            return "image/png";
        }

        return "application/octet-stream";
    }

    private String extractFileName(String value) {
        if (isBlank(value)) {
            return "";
        }

        String text = value.trim();

        int queryIndex = text.indexOf('?');
        if (queryIndex >= 0) {
            text = text.substring(0, queryIndex);
        }

        int hashIndex = text.indexOf('#');
        if (hashIndex >= 0) {
            text = text.substring(0, hashIndex);
        }

        int slashIndex = Math.max(text.lastIndexOf('/'), text.lastIndexOf('\\'));
        String name = slashIndex >= 0 ? text.substring(slashIndex + 1) : text;

        if (name.startsWith("data:")) {
            return "image";
        }

        return name.length() > 120 ? name.substring(0, 120) : name;
    }

    private String extractFileIdFromFileUrl(String fileUrl, String fallbackFileId) {
        if (isBlank(fileUrl)) {
            return fallbackFileId;
        }

        String value = fileUrl.trim();
        int slashIndex = value.lastIndexOf('/');
        if (slashIndex >= 0 && slashIndex < value.length() - 1) {
            return value.substring(slashIndex + 1);
        }

        return fallbackFileId;
    }

    private String generateConversationId() {
        long now100ns = System.currentTimeMillis() * 10_000L + UUID_EPOCH_OFFSET_100NS;
        long timestamp = LAST_UUID_TIMESTAMP_100NS.updateAndGet(previous -> now100ns > previous ? now100ns : previous + 1);

        long timeLow = timestamp & 0xFFFFFFFFL;
        long timeMid = (timestamp >>> 32) & 0xFFFFL;
        long timeHiAndVersion = ((timestamp >>> 48) & 0x0FFFL) | 0x1000L;
        int clockSeq = ThreadLocalRandom.current().nextInt(0x4000) | 0x8000;
        long node = ThreadLocalRandom.current().nextLong() & 0xFFFFFFFFFFFFL;

        return String.format("%08x-%04x-%04x-%04x-%012x",
                timeLow,
                timeMid,
                timeHiAndVersion,
                clockSeq,
                node);
    }

    private String readAll(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return "";
        }

        try (InputStream input = inputStream) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Map<String, Object> buildFailureResult(Throwable throwable, long startTime) {
        Throwable root = unwrap(throwable);

        String message = root.getMessage();
        if (isBlank(message)) {
            message = root.getClass().getSimpleName();
        }

        long responseTime = System.currentTimeMillis() - startTime;
        log.warn("SemiMind 多模态 Agent 调用失败: {}", message, root);


        Map<String, Object> result = new HashMap<>();
        result.put("answer", "调用 SemiMind 多模态失败: " + message);
        result.put("responseTime", responseTime);
        result.put("multimodalTransport", "semimind-file-upload");
        return result;
    }

    private Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current != null
                && current.getCause() != null
                && (current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException
                || current instanceof RuntimeException)) {
            current = current.getCause();
        }
        return current == null ? throwable : current;
    }

    private void validateRequired(String name, String value) {
        if (isBlank(value)) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
    }

    private String sanitizeUrl(String url) {
        if (url == null) {
            return null;
        }

        return url
                .replaceAll("(?i)(token=)[^&]+", "$1***")
                .replaceAll("(?i)(session_id=)[^&]+", "$1***")
                .replaceAll("(?i)(tenant_id=)[^&]+", "$1***")
                .replaceAll("(?i)(password=)[^&]+", "$1***")
                .replaceAll("(?i)(authorization=)[^&]+", "$1***");
    }

    private String summarizeBody(String body) {
        if (isBlank(body)) {
            return "";
        }

        String sanitized = body
                .replaceAll("(?i)\"access_token\"\\s*:\\s*\"[^\"]*\"", "\"access_token\":\"***\"")
                .replaceAll("(?i)\"accessToken\"\\s*:\\s*\"[^\"]*\"", "\"accessToken\":\"***\"")
                .replaceAll("(?i)\"token\"\\s*:\\s*\"[^\"]*\"", "\"token\":\"***\"")
                .replaceAll("(?i)\"password\"\\s*:\\s*\"[^\"]*\"", "\"password\":\"***\"")
                .replaceAll("(?i)\"authorization\"\\s*:\\s*\"[^\"]*\"", "\"authorization\":\"***\"")
                .replaceAll("(?i)(Bearer\\s+)[A-Za-z0-9._~+/-]+", "$1***");

        if (sanitized.length() > 300) {
            return sanitized.substring(0, 300) + "...";
        }

        return sanitized;
    }

    private String summarizeMediaSource(String source) {
        if (source == null) {
            return "";
        }

        String value = source.trim();
        if (value.length() <= 160) {
            return sanitizeUrl(value);
        }

        return sanitizeUrl(value.substring(0, 160) + "...");
    }

    private String summarizeUrl(String value) {
        if (isBlank(value)) {
            return "";
        }
        return value.length() <= 120 ? value : value.substring(0, 120) + "...";
    }

    private String maskValue(String value) {
        if (isBlank(value)) {
            return "";
        }

        String trimmed = value.trim();

        if (trimmed.length() <= 2) {
            return "***";
        }

        if (trimmed.length() <= 6) {
            return trimmed.charAt(0) + "***" + trimmed.charAt(trimmed.length() - 1);
        }

        return trimmed.substring(0, 2) + "***" + trimmed.substring(trimmed.length() - 2);
    }

    private String maskUsername(String username) {
        if (isBlank(username)) {
            return "";
        }

        String value = username.trim();

        int atIndex = value.indexOf('@');
        if (atIndex > 1) {
            String prefix = value.substring(0, atIndex);
            String domain = value.substring(atIndex);
            return maskValue(prefix) + domain;
        }

        return maskValue(value);
    }

    private String escapeMultipartValue(String value) {
        if (value == null) {
            return "";
        }

        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    private int toInt(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (Exception e) {
            return defaultValue;
        }
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

        String text = value.toString().trim().toLowerCase();
        if ("true".equals(text) || "1".equals(text) || "yes".equals(text) || "y".equals(text)) {
            return true;
        }
        if ("false".equals(text) || "0".equals(text) || "no".equals(text) || "n".equals(text)) {
            return false;
        }
        return defaultValue;
    }

    private List<String> normalizeStringList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (!isBlank(value)) {
                result.add(value.trim());
            }
        }

        return result;
    }

    private static boolean isDataUri(String value) {
        if (value == null) {
            return false;
        }

        String text = value.trim().toLowerCase();
        return text.startsWith("data:image/")
                || text.startsWith("data:application/")
                || text.startsWith("data:text/");
    }

    private static boolean isBase64Like(String value) {
        if (value == null) {
            return false;
        }

        String text = value.trim();
        if (text.length() < 120) {
            return false;
        }

        if (text.startsWith("http://")
                || text.startsWith("https://")
                || text.startsWith("/")
                || text.contains("://")) {
            return false;
        }

        return text.matches("^[A-Za-z0-9+/=\\r\\n]+$");
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }

        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }

        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static class PublishEndpoint {
        private final String label;
        private final String path;

        private PublishEndpoint(String label, String path) {
            this.label = label;
            this.path = path;
        }
    }

    private static class ConversationContext {
        private final String conversationId;
        private final String dialogId;

        private ConversationContext(String conversationId, String dialogId) {
            this.conversationId = conversationId;
            this.dialogId = dialogId;
        }
    }

    private static class UploadedFile {
        private String fileId;
        private String fileUrl;
        private String filename;
    }

    private static class MediaResource {
        private final String filename;
        private final String contentType;
        private final byte[] bytes;
        @SuppressWarnings("unused")
        private final String source;

        private MediaResource(String filename,
                              String contentType,
                              byte[] bytes,
                              String source) {
            this.filename = filename;
            this.contentType = contentType;
            this.bytes = bytes;
            this.source = source;
        }
    }

    private static class FetchMediaResult {
        private final byte[] bytes;
        private final String contentType;

        private FetchMediaResult(byte[] bytes, String contentType) {
            this.bytes = bytes;
            this.contentType = contentType;
        }
    }

    private static class DataUriResource {
        private final byte[] bytes;
        private final String contentType;

        private DataUriResource(byte[] bytes, String contentType) {
            this.bytes = bytes;
            this.contentType = contentType;
        }
    }

    @PreDestroy
    public void shutdown() {
        chatExecutor.shutdown();
        try {
            if (!chatExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                chatExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            chatExecutor.shutdownNow();
        }
    }
}
