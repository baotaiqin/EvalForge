package com.qatools.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Cipher;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户登录服务
 * 调用 SemiMind 平台的登录接口，获取 authorization token
 * 密码传输流程：明文 → Base64编码 → RSA加密 → Base64编码
 */
@Service
public class UserLoginService {

    private static final Logger log = LoggerFactory.getLogger(UserLoginService.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 共享 token 缓存：key = "envUrl|email"，value = { authorization, apiUsername, timestamp }
     * 评测和多 Agent 会话共享同一缓存，避免重复登录导致 SemiMind 踢掉旧 token
     */
    private static final ConcurrentHashMap<String, Map<String, Object>> tokenCache = new ConcurrentHashMap<>();
    private static final long TOKEN_CACHE_TTL = 25 * 60 * 1000L; // 25 分钟

    /** RSA 公钥（与 SemiMind 前端一致） */
    private static final String RSA_PUBLIC_KEY =
            "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEArq9XTUSeYr2+N1h3Af1/"
                    + "z8Dse/2yD0ZGrKwx+EEEcdsBLca9Ynmx3nIB5obmL1SfmSkLpB0OUACBmB5rEjBp"
                    + "2Q2F3AG3Hjd4B+gNCG6BDaawuD1gANIhGnaTLrIqWrrcm4EMzJOnAOI1fgzJRs0O"
                    + "UEfaS318Eq9OVO3apEyCCt0l0QK6PuksduOjVxtltDav+guVAA068NrPYmRNabVKR"
                    + "NLJpL8w4D44sfth5RvZ3q9t+6RTArpEtc5sh5ChzvqPOZKGMXW83C95TxmXqpbK6"
                    + "o1N4RevSfVjEAgCydH6HN6OhtOQFcnrU97r9H0iZOWwbw3pVrZiukuRD1R56Wzs2"
                    + "WIDAQAB";

    public UserLoginService() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(15));
        factory.setReadTimeout(Duration.ofSeconds(30));
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * 登录到 SemiMind 平台
     *
     * @param envUrl   环境前端URL，如 demo-sit.example.invalid:7111
     * @param email    用户邮箱/用户名
     * @param password 用户密码（明文，会自动加密）
     * @return { authorization: "...", apiUsername: "..." }
     */
    @SuppressWarnings("unchecked")
    public Map<String, String> login(String envUrl, String email, String password) {
        // 检查共享缓存，避免重复登录导致旧 token 被踢掉
        String cacheKey = envUrl + "|" + email;
        Map<String, Object> cached = tokenCache.get(cacheKey);
        if (cached != null) {
            long timestamp = (Long) cached.get("timestamp");
            if (System.currentTimeMillis() - timestamp < TOKEN_CACHE_TTL) {
                log.info("使用缓存的登录token: envUrl={}, email={}", envUrl, email);
                Map<String, String> result = new HashMap<>();
                result.put("authorization", (String) cached.get("authorization"));
                result.put("apiUsername", (String) cached.get("apiUsername"));
                return result;
            } else {
                tokenCache.remove(cacheKey);
            }
        }

        String url = "http://" + envUrl + "/v1/user/login";

        // 与前端一致：先 Base64 编码，再 RSA 加密
        String encryptedPassword = rsaEncrypt(password);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        Map<String, Object> body = new HashMap<>();
        body.put("email", email);
        body.put("password", encryptedPassword);

        log.info("调用登录接口: url={}, email={}", url, email);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
            String responseBody = response.getBody();

            if (responseBody == null || responseBody.isBlank()) {
                throw new RuntimeException("登录接口未返回响应");
            }

            log.info("登录响应: {}", responseBody.length() > 200
                    ? responseBody.substring(0, 200) + "..." : responseBody);

            Map<String, Object> resp = objectMapper.readValue(responseBody, Map.class);

            // 检查返回码
            Object code = resp.get("code");
            if (code == null) code = resp.get("retcode");
            if (code != null && !Objects.equals(code, 0) && !"0".equals(code.toString())) {
                Object msg = resp.get("message");
                if (msg == null) msg = resp.get("retmsg");
                throw new RuntimeException("登录失败(code=" + code + "): " + msg);
            }

            // 优先从响应头提取 Authorization（SemiMind 在响应头中返回 token）
            String authorization = null;
            String apiUsername = null;

            List<String> authHeaders = response.getHeaders().get("Authorization");
            if (authHeaders != null && !authHeaders.isEmpty()) {
                authorization = authHeaders.get(0);
                log.info("从响应头获取到Authorization: {}...",
                        authorization.length() > 20 ? authorization.substring(0, 20) : authorization);
            }

            // 从响应体提取 apiUsername
            Object data = resp.get("data");
            if (data instanceof Map) {
                Map<String, Object> dataMap = (Map<String, Object>) data;
                // 如果响应头没有 authorization，尝试从 body 提取
                if (authorization == null) {
                    for (String key : List.of("authorization", "token", "access_token", "auth_token")) {
                        Object val = dataMap.get(key);
                        if (val != null && !val.toString().isBlank()) {
                            authorization = val.toString();
                            break;
                        }
                    }
                }
                for (String key : List.of("username", "user_id", "userId", "id")) {
                    Object val = dataMap.get(key);
                    if (val != null && !val.toString().isBlank()) {
                        apiUsername = val.toString();
                        break;
                    }
                }
            } else if (data instanceof String && authorization == null) {
                authorization = (String) data;
            }

            if (authorization == null) {
                throw new RuntimeException("无法从登录响应中提取authorization token: " + responseBody);
            }

            Map<String, String> result = new HashMap<>();
            result.put("authorization", authorization);
            result.put("apiUsername", apiUsername != null ? apiUsername : email);

            // 存入共享缓存
            Map<String, Object> cacheEntry = new HashMap<>();
            cacheEntry.put("authorization", result.get("authorization"));
            cacheEntry.put("apiUsername", result.get("apiUsername"));
            cacheEntry.put("timestamp", System.currentTimeMillis());
            tokenCache.put(cacheKey, cacheEntry);

            log.info("登录成功: email={}, authToken={}...", email,
                    authorization.length() > 20 ? authorization.substring(0, 20) : authorization);

            return result;

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("登录请求失败: " + e.getMessage(), e);
        }
    }

    // ==================== RSA 加密（与 SemiMind 前端逻辑一致） ====================

    /**
     * 清除指定用户的 token 缓存（用于 token 失效后强制重新登录）
     */
    public void invalidateToken(String envUrl, String email) {
        String cacheKey = envUrl + "|" + email;
        tokenCache.remove(cacheKey);
        log.info("已清除token缓存: envUrl={}, email={}", envUrl, email);
    }

    /**
     * 清除所有 token 缓存
     */
    public void clearAllTokenCache() {
        tokenCache.clear();
        log.info("已清除所有token缓存");
    }

    /**
     * 加密密码：明文 → Base64编码 → RSA加密 → Base64编码
     * 对应前端： encryptor.encrypt(Base64.encode(password))
     */
    private String rsaEncrypt(String plainPassword) {
        try {
            // Step 1: Base64 编码明文密码（与前端 Base64.encode(password) 一致）
            String base64Password = Base64.getEncoder().encodeToString(
                    plainPassword.getBytes(StandardCharsets.UTF_8));

            // Step 2: RSA 加密
            byte[] keyBytes = Base64.getDecoder().decode(RSA_PUBLIC_KEY);
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(keyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            PublicKey publicKey = keyFactory.generatePublic(keySpec);

            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, publicKey);
            byte[] encrypted = cipher.doFinal(base64Password.getBytes(StandardCharsets.UTF_8));

            // Step 3: Base64 编码加密结果
            return Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            throw new RuntimeException("RSA加密密码失败: " + e.getMessage(), e);
        }
    }
}
