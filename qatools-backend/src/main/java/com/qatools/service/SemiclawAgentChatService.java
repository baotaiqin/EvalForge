package com.qatools.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * SemiClaw Agent 文本聊天服务。
 *
 * 当前实现保留 SemiClaw 纯文本评测链路：login -> create session -> WebSocket -> wait done。
 * 保留 chatWithAgentAsync(...) 文本入口，避免影响 EvaluationService 现有调用。
 * 保留 chatWithAgentMultimodalAsync(...) 签名，但将图片/文件请求委托给独立的多模态服务，避免影响 MultimodalEvaluationService。
 * SemiClaw 图片/文件批量上传和官方多模态 payload 已迁到 SemiclawMultimodalChatService。
 */
@Service
public class SemiclawAgentChatService {

    private static final Logger log = LoggerFactory.getLogger(SemiclawAgentChatService.class);

    private static final long CONNECT_TIMEOUT_SECONDS = 20;
    private static final long SESSION_TIMEOUT_SECONDS = 30;
    private static final long CHAT_TIMEOUT_SECONDS = 180;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService chatExecutor = Executors.newFixedThreadPool(4);
    private final HttpClient httpClient;

    @Autowired
    private SemiclawApiService semiclawApiService;

    @Autowired
    private SemiclawMultimodalChatService semiclawMultimodalChatService;

    /**
     * SemiClaw WS 鉴权模式。
     *
     * ticket：新协议，先 POST /api/ws/ticket 换取短票据，再连接 WS (?ticket=xxx&session_id=xxx)。
     * token：旧协议，直接携带 access_token 连接 WS (?token=xxx&session_id=xxx&tenant_id=xxx)。
     * 保留 token 分支，便于兼容尚未升级到 ticket 协议的 SemiClaw 环境。
     */
    @Value("${semiclaw.ws.auth-mode:ticket}")
    private String wsAuthMode;

    public SemiclawAgentChatService(
            @Value("${semiclaw.ssl.trust-all:false}") boolean trustAllSsl
    ) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                .followRedirects(HttpClient.Redirect.NEVER);

        if (trustAllSsl) {
            SSLContext sslContext = buildTrustAllSslContext();
            SSLParameters sslParameters = new SSLParameters();
            // 关闭 HttpClient 对内部自签名证书的主机名校验，避免 WSS/HTTPS 证书 CN/SAN 不匹配失败。
            sslParameters.setEndpointIdentificationAlgorithm("");
            builder.sslContext(sslContext);
            builder.sslParameters(sslParameters);
            log.warn("SemiClaw HTTPS/WSS 证书校验已配置为跳过，仅建议在内网 SIT/临时环境使用");
        } else {
            log.info("SemiClaw HTTPS/WSS 证书校验使用 Java 默认信任链");
        }

        this.httpClient = builder.build();
    }

    /**
     * 异步调用 SemiClaw Agent 纯文本问答。
     *
     * 返回结构兼容 EvaluationService / MultimodalEvaluationService 现有逻辑：
     * {
     *   "answer": "...",
     *   "responseTime": 1234
     * }
     *
     * tenantId / username / password 允许为空：
     * 1. 如果三项齐全，SemiclawApiService 会按个人账号方式登录。
     * 2. 如果为空或不完整，SemiclawApiService 会按 baseUrl 匹配环境中的服务账号配置。
     */
    public CompletableFuture<Map<String, Object>> chatWithAgentAsync(String baseUrl,
                                                                      String tenantId,
                                                                      String agentId,
                                                                      String question,
                                                                      String username,
                                                                      String password,
                                                                      String wsCookie) {
        long start = System.currentTimeMillis();

        return CompletableFuture
                .supplyAsync(() -> doChat(baseUrl, tenantId, agentId, question, username, password, wsCookie, start), chatExecutor)
                .exceptionally(e -> buildFailureResult(e, start));
    }

    /**
     * 兼容现有 MultimodalEvaluationService 的 SemiClaw 多模态入口。
     *
     * 注意：这里不再保留 image URL / base64 上传逻辑。
     * 真正的 SemiClaw 多模态链路已经迁到 SemiclawMultimodalChatService：
     * login -> create session -> /api/chat/upload/batch -> WebSocket 官方 payload。
     *
     * 这样做的好处：
     * 1. MultimodalEvaluationService 原有调用不用调整。
     * 2. SemiClaw 图片回答不再混回 URL/base64 方案。
     * 3. 本类职责收敛为纯文本服务。
     */
    public CompletableFuture<Map<String, Object>> chatWithAgentMultimodalAsync(String baseUrl,
                                                                                String tenantId,
                                                                                String agentId,
                                                                                String question,
                                                                                List<String> questionImages,
                                                                                List<String> questionFiles,
                                                                                String username,
                                                                                String password) {
        return semiclawMultimodalChatService.chatWithAgentMultimodalAsync(
                baseUrl,
                tenantId,
                agentId,
                question,
                questionImages,
                questionFiles,
                username,
                password,
                null
        );
    }

    private Map<String, Object> doChat(String baseUrl,
                                       String tenantId,
                                       String agentId,
                                       String question,
                                       String username,
                                       String password,
                                       String wsCookie,
                                       long startTime) {
        validateRequired("semiclaw baseUrl", baseUrl);
        validateRequired("semiclaw agentId", agentId);
        validateRequired("semiclaw question", question);

        Map<String, String> loginContext = semiclawApiService.resolveLoginContext(
                baseUrl,
                tenantId,
                username,
                password
        );

        String normalizedBaseUrl = firstNonBlank(loginContext.get("baseUrl"), normalizeBaseUrl(baseUrl));
        String resolvedTenantId = loginContext.get("tenantId");
        String resolvedUsername = loginContext.get("username");
        String credentialSource = loginContext.get("source");
        String serviceAccountName = loginContext.get("serviceAccountName");

        validateRequired("semiclaw tenantId", resolvedTenantId);
        validateRequired("semiclaw username", resolvedUsername);

        log.info("SemiClaw 调用开始 baseUrl={}, tenantId={}, agentId={}, credentialSource={}, serviceAccount={}",
                sanitizeUrl(normalizedBaseUrl),
                maskValue(resolvedTenantId),
                agentId,
                credentialSource,
                isBlank(serviceAccountName) ? "" : serviceAccountName);

        Map<String, String> loginResult = semiclawApiService.login(baseUrl, tenantId, username, password);
        String accessToken = loginResult.get("accessToken");
        if (isBlank(accessToken)) {
            throw new RuntimeException("SemiClaw 登录成功但 accessToken 为空");
        }

        SessionContext sessionContext = createSessionWithTokenRefresh(
                normalizedBaseUrl,
                resolvedTenantId,
                agentId,
                resolvedUsername,
                password,
                accessToken
        );

        String answer = chatByWebSocket(
                normalizedBaseUrl,
                resolvedTenantId,
                agentId,
                sessionContext.sessionId,
                sessionContext.accessToken,
                question,
                wsCookie
        );

        long responseTime = System.currentTimeMillis() - startTime;
        Map<String, Object> result = new HashMap<>();
        result.put("answer", answer);
        result.put("responseTime", responseTime);

        log.info("SemiClaw 调用完成 agentId={}, sessionId={}, responseTime={}ms",
                agentId, maskValue(sessionContext.sessionId), responseTime);
        return result;
    }

    /**
     * 创建 session。
     * 如果创建 session 返回 401 / AUTH_KICKED_OUT，说明当前 token 可能已被踢下线。
     * 这里会清理 token 缓存，重新登录一次，并重建 session，避免整题直接失败。
     */
    private SessionContext createSessionWithTokenRefresh(String normalizedBaseUrl,
                                                          String tenantId,
                                                          String agentId,
                                                          String username,
                                                          String password,
                                                          String accessToken) {
        RuntimeException firstException;
        try {
            String sessionId = createSession(normalizedBaseUrl, agentId, accessToken);
            return new SessionContext(sessionId, accessToken);
        } catch (RuntimeException e) {
            if (!isTokenInvalidSessionError(e)) {
                throw e;
            }
            firstException = e;
        }

        log.warn("SemiClaw 创建 session 时 token 可能失效，清理 token 后重试一次 baseUrl={}, tenantId={}, username={}, agentId={}",
                sanitizeUrl(normalizedBaseUrl), maskValue(tenantId), maskUsername(username), agentId);

        semiclawApiService.invalidateToken(normalizedBaseUrl, tenantId, username);
        Map<String, String> retryLoginResult = semiclawApiService.login(
                normalizedBaseUrl,
                tenantId,
                username,
                password
        );

        String retryAccessToken = retryLoginResult.get("accessToken");
        if (isBlank(retryAccessToken)) {
            throw new RuntimeException("SemiClaw 重新登录成功但 accessToken 为空");
        }

        try {
            String sessionId = createSession(normalizedBaseUrl, agentId, retryAccessToken);
            return new SessionContext(sessionId, retryAccessToken);
        } catch (RuntimeException retryException) {
            retryException.addSuppressed(firstException);
            throw retryException;
        }
    }

    /**
     * 创建 SemiClaw session。
     *
     * 接口：POST /api/agents/{agentId}/sessions
     * 如果服务端返回 307/308 重定向，会手动跟随，避免 HttpClient 默认策略导致失败。
     */
    private String createSession(String normalizedBaseUrl, String agentId, String accessToken) {
        URI uri = buildSessionUri(normalizedBaseUrl, agentId);
        String body = "{}";

        try {
            HttpResponse<String> response = postJsonWithRedirect(uri, accessToken, body, 3);
            int status = response.statusCode();
            String responseBody = response.body();

            log.info("SemiClaw 创建 session 响应 status={}, bodyLength={}",
                    status, responseBody == null ? 0 : responseBody.length());
            log.debug("SemiClaw 创建 session 响应体摘要: {}", summarizeBody(responseBody));

            if (status < 200 || status >= 300) {
                throw new RuntimeException("SemiClaw 创建 session 失败: status=" + status
                        + ", bodySummary=" + summarizeBody(responseBody));
            }

            if (isBlank(responseBody)) {
                throw new RuntimeException("SemiClaw 创建 session 响应体为空");
            }

            Map<?, ?> parsed = objectMapper.readValue(responseBody, Map.class);
            String sessionId = firstNonBlank(
                    stringValue(parsed.get("id")),
                    stringValue(parsed.get("session_id")),
                    stringValue(parsed.get("sessionId"))
            );
            if (isBlank(sessionId)) {
                throw new RuntimeException("无法从 SemiClaw session 响应中提取 sessionId");
            }
            return sessionId;
        } catch (Exception e) {
            throw new RuntimeException("SemiClaw 创建 session 请求失败: " + e.getMessage(), e);
        }
    }

    private HttpResponse<String> postJsonWithRedirect(URI initialUri,
                                                       String accessToken,
                                                       String body,
                                                       int maxRedirects) throws Exception {
        URI currentUri = initialUri;

        for (int i = 0; i <= maxRedirects; i++) {
            HttpRequest request = HttpRequest.newBuilder(currentUri)
                    .timeout(Duration.ofSeconds(SESSION_TIMEOUT_SECONDS))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            log.info("调用 SemiClaw 创建 session 接口 url={}", sanitizeUrl(currentUri.toString()));
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (!isRedirect(response.statusCode())) {
                return response;
            }

            String location = response.headers().firstValue("location").orElse(null);
            log.warn("SemiClaw 创建 session 返回重定向 status={}, location={}",
                    response.statusCode(), sanitizeUrl(location));
            if (isBlank(location)) {
                return response;
            }

            currentUri = normalizeRedirectUri(initialUri, location);
        }

        throw new RuntimeException("SemiClaw 创建 session 重定向次数过多: " + sanitizeUrl(initialUri.toString()));
    }

    /**
     * 连接 SemiClaw WebSocket 并等待 done.content。
     */
    private String chatByWebSocket(String normalizedBaseUrl,
                                   String tenantId,
                                   String agentId,
                                   String sessionId,
                                   String accessToken,
                                   String question,
                                   String wsCookie) {
        WebSocket webSocket = null;

        try {
            URI wsUri = "ticket".equalsIgnoreCase(wsAuthMode)
                    ? buildWebSocketUriByTicket(
                            normalizedBaseUrl,
                            agentId,
                            sessionId,
                            semiclawApiService.getWsTicket(normalizedBaseUrl, accessToken)
                    )
                    : buildWebSocketUri(normalizedBaseUrl, tenantId, agentId, sessionId, accessToken);

            SemiclawWebSocketListener listener = new SemiclawWebSocketListener(objectMapper);
            log.info("连接 SemiClaw WebSocket url={}, sessionId={}, hasCookie={}",
                    sanitizeUrl(wsUri.toString()), maskValue(sessionId), !isBlank(wsCookie));

            WebSocket.Builder wsBuilder = httpClient.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                    .header("Origin", normalizedBaseUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36");

            /*
             * SemiClaw WS 网关鉴权临时方案（2026-08-11）：
             * 网关除校验 ticket 外，还依赖 SemiMind（8111 端口）签发的会话 Cookie，
             * 该 Cookie 由用户在前端环境配置中手动维护，过期后可重新登录更新。
             * 2026-08-12 补齐：单靠 Cookie 仍持续 403（身份与请求一致），追加 Origin/User-Agent
             * 头以对齐浏览器真实请求，排查记录 2.7 节的成功复现方案是 Cookie+Origin+UA 同时具备。
             */
            if (!isBlank(wsCookie)) {
                wsBuilder = wsBuilder.header("Cookie", wsCookie);
            }

            webSocket = wsBuilder
                    .buildAsync(wsUri, listener)
                    .get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("content", question);
            payload.put("display_content", question);
            payload.put("file_name", "");

            String message = objectMapper.writeValueAsString(payload);
            log.info("发送 SemiClaw 问题 agentId={}, sessionId={}, questionLength={}",
                    agentId, maskValue(sessionId), question.length());

            webSocket.sendText(message, true).get(10, TimeUnit.SECONDS);
            return listener.waitForAnswer(CHAT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("SemiClaw WebSocket 聊天失败: " + e.getMessage(), e);
        } finally {
            if (webSocket != null) {
                try {
                    webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
                } catch (Exception ignored) {
                    // WebSocket 可能已由服务端关闭。
                }
            }
        }
    }

    private URI buildSessionUri(String normalizedBaseUrl, String agentId) {
        return URI.create(normalizedBaseUrl + "/api/agents/" + encode(agentId) + "/sessions");
    }

    private URI buildWebSocketUri(String normalizedBaseUrl,
                                  String tenantId,
                                  String agentId,
                                  String sessionId,
                                  String accessToken) {
        String wsBaseUrl;
        if (normalizedBaseUrl.startsWith("https://")) {
            wsBaseUrl = "wss://" + normalizedBaseUrl.substring("https://".length());
        } else if (normalizedBaseUrl.startsWith("http://")) {
            wsBaseUrl = "ws://" + normalizedBaseUrl.substring("http://".length());
        } else {
            wsBaseUrl = "ws://" + normalizedBaseUrl;
        }

        String url = wsBaseUrl
                + "/ws/chat/" + encode(agentId)
                + "?token=" + encode(accessToken)
                + "&session_id=" + encode(sessionId)
                + "&tenant_id=" + encode(tenantId);
        return URI.create(url);
    }

    /**
     * SemiClaw 新协议：用 ticket + session_id 连接，不再传 token / tenant_id。
     */
    private URI buildWebSocketUriByTicket(String normalizedBaseUrl,
                                          String agentId,
                                          String sessionId,
                                          String ticket) {
        String wsBaseUrl;
        if (normalizedBaseUrl.startsWith("https://")) {
            wsBaseUrl = "wss://" + normalizedBaseUrl.substring("https://".length());
        } else if (normalizedBaseUrl.startsWith("http://")) {
            wsBaseUrl = "ws://" + normalizedBaseUrl.substring("http://".length());
        } else {
            wsBaseUrl = "ws://" + normalizedBaseUrl;
        }

        String url = wsBaseUrl
                + "/ws/chat/" + encode(agentId)
                + "?ticket=" + encode(ticket)
                + "&session_id=" + encode(sessionId);
        return URI.create(url);
    }

    private URI normalizeRedirectUri(URI originalUri, String location) {
        URI locationUri = URI.create(location);
        if (locationUri.isAbsolute()) {
            return locationUri;
        }

        String origin = originalUri.getScheme() + "://" + originalUri.getAuthority();
        if (location.startsWith("/")) {
            return URI.create(origin + location);
        }

        String originalPath = originalUri.getPath();
        int lastSlash = originalPath.lastIndexOf('/');
        String basePath = lastSlash >= 0 ? originalPath.substring(0, lastSlash + 1) : "/";
        return URI.create(origin + basePath + location);
    }

    private static class SemiclawWebSocketListener implements WebSocket.Listener {

        private final ObjectMapper objectMapper;
        private final CompletableFuture<String> answerFuture = new CompletableFuture<>();
        private final StringBuilder chunkBuffer = new StringBuilder();
        private final StringBuilder partialBuffer = new StringBuilder();

        private SemiclawWebSocketListener(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            try {
                String fullMessage = null;
                synchronized (partialBuffer) {
                    partialBuffer.append(data);
                    if (last) {
                        fullMessage = partialBuffer.toString();
                        partialBuffer.setLength(0);
                    }
                }

                if (fullMessage != null) {
                    handleMessage(fullMessage);
                }
            } catch (Exception e) {
                answerFuture.completeExceptionally(e);
            } finally {
                webSocket.request(1);
            }

            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (!answerFuture.isDone()) {
                String fallback = chunkBuffer.toString();
                if (isBlank(fallback)) {
                    answerFuture.completeExceptionally(new RuntimeException(
                            "SemiClaw WebSocket 已关闭但未收到 done: status=" + statusCode + ", reason=" + reason));
                } else {
                    answerFuture.complete(fallback);
                }
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (!answerFuture.isDone()) {
                answerFuture.completeExceptionally(error);
            }
        }

        private void handleMessage(String message) {
            if (isBlank(message)) {
                return;
            }

            try {
                Map<?, ?> json = objectMapper.readValue(message, Map.class);
                String type = firstNonBlank(
                        stringValue(json.get("type")),
                        stringValue(json.get("event"))
                );
                String content = firstNonBlank(
                        stringValue(json.get("content")),
                        stringValue(json.get("answer")),
                        stringValue(json.get("message"))
                );

                if ("chunk".equalsIgnoreCase(type)) {
                    if (content != null) {
                        chunkBuffer.append(content);
                    }
                    return;
                }

                if ("result".equalsIgnoreCase(type) || "done".equalsIgnoreCase(type)) {
                    // result 是正式最终结果，优先使用 result.content；兼容仅以 done chunk 结束的实现。
                    String finalAnswer = firstNonBlank(content, chunkBuffer.toString());
                    answerFuture.complete(finalAnswer == null ? "" : finalAnswer);
                    return;
                }

                // 部分 SemiClaw 实现不带 type，但会直接发送 content。
                if (!isBlank(content) && !answerFuture.isDone()) {
                    chunkBuffer.append(content);
                }
            } catch (Exception e) {
                // 非 JSON 文本按 chunk 处理，兼容旧服务端实现。
                if (!answerFuture.isDone()) {
                    chunkBuffer.append(message);
                }
            }
        }

        private String waitForAnswer(long timeout, TimeUnit unit) throws Exception {
            return answerFuture.get(timeout, unit);
        }
    }

    private Map<String, Object> buildFailureResult(Throwable throwable, long startTime) {
        Throwable root = unwrap(throwable);
        String message = root.getMessage();
        if (isBlank(message)) {
            message = root.getClass().getSimpleName();
        }

        long responseTime = System.currentTimeMillis() - startTime;
        log.warn("SemiClaw Agent 调用失败: {}", message, root);

        Map<String, Object> result = new HashMap<>();
        result.put("answer", "调用 SemiClaw 失败: " + message);
        result.put("responseTime", responseTime);
        return result;
    }

    private Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current.getCause() != null
                    && (current instanceof CompletionException
                    || current instanceof java.util.concurrent.ExecutionException
                    || current instanceof RuntimeException)) {
                current = current.getCause();
            } else {
                break;
            }
        }
        return current == null ? throwable : current;
    }

    private boolean isTokenInvalidSessionError(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (!isBlank(message)) {
                String lowerMessage = message.toLowerCase();
                if (lowerMessage.contains("status=401")
                        || lowerMessage.contains("auth_kicked_out")
                        || lowerMessage.contains("account logged in elsewhere")
                        || lowerMessage.contains("unauthorized")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private void validateRequired(String name, String value) {
        if (isBlank(value)) {
            throw new IllegalArgumentException(name + "不能为空");
        }
    }

    private String normalizeBaseUrl(String baseUrl) {
        String url = baseUrl == null ? "" : baseUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }

        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        return "https://" + url;
    }

    private String sanitizeUrl(String url) {
        if (url == null) {
            return null;
        }

        return url
                .replaceAll("(?i)([?&]token=)[^&]*", "$1***")
                .replaceAll("(?i)([?&]ticket=)[^&]*", "$1***")
                .replaceAll("(?i)([?&]session_id=)[^&]*", "$1***")
                .replaceAll("(?i)([?&]tenant_id=)[^&]*", "$1***")
                .replaceAll("(?i)([?&]password=)[^&]*", "$1***")
                .replaceAll("(?i)([?&]authorization=)[^&]*", "$1***");
    }

    private String summarizeBody(String body) {
        if (isBlank(body)) {
            return "";
        }

        String sanitized = body
                .replaceAll("(?i)(\\\"access_token\\\"\\s*:\\s*\\\")[^\\\"]*(\\\")", "$1***$2")
                .replaceAll("(?i)(\\\"accessToken\\\"\\s*:\\s*\\\")[^\\\"]*(\\\")", "$1***$2")
                .replaceAll("(?i)(\\\"token\\\"\\s*:\\s*\\\")[^\\\"]*(\\\")", "$1***$2")
                .replaceAll("(?i)(\\\"password\\\"\\s*:\\s*\\\")[^\\\"]*(\\\")", "$1***$2")
                .replaceAll("(?i)(\\\"authorization\\\"\\s*:\\s*\\\")[^\\\"]*(\\\")", "$1***$2")
                .replaceAll("(?i)(Bearer\\s+)[A-Za-z0-9._~+/=-]+", "$1***");

        if (sanitized.length() > 300) {
            return sanitized.substring(0, 300) + "...";
        }
        return sanitized;
    }

    private String maskValue(String value) {
        if (isBlank(value)) {
            return "";
        }

        String trimmed = value.trim();
        if (trimmed.length() <= 2) {
            return "*";
        }
        if (trimmed.length() <= 6) {
            return trimmed.charAt(0) + "***" + trimmed.charAt(trimmed.length() - 1);
        }
        return trimmed.substring(0, 3) + "***" + trimmed.substring(trimmed.length() - 3);
    }

    private String maskUsername(String username) {
        if (isBlank(username)) {
            return "";
        }

        String value = username.trim();
        int atIndex = value.indexOf('@');
        if (atIndex > 1) {
            String prefix = value.substring(0, atIndex);
            String domain = value.substring(atIndex + 1);
            return maskValue(prefix) + "@" + domain;
        }
        return maskValue(value);
    }

    private static SSLContext buildTrustAllSslContext() {
        try {
            TrustManager[] trustManagers = new TrustManager[]{
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }
                    }
            };

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustManagers, new SecureRandom());
            return sslContext;
        } catch (Exception e) {
            throw new IllegalStateException("初始化 SemiClaw HTTPS/WSS 证书兼容配置失败", e);
        }
    }

    private static boolean isRedirect(int statusCode) {
        return statusCode == 301
                || statusCode == 302
                || statusCode == 303
                || statusCode == 307
                || statusCode == 308;
    }

    private static String encode(String value) {
        if (value == null) {
            return "";
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
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

    private static class SessionContext {
        private final String sessionId;
        private final String accessToken;

        private SessionContext(String sessionId, String accessToken) {
            this.sessionId = sessionId;
            this.accessToken = accessToken;
        }
    }

    @PreDestroy
    public void shutdown() {
        chatExecutor.shutdown();
    }
}
