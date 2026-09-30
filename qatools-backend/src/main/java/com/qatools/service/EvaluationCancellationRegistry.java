package com.qatools.service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * 评测终止请求登记表。
 * 记录用户请求终止的评测记录 evalId，由 EvaluationService/MultimodalEvaluationService
 * 的 executeStreaming() 主循环在每道题开始前检查，命中则提前结束循环，
 * 已完成的题目结果保留，评测记录状态标记为 stopped。
 *
 * 同时维护一份“当前是否有真实执行线程在跑该 evalId”的登记表(activeEvalIds)，
 * 用于区分正常执行中的记录与数据库 status 为 running 但实际执行线程已不存在的僵尸记录
 *（例如进程/容器被重启导致执行线程被强制终止，但状态未来得及落库为 failed）。
 * 后者无法通过 requestCancel() 触发任何循环检测，需要 EvaluationController 直接落库终止。
 */
@Component
public class EvaluationCancellationRegistry {

    private final Set<String> cancelledEvalIds = ConcurrentHashMap.newKeySet();
    private final Set<String> activeEvalIds = ConcurrentHashMap.newKeySet();

    public void requestCancel(String evalId) {
        cancelledEvalIds.add(evalId);
    }

    public boolean isCancelled(String evalId) {
        return cancelledEvalIds.contains(evalId);
    }

    public void clear(String evalId) {
        cancelledEvalIds.remove(evalId);
    }

    public void markActive(String evalId) {
        activeEvalIds.add(evalId);
    }

    public void markInactive(String evalId) {
        activeEvalIds.remove(evalId);
    }

    public boolean isActive(String evalId) {
        return activeEvalIds.contains(evalId);
    }
}
