package com.evalforge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Broadcasts evaluation status changes to the list and detail views over one SSE stream.
 * Payload fields match the frontend's evalId/evaluationId/id lookup.
 */
@Component
public class EvaluationStatusBroadcaster {

    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_STOPPED = "stopped";
    public static final String STATUS_FAILED = "failed";

    private static final Logger log = LoggerFactory.getLogger(EvaluationStatusBroadcaster.class);

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService dispatchExecutor = Executors.newSingleThreadExecutor();

    /** Opens a long-lived subscription; browser disconnects remove it from the subscriber list. */
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(error -> emitters.remove(emitter));
        return emitter;
    }

    /** Sends a compact status event without blocking evaluation worker threads. */
    public void broadcast(String evalId, String status) {
        if (evalId == null || status == null || emitters.isEmpty()) {
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("evalId", evalId);
        payload.put("status", status);

        dispatchExecutor.submit(() -> {
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event().name("status")
                            .data(objectMapper.writeValueAsString(payload), MediaType.TEXT_PLAIN));
                } catch (Exception e) {
                    emitters.remove(emitter);
                    log.debug("移除已断开的评测状态 SSE 连接: {}", e.getMessage());
                }
            }
        });
    }

    @PreDestroy
    public void shutdown() {
        dispatchExecutor.shutdownNow();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.complete();
            } catch (Exception ignored) {
                log.debug("关闭评测状态 SSE 连接时忽略异常");
            }
        }
        emitters.clear();
    }
}
