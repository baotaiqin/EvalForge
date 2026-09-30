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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SemiClaw 多模态 Agent 聊天服务。
 *
 * 图片和文件统一先解析为字节资源，再通过 /api/chat/upload/batch 上传；
 * 随后将 SemiClaw 返回的文件引用放入 WebSocket 消息，等待最终答案。
 */
@Service
public class SemiclawMultimodalChatService {

    private static final Logger log = LoggerFactory.getLogger(SemiclawMultimodalChatService.class);
    private static final long CONNECT_TIMEOUT_SECONDS = 20;
    private static final long SESSION_TIMEOUT_SECONDS = 30;
    private static final long DEFAULT_CHAT_TIMEOUT_SECONDS = 600;
    private static final int INLINE_FILE_TEXT_MAX_CHARS = 50_000;
    private static final Pattern GENERATED_DOC_DOWNLOAD_LINK_PATTERN = Pattern.compile(
            "\\[[^\\]]*\\]\\((/[^)]+\\.(?:pptx?|html?))\\)",
            Pattern.CASE_INSENSITIVE);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService chatExecutor = Executors.newFixedThreadPool(4);
    private final HttpClient httpClient;

    @Autowired
    private SemiclawApiService semiclawApiService;

    @Autowired
    private MinioStorageService minioStorageService;

    /** 对外可访问的 QATools 根地址，用于将 /api/... 相对资源解析成可下载 URL。 */
    @Value("${qatools.public-base-url:}")
    private String qatoolsPublicBaseUrl;

    @Value("${server.port:8081}")
    private String serverPort;

    @Value("${semiclaw.multimodal.image-timeout-seconds:300}")
    private long imageChatTimeoutSeconds;

    @Value("${semiclaw.multimodal.file-timeout-seconds:1200}")
    private long fileChatTimeoutSeconds;

    @Value("${semiclaw.ws.auth-mode:ticket}")
    private String wsAuthMode;

    public SemiclawMultimodalChatService(
            @Value("${semiclaw.ssl.trust-all:false}") boolean trustAllSsl
    ) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                .followRedirects(HttpClient.Redirect.NEVER);

        if (trustAllSsl) {
            SSLContext sslContext = buildTrustAllSslContext();
            SSLParameters sslParameters = new SSLParameters();
            sslParameters.setEndpointIdentificationAlgorithm("");
            builder.sslContext(sslContext);
            builder.sslParameters(sslParameters);
            log.warn("SemiClaw HTTPS/WSS 证书校验配置为跳过，仅建议在内网 SIT/临时环境使用");
        } else {
            log.info("SemiClaw HTTPS/WSS 证书校验使用 Java 默认信任链");
        }
        this.httpClient = builder.build();
    }

    public CompletableFuture<Map<String, Object>> chatWithAgentMultimodalAsync(String baseUrl,
                                                                                String tenantId,
                                                                                String agentId,
                                                                                String question,
                                                                                List<String> questionImages,
                                                                                List<String> questionFiles,
                                                                                String username,
                                                                                String password,
                                                                                String expectedDocType) {
        return chatWithAgentMultimodalAsync(baseUrl, tenantId, agentId, question, questionImages,
                questionFiles, username, password, null, expectedDocType);
    }

    /**
     * 保留 evaluation 链路可传入的 WebSocket Cookie；不改变旧调用方的参数签名。
     */
    public CompletableFuture<Map<String, Object>> chatWithAgentMultimodalAsync(String baseUrl,
                                                                                String tenantId,
                                                                                String agentId,
                                                                                String question,
                                                                                List<String> questionImages,
                                                                                List<String> questionFiles,
                                                                                String username,
                                                                                String password,
                                                                                String wsCookie,
                                                                                String expectedDocType) {
        long start = System.currentTimeMillis();
        List<String> safeImages = normalizeStringList(questionImages);
        List<String> safeFiles = normalizeStringList(questionFiles);
        return CompletableFuture
                .supplyAsync(() -> doChatMultimodal(baseUrl, tenantId, agentId, question,
                        safeImages, safeFiles, username, password, wsCookie, expectedDocType, start), chatExecutor)
                .exceptionally(error -> buildFailureResult(error, start));
    }

    /**
     * 兼容 SemiclawAgentChatService 对早期多模态入口的委托。
     */
    public CompletableFuture<Map<String, Object>> chatWithAgentMultimodalAsync(String baseUrl,
                                                                                String tenantId,
                                                                                String agentId,
                                                                                String question,
                                                                                List<String> questionImages,
                                                                                List<String> questionFiles,
                                                                                String username,
                                                                                String password) {
        return chatWithAgentMultimodalAsync(baseUrl, tenantId, agentId, question, questionImages,
                questionFiles, username, password, null, null);
    }

    private Map<String, Object> doChatMultimodal(String baseUrl,
                                                  String tenantId,
                                                  String agentId,
                                                  String question,
                                                  List<String> questionImages,
                                                  List<String> questionFiles,
                                                  String username,
                                                  String password,
                                                  String wsCookie,
                                                  String expectedDocType,
                                                  long startTime) {
        validateRequired("semiclaw baseUrl", baseUrl);
        validateRequired("semiclaw agentId", agentId);

        List<MediaResource> resources = collectMediaResources(questionImages, questionFiles);
        if ((!questionImages.isEmpty() || !questionFiles.isEmpty()) && resources.isEmpty()) {
            throw new IllegalArgumentException("无法读取提供的图片或文件，请检查资源 URL、Base64 内容或本地路径");
        }

        String finalQuestion = normalizeMultimodalQuestion(question, questionImages, questionFiles);
        Map<String, String> loginContext = semiclawApiService.resolveLoginContext(baseUrl, tenantId, username, password);
        String normalizedBaseUrl = firstNonBlank(loginContext.get("baseUrl"), normalizeBaseUrl(baseUrl));
        String resolvedTenantId = loginContext.get("tenantId");
        String resolvedUsername = loginContext.get("username");
        String credentialSource = loginContext.get("source");
        String serviceAccountName = loginContext.get("serviceAccountName");
        validateRequired("semiclaw tenantId", resolvedTenantId);
        validateRequired("semiclaw username", resolvedUsername);

        log.info("SemiClaw 多模态调用开始 baseUrl={}, tenantId={}, agentId={}, credentialSource={}, serviceAccount={}, imageCount={}, fileCount={}",
                sanitizeUrl(normalizedBaseUrl), maskValue(resolvedTenantId), agentId, credentialSource,
                isBlank(serviceAccountName) ? "" : serviceAccountName, questionImages.size(), questionFiles.size());

        Map<String, String> loginResult = semiclawApiService.login(baseUrl, tenantId, username, password);
        String accessToken = loginResult.get("accessToken");
        if (isBlank(accessToken)) {
            throw new IllegalStateException("SemiClaw 登录成功但 accessToken 为空");
        }

        SessionContext session = createSessionWithTokenRefresh(normalizedBaseUrl, resolvedTenantId,
                agentId, resolvedUsername, password, accessToken);
        UploadBatchResult upload = uploadBatch(normalizedBaseUrl, agentId, session.sessionId,
                session.accessToken, resources);
        int expectedUploadCount = questionImages.size() + questionFiles.size();
        if (expectedUploadCount > 0 && upload.uploadedFiles.size() < expectedUploadCount) {
            throw new IllegalStateException("SemiClaw 多模态文件上传不完整：期望上传 "
                    + expectedUploadCount + " 个资源，实际成功 " + upload.uploadedFiles.size()
                    + " 个；uploadError=" + firstNonBlank(upload.uploadError, "无"));
        }

        WsChatResult wsResult = chatByWebSocketMultimodal(normalizedBaseUrl, resolvedTenantId, agentId,
                session.sessionId, session.accessToken, finalQuestion, questionImages, questionFiles,
                upload.uploadedFiles, upload.resources, wsCookie, expectedDocType);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("answer", wsResult.answer);
        result.put("responseTime", System.currentTimeMillis() - startTime);
        result.put("multimodalTransport", upload.uploadedFiles.isEmpty()
                ? resolveMultimodalTransport(questionImages, questionFiles) : "semiclaw-batch-upload");
        result.put("questionImages", questionImages);
        result.put("questionFiles", questionFiles);
        result.put("uploadedFiles", upload.uploadedFiles);
        result.put("uploadedFileCount", upload.uploadedFiles.size());
        if (!isBlank(upload.uploadError)) {
            result.put("uploadError", upload.uploadError);
        }

        String generatedDocPath = firstNonBlank(wsResult.generatedFilePath,
                extractGeneratedDocRelativePath(wsResult.answer));
        if (!isBlank(generatedDocPath)) {
            try {
                byte[] data = downloadGeneratedFile(normalizedBaseUrl, agentId, session.sessionId,
                        session.accessToken, generatedDocPath);
                String fileName = extractFileName(generatedDocPath);
                String objectName = "files/" + System.currentTimeMillis() + "_"
                        + UUID.randomUUID().toString().replace("-", "").substring(0, 8) + "_" + fileName;
                String contentType = resolveGeneratedDocContentType(fileName);
                minioStorageService.upload(objectName, data, contentType);
                result.put("generatedFileUrl", minioStorageService.getUrl(objectName));
                result.put("generatedFileObjectName", objectName);
                result.put("generatedFileName", fileName);
                log.info("已下载并保存 SemiClaw 生成的文件: agentId={}, fileName={}, size={}",
                        agentId, fileName, data.length);
            } catch (Exception e) {
                log.warn("下载/保存 SemiClaw 生成的文档失败: agentId={}, path={}, error={}",
                        agentId, generatedDocPath, e.getMessage());
            }
        }

        log.info("SemiClaw 多模态评测调用完成: agentId={}, sessionId={}, responseTime={}ms, transport={}, uploadedFileCount={}",
                agentId, maskValue(session.sessionId), System.currentTimeMillis() - startTime,
                result.get("multimodalTransport"), upload.uploadedFiles.size());
        return result;
    }

    private SessionContext createSessionWithTokenRefresh(String normalizedBaseUrl,
                                                          String tenantId,
                                                          String agentId,
                                                          String username,
                                                          String password,
                                                          String accessToken) {
        try {
            String sessionId = createSession(normalizedBaseUrl, agentId, accessToken);
            return new SessionContext(sessionId, accessToken);
        } catch (RuntimeException e) {
            if (!isTokenInvalidSessionError(e)) {
                throw e;
            }
            log.warn("SemiClaw 创建 session 时 token 可能失效，清理缓存后重试一次: baseUrl={}, tenantId={}, username={}, agentId={}",
                    sanitizeUrl(normalizedBaseUrl), maskValue(tenantId), maskUsername(username), agentId);
            semiclawApiService.invalidateToken(normalizedBaseUrl, tenantId, username);
            Map<String, String> retryLogin = semiclawApiService.login(
                    normalizedBaseUrl, tenantId, username, password);
            String retryAccessToken = retryLogin.get("accessToken");
            if (isBlank(retryAccessToken)) {
                throw new RuntimeException("SemiClaw 重新登录成功但 accessToken 为空", e);
            }
            try {
                String retrySessionId = createSession(normalizedBaseUrl, agentId, retryAccessToken);
                return new SessionContext(retrySessionId, retryAccessToken);
            } catch (RuntimeException retryError) {
                retryError.addSuppressed(e);
                throw retryError;
            }
        }
    }

    private String createSession(String normalizedBaseUrl, String agentId, String accessToken) {
        URI uri = buildSessionUri(normalizedBaseUrl, agentId);
        String body = "{}";
        try {
            HttpResponse<String> response = postJsonWithRedirect(uri, accessToken, body, 3);
            int status = response.statusCode();
            String responseBody = response.body();
            log.info("SemiClaw 创建 session 响应: status={}, bodyLength={}",
                    status, responseBody == null ? 0 : responseBody.length());
            log.debug("SemiClaw 创建 session 响应摘要: {}", summarizeBody(responseBody));
            if (status < 200 || status >= 300) {
                throw new RuntimeException("SemiClaw 创建 session 失败: status=" + status
                        + ", bodySummary=" + summarizeBody(responseBody));
            }
            if (isBlank(responseBody)) {
                throw new RuntimeException("SemiClaw 创建 session 响应为空");
            }
            Map<?, ?> parsed = objectMapper.readValue(responseBody, Map.class);
            String sessionId = firstNonBlank(stringValue(parsed.get("id")),
                    stringValue(parsed.get("session_id")), stringValue(parsed.get("sessionId")));
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
            log.info("调用 SemiClaw 创建 session 接口: url={}", sanitizeUrl(currentUri.toString()));
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (!isRedirect(response.statusCode())) {
                return response;
            }
            String location = response.headers().firstValue("location").orElse(null);
            log.warn("SemiClaw 创建 session 返回重定向: status={}, location={}",
                    response.statusCode(), sanitizeUrl(location));
            if (isBlank(location)) {
                return response;
            }
            currentUri = normalizeRedirectUri(initialUri, location);
        }
        throw new RuntimeException("SemiClaw 创建 session 重定向次数过多: "
                + sanitizeUrl(initialUri.toString()));
    }

    private UploadBatchResult uploadBatch(String normalizedBaseUrl,
                                          String agentId,
                                          String sessionId,
                                          String accessToken,
                                          List<MediaResource> resources) {
        UploadBatchResult result = new UploadBatchResult();
        result.resources = resources == null ? new ArrayList<>() : resources;
        resources = result.resources;
        if (resources.isEmpty()) {
            return result;
        }

        String boundary = "----QAToolsSemiClawBoundary" + UUID.randomUUID().toString().replace("-", "");
        URI uri = URI.create(normalizedBaseUrl + "/api/chat/upload/batch");
        try {
            byte[] body = buildMultipartBody(boundary, resources, sessionId, agentId);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(SESSION_TIMEOUT_SECONDS))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Accept", "application/json")
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            log.info("调用 SemiClaw batch 上传接口: url={}, sessionId={}, agentId={}, fileCount={}, bodyLength={}",
                    sanitizeUrl(uri.toString()), maskValue(sessionId), agentId, resources.size(), body.length);
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            String responseBody = response.body();
            log.info("SemiClaw batch 上传响应: status={}, bodyLength={}",
                    status, responseBody == null ? 0 : responseBody.length());
            log.debug("SemiClaw batch 上传响应摘要: {}", summarizeBody(responseBody));
            if (status < 200 || status >= 300) {
                result.uploadError = "SemiClaw batch 上传失败: status=" + status
                        + ", bodySummary=" + summarizeBody(responseBody);
                log.warn(result.uploadError);
                return result;
            }

            List<Map<String, Object>> uploaded = parseUploadedFiles(responseBody);
            result.uploadedFiles.addAll(uploaded);
            if (uploaded.isEmpty()) {
                result.uploadError = "SemiClaw batch 上传成功但响应中未解析到 files";
                log.warn("{}; bodySummary={}", result.uploadError, summarizeBody(responseBody));
            } else {
                log.info("SemiClaw batch 上传完成: sessionId={}, uploadedFileCount={}, fileNames={}",
                        maskValue(sessionId), uploaded.size(), uploadedFileNames(uploaded));
            }
            return result;
        } catch (Exception e) {
            result.uploadError = "SemiClaw batch 上传异常: " + e.getMessage();
            log.warn(result.uploadError, e);
            return result;
        }
    }

    private byte[] buildMultipartBody(String boundary,
                                      List<MediaResource> resources,
                                      String sessionId,
                                      String agentId) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (MediaResource resource : resources) {
            writeString(out, "--" + boundary + "\r\n");
            writeString(out, "Content-Disposition: form-data; name=\"files\"; filename=\""
                    + escapeMultipartValue(resource.fileName) + "\"\r\n");
            writeString(out, "Content-Type: " + firstNonBlank(resource.contentType, "application/octet-stream")
                    + "\r\n\r\n");
            out.write(resource.bytes);
            writeString(out, "\r\n");
        }
        writeFormField(out, boundary, "session_id", sessionId);
        writeFormField(out, boundary, "agent_id", agentId);
        writeString(out, "--" + boundary + "--\r\n");
        return out.toByteArray();
    }

    private void writeFormField(ByteArrayOutputStream out, String boundary, String name, String value)
            throws IOException {
        writeString(out, "--" + boundary + "\r\n");
        writeString(out, "Content-Disposition: form-data; name=\"" + escapeMultipartValue(name) + "\"\r\n\r\n");
        writeString(out, value == null ? "" : value);
        writeString(out, "\r\n");
    }

    private void writeString(ByteArrayOutputStream out, String value) throws IOException {
        out.write(value.getBytes(StandardCharsets.UTF_8));
    }

    private String escapeMultipartValue(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "").replace("\n", "");
    }

    private String uploadedFileNames(List<Map<String, Object>> uploadedFiles) {
        List<String> names = new ArrayList<>();
        for (Map<String, Object> file : normalizeUploadedFiles(uploadedFiles)) {
            String name = firstNonBlank(stringValue(file.get("filename")),
                    stringValue(file.get("saved_filename")), stringValue(file.get("savedFilename")),
                    stringValue(file.get("name")));
            if (!isBlank(name)) names.add(name);
        }
        return String.join(", ", names);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseUploadedFiles(String responseBody) {
        if (isBlank(responseBody)) {
            return Collections.emptyList();
        }
        try {
            Object parsed = objectMapper.readValue(responseBody, Object.class);
            if (parsed instanceof Map<?, ?> map) {
                Object files = firstPresent(map, "files", "data", "items", "results");
                if (files instanceof Collection<?> collection) {
                    return toMapList(collection);
                }
                if (files instanceof Map<?, ?> nested) {
                    List<Map<String, Object>> nestedFiles = parseFilesFromMap(nested);
                    if (!nestedFiles.isEmpty()) {
                        return nestedFiles;
                    }
                }
                return parseFilesFromMap(map);
            }
            if (parsed instanceof Collection<?> collection) {
                return toMapList(collection);
            }
        } catch (Exception e) {
            log.warn("解析 SemiClaw batch 上传响应失败: {}", e.getMessage());
        }
        return Collections.emptyList();
    }

    private List<Map<String, Object>> parseFilesFromMap(Map<?, ?> map) {
        if (map == null || map.isEmpty()) {
            return Collections.emptyList();
        }
        Object files = firstPresent(map, "files", "data", "items", "results");
        if (files instanceof Collection<?> collection) {
            return toMapList(collection);
        }
        Map<String, Object> file = toStringObjectMap(map);
        return file.isEmpty() ? Collections.emptyList() : List.of(file);
    }

    private List<Map<String, Object>> toMapList(Collection<?> collection) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : collection) {
            Map<String, Object> map = toStringObjectMap(item);
            if (!map.isEmpty()) {
                result.add(map);
            }
        }
        return result;
    }

    private Map<String, Object> toStringObjectMap(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    result.put(entry.getKey().toString(), entry.getValue());
                }
            }
        }
        return result;
    }

    private List<MediaResource> collectMediaResources(List<String> questionImages, List<String> questionFiles) {
        List<MediaResource> resources = new ArrayList<>();
        for (String image : normalizeStringList(questionImages)) {
            try {
                resources.add(resolveMediaResource(image, "image"));
            } catch (Exception e) {
                log.warn("读取 SemiClaw 图片上传资源失败: source={}, reason={}",
                        summarizeMediaSource(image), e.getMessage());
            }
        }
        for (String file : normalizeStringList(questionFiles)) {
            try {
                resources.add(resolveMediaResource(file, "file"));
            } catch (Exception e) {
                log.warn("读取 SemiClaw 文件上传资源失败: source={}, reason={}",
                        summarizeMediaSource(file), e.getMessage());
            }
        }
        return resources;
    }

    private MediaResource resolveMediaResource(String source, String type) throws Exception {
        validateRequired("semiclaw media source", source);
        String fileName = extractFileName(source);
        if (isBlank(fileName) || fileName.startsWith("data:")) {
            fileName = "image".equals(type) ? "image.png" : "file.bin";
        }

        if (isDataUri(source)) {
            DataUriResource resource = parseDataUri(source);
            return new MediaResource(filenameWithExtension(fileName, resource.contentType),
                    resource.contentType, resource.bytes, source);
        }
        if (isBase64Like(source)) {
            byte[] bytes = Base64.getDecoder().decode(source);
            String contentType = guessContentType(fileName, type);
            return new MediaResource(filenameWithExtension(fileName, contentType), contentType, bytes, source);
        }

        Path localPath = tryResolveLocalPath(source);
        if (localPath != null) {
            byte[] bytes = Files.readAllBytes(localPath);
            String localFileName = localPath.getFileName() == null ? fileName : localPath.getFileName().toString();
            String contentType = guessContentType(localFileName, type);
            return new MediaResource(filenameWithExtension(localFileName, contentType),
                    contentType, bytes, source);
        }

        Exception lastError = null;
        for (URI candidate : buildMediaUriCandidates(source)) {
            try {
                FetchMediaResult fetched = fetchMediaBytes(candidate);
                return new MediaResource(filenameWithExtension(fileName, fetched.contentType),
                        firstNonBlank(fetched.contentType, guessContentType(fileName, type)),
                        fetched.bytes, candidate.toString());
            } catch (Exception e) {
                lastError = e;
                log.debug("尝试下载 SemiClaw 媒体资源失败: url={}, reason={}",
                        sanitizeUrl(candidate.toString()), e.getMessage());
            }
        }
        throw lastError == null
                ? new RuntimeException("无法解析媒体资源: " + summarizeMediaSource(source))
                : lastError;
    }

    private List<URI> buildMediaUriCandidates(String source) {
        List<URI> candidates = new ArrayList<>();
        String value = source == null ? "" : source.trim();
        if (value.startsWith("http://") || value.startsWith("https://")) {
            try {
                candidates.add(toSafeUri(value));
            } catch (Exception e) {
                log.warn("构建媒体资源 URL 失败: source={}, reason={}", summarizeMediaSource(value), e.getMessage());
            }
            return candidates;
        }
        String publicBaseUrl = normalizePublicBaseUrl();
        if (isBlank(publicBaseUrl)) {
            return candidates;
        }
        try {
            if (value.startsWith("/")) {
                candidates.add(toSafeUri(publicBaseUrl + value));
            } else {
                candidates.add(toSafeUri(publicBaseUrl + "/" + value));
                candidates.add(toSafeUri(publicBaseUrl + "/api/datasets/images/" + value));
            }
        } catch (Exception e) {
            log.warn("构建媒体资源 URL 失败: source={}, reason={}", summarizeMediaSource(value), e.getMessage());
        }
        return candidates;
    }

    private URI toSafeUri(String rawUrl) throws Exception {
        String normalized = rawUrl.replace(" ", "%20");
        return new URI(normalized);
    }

    private FetchMediaResult fetchMediaBytes(URI uri) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(SESSION_TIMEOUT_SECONDS))
                .header("Accept", "*/*")
                .header("User-Agent", "QATools/1.0")
                .GET()
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new RuntimeException("下载媒体资源失败: status=" + status + ", url=" + sanitizeUrl(uri.toString()));
        }
        byte[] bytes = response.body();
        if (bytes == null || bytes.length == 0) {
            throw new RuntimeException("下载媒体资源为空: url=" + sanitizeUrl(uri.toString()));
        }
        String contentType = response.headers().firstValue("content-type").orElse(null);
        return new FetchMediaResult(bytes, contentType);
    }

    private Path tryResolveLocalPath(String source) {
        if (isBlank(source) || isDataUri(source) || source.startsWith("http://") || source.startsWith("https://")) {
            return null;
        }
        String value = source.trim();
        try {
            Path direct = Paths.get(value);
            if (Files.exists(direct) && Files.isRegularFile(direct)) {
                return direct;
            }
        } catch (Exception ignored) {
        }

        String relative = value.replace("\\", "/");
        if (relative.startsWith("workspace/")) {
            relative = relative.substring("workspace/".length());
        }
        try {
            Path workingDirectory = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
            Path candidate = workingDirectory.resolve(relative).normalize();
            if (Files.exists(candidate) && Files.isRegularFile(candidate)) {
                return candidate;
            }
            Path parentCandidate = workingDirectory.resolve("..").resolve(relative).normalize();
            if (Files.exists(parentCandidate) && Files.isRegularFile(parentCandidate)) {
                return parentCandidate;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private DataUriResource parseDataUri(String dataUri) {
        int comma = dataUri.indexOf(',');
        if (comma < 0) {
            throw new IllegalArgumentException("非法 data URI");
        }
        String metadata = dataUri.substring(5, comma);
        String payload = dataUri.substring(comma + 1);
        String contentType = "application/octet-stream";
        boolean base64 = false;
        String[] parts = metadata.split(";");
        if (parts.length > 0 && !isBlank(parts[0])) {
            contentType = parts[0].trim();
        }
        for (String part : parts) {
            if ("base64".equalsIgnoreCase(part.trim())) {
                base64 = true;
            }
        }
        byte[] bytes = base64
                ? Base64.getDecoder().decode(payload)
                : java.net.URLDecoder.decode(payload, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8);
        return new DataUriResource(bytes, contentType);
    }

    private String normalizePublicBaseUrl() {
        String baseUrl = firstNonBlank(qatoolsPublicBaseUrl, "http://127.0.0.1:" + serverPort);
        if (isBlank(baseUrl)) {
            return null;
        }
        String normalized = baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "http://" + normalized;
        }
        return normalized;
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
            return "请根据提供的多模态输入完成回答。";
        }
        return "";
    }

    private String resolveMultimodalTransport(List<String> questionImages, List<String> questionFiles) {
        List<String> images = normalizeStringList(questionImages);
        List<String> files = normalizeStringList(questionFiles);
        if (!images.isEmpty()) {
            boolean hasBase64 = images.stream().anyMatch(value -> isDataUri(value) || isBase64Like(value));
            return hasBase64 ? "semiclaw-image-base64" : "semiclaw-image-url";
        }
        if (!files.isEmpty()) {
            boolean hasBase64 = files.stream().anyMatch(value -> isDataUri(value) || isBase64Like(value));
            return hasBase64 ? "semiclaw-file-base64" : "semiclaw-file-url";
        }
        return "semiclaw-text";
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

    private String filenameWithExtension(String filename, String contentType) {
        String safeName = isBlank(filename) ? "file" : filename;
        String lower = safeName.toLowerCase(Locale.ROOT);
        if (lower.contains(".") && !lower.endsWith(".")) {
            return safeName;
        }
        String extension = extensionFromContentType(contentType);
        return isBlank(extension) ? safeName : safeName + extension;
    }

    private String extensionFromContentType(String contentType) {
        if (isBlank(contentType)) return "";
        String value = contentType.toLowerCase(Locale.ROOT);
        if (value.contains("image/png")) return ".png";
        if (value.contains("image/jpeg")) return ".jpg";
        if (value.contains("image/gif")) return ".gif";
        if (value.contains("image/webp")) return ".webp";
        if (value.contains("image/bmp")) return ".bmp";
        if (value.contains("application/pdf")) return ".pdf";
        if (value.contains("text/plain")) return ".txt";
        if (value.contains("text/csv")) return ".csv";
        if (value.contains("spreadsheetml")) return ".xlsx";
        if (value.contains("ms-excel")) return ".xls";
        if (value.contains("wordprocessingml")) return ".docx";
        if (value.contains("application/msword")) return ".doc";
        if (value.contains("presentationml")) return ".pptx";
        return "";
    }

    private String guessContentType(String filename, String type) {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".bmp")) return "image/bmp";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".csv")) return "text/csv";
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (lower.endsWith(".xls")) return "application/vnd.ms-excel";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".doc")) return "application/msword";
        if (lower.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (lower.endsWith(".ppt")) return "application/vnd.ms-powerpoint";
        return "image".equals(type) ? "image/png" : "application/octet-stream";
    }

    private String summarizeMediaSource(String source) {
        if (source == null) return "";
        String value = source.trim();
        if (value.length() <= 160) return sanitizeUrl(value);
        return sanitizeUrl(value.substring(0, 160)) + "...";
    }

    private Map<String, Object> buildMultimodalPayload(String question,
                                                        List<String> questionImages,
                                                        List<String> questionFiles,
                                                        List<Map<String, Object>> uploadedFiles,
                                                        List<MediaResource> uploadedResources) {
        List<String> safeQuestionImages = normalizeStringList(questionImages);
        List<String> safeQuestionFiles = normalizeStringList(questionFiles);
        List<Map<String, Object>> safeUploadedFiles = normalizeUploadedFiles(uploadedFiles);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("client_request_id", UUID.randomUUID().toString());
        payload.put("ui_lang", "zh-CN");
        if (!safeUploadedFiles.isEmpty()) {
            payload.put("content", buildContentWithUploadedFileRefs(question, safeUploadedFiles, uploadedResources));
            payload.put("display_content", question);
            payload.put("file_name", buildUploadedFileNameField(safeUploadedFiles));
            String fileIds = buildUploadedFieldJoined(safeUploadedFiles, "file_id", "fileId", "id");
            if (!isBlank(fileIds)) payload.put("file_ids", fileIds);
            String filePaths = buildUploadedFilePathsField(safeUploadedFiles);
            if (!isBlank(filePaths)) payload.put("file_paths", filePaths);
            return payload;
        }

        payload.put("content", question);
        payload.put("display_content", question);
        payload.put("file_name", buildFileNameField(safeQuestionImages, safeQuestionFiles));
        if (!safeQuestionImages.isEmpty()) {
            payload.put("images", buildMediaItems(safeQuestionImages, "image"));
            payload.put("image_urls", safeQuestionImages);
            payload.put("questionImages", safeQuestionImages);
            payload.put("question_images", safeQuestionImages);
        }
        if (!safeQuestionFiles.isEmpty()) {
            payload.put("files", buildMediaItems(safeQuestionFiles, "file"));
            payload.put("file_urls", safeQuestionFiles);
            payload.put("questionFiles", safeQuestionFiles);
            payload.put("question_files", safeQuestionFiles);
        }
        if (!safeQuestionImages.isEmpty() || !safeQuestionFiles.isEmpty()) {
            payload.put("attachments", buildAttachmentItems(safeQuestionImages, safeQuestionFiles));
            payload.put("multimodal", true);
            payload.put("input_type", "multimodal");
            payload.put("upload_mode", "fallback-url-payload");
        }
        return payload;
    }

    private String buildContentWithUploadedFileRefs(String question,
                                                     List<Map<String, Object>> uploadedFiles,
                                                     List<MediaResource> uploadedResources) {
        String finalQuestion = question == null ? "" : question;
        StringBuilder content = new StringBuilder();
        List<Map<String, Object>> files = normalizeUploadedFiles(uploadedFiles);
        for (int i = 0; i < files.size(); i++) {
            Map<String, Object> file = files.get(i);
            if (isUploadedImage(file)) {
                String fileId = firstNonBlank(stringValue(file.get("file_id")),
                        stringValue(file.get("fileId")), stringValue(file.get("id")));
                if (!isBlank(fileId)) {
                    content.append("[image_file:").append(fileId).append("]\n");
                }
                continue;
            }
            String fileName = firstNonBlank(stringValue(file.get("filename")),
                    stringValue(file.get("saved_filename")), stringValue(file.get("savedFilename")),
                    stringValue(file.get("name")));
            String relativePath = resolveUploadedFileRelativePath(file);
            if (isBlank(fileName) && isBlank(relativePath)) {
                continue;
            }
            content.append("文件：").append(isBlank(fileName) ? relativePath : fileName).append("\n");
            if (!isBlank(relativePath)) {
                content.append("File location: ").append(ensureWorkspacePrefix(relativePath))
                        .append(" (for read_file/read_document tools)\n");
                content.append("In execute_code, use relative path: \"").append(relativePath)
                        .append("\" (working directory is workspace)\n");
            }
            String rawText = extractTextIfDecodable(fileName,
                    findMatchingResource(fileName, uploadedResources, i));
            if (!isBlank(rawText)) {
                if (rawText.length() <= INLINE_FILE_TEXT_MAX_CHARS) {
                    content.append("\n").append(rawText).append("\n");
                } else {
                    log.info("SemiClaw 多模态文件过大，改为提示文件路径供 Agent 工具读取: fileName={}, textLength={}, maxChars={}",
                            fileName, rawText.length(), INLINE_FILE_TEXT_MAX_CHARS);
                }
            }
        }
        content.append("用户问题：").append(finalQuestion);
        return content.toString();
    }

    private MediaResource findMatchingResource(String filename, List<MediaResource> resources, int index) {
        if (resources == null || resources.isEmpty()) return null;
        if (index >= 0 && index < resources.size()) return resources.get(index);
        if (!isBlank(filename)) {
            for (MediaResource resource : resources) {
                if (filename.equalsIgnoreCase(resource.fileName)) return resource;
            }
        }
        return resources.size() == 1 ? resources.get(0) : null;
    }

    private String extractTextIfDecodable(String filename, MediaResource resource) {
        if (resource == null || resource.bytes == null || resource.bytes.length == 0
                || !isLikelyTextFile(filename, resource.contentType)) {
            return null;
        }
        return new String(resource.bytes, StandardCharsets.UTF_8);
    }

    private boolean isLikelyTextFile(String filename, String contentType) {
        String lowerName = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        String lowerType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        return lowerName.endsWith(".txt") || lowerName.endsWith(".csv") || lowerName.endsWith(".json")
                || lowerName.endsWith(".jsonl") || lowerName.endsWith(".md")
                || lowerType.startsWith("text/") || lowerType.contains("json");
    }

    private List<Map<String, Object>> normalizeUploadedFiles(List<Map<String, Object>> uploadedFiles) {
        if (uploadedFiles == null || uploadedFiles.isEmpty()) return Collections.emptyList();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> file : uploadedFiles) {
            if (file != null && !file.isEmpty()) result.add(new LinkedHashMap<>(file));
        }
        return result;
    }

    private String buildUploadedFileNameField(List<Map<String, Object>> uploadedFiles) {
        List<String> names = new ArrayList<>();
        for (Map<String, Object> file : normalizeUploadedFiles(uploadedFiles)) {
            String name = firstNonBlank(stringValue(file.get("filename")),
                    stringValue(file.get("saved_filename")), stringValue(file.get("savedFilename")),
                    stringValue(file.get("name")));
            if (!isBlank(name)) names.add(name);
        }
        return String.join(",", names);
    }

    private String buildUploadedFilePathsField(List<Map<String, Object>> uploadedFiles) {
        List<String> paths = new ArrayList<>();
        for (Map<String, Object> file : normalizeUploadedFiles(uploadedFiles)) {
            String path = resolveUploadedFileRelativePath(file);
            if (!isBlank(path)) paths.add(ensureWorkspacePrefix(path));
        }
        return String.join(",", paths);
    }

    private String resolveUploadedFileRelativePath(Map<String, Object> file) {
        if (file == null) return "";
        String path = firstNonBlank(stringValue(file.get("workspace_path")),
                stringValue(file.get("workspacePath")), stringValue(file.get("file_path")),
                stringValue(file.get("filePath")), stringValue(file.get("path")));
        if (isBlank(path)) return "";
        return path.startsWith("workspace/") ? path.substring("workspace/".length()) : path;
    }

    private String ensureWorkspacePrefix(String path) {
        if (isBlank(path)) return path;
        return path.startsWith("workspace/") ? path : "workspace/" + path;
    }

    private String buildUploadedFieldJoined(List<Map<String, Object>> uploadedFiles, String... fields) {
        List<String> values = new ArrayList<>();
        for (Map<String, Object> file : normalizeUploadedFiles(uploadedFiles)) {
            String value = null;
            if (fields != null) {
                for (String field : fields) {
                    value = firstNonBlank(value, stringValue(file.get(field)));
                }
            }
            if (!isBlank(value)) values.add(value);
        }
        return String.join(",", values);
    }

    private boolean isUploadedImage(Map<String, Object> file) {
        if (file == null || file.isEmpty()) return false;
        if (isTruthy(firstPresent(file, "image", "is_image", "isImage"))) return true;
        String imageUrl = firstNonBlank(stringValue(file.get("image_url")),
                stringValue(file.get("imageUrl")), stringValue(file.get("image_data_url")),
                stringValue(file.get("imageDataUrl")));
        if (!isBlank(imageUrl)) return true;
        String filename = firstNonBlank(stringValue(file.get("filename")),
                stringValue(file.get("saved_filename")), stringValue(file.get("name")));
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".bmp");
    }

    private List<Map<String, Object>> buildAttachmentItems(List<String> images, List<String> files) {
        List<Map<String, Object>> attachments = new ArrayList<>();
        for (String image : normalizeStringList(images)) attachments.add(buildMediaItem(image, "image"));
        for (String file : normalizeStringList(files)) attachments.add(buildMediaItem(file, "file"));
        return attachments;
    }

    private List<Map<String, Object>> buildMediaItems(List<String> values, String type) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (String value : normalizeStringList(values)) items.add(buildMediaItem(value, type));
        return items;
    }

    private Map<String, Object> buildMediaItem(String value, String type) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", type);
        item.put("name", extractFileName(value));
        if (isDataUri(value)) {
            item.put("data", value);
            item.put("data_uri", value);
        } else if (isBase64Like(value)) {
            item.put("base64", value);
        } else {
            item.put("url", value);
            item.put("uri", value);
        }
        return item;
    }

    private String buildFileNameField(List<String> images, List<String> files) {
        List<String> names = new ArrayList<>();
        for (String image : normalizeStringList(images)) names.add(extractFileName(image));
        for (String file : normalizeStringList(files)) names.add(extractFileName(file));
        names.removeIf(SemiclawMultimodalChatService::isBlank);
        return String.join(",", names);
    }

    private WsChatResult chatByWebSocketMultimodal(String normalizedBaseUrl,
                                                    String tenantId,
                                                    String agentId,
                                                    String sessionId,
                                                    String accessToken,
                                                    String question,
                                                    List<String> questionImages,
                                                    List<String> questionFiles,
                                                    List<Map<String, Object>> uploadedFiles,
                                                    List<MediaResource> uploadedResources,
                                                    String wsCookie,
                                                    String expectedDocType) {
        WebSocket webSocket = null;
        try {
            URI wsUri = "ticket".equalsIgnoreCase(wsAuthMode)
                    ? buildWebSocketUriByTicket(normalizedBaseUrl, agentId, sessionId,
                    semiclawApiService.getWsTicket(normalizedBaseUrl, accessToken))
                    : buildWebSocketUri(normalizedBaseUrl, tenantId, agentId, sessionId, accessToken);
            SemiclawWebSocketListener listener = new SemiclawWebSocketListener(objectMapper, expectedDocType);
            long waitTimeoutSeconds = resolveChatTimeoutSeconds(questionImages, questionFiles);
            log.info("连接 SemiClaw 多模态 WebSocket: url={}, sessionId={}, imageCount={}, fileCount={}, uploadedFileCount={}, waitTimeoutSeconds={}",
                    sanitizeUrl(wsUri.toString()), maskValue(sessionId), questionImages.size(),
                    questionFiles.size(), uploadedFiles == null ? 0 : uploadedFiles.size(), waitTimeoutSeconds);

            WebSocket.Builder webSocketBuilder = httpClient.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                    .header("Origin", normalizedBaseUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36");
            if (!isBlank(wsCookie)) {
                webSocketBuilder.header("Cookie", wsCookie);
            }
            webSocket = webSocketBuilder.buildAsync(wsUri, listener)
                    .get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            Map<String, Object> payload = buildMultimodalPayload(question, questionImages, questionFiles,
                    uploadedFiles, uploadedResources);
            String message = objectMapper.writeValueAsString(payload);
            log.info("发送 SemiClaw 多模态问题: agentId={}, sessionId={}, questionLength={}, imageCount={}, fileCount={}, uploadedFileCount={}, payloadKeys={}, waitTimeoutSeconds={}",
                    agentId, maskValue(sessionId), question == null ? 0 : question.length(),
                    questionImages.size(), questionFiles.size(),
                    uploadedFiles == null ? 0 : uploadedFiles.size(), payload.keySet(), waitTimeoutSeconds);
            webSocket.sendText(message, true).get(10, TimeUnit.SECONDS);

            return new WsChatResult(listener.waitForAnswer(webSocket, waitTimeoutSeconds, TimeUnit.SECONDS),
                    listener.lastGeneratedDocPath);
        } catch (Exception e) {
            throw new RuntimeException("SemiClaw 多模态 WebSocket 聊天失败: " + e.getMessage(), e);
        } finally {
            if (webSocket != null) {
                try {
                    webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(2, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    try {
                        webSocket.abort();
                    } catch (Exception ignoredAgain) {
                    }
                }
            }
        }
    }

    private long resolveChatTimeoutSeconds(List<String> questionImages, List<String> questionFiles) {
        if (!normalizeStringList(questionFiles).isEmpty()) {
            return Math.max(fileChatTimeoutSeconds, DEFAULT_CHAT_TIMEOUT_SECONDS);
        }
        if (!normalizeStringList(questionImages).isEmpty()) {
            return Math.max(imageChatTimeoutSeconds, DEFAULT_CHAT_TIMEOUT_SECONDS);
        }
        return DEFAULT_CHAT_TIMEOUT_SECONDS;
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
            wsBaseUrl = "wss://" + normalizedBaseUrl;
        }
        String url = wsBaseUrl + "/ws/chat/" + encode(agentId)
                + "?token=" + encode(accessToken)
                + "&session_id=" + encode(sessionId)
                + "&tenant_id=" + encode(tenantId);
        return URI.create(url);
    }

    /** 新版 SemiClaw WS 协议使用短时 ticket 和 session_id。 */
    private URI buildWebSocketUriByTicket(String normalizedBaseUrl,
                                          String agentId,
                                          String sessionId,
                                          String ticket) {
        validateRequired("semiclaw ws ticket", ticket);
        String wsBaseUrl;
        if (normalizedBaseUrl.startsWith("https://")) {
            wsBaseUrl = "wss://" + normalizedBaseUrl.substring("https://".length());
        } else if (normalizedBaseUrl.startsWith("http://")) {
            wsBaseUrl = "ws://" + normalizedBaseUrl.substring("http://".length());
        } else {
            wsBaseUrl = "wss://" + normalizedBaseUrl;
        }
        return URI.create(wsBaseUrl + "/ws/chat/" + encode(agentId)
                + "?ticket=" + encode(ticket) + "&session_id=" + encode(sessionId));
    }

    private URI normalizeRedirectUri(URI originalUri, String location) {
        URI locationUri = URI.create(location);
        if (locationUri.isAbsolute()) {
            return locationUri;
        }
        if (location.startsWith("/")) {
            return URI.create(originalUri.getScheme() + "://" + originalUri.getAuthority() + location);
        }
        String originalPath = originalUri.getPath();
        int lastSlash = originalPath == null ? -1 : originalPath.lastIndexOf('/');
        String basePath = lastSlash >= 0 ? originalPath.substring(0, lastSlash + 1) : "/";
        return URI.create(originalUri.getScheme() + "://" + originalUri.getAuthority() + basePath + location);
    }

    private boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private byte[] downloadGeneratedFile(String normalizedBaseUrl,
                                        String agentId,
                                        String sessionId,
                                        String accessToken,
                                        String relativePath) throws Exception {
        String workspacePath = ensureWorkspacePrefix(relativePath);
        URI uri = URI.create(normalizedBaseUrl + "/api/agents/" + encode(agentId)
                + "/files/download?path=" + encode(workspacePath)
                + "&session_id=" + encode(sessionId));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(SESSION_TIMEOUT_SECONDS))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();
        log.info("下载 SemiClaw 生成文件: url={}", sanitizeUrl(uri.toString()));
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new RuntimeException("SemiClaw 文件下载失败: status=" + response.statusCode());
        }
        byte[] bytes = response.body();
        if (bytes == null || bytes.length == 0) {
            throw new RuntimeException("SemiClaw 生成文件内容为空");
        }
        return bytes;
    }

    private String extractGeneratedDocRelativePath(String answer) {
        if (isBlank(answer)) return null;
        Matcher matcher = GENERATED_DOC_DOWNLOAD_LINK_PATTERN.matcher(answer);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    private String extractFileName(String value) {
        if (isBlank(value)) return "";
        String text = value.trim();
        int queryIndex = text.indexOf('?');
        if (queryIndex >= 0) text = text.substring(0, queryIndex);
        int hashIndex = text.indexOf('#');
        if (hashIndex >= 0) text = text.substring(0, hashIndex);
        int slashIndex = Math.max(text.lastIndexOf('/'), text.lastIndexOf('\\'));
        String name = slashIndex >= 0 ? text.substring(slashIndex + 1) : text;
        if (name.startsWith("data:")) return "image";
        return name.length() > 120 ? name.substring(0, 120) : name;
    }

    private String resolveGeneratedDocContentType(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
        return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
    }

    private static class SemiclawWebSocketListener implements WebSocket.Listener {
        private static final long SILENCE_THRESHOLD_SECONDS = 90;
        private static final long POLL_INTERVAL_SECONDS = 15;
        private static final String PPT_FOLLOW_UP_TEXT =
                "请将当前生成的文件作为本次任务的最终结果返回，不要继续修改或新增内容。";
        private static final int PPT_MAX_FOLLOW_UP_COUNT = 2;
        private static final String HTML_FOLLOW_UP_TEXT = "继续，完成剩余内容";
        private static final int HTML_MAX_FOLLOW_UP_COUNT = 3;
        private static final int DEFAULT_MAX_FOLLOW_UP_COUNT = 1;

        private final ObjectMapper objectMapper;
        private final String expectedDocType;
        private final CompletableFuture<String> answerFuture = new CompletableFuture<>();
        private final StringBuilder chunkBuffer = new StringBuilder();
        private final StringBuilder partialBuffer = new StringBuilder();
        private final StringBuilder textChannelBuffer = new StringBuilder();
        private int receivedMessageCount;
        private int chunkMessageCount;
        private int doneMessageCount;
        private String lastMessageType = "";
        private String lastMessagePreview = "";
        private int lastCloseStatus = -1;
        private String lastCloseReason = "";
        private volatile String lastGeneratedDocPath;
        private volatile long lastMessageTime = System.currentTimeMillis();
        private int followUpCount;
        private boolean greetingSkipped;

        private SemiclawWebSocketListener(ObjectMapper objectMapper, String expectedDocType) {
            this.objectMapper = objectMapper;
            this.expectedDocType = expectedDocType;
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
            lastCloseStatus = statusCode;
            lastCloseReason = reason == null ? "" : reason;
            if (!answerFuture.isDone()) {
                String fallback = firstNonBlank(textChannelBuffer.toString(), chunkBuffer.toString());
                if (!isBlank(fallback)) {
                    answerFuture.complete(fallback);
                } else {
                    answerFuture.completeExceptionally(new RuntimeException(
                            "SemiClaw WebSocket 已关闭但未收到最终回答: status=" + statusCode
                                    + ", reason=" + reason + ", receivedMessageCount=" + receivedMessageCount
                                    + ", lastType=" + lastMessageType + ", lastMessage=" + lastMessagePreview));
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
            if (isBlank(message)) return;
            receivedMessageCount++;
            lastMessagePreview = summarizeMessage(message);
            lastMessageTime = System.currentTimeMillis();
            try {
                Map<?, ?> json = objectMapper.readValue(message, Map.class);
                String type = firstNonBlank(stringValue(json.get("type")),
                        stringValue(json.get("event")), stringValue(json.get("status")));
                lastMessageType = type == null ? "" : type;
                String content = extractContent(json);

                if ("user_msg_saved".equalsIgnoreCase(type) || "assistant_thinking".equalsIgnoreCase(type)) {
                    if (!greetingSkipped) {
                        greetingSkipped = true;
                        chunkBuffer.setLength(0);
                        textChannelBuffer.setLength(0);
                        log.debug("SemiClaw WS 真实回答轮次开始({}), 已清空 greeting buffer", type);
                    }
                    return;
                }
                if (!greetingSkipped) {
                    log.debug("SemiClaw WS 忽略开场欢迎阶段的消息: type={}", type);
                    return;
                }
                if ("workspace_change".equalsIgnoreCase(type)) {
                    trackGeneratedFile(json);
                    return;
                }
                if ("stream_event".equalsIgnoreCase(type)) {
                    handleStreamEvent(json);
                    return;
                }
                if ("chunk".equalsIgnoreCase(type) || "message".equalsIgnoreCase(type)
                        || "stream".equalsIgnoreCase(type) || "delta".equalsIgnoreCase(type)) {
                    if (content != null) {
                        chunkBuffer.append(content);
                        chunkMessageCount++;
                    }
                    return;
                }
                if ("result".equalsIgnoreCase(type)) {
                    String finalAnswer = firstNonBlank(textChannelBuffer.toString(), content, chunkBuffer.toString());
                    trackGeneratedFileFromSegments(json);
                    answerFuture.complete(finalAnswer == null ? "" : finalAnswer);
                    return;
                }
                if (isDoneType(type)) {
                    doneMessageCount++;
                    if (!answerFuture.isDone()) {
                        String finalAnswer = firstNonBlank(textChannelBuffer.toString(), content, chunkBuffer.toString());
                        answerFuture.complete(finalAnswer == null ? "" : finalAnswer);
                    }
                    return;
                }
                if ("finish_verifier_status".equalsIgnoreCase(type)) {
                    return;
                }
                if (!isBlank(content) && !answerFuture.isDone()) {
                    chunkBuffer.append(content);
                    chunkMessageCount++;
                }
            } catch (Exception e) {
                log.debug("SemiClaw WS 非 JSON 消息按文本片段处理: length={}, preview={}",
                        message.length(), lastMessagePreview);
                if (!answerFuture.isDone()) chunkBuffer.append(message);
            }
        }

        private void handleStreamEvent(Map<?, ?> json) {
            String eventType = stringValue(json.get("event_type"));
            if (!"stream_delta".equalsIgnoreCase(eventType)) return;
            String channel = stringValue(json.get("channel"));
            if (!"text".equalsIgnoreCase(channel)) return;
            String value = stringValue(json.get("value"));
            if (!isBlank(value)) textChannelBuffer.append(value);
        }

        private void trackGeneratedFile(Map<?, ?> json) {
            Object dataObject = json.get("data");
            if (!(dataObject instanceof Map<?, ?> data)) return;
            String action = stringValue(data.get("action"));
            String path = stringValue(data.get("path"));
            if (!"created".equalsIgnoreCase(action) || !isGeneratedDocPath(path)) return;
            lastGeneratedDocPath = path;
            log.info("SemiClaw WS 捕获生成的文档文件: path={}", path);
        }

        private void trackGeneratedFileFromSegments(Map<?, ?> json) {
            Object segmentsObject = json.get("segments");
            if (!(segmentsObject instanceof List<?> segments)) return;
            for (Object segmentObject : segments) {
                if (!(segmentObject instanceof Map<?, ?> segment)) continue;
                if (!"tool_call".equalsIgnoreCase(stringValue(segment.get("type")))) continue;
                Object argsObject = segment.get("args");
                if (!(argsObject instanceof Map<?, ?> args)) continue;
                String path = stringValue(args.get("file_path"));
                if (isGeneratedDocPath(path)) {
                    lastGeneratedDocPath = path;
                    log.info("SemiClaw WS result.segments 捕获生成的文档文件: path={}", path);
                }
            }
        }

        private boolean isGeneratedDocPath(String path) {
            if (isBlank(path)) return false;
            String lower = path.toLowerCase(Locale.ROOT);
            return lower.endsWith(".pptx") || lower.endsWith(".ppt")
                    || lower.endsWith(".html") || lower.endsWith(".htm");
        }

        private String extractContent(Map<?, ?> json) {
            String content = firstNonBlank(stringValue(json.get("content")),
                    stringValue(json.get("answer")), stringValue(json.get("message")),
                    stringValue(json.get("text")), stringValue(json.get("result")));
            if (!isBlank(content)) return content;
            Object data = json.get("data");
            if (data instanceof Map<?, ?> map) {
                return firstNonBlank(stringValue(map.get("content")), stringValue(map.get("answer")),
                        stringValue(map.get("message")), stringValue(map.get("text")),
                        stringValue(map.get("result")));
            }
            if (data instanceof String || data instanceof Number || data instanceof Boolean) {
                return data.toString();
            }
            return null;
        }

        private boolean isDoneType(String type) {
            if (isBlank(type)) return false;
            String normalized = type.trim().toLowerCase(Locale.ROOT);
            return "done".equals(normalized) || "finish".equals(normalized)
                    || "finished".equals(normalized) || "complete".equals(normalized)
                    || "completed".equals(normalized) || "end".equals(normalized)
                    || "final".equals(normalized);
        }

        private String waitForAnswer(WebSocket webSocket, long timeout, TimeUnit unit) throws Exception {
            long totalTimeoutMillis = unit.toMillis(timeout);
            long deadline = System.currentTimeMillis() + totalTimeoutMillis;
            while (true) {
                long remainingMillis = deadline - System.currentTimeMillis();
                if (remainingMillis <= 0) {
                    throw buildTimeoutException(timeout, unit);
                }
                long pollMillis = Math.min(remainingMillis, TimeUnit.SECONDS.toMillis(POLL_INTERVAL_SECONDS));
                try {
                    return answerFuture.get(pollMillis, TimeUnit.MILLISECONDS);
                } catch (TimeoutException pollTimeout) {
                    maybeSendFollowUp(webSocket);
                }
            }
        }

        private TimeoutException buildTimeoutException(long timeout, TimeUnit unit) {
            return new TimeoutException("等待 SemiClaw WebSocket 最终回答超时: timeout="
                    + unit.toSeconds(timeout) + "s, receivedMessageCount=" + receivedMessageCount
                    + ", chunkMessageCount=" + chunkMessageCount + ", doneMessageCount=" + doneMessageCount
                    + ", currentAnswerLength=" + chunkBuffer.length() + ", lastType=" + lastMessageType
                    + ", closeStatus=" + lastCloseStatus + ", closeReason=" + lastCloseReason
                    + ", followUpCount=" + followUpCount + ", lastMessage=" + lastMessagePreview);
        }

        private void maybeSendFollowUp(WebSocket webSocket) {
            int maxCount = resolveMaxFollowUpCount();
            if (followUpCount >= maxCount || answerFuture.isDone() || lastGeneratedDocPath == null) return;
            long silenceMillis = System.currentTimeMillis() - lastMessageTime;
            if (silenceMillis < TimeUnit.SECONDS.toMillis(SILENCE_THRESHOLD_SECONDS)) return;
            followUpCount++;
            String followUpText = resolveFollowUpText();
            try {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("client_request_id", UUID.randomUUID().toString());
                payload.put("ui_lang", "zh-CN");
                payload.put("content", followUpText);
                payload.put("display_content", followUpText);
                String message = objectMapper.writeValueAsString(payload);
                log.info("SemiClaw WS 检测到生成文件后静默 {} 秒，主动发送 {}/{} 次追问: expectedDocType={}, lastGeneratedDocPath={}",
                        silenceMillis / 1000, followUpCount, maxCount, expectedDocType, lastGeneratedDocPath);
                webSocket.sendText(message, true).get(10, TimeUnit.SECONDS);
                lastMessageTime = System.currentTimeMillis();
            } catch (Exception e) {
                log.warn("SemiClaw WS 发送恢复追问失败: {}", e.getMessage());
            }
        }

        private int resolveMaxFollowUpCount() {
            if (isHtmlExpectedDocType(expectedDocType)) return HTML_MAX_FOLLOW_UP_COUNT;
            if (PptTypeUtils.isPptExpectedType(expectedDocType)) return PPT_MAX_FOLLOW_UP_COUNT;
            return DEFAULT_MAX_FOLLOW_UP_COUNT;
        }

        private String resolveFollowUpText() {
            if (isHtmlExpectedDocType(expectedDocType)) return HTML_FOLLOW_UP_TEXT;
            return PPT_FOLLOW_UP_TEXT;
        }

        private boolean isHtmlExpectedDocType(String type) {
            return "html".equalsIgnoreCase(type) || "htm".equalsIgnoreCase(type);
        }

        private String summarizeMessage(String message) {
            if (message == null) return "";
            String value = message.replaceAll("[\\r\\n\\t]+", " ").trim();
            return value.length() <= 300 ? value : value.substring(0, 300) + "...";
        }
    }

    private Map<String, Object> buildFailureResult(Throwable throwable, long startTime) {
        Throwable root = unwrap(throwable);
        String message = root.getMessage();
        if (isBlank(message)) {
            message = root.getClass().getSimpleName();
        }
        long responseTime = System.currentTimeMillis() - startTime;
        log.warn("SemiClaw 多模态调用失败: {}", message, root);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("answer", "调用 SemiClaw 多模态失败: " + message);
        result.put("responseTime", responseTime);
        return result;
    }

    private Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current != null
                && current.getCause() != null
                && (current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException
                || current instanceof RuntimeException && current.getClass() == RuntimeException.class)) {
            current = current.getCause();
        }
        return current == null ? throwable : current;
    }

    private boolean isTokenInvalidSessionError(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (!isBlank(message)) {
                String lower = message.toLowerCase(Locale.ROOT);
                if (lower.contains("status=401") || lower.contains("status 401")
                        || lower.contains("auth_kicked_out")
                        || lower.contains("account logged in elsewhere")
                        || lower.contains("unauthorized")
                        || lower.contains("token expired")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private void validateRequired(String name, String value) {
        if (isBlank(value)) {
            throw new IllegalArgumentException(name + " 不能为空");
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
        if (url == null) return null;
        return url
                .replaceAll("(?i)(token=)[^&]+", "$1***")
                .replaceAll("(?i)(ticket=)[^&]+", "$1***")
                .replaceAll("(?i)(session_id=)[^&]+", "$1***")
                .replaceAll("(?i)(tenant_id=)[^&]+", "$1***")
                .replaceAll("(?i)(password=)[^&]+", "$1***")
                .replaceAll("(?i)(authorization=)[^&]+", "$1***");
    }

    private String maskValue(String value) {
        if (isBlank(value)) return "";
        if (value.length() <= 4) return "***";
        return value.substring(0, 2) + "***" + value.substring(value.length() - 2);
    }

    private String maskUsername(String username) {
        if (isBlank(username)) return "";
        int atIndex = username.indexOf('@');
        if (atIndex > 1) {
            return username.charAt(0) + "***" + username.substring(atIndex);
        }
        if (username.length() <= 2) return "***";
        return username.substring(0, 1) + "***" + username.substring(username.length() - 1);
    }

    private String summarizeBody(String body) {
        if (body == null) return "";
        String value = sanitizeUrl(body.trim());
        if (value.length() <= 500) return value;
        return value.substring(0, 500) + "...";
    }

    private String summarizeMessage(String message) {
        if (message == null) return "";
        String value = message.replaceAll("[\\r\\n\\t]+", " ").trim();
        return value.length() <= 300 ? value : value.substring(0, 300) + "...";
    }

    private String encode(String value) {
        return value == null ? "" : URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private Object firstPresent(Map<?, ?> map, String... keys) {
        if (map == null || keys == null) return null;
        for (String key : keys) {
            if (map.containsKey(key)) {
                Object value = map.get(key);
                if (value != null) return value;
            }
        }
        return null;
    }

    private boolean isTruthy(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean flag) return flag;
        String text = value.toString().trim().toLowerCase(Locale.ROOT);
        return "true".equals(text) || "1".equals(text) || "yes".equals(text) || "y".equals(text);
    }

    private boolean isDataUri(String value) {
        if (value == null) return false;
        String text = value.trim().toLowerCase(Locale.ROOT);
        return text.startsWith("data:image/") || text.startsWith("data:application/")
                || text.startsWith("data:text/");
    }

    private boolean isBase64Like(String value) {
        if (value == null) return false;
        String text = value.trim();
        if (text.length() < 120 || text.startsWith("http://") || text.startsWith("https://")
                || text.startsWith("/") || text.contains("://")) {
            return false;
        }
        return text.matches("^[A-Za-z0-9+/=\\r\\n]+$");
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (!isBlank(value)) return value;
        }
        return null;
    }

    private static SSLContext buildTrustAllSslContext() {
        try {
            TrustManager[] trustAll = new TrustManager[] {
                    new X509TrustManager() {
                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }

                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {
                        }
                    }
            };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAll, new SecureRandom());
            return sslContext;
        } catch (Exception e) {
            throw new RuntimeException("初始化 SemiClaw 信任所有证书的 SSLContext 失败", e);
        }
    }

    private static class SessionContext {
        private final String sessionId;
        private final String accessToken;

        private SessionContext(String sessionId, String accessToken) {
            this.sessionId = sessionId;
            this.accessToken = accessToken;
        }
    }

    private static class WsChatResult {
        private final String answer;
        private final String generatedFilePath;

        private WsChatResult(String answer, String generatedFilePath) {
            this.answer = answer;
            this.generatedFilePath = generatedFilePath;
        }
    }

    private static class UploadBatchResult {
        private final List<Map<String, Object>> uploadedFiles = new ArrayList<>();
        private List<MediaResource> resources = new ArrayList<>();
        private String uploadError;
    }

    private static class MediaResource {
        private final String fileName;
        private final String contentType;
        private final byte[] bytes;
        @SuppressWarnings("unused")
        private final String source;

        private MediaResource(String fileName, String contentType, byte[] bytes, String source) {
            this.fileName = fileName;
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
    }
}
