package com.evalforge.controller;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.evalforge.service.ModelStoreService;

@RestController
@RequestMapping("/api/models")
@CrossOrigin(origins = "*")
public class ModelController {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private ModelStoreService modelStore;

    /**
     * 获取所有模型列表
     */
    @GetMapping
    public Map<String, Object> listModels() {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("data", modelStore.getAll());
        return result;
    }

    /**
     * 创建模型
     */
    @PostMapping
    public Map<String, Object> createModel(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        try {
            String id = modelStore.nextId();
            Map<String, Object> model = new HashMap<>(body);
            model.put("id", id);
            model.put("createdAt", LocalDateTime.now().format(FMT));
            modelStore.put(id, model);
            result.put("code", 0);
            result.put("data", model);
        } catch (Exception e) {
            result.put("code", 500);
            result.put("message", "创建模型失败：" + e.getMessage());
        }
        return result;
    }

    /**
     * 更新模型
     */
    @PutMapping("/{id}")
    public Map<String, Object> updateModel(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> model = modelStore.get(id);
        if (model == null) {
            result.put("code", 404);
            result.put("message", "模型不存在");
            return result;
        }
        model.putAll(body);
        model.put("id", id);
        modelStore.put(id, model);
        result.put("code", 0);
        result.put("data", model);
        return result;
    }

    /**
     * 删除模型
     */
    @DeleteMapping("/{id}")
    public Map<String, Object> deleteModel(@PathVariable String id) {
        Map<String, Object> result = new HashMap<>();
        modelStore.remove(id);
        result.put("code", 0);
        return result;
    }

    /**
     * 测试模型连接：发送一条最小 chat 请求，验证 baseUrl/apiKey/modelName 三项配置可用。
     * 请求本身与模型对话无关，仅用于验证模型对外接口和鉴权是否可用。
     */
    @PostMapping("/test")
    public Map<String, Object> testConnection(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        try {
            String baseUrl = stringValue(body.get("baseUrl"));
            String apiKey = stringValue(body.get("apiKey"));
            String name = stringValue(body.get("name"));

            if (isBlank(baseUrl) || isBlank(apiKey) || isBlank(name)) {
                result.put("code", 400);
                result.put("message", "baseUrl、apiKey、name 均为必填项");
                return result;
            }

            String url = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            url += "/chat/completions";

            var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(java.time.Duration.ofSeconds(10));
            factory.setReadTimeout(java.time.Duration.ofSeconds(30));
            org.springframework.web.client.RestTemplate restTemplate =
                    new org.springframework.web.client.RestTemplate(factory);

            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            Map<String, Object> reqBody = new HashMap<>();
            reqBody.put("model", name);
            reqBody.put("stream", false);
            reqBody.put("max_tokens", 1);
            reqBody.put("messages", java.util.List.of(
                    java.util.Map.of("role", "user", "content", "hi")));

            org.springframework.http.HttpEntity<Map<String, Object>> entity =
                    new org.springframework.http.HttpEntity<>(reqBody, headers);
            org.springframework.http.ResponseEntity<?> resp =
                    restTemplate.postForEntity(url, entity, Map.class);

            if (resp.getStatusCode().is2xxSuccessful()) {
                result.put("code", 0);
                result.put("message", "连接成功");
            } else {
                result.put("code", resp.getStatusCode().value());
                result.put("message", "服务返回非 2xx 状态码: " + resp.getStatusCode().value());
            }
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            result.put("code", e.getStatusCode().value());
            result.put("message", "认证或请求错误（" + e.getStatusCode().value() + "）: "
                    + e.getResponseBodyAsString());
        } catch (org.springframework.web.client.ResourceAccessException e) {
            result.put("code", 503);
            result.put("message", "无法连接到服务：" + e.getMessage());
        } catch (Exception e) {
            result.put("code", 500);
            result.put("message", "测试失败：" + e.getMessage());
        }
        return result;
    }

    private String stringValue(Object v) {
        return v == null ? "" : v.toString().trim();
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
