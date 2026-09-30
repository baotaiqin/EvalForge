package com.qatools.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 评测记录存储服务 - 基于 MySQL 持久化。
 *
 * 设计目标：
 * 1. 保留旧文本评测的存储方式。
 * 2. 持久化多模态报告所需的 evaluationType / reportType / datasetType / modalities。
 * 3. 对无参考答案的结果统一补充 scoreStatus，避免报告页把未评分样本展示为 0 分。
 * 4. 兼容旧数据：读取历史记录时也尽量补齐报告字段。
 */
@Service
public class EvaluationStoreService {

    private static final Logger log = LoggerFactory.getLogger(EvaluationStoreService.class);

    private static final String EVALUATION_TYPE_TEXT = "text";
    private static final String EVALUATION_TYPE_MULTIMODAL = "multimodal";
    private static final String REPORT_TYPE_TEXT = "text";
    private static final String REPORT_TYPE_MULTIMODAL = "multimodal";

    private static final String SCORE_STATUS_AUTO_SCORED = "auto_scored";
    private static final String SCORE_STATUS_MANUAL_REVIEW = "manual_review";
    private static final String SCORE_STATUS_MANUAL_SCORED = "manual_scored";
    private static final String SCORE_STATUS_PENDING = "pending";

    private static final String SCORE_DISPLAY_AUTO_SCORED = "自动评分";
    private static final String SCORE_DISPLAY_MANUAL_REVIEW = "待人工评分";
    private static final String SCORE_DISPLAY_MANUAL_SCORED = "已人工评分";
    private static final String SCORE_DISPLAY_PENDING = "待评分";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicInteger idCounter = new AtomicInteger(1);

    @PostConstruct
    public void init() {
        try {
            Integer max = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(MAX(CAST(SUBSTRING_INDEX(id, '-', -1) AS UNSIGNED)), 0) FROM eval_record",
                    Integer.class);
            if (max != null) {
                idCounter.set(max + 1);
            }
        } catch (Exception e) {
            log.warn("初始化评测ID计数器失败，使用默认值: {}", e.getMessage());
        }

        // 自动添加评测方式相关列（兼容旧表）
        addColumnIfMissing("judge_mode", "VARCHAR(20) NULL");
        addColumnIfMissing("judge_model_id", "VARCHAR(50) NULL");

        // 自动添加评测类型相关列（兼容旧表）
        addColumnIfMissing("evaluation_type", "VARCHAR(32) NOT NULL DEFAULT 'text'");
        addColumnIfMissing("report_type", "VARCHAR(32) NOT NULL DEFAULT 'text'");
        addColumnIfMissing("has_multimodal", "TINYINT(1) NOT NULL DEFAULT 0");

        // 报告展示增强字段（兼容旧表）
        addColumnIfMissing("dataset_type", "VARCHAR(32) NULL");
        addColumnIfMissing("modalities", "TEXT NULL");
        addColumnIfMissing("score_status", "VARCHAR(32) NULL");
        addColumnIfMissing("score_display", "VARCHAR(64) NULL");

        // 模型评测：关联子评测配置及版本号（兼容旧表，为 null 表示原有的 Agent 评测记录）
        addColumnIfMissing("sub_eval_id", "VARCHAR(50) NULL");
        addColumnIfMissing("sub_eval_version", "INT NULL");
        addIndexIfMissing("idx_eval_record_sub_eval_id", "sub_eval_id");

        normalizeExistingEvaluationMetadata();
    }

    public String nextId() {
        return "eval-" + String.format("%03d", idCounter.getAndIncrement());
    }

    public Map<String, Object> get(String id) {
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM eval_record WHERE id = ?", id);
            return rowToMap(row);
        } catch (Exception e) {
            return null;
        }
    }

    public void put(String id, Map<String, Object> eval) {
        try {
            String name = asString(eval.get("name"));
            String type = asString(eval.get("type"));
            String status = asString(eval.get("status"));
            String startTime = asString(eval.get("startTime"));
            String endTime = asString(eval.get("endTime"));
            Double accuracy = toDoubleOrNull(eval.get("accuracy"));
            String remark = asString(eval.get("remark"));
            String judgeMode = asString(eval.get("judgeMode"));
            String judgeModelId = asString(eval.get("judgeModelId"));
            String subEvalId = asString(firstPresent(eval, "subEvalId", "sub_eval_id"));
            Integer subEvalVersion = toIntegerOrNull(firstPresent(eval, "subEvalVersion", "sub_eval_version"));

            List<String> modalities = normalizeModalities(firstPresent(eval, "modalities", "modality"));
            String datasetType = normalizeDatasetType(
                    firstPresent(eval, "datasetType", "dataset_type"),
                    EVALUATION_TYPE_TEXT);

            Object rawHasMultimodal = firstPresent(eval, "hasMultimodal", "has_multimodal");
            boolean hasMultimodal = asBoolean(rawHasMultimodal, false);

            if (EVALUATION_TYPE_MULTIMODAL.equals(datasetType) || containsNonTextModality(modalities)) {
                hasMultimodal = true;
            }

            String evaluationType = normalizeEvaluationType(
                    firstPresent(eval, "evaluationType", "evaluation_type"),
                    hasMultimodal ? EVALUATION_TYPE_MULTIMODAL : EVALUATION_TYPE_TEXT);
            if (EVALUATION_TYPE_MULTIMODAL.equals(evaluationType)) {
                hasMultimodal = true;
                datasetType = EVALUATION_TYPE_MULTIMODAL;
            }

            String reportType = normalizeReportType(
                    firstPresent(eval, "reportType", "report_type"),
                    EVALUATION_TYPE_MULTIMODAL.equals(evaluationType)
                            ? REPORT_TYPE_MULTIMODAL : REPORT_TYPE_TEXT);
            if (REPORT_TYPE_MULTIMODAL.equals(reportType)) {
                hasMultimodal = true;
                evaluationType = EVALUATION_TYPE_MULTIMODAL;
                datasetType = EVALUATION_TYPE_MULTIMODAL;
            }

            List<Map<String, Object>> results = normalizeResultsForReport(
                    eval.get("results"), evaluationType, datasetType, modalities);
            if ((modalities == null || modalities.isEmpty()) && results != null) {
                modalities = inferModalitiesFromResults(results);
            }
            if (modalities == null || modalities.isEmpty()) {
                modalities = Collections.singletonList("text");
            }
            if (containsNonTextModality(modalities)) {
                hasMultimodal = true;
                evaluationType = EVALUATION_TYPE_MULTIMODAL;
                reportType = REPORT_TYPE_MULTIMODAL;
                datasetType = EVALUATION_TYPE_MULTIMODAL;
            }

            Double storedAccuracy = accuracy;
            boolean hasResults = results != null && !results.isEmpty();
            boolean hasScorableResults = hasScorableResults(results);
            if (hasResults && !hasScorableResults) {
                // 无参考答案或全部待人工评分时，不把整场评测误存成 0 分。
                storedAccuracy = null;
            }

            String scoreStatus = resolveEvaluationScoreStatus(eval, results, hasScorableResults);
            String scoreDisplay = resolveScoreDisplay(scoreStatus);

            String targetsJson = toJson(eval.get("targets"));
            String datasetIdsJson = toJson(eval.get("datasetIds"));
            String datasetNamesJson = toJson(eval.get("datasetNames"));
            String datasetMappingJson = toJson(eval.get("datasetMapping"));
            String resultsJson = toJson(results);
            String modalitiesJson = toJson(modalities);

            jdbcTemplate.update(
                    "INSERT INTO eval_record (" +
                            "id, name, type, status, start_time, end_time, accuracy, remark, " +
                            "judge_mode, judge_model_id, evaluation_type, report_type, has_multimodal, " +
                            "dataset_type, modalities, score_status, score_display, " +
                            "sub_eval_id, sub_eval_version, " +
                            "targets, dataset_ids, dataset_names, dataset_mapping, results" +
                            ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                            "ON DUPLICATE KEY UPDATE " +
                            "name=VALUES(name), type=VALUES(type), status=VALUES(status), " +
                            "start_time=VALUES(start_time), end_time=VALUES(end_time), accuracy=VALUES(accuracy), " +
                            "remark=VALUES(remark), judge_mode=VALUES(judge_mode), judge_model_id=VALUES(judge_model_id), " +
                            "evaluation_type=VALUES(evaluation_type), report_type=VALUES(report_type), " +
                            "has_multimodal=VALUES(has_multimodal), dataset_type=VALUES(dataset_type), " +
                            "modalities=VALUES(modalities), score_status=VALUES(score_status), " +
                            "score_display=VALUES(score_display), sub_eval_id=VALUES(sub_eval_id), " +
                            "sub_eval_version=VALUES(sub_eval_version), targets=VALUES(targets), " +
                            "dataset_ids=VALUES(dataset_ids), dataset_names=VALUES(dataset_names), " +
                            "dataset_mapping=VALUES(dataset_mapping), results=VALUES(results)",
                    id,
                    name,
                    type,
                    status,
                    startTime,
                    endTime,
                    storedAccuracy,
                    remark,
                    judgeMode,
                    judgeModelId,
                    evaluationType,
                    reportType,
                    hasMultimodal ? 1 : 0,
                    datasetType,
                    modalitiesJson,
                    scoreStatus,
                    scoreDisplay,
                    subEvalId,
                    subEvalVersion,
                    targetsJson,
                    datasetIdsJson,
                    datasetNamesJson,
                    datasetMappingJson,
                    resultsJson
            );
        } catch (Exception e) {
            log.error("保存评测记录失败: id={}", id, e);
            throw new RuntimeException("保存评测记录失败", e);
        }
    }

    public void remove(String id) {
        jdbcTemplate.update("DELETE FROM eval_record WHERE id = ?", id);
    }

    /**
     * 获取所有评测记录（不含 results 字段，减少数据量）。
     */
    public List<Map<String, Object>> getAll() {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT id, name, type, status, start_time, end_time, accuracy, remark, " +
                            "judge_mode, judge_model_id, evaluation_type, report_type, has_multimodal, " +
                            "dataset_type, modalities, score_status, score_display, " +
                            "sub_eval_id, sub_eval_version, targets, dataset_ids, dataset_names, dataset_mapping " +
                            "FROM eval_record ORDER BY created_at DESC");
            List<Map<String, Object>> result = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                result.add(rowToMap(row));
            }
            return result;
        } catch (Exception e) {
            log.error("查询评测记录列表失败", e);
            return new ArrayList<>();
        }
    }

    /**
     * 按子评测 id 查询该子评测下所有版本的评测记录（不含 results 字段），按版本号倒序，
     * 用于“模型测评”子评测详情页展示历史版本列表。
     */
    public List<Map<String, Object>> getBySubEvalId(String subEvalId) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT id, name, type, status, start_time, end_time, accuracy, remark, " +
                            "judge_mode, judge_model_id, evaluation_type, report_type, has_multimodal, " +
                            "dataset_type, modalities, score_status, score_display, " +
                            "sub_eval_id, sub_eval_version, targets, dataset_ids, dataset_names, dataset_mapping " +
                            "FROM eval_record WHERE sub_eval_id = ? ORDER BY sub_eval_version DESC",
                    subEvalId);
            List<Map<String, Object>> result = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                result.add(rowToMap(row));
            }
            return result;
        } catch (Exception e) {
            log.error("查询子评测版本记录列表失败: subEvalId={}", subEvalId, e);
            return new ArrayList<>();
        }
    }

    // ----- 内部方法 -----

    private void addColumnIfMissing(String columnName, String columnDefinition) {
        try {
            jdbcTemplate.execute("ALTER TABLE eval_record ADD COLUMN " + columnName + " " + columnDefinition);
            log.info("eval_record 表新增字段成功: {}", columnName);
        } catch (Exception e) {
            log.debug("字段已存在或添加失败: {}，{}", columnName, e.getMessage());
        }
    }

    private void addIndexIfMissing(String indexName, String columnName) {
        try {
            jdbcTemplate.execute("ALTER TABLE eval_record ADD INDEX " + indexName + " (" + columnName + ")");
            log.info("eval_record 表新增索引成功: {}", indexName);
        } catch (Exception e) {
            log.debug("索引已存在或添加失败: {}，{}", indexName, e.getMessage());
        }
    }

    private void normalizeExistingEvaluationMetadata() {
        try {
            jdbcTemplate.update("UPDATE eval_record SET evaluation_type = 'text' " +
                    "WHERE evaluation_type IS NULL OR evaluation_type = ''");
        } catch (Exception e) {
            log.debug("兼容旧评测记录 evaluation_type 失败: {}", e.getMessage());
        }

        try {
            jdbcTemplate.update("UPDATE eval_record SET report_type = 'text' " +
                    "WHERE report_type IS NULL OR report_type = ''");
        } catch (Exception e) {
            log.debug("兼容旧评测记录 report_type 失败: {}", e.getMessage());
        }

        try {
            jdbcTemplate.update("UPDATE eval_record SET has_multimodal = 0 WHERE has_multimodal IS NULL");
        } catch (Exception e) {
            log.debug("兼容旧评测记录 has_multimodal 失败: {}", e.getMessage());
        }

        try {
            jdbcTemplate.update("UPDATE eval_record SET dataset_type = evaluation_type " +
                    "WHERE dataset_type IS NULL OR dataset_type = ''");
        } catch (Exception e) {
            log.debug("兼容旧评测记录 dataset_type 失败: {}", e.getMessage());
        }
    }

    private Map<String, Object> rowToMap(Map<String, Object> row) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", row.get("id"));
        map.put("name", row.get("name"));
        map.put("type", row.get("type"));
        map.put("status", row.get("status"));
        map.put("startTime", row.get("start_time"));
        map.put("endTime", row.get("end_time"));
        map.put("remark", row.get("remark"));
        map.put("judgeMode", row.get("judge_mode"));
        map.put("judgeModelId", row.get("judge_model_id"));
        map.put("subEvalId", row.get("sub_eval_id"));
        map.put("subEvalVersion", row.get("sub_eval_version"));

        String evaluationType = normalizeEvaluationType(
                row.get("evaluation_type"), EVALUATION_TYPE_TEXT);
        String datasetType = normalizeDatasetType(row.get("dataset_type"), evaluationType);
        boolean hasMultimodal = asBoolean(row.get("has_multimodal"),
                EVALUATION_TYPE_MULTIMODAL.equals(evaluationType));

        String reportType = normalizeReportType(
                row.get("report_type"),
                EVALUATION_TYPE_MULTIMODAL.equals(evaluationType)
                        ? REPORT_TYPE_MULTIMODAL : REPORT_TYPE_TEXT);

        List<String> modalities = normalizeModalities(row.get("modalities"));

        List<Map<String, Object>> results = null;
        if (row.containsKey("results")) {
            results = fromJson(row.get("results"), new TypeReference<List<Map<String, Object>>>() {});
            results = normalizeResultsForReport(results, evaluationType, datasetType, modalities);
        }

        if ((modalities == null || modalities.isEmpty()) && results != null) {
            modalities = inferModalitiesFromResults(results);
        }
        if (modalities == null || modalities.isEmpty()) {
            modalities = Collections.singletonList("text");
        }

        if (EVALUATION_TYPE_MULTIMODAL.equals(evaluationType)
                || EVALUATION_TYPE_MULTIMODAL.equals(datasetType)
                || REPORT_TYPE_MULTIMODAL.equals(reportType)
                || containsNonTextModality(modalities)) {
            hasMultimodal = true;
            evaluationType = EVALUATION_TYPE_MULTIMODAL;
            reportType = REPORT_TYPE_MULTIMODAL;
            datasetType = EVALUATION_TYPE_MULTIMODAL;
        }

        map.put("evaluationType", evaluationType);
        map.put("reportType", reportType);
        map.put("datasetType", datasetType);
        map.put("hasMultimodal", hasMultimodal);
        map.put("modalities", modalities);

        if (row.containsKey("accuracy")) {
            map.put("accuracy", toDoubleOrNull(row.get("accuracy")));
        }

        String scoreStatus = normalizeScoreStatus(row.get("score_status"));
        if (isBlank(scoreStatus)) {
            scoreStatus = resolveEvaluationScoreStatus(null, results, hasScorableResults(results));
        }
        String scoreDisplay = firstNonBlank(asString(row.get("score_display")), resolveScoreDisplay(scoreStatus));

        map.put("scoreStatus", scoreStatus);
        map.put("scoreDisplay", scoreDisplay);
        map.put("manualReviewRequired", SCORE_STATUS_MANUAL_REVIEW.equals(scoreStatus));
        map.put("autoScoreAvailable", hasScorableResults(results));

        map.put("targets", fromJson(row.get("targets"), new TypeReference<List<String>>() {}));
        map.put("datasetIds", fromJson(row.get("dataset_ids"), new TypeReference<List<String>>() {}));
        map.put("datasetNames", fromJson(row.get("dataset_names"), new TypeReference<List<String>>() {}));
        map.put("datasetMapping", fromJson(row.get("dataset_mapping"),
                new TypeReference<Map<String, Object>>() {}));

        if (row.containsKey("results")) {
            map.put("results", results);
        }

        return map;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> normalizeResultsForReport(Object resultsObj,
                                                                 String evaluationType,
                                                                 String datasetType,
                                                                 List<String> evalModalities) {
        if (resultsObj == null) {
            return null;
        }
        if (!(resultsObj instanceof Collection<?> results)) {
            return null;
        }

        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Object item : results) {
            Map<String, Object> source = asMap(item);
            if (source == null) {
                continue;
            }

            Map<String, Object> result = new LinkedHashMap<>(source);
            List<String> questionImages = extractResourceList(result,
                    "questionImages", "question_images", "images", "image");
            List<String> questionFiles = extractResourceList(result,
                    "questionFiles", "question_files", "files", "file", "attachments");
            List<String> resultModalities = mergeModalities(
                    evalModalities,
                    normalizeModalities(result.get("modalities")),
                    inferModalities(questionImages, questionFiles));

            if (questionImages != null && !questionImages.isEmpty()) {
                result.put("questionImages", questionImages);
                result.put("question_images", questionImages);
                result.put("images", questionImages);
            }
            if (questionFiles != null && !questionFiles.isEmpty()) {
                result.put("questionFiles", questionFiles);
                result.put("question_files", questionFiles);
                result.put("files", questionFiles);
            }

            String resolvedEvaluationType = normalizeEvaluationType(
                    firstPresent(result, "evaluationType", "evaluation_type"),
                    evaluationType);
            String resolvedDatasetType = normalizeDatasetType(
                    firstPresent(result, "datasetType", "dataset_type"),
                    datasetType);
            boolean resultHasMultimodal = asBoolean(
                    firstPresent(result, "hasMultimodal", "has_multimodal"),
                    EVALUATION_TYPE_MULTIMODAL.equals(resolvedEvaluationType)
                            || EVALUATION_TYPE_MULTIMODAL.equals(resolvedDatasetType)
                            || containsNonTextModality(resultModalities));

            if (resultHasMultimodal || containsNonTextModality(resultModalities)) {
                resolvedEvaluationType = EVALUATION_TYPE_MULTIMODAL;
                resolvedDatasetType = EVALUATION_TYPE_MULTIMODAL;
                resultHasMultimodal = true;
            }

            result.put("evaluationType", resolvedEvaluationType);
            result.put("reportType",
                    EVALUATION_TYPE_MULTIMODAL.equals(resolvedEvaluationType)
                            ? REPORT_TYPE_MULTIMODAL : REPORT_TYPE_TEXT);
            result.put("datasetType", resolvedDatasetType);
            result.put("hasMultimodal", resultHasMultimodal);
            result.put("modalities", resultModalities);

            String expectedAnswer = extractExpectedAnswerFromResult(result);
            String existingExpectedType = asString(result.get("expectedType"));
            // 对于 PPT/HTML 标准答案（expected_type="pptx"/"html"），
            // 没有文本内容时，必须额外通过 PptTypeUtils/HtmlTypeUtils 判断，
            // 否则会被误判为“无参考答案”。
            boolean hasExpectedAnswer = hasMeaningfulText(expectedAnswer)
                    || PptTypeUtils.isPptExpectedType(existingExpectedType)
                    || HtmlTypeUtils.isHtmlExpectedType(existingExpectedType);
            result.put("hasExpected", hasExpectedAnswer);
            result.put("hasExpectedAnswer", hasExpectedAnswer);
            result.put("expectedType", hasExpectedAnswer
                    ? firstNonBlank(existingExpectedType, "text") : "none");

            Object scoresObj = result.get("scores");
            Map<String, Object> normalizedScores = normalizeScoresForReport(scoresObj, hasExpectedAnswer);
            if (normalizedScores != null) {
                result.put("scores", normalizedScores);
            }

            String resultScoreStatus = resolveResultScoreStatus(result, normalizedScores, hasExpectedAnswer);
            result.put("scoreStatus", resultScoreStatus);
            result.put("scoreDisplay", resolveScoreDisplay(resultScoreStatus));
            result.put("manualReviewRequired", SCORE_STATUS_MANUAL_REVIEW.equals(resultScoreStatus));
            result.put("autoScoreAvailable", hasScorableScores(normalizedScores));
            result.put("judgeMode", hasExpectedAnswer
                    ? firstNonBlank(asString(result.get("judgeMode")), "auto") : "manual");

            normalized.add(result);
        }
        return normalized;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeScoresForReport(Object scoresObj, boolean hasExpectedAnswer) {
        Map<String, Object> scores = asMap(scoresObj);
        if (scores == null) {
            return null;
        }

        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : scores.entrySet()) {
            Map<String, Object> score = asMap(entry.getValue());
            if (score == null) {
                normalized.put(entry.getKey(), entry.getValue());
                continue;
            }

            Map<String, Object> copy = new LinkedHashMap<>(score);
            boolean hasManualFinal = hasManualScore(copy);
            if (!hasExpectedAnswer) {
                if (hasManualFinal) {
                    copy.putIfAbsent("scoreStatus", SCORE_STATUS_MANUAL_SCORED);
                    copy.putIfAbsent("scoreDisplay", SCORE_DISPLAY_MANUAL_SCORED);
                    copy.putIfAbsent("manualReviewRequired", false);
                } else {
                    copy.put("auto", null);
                    copy.put("final", null);
                    copy.put("scoreStatus", SCORE_STATUS_MANUAL_REVIEW);
                    copy.put("scoreDisplay", SCORE_DISPLAY_MANUAL_REVIEW);
                    copy.put("manualReviewRequired", true);
                }
                copy.put("autoScoreAvailable", false);
                normalized.put(entry.getKey(), copy);
                continue;
            }

            if (copy.get("final") instanceof Number) {
                copy.putIfAbsent("scoreStatus", SCORE_STATUS_AUTO_SCORED);
                copy.putIfAbsent("scoreDisplay", SCORE_DISPLAY_AUTO_SCORED);
                copy.putIfAbsent("manualReviewRequired", false);
                copy.putIfAbsent("autoScoreAvailable", true);
            } else {
                copy.putIfAbsent("scoreStatus", SCORE_STATUS_PENDING);
                copy.putIfAbsent("scoreDisplay", SCORE_DISPLAY_PENDING);
                copy.putIfAbsent("manualReviewRequired", false);
                copy.putIfAbsent("autoScoreAvailable", false);
            }

            normalized.put(entry.getKey(), copy);
        }
        return normalized;
    }

    private String resolveResultScoreStatus(Map<String, Object> result,
                                            Map<String, Object> scores,
                                            boolean hasExpectedAnswer) {
        String explicit = normalizeScoreStatus(
                result == null ? null : firstPresent(result, "scoreStatus", "score_status"));
        if (!isBlank(explicit)) {
            return explicit;
        }

        if (!hasExpectedAnswer) {
            return hasManualScores(scores)
                    ? SCORE_STATUS_MANUAL_SCORED : SCORE_STATUS_MANUAL_REVIEW;
        }
        if (hasScorableScores(scores)) {
            return SCORE_STATUS_AUTO_SCORED;
        }
        if (hasManualScores(scores)) {
            return SCORE_STATUS_MANUAL_SCORED;
        }
        return SCORE_STATUS_PENDING;
    }

    private String resolveEvaluationScoreStatus(Map<String, Object> eval,
                                                List<Map<String, Object>> results,
                                                boolean hasScorableResults) {
        String explicit = eval == null ? null
                : normalizeScoreStatus(firstPresent(eval, "scoreStatus", "score_status"));
        if (!isBlank(explicit)) {
            return explicit;
        }
        if (results == null || results.isEmpty()) {
            return null;
        }
        if (hasScorableResults) {
            return SCORE_STATUS_AUTO_SCORED;
        }
        if (hasManualReviewResults(results)) {
            return SCORE_STATUS_MANUAL_REVIEW;
        }
        return SCORE_STATUS_PENDING;
    }

    private String resolveScoreDisplay(String scoreStatus) {
        if (SCORE_STATUS_AUTO_SCORED.equals(scoreStatus)) {
            return SCORE_DISPLAY_AUTO_SCORED;
        }
        if (SCORE_STATUS_MANUAL_REVIEW.equals(scoreStatus)) {
            return SCORE_DISPLAY_MANUAL_REVIEW;
        }
        if (SCORE_STATUS_MANUAL_SCORED.equals(scoreStatus)) {
            return SCORE_DISPLAY_MANUAL_SCORED;
        }
        if (SCORE_STATUS_PENDING.equals(scoreStatus)) {
            return SCORE_DISPLAY_PENDING;
        }
        return null;
    }

    private boolean hasScorableResults(List<Map<String, Object>> results) {
        if (results == null || results.isEmpty()) {
            return false;
        }
        for (Map<String, Object> result : results) {
            if (hasScorableScores(asMap(result.get("scores")))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasManualReviewResults(List<Map<String, Object>> results) {
        if (results == null || results.isEmpty()) {
            return false;
        }
        for (Map<String, Object> result : results) {
            String scoreStatus = normalizeScoreStatus(
                    firstPresent(result, "scoreStatus", "score_status"));
            if (SCORE_STATUS_MANUAL_REVIEW.equals(scoreStatus)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasScorableScores(Map<String, Object> scores) {
        if (scores == null || scores.isEmpty()) {
            return false;
        }
        for (Object value : scores.values()) {
            Map<String, Object> score = asMap(value);
            if (score != null && score.get("final") instanceof Number) {
                return true;
            }
        }
        return false;
    }

    private boolean hasManualScores(Map<String, Object> scores) {
        if (scores == null || scores.isEmpty()) {
            return false;
        }
        for (Object value : scores.values()) {
            Map<String, Object> score = asMap(value);
            if (score != null && hasManualScore(score)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasManualScore(Map<String, Object> score) {
        if (score == null) {
            return false;
        }
        return score.get("manual") != null && score.get("final") instanceof Number;
    }

    private List<String> inferModalitiesFromResults(List<Map<String, Object>> results) {
        LinkedHashSet<String> modalities = new LinkedHashSet<>();
        modalities.add("text");
        if (results == null) {
            return new ArrayList<>(modalities);
        }

        for (Map<String, Object> result : results) {
            if (result == null) {
                continue;
            }
            addModalities(modalities, result.get("modalities"));
            List<String> images = extractResourceList(result,
                    "questionImages", "question_images", "images", "image");
            List<String> files = extractResourceList(result,
                    "questionFiles", "question_files", "files", "file", "attachments");
            if (images != null && !images.isEmpty()) {
                modalities.add("image");
            }
            if (files != null && !files.isEmpty()) {
                modalities.add("file");
            }
        }
        return new ArrayList<>(modalities);
    }

    private List<String> inferModalities(List<String> images, List<String> files) {
        LinkedHashSet<String> modalities = new LinkedHashSet<>();
        modalities.add("text");
        if (images != null && !images.isEmpty()) {
            modalities.add("image");
        }
        if (files != null && !files.isEmpty()) {
            modalities.add("file");
        }
        return new ArrayList<>(modalities);
    }

    @SafeVarargs
    private final List<String> mergeModalities(List<String>... values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        result.add("text");
        if (values != null) {
            for (List<String> list : values) {
                if (list == null) {
                    continue;
                }
                for (String value : list) {
                    addOneModality(result, value);
                }
            }
        }
        return new ArrayList<>(result);
    }

    private List<String> normalizeModalities(Object value) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        addModalities(result, value);
        if (result.isEmpty()) {
            return new ArrayList<>();
        }
        if (result.contains("text")) {
            LinkedHashSet<String> withText = new LinkedHashSet<>();
            withText.add("text");
            withText.addAll(result);
            result = withText;
        }
        return new ArrayList<>(result);
    }

    private void addModalities(Set<String> result, Object value) {
        if (result == null || value == null) {
            return;
        }
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                addModalities(result, item);
            }
            return;
        }
        if (value instanceof Map<?, ?>) {
            Map<String, Object> map = asMap(value);
            addModalities(result, firstPresent(map, "modalities", "modality", "type"));
            return;
        }

        String text = value.toString().trim();
        if (text.isEmpty()) {
            return;
        }

        if (text.startsWith("[") && text.endsWith("]")) {
            try {
                List<Object> parsed = objectMapper.readValue(text, new TypeReference<List<Object>>() {});
                addModalities(result, parsed);
                return;
            } catch (Exception ignored) {
                // 按普通字符串继续处理。
                text = text.substring(1, text.length() - 1);
            }
        }

        for (String part : text.split("[,，;；|]")) {
            addOneModality(result, part);
        }
    }

    private void addOneModality(Set<String> result, String value) {
        if (result == null || value == null) {
            return;
        }
        String normalized = value.replace("\"", "")
                .replace("'", "")
                .trim()
                .toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return;
        }

        if (normalized.contains("image")
                || normalized.contains("img")
                || normalized.contains("picture")) {
            result.add("image");
        } else if (normalized.contains("file")
                || normalized.contains("document")
                || normalized.contains("doc")
                || normalized.contains("pdf")
                || normalized.contains("xls")
                || normalized.contains("xlsx")
                || normalized.contains("csv")
                || normalized.contains("zip")) {
            result.add("file");
        } else if (normalized.contains("text")) {
            result.add("text");
        } else {
            result.add(normalized);
        }
    }

    private boolean containsNonTextModality(List<String> modalities) {
        if (modalities == null) {
            return false;
        }
        for (String modality : modalities) {
            if (modality != null && !"text".equalsIgnoreCase(modality)) {
                return true;
            }
        }
        return false;
    }

    private List<String> extractResourceList(Map<String, Object> map, String... keys) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (map == null || keys == null) {
            return new ArrayList<>();
        }
        for (String key : keys) {
            addStringValues(result, map.get(key));
        }
        return new ArrayList<>(result);
    }

    private void addStringValues(Set<String> result, Object value) {
        if (result == null || value == null) {
            return;
        }
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                addStringValues(result, item);
            }
            return;
        }
        if (value instanceof Map<?, ?>) {
            Map<String, Object> map = asMap(value);
            addStringValues(result, firstPresent(map,
                    "value", "url", "uri", "href", "path", "file", "data",
                    "image_url", "imageUrl", "file_url", "fileUrl"));
            return;
        }

        String text = value.toString().trim();
        if (text.isEmpty()) {
            return;
        }
        if (text.startsWith("[") && text.endsWith("]")) {
            try {
                List<Object> parsed = objectMapper.readValue(text, new TypeReference<List<Object>>() {});
                addStringValues(result, parsed);
                return;
            } catch (Exception ignored) {
                // 按普通字符串处理。
            }
        }
        result.add(text);
    }

    private String extractExpectedAnswerFromResult(Map<String, Object> result) {
        if (result == null) {
            return null;
        }
        return firstNonBlank(
                asString(result.get("expectedAnswer")),
                asString(result.get("expected_answer")),
                asString(result.get("expectedResult")),
                asString(result.get("expected_result")),
                asString(result.get("reference")),
                asString(result.get("referenceAnswer")),
                extractExpectedText(result.get("expected")),
                extractExpectedText(result.get("output")),
                extractExpectedText(result.get("target")));
    }

    private String extractExpectedText(Object expectedObj) {
        if (expectedObj == null) {
            return null;
        }
        if (expectedObj instanceof String || expectedObj instanceof Number || expectedObj instanceof Boolean) {
            return expectedObj.toString();
        }

        Map<String, Object> map = asMap(expectedObj);
        if (map == null) {
            return null;
        }
        return firstNonBlank(
                asString(map.get("text")),
                asString(map.get("value")),
                asString(map.get("answer")),
                asString(map.get("expectedAnswer")),
                asString(map.get("expected_answer")));
    }

    private Object firstPresent(Map<String, Object> map, String... keys) {
        if (map == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (map.containsKey(key)) {
                return map.get(key);
            }
        }
        return null;
    }

    private String normalizeEvaluationType(Object value, String defaultValue) {
        String text = asString(value);
        if (text == null || text.trim().isEmpty()) {
            return defaultValue;
        }

        text = text.trim().toLowerCase(Locale.ROOT);
        if (EVALUATION_TYPE_MULTIMODAL.equals(text)) {
            return EVALUATION_TYPE_MULTIMODAL;
        }
        if (EVALUATION_TYPE_TEXT.equals(text)) {
            return EVALUATION_TYPE_TEXT;
        }
        return defaultValue;
    }

    private String normalizeReportType(Object value, String defaultValue) {
        String text = asString(value);
        if (text == null || text.trim().isEmpty()) {
            return defaultValue;
        }

        text = text.trim().toLowerCase(Locale.ROOT);
        if (REPORT_TYPE_MULTIMODAL.equals(text)) {
            return REPORT_TYPE_MULTIMODAL;
        }
        if (REPORT_TYPE_TEXT.equals(text)) {
            return REPORT_TYPE_TEXT;
        }
        return defaultValue;
    }

    private String normalizeDatasetType(Object value, String defaultValue) {
        String text = asString(value);
        if (text == null || text.trim().isEmpty()) {
            return defaultValue;
        }

        text = text.trim().toLowerCase(Locale.ROOT);
        if (EVALUATION_TYPE_MULTIMODAL.equals(text)) {
            return EVALUATION_TYPE_MULTIMODAL;
        }
        if (EVALUATION_TYPE_TEXT.equals(text)
                || "text_qa".equals(text)
                || "legacy".equals(text)) {
            return EVALUATION_TYPE_TEXT;
        }
        return defaultValue;
    }

    private String normalizeScoreStatus(Object value) {
        String text = asString(value);
        if (text == null || text.trim().isEmpty()) {
            return null;
        }

        String normalized = text.trim()
                .toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
        if (SCORE_STATUS_AUTO_SCORED.equals(normalized)) {
            return SCORE_STATUS_AUTO_SCORED;
        }
        if (SCORE_STATUS_MANUAL_REVIEW.equals(normalized)
                || "manual".equals(normalized)
                || "need_manual".equals(normalized)
                || "needs_manual".equals(normalized)) {
            return SCORE_STATUS_MANUAL_REVIEW;
        }
        if (SCORE_STATUS_MANUAL_SCORED.equals(normalized)) {
            return SCORE_STATUS_MANUAL_SCORED;
        }
        if (SCORE_STATUS_PENDING.equals(normalized)) {
            return SCORE_STATUS_PENDING;
        }
        return normalized;
    }

    private boolean asBoolean(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }

        String text = value.toString().trim().toLowerCase(Locale.ROOT);
        if ("true".equals(text) || "1".equals(text) || "yes".equals(text) || "y".equals(text)) {
            return true;
        }
        if ("false".equals(text) || "0".equals(text) || "no".equals(text) || "n".equals(text)) {
            return false;
        }
        return defaultValue;
    }

    private String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private boolean hasMeaningfulText(String value) {
        if (value == null) {
            return false;
        }

        String text = value.trim();
        return !text.isEmpty()
                && !"null".equalsIgnoreCase(text)
                && !"undefined".equalsIgnoreCase(text)
                && !"无".equals(text);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (hasMeaningfulText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private Double toDoubleOrNull(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private Integer toIntegerOrNull(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?>)) {
            return null;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        Map<?, ?> map = (Map<?, ?>) value;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                result.put(entry.getKey().toString(), entry.getValue());
            }
        }
        return result;
    }

 
 
    private String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("JSON序列化失败", e);
            return null;
        }
    }

    private <T> T fromJson(Object jsonObj, TypeReference<T> typeRef) {
        if (jsonObj == null) return null;
        try {
            String json = jsonObj.toString();
            if (json.trim().isEmpty()) {
                return null;
            }
            return objectMapper.readValue(json, typeRef);
        } catch (Exception e) {
            log.warn("JSON反序列化失败", e);
            return null;
        }
    }
}
