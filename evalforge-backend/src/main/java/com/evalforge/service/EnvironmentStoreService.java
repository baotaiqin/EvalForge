package com.evalforge.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;

/**
 * 环境配置存储服务 - 基于 MySQL 持久化
 *
 * 兼容：
 * 1. 原有 SemiMind 环境字段：id / name / url / agents / createdAt
 * 2. SemiClaw 新增字段：platform / tenantId / tenant_id / username / password
 * 3. SemiClaw 服务账号模式：环境配置可以不保存 tenantId / username / password，
 *    由后端 application.properties 中的 semiclaw.service-accounts.* 自动提供。
 *
 * 注意：
 * 1. 数据库中只存 tenant_id
 * 2. 返回给前端时同时返回 tenantId 和 tenant_id，兼容 camelCase 与 snake_case
 * 3. 默认情况下 password 为空时不覆盖旧密码，避免编辑环境时误清空密码
 * 4. 如果前端显式传入 loginMode / credentialMode = service-account，
 *    则会清空环境中的 tenant_id / username / password，让 SemiClaw 走后端服务账号配置
 */
@Service
public class EnvironmentStoreService {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentStoreService.class);

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String PLATFORM_SEMICLAW = "semiclaw";
    private static final String PLATFORM_SEMIMIND = "semimind";

    private static final String CREDENTIAL_MODE_PERSONAL = "personal";
    private static final String CREDENTIAL_MODE_SERVICE_ACCOUNT = "service-account";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicInteger idCounter = new AtomicInteger(1);

    @PostConstruct
    public void init() {
        try {
            Integer max = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(MAX(CAST(SUBSTRING_INDEX(id, '-', -1) AS UNSIGNED)), 0) FROM environment",
                    Integer.class
            );
            if (max != null) {
                idCounter.set(max + 1);
            }
        } catch (Exception e) {
            log.warn("初始化环境ID计数器失败，使用默认值：{}", e.getMessage());
        }

        /*
         * 兼容旧库。
         * 你当前已经手动 ALTER TABLE 补过字段，所以这里正常会因为字段已存在而进入 debug 日志。
         * 保留这段是为了以后数据库重新初始化时不容易遗漏字段。
         *
         * 不添加 description，避免影响当前 environment 表结构。
         */
        tryAddColumn("ALTER TABLE environment ADD COLUMN platform VARCHAR(50) DEFAULT 'semimind'");
        tryAddColumn("ALTER TABLE environment ADD COLUMN tenant_id VARCHAR(255) NULL");
        tryAddColumn("ALTER TABLE environment ADD COLUMN username VARCHAR(255) NULL");
        tryAddColumn("ALTER TABLE environment ADD COLUMN password VARCHAR(255) NULL");
        /*
         * SemiClaw WS 网关鉴权临时方案（2026-08-11）：
         * WS 握手除 ticket 外，还依赖 SemiMind(8111端口) 签发的会话 Cookie，
         * 服务端调用无法自然获取该 Cookie，故在环境配置中新增该字段供用户手动维护，
         * Cookie 过期后可在前端重新粘贴更新，无需改代码/重新部署。
         */
        tryAddColumn("ALTER TABLE environment ADD COLUMN ws_cookie TEXT NULL");
        /* 账号角色标记(2026-08-14)：纯标记字段，不影响实际登录凭证。
         * 用于区分同一环境配置的登录账号是管理员账号还是普通账号，方便用户自行管理识别。
         * 取值：admin / normal / 空(未设置)。已有环境默认置空，用户可在编辑时补充。
         */
        tryAddColumn("ALTER TABLE environment ADD COLUMN account_role VARCHAR(20) NULL");
    }


    public String nextId() {
        return "env-" + idCounter.getAndIncrement();
    }

    public Map<String, Object> get(String id) {
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT * FROM environment WHERE id = ?",
                    id
            );
            return rowToMap(row);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 根据前端环境 URL 查询环境配置。
     *
     * 用途：
     * 1. EnvAgentService 加载 SemiClaw Agent 时，根据 url 找环境配置
     * 2. EvaluationService / SemiclawAgentChatService 根据 url 获取环境中的平台与兼容字段
     *
     * 兼容：
     * 1. 数据库存的是 demo-claw-prod.example.invalid:8630，请求传 demo-claw-prod.example.invalid:8630
     * 2. 数据库存的是 demo-claw-prod.example.invalid:8630，请求传 https://demo-claw-prod.example.invalid:8630
     * 3. 数据库存的是 https://demo-claw-prod.example.invalid:8630，请求传 demo-claw-prod.example.invalid:8630
     */
    public Map<String, Object> findByUrl(String url) {
        if (isBlank(url)) {
            return null;
        }

        List<String> candidates = buildUrlCandidates(url);

        for (String candidate : candidates) {
            try {
                Map<String, Object> row = jdbcTemplate.queryForMap(
                        "SELECT * FROM environment WHERE url = ? LIMIT 1",
                        candidate
                );
                return rowToMap(row);
            } catch (Exception ignored) {
                // 当前候选 URL 没查到，继续尝试下一个候选
            }
        }

        return null;
    }

    public void put(String id, Map<String, Object> env) {
        try {
            Map<String, Object> existing = get(id);

            String name = firstNonBlank(
                    stringValue(env.get("name")),
                    existing == null ? null : stringValue(existing.get("name"))
            );

            String url = firstNonBlank(
                    stringValue(env.get("url")),
                    existing == null ? null : stringValue(existing.get("url"))
            );

            String createdAt = firstNonBlank(
                    stringValue(env.get("createdAt")),
                    stringValue(env.get("created_at")),
                    existing == null ? null : stringValue(existing.get("createdAt")),
                    existing == null ? null : stringValue(existing.get("created_at")),
                    nowString()
            );

            String platform = normalizePlatform(firstNonBlank(
                    stringValue(env.get("platform")),
                    existing == null ? null : stringValue(existing.get("platform")),
                    PLATFORM_SEMIMIND
            ));

            String requestedCredentialMode = normalizeCredentialMode(firstNonBlank(
                    stringValue(env.get("credentialMode")),
                    stringValue(env.get("credential_mode")),
                    stringValue(env.get("loginMode")),
                    stringValue(env.get("login_mode"))
            ));

            boolean semiclaw = PLATFORM_SEMICLAW.equalsIgnoreCase(platform);
            boolean forceServiceAccountMode = semiclaw
                    && CREDENTIAL_MODE_SERVICE_ACCOUNT.equals(requestedCredentialMode);

            /*
             * 兼容逻辑：
             * 1. 普通保存 / 编辑时，如果没有传新的 tenantId / username / password，保留旧值。
             *    这样不会影响已经跑通的 SemiClaw 个人账号环境。
             * 2. 新增 SemiClaw 服务账号环境时，existing 为 null，且前端不传账号字段，会保存为 null。
             *    后续 SemiclawApiService 会根据 baseUrl 从 application.properties 读取服务账号。
             * 3. 如果前端显式传 loginMode / credentialMode = service-account，
             *    则主动清空 tenant_id / username / password，让旧个人账号环境也能切到服务账号模式。
             */
            String tenantId;
            String username;
            String password;

            if (forceServiceAccountMode) {
                tenantId = null;
                username = null;
                password = null;
            } else {
                tenantId = firstNonBlank(
                        stringValue(env.get("tenantId")),
                        stringValue(env.get("tenant_id")),
                        existing == null ? null : stringValue(existing.get("tenantId")),
                        existing == null ? null : stringValue(existing.get("tenant_id"))
                );

                username = firstNonBlank(
                        stringValue(env.get("username")),
                        existing == null ? null : stringValue(existing.get("username"))
                );

                password = firstNonBlank(
                        stringValue(env.get("password")),
                        stringValue(env.get("pwd")),
                        existing == null ? null : stringValue(existing.get("password"))
                );
            }

            tenantId = trimToNull(tenantId);
            username = trimToNull(username);
            password = trimToNull(password);

            // ws_cookie：与 password 一致，前端未传新值时保留旧值，避免误清空
            String wsCookie = firstNonBlank(
                    stringValue(env.get("wsCookie")),
                    stringValue(env.get("ws_cookie")),
                    existing == null ? null : stringValue(existing.get("wsCookie")),
                    existing == null ? null : stringValue(existing.get("ws_cookie"))
            );
            wsCookie = trimToNull(wsCookie);

            // account_role：纯标记字段，前端未传新值时保留旧值，与 ws_cookie 处理方式一致
            String accountRole = firstNonBlank(
                    stringValue(env.get("accountRole")),
                    stringValue(env.get("account_role")),
                    existing == null ? null : stringValue(existing.get("accountRole")),
                    existing == null ? null : stringValue(existing.get("account_role"))
            );
            accountRole = trimToNull(accountRole);

            String agentsJson;
            if (env.containsKey("agents")) {
                agentsJson = toJson(env.get("agents"));
            } else {
                agentsJson = null;
            }

            if (forceServiceAccountMode) {
                jdbcTemplate.update(
                        "INSERT INTO environment " +
                                "(id, name, url, agents, created_at, platform, tenant_id, username, password, ws_cookie, account_role) " +
                                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                                "ON DUPLICATE KEY UPDATE " +
                                "name = VALUES(name), " +
                                "url = VALUES(url), " +
                                "agents = COALESCE(VALUES(agents), agents), " +
                                "created_at = COALESCE(created_at, VALUES(created_at)), " +
                                "platform = VALUES(platform), " +
                                "tenant_id = VALUES(tenant_id), " +
                                "username = VALUES(username), " +
                                "password = VALUES(password), " +
                                "ws_cookie = VALUES(ws_cookie), " +
                                "account_role = VALUES(account_role)",
                        id,
                        name,
                        url,
                        agentsJson,
                        createdAt,
                        platform,
                        tenantId,
                        username,
                        password,
                        wsCookie,
                        accountRole
                );
            } else {
                jdbcTemplate.update(
                        "INSERT INTO environment " +
                                "(id, name, url, agents, created_at, platform, tenant_id, username, password, ws_cookie, account_role) " +
                                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                                "ON DUPLICATE KEY UPDATE " +
                                "name = VALUES(name), " +
                                "url = VALUES(url), " +
                                "agents = COALESCE(VALUES(agents), agents), " +
                                "created_at = COALESCE(created_at, VALUES(created_at)), " +
                                "platform = VALUES(platform), " +
                                "tenant_id = VALUES(tenant_id), " +
                                "username = VALUES(username), " +
                                "password = COALESCE(VALUES(password), password), " +
                                "ws_cookie = COALESCE(VALUES(ws_cookie), ws_cookie), " +
                                "account_role = COALESCE(VALUES(account_role), account_role)",
                        id,
                        name,
                        url,
                        agentsJson,
                        createdAt,
                        platform,
                        tenantId,
                        username,
                        password,
                        wsCookie,
                        accountRole
                );
            }
        } catch (Exception e) {
            log.error("保存环境配置失败：id={}", id, e);
            throw new RuntimeException("保存环境配置失败", e);
        }
    }

    public void remove(String id) {
        jdbcTemplate.update("DELETE FROM environment WHERE id = ?", id);
    }

    public List<Map<String, Object>> getAll() {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT * FROM environment ORDER BY created_at DESC"
            );

            List<Map<String, Object>> result = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                result.add(rowToMap(row));
            }
            return result;
        } catch (Exception e) {
            log.error("查询环境列表失败", e);
            return new ArrayList<>();
        }
    }

    private Map<String, Object> rowToMap(Map<String, Object> row) {
        Map<String, Object> map = new HashMap<>();

        map.put("id", row.get("id"));
        map.put("name", row.get("name"));
        map.put("url", row.get("url"));

        map.put(
                "agents",
                fromJson(row.get("agents"), new TypeReference<List<Map<String, Object>>>() {})
        );

        map.put("createdAt", row.get("created_at"));

        String platform = normalizePlatform(firstNonBlank(
                stringValue(row.get("platform")),
                PLATFORM_SEMIMIND
        ));

        String tenantId = stringValue(row.get("tenant_id"));
        String username = stringValue(row.get("username"));
        String password = stringValue(row.get("password"));

        boolean semiclaw = PLATFORM_SEMICLAW.equalsIgnoreCase(platform);
        boolean hasPersonalCredential = !isBlank(tenantId)
                && !isBlank(username)
                && !isBlank(password);

        String credentialMode = semiclaw && !hasPersonalCredential
                ? CREDENTIAL_MODE_SERVICE_ACCOUNT
                : CREDENTIAL_MODE_PERSONAL;

        map.put("platform", platform);

        // 同时返回 tenantId 和 tenant_id，兼容前端和后端不同命名方式
        map.put("tenantId", tenantId);
        map.put("tenant_id", tenantId);

        map.put("username", username);

        /*
         * 为了不影响原有 SemiClaw 个人账号方式和旧逻辑，这里仍然保留 password 返回。
         * 新的服务账号模式下，数据库 password 为空，不会把 application.properties 中的服务账号密码返回给前端。
         */
        map.put("password", password);

        // 给前端后续做显示/隐藏逻辑使用，不需要新增数据库字段
        map.put("hasPassword", !isBlank(password));
        map.put("credentialMode", credentialMode);
        map.put("credential_mode", credentialMode);

        // 兼容前端可能使用 loginMode 命名
        map.put("loginMode", credentialMode);
        map.put("login_mode", credentialMode);

        // SemiClaw WS 网关鉴权临时方案：前端可维护的 SemiMind 会话 Cookie
        String wsCookie = stringValue(row.get("ws_cookie"));
        map.put("wsCookie", wsCookie);
        map.put("ws_cookie", wsCookie);
        map.put("hasWsCookie", !isBlank(wsCookie));

        // 账号角色标记(纯展示用途，不影响登录凭证)：admin / normal / 空(未设置)
        String accountRole = stringValue(row.get("account_role"));
        map.put("accountRole", accountRole);
        map.put("account_role", accountRole);

        return map;
    }

    private void tryAddColumn(String sql) {
        try {
            jdbcTemplate.execute(sql);
        } catch (Exception e) {
            log.debug("环境表字段已存在或添加失败：{}", e.getMessage());
        }
    }

    private List<String> buildUrlCandidates(String url) {
        Set<String> candidates = new LinkedHashSet<>();

        String raw = url == null ? null : url.trim();
        String noTrailingSlash = stripTrailingSlashes(raw);
        String withoutProtocol = stripProtocol(noTrailingSlash);
        String withoutProtocolNoTrailingSlash = stripTrailingSlashes(withoutProtocol);

        addUrlCandidate(candidates, raw);
        addUrlCandidate(candidates, noTrailingSlash);
        addUrlCandidate(candidates, withoutProtocol);
        addUrlCandidate(candidates, withoutProtocolNoTrailingSlash);

        if (!isBlank(withoutProtocolNoTrailingSlash)) {
            addUrlCandidate(candidates, "https://" + withoutProtocolNoTrailingSlash);
            addUrlCandidate(candidates, "http://" + withoutProtocolNoTrailingSlash);
        }

        return new ArrayList<>(candidates);
    }

    private void addUrlCandidate(Set<String> candidates, String value) {
        if (!isBlank(value)) {
            candidates.add(value.trim());
        }
    }

    private String stripProtocol(String value) {
        if (isBlank(value)) {
            return value;
        }

        String trimmed = value.trim();

        if (trimmed.startsWith("https://")) {
            return trimmed.substring("https://".length());
        }

        if (trimmed.startsWith("http://")) {
            return trimmed.substring("http://".length());
        }

        return trimmed;
    }

    private String stripTrailingSlashes(String value) {
        if (isBlank(value)) {
            return value;
        }

        String result = value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }

        return result;
    }

    private String normalizePlatform(String platform) {
        if (isBlank(platform)) {
            return PLATFORM_SEMIMIND;
        }

        String value = platform.trim();

        if (PLATFORM_SEMICLAW.equalsIgnoreCase(value)) {
            return PLATFORM_SEMICLAW;
        }

        if (PLATFORM_SEMIMIND.equalsIgnoreCase(value)) {
            return PLATFORM_SEMIMIND;
        }

        return value;
    }

    private String normalizeCredentialMode(String mode) {
        if (isBlank(mode)) {
            return null;
        }

        String value = mode.trim()
                .toLowerCase()
                .replace("_", "-");

        if ("service".equals(value)
                || "service-account".equals(value)
                || "serviceaccount".equals(value)
                || "backend".equals(value)
                || "backend-config".equals(value)
                || "config".equals(value)) {
            return CREDENTIAL_MODE_SERVICE_ACCOUNT;
        }

        if ("personal".equals(value)
                || "environment".equals(value)
                || "env".equals(value)
                || "manual".equals(value)) {
            return CREDENTIAL_MODE_PERSONAL;
        }

        return value;
    }

    private String toJson(Object obj) {
        if (obj == null) {
            return null;
        }

        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("JSON序列化失败", e);
            return null;
        }
    }

    private <T> T fromJson(Object jsonObj, TypeReference<T> typeRef) {
        if (jsonObj == null) {
            return null;
        }

        try {
            return objectMapper.readValue(jsonObj.toString(), typeRef);
        } catch (Exception e) {
            log.warn("JSON反序列化失败", e);
            return null;
        }
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

    private String trimToNull(String value) {
        if (isBlank(value)) {
            return null;
        }

        return value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String nowString() {
        return LocalDateTime.now().format(DATE_TIME_FORMATTER);
    }
}
