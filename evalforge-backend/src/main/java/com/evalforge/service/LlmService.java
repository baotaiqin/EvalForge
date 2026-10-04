package com.evalforge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * 大模型调用服务
 * 支持 OpenAI 兼容的 Chat Completions API 格式
 */
@Service
public class LlmService {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newFixedThreadPool(10);

    public LlmService() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(15));
        factory.setReadTimeout(Duration.ofSeconds(120));
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * 同步调用 LLM Chat API（OpenAI 兼容格式）
     *
     * @param modelConfig 模型配置，包含 baseUrl、apiKey、name、params
     * @param messages 完整的对话消息列表 [{role, content}, ...]
     * @return 模型回答文本
     */
    @SuppressWarnings("unchecked")
    public String chat(Map<String, Object> modelConfig, List<Map<String, String>> messages) {
        String baseUrl = (String) modelConfig.get("baseUrl");
        String apiKey = (String) modelConfig.get("apiKey");
        String modelName = (String) modelConfig.get("name");
        Map<String, Object> params = (Map<String, Object>) modelConfig.get("params");

        // 构建请求 URL
        String url = baseUrl;
        if (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        url += "/chat/completions";

        // 构建请求头
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        // 构建请求体
        Map<String, Object> body = new HashMap<>();
        body.put("model", modelName);
        body.put("messages", messages);
        body.put("stream", false);

        // 设置模型参数
        if (params != null) {
            if (params.get("temperature") != null) {
                body.put("temperature", toDouble(params.get("temperature")));
            }
            if (params.get("topP") != null) {
                body.put("top_p", toDouble(params.get("topP")));
            }
            if (params.get("maxInputTokens") != null) {
                body.put("max_tokens", toInt(params.get("maxInputTokens")));
            }
        }

        // 合并额外参数
        if (params != null && params.get("extraParams") != null) {
            String extra = params.get("extraParams").toString().trim();
            if (!extra.isEmpty()) {
                try {
                    Map<String, Object> extraMap = objectMapper.readValue(extra, Map.class);
                    body.putAll(extraMap);
                } catch (Exception e) {
                    log.warn("解析额外参数失败: {}", extra);
                }
            }
        }

        log.info("调用LLM: model={}, url={}, messagesCount={}", modelName, url, messages.size());
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);

        // 解析响应
        Map<String, Object> responseBody = response.getBody();
        if (responseBody == null) return "模型未返回响应";

        List<Map<String, Object>> choices = (List<Map<String, Object>>) responseBody.get("choices");
        if (choices != null && !choices.isEmpty()) {
            Map<String, Object> msg = (Map<String, Object>) choices.get(0).get("message");
            if (msg != null && msg.get("content") != null) {
                return msg.get("content").toString();
            }
        }
        return "模型未返回有效回答";
    }

    /**
     * 同步调用（向后兼容，单条问题）
     */
    public String chat(Map<String, Object> modelConfig, String question) {
        return chat(modelConfig, List.of(Map.of("role", "user", "content", question)));
    }

    /**
     * 异步调用 LLM API（带历史消息），返回包含 answer、responseTime 的结果
     */
    public CompletableFuture<Map<String, Object>> chatAsync(Map<String, Object> modelConfig,
            List<Map<String, String>> messages) {
        return CompletableFuture.supplyAsync(() -> {
            long start = System.currentTimeMillis();
            Map<String, Object> result = new HashMap<>();
            result.put("modelId", modelConfig.get("id"));
            result.put("modelName", modelConfig.get("name"));
            try {
                String answer = chat(modelConfig, messages);
                result.put("answer", answer);
                result.put("responseTime", System.currentTimeMillis() - start);
            } catch (Exception e) {
                log.error("LLM调用失败 model={}: {}", modelConfig.get("name"), e.getMessage());
                String errorMsg = e.getMessage();
                if (errorMsg != null && errorMsg.length() > 200) {
                    errorMsg = errorMsg.substring(0, 200) + "...";
                }
                result.put("answer", "调用失败: " + errorMsg);
                result.put("responseTime", System.currentTimeMillis() - start);
            }
            return result;
        }, executor).orTimeout(60, TimeUnit.SECONDS);
    }

    /**
     * 异步调用（向后兼容，单条问题）
     */
    public CompletableFuture<Map<String, Object>> chatAsync(Map<String, Object> modelConfig, String question) {
        return chatAsync(modelConfig, List.of(Map.of("role", "user", "content", question)));
    }

    private double toDouble(Object value) {
        if (value instanceof Number) return ((Number) value).doubleValue();
        return Double.parseDouble(value.toString());
    }

    private int toInt(Object value) {
        if (value instanceof Number) return ((Number) value).intValue();
        return Integer.parseInt(value.toString());
    }
}
