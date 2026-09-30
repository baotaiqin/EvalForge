package com.qatools.service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PreDestroy;

/**
 * 评测记录逐题进度广播器。
 * 按 evalId 分组维护订阅了“评测详情页实时进度”的 SSE 长连接，
 * 评测执行过程中每题题完成后主动推送给该 evalId 下所有订阅。
 * 详情页无需发起轮询即可看到逐题结果。
 * 设计上是 EvaluationStatusBroadcaster 的按 evalId 分组版本，事件粒度更细。
 */
@Component
public class EvaluationProgressBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(EvaluationProgressBroadcaster.class);

    private final Map<String, List<SseEmitter>> emittersByEvalId = new ConcurrentHashMap<>();
    // 最近一次 progress 事件快照(当前处理到第几题)。纯广播模式下新订阅者只能等下一次真实事件
    // 才知道当前进度——重新测评/重新打开详情页时常常错过“当前题”的那次事件，
    // 缓存快照，subscribe() 时可以立即补发，不必等下一题。
    private final Map<String, Map<String, Object>> latestProgressByEvalId = new ConcurrentHashMap<>();
    // 当前题目每个 target(被测 Agent/模型) 最新的执行步骤(调用中/已应答/评分中/已评分)，
    // 外层 key 为 evalId，内层 key 为 targetId
    private final Map<String, Map<String, Map<String, Object>>> latestStepsByEvalId = new ConcurrentHashMap<>();
    // 已完成题目的 question 事件快照，按 questionIndex 排序。
    // 用户中途打开详情页时，fetchDetail 只能拿到数据库已落库的数据；
    // 若某题正好在 fetchDetail 之后才落库，则该题在前端会停留在占位行状态（显示“无文本提问”）。
    // 缓存 question 事件后，subscribe() 时按顺序补发，确保已完成题目的结果不会丢失。
    private final Map<String, Map<Integer, Map<String, Object>>> questionSnapshotsByEvalId = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    // 单线程执行器：串行处理所有推送，避免并发对同一个 SseEmitter 并发写入导致 SSE 帧交织，
    // 同时让 push() 立即返回，不阻塞评测执行线程
    private final ExecutorService dispatchExecutor = Executors.newSingleThreadExecutor();

    /**
     * 新建一条订阅连接。不设超时，评测结束(complete事件)后由 closeAll() 主动关闭。
     */
    public SseEmitter subscribe(String evalId) {
        SseEmitter emitter = new SseEmitter(0L);
        List<SseEmitter> emitters = emittersByEvalId.computeIfAbsent(evalId, k -> new CopyOnWriteArrayList<>());
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));

        // 通过同一个串行 dispatchExecutor 补发快照，避免和并发的真实事件推送在同一个
        // emitter 上交叉写入导致 SSE 帧边界混乱。
        dispatchExecutor.submit(() -> {
            // 补发已完成题目的 question 事件（按 questionIndex 升序）
            Map<Integer, Map<String, Object>> questionSnapshots = questionSnapshotsByEvalId.get(evalId);
            if (questionSnapshots != null) {
                questionSnapshots.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .forEach(e -> sendToEmitter(emitter, "question", e.getValue(), emitters));
            }

            Map<String, Object> progressSnapshot = latestProgressByEvalId.get(evalId);
            if (progressSnapshot != null) {
                sendToEmitter(emitter, "progress", progressSnapshot, emitters);
            }
            Map<String, Map<String, Object>> steps = latestStepsByEvalId.get(evalId);
            if (steps != null) {
                for (Map<String, Object> stepPayload : steps.values()) {
                    sendToEmitter(emitter, "step", stepPayload, emitters);
                }
            }
        });

        return emitter;
    }

    /**
     * 推送一次事件给指定评测记录的所有订阅端。异步执行，不阻塞调用方（评测执行线程）。
     */
    public void push(String evalId, String eventName, Map<String, Object> payload) {
        if ("progress".equals(eventName)) {
            // progress 代表一道新题目开始，之前缓存的该 target 步骤已经过时，清空避免串题
            latestProgressByEvalId.put(evalId, payload);
            latestStepsByEvalId.remove(evalId);
        } else if ("step".equals(eventName)) {
            Object targetId = payload.get("targetId");
            if (targetId != null) {
                latestStepsByEvalId
                        .computeIfAbsent(evalId, k -> new ConcurrentHashMap<>())
                        .put(targetId.toString(), payload);
            }
        } else if ("question".equals(eventName)) {
            // 缓存已完成题目的 question 事件，供中途订阅的客户端补发
            Object questionIndex = payload.get("questionIndex");
            if (questionIndex instanceof Number) {
                questionSnapshotsByEvalId
                        .computeIfAbsent(evalId, k -> new ConcurrentHashMap<>())
                        .put(((Number) questionIndex).intValue(), payload);
            }
        }

        List<SseEmitter> emitters = emittersByEvalId.get(evalId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }

        dispatchExecutor.submit(() -> {
            for (SseEmitter emitter : emitters) {
                sendToEmitter(emitter, eventName, payload, emitters);
            }
        });
    }

    private void sendToEmitter(SseEmitter emitter, String eventName, Object payload, List<SseEmitter> emitters) {
        try {
            emitter.send(SseEmitter.event().name(eventName)
                    .data(objectMapper.writeValueAsString(payload), MediaType.TEXT_PLAIN));
        } catch (Exception e) {
            emitters.remove(emitter);
        }
    }

    /**
     * 评测结束(完成/失败/终止)后关闭 evalId 下所有订阅连接并清理，避免 Map 无限增长。
     */
    public void closeAll(String evalId) {
        latestProgressByEvalId.remove(evalId);
        latestStepsByEvalId.remove(evalId);
        questionSnapshotsByEvalId.remove(evalId);
        List<SseEmitter> emitters = emittersByEvalId.remove(evalId);
        if (emitters == null) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.complete();
            } catch (Exception ignored) {
                log.debug("关闭评测进度订阅连接时忽略异常");
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        dispatchExecutor.shutdownNow();
        for (List<SseEmitter> emitters : emittersByEvalId.values()) {
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                    log.debug("关闭评测进度订阅连接时忽略异常");
                }
            }
        }
    }
}
