package com.evalforge.controller;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.evalforge.config.TestAccountProperties;
import com.evalforge.service.EnvAgentService;
import com.evalforge.service.EnvironmentStoreService;
import com.evalforge.service.SemiclawApiService;
import com.evalforge.service.UserLoginService;

@RestController
@RequestMapping("/api/config/environments")
@CrossOrigin(origins = "*")
public class EnvironmentController {

    @Autowired
    private EnvAgentService envAgentService;

    @Autowired
    private EnvironmentStoreService envStore;

    @Autowired
    private UserLoginService userLoginService;

    @Autowired
    private TestAccountProperties testAccountProperties;

    @Autowired
    private SemiclawApiService semiclawApiService;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String PLATFORM_SEMIMIND = "semimind";
    private static final String PLATFORM_SEMICLAW = "semiclaw";
    private static final String CREDENTIAL_MODE_PERSONAL = "personal";
    private static final String CREDENTIAL_MODE_SERVICE_ACCOUNT = "service-account";
    private static final String ACCOUNT_ROLE_ADMIN = "admin";

    /**
     * 获取所有环境列表（精简版，不返回 agents 详情和真实 password）。
     */
    @GetMapping
    public Map<String, Object> listEnvironments() {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> envList = envStore.getAll();

        List<Map<String, Object>> slimList = new ArrayList<>();
        for (Map<String, Object> env : envList) {
            slimList.add(sanitizeEnvironmentForClient(env));
        }

        result.put("code", 0);
        result.put("data", slimList);
        return result;
    }

    /**
     * 创建环境。
     */
    @PostMapping
    public Map<String, Object> createEnvironment(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        try {
            String id = envStore.nextId();
            Map<String, Object> env = new HashMap<>(body);
            env.put("id", id);
            env.putIfAbsent("platform", PLATFORM_SEMIMIND);
            env.putIfAbsent("agents", new ArrayList<>());
            env.put("createdAt", LocalDateTime.now().format(FMT));
            envStore.put(id, env);

            Map<String, Object> savedEnv = envStore.get(id);
            result.put("code", 0);
            result.put("data", sanitizeEnvironmentForClient(savedEnv != null ? savedEnv : env));
        } catch (Exception e) {
            result.put("code", 500);
            result.put("message", "创建环境失败：" + e.getMessage());
        }
        return result;
    }

    /**
     * 更新环境。
     */
    @PutMapping("/{id}")
    public Map<String, Object> updateEnvironment(@PathVariable String id,
                                                 @RequestBody Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> env = envStore.get(id);
        if (env == null) {
            result.put("code", 404);
            result.put("message", "环境不存在");
            return result;
        }

        env.putAll(body);
        env.put("id", id);
        env.putIfAbsent("platform", PLATFORM_SEMIMIND);
        envStore.put(id, env);

        Map<String, Object> savedEnv = envStore.get(id);
        result.put("code", 0);
        result.put("data", sanitizeEnvironmentForClient(savedEnv != null ? savedEnv : env));
        return result;
    }

    /**
     * 删除环境。
     */
    @DeleteMapping("/{id}")
    public Map<String, Object> deleteEnvironment(@PathVariable String id) {
        Map<String, Object> result = new HashMap<>();
        envStore.remove(id);
        result.put("code", 0);
        return result;
    }

    /**
     * 根据环境 URL 获取所有 Agent 列表（兼容旧接口）。
     */
    @GetMapping("/agents")
    public Map<String, Object> getAgentsByEnvUrl(@RequestParam("envUrl") String envUrl) {
        Map<String, Object> result = new HashMap<>();
        try {
            Map<String, Object> agents = envAgentService.getAgentsByEnvUrl(envUrl);
            result.put("code", 0);
            result.put("data", agents);
        } catch (IllegalArgumentException e) {
            result.put("code", 404);
            result.put("message", e.getMessage());
        } catch (Exception e) {
            result.put("code", 500);
            result.put("message", "查询Agent列表失败：" + e.getMessage());
        }
        return result;
    }

    /**
     * 分页获取环境中的 Agent 列表。
     */
    @GetMapping("/agents/paged")
    public Map<String, Object> getAgentsPaged(
            @RequestParam("envUrl") String envUrl,
            @RequestParam(defaultValue = "bot") String type,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String keyword) {
        Map<String, Object> result = new HashMap<>();
        try {
            Map<String, Object> data = envAgentService.getAgentsByEnvUrlPaged(
                    envUrl, type, offset, limit, keyword);
            result.put("code", 0);
            result.put("data", data);
        } catch (IllegalArgumentException e) {
            result.put("code", 404);
            result.put("message", e.getMessage());
        } catch (Exception e) {
            result.put("code", 500);
            result.put("message", "分页查询Agent列表失败：" + e.getMessage());
        }
        return result;
    }

    /**
     * 分页获取 SemiClaw Agent 列表。personal 模式优先使用请求或环境中的个人凭据，
     * service-account 模式让服务层使用后台配置的服务账号。
     */
    @PostMapping("/semiclaw/agents")
    public Map<String, Object> listSemiclawAgents(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        try {
            String envId = firstNonBlank(
                    str(body.get("envId")),
                    str(body.get("environmentId")),
                    str(body.get("id"))
            );

            String baseUrl = firstNonBlank(
                    str(body.get("baseUrl")),
                    str(body.get("envUrl")),
                    str(body.get("url"))
            );

            Map<String, Object> savedEnv = null;
            if (!isBlank(envId)) {
                savedEnv = envStore.get(envId);
            }
            if (savedEnv == null && !isBlank(baseUrl)) {
                savedEnv = envStore.findByUrl(baseUrl);
            }

            baseUrl = firstNonBlank(
                    baseUrl,
                    savedEnv == null ? null : str(savedEnv.get("url"))
            );

            String credentialMode = normalizeCredentialMode(firstNonBlank(
                    str(body.get("credentialMode")),
                    str(body.get("credential_mode")),
                    str(body.get("loginMode")),
                    str(body.get("login_mode")),
                    savedEnv == null ? null : str(savedEnv.get("credentialMode")),
                    savedEnv == null ? null : str(savedEnv.get("credential_mode")),
                    savedEnv == null ? null : str(savedEnv.get("loginMode")),
                    savedEnv == null ? null : str(savedEnv.get("login_mode"))
            ));

            boolean useServiceAccount = CREDENTIAL_MODE_SERVICE_ACCOUNT.equals(credentialMode);

            String tenantId = null;
            String username = null;
            String password = null;
            if (!useServiceAccount) {
                tenantId = firstNonBlank(
                        str(body.get("tenantId")),
                        str(body.get("tenant_id")),
                        savedEnv == null ? null : str(savedEnv.get("tenantId")),
                        savedEnv == null ? null : str(savedEnv.get("tenant_id"))
                );
                username = firstNonBlank(
                        str(body.get("username")),
                        savedEnv == null ? null : str(savedEnv.get("username"))
                );
                password = firstNonBlank(
                        str(body.get("password")),
                        str(body.get("pwd")),
                        savedEnv == null ? null : str(savedEnv.get("password")),
                        savedEnv == null ? null : str(savedEnv.get("pwd"))
                );
            }

            String keyword = str(body.get("keyword"));
            int offset = intValue(body.get("offset"), 0);
            int limit = intValue(body.get("limit"), 20);

            if (isBlank(baseUrl)) {
                result.put("code", 400);
                result.put("message", "缺少必要参数：baseUrl");
                return result;
            }

            List<Map<String, Object>> allAgents = semiclawApiService.listAgents(
                    baseUrl, tenantId, username, password);

            List<Map<String, Object>> filtered = new ArrayList<>();
            for (Map<String, Object> agent : allAgents) {
                if (isBlank(keyword) || matchesAgentKeyword(agent, keyword)) {
                    filtered.add(agent);
                }
            }

            int total = filtered.size();
            int safeOffset = Math.max(offset, 0);
            int safeLimit = Math.max(limit, 1);
            int fromIndex = Math.min(safeOffset, total);
            int toIndex = Math.min(fromIndex + safeLimit, total);

            Map<String, Object> data = new HashMap<>();
            data.put("items", filtered.subList(fromIndex, toIndex));
            data.put("total", total);
            result.put("code", 0);
            result.put("data", data);
        } catch (IllegalArgumentException e) {
            result.put("code", 400);
            result.put("message", e.getMessage());
        } catch (Exception e) {
            result.put("code", 500);
            result.put("message", "获取 SemiClaw Agent 列表失败：" + e.getMessage());
        }
        return result;
    }

    /**
     * 分页获取环境中的用户列表。
     */
    @GetMapping("/users")
    public Map<String, Object> getUsersByEnvUrl(
            @RequestParam("envUrl") String envUrl,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String keyword) {
        Map<String, Object> result = new HashMap<>();
        try {
            Map<String, Object> users = envAgentService.getUsersByEnvUrl(envUrl, offset, limit, keyword);
            result.put("code", 0);
            result.put("data", users);
        } catch (IllegalArgumentException e) {
            result.put("code", 404);
            result.put("message", e.getMessage());
        } catch (Exception e) {
            result.put("code", 500);
            result.put("message", "查询用户列表失败：" + e.getMessage());
        }
        return result;
    }

    /**
     * 获取指定用户在指定环境中创建的 Agent。
     */
    @GetMapping("/users/{userId}/agents")
    public Map<String, Object> getAgentsByUser(
            @PathVariable String userId,
            @RequestParam("envUrl") String envUrl) {
        Map<String, Object> result = new HashMap<>();
        try {
            Map<String, Object> agents = envAgentService.getAgentsByUserAndEnvUrl(envUrl, userId);
            result.put("code", 0);
            result.put("data", agents);
        } catch (IllegalArgumentException e) {
            result.put("code", 404);
            result.put("message", e.getMessage());
        } catch (Exception e) {
            result.put("code", 500);
            result.put("message", "查询用户Agent列表失败：" + e.getMessage());
        }
        return result;
    }

    /**
     * 获取环境预置测试账号。
     */
    @GetMapping("/test-account")
    public Map<String, Object> getTestAccount(@RequestParam("envUrl") String envUrl) {
        Map<String, Object> result = new HashMap<>();
        try {
            TestAccountProperties.TestAccountConfig config = testAccountProperties.getByFrontendUrl(envUrl);
            if (config == null) {
                result.put("code", 404);
                result.put("message", "未找到该环境的评测账号配置");
                return result;
            }

            Map<String, Object> user = envAgentService.getUserByNickname(envUrl, config.getNickname());
            Map<String, Object> data = new HashMap<>();
            data.put("nickname", config.getNickname());
            data.put("password", config.getPassword());
            if (user != null) {
                data.put("id", user.get("id"));
                data.put("email", user.get("email"));
            }

            result.put("code", 0);
            result.put("data", data);
        } catch (Exception e) {
            result.put("code", 500);
            result.put("message", "获取评测账号失败：" + e.getMessage());
        }
        return result;
    }

    /**
     * 登录 SemiMind 平台，获取 authorization token。
     */
    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        try {
            String envUrl = (String) body.get("envUrl");
            String email = (String) body.get("email");
            String password = (String) body.get("password");
            if (envUrl == null || email == null || password == null) {
                result.put("code", 400);
                result.put("message", "缺少必要参数：envUrl, email, password");
                return result;
            }

            Map<String, String> loginResult = userLoginService.login(envUrl, email, password);
            result.put("code", 0);
            result.put("data", loginResult);
        } catch (Exception e) {
            result.put("code", 500);
            result.put("message", "登录失败：" + e.getMessage());
        }
        return result;
    }

    /**
     * 管理员 SemiClaw 环境：获取可配置给 Agent 的模型列表。
     */
    @GetMapping("/{envId}/semiclaw/models")
    public Map<String, Object> listSemiclawModels(@PathVariable String envId) {
        Map<String, Object> result = new HashMap<>();
        try {
            Map<String, Object> env = requireAdminSemiclawEnv(envId);
            SemiclawEnvCredential credential = resolveSemiclawCredential(env);
            result.put("code", 0);
            result.put("data", semiclawApiService.listModels(
                    credential.baseUrl(), credential.tenantId(), credential.username(), credential.password()));
        } catch (IllegalArgumentException e) {
            result.put("code", 400);
            result.put("message", e.getMessage());
        } catch (Exception e) {
            result.put("code", 500);
            result.put("message", "获取 SemiClaw 模型列表失败：" + e.getMessage());
        }
        return result;
    }

    /**
     * 导入 SemiClaw 数字员工（Agent）压缩包。
     */
    @PostMapping("/{envId}/semiclaw/agents/import")
    public Map<String, Object> importSemiclawAgent(
            @PathVariable String envId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String primaryModelId,
            @RequestParam(required = false) String fallbackModelId,
            @RequestParam(required = false, defaultValue = "true") boolean autoConfigPermission) {
        Map<String, Object> result = new HashMap<>();
        try {
            Map<String, Object> env = requireAdminSemiclawEnv(envId);
            String fileName = file.getOriginalFilename();
            if (file.isEmpty() || isBlank(fileName) || !isTarGzFile(fileName)) {
                result.put("code", 400);
                result.put("message", "仅支持上传 .tar.gz 格式的数字员工导入包");
                return result;
            }

            SemiclawEnvCredential credential = resolveSemiclawCredential(env);
            List<Map<String, Object>> skillFoldersBefore = semiclawApiService.listSkillFolders(
                    credential.baseUrl(), credential.tenantId(), credential.username(), credential.password(), "");

            Map<String, Object> importResult = semiclawApiService.importAgent(
                    credential.baseUrl(), credential.tenantId(), credential.username(), credential.password(),
                    file.getBytes(), fileName);

            List<Map<String, Object>> skillFoldersAfter = semiclawApiService.listSkillFolders(
                    credential.baseUrl(), credential.tenantId(), credential.username(), credential.password(), "");
            Set<String> pathsBefore = skillFoldersBefore.stream()
                    .map(item -> str(item.get("path")))
                    .collect(Collectors.toSet());
            List<Map<String, Object>> newSkillFolders = skillFoldersAfter.stream()
                    .filter(item -> !pathsBefore.contains(str(item.get("path"))))
                    .collect(Collectors.toList());

            Set<String> conflictRenamedNames = extractConflictRenamedNames(importResult.get("warnings"));
            List<Map<String, Object>> duplicateSkillFolders = newSkillFolders.stream()
                    .filter(item -> conflictRenamedNames.contains(str(item.get("name")))
                            || conflictRenamedNames.contains(str(item.get("path"))))
                    .collect(Collectors.toList());
            importResult.put("duplicateSkillFolders", duplicateSkillFolders);

            String agentId = str(importResult.get("agent_id"));
            if (!isBlank(agentId)) {
                if (autoConfigPermission) {
                    try {
                        semiclawApiService.updateAgentModel(
                                credential.baseUrl(), credential.tenantId(), credential.username(), credential.password(),
                                agentId, primaryModelId, fallbackModelId, "tenant");
                        semiclawApiService.setAgentPermissions(
                                credential.baseUrl(), credential.tenantId(), credential.username(), credential.password(),
                                agentId, "company", "manage");
                        importResult.put("needsModelFix", false);
                        importResult.put("permissionConfigured", true);
                    } catch (Exception e) {
                        importResult.put("configWarning", "自动配置权限失败：" + e.getMessage());
                        importResult.put("permissionConfigured", false);
                        if (!isBlank(primaryModelId) || !isBlank(fallbackModelId)) {
                            importResult.put("needsModelFix", true);
                        }
                    }
                } else if (!isBlank(primaryModelId) || !isBlank(fallbackModelId)) {
                    importResult.put("agentDetail", semiclawApiService.updateAgentModel(
                            credential.baseUrl(), credential.tenantId(), credential.username(), credential.password(),
                            agentId, primaryModelId, fallbackModelId));
                    importResult.put("needsModelFix", false);
                }
                importResult.put("agentDetail", semiclawApiService.getAgentDetail(
                        credential.baseUrl(), credential.tenantId(), credential.username(), credential.password(), agentId));
            }

            result.put("code", 0);
            result.put("data", importResult);
        } catch (IllegalArgumentException e) {
            result.put("code", 400);
            result.put("message", e.getMessage());
        } catch (java.io.IOException e) {
            result.put("code", 500);
            result.put("message", "读取上传文件失败：" + e.getMessage());
        } catch (Exception e) {
            result.put("code", 500);
            result.put("message", "导入数字员工失败：" + e.getMessage());
        }
        return result;
    }

    /**
     * 更新 SemiClaw Agent 的主模型和备用模型。
     */
    @PostMapping("/{envId}/semiclaw/agents/{agentId}/model")
    public Map<String, Object> updateSemiclawAgentModel(
            @PathVariable String envId,
            @PathVariable String agentId,
            @RequestBody Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        try {
            Map<String, Object> env = requireAdminSemiclawEnv(envId);
            String primaryModelId = str(body.get("primaryModelId"));
            String fallbackModelId = str(body.get("fallbackModelId"));
            if (isBlank(primaryModelId) && isBlank(fallbackModelId)) {
                result.put("code", 400);
                result.put("message", "请至少提供 primaryModelId 或 fallbackModelId 其中一项");
                return result;
            }

            SemiclawEnvCredential credential = resolveSemiclawCredential(env);
            Map<String, Object> updated = semiclawApiService.updateAgentModel(
                    credential.baseUrl(), credential.tenantId(), credential.username(), credential.password(),
                    agentId, primaryModelId, fallbackModelId);
            result.put("code", 0);
            result.put("data", updated);
        } catch (IllegalArgumentException e) {
            result.put("code", 400);
            result.put("message", e.getMessage());
        } catch (Exception e) {
            result.put("code", 500);
            result.put("message", "更新 Agent 模型配置失败：" + e.getMessage());
        }
        return result;
    }

    /**
     * 删除 SemiClaw 技能文件夹（用于清理导入冲突产生的重复项）。
     */
    @PostMapping("/{envId}/semiclaw/skills/delete")
    public Map<String, Object> deleteSemiclawSkillFolders(
            @PathVariable String envId,
            @RequestBody Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        try {
            Map<String, Object> env = requireAdminSemiclawEnv(envId);
            SemiclawEnvCredential credential = resolveSemiclawCredential(env);

            @SuppressWarnings("unchecked")
            List<String> paths = (List<String>) body.get("paths");
            if (paths == null || paths.isEmpty()) {
                result.put("code", 400);
                result.put("message", "请至少选择一个要删除的技能文件夹");
                return result;
            }

            List<String> deleted = new ArrayList<>();
            Map<String, String> failed = new HashMap<>();
            for (String path : paths) {
                try {
                    semiclawApiService.deleteSkillFolder(
                            credential.baseUrl(), credential.tenantId(), credential.username(), credential.password(), path);
                    deleted.add(path);
                } catch (Exception e) {
                    failed.put(path, e.getMessage());
                }
            }

            result.put("code", 0);
            result.put("data", Map.of("deleted", deleted, "failed", failed));
        } catch (IllegalArgumentException e) {
            result.put("code", 400);
            result.put("message", e.getMessage());
        } catch (Exception e) {
            result.put("code", 500);
            result.put("message", "删除技能文件夹失败：" + e.getMessage());
        }
        return result;
    }

    private Map<String, Object> requireAdminSemiclawEnv(String envId) {
        Map<String, Object> env = envStore.get(envId);
        if (env == null) {
            throw new IllegalArgumentException("环境不存在");
        }

        String platform = str(env.get("platform"));
        if (!PLATFORM_SEMICLAW.equalsIgnoreCase(platform)) {
            throw new IllegalArgumentException("仅 SemiClaw 环境支持导入数字员工");
        }

        String accountRole = str(env.get("accountRole"));
        if (!ACCOUNT_ROLE_ADMIN.equalsIgnoreCase(accountRole)) {
            throw new IllegalArgumentException("仅账号角色为「管理员」的环境才能执行导入/改配置操作，请先在环境管理中将该环境的账号角色设置为管理员");
        }
        return env;
    }

    /**
     * 解析调用 SemiClaw 的凭据。service-account 模式传入空个人凭据，
     * 由服务层根据 baseUrl 回退到 application.properties 中的服务账号配置。
     */
    private SemiclawEnvCredential resolveSemiclawCredential(Map<String, Object> env) {
        String credentialMode = normalizeCredentialMode(firstNonBlank(
                str(env.get("credentialMode")),
                str(env.get("credential_mode")),
                str(env.get("loginMode")),
                str(env.get("login_mode"))
        ));
        boolean useServiceAccount = CREDENTIAL_MODE_SERVICE_ACCOUNT.equals(credentialMode);

        return new SemiclawEnvCredential(
                str(env.get("url")),
                useServiceAccount ? null : str(env.get("tenantId")),
                useServiceAccount ? null : str(env.get("username")),
                useServiceAccount ? null : str(env.get("password"))
        );
    }

    private record SemiclawEnvCredential(String baseUrl, String tenantId, String username, String password) {
    }

    private boolean isTarGzFile(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".tar.gz") || lower.endsWith(".tgz");
    }

    private Map<String, Object> sanitizeEnvironmentForClient(Map<String, Object> env) {
        Map<String, Object> safe = new HashMap<>();
        if (env == null) {
            safe.put("agentCount", 0);
            safe.put("platform", PLATFORM_SEMIMIND);
            safe.put("passwordConfigured", false);
            safe.put("credentialMode", CREDENTIAL_MODE_PERSONAL);
            safe.put("credential_mode", CREDENTIAL_MODE_PERSONAL);
            safe.put("loginMode", CREDENTIAL_MODE_PERSONAL);
            safe.put("login_mode", CREDENTIAL_MODE_PERSONAL);
            return safe;
        }

        safe.putAll(env);
        Object agents = safe.remove("agents");
        int count = 0;
        if (agents instanceof List) {
            count = ((List<?>) agents).size();
        }
        safe.put("agentCount", count);

        String platform = firstNonBlank(str(safe.get("platform")), PLATFORM_SEMIMIND);
        safe.put("platform", platform);

        String tenantId = firstNonBlank(
                str(safe.get("tenantId")),
                str(safe.get("tenant_id"))
        );
        String username = str(safe.get("username"));
        String password = firstNonBlank(
                str(safe.remove("password")),
                str(safe.remove("pwd"))
        );

        boolean passwordConfigured = !isBlank(password);
        safe.put("passwordConfigured", passwordConfigured);
        safe.put("hasPassword", passwordConfigured);

        String credentialMode = normalizeCredentialMode(firstNonBlank(
                str(safe.get("credentialMode")),
                str(safe.get("credential_mode")),
                str(safe.get("loginMode")),
                str(safe.get("login_mode"))
        ));
        if (isBlank(credentialMode)) {
            boolean isSemiclaw = PLATFORM_SEMICLAW.equalsIgnoreCase(platform);
            boolean hasCompletePersonalCredential = !isBlank(tenantId)
                    && !isBlank(username)
                    && passwordConfigured;
            credentialMode = isSemiclaw && !hasCompletePersonalCredential
                    ? CREDENTIAL_MODE_SERVICE_ACCOUNT
                    : CREDENTIAL_MODE_PERSONAL;
        }

        safe.put("credentialMode", credentialMode);
        safe.put("credential_mode", credentialMode);
        safe.put("loginMode", credentialMode);
        safe.put("login_mode", credentialMode);
        return safe;
    }

    private String normalizeCredentialMode(String mode) {
        if (isBlank(mode)) {
            return null;
        }

        String value = mode.trim().toLowerCase(Locale.ROOT).replace("_", "-");
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

    private String str(Object value) {
        return value == null ? null : value.toString();
    }

    /**
     * 从 SemiClaw 导入接口返回的 warnings 中提取“因跨租户冲突被重命名”的目标文件夹名。
     */
    private static final java.util.regex.Pattern CONFLICT_RENAME_PATTERN =
            java.util.regex.Pattern.compile("已重命名为\\s*['\"]([^'\"]+)['\"]");

    private Set<String> extractConflictRenamedNames(Object warnings) {
        if (!(warnings instanceof List<?> list)) {
            return Set.of();
        }

        Set<String> names = new java.util.HashSet<>();
        for (Object item : list) {
            if (item == null) {
                continue;
            }
            var matcher = CONFLICT_RENAME_PATTERN.matcher(item.toString());
            if (matcher.find()) {
                names.add(matcher.group(1));
            }
        }
        return names;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
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

    private int intValue(Object value, int defaultValue) {
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

    private boolean matchesAgentKeyword(Map<String, Object> agent, String keyword) {
        if (isBlank(keyword)) {
            return true;
        }
        String lowerKeyword = keyword.toLowerCase(Locale.ROOT);
        String name = firstNonBlank(
                str(agent.get("name")),
                str(agent.get("agentName")),
                str(agent.get("agent_name"))
        );
        String description = firstNonBlank(
                str(agent.get("roleDescription")),
                str(agent.get("role_description")),
                str(agent.get("description"))
        );
        String type = firstNonBlank(
                str(agent.get("agentType")),
                str(agent.get("agent_type")),
                str(agent.get("type"))
        );
        return containsIgnoreCase(name, lowerKeyword)
                || containsIgnoreCase(description, lowerKeyword)
                || containsIgnoreCase(type, lowerKeyword);
    }

    private boolean containsIgnoreCase(String value, String lowerKeyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(lowerKeyword);
    }
}
