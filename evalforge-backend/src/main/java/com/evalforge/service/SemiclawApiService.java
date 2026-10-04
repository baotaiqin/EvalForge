package com.evalforge.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * SemiClaw 平台 API 调用服务
 *
 * 当前职责：
 * 1. 登录 /api/auth/login 获取 access_token
 * 2. 调用 /api/agents/ 获取 Agent 列表
 *
 * 注意：
 * 1. 真正评测聊天链路暂不在本类实现，后续会在 SemiclawAgentChatService 中接 WebSocket。
 * 2. SemiClaw 当前是内网 HTTPS，自签证书不在 Java 默认信任链中。
 *    本类支持通过配置项 semiclaw.ssl.trust-all 跳过证书校验。
 * 3. 该服务只在 EnvAgentService 判断 platform=semiclaw 后调用，不影响 SemiMind 逻辑。
 * 4. 支持两种登录信息来源：
 *    - 环境配置直接传 tenantId / username / password：兼容原个人账号方式。
 *    - application 配置中的 semiclaw.service-accounts：新增服务账号方式。
 */
@Service
public class SemiclawApiService {

    private static final Logger log = LoggerFactory.getLogger(SemiclawApiService.class);

    /**
     * token 缓存时间。
     * 当前按 25 分钟缓存，避免频繁登录。
     * 后续如果确认 access_token 有明确 expires_in，再改成读取服务端过期时间。
     */
    private static final long TOKEN_CACHE_TTL = 25 * 60 * 1000L;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 后端配置的 SemiClaw 服务账号。 */
    private final List<SemiclawServiceAccount> serviceAccounts;

    /** token 缓存：key = normalizedBaseUrl|tenantId|username */
    private final Map<String, Map<String, Object>> tokenCache = new ConcurrentHashMap<>();

    /** 登录锁：避免同一 SemiClaw 环境同一账号同时触发重复登录。 */
    private final Map<String, Object> loginLocks = new ConcurrentHashMap<>();

    /**
     * 后端配置示例：
     * semiclaw.service-accounts.keys=sit,prod
     * semiclaw.service-accounts.sit.name=semiclaw-sit
     * semiclaw.service-accounts.sit.base-url=https://demo-claw-sit.example.invalid:7630
     * semiclaw.service-accounts.sit.tenant-id=xxx
     * semiclaw.service-accounts.sit.username=xxx
     * semiclaw.service-accounts.sit.password=xxx
     * semiclaw.service-accounts.sit.enabled=true
     *
     * token 缓存：key = normalizedBaseUrl|tenantId|username
     * 登录锁：key = normalizedBaseUrl|tenantId|username
     * 作用：同一个 SemiClaw 环境、同一个 tenant、同一个账号，同时只允许一个线程真正发起 login。
     * 避免多题并发评测时多个线程同时登录同一账号，导致 SemiClaw 平台互踢 token。
     */
    public SemiclawApiService(
            @Value("${semiclaw.ssl.trust-all:false}") boolean trustAllSsl,
            Environment environment
    ) {
        HttpClient.Builder httpClientBuilder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15));

        if (trustAllSsl) {
            httpClientBuilder.sslContext(buildTrustAllSslContext());
        }

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClientBuilder.build());
        factory.setReadTimeout(Duration.ofSeconds(30));
        this.restTemplate = new RestTemplate(factory);
        this.serviceAccounts = loadServiceAccounts(environment);

        if (trustAllSsl) {
            log.warn("SemiClaw HTTPS 证书校验已配置为跳过，仅建议在内网 SIT/临时环境使用");
        } else {
            log.info("SemiClaw HTTPS 证书校验使用 Java 默认信任链");
        }

        if (serviceAccounts.isEmpty()) {
            log.info("未加载到 SemiClaw 服务账号配置，将仅支持环境配置中的 tenantId/username/password 登录方式");
        } else {
            log.info("已加载 SemiClaw 服务账号配置: count={}, accounts={}",
                    serviceAccounts.size(), serviceAccountDescriptions(serviceAccounts));
        }
    }

    /**
     * 查询 SemiClaw Agent 列表。
     *
     * @param baseUrl 前端环境 URL，允许传 demo-claw-prod.example.invalid:8630 或 https://demo-claw-prod.example.invalid:8630
     * @param tenantId SemiClaw tenant id；为空时会尝试读取服务账号配置
     * @param username SemiClaw 登录账号；为空时会尝试读取服务账号配置
     * @param password SemiClaw 登录密码；为空时会尝试读取服务账号配置
     * @return 兼容前端 AgentSelector 的 Agent 列表
     */
    public List<Map<String, Object>> listAgents(String baseUrl,
                                                 String tenantId,
                                                 String username,
                                                 String password) {
        SemiclawCredential credential = resolveCredential(baseUrl, tenantId, username, password);

        try {
            return doListAgents(
                    credential.normalizedBaseUrl,
                    credential.tenantId,
                    credential.username,
                    credential.password
            );
        } catch (HttpClientErrorException.Unauthorized e) {
            log.warn("SemiClaw token 可能失效，清理缓存后重试一次: baseUrl={}, tenantId={}, username={}",
                    sanitizeUrl(credential.normalizedBaseUrl),
                    maskValue(credential.tenantId),
                    maskUsername(credential.username));
            invalidateToken(
                    credential.normalizedBaseUrl,
                    credential.tenantId,
                    credential.username
            );
            return doListAgents(
                    credential.normalizedBaseUrl,
                    credential.tenantId,
                    credential.username,
                    credential.password
            );
        }
    }

    private List<Map<String, Object>> doListAgents(String baseUrl,
                                                    String tenantId,
                                                    String username,
                                                    String password) {
        Map<String, String> loginResult = login(baseUrl, tenantId, username, password);
        String accessToken = loginResult.get("accessToken");
        String normalizedBaseUrl = normalizeBaseUrl(baseUrl);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        ResponseEntity<String> response = requestAgents(normalizedBaseUrl, tenantId, headers);
        String responseBody = response.getBody();

        log.info("SemiClaw Agent 原始响应状态: status={}", response.getStatusCode());
        log.debug("SemiClaw Agent 原始响应长度: length={}", responseBody == null ? 0 : responseBody.length());

        if (response.getStatusCode().is3xxRedirection()) {
            URI location = response.getHeaders().getLocation();
            throw new RuntimeException("SemiClaw Agent 接口返回重定向，未获取到有效 Agent 数据: status="
                    + response.getStatusCode() + ", location=" + sanitizeUrl(location));
        }

        if (isBlank(responseBody)) {
            log.warn("SemiClaw Agent 接口返回空响应体: tenantId={}", maskValue(tenantId));
            return new ArrayList<>();
        }

        try {
            Object parsed = objectMapper.readValue(responseBody, Object.class);
            List<Map<String, Object>> rawAgents = extractList(parsed);
            log.info("SemiClaw Agent 解析后的原始数量: rawCount={}", rawAgents.size());
            if (!rawAgents.isEmpty()) {
                log.debug("SemiClaw Agent 第一条原始数据字段: keys={}", rawAgents.get(0).keySet());
            }

            List<Map<String, Object>> result = new ArrayList<>();
            for (Map<String, Object> raw : rawAgents) {
                Map<String, Object> agent = normalizeAgent(raw);
                if (agent.get("id") != null) {
                    result.add(agent);
                } else {
                    log.warn("SemiClaw Agent 缺少可识别 ID，已跳过");
                }
            }

            log.info("SemiClaw Agent 列表加载完成: tenantId={}, count={}", maskValue(tenantId), result.size());
            return result;
        } catch (Exception e) {
            throw new RuntimeException("解析 SemiClaw Agent 列表失败: " + e.getMessage(), e);
        }
    }

    /**
     * 请求 SemiClaw Agent 列表。
     * 这里优先使用 /api/agents/。
     * 之前请求 /api/agents 时，SemiClaw 返回 307 TEMPORARY_REDIRECT 且 body=null，
     * 导致后续解析为空列表。
     */
    private ResponseEntity<String> requestAgents(String normalizedBaseUrl,
                                                 String tenantId,
                                                 HttpHeaders headers) {
        URI uri = buildAgentsUri(normalizedBaseUrl, tenantId);
        log.info("调用 SemiClaw Agent 列表接口: baseUrl={}, path=/api/agents/, tenantId={}",
                sanitizeUrl(normalizedBaseUrl), maskValue(tenantId));

        ResponseEntity<String> response = restTemplate.exchange(
                uri,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        if (!response.getStatusCode().is3xxRedirection()) {
            return response;
        }

        URI location = response.getHeaders().getLocation();
        log.warn("SemiClaw Agent 接口返回重定向: status={}, location={}",
                response.getStatusCode(), sanitizeUrl(location));
        if (location == null) {
            return response;
        }

        URI redirectUri = normalizeRedirectUri(normalizedBaseUrl, location);
        log.info("跟随 SemiClaw Agent 重定向地址: baseUrl={}, location={}",
                sanitizeUrl(normalizedBaseUrl), sanitizeUrl(redirectUri));

        return restTemplate.exchange(
                redirectUri,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );
    }

    private URI buildAgentsUri(String normalizedBaseUrl, String tenantId) {
        return UriComponentsBuilder
                .fromUriString(normalizedBaseUrl + "/api/agents/")
                .queryParam("tenant_id", tenantId)
                .queryParam("include_private", true)
                .build()
                .encode()
                .toUri();
    }

    private URI normalizeRedirectUri(String normalizedBaseUrl, URI location) {
        if (location.isAbsolute()) {
            return location;
        }

        String locationText = location.toString();
        if (locationText.startsWith("/")) {
            return URI.create(normalizedBaseUrl + locationText);
        }
        return URI.create(normalizedBaseUrl + "/" + locationText);
    }

    /**
     * 登录 SemiClaw，获取 access_token。
     *
     * 这里增加了同账号同环境的同步：
     * 1. 先无锁读取缓存，命中则直接返回。
     * 2. 缓存未命中时，根据 normalizedBaseUrl|tenantId|username 加锁。
     * 3. 进入锁后再次读取缓存，避免其他线程刚刚登录成功但本线程重复登录。
     * 4. 只有一次缓存未命中时，才真正请求 SemiClaw 登录接口。
     *
     * 兼容说明：
     * 1. 如果 tenantId / username / password 三项都存在，使用环境中的个人账号配置。
     * 2. 如果三项缺失或不完整，根据 baseUrl 使用服务账号配置。
     */
    public Map<String, String> login(String baseUrl,
                                     String tenantId,
                                     String username,
                                     String password) {
        SemiclawCredential credential = resolveCredential(baseUrl, tenantId, username, password);
        String normalizedBaseUrl = credential.normalizedBaseUrl;
        String cacheKey = buildTokenCacheKey(normalizedBaseUrl, credential.tenantId, credential.username);

        Map<String, String> cachedResult = getValidCachedLoginResult(cacheKey);
        if (cachedResult != null) {
            return cachedResult;
        }

        Object loginLock = loginLocks.computeIfAbsent(cacheKey, key -> new Object());
        synchronized (loginLock) {
            cachedResult = getValidCachedLoginResult(cacheKey);
            if (cachedResult != null) {
                return cachedResult;
            }

            return doLogin(
                    normalizedBaseUrl,
                    credential.tenantId,
                    credential.username,
                    credential.password,
                    cacheKey
            );
        }
    }

    private Map<String, String> getValidCachedLoginResult(String cacheKey) {
        Map<String, Object> cached = tokenCache.get(cacheKey);
        if (cached == null) {
            return null;
        }

        Object timestampObject = cached.get("timestamp");
        long timestamp = timestampObject instanceof Number
                ? ((Number) timestampObject).longValue()
                : -1;
        if (timestamp <= 0 || System.currentTimeMillis() - timestamp >= TOKEN_CACHE_TTL) {
            tokenCache.remove(cacheKey);
            return null;
        }

        String accessToken = stringValue(cached.get("accessToken"));
        if (isBlank(accessToken)) {
            tokenCache.remove(cacheKey);
            return null;
        }

        Map<String, String> result = new HashMap<>();
        result.put("accessToken", accessToken);
        result.put("apiUsername", stringValue(cached.get("apiUsername")));
        return result;
    }

    private Map<String, String> doLogin(String normalizedBaseUrl,
                                        String tenantId,
                                        String username,
                                        String password,
                                        String cacheKey) {
        String url = normalizedBaseUrl + "/api/auth/login";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        Map<String, Object> body = new HashMap<>();
        body.put("username", username);
        body.put("password", password);

        log.info("登录 SemiClaw: baseUrl={}, path=/api/auth/login, tenantId={}, username={}",
                sanitizeUrl(normalizedBaseUrl), maskValue(tenantId), maskUsername(username));

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    url,
                    new HttpEntity<>(body, headers),
                    String.class
            );

            String responseBody = response.getBody();
            if (isBlank(responseBody)) {
                throw new RuntimeException("SemiClaw 登录接口未返回响应体");
            }

            Map<?, ?> resp = objectMapper.readValue(responseBody, Map.class);
            String accessToken = firstNonBlank(
                    stringValue(resp.get("access_token")),
                    stringValue(resp.get("accessToken")),
                    stringValue(resp.get("token"))
            );
            if (isBlank(accessToken)) {
                throw new RuntimeException("无法从 SemiClaw 登录响应中提取 access_token");
            }

            String apiUsername = username;
            Object userObject = resp.get("user");
            if (userObject instanceof Map<?, ?> userMap) {
                apiUsername = firstNonBlank(
                        stringValue(userMap.get("username")),
                        stringValue(userMap.get("email")),
                        stringValue(userMap.get("id")),
                        username
                );
            }

            Map<String, String> result = new HashMap<>();
            result.put("accessToken", accessToken);
            result.put("apiUsername", apiUsername);

            Map<String, Object> cacheEntry = new HashMap<>();
            cacheEntry.put("accessToken", accessToken);
            cacheEntry.put("apiUsername", apiUsername);
            cacheEntry.put("timestamp", System.currentTimeMillis());
            tokenCache.put(cacheKey, cacheEntry);

            log.info("SemiClaw 登录成功: tenantId={}, username={}",
                    maskValue(tenantId), maskUsername(username));
            return result;
        } catch (HttpClientErrorException.Unauthorized e) {
            tokenCache.remove(cacheKey);
            throw new RuntimeException("SemiClaw 登录失败：账号或密码错误", e);
        } catch (Exception e) {
            tokenCache.remove(cacheKey);
            throw new RuntimeException("SemiClaw 登录请求失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取 SemiClaw WebSocket 连接专用短时票据(ticket)。
     *
     * SemiClaw WS 网关升级后，直接使用登录 access_token 连接 WS 会被拒绝(403 CheckFailedException)。
     * 新协议要求先 POST /api/ws/ticket 获取一次性短时 ticket(约30秒有效)，再拿 ticket 去连
     * wss://.../ws/chat/{agentId}?ticket=xxx&session_id=xxx (不再需要 token / tenant_id)。
     *
     * 注意：ticket 有效期很短，调用方必须拿到后立即发起 WS 连接，不能提前缓存复用。
     */
    public String getWsTicket(String normalizedBaseUrl, String accessToken) {
        String url = normalizedBaseUrl + "/api/ws/ticket";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        Map<String, Object> body = new HashMap<>();
        body.put("purpose", "chat");

        log.info("获取 SemiClaw WS ticket: baseUrl={}, path=/api/ws/ticket", sanitizeUrl(normalizedBaseUrl));

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    url,
                    new HttpEntity<>(body, headers),
                    String.class
            );

            String responseBody = response.getBody();
            if (isBlank(responseBody)) {
                throw new RuntimeException("SemiClaw ticket 接口未返回响应体");
            }

            Map<?, ?> resp = objectMapper.readValue(responseBody, Map.class);
            String ticket = stringValue(resp.get("ticket"));
            if (isBlank(ticket)) {
                throw new RuntimeException("无法从 SemiClaw ticket 响应中提取 ticket");
            }

            return ticket;
        } catch (Exception e) {
            throw new RuntimeException("SemiClaw 获取 WS ticket 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 用已解析好的凭证登录并返回 accessToken，供本类内部 SemiClaw 管理接口复用，
     * 避免每个方法各自重复“resolveCredential + login”模板代码。
     */
    private String authenticate(SemiclawCredential credential) {
        return login(
                credential.normalizedBaseUrl,
                credential.tenantId,
                credential.username,
                credential.password
        ).get("accessToken");
    }

    /**
     * 导入 SemiClaw 数字员工压缩包。
     *
     * 对应 SemiClaw 前端“导入数字员工”按钮触发的接口(非包含确认)：
     * POST /api/agents/import, multipart/form-data, 字段：file(tar.gz) + tenant_id。
     * 仅管理员账号有权调用。
     *
     * 返回中的 readiness_level="partial" / has_model_fallback=true 表示导入模型需要迁移，
     * 前端后续调用 updateAgentModel 根据模型映射修复 primary/fallback。
     * 不要求 Controller 层理解 SemiClaw 原始响应字段。
     */
    public Map<String, Object> importAgent(String baseUrl,
                                           String tenantId,
                                           String username,
                                           String password,
                                           byte[] fileBytes,
                                           String filename) {
        SemiclawCredential credential = resolveCredential(baseUrl, tenantId, username, password);
        String accessToken = authenticate(credential);

        String url = credential.normalizedBaseUrl + "/api/agents/import";
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        ByteArrayResource fileResource = new ByteArrayResource(fileBytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        body.add("file", fileResource);
        body.add("tenant_id", credential.tenantId);

        log.info("导入 SemiClaw 数字员工: baseUrl={}, tenantId={}, filename={}",
                sanitizeUrl(credential.normalizedBaseUrl), maskValue(credential.tenantId), filename);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    url,
                    new HttpEntity<>(body, headers),
                    String.class
            );
            String responseBody = response.getBody();
            if (isBlank(responseBody)) {
                throw new RuntimeException("SemiClaw 导入接口未返回响应体");
            }

            Map<String, Object> result = objectMapper.readValue(
                    responseBody,
                    new TypeReference<Map<String, Object>>() { }
            );
            boolean needsModelFix = Boolean.TRUE.equals(result.get("has_model_fallback"))
                    || "partial".equalsIgnoreCase(stringValue(result.get("readiness_level")));
            result.put("needsModelFix", needsModelFix);

            log.info("SemiClaw 数字员工导入完成: agentId={}, readinessLevel={}",
                    stringValue(result.get("agent_id")), stringValue(result.get("readiness_level")));
            return result;
        } catch (Exception e) {
            throw new RuntimeException("SemiClaw 导入数字员工失败: " + e.getMessage(), e);
        }
    }

    /**
     * 查询 SemiClaw 用户可用模型列表(模型池)。
     *
     * 对应企业后台设置 -> 模型池页面加载时调用的接口(非包确认)：
     * GET /api/enterprise/llm-models?tenant_id=xxx
     * 用于前端“导入数字员工”弹窗里模型/备选模型下拉框的数据源。
     */
    public List<Map<String, Object>> listModels(String baseUrl,
                                                String tenantId,
                                                String username,
                                                String password) {
        SemiclawCredential credential = resolveCredential(baseUrl, tenantId, username, password);
        String accessToken = authenticate(credential);

        URI uri = UriComponentsBuilder
                .fromUriString(credential.normalizedBaseUrl + "/api/enterprise/llm-models")
                .queryParam("tenant_id", credential.tenantId)
                .build()
                .encode()
                .toUri();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    uri,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    String.class
            );
            String responseBody = response.getBody();
            if (isBlank(responseBody)) {
                return List.of();
            }
            return objectMapper.readValue(
                    responseBody,
                    new TypeReference<List<Map<String, Object>>>() { }
            );
        } catch (Exception e) {
            throw new RuntimeException("获取 SemiClaw 模型列表失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取 SemiClaw 技能文件夹(用于导入前后快照，对比本次导入新增/因跨租户冲突重命名的技能文件夹)。
     *
     * 对应企业后台“技能管理”页面加载时调用的接口(非包确认)：
     * GET /api/skills/browse/list?path=xxx，返回目录下的文件/文件夹项(name/path/is_dir/size)
     */
    public List<Map<String, Object>> listSkillFolders(String baseUrl,
                                                      String tenantId,
                                                      String username,
                                                      String password,
                                                      String path) {
        SemiclawCredential credential = resolveCredential(baseUrl, tenantId, username, password);
        String accessToken = authenticate(credential);

        URI uri = UriComponentsBuilder
                .fromUriString(credential.normalizedBaseUrl + "/api/skills/browse/list")
                .queryParam("path", path == null ? "" : path)
                .build()
                .encode()
                .toUri();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    uri,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    String.class
            );
            String responseBody = response.getBody();
            if (isBlank(responseBody)) {
                return List.of();
            }
            return objectMapper.readValue(
                    responseBody,
                    new TypeReference<List<Map<String, Object>>>() { }
            );
        } catch (Exception e) {
            throw new RuntimeException("获取 SemiClaw 技能文件夹列表失败: " + e.getMessage(), e);
        }
    }

    /**
     * 删除 SemiClaw 技能文件夹(用于清理导入冲突产生的重复项)。
     *
     * 对应企业后台“技能管理”页面删除按钮的接口(非包确认)：
     * DELETE /api/skills/browse/delete?path=xxx
     * 是否删除前端由用户勾选决定，本方法只负责发送删除请求。
     */
    public void deleteSkillFolder(String baseUrl,
                                  String tenantId,
                                  String username,
                                  String password,
                                  String path) {
        SemiclawCredential credential = resolveCredential(baseUrl, tenantId, username, password);
        String accessToken = authenticate(credential);

        URI uri = UriComponentsBuilder
                .fromUriString(credential.normalizedBaseUrl + "/api/skills/browse/delete")
                .queryParam("path", path)
                .build()
                .encode()
                .toUri();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    uri,
                    HttpMethod.DELETE,
                    new HttpEntity<>(headers),
                    String.class
            );
            String responseBody = response.getBody();
            Map<String, Object> body = isBlank(responseBody)
                    ? Map.of()
                    : objectMapper.readValue(responseBody, new TypeReference<Map<String, Object>>() { });
            if (Boolean.FALSE.equals(body.get("ok"))) {
                throw new RuntimeException("SemiClaw 删除技能文件夹失败: " + responseBody);
            }
        } catch (RestClientResponseException e) {
            throw new RuntimeException("删除技能文件夹失败: " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            throw new RuntimeException("删除技能文件夹失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取 SemiClaw Agent 详情(用于导入后查看/确认当前主模型与备选模型ID)。
     */
    public Map<String, Object> getAgentDetail(String baseUrl,
                                              String tenantId,
                                              String username,
                                              String password,
                                              String agentId) {
        SemiclawCredential credential = resolveCredential(baseUrl, tenantId, username, password);
        String accessToken = authenticate(credential);
        return fetchAgentDetail(credential, accessToken, agentId);
    }

    private Map<String, Object> fetchAgentDetail(SemiclawCredential credential,
                                                  String accessToken,
                                                  String agentId) {
        String url = credential.normalizedBaseUrl + "/api/agents/" + agentId;

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    String.class
            );
            String responseBody = response.getBody();
            if (isBlank(responseBody)) {
                throw new RuntimeException("SemiClaw Agent 详情接口未返回响应体");
            }
            return objectMapper.readValue(
                    responseBody,
                    new TypeReference<Map<String, Object>>() { }
            );
        } catch (Exception e) {
            throw new RuntimeException("获取 SemiClaw Agent 详情失败: " + e.getMessage(), e);
        }
    }

    /** 更新 SemiClaw Agent 的主模型 / 备选模型。PATCH /api/agents/{agentId}。 */
    private static final List<String> AGENT_SETTINGS_FIELDS = List.of(
            "context_window_size",
            "max_tool_rounds",
            "max_tokens_per_day",
            "max_tokens_per_month",
            "max_triggers",
            "mcp_network_enabled",
            "mcp_network_egress_enabled",
            "min_poll_interval_min",
            "fallback_model_id",
            "primary_model_id",
            "proactive_confidence_threshold",
            "proactive_daily_token_budget",
            "proactive_max_rounds",
            "proactive_mode",
            "proactive_model_id",
            "scope",
            "tags",
            "webhook_rate_limit"
    );

    public Map<String, Object> updateAgentModel(String baseUrl,
                                                String tenantId,
                                                String username,
                                                String password,
                                                String agentId,
                                                String primaryModelId,
                                                String fallbackModelId) {
        return updateAgentModel(
                baseUrl,
                tenantId,
                username,
                password,
                agentId,
                primaryModelId,
                fallbackModelId,
                null
        );
    }

    public Map<String, Object> updateAgentModel(String baseUrl,
                                                String tenantId,
                                                String username,
                                                String password,
                                                String agentId,
                                                String primaryModelId,
                                                String fallbackModelId,
                                                String scope) {
        SemiclawCredential credential = resolveCredential(baseUrl, tenantId, username, password);
        String accessToken = authenticate(credential);
        Map<String, Object> current = fetchAgentDetail(credential, accessToken, agentId);

        Map<String, Object> patchBody = new HashMap<>();
        for (String field : AGENT_SETTINGS_FIELDS) {
            Object value = current.get(field);
            if (value != null) {
                patchBody.put(field, value);
            }
        }

        if (!isBlank(primaryModelId)) {
            patchBody.put("primary_model_id", primaryModelId);
        }
        if (!isBlank(fallbackModelId)) {
            patchBody.put("fallback_model_id", fallbackModelId);
        }
        if (!isBlank(scope)) {
            patchBody.put("scope", scope);
        }

        String url = credential.normalizedBaseUrl + "/api/agents/" + agentId;
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        log.info("更新 SemiClaw Agent 模型配置: baseUrl={}, agentId={}, primaryModelId={}, fallbackModelId={}",
                sanitizeUrl(credential.normalizedBaseUrl), agentId, primaryModelId, fallbackModelId);

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.PATCH,
                    new HttpEntity<>(patchBody, headers),
                    String.class
            );
            String responseBody = response.getBody();
            if (isBlank(responseBody)) {
                return current;
            }
            return objectMapper.readValue(
                    responseBody,
                    new TypeReference<Map<String, Object>>() { }
            );
        } catch (RestClientResponseException e) {
            log.error("更新 SemiClaw Agent 模型配置失败，响应体: {}", e.getResponseBodyAsString());
            throw new RuntimeException("更新 SemiClaw Agent 模型配置失败: " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            throw new RuntimeException("更新 SemiClaw Agent 模型配置失败: " + e.getMessage(), e);
        }
    }

    /** 设置 SemiClaw Agent 的访问权限。 */
    public void setAgentPermissions(String baseUrl,
                                    String tenantId,
                                    String username,
                                    String password,
                                    String agentId,
                                    String scopeType,
                                    String accessLevel) {
        SemiclawCredential credential = resolveCredential(baseUrl, tenantId, username, password);
        String accessToken = authenticate(credential);

        Map<String, Object> body = new HashMap<>();
        body.put("scope_type", scopeType);
        body.put("scope_ids", List.of());
        body.put("access_level", accessLevel);

        String url = credential.normalizedBaseUrl + "/api/agents/" + agentId + "/permissions";
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        log.info("设置 SemiClaw Agent 访问权限: baseUrl={}, agentId={}, scopeType={}, accessLevel={}",
                sanitizeUrl(credential.normalizedBaseUrl), agentId, scopeType, accessLevel);

        try {
            restTemplate.exchange(url, HttpMethod.PUT, new HttpEntity<>(body, headers), String.class);
        } catch (RestClientResponseException e) {
            log.error("设置 SemiClaw Agent 访问权限失败，响应体: {}", e.getResponseBodyAsString());
            throw new RuntimeException("设置 SemiClaw Agent 访问权限失败: " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            throw new RuntimeException("设置 SemiClaw Agent 访问权限失败: " + e.getMessage(), e);
        }
    }

    public void invalidateToken(String baseUrl, String tenantId, String username) {
        String normalizedBaseUrl = normalizeBaseUrl(baseUrl);
        String actualTenantId = tenantId;
        String actualUsername = username;

        if (isBlank(actualTenantId) || isBlank(actualUsername)) {
            try {
                SemiclawCredential credential = resolveCredential(baseUrl, tenantId, username, null);
                actualTenantId = credential.tenantId;
                actualUsername = credential.username;
                normalizedBaseUrl = credential.normalizedBaseUrl;
            } catch (Exception e) {
                log.debug("SemiClaw 清理 token 缓存时未能解析完整登录信息，将按传入参数清理: baseUrl={}, reason={}",
                        sanitizeUrl(normalizedBaseUrl), e.getMessage());
            }
        }

        if (isBlank(actualTenantId) || isBlank(actualUsername)) {
            log.debug("SemiClaw 清理 token 缓存时缺少 tenantId 或 username: baseUrl={}", sanitizeUrl(baseUrl));
            return;
        }

        tokenCache.remove(buildTokenCacheKey(normalizedBaseUrl, actualTenantId, actualUsername));
    }

    /**
     * 给其他 SemiClaw 服务使用的登录上下文解析方法。
     * 不返回 password，避免调用方把密码放进缓存或调试日志。
     */
    public Map<String, String> resolveLoginContext(String baseUrl,
                                                   String tenantId,
                                                   String username,
                                                   String password) {
        SemiclawCredential credential = resolveCredential(baseUrl, tenantId, username, password);
        Map<String, String> result = new HashMap<>();
        result.put("baseUrl", credential.normalizedBaseUrl);
        result.put("tenantId", credential.tenantId);
        result.put("username", credential.username);
        result.put("source", credential.source);
        result.put("serviceAccountName", credential.serviceAccountName);
        return result;
    }

    /**
     * 解析 SemiClaw 登录凭证。
     * 1. tenantId / username / password 三项都存在：使用调用方传入的个人账号配置。
     * 2. 三项缺失或不完整：根据 baseUrl 匹配 application 中配置的服务账号。
     */
    private SemiclawCredential resolveCredential(String baseUrl,
                                                 String tenantId,
                                                 String username,
                                                 String password) {
        if (isBlank(baseUrl)) {
            throw new IllegalArgumentException("semiclaw baseUrl 不能为空");
        }

        String normalizedBaseUrl = normalizeBaseUrl(baseUrl);
        if (hasCompleteCredential(tenantId, username, password)) {
            return new SemiclawCredential(
                    normalizedBaseUrl,
                    tenantId.trim(),
                    username.trim(),
                    password,
                    "environment",
                    ""
            );
        }

        if (hasAnyCredential(tenantId, username, password)) {
            log.warn("SemiClaw 环境账号字段不完整，将尝试使用服务账号配置: baseUrl={}, hasTenantId={}, hasUsername={}, hasPassword={}",
                    sanitizeUrl(normalizedBaseUrl),
                    !isBlank(tenantId),
                    !isBlank(username),
                    !isBlank(password));
        }

        SemiclawServiceAccount account = findServiceAccount(normalizedBaseUrl);
        if (account == null) {
            throw new IllegalArgumentException(
                    "semiclaw 登录凭据不完整，且未找到匹配的服务账号配置: baseUrl="
                            + sanitizeUrl(normalizedBaseUrl)
                            + "。请检查 tenantId/username/password 是否完整，或配置 semiclaw.service-accounts。"
            );
        }

        log.debug("SemiClaw 使用后端服务账号配置: account={}, baseUrl={}, tenantId={}, username={}",
                account.name,
                sanitizeUrl(account.normalizedBaseUrl),
                maskValue(account.tenantId),
                maskUsername(account.username));

        return new SemiclawCredential(
                normalizedBaseUrl,
                account.tenantId,
                account.username,
                account.password,
                "service-account",
                account.name
        );
    }

    private boolean hasCompleteCredential(String tenantId, String username, String password) {
        return !isBlank(tenantId) && !isBlank(username) && !isBlank(password);
    }

    private boolean hasAnyCredential(String tenantId, String username, String password) {
        return !isBlank(tenantId) || !isBlank(username) || !isBlank(password);
    }

    private SemiclawServiceAccount findServiceAccount(String baseUrl) {
        if (serviceAccounts.isEmpty()) {
            return null;
        }

        String normalizedBaseUrl = normalizeBaseUrl(baseUrl);
        for (SemiclawServiceAccount account : serviceAccounts) {
            if (sameBaseUrl(normalizedBaseUrl, account.normalizedBaseUrl)) {
                return account;
            }
        }
        return null;
    }

    private List<SemiclawServiceAccount> loadServiceAccounts(Environment environment) {
        List<SemiclawServiceAccount> accounts = new ArrayList<>();
        if (environment == null) {
            return accounts;
        }

        Set<String> keys = new LinkedHashSet<>();
        String configuredKeys = environment.getProperty("semiclaw.service-accounts.keys");
        if (!isBlank(configuredKeys)) {
            for (String part : configuredKeys.split(",")) {
                if (!isBlank(part)) {
                    keys.add(part.trim());
                }
            }
        }

        // 默认兼容常用环境名，即使没有配置 keys，也读取 sit/prod/dev。
        keys.add("dev");
        keys.add("sit");
        keys.add("prod");

        for (String key : keys) {
            String prefix = "semiclaw.service-accounts." + key + ".";
            String baseUrl = readProperty(environment, prefix, "base-url", "baseUrl", "url");
            String tenantId = readProperty(environment, prefix, "tenant-id", "tenantId");
            String username = readProperty(environment, prefix, "username", "user-name", "userName");
            String password = readProperty(environment, prefix, "password");
            String name = firstNonBlank(readProperty(environment, prefix, "name"), key);
            String enabledText = readProperty(environment, prefix, "enabled");

            boolean hasAnyConfig = !isBlank(baseUrl)
                    || !isBlank(tenantId)
                    || !isBlank(username)
                    || !isBlank(password);
            if (!hasAnyConfig) {
                continue;
            }

            boolean enabled = isBlank(enabledText) || Boolean.parseBoolean(enabledText.trim());
            if (!enabled) {
                log.info("SemiClaw 服务账号配置已禁用: key={}, name={}", key, name);
                continue;
            }

            if (isBlank(baseUrl) || isBlank(tenantId) || isBlank(username) || isBlank(password)) {
                log.warn("SemiClaw 服务账号配置字段不完整，已跳过: key={}, name={}, hasBaseUrl={}, hasTenantId={}, hasUsername={}, hasPassword={}",
                        key,
                        name,
                        !isBlank(baseUrl),
                        !isBlank(tenantId),
                        !isBlank(username),
                        !isBlank(password));
                continue;
            }

            String normalizedBaseUrl = normalizeBaseUrl(baseUrl);
            if (accounts.stream().anyMatch(account -> sameBaseUrl(account.normalizedBaseUrl, normalizedBaseUrl))) {
                log.warn("SemiClaw 服务账号 baseUrl 重复，后续配置已跳过: key={}, name={}, baseUrl={}",
                        key, name, sanitizeUrl(normalizedBaseUrl));
                continue;
            }

            accounts.add(new SemiclawServiceAccount(
                    name.trim(),
                    normalizedBaseUrl,
                    tenantId.trim(),
                    username.trim(),
                    password
            ));
        }

        return accounts;
    }

    private String readProperty(Environment environment, String prefix, String... names) {
        if (environment == null || names == null) {
            return null;
        }

        for (String name : names) {
            String value = environment.getProperty(prefix + name);
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private List<String> serviceAccountDescriptions(List<SemiclawServiceAccount> accounts) {
        List<String> descriptions = new ArrayList<>();
        for (SemiclawServiceAccount account : accounts) {
            descriptions.add(account.name + "@" + sanitizeUrl(account.normalizedBaseUrl));
        }
        return descriptions;
    }

    private boolean sameBaseUrl(String left, String right) {
        if (isBlank(left) || isBlank(right)) {
            return false;
        }

        String normalizedLeft = normalizeBaseUrl(left);
        String normalizedRight = normalizeBaseUrl(right);
        if (normalizedLeft.equalsIgnoreCase(normalizedRight)) {
            return true;
        }
        return baseUrlIdentity(normalizedLeft).equalsIgnoreCase(baseUrlIdentity(normalizedRight));
    }

    private String baseUrlIdentity(String baseUrl) {
        if (baseUrl == null) {
            return "";
        }

        String value = baseUrl.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }

        if (value.startsWith("https://")) {
            value = value.substring("https://".length());
        } else if (value.startsWith("http://")) {
            value = value.substring("http://".length());
        }
        return value.toLowerCase();
    }

    private Map<String, Object> normalizeAgent(Map<String, Object> raw) {
        Map<String, Object> agent = new HashMap<>();

        String id = firstNonBlank(
                stringValue(raw.get("id")),
                stringValue(raw.get("agent_id")),
                stringValue(raw.get("agentId")),
                stringValue(raw.get("uid")),
                stringValue(raw.get("agent_uid")),
                stringValue(raw.get("agentUid"))
        );
        String name = firstNonBlank(
                stringValue(raw.get("name")),
                stringValue(raw.get("agent_name")),
                stringValue(raw.get("agentName")),
                stringValue(raw.get("display_name")),
                stringValue(raw.get("displayName")),
                stringValue(raw.get("title")),
                id
        );
        String agentType = firstNonBlank(
                stringValue(raw.get("agent_type")),
                stringValue(raw.get("agentType")),
                stringValue(raw.get("type")),
                "agent"
        );
        String roleDescription = firstNonBlank(
                stringValue(raw.get("role_description")),
                stringValue(raw.get("roleDescription")),
                stringValue(raw.get("description")),
                ""
        );
        Object isExpired = raw.get("is_expired") != null
                ? raw.get("is_expired")
                : raw.get("isExpired");

        agent.put("id", id);
        agent.put("name", name);
        // 新后端创建评测时读取 target.agentId / target.agentName
        agent.put("agentId", id);
        agent.put("agentName", name);
        // SemiMind 前端列表字段：智能体类型使用 bot
        agent.put("type", "bot");
        agent.put("creator", "");
        // 评测平台，后续创建评测和报告分流可用
        agent.put("platform", "semiclaw");
        // camelCase
        agent.put("agentType", agentType);
        agent.put("roleDescription", roleDescription);
        agent.put("isExpired", isExpired);
        // snake_case
        agent.put("agent_type", agentType);
        agent.put("role_description", roleDescription);
        agent.put("is_expired", isExpired);
        agent.put("status", raw.get("status"));
        return agent;
    }

    private List<Map<String, Object>> extractList(Object parsed) {
        if (parsed instanceof List<?> list) {
            return toMapList(list);
        }

        if (parsed instanceof Map<?, ?> map) {
            List<String> keys = List.of(
                    "data",
                    "items",
                    "agents",
                    "results",
                    "list",
                    "rows",
                    "records",
                    "content"
            );
            for (String key : keys) {
                Object value = map.get(key);
                if (value instanceof List<?> list) {
                    return toMapList(list);
                }
                if (value instanceof Map<?, ?>) {
                    List<Map<String, Object>> nested = extractList(value);
                    if (!nested.isEmpty()) {
                        return nested;
                    }
                }
            }
        }

        return new ArrayList<>();
    }

    private List<Map<String, Object>> toMapList(List<?> list) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> rawMap) {
                Map<String, Object> map = new HashMap<>();
                for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                    if (entry.getKey() != null) {
                        map.put(entry.getKey().toString(), entry.getValue());
                    }
                }
                result.add(map);
            }
        }
        return result;
    }

    /**
     * 规范化 SemiClaw baseUrl。
     * 数据库里现有保存的 url 可能是：
     * 1. demo-claw-prod.example.invalid:8630
     * 2. https://demo-claw-prod.example.invalid:8630
     * 3. https://demo-claw-prod.example.invalid:8630/
     * SemiClaw 当前实际 API 是 HTTPS，所以没有协议时默认补 https://
     */
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

    private String buildTokenCacheKey(String baseUrl, String tenantId, String username) {
        return baseUrl + "|" + tenantId + "|" + username;
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private String firstNonBlank(String... values) {
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

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String sanitizeUrl(String url) {
        if (url == null) {
            return null;
        }

        String sanitized = url;
        int queryIndex = sanitized.indexOf('?');
        if (queryIndex >= 0) {
            sanitized = sanitized.substring(0, queryIndex) + "?***";
        }
        return sanitized;
    }

    private String sanitizeUrl(URI uri) {
        if (uri == null) {
            return null;
        }
        return sanitizeUrl(uri.toString());
    }

    private String maskUsername(String username) {
        if (isBlank(username)) {
            return "";
        }

        String value = username.trim();
        int atIndex = value.indexOf('@');
        if (atIndex > 0) {
            String prefix = value.substring(0, atIndex);
            String domain = value.substring(atIndex);
            return maskMiddle(prefix) + domain;
        }
        return maskMiddle(value);
    }

    private String maskValue(String value) {
        if (isBlank(value)) {
            return "";
        }
        return maskMiddle(value.trim());
    }

    private String maskMiddle(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (value.length() <= 2) {
            return "***";
        }
        if (value.length() <= 6) {
            return value.charAt(0) + "***" + value.charAt(value.length() - 1);
        }
        return value.substring(0, 2) + "***" + value.substring(value.length() - 2);
    }

    private static class SemiclawServiceAccount {
        private final String name;
        private final String normalizedBaseUrl;
        private final String tenantId;
        private final String username;
        private final String password;

        private SemiclawServiceAccount(String name,
                                       String normalizedBaseUrl,
                                       String tenantId,
                                       String username,
                                       String password) {
            this.name = name;
            this.normalizedBaseUrl = normalizedBaseUrl;
            this.tenantId = tenantId;
            this.username = username;
            this.password = password;
        }
    }

    private static class SemiclawCredential {
        private final String normalizedBaseUrl;
        private final String tenantId;
        private final String username;
        private final String password;
        private final String source;
        private final String serviceAccountName;

        private SemiclawCredential(String normalizedBaseUrl,
                                   String tenantId,
                                   String username,
                                   String password,
                                   String source,
                                   String serviceAccountName) {
            this.normalizedBaseUrl = normalizedBaseUrl;
            this.tenantId = tenantId;
            this.username = username;
            this.password = password;
            this.source = source;
            this.serviceAccountName = serviceAccountName;
        }
    }

    /**
     * SemiClaw 内网 HTTPS 证书兼容。
     * 当前 SemiClaw 使用的 https://demo-claw-prod.example.invalid:8630 证书不在 Java 默认信任链中，
     * 会导致 RestTemplate 请求时报 failed / unable to find valid certification path。
     * 这个 SSLContext 只用于 SemiclawApiService 自己的 HttpClient，不修改全局 JVM 证书配置，
     * 不影响原有 SemiMind 逻辑。
     *
     * 注意：
     * 只有配置 semiclaw.ssl.trust-all=true 时才启用。
     */
    private static SSLContext buildTrustAllSslContext() {
        try {
            TrustManager[] trustAllManagers = new TrustManager[]{
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
            sslContext.init(null, trustAllManagers, new SecureRandom());
            return sslContext;
        } catch (Exception e) {
            throw new IllegalStateException("初始化 SemiClaw HTTPS 证书兼容配置失败", e);
        }
    }
}
