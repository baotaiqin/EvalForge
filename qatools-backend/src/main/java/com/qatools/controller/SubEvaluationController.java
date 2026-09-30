package com.qatools.controller;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.qatools.service.EvaluationCancellationRegistry;
import com.qatools.service.EvaluationLifecycleHelper;
import com.qatools.service.EvaluationService;
import com.qatools.service.EvaluationStatusBroadcaster;
import com.qatools.service.EvaluationStoreService;
import com.qatools.service.ModelGroupStoreService;
import com.qatools.service.SubEvalTypeStoreService;
import com.qatools.service.SubEvaluationStoreService;

/**
 * “模型测评”子评测（如 CP优良率评测/UTC评测/AI-ppt评测）—— 一次性固化被测对象/数据集等配置，
 * 每次“开始测评”复用该配置生成一条新的 eval_record（版本号递增），执行链路直接复用
 * 现有 EvaluationService.executeStreaming()，不新增评测执行逻辑。
 */
@RestController
@RequestMapping("/api/sub-evaluations")
@CrossOrigin(origins = "*")
public class SubEvaluationController {

    private static final Logger log = LoggerFactory.getLogger(SubEvaluationController.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private SubEvaluationStoreService subEvalStore;

    @Autowired
    private SubEvalTypeStoreService typeStore;

    @Autowired
    private ModelGroupStoreService groupStore;

    @Autowired
    private EvaluationStoreService evalStore;

    @Autowired
    private EvaluationService evaluationService;

    @Autowired
    private EvaluationStatusBroadcaster statusBroadcaster;

    @Autowired
    private EvaluationLifecycleHelper lifecycle;

    @Autowired
    private EvaluationCancellationRegistry cancellationRegistry;

    @Autowired
    private ExecutorService sseExecutor;

    @GetMapping("/{id}")
    public Map<String, Object> getSubEvaluation(@PathVariable String id) {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> subEval = subEvalStore.get(id);
        if (subEval == null) {
            result.put("code", 404);
            result.put("message", "子评测不存在");
            return result;
        }
        result.put("code", 0);
        result.put("data", subEval);
        return result;
    }

    @PostMapping
    public Map<String, Object> createSubEvaluation(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        String groupId = (String) body.get("groupId");
        String subType = (String) body.get("subType");
        String typeName = resolveTypeName(subType);
        if (typeName == null) {
            result.put("code", 400);
            result.put("message", "评测类型不存在");
            return result;
        }
        if (subEvalStore.existsByGroupAndType(groupId, subType, null)) {
            result.put("code", 400);
            result.put("message", "该分组下已存在该类型的子评测，请直接编辑现有配置");
            return result;
        }
        try {
            String id = subEvalStore.nextId();
            Map<String, Object> subEval = new HashMap<>(body);
            subEval.put("id", id);
            subEval.put("name", typeName);
            subEvalStore.put(id, subEval);
            result.put("code", 0);
            result.put("data", subEvalStore.get(id));
        } catch (Exception e) {
            result.put("code", 500);
            result.put("message", "创建子评测失败：" + e.getMessage());
        }
        return result;
    }

    @PutMapping("/{id}")
    public Map<String, Object> updateSubEvaluation(@PathVariable String id,
                                                   @RequestBody Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> subEval = subEvalStore.get(id);
        if (subEval == null) {
            result.put("code", 404);
            result.put("message", "子评测不存在");
            return result;
        }
        String groupId = (String) subEval.get("groupId");
        String subType = (String) body.getOrDefault("subType", subEval.get("subType"));
        String typeName = resolveTypeName(subType);
        if (typeName == null) {
            result.put("code", 400);
            result.put("message", "评测类型不存在");
            return result;
        }
        if (subEvalStore.existsByGroupAndType(groupId, subType, id)) {
            result.put("code", 400);
            result.put("message", "该分组下已存在该类型的子评测，请直接编辑现有配置");
            return result;
        }
        subEval.putAll(body);
        subEval.put("id", id);
        subEval.put("name", typeName);
        subEvalStore.put(id, subEval);
        result.put("code", 0);
        result.put("data", subEvalStore.get(id));
        return result;
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> deleteSubEvaluation(@PathVariable String id) {
        Map<String, Object> result = new HashMap<>();
        subEvalStore.remove(id);
        result.put("code", 0);
        return result;
    }

    /**
     * 将该子评测的完整配置（被测对象/数据集/评判模型等）复制到另一个模型分组下。
     * 不复制历史执行记录，新副本版本号从 0 开始；同分组下同类型子评测唯一的约束仍生效。
     * 若目标分组已配置该类型则拒绝。副本 name 追加“_复制自_来源分组名”以便区分来源。
     */
    @PostMapping("/{id}/duplicate")
    public Map<String, Object> duplicateSubEvaluation(@PathVariable String id,
                                                      @RequestBody Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> source = subEvalStore.get(id);
        if (source == null) {
            result.put("code", 404);
            result.put("message", "子评测不存在");
            return result;
        }

        String targetGroupId = (String) body.get("targetGroupId");
        if (targetGroupId == null || targetGroupId.isBlank()) {
            result.put("code", 400);
            result.put("message", "请选择目标模型组");
            return result;
        }
        Map<String, Object> targetGroup = groupStore.get(targetGroupId);
        if (targetGroup == null) {
            result.put("code", 404);
            result.put("message", "目标分组不存在");
            return result;
        }

        String subType = (String) source.get("subType");
        if (subEvalStore.existsByGroupAndType(targetGroupId, subType, null)) {
            result.put("code", 400);
            result.put("message", "目标分组已存在该类型的子评测，请直接编辑该分组下的现有配置");
            return result;
        }

        try {
            String sourceGroupId = (String) source.get("groupId");
            Map<String, Object> sourceGroup = sourceGroupId == null ? null : groupStore.get(sourceGroupId);
            String sourceGroupName = sourceGroup != null
                    ? String.valueOf(sourceGroup.get("name")) : "未知分组";

            String newId = subEvalStore.nextId();
            Map<String, Object> copy = new HashMap<>(source);
            copy.put("id", newId);
            copy.put("groupId", targetGroupId);
            copy.put("name", source.get("name") + "_复制自_" + sourceGroupName);
            copy.remove("latestVersion");
            copy.remove("createdAt");
            copy.remove("updatedAt");
            subEvalStore.put(newId, copy);

            result.put("code", 0);
            result.put("data", subEvalStore.get(newId));
        } catch (Exception e) {
            log.error("复制子评测失败: subEvalId={}, targetGroupId={}", id, targetGroupId, e);
            result.put("code", 500);
            result.put("message", "复制子评测失败：" + e.getMessage());
        }
        return result;
    }

    /**
     * 该子评测下所有历史版本记录（按版本号倒序）。
     */
    @GetMapping("/{id}/records")
    public Map<String, Object> listRecords(@PathVariable String id) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("data", evalStore.getBySubEvalId(id));
        return result;
    }

    /**
     * 删除该子评测下某一个版本的评测记录，并按剩余记录重新校准 latest_version，
     * 使下一次“开始测评”从正确的版本号继续（而不是无限递增）。
     *
     * 若该版本当前仍有真实执行线程在跑(cancellationRegistry.isActive)，拒绝删除；
     * 执行线程对“记录已被删除”毫无感知，每题完成后仍会照常调用 evalStore.put() 落库，
     * 该 UPSERT 在记录已被物理删除的情况下会退化为重新 INSERT，导致被删记录“复活”，
     * 且与之后新触发的“开始测评”生成的新版本号相撞，产生同一版本号出现多条记录的乱象。
     */
    @DeleteMapping("/{id}/records/{evalId}")
    public Map<String, Object> deleteRecord(@PathVariable String id,
                                            @PathVariable String evalId) {
        Map<String, Object> result = new HashMap<>();
        if (cancellationRegistry.isActive(evalId)) {
            result.put("code", 400);
            result.put("message", "该版本正在执行中，请先终止评测再删除");
            return result;
        }
        evalStore.remove(evalId);
        subEvalStore.syncLatestVersion(id);
        result.put("code", 0);
        return result;
    }

    /**
     * 单个开始测评：复用子评测固化配置生成新版本 eval_record 并异步执行。
     */
    @PostMapping("/{id}/run")
    public Map<String, Object> run(@PathVariable String id) {
        Map<String, Object> result = new HashMap<>();
        try {
            Map<String, Object> record = startRun(id);
            result.put("code", 0);
            result.put("data", record);
        } catch (IllegalStateException e) {
            result.put("code", 400);
            result.put("message", e.getMessage());
        } catch (Exception e) {
            log.error("开始测评失败: subEvalId={}", id, e);
            result.put("code", 500);
            result.put("message", "开始测评失败：" + e.getMessage());
        }
        return result;
    }

    /**
     * 批量开始测评：对每个子评测 id 依次触发，各自独立生成各自的版本记录，互不影响。
     * 单个子评测触发失败不影响其余子评测继续执行。
     */
    @PostMapping("/batch-run")
    public Map<String, Object> batchRun(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) body.get("ids");
        List<Map<String, Object>> records = new ArrayList<>();
        if (ids != null) {
            for (String id : ids) {
                try {
                    records.add(startRun(id));
                } catch (Exception e) {
                    log.warn("批量开始测评: subEvalId={} 触发失败: {}", id, e.getMessage());
                    Map<String, Object> failed = new HashMap<>();
                    failed.put("subEvalId", id);
                    failed.put("error", e.getMessage());
                    records.add(failed);
                }
            }
        }
        result.put("code", 0);
        result.put("data", records);
        return result;
    }

    /**
     * 读取子评测固化配置，生成新一轮版本记录并提交异步执行，返回新生成的 eval_record 摘要。
     */
    private Map<String, Object> startRun(String subEvalId) {
        Map<String, Object> subEval = subEvalStore.get(subEvalId);
        if (subEval == null) {
            throw new IllegalStateException("子评测不存在：" + subEvalId);
        }

        String subType = (String) subEval.get("subType");
        String groupId = (String) subEval.get("groupId");
        Map<String, Object> group = groupId == null ? null : groupStore.get(groupId);
        String groupName = group != null ? String.valueOf(group.get("name")) : "";
        String subEvalName = resolveTypeName(subType);
        if (subEvalName == null) {
            subEvalName = String.valueOf(subEval.get("name"));
        }

        int version = subEvalStore.incrementVersion(subEvalId);

        String evalId = evalStore.nextId();
        Map<String, Object> eval = new HashMap<>();
        eval.put("id", evalId);
        eval.put("name", groupName + "_" + subEvalName + "_v" + version);
        eval.put("type", subEval.get("targetType"));
        eval.put("status", EvaluationStatusBroadcaster.STATUS_RUNNING);
        eval.put("startTime", LocalDateTime.now().format(FMT));
        eval.put("endTime", null);
        eval.put("accuracy", null);
        eval.put("results", new ArrayList<>());
        eval.put("targets", subEval.get("targets"));
        eval.put("datasetIds", subEval.get("datasetIds"));
        eval.put("datasetNames", subEval.get("datasetNames"));
        eval.put("datasetMapping", subEval.get("datasetMapping"));
        eval.put("judgeMode", subEval.get("judgeMode"));
        eval.put("judgeModelId", subEval.get("judgeModelId"));
        eval.put("remark", subEval.get("remark"));
        eval.put("subEvalId", subEvalId);
        eval.put("subEvalVersion", version);

        lifecycle.normalizeEvaluationMetadata(eval);
        evalStore.put(evalId, eval);
        statusBroadcaster.broadcast(evalId, EvaluationStatusBroadcaster.STATUS_RUNNING);

        SseEmitter internalEmitter = new SseEmitter(60 * 60 * 1000L);
        sseExecutor.submit(() -> {
            try {
                evaluationService.executeStreaming(evalId, internalEmitter);
                internalEmitter.complete();
            } catch (Throwable e) {
                log.error("子评测执行失败: subEvalId={}, evalId={}, error={}",
                        subEvalId, evalId, e.getMessage(), e);
                Map<String, Object> failedEval = evalStore.get(evalId);
                if (failedEval != null
                        && EvaluationStatusBroadcaster.STATUS_RUNNING.equals(failedEval.get("status"))) {
                    lifecycle.markFailed(failedEval, "执行线程异常：" + e.getMessage());
                }
                internalEmitter.completeWithError(e);
            }
        });

        Map<String, Object> summary = new HashMap<>();
        summary.put("subEvalId", subEvalId);
        summary.put("evalId", evalId);
        summary.put("version", version);
        summary.put("name", eval.get("name"));
        return summary;
    }

    /**
     * 查询子评测类型当前的展示名称，类型不存在时返回 null。
     */
    private String resolveTypeName(String subType) {
        if (subType == null) {
            return null;
        }
        Map<String, Object> type = typeStore.get(subType);
        return type == null ? null : String.valueOf(type.get("name"));
    }
}
