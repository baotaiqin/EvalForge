package com.qatools.service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.qatools.config.EnvDbConfig;
import com.qatools.config.EnvDbProperties;
import com.qatools.config.PlatformDetectionProperties;

/**
 * 动态连接远程环境数据库，查询智能体、工作流和用户列表
 * 兼容：
 * 1. SemiMind：继续通过远程 MySQL 查询 ai_bot / user_canvas / user
 * 2. SemiClaw：通过 SemiclawApiService 调用 /api/agents/
 */
@Service
public class EnvAgentService {

    @Autowired
    private EnvDbProperties envDbProperties;

    @Autowired
    private EnvironmentStoreService environmentStoreService;

    @Autowired
    private SemiclawApiService semiclawApiService;

    @Autowired
    private PlatformDetectionProperties platformDetection;

    /**
     * 根据前端 URL 查询该环境数据库中的智能体和工作流（全量，向后兼容）
     * @param frontendUrl 前端环境URL，如 demo-dev.example.invalid:6111
     * @return { bots: [...], canvases: [...] }
     */
    public Map<String, Object> getAgentsByEnvUrl(String frontendUrl) {
        return getAgentsByEnvUrl(frontendUrl, null, null);
    }

    /**
     * 根据平台查询智能体和工作流。
     * SemiMind：查远程环境数据库
     * SemiClaw：查 SemiClaw /api/agents/
     */
    public Map<String, Object> getAgentsByEnvUrl(String frontendUrl, String platform, String tenantId) {
        if (isSemiclaw(platform, frontendUrl)) {
            Map<String, Object> result = new HashMap<>();
            result.put("bots", loadSemiclawAgents(frontendUrl, tenantId));
            result.put("canvases", new ArrayList<>());
            return result;
        }

        EnvDbConfig config = getConfig(frontendUrl);

        Map<String, Object> result = new HashMap<>();
        result.put("bots", queryBots(config));
        result.put("canvases", queryCanvases(config));
        return result;
    }

    /**
     * 分页查询环境中的 Agent 列表（智能体或工作流）
     * @param frontendUrl 前端环境URL
     * @param type        "bot" 或 "canvas"
     * @param offset      分页偏移量
     * @param limit       每页条数
     * @param keyword     搜索关键字（匹配名称或创建者）
     * @return { items: [...], total: N }
     */
    public Map<String, Object> getAgentsByEnvUrlPaged(String frontendUrl,
                                                       String type,
                                                       int offset,
                                                       int limit,
                                                       String keyword) {
        return getAgentsByEnvUrlPaged(frontendUrl, null, null, type, offset, limit, keyword);
    }

    /**
     * 分页查询环境中的 Agent 列表，支持平台分流。
     * SemiMind：保留原来的数据库分页查询
     * SemiClaw：抽取 /api/agents/ 后在内存中做关键字过滤和分页
     */
    public Map<String, Object> getAgentsByEnvUrlPaged(String frontendUrl,
                                                       String platform,
                                                       String tenantId,
                                                       String type,
                                                       int offset,
                                                       int limit,
                                                       String keyword) {
        if (isSemiclaw(platform, frontendUrl)) {
            Map<String, Object> result = new HashMap<>();
            if ("canvas".equalsIgnoreCase(type)) {
                result.put("items", new ArrayList<>());
                result.put("total", 0);
                return result;
            }

            List<Map<String, Object>> allAgents = loadSemiclawAgents(frontendUrl, tenantId);
            List<Map<String, Object>> filtered = filterSemiclawAgents(allAgents, keyword);
            List<Map<String, Object>> paged = paginate(filtered, offset, limit);

            result.put("items", paged);
            result.put("total", filtered.size());
            return result;
        }

        EnvDbConfig config = getConfig(frontendUrl);

        Map<String, Object> result = new HashMap<>();
        if ("canvas".equals(type)) {
            result.put("items", queryCanvasesPaged(config, offset, limit, keyword));
            result.put("total", queryCanvasCount(config, keyword));
        } else {
            result.put("items", queryBotsPaged(config, offset, limit, keyword));
            result.put("total", queryBotCount(config, keyword));
        }
        return result;
    }

    /**
     * 查询环境中 Agent 的数量统计
     */
    public Map<String, Object> getAgentCounts(String frontendUrl) {
        return getAgentCounts(frontendUrl, null, null);
    }

    /**
     * 查询环境中 Agent 的数量统计，支持平台分流。
     */
    public Map<String, Object> getAgentCounts(String frontendUrl, String platform, String tenantId) {
        if (isSemiclaw(platform, frontendUrl)) {
            List<Map<String, Object>> agents = loadSemiclawAgents(frontendUrl, tenantId);

            Map<String, Object> result = new HashMap<>();
            result.put("botCount", agents.size());
            result.put("canvasCount", 0);
            return result;
        }

        EnvDbConfig config = getConfig(frontendUrl);

        Map<String, Object> result = new HashMap<>();
        result.put("botCount", queryBotCount(config, null));
        result.put("canvasCount", queryCanvasCount(config, null));
        return result;
    }

    /**
     * 分页查询环境中的用户列表
     * @param frontendUrl 前端环境URL
     * @param offset      分页起始偏移量
     * @param limit       每页条数
     * @param keyword     搜索关键字（匹配nickname或email）
     * @return { users: [...], total: N }
     */
    public Map<String, Object> getUsersByEnvUrl(String frontendUrl, int offset, int limit, String keyword) {
        return getUsersByEnvUrl(frontendUrl, null, null, offset, limit, keyword);
    }

    /**
     * 分页查询环境中的用户列表，支持平台分流。
     * SemiClaw 最小版本暂不实现用户列表，直接返回空。
     */
    public Map<String, Object> getUsersByEnvUrl(String frontendUrl,
                                                String platform,
                                                String tenantId,
                                                int offset,
                                                int limit,
                                                String keyword) {
        if (isSemiclaw(platform, frontendUrl)) {
            Map<String, Object> result = new HashMap<>();
            result.put("users", new ArrayList<>());
            result.put("total", 0);
            return result;
        }

        EnvDbConfig config = getConfig(frontendUrl);

        Map<String, Object> result = new HashMap<>();
        result.put("users", queryUsers(config, offset, limit, keyword));
        result.put("total", queryUserCount(config, keyword));
        return result;
    }

    /**
     * 根据用户ID查询该用户创建的智能体和工作流
     * @param frontendUrl 前端环境URL
     * @param userId      用户ID
     * @return { bots: [...], canvases: [...] }
     */
    public Map<String, Object> getAgentsByUserAndEnvUrl(String frontendUrl, String userId) {
        return getAgentsByUserAndEnvUrl(frontendUrl, null, null, userId);
    }

    /**
     * 根据用户ID查询该用户创建的智能体和工作流，支持平台分流。
     * SemiClaw 最小版本没有使用当前系统 userId 维度，返回全部 Agent。
     */
    public Map<String, Object> getAgentsByUserAndEnvUrl(String frontendUrl,
                                                        String platform,
                                                        String tenantId,
                                                        String userId) {
        if (isSemiclaw(platform, frontendUrl)) {
            Map<String, Object> result = new HashMap<>();
            result.put("bots", loadSemiclawAgents(frontendUrl, tenantId));
            result.put("canvases", new ArrayList<>());
            return result;
        }

        EnvDbConfig config = getConfig(frontendUrl);

        Map<String, Object> result = new HashMap<>();
        result.put("bots", queryBotsByUser(config, userId));
        result.put("canvases", queryCanvasesByUser(config, userId));
        return result;
    }

    /**
     * 根据昵称查询用户信息（用于定位评测账号）
     * 注意：
     * 这个方法保持 SemiMind 原逻辑不变。
     * SemiClaw 最小闭环使用环境配置中的 username / password / tenantId，不依赖这里。
     * @param frontendUrl 前端环境URL
     * @param nickname    用户昵称
     * @return 用户信息 { id, nickname, email } 或 null
     */
    public Map<String, Object> getUserByNickname(String frontendUrl, String nickname) {
        EnvDbConfig config = getConfig(frontendUrl);
        String sql = "SELECT u.id, u.nickname, u.email FROM `user` u WHERE u.status = '1' AND u.nickname = ?";
        List<Map<String, Object>> results = executeQueryWithParams(config, sql, List.of(nickname));
        return results.isEmpty() ? null : results.get(0);
    }

    // ---------------- 查询方法 ----------------

    /**
     *
     * 查询智能体列表（全量，向后兼容）
     */
    private List<Map<String, Object>> queryBots(EnvDbConfig config) {
        String dialogSelect = buildBotDialogSelect(config, "ab");
        String sql = "SELECT ab.id AS id, u.nickname AS creator, CONCAT('(智能体) ', ab.name) AS name" +
                dialogSelect + " " +
                "FROM `user` u INNER JOIN ai_bot ab ON ab.created_by = u.id " +
                "WHERE ab.status != 0";
        List<Map<String, Object>> results = executeQuery(config, sql);
        for (Map<String, Object> row : results) {
            normalizeDialogFields(row);
            row.put("type", "bot");
        }
        return results;
    }

    /**
     *
     * 分页查询智能体列表
     */
    private List<Map<String, Object>> queryBotsPaged(EnvDbConfig config, int offset, int limit, String keyword) {
        List<Object> params = new ArrayList<>();
        String dialogSelect = buildBotDialogSelect(config, "ab");
        String sql = "SELECT ab.id AS id, u.nickname AS creator, CONCAT('(智能体) ', ab.name) AS name" +
                dialogSelect + " " +
                "FROM `user` u INNER JOIN ai_bot ab ON ab.created_by = u.id " +
                "WHERE ab.status != 0";
        if (keyword != null && !keyword.isBlank()) {
            sql += " AND (ab.name LIKE ? OR u.nickname LIKE ?)";
            params.add("%" + keyword + "%");
            params.add("%" + keyword + "%");
        }
        sql += " ORDER BY ab.id DESC LIMIT ? OFFSET ?";
        params.add(limit);
        params.add(offset);

        List<Map<String, Object>> results = executeQueryWithParams(config, sql, params);
        for (Map<String, Object> row : results) {
            normalizeDialogFields(row);
            row.put("type", "bot");
        }
        return results;
    }

    /**
     *
     * 查询智能体总数
     */
    private int queryBotCount(EnvDbConfig config, String keyword) {
        List<Object> params = new ArrayList<>();
        String sql = "SELECT COUNT(*) AS cnt FROM `user` u INNER JOIN ai_bot ab ON ab.created_by = u.id " +
                "WHERE ab.status != 0";
        if (keyword != null && !keyword.isBlank()) {
            sql += " AND (ab.name LIKE ? OR u.nickname LIKE ?)";
            params.add("%" + keyword + "%");
            params.add("%" + keyword + "%");
        }
        List<Map<String, Object>> results = executeQueryWithParams(config, sql, params);
        if (!results.isEmpty()) {
            Object cnt = results.get(0).get("cnt");
            return cnt != null ? Integer.parseInt(cnt.toString()) : 0;
        }
        return 0;
    }

    /**
     *
     * 查询工作流列表（全量，向后兼容）
     */
    private List<Map<String, Object>> queryCanvases(EnvDbConfig config) {
        String dialogSelect = buildCanvasDialogSelect(config, "uc");
        String sql = "SELECT uc.id AS id, u.nickname AS creator, CONCAT('(工作流) ', uc.title) AS name" +
                dialogSelect + " " +
                "FROM `user` u INNER JOIN user_canvas uc ON uc.created_by = u.id";
        List<Map<String, Object>> results = executeQuery(config, sql);
        for (Map<String, Object> row : results) {
            normalizeDialogFields(row);
            row.put("type", "canvas");
        }
        return results;
    }

    /**
     *
     * 分页查询工作流列表
     */
    private List<Map<String, Object>> queryCanvasesPaged(EnvDbConfig config, int offset, int limit, String keyword) {
        List<Object> params = new ArrayList<>();
        String dialogSelect = buildCanvasDialogSelect(config, "uc");
        String sql = "SELECT uc.id AS id, u.nickname AS creator, CONCAT('(工作流) ', uc.title) AS name" +
                dialogSelect + " " +
                "FROM `user` u INNER JOIN user_canvas uc ON uc.created_by = u.id " +
                "WHERE 1=1";
        if (keyword != null && !keyword.isBlank()) {
            sql += " AND (uc.title LIKE ? OR u.nickname LIKE ?)";
            params.add("%" + keyword + "%");
            params.add("%" + keyword + "%");
        }
        sql += " ORDER BY uc.id DESC LIMIT ? OFFSET ?";
        params.add(limit);
        params.add(offset);

        List<Map<String, Object>> results = executeQueryWithParams(config, sql, params);
        for (Map<String, Object> row : results) {
            normalizeDialogFields(row);
            row.put("type", "canvas");
        }
        return results;
    }

    /**
     *
     * 查询工作流总数
     */
    private int queryCanvasCount(EnvDbConfig config, String keyword) {
        List<Object> params = new ArrayList<>();
        String sql = "SELECT COUNT(*) AS cnt FROM `user` u INNER JOIN user_canvas uc ON uc.created_by = u.id " +
                "WHERE 1=1";
        if (keyword != null && !keyword.isBlank()) {
            sql += " AND (uc.title LIKE ? OR u.nickname LIKE ?)";
            params.add("%" + keyword + "%");
            params.add("%" + keyword + "%");
        }
        List<Map<String, Object>> results = executeQueryWithParams(config, sql, params);
        if (!results.isEmpty()) {
            Object cnt = results.get(0).get("cnt");
            return cnt != null ? Integer.parseInt(cnt.toString()) : 0;
        }
        return 0;
    }

    /** 分页查询用户列表 */
    private List<Map<String, Object>> queryUsers(EnvDbConfig config, int offset, int limit, String keyword) {
        String sql;
        List<Object> params = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            sql = "SELECT u.id, u.nickname, u.email FROM `user` u " +
                    "WHERE u.status = '1' AND (u.nickname LIKE ? OR u.email LIKE ?) " +
                    "ORDER BY u.nickname ASC LIMIT ? OFFSET ?";
            params.add("%" + keyword + "%");
            params.add("%" + keyword + "%");
        } else {
            sql = "SELECT u.id, u.nickname, u.email FROM `user` u " +
                    "WHERE u.status = '1' ORDER BY u.nickname ASC LIMIT ? OFFSET ?";
        }
        params.add(limit);
        params.add(offset);
        return executeQueryWithParams(config, sql, params);
    }

    /** 查询用户总数 */
    private int queryUserCount(EnvDbConfig config, String keyword) {
        String sql;
        List<Object> params = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            sql = "SELECT COUNT(*) AS cnt FROM `user` u " +
                    "WHERE u.status = '1' AND (u.nickname LIKE ? OR u.email LIKE ?)";
            params.add("%" + keyword + "%");
            params.add("%" + keyword + "%");
        } else {
            sql = "SELECT COUNT(*) AS cnt FROM `user` u WHERE u.status = '1'";
        }
        List<Map<String, Object>> results = executeQueryWithParams(config, sql, params);
        if (!results.isEmpty()) {
            Object cnt = results.get(0).get("cnt");
            return cnt != null ? Integer.parseInt(cnt.toString()) : 0;
        }
        return 0;
    }

    /** 根据用户ID查询该用户创建的智能体 */
    private List<Map<String, Object>> queryBotsByUser(EnvDbConfig config, String userId) {
        // 查询用户自己创建的 + 通过 obj_tenant 共享给该用户 的智能体
        String dialogSelect = buildBotDialogSelect(config, "ab");
        String sql = "SELECT ab.id AS id, u.nickname AS creator, CONCAT('(智能体) ', ab.name) AS name" +
                dialogSelect + " " +
                "FROM `user` u INNER JOIN ai_bot ab ON ab.created_by = u.id " +
                "WHERE ab.status != 0 AND u.id = ? " +
                "UNION " +
                "SELECT ab.id AS id, u.nickname AS creator, CONCAT('(智能体) ', ab.name) AS name" +
                dialogSelect + " " +
                "FROM ai_bot ab " +
                "INNER JOIN `user` u ON ab.created_by = u.id " +
                "INNER JOIN obj_tenant ot ON ot.obj_id = ab.id AND ot.tenant_id = ? " +
                "WHERE ab.status != 0 AND ot.tenant_role != 'owner'";
        List<Map<String, Object>> results = executeQueryWithParams(config, sql, List.of(userId, userId));
        for (Map<String, Object> row : results) {
            normalizeDialogFields(row);
            row.put("type", "bot");
        }
        return results;
    }

    /** 根据用户ID查询该用户创建的工作流 */
    private List<Map<String, Object>> queryCanvasesByUser(EnvDbConfig config, String userId) {
        // 查询用户自己创建的 + 通过 obj_tenant 共享给该用户 的工作流
        String dialogSelect = buildCanvasDialogSelect(config, "uc");
        String sql = "SELECT uc.id AS id, u.nickname AS creator, CONCAT('(工作流) ', uc.title) AS name" +
                dialogSelect + " " +
                "FROM `user` u INNER JOIN user_canvas uc ON uc.created_by = u.id " +
                "WHERE u.id = ? " +
                "UNION " +
                "SELECT uc.id AS id, u.nickname AS creator, CONCAT('(工作流) ', uc.title) AS name" +
                dialogSelect + " " +
                "FROM user_canvas uc " +
                "INNER JOIN `user` u ON uc.created_by = u.id " +
                "INNER JOIN obj_tenant ot ON ot.obj_id = uc.id AND ot.tenant_id = ? " +
                "WHERE ot.tenant_role != 'owner'";
        List<Map<String, Object>> results = executeQueryWithParams(config, sql, List.of(userId, userId));
        for (Map<String, Object> row : results) {
            normalizeDialogFields(row);
            row.put("type", "canvas");
        }
        return results;
    }

    // ---------------- 工具方法 ----------------

    /**
     * SemiMind 多模态接口需要真实 dialog_id。
     * 不同环境表结构可能没有 dialog_id 字段，所以先探测字段是否存在，再动态拼接 SELECT，避免老环境 SQL 直接报错。
     */
    private String buildBotDialogSelect(EnvDbConfig config, String alias) {
        return buildDialogSelect(config, "ai_bot", alias);
    }

    private String buildCanvasDialogSelect(EnvDbConfig config, String alias) {
        return buildDialogSelect(config, "user_canvas", alias);
    }

    private String buildDialogSelect(EnvDbConfig config, String tableName, String alias) {
        String column = findExistingColumn(config, tableName, "dialog_id", "dialogId", "dialogID");
        if (isBlank(column)) {
            return "";
        }
        String prefix = alias == null || alias.isBlank() ? "" : alias + ".";
        String quotedColumn = "`" + column.replace("`", "") + "`";
        return ", " + prefix + quotedColumn + " AS dialogId,"
                + prefix + quotedColumn + " AS dialog_id,"
                + prefix + quotedColumn + " AS semimindDialogId,"
                + prefix + quotedColumn + " AS semimind_dialog_id";
    }

    private String findExistingColumn(EnvDbConfig config, String tableName, String... candidateNames) {
        if (config == null || isBlank(tableName) || candidateNames == null || candidateNames.length == 0) {
            return null;
        }

        List<Map<String, Object>> columns;
        try {
            columns = executeQuery(config, "SHOW COLUMNS FROM `" + tableName.replace("`", "") + "`");
        } catch (RuntimeException e) {
            return null;
        }

        for (String candidateName : candidateNames) {
            if (isBlank(candidateName)) {
                continue;
            }
            for (Map<String, Object> column : columns) {
                String fieldName = firstNonBlank(
                        stringValue(column.get("Field")),
                        stringValue(column.get("field")),
                        stringValue(column.get("COLUMN_NAME")),
                        stringValue(column.get("column_name"))
                );
                if (candidateName.equalsIgnoreCase(fieldName)) {
                    return fieldName;
                }
            }
        }
        return null;
    }

    private void normalizeDialogFields(Map<String, Object> row) {
        if (row == null) {
            return;
        }
        String dialogId = firstNonBlank(
                stringValue(row.get("dialogId")),
                stringValue(row.get("dialog_id")),
                stringValue(row.get("semimindDialogId")),
                stringValue(row.get("semimind_dialog_id"))
        );
        if (!isBlank(dialogId)) {
            row.put("dialogId", dialogId);
            row.put("dialog_id", dialogId);
            row.put("semimindDialogId", dialogId);
            row.put("semimind_dialog_id", dialogId);
        }
    }

    private EnvDbConfig getConfig(String frontendUrl) {
        EnvDbConfig config = envDbProperties.getByFrontendUrl(frontendUrl);
        if (config == null) {
            throw new IllegalArgumentException("未找到环境URL对应的数据库配置: " + frontendUrl);
        }
        return config;
    }

    /**
     * 加载 SemiClaw Agent 列表。
     * 账号、密码从 environment 表读取；
     * tenantId 优先使用请求传入值，其次使用 environment 表中的 tenantId / tenant_id。
     */
    private List<Map<String, Object>> loadSemiclawAgents(String frontendUrl, String tenantIdFromRequest) {
        Map<String, Object> env = environmentStoreService.findByUrl(frontendUrl);

        String baseUrl = firstNonBlank(
                frontendUrl,
                env != null ? stringValue(env.get("url")) : null,
                env != null ? stringValue(env.get("baseUrl")) : null
        );

        String tenantId = firstNonBlank(
                tenantIdFromRequest,
                env != null ? stringValue(env.get("tenantId")) : null,
                env != null ? stringValue(env.get("tenant_id")) : null
        );

        String username = firstNonBlank(
                env != null ? stringValue(env.get("username")) : null
        );

        String password = firstNonBlank(
                env != null ? stringValue(env.get("password")) : null
        );

        if (isBlank(baseUrl)) {
            throw new IllegalArgumentException("未配置 semiclaw baseUrl");
        }
        if (isBlank(tenantId)) {
            throw new IllegalArgumentException("未配置 semiclaw tenantId");
        }
        if (isBlank(username)) {
            throw new IllegalArgumentException("未配置 semiclaw 登录账号");
        }
        if (isBlank(password)) {
            throw new IllegalArgumentException("未配置 semiclaw 登录密码");
        }

        return semiclawApiService.listAgents(baseUrl, tenantId, username, password);
    }

    private List<Map<String, Object>> filterSemiclawAgents(List<Map<String, Object>> agents, String keyword) {
        if (agents == null || agents.isEmpty()) {
            return new ArrayList<>();
        }
        if (isBlank(keyword)) {
            return new ArrayList<>(agents);
        }

        String kw = keyword.toLowerCase(Locale.ROOT);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> agent : agents) {
            String id = stringValue(agent.get("id"));
            String name = firstNonBlank(
                    stringValue(agent.get("name")),
                    stringValue(agent.get("agentName")),
                    stringValue(agent.get("agent_name"))
            );
            String roleDescription = firstNonBlank(
                    stringValue(agent.get("roleDescription")),
                    stringValue(agent.get("role_description"))
            );
            String status = stringValue(agent.get("status"));
            String agentType = firstNonBlank(
                    stringValue(agent.get("agentType")),
                    stringValue(agent.get("agent_type"))
            );

            if (containsIgnoreCase(id, kw)
                    || containsIgnoreCase(name, kw)
                    || containsIgnoreCase(roleDescription, kw)
                    || containsIgnoreCase(status, kw)
                    || containsIgnoreCase(agentType, kw)) {
                result.add(agent);
            }
        }
        return result;
    }

    private List<Map<String, Object>> paginate(List<Map<String, Object>> source, int offset, int limit) {
        if (source == null || source.isEmpty()) {
            return new ArrayList<>();
        }
        int safeOffset = Math.max(offset, 0);
        int safeLimit = limit <= 0 ? 20 : limit;
        int from = Math.min(safeOffset, source.size());
        int to = Math.min(from + safeLimit, source.size());
        return new ArrayList<>(source.subList(from, to));
    }

    private boolean isSemiclaw(String platform, String frontendUrl) {
        if ("semiclaw".equalsIgnoreCase(firstNonBlank(platform))) {
            return true;
        }
        String url = stringValue(frontendUrl);
        if (url == null) {
            return false;
        }
        String lowerUrl = url.toLowerCase(Locale.ROOT);
        return platformDetection.isSemiclawUrl(lowerUrl)
                || lowerUrl.contains("semiclaw");
    }

    private boolean containsIgnoreCase(String value, String lowerKeyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(lowerKeyword);
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
        return value == null || value.trim().isEmpty();
    }

    /**
     * 使用独立 JDBC 连接执行查询（不依赖Spring数据源，直接连接远程数据库）
     */
    private List<Map<String, Object>> executeQuery(EnvDbConfig config, String sql) {
        return executeQueryWithParams(config, sql, Collections.emptyList());
    }

    /**
     * 参数化查询，防止SQL注入
     */
    private List<Map<String, Object>> executeQueryWithParams(EnvDbConfig config, String sql, List<Object> params) {
        List<Map<String, Object>> results = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(
                config.getUrl(), config.getUsername(), config.getPassword());
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            // 绑定参数
            for (int i = 0; i < params.size(); i++) {
                Object param = params.get(i);
                if (param instanceof Integer) {
                    stmt.setInt(i + 1, (Integer) param);
                } else {
                    stmt.setString(i + 1, param.toString());
                }
            }

            try (ResultSet rs = stmt.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int columnCount = meta.getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new HashMap<>();
                    for (int i = 1; i <= columnCount; i++) {
                        String colLabel = meta.getColumnLabel(i);
                        row.put(colLabel, rs.getString(i));
                    }
                    results.add(row);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询环境数据库失败 [" + config.getUrl() + "]: " + e.getMessage(), e);
        }
        return results;
    }
}
