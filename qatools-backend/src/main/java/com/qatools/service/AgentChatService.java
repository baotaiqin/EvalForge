package com.qatools.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qatools.config.EnvDbConfig;
import com.qatools.config.EnvDbProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 多Agent会话服务
 *
 * 实现 SemiMind 平台的多Agent调用逻辑：
 * 1. publish   — 发布智能体/工作流，获取 dialog_id
 * 2. new_token — 根据 dialog_id 获取对话 token
 * 3. chat      — 使用 token 发送消息并获取回答
 *
 * 认证方式：
 *   通过前端预登录获取的 authorization 和 apiUsername 参数传入
 */
@Service
public class AgentChatService {

    private static final Logger log = LoggerFactory.getLogger(AgentChatService.class);

    @Autowired
    private EnvDbProperties envDbProperties;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newFixedThreadPool(10);

    public AgentChatService() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(15));
        factory.setReadTimeout(Duration.ofSeconds(60));
        this.restTemplate = new RestTemplate(factory);
    }

    // ==================== 公开方法 ====================

    /**
     * 完整的Agent对话流程（同步），使用传入的 auth 信息
     *
     * 会话复用策略（减少不必要的调用）：
     * - 有 chatToken → 直接 chat（1次调用）
     * - 有 dialogId 无 chatToken → new_token + chat（2次调用）
     * - 都没有 → publish + new_token + chat（3次调用，首次对话）
     * - 任一步骤失败 → fallback 完整3步重试
     *
     * @param envUrl       环境前端URL，如 demo-sit.example.invalid:7111
     * @param agentId      智能体/工作流在远程数据库中的实际ID
     * @param agentType    "bot" 或 "canvas"
     * @param question     用户问题
     * @param dialogId     已有的 dialog_id（可为 null）
     * @param chatToken    已有的对话 token（可为 null）
     * @param authorization 登录获取的 authorization token
     * @param apiUsername  登录获取的 api username
     * @return Map 包含 "answer"、"dialogId"、"chatToken"（前端应缓存供下次复用）
     */
    public Map<String, Object> chatWithAgent(String envUrl, String agentId, String agentType,
                                              String question, String dialogId, String chatToken,
                                              String authorization, String apiUsername) {
        boolean hasDialogId = dialogId != null && !dialogId.isBlank();
        boolean hasChatToken = chatToken != null && !chatToken.isBlank();

        // 快速路径：有 token 直接 chat
        if (hasChatToken && hasDialogId) {
            try {
                log.info("复用已有token直接chat: agentId={}, dialogId={}", agentId, dialogId);
                Map<String, Object> chatResult = chat(envUrl, chatToken, question);
                String answer = (String) chatResult.get("answer");
                log.info("Agent回答完成(复用token): agentId={}, answerLength={}", agentId, answer.length());
                Map<String, Object> result = new HashMap<>();
                result.put("answer", answer);
                result.put("dialogId", dialogId);
                result.put("chatToken", chatToken);
                if (chatResult.get("imgMap") != null) result.put("imgMap", chatResult.get("imgMap"));
                return result;
            } catch (Exception e) {
                log.warn("复用token失败，尝试重新获取token: agentId={}, error={}", agentId, e.getMessage());
                // token 失效，fall through 到 new_token 路径
            }
        }

        // 中间路径：有 dialogId，获取新 token 后 chat
        if (hasDialogId) {
            try {
                log.info("复用已有dialogId: agentId={}, dialogId={}", agentId, dialogId);
                String newToken = getNewToken(envUrl, dialogId, authorization, apiUsername);
                log.info("获取token成功: dialogId={}", dialogId);
                Map<String, Object> chatResult = chat(envUrl, newToken, question);
                String answer = (String) chatResult.get("answer");
                log.info("Agent回答完成(复用dialogId): agentId={}, answerLength={}", agentId, answer.length());
                Map<String, Object> result = new HashMap<>();
                result.put("answer", answer);
                result.put("dialogId", dialogId);
                result.put("chatToken", newToken);
                if (chatResult.get("imgMap") != null) result.put("imgMap", chatResult.get("imgMap"));
                return result;
            } catch (Exception e) {
                log.warn("复用dialogId失败，完整重建会话: dialogId={}, error={}", dialogId, e.getMessage());
                // dialogId 也失效，fall through 到完整路径
            }
        }

        // 完整路径：publish + new_token + chat
        String newDialogId = publish(envUrl, agentId, agentType, authorization, apiUsername);
        log.info("Publish成功(新建): envUrl={}, agentId={}, dialogId={}", envUrl, agentId, newDialogId);
        String newToken = getNewToken(envUrl, newDialogId, authorization, apiUsername);
        log.info("获取token成功: dialogId={}", newDialogId);
        Map<String, Object> chatResult = chat(envUrl, newToken, question);
        String answer = (String) chatResult.get("answer");
        log.info("Agent回答完成(新建会话): agentId={}, answerLength={}", agentId, answer.length());
        Map<String, Object> result = new HashMap<>();
        result.put("answer", answer);
        result.put("dialogId", newDialogId);
        result.put("chatToken", newToken);
        if (chatResult.get("imgMap") != null) result.put("imgMap", chatResult.get("imgMap"));
        return result;
    }

    /**
     * 完整的Agent对话流程（同步），使用配置文件中的 auth 信息（向后兼容）
     */
    public Map<String, Object> chatWithAgent(String envUrl, String agentId, String agentType, String question) {
        EnvDbConfig config = getConfig(envUrl);
        return chatWithAgent(envUrl, agentId, agentType, question, null, null,
                config.getAuthorization(), config.getApiUsername());
    }

    /**
     * 异步Agent对话，使用传入的 auth 信息
     *
     * @param targetId  前端生成的复合ID，原样返回给前端用于匹配
     * @param dialogId  已有的 dialog_id（可为 null）
     * @param chatToken 已有的对话 token（可为 null）
     */
    public CompletableFuture<Map<String, Object>> chatWithAgentAsync(
            String envUrl, String agentId, String agentType, String targetId, String label, String question,
            String dialogId, String chatToken, String authorization, String apiUsername) {
        return CompletableFuture.supplyAsync(() -> {
            long start = System.currentTimeMillis();
            Map<String, Object> result = new HashMap<>();
            result.put("targetId", targetId);
            result.put("label", label);
            try {
                Map<String, Object> chatResult = chatWithAgent(envUrl, agentId, agentType, question,
                        dialogId, chatToken, authorization, apiUsername);
                result.put("answer", chatResult.get("answer"));
                result.put("dialogId", chatResult.get("dialogId"));
                result.put("chatToken", chatResult.get("chatToken"));
                if (chatResult.get("imgMap") != null) result.put("imgMap", chatResult.get("imgMap"));
                result.put("responseTime", System.currentTimeMillis() - start);
            } catch (Exception e) {
                log.error("Agent对话失败 envUrl={}, agentId={}: {}", envUrl, agentId, e.getMessage());
                String errorMsg = e.getMessage();
                if (errorMsg != null && errorMsg.length() > 200) {
                    errorMsg = errorMsg.substring(0, 200) + "...";
                }
                result.put("answer", "Agent调用失败: " + errorMsg);
                result.put("responseTime", System.currentTimeMillis() - start);
            }
            return result;
        }, executor).orTimeout(60, TimeUnit.SECONDS);
    }

    /**
     * 异步Agent对话，使用配置文件中的 auth 信息（向后兼容）
     */
    public CompletableFuture<Map<String, Object>> chatWithAgentAsync(
            String envUrl, String agentId, String agentType, String targetId, String label, String question) {
        EnvDbConfig config = getConfig(envUrl);
        return chatWithAgentAsync(envUrl, agentId, agentType, targetId, label, question,
                null, null, config.getAuthorization(), config.getApiUsername());
    }

    // ==================== 流式 SSE 代理 ====================

    /**
     * 流式Agent对话 — 将 SemiMind 的 SSE 流直接转发给前端处理
     *
     * 后端只负责：会话建立(publish/new_token) + SSE 流转发
     * 前端负责：SSE 解析、answer 展现、引用处理、markdown 渲染
     *
     * SSE 事件协议（后端 → 前端）：
     *   event: session  — 会话建立成功，data: {"dialogId":"...", "chatToken":"..."}
     *   event: message  — SemiMind 原始 SSE 数据转发
     *   event: error    — 错误，data: {"message":"..."}
     */
    public void streamChatWithAgent(String envUrl, String agentId, String agentType,
                                    String question, String dialogId, String chatToken,
                                    String authorization, String apiUsername,
                                    SseEmitter emitter) throws Exception {
        boolean hasDialogId = dialogId != null && !dialogId.isBlank();
        boolean hasChatToken = chatToken != null && !chatToken.isBlank();

        // 快速路径：有 token 直接 stream
        if (hasChatToken && hasDialogId) {
            try {
                log.info("流式复用已有token: agentId={}, dialogId={}", agentId, dialogId);
                sendSessionEvent(emitter, dialogId, chatToken);
                streamChatProxy(envUrl, chatToken, question, emitter);
                return;
            } catch (Exception e) {
                log.warn("流式复用token失败，尝试重新获取: {}", e.getMessage());
            }
        }

        // 中间路径：有 dialogId，获取新 token
        if (hasDialogId) {
            try {
                log.info("流式复用已有dialogId: agentId={}, dialogId={}", agentId, dialogId);
                String newToken = getNewToken(envUrl, dialogId, authorization, apiUsername);
                sendSessionEvent(emitter, dialogId, newToken);
                streamChatProxy(envUrl, newToken, question, emitter);
                return;
            } catch (Exception e) {
                log.warn("流式复用dialogId失败，完整重建: {}", e.getMessage());
            }
        }

        // 完整路径：publish + new_token + stream
        String newDialogId = publish(envUrl, agentId, agentType, authorization, apiUsername);
        log.info("流式Publish成功: agentId={}, dialogId={}", agentId, newDialogId);
        String newToken = getNewToken(envUrl, newDialogId, authorization, apiUsername);
        sendSessionEvent(emitter, newDialogId, newToken);
        streamChatProxy(envUrl, newToken, question, emitter);
    }

    /** 发送 session 事件（包含 dialogId 和 chatToken 供前端缓存） */
    private void sendSessionEvent(SseEmitter emitter, String dialogId, String chatToken) throws IOException {
        Map<String, String> session = new HashMap<>();
        session.put("dialogId", dialogId);
        session.put("chatToken", chatToken);
        emitter.send(SseEmitter.event().name("session")
                .data(objectMapper.writeValueAsString(session), MediaType.TEXT_PLAIN));
    }

    /**
     * 流式调用 SemiMind chat 接口，将 SSE 事件原样透传给前端
     * 后端不做任何内容解析，所有渲染逻辑由前端负责
     *
     * 透传策略：
     *   SSE 格式（data:xxx） → 提取 data: 后的内容，过滤结束标记，原样转发
     *   NDJSON 格式（{...}） → 整行原样转发，前端自行解码
     *   非流式 JSON 响应   → 整体作为单条 message 事件转发
     */
    private void streamChatProxy(String envUrl, String token, String question, SseEmitter emitter) throws Exception {
        URL url = new URL("http://" + envUrl + "/v1/api/chat");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("platform", "Semi-Mind");
        conn.setRequestProperty("traceId", UUID.randomUUID().toString().replace("-", ""));

        String chatId = UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> body = new HashMap<>();
        body.put("chat_id", chatId);
        body.put("prompt", question);
        body.put("file_ids", new ArrayList<>());

        byte[] bodyBytes = objectMapper.writeValueAsBytes(body);
        log.info("streamChatProxy请求: url={}, headers={}, body={}",
                url, conn.getRequestProperties(), new String(bodyBytes, StandardCharsets.UTF_8));
        try (OutputStream out = conn.getOutputStream()) {
            out.write(bodyBytes);
            out.flush();
        }

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            InputStream errStream = conn.getErrorStream();
            String errBody = errStream != null
                    ? new String(errStream.readAllBytes(), StandardCharsets.UTF_8) : "Unknown error";
            throw new RuntimeException("Chat接口返回HTTP " + responseCode + ": "
                    + (errBody.length() > 200 ? errBody.substring(0, 200) + "..." : errBody));
        }

        log.info("streamChatProxy: chatId={}, contentType={}", chatId, conn.getContentType());

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8), 256)) {

            String firstLine = reader.readLine();
            if (firstLine == null) {
                log.warn("streamChatProxy: 响应为空");
                return;
            }
            String trimmedFirst = firstLine.trim();
            log.info("streamChatProxy: firstLine长度={}, 前100字符=[{}]",
                    trimmedFirst.length(), trimmedFirst.substring(0, Math.min(100, trimmedFirst.length())));

            boolean isSSE = trimmedFirst.startsWith("data:") || trimmedFirst.startsWith("event:");
            boolean isNDJSON = !isSSE && trimmedFirst.startsWith("{");

            if (isSSE) {
                // SSE 流：逐行提取 data: 内容并原样转发
                log.info("streamChatProxy: 检测到SSE格式");
                forwardSSELine(trimmedFirst, emitter);
                String line;
                int lineCount = 1;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    lineCount++;
                    forwardSSELine(line, emitter);
                }
                log.info("streamChatProxy: SSE流结束，共读取{}行", lineCount);
            } else if (isNDJSON) {
                // NDJSON 流：整行原样转发，前端自行解码（含双重编码处理）
                log.info("streamChatProxy: 检测到NDJSON格式，原样透传");
                forwardRawLine(trimmedFirst, emitter);
                String line;
                int lineCount = 1;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    lineCount++;
                    log.info("streamChatProxy: NDJSON第{}行，长度={}", lineCount, line.length());
                    forwardRawLine(line, emitter);
                }
                log.info("streamChatProxy: NDJSON流结束，共读取{}行", lineCount);
            } else {
                // 非流式 JSON 响应：读取完整响应体，整体转发
                StringBuilder sb = new StringBuilder();
                sb.append(firstLine).append("\n");
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                String responseBody = sb.toString().trim();
                log.info("streamChatProxy: 非SSE响应，长度={}", responseBody.length());
                emitter.send(SseEmitter.event().name("message")
                        .data(responseBody, MediaType.TEXT_PLAIN));
            }
        } finally {
            conn.disconnect();
        }
    }

    /** SSE 行透传：提取 data: 后的内容，过滤结束标记，原样转发 */
    private void forwardSSELine(String line, SseEmitter emitter) throws IOException {
        if (!line.startsWith("data:")) return;
        String data = line.substring(5).trim();
        if (data.isEmpty() || "[DONE]".equals(data) || "true".equals(data)) return;
        // 过滤 {"data":true} 结束标记
        if (data.startsWith("{")) {
            try {
                Object parsed = objectMapper.readValue(data, Object.class);
                if (parsed instanceof Map && ((Map<?, ?>) parsed).get("data") instanceof Boolean) return;
            } catch (Exception ignored) {}
        }
        log.debug("SSE转发: time={}, dataLen={}", System.currentTimeMillis(), data.length());
        emitter.send(SseEmitter.event().name("message").data(data, MediaType.TEXT_PLAIN));
    }

    /** NDJSON 行透传：过滤结束标记，整行原样转发 */
    private void forwardRawLine(String line, SseEmitter emitter) throws IOException {
        if (line.isEmpty()) return;
        // 过滤结束标记
        try {
            Object parsed = objectMapper.readValue(line, Object.class);
            if (parsed instanceof Map) {
                Object data = ((Map<?, ?>) parsed).get("data");
                if (data instanceof Boolean) return;
                if ("true".equals(data) || "[DONE]".equals(data)) return;
            }
        } catch (Exception ignored) {}
        log.debug("NDJSON转发: time={}, dataLen={}", System.currentTimeMillis(), line.length());
        emitter.send(SseEmitter.event().name("message").data(line, MediaType.TEXT_PLAIN));
    }

    // ==================== 内部步骤 ====================

    /**
     * Step 1: 发布智能体/工作流，获取 dialog_id
     * - 智能体: POST /v1/bot/publish
     * - 工作流: POST /v1/canvas/publish
     */
    @SuppressWarnings("unchecked")
    private String publish(String envUrl, String agentId, String agentType,
                           String authorization, String apiUsername) {
        String publishPath = "bot".equals(agentType) ? "/v1/bot/publish" : "/v1/canvas/publish";
        String url = "http://" + envUrl + publishPath;

        HttpHeaders headers = buildAuthHeaders(authorization, apiUsername);

        Map<String, Object> body = new HashMap<>();
        body.put("id", agentId);
        body.put("publish_type", "personal");

        log.info("调用publish接口: url={}, agentId={}, type={}", url, agentId, agentType);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);

        Map<String, Object> respBody = response.getBody();
        log.info("Publish响应完整内容: envUrl={}, agentId={}, response={}", envUrl, agentId, respBody);
        if (respBody == null) {
            throw new RuntimeException("Publish接口未返回响应");
        }

        // 检查返回码 — 兼容 code 和 retcode 两种格式
        Object code = respBody.get("code");
        if (code == null) code = respBody.get("retcode");
        if (code != null && !Objects.equals(code, 0) && !"0".equals(code.toString())) {
            // 兼容 message / retmsg 两种错误信息字段
            Object msg = respBody.get("message");
            if (msg == null) msg = respBody.get("retmsg");
            throw new RuntimeException("Publish失败(code=" + code + "): " + msg);
        }

        // data 即为 dialog_id
        Object data = respBody.get("data");
        if (data == null) {
            throw new RuntimeException("Publish接口返回data为空: " + respBody);
        }

        // data 可能是 dialog_id 字符串，也可能是包含 id 的 Map
        if (data instanceof String) {
            return (String) data;
        } else if (data instanceof Map) {
            Map<String, Object> dataMap = (Map<String, Object>) data;
            Object id = dataMap.get("id");
            if (id == null) id = dataMap.get("dialog_id");
            if (id != null) return id.toString();
        }

        throw new RuntimeException("无法从Publish响应中提取dialog_id: " + data);
    }

    /**
     * Step 2: 获取对话 token
     * POST /v1/api/new_token
     */
    @SuppressWarnings("unchecked")
    private String getNewToken(String envUrl, String dialogId,
                               String authorization, String apiUsername) {
        String url = "http://" + envUrl + "/v1/api/new_token";

        HttpHeaders headers = buildAuthHeaders(authorization, apiUsername);

        Map<String, Object> body = new HashMap<>();
        body.put("dialog_id", dialogId);

        log.info("调用new_token接口: url={}, dialogId={}", url, dialogId);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);

        Map<String, Object> respBody = response.getBody();
        log.info("new_token响应: dialogId={}, response={}", dialogId, respBody);
        if (respBody == null) {
            throw new RuntimeException("new_token接口未返回响应");
        }

        Object code = respBody.get("code");
        if (code != null && !Objects.equals(code, 0) && !"0".equals(code.toString())) {
            throw new RuntimeException("获取token失败: " + respBody.get("message"));
        }

        Object data = respBody.get("data");
        if (data == null) {
            throw new RuntimeException("new_token接口返回data为空: " + respBody);
        }

        // data 可能直接是 token 字符串，也可能是包含 token 的 Map
        if (data instanceof String) {
            return (String) data;
        } else if (data instanceof Map) {
            Map<String, Object> dataMap = (Map<String, Object>) data;
            Object token = dataMap.get("token");
            if (token != null) return token.toString();
        }

        throw new RuntimeException("无法从new_token响应中提取token: " + data);
    }

    /**
     * Step 3: 发送消息到Agent（流式读取，简单文本收集）
     * POST /v1/api/chat (stream=true)
     * 使用 HttpURLConnection 流式读取 SemiMind SSE 响应，仅累积文本内容。
     * 不做结构化解析（无 tool_use 结构、无图片映射等），评测仅需纯文本用于评分。
     */
    private Map<String, Object> chat(String envUrl, String token, String question) {
        try {
            URL url = new URL("http://" + envUrl + "/v1/api/chat");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("platform", "Semi-Mind");
            conn.setRequestProperty("traceId", UUID.randomUUID().toString().replace("-", ""));

            String chatId = UUID.randomUUID().toString().replace("-", "");
            Map<String, Object> body = new HashMap<>();
            body.put("chat_id", chatId);
            body.put("prompt", question);
            body.put("file_ids", new ArrayList<>());

            byte[] bodyBytes = objectMapper.writeValueAsBytes(body);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(bodyBytes);
                out.flush();
            }

            log.info("chat()发送请求: chatId={}, envUrl={}", chatId, envUrl);
            int responseCode = conn.getResponseCode();
            log.info("chat()响应: chatId={}, code={}, contentType={}", chatId, responseCode, conn.getContentType());
            if (responseCode != 200) {
                InputStream errStream = conn.getErrorStream();
                String errBody = errStream != null
                        ? new String(errStream.readAllBytes(), StandardCharsets.UTF_8) : "Unknown error";
                throw new RuntimeException("Chat接口返回HTTP " + responseCode + ": "
                        + (errBody.length() > 200 ? errBody.substring(0, 200) + "..." : errBody));
            }

            // 收集结构化数据（text + tool_use），用于前端完整展示
            List<Map<String, Object>> parts = new ArrayList<>();
            StringBuilder textContent = new StringBuilder();
            int lineCount = 0;

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    lineCount++;

                    String data;
                    if (line.startsWith("data:")) {
                        data = line.substring(5).trim();
                    } else if (line.startsWith("{") || line.startsWith("[")) {
                        data = line;
                    } else {
                        continue;
                    }

                    // 终止标记
                    if ("[DONE]".equals(data) || "true".equals(data)) break;
                    if (isEndMarker(data)) break;

                    collectStructured(data, parts, textContent);
                }
            } finally {
                conn.disconnect();
            }

            // 构建结构化JSON：合并连续文本 chunk 为单个 part
            // 原始 parts 中每个 SSE chunk 都是一个独立 text part，需要合并
            List<Map<String, Object>> mergedParts = new ArrayList<>();
            StringBuilder currentText = new StringBuilder();
            for (Map<String, Object> part : parts) {
                String partType = (String) part.get("type");
                if ("tool_use".equals(partType) || "file".equals(partType) || "reference".equals(partType)) {
                    // 遇到非文本 part，先把累积的文本 flush
                    if (currentText.length() > 0) {
                        mergedParts.add(Map.of("type", "text", "content", currentText.toString()));
                        currentText.setLength(0);
                    }
                    mergedParts.add(part);
                } else if ("text".equals(partType)) {
                    currentText.append(part.getOrDefault("content", ""));
                }
            }
            // flush 剩余文本
            if (currentText.length() > 0) {
                mergedParts.add(Map.of("type", "text", "content", currentText.toString()));
            }

            // 从 reference 类型的 parts 中提取 elements_url_mapping 构建 imgMap
            Map<String, String> imgMap = new HashMap<>();
            for (Map<String, Object> part : parts) {
                if ("reference".equals(part.get("type")) && part.get("data") instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> refData = (Map<String, Object>) part.get("data");
                    Object chunksObj = refData.get("chunks");
                    if (chunksObj instanceof List) {
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> chunks = (List<Map<String, Object>>) chunksObj;
                        for (Map<String, Object> chunk : chunks) {
                            Object mappingObj = chunk.get("elements_url_mapping");
                            if (!(mappingObj instanceof Map)) continue;
                            @SuppressWarnings("unchecked")
                            Map<String, String> mapping = (Map<String, String>) mappingObj;
                            for (Map.Entry<String, String> entry : mapping.entrySet()) {
                                String mKey = entry.getKey();
                                String mUrl = entry.getValue();
                                if (mUrl == null) continue;
                                int dashIdx = mUrl.indexOf('-');
                                if (dashIdx > 0) {
                                    String bucket = mUrl.substring(0, dashIdx);
                                    String fname = mUrl.substring(dashIdx + 1);
                                    imgMap.put("MINIO-" + mKey, bucket + "-ragimage/" + fname);
                                }
                            }
                        }
                    }
                }
            }

            String answer;
            boolean hasStructured = mergedParts.stream().anyMatch(p -> {
                String t = (String) p.get("type");
                return "tool_use".equals(t) || "file".equals(t) || "reference".equals(t);
            });
            if (hasStructured) {
                Map<String, Object> structured = new HashMap<>();
                structured.put("textContent", textContent.toString());
                structured.put("parts", mergedParts);
                answer = objectMapper.writeValueAsString(structured);
            } else {
                // 纯文本回答，直接返回合并后的文本
                answer = textContent.toString();
            }

            log.info("chat()完成: chatId={}, 共{}行, rawParts={}, mergedParts={}, 文本长度={}, imgMap数量={}",
                    chatId, lineCount, parts.size(), mergedParts.size(), textContent.length(), imgMap.size());

            Map<String, Object> chatResult = new HashMap<>();
            chatResult.put("answer", answer);
            if (!imgMap.isEmpty()) {
                chatResult.put("imgMap", imgMap);
            }
            return chatResult;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Chat请求失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从 SSE 数据中收集结构化内容（text + tool_use），保持原始顺序
     */
    @SuppressWarnings("unchecked")
    private void collectStructured(String data, List<Map<String, Object>> parts, StringBuilder textContent) {
        try {
            Object parsed = objectMapper.readValue(data, Object.class);

            // NDJSON 双重编码：{data: "stringified JSON"} → 解包后递归
            if (parsed instanceof Map) {
                Map<String, Object> map = (Map<String, Object>) parsed;
                Object inner = map.get("data");

                if (inner instanceof Boolean) return;
                if (inner instanceof String) {
                    String innerStr = ((String) inner).trim();
                    if ("true".equals(innerStr) || "[DONE]".equals(innerStr)) return;
                    collectStructured(innerStr, parts, textContent);
                    return;
                }

                // SSE 流式单对象：{data: {type: "text/tool_use", md_content: ...}}
                if (inner instanceof Map) {
                    Map<String, Object> innerMap = (Map<String, Object>) inner;
                    if (innerMap.containsKey("type")) {
                        addPart(innerMap, parts, textContent);
                        return;
                    }
                    // 旧格式：{data: {answer: "..."}}
                    Object answer = innerMap.get("answer");
                    if (answer != null && !answer.toString().isEmpty()) {
                        textContent.append(answer.toString());
                        parts.add(Map.of("type", "text", "content", answer.toString()));
                    }
                    return;
                }

                // {data: [...]}
                if (inner instanceof List) {
                    for (Object item : (List<?>) inner) {
                        if (item instanceof Map) addPart((Map<String, Object>) item, parts, textContent);
                    }
                    return;
                }
            }

            // 裸数组 [{type, md_content}]
            if (parsed instanceof List) {
                for (Object item : (List<?>) parsed) {
                    if (item instanceof Map) addPart((Map<String, Object>) item, parts, textContent);
                }
            }
        } catch (Exception e) {
            // 非 JSON，当纯文本
            textContent.append(data);
            parts.add(Map.of("type", "text", "content", data));
        }
    }

    /** 将单个 SemiMind data item 添加到 parts 列表 */
    @SuppressWarnings("unchecked")
    private void addPart(Map<String, Object> item, List<Map<String, Object>> parts, StringBuilder textContent) {
        String type = item.get("type") != null ? item.get("type").toString() : "";
        Object mdContent = item.get("md_content");

        if ("text".equals(type) && mdContent != null) {
            String text = mdContent.toString();
            textContent.append(text);
            parts.add(Map.of("type", "text", "content", text));
        } else if ("tool_use".equals(type) && mdContent instanceof Map) {
            Map<String, Object> mc = (Map<String, Object>) mdContent;
            Map<String, Object> toolPart = new HashMap<>();
            toolPart.put("type", "tool_use");
            toolPart.put("toolName", mc.getOrDefault("tool_name", "unknown"));
            toolPart.put("status", mc.getOrDefault("status", ""));
            toolPart.put("useTarget", mc.getOrDefault("use_target", ""));
            Object toolRes = mc.get("tool_res");
            if (toolRes instanceof Map && ((Map<?, ?>) toolRes).get("content") != null) {
                toolPart.put("content", ((Map<?, ?>) toolRes).get("content").toString());
            } else if (toolRes instanceof String) {
                toolPart.put("content", toolRes);
            } else {
                toolPart.put("content", "");
            }
            parts.add(toolPart);
        } else if ("file".equals(type)) {
            // 文件附件：数据在 content 或 md_content 字段
            Object fileData = item.get("content");
            if (fileData == null) fileData = mdContent;
            if (fileData instanceof Map) {
                Map<String, Object> fc = (Map<String, Object>) fileData;
                Map<String, Object> filePart = new HashMap<>();
                filePart.put("type", "file");
                filePart.put("fileName", fc.getOrDefault("file_name", "unknown"));
                filePart.put("fileType", fc.getOrDefault("file_type", ""));
                filePart.put("fileDownloadUrl", fc.getOrDefault("file_download_url", ""));
                filePart.put("docId", fc.getOrDefault("doc_id", ""));
                parts.add(filePart);
            }
        } else if ("reference".equals(type) && mdContent != null) {
            // 引用数据（含图片 img_id）
            Map<String, Object> refPart = new HashMap<>();
            refPart.put("type", "reference");
            refPart.put("data", mdContent);
            parts.add(refPart);
        } else if (mdContent != null) {
            String text = mdContent.toString();
            textContent.append(text);
            parts.add(Map.of("type", "text", "content", text));
        }
    }

    /**
     * 从单条 SSE 数据中提取文本内容（简单累积，不做结构化解析）
     */
    @SuppressWarnings("unchecked")
    private void collectText(String data, StringBuilder accumulated) {
        try {
            Object parsed = objectMapper.readValue(data, Object.class);

            // NDJSON 双重编码：{data: "stringified JSON"} → 解包后递归
            if (parsed instanceof Map) {
                Map<String, Object> map = (Map<String, Object>) parsed;
                Object inner = map.get("data");

                if (inner instanceof Boolean) return;
                if (inner instanceof String) {
                    String innerStr = ((String) inner).trim();
                    if ("true".equals(innerStr) || "[DONE]".equals(innerStr)) return;
                    collectText(innerStr, accumulated);
                    return;
                }

                // 格式A: {data: {answer: "文本"}} 或 SSE流式单对象 {data: {type: "text", md_content: "..."}}
                if (inner instanceof Map) {
                    Map<String, Object> innerMap = (Map<String, Object>) inner;
                    // SSE 流式格式：单个 {type, md_content} 对象
                    if (innerMap.containsKey("type")) {
                        String type = innerMap.get("type") != null ? innerMap.get("type").toString() : "";
                        if ("text".equals(type) && innerMap.get("md_content") != null) {
                            accumulated.append(innerMap.get("md_content").toString());
                        }
                        return;
                    }
                    // 旧格式: {answer: "文本"}
                    Object answer = innerMap.get("answer");
                    if (answer != null && !answer.toString().isEmpty()) {
                        accumulated.append(answer.toString());
                    }
                    return;
                }

                // 格式B: {data: [...]}
                if (inner instanceof List) {
                    collectFromDataList((List<Map<String, Object>>) inner, accumulated);
                    return;
                }
            }

            // 格式B: 裸数组 [{type:"text", md_content:"..."}]
            if (parsed instanceof List) {
                collectFromDataList((List<Map<String, Object>>) parsed, accumulated);
            }
        } catch (Exception e) {
            // 非 JSON 数据，当作纯文本追加
            accumulated.append(data);
        }
    }

    /** 检测 SSE 流终止标记：{data: true} 或 {retcode: 0, data: true} */
    private boolean isEndMarker(String data) {
        if (!data.startsWith("{")) return false;
        try {
            Object parsed = objectMapper.readValue(data, Object.class);
            if (parsed instanceof Map) {
                Object d = ((Map<?, ?>) parsed).get("data");
                return d instanceof Boolean;
            }
        } catch (Exception ignored) {}
        return false;
    }

    /**
     * 从 SemiMind data 数组中提取文本内容（仅收集 text 类型，忽略 tool_use/reference）
     */
    private void collectFromDataList(List<Map<String, Object>> dataList, StringBuilder accumulated) {
        for (Map<String, Object> item : dataList) {
            if (item == null) continue;
            String type = item.get("type") != null ? item.get("type").toString() : "";
            Object mdContent = item.get("md_content");
            if ("text".equals(type) && mdContent != null) {
                accumulated.append(mdContent.toString());
            } else if (mdContent != null && !"reference".equals(type) && !"tool_use".equals(type)) {
                accumulated.append(mdContent.toString());
            }
        }
    }

    // ==================== 工具方法 ====================

    private EnvDbConfig getConfig(String envUrl) {
        EnvDbConfig config = envDbProperties.getByFrontendUrl(envUrl);
        if (config == null) {
            throw new IllegalArgumentException("未找到环境URL对应的配置: " + envUrl
                    + "，请检查 application.properties 中的 env.db.*.frontend-url 配置");
        }
        return config;
    }

    /**
     * 构建调用 SemiMind 平台所需的请求头（publish / new_token 等接口）
     * 使用传入的 authorization 和 apiUsername 参数
     */
    private HttpHeaders buildAuthHeaders(String authorization, String apiUsername) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set("Authorization", authorization);
        headers.set("username", apiUsername);
        headers.set("traceId", UUID.randomUUID().toString().replace("-", ""));
        return headers;
    }
}
