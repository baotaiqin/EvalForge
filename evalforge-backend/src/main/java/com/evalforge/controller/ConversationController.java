package com.evalforge.controller;

import com.evalforge.service.AgentChatService;
import com.evalforge.service.LlmService;
import com.evalforge.service.ModelStoreService;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api/conversation")
@CrossOrigin(origins = "*")
public class ConversationController {

    private static final Logger log = LoggerFactory.getLogger(ConversationController.class);

    @Autowired
    private LlmService llmService;

    @Autowired
    private ModelStoreService modelStore;

    @Autowired
    private AgentChatService agentChatService;

    private final ExecutorService sseExecutor = Executors.newFixedThreadPool(10);

    /**
     * 单Agent流式对话 — SSE 代理转发
     *
     * 前端为每个Agent建立独立的 SSE 连接，后端：
     * 1. 建立会话（publish → new_token）
     * 2. 将 SemiMind chat SSE 流原样转发给前端
     * 3. 前端自行解析 SSE 数据，累积 answer、渲染 markdown
     *
     * SSE 事件协议：
     * event: session — {"dialogId":"...", "chatToken":"..."}
     * event: message — SemiMind 原始 SSE 数据
     * event: error   — {"message":"..."}
     */
    @PostMapping("/agent/stream")
    public SseEmitter streamAgent(@RequestBody Map<String, Object> body,
                                  HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");

        SseEmitter emitter = new SseEmitter(300_000L);

        String envUrl = (String) body.get("envUrl");
        String agentId = (String) body.get("agentId");
        String agentType = (String) body.getOrDefault("agentType", "bot");
        String question = (String) body.get("question");
        String dialogId = (String) body.get("dialogId");
        String chatToken = (String) body.get("chatToken");
        String authorization = (String) body.get("authorization");
        String apiUsername = (String) body.get("apiUsername");

        sseExecutor.submit(() -> {
            try {
                agentChatService.streamChatWithAgent(
                        envUrl, agentId, agentType, question,
                        dialogId, chatToken, authorization, apiUsername,
                        emitter);
                emitter.complete();
            } catch (Exception e) {
                log.error("Agent 流式对话失败: envUrl={}, agentId={}, error={}",
                        envUrl, agentId, e.getMessage());
                try {
                    String errMsg = e.getMessage() != null ? e.getMessage() : "未知错误";
                    if (errMsg.length() > 200) {
                        errMsg = errMsg.substring(0, 200) + "...";
                    }
                    emitter.send(SseEmitter.event().name("error")
                            .data("{\"message\":\"" + errMsg.replace("\"", "") + "\"}"));
                } catch (Exception ignored) {
                }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * 多模型问答 — 并发调用选中的多个大模型（支持对话历史）。
     */
    @SuppressWarnings("unchecked")
    @PostMapping("/models")
    public Map<String, Object> chatWithModels(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        try {
            String question = (String) body.get("question");
            List<String> modelIds = (List<String>) body.get("modelIds");
            List<Map<String, Object>> history = (List<Map<String, Object>>) body.get("history");

            List<CompletableFuture<Map<String, Object>>> futures = new ArrayList<>();
            for (String modelId : modelIds) {
                Map<String, Object> modelConfig = modelStore.get(modelId);
                if (modelConfig == null) {
                    futures.add(CompletableFuture.completedFuture(Map.of(
                            "modelId", modelId,
                            "modelName", "未知模型",
                            "answer", "模型配置不存在，请检查模型是否已被删除",
                            "responseTime", 0
                    )));
                } else {
                    List<Map<String, String>> messages = buildMessages(modelId, history, question);
                    futures.add(llmService.chatAsync(modelConfig, messages));
                }
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            List<Map<String, Object>> responses = new ArrayList<>();
            for (CompletableFuture<Map<String, Object>> future : futures) {
                responses.add(future.get());
            }

            Map<String, Object> data = new HashMap<>();
            data.put("responses", responses);
            result.put("code", 0);
            result.put("data", data);
        } catch (Exception e) {
            log.error("多模型对话失败", e);
            result.put("code", 500);
            result.put("message", "对话请求失败：" + e.getMessage());
        }
        return result;
    }

    /**
     * 重新回答（单个模型）。Agent 类型的重新回答由前端重新调用 /agent/stream SSE 流式端点。
     */
    @PostMapping("/regenerate")
    public Map<String, Object> regenerate(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        try {
            String question = (String) body.get("question");
            String targetId = (String) body.get("targetId");

            Map<String, Object> modelConfig = modelStore.get(targetId);
            if (modelConfig == null) {
                result.put("code", 404);
                result.put("message", "模型配置不存在");
                return result;
            }

            long start = System.currentTimeMillis();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> history = (List<Map<String, Object>>) body.get("history");
            List<Map<String, String>> messages = buildMessages(targetId, history, question);
            String answer = llmService.chat(modelConfig, messages);

            Map<String, Object> data = new HashMap<>();
            data.put("answer", answer);
            data.put("responseTime", System.currentTimeMillis() - start);
            result.put("code", 0);
            result.put("data", data);
        } catch (Exception e) {
            log.error("重新生成失败", e);
            result.put("code", 500);
            result.put("message", "重新生成请求失败：" + e.getMessage());
        }
        return result;
    }

    /**
     * 根据指定模型从历史轮次中提取对应回答，并组装为该模型的 messages 上下文。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, String>> buildMessages(String modelId,
                                                    List<Map<String, Object>> history,
                                                    String currentQuestion) {
        List<Map<String, String>> messages = new ArrayList<>();

        if (history != null) {
            for (Map<String, Object> turn : history) {
                String question = (String) turn.get("question");
                if (question != null && !question.isBlank()) {
                    messages.add(Map.of("role", "user", "content", question));
                }
                Map<String, String> answers = (Map<String, String>) turn.get("answers");
                if (answers != null) {
                    String answer = answers.get(modelId);
                    if (answer != null && !answer.isBlank()) {
                        messages.add(Map.of("role", "assistant", "content", answer));
                    }
                }
            }
        }

        messages.add(Map.of("role", "user", "content", currentQuestion));
        return messages;
    }
}
