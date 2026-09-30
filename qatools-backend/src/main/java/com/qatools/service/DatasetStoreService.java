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
 * 数据集存储服务 - 基于 MySQL 持久化
 */
@Service
public class DatasetStoreService {

    private static final Logger log = LoggerFactory.getLogger(DatasetStoreService.class);

    private static final String DEFAULT_DATASET_TYPE = "text";
    private static final String DEFAULT_SCHEMA_VERSION = "v1";
    private static final String DEFAULT_SOURCE_FORMAT = "legacy";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicInteger idCounter = new AtomicInteger(1);

    @PostConstruct
    public void init() {
        ensureDatasetMetadataColumns();
        normalizeExistingDatasetMetadata();

        try {
            Integer max = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(MAX(CAST(SUBSTRING_INDEX(id, '-', -1) AS UNSIGNED)), 0) FROM dataset",
                    Integer.class);
            if (max != null) {
                idCounter.set(max + 1);
            }
        } catch (Exception e) {
            log.warn("初始化数据集ID计数器失败，使用默认值: {}", e.getMessage());
        }
    }

    public String nextId() {
        return "ds-" + String.format("%03d", idCounter.getAndIncrement());
    }

    public Map<String, Object> get(String id) {
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM dataset WHERE id = ?", id);
            return rowToMap(row);
        } catch (Exception e) {
            return null;
        }
    }

    public boolean existsByName(String name, String excludeId) {
        try {
            String sql = excludeId != null
                    ? "SELECT COUNT(*) FROM dataset WHERE name = ? AND id != ?"
                    : "SELECT COUNT(*) FROM dataset WHERE name = ?";
            Integer count = excludeId != null
                    ? jdbcTemplate.queryForObject(sql, Integer.class, name, excludeId)
                    : jdbcTemplate.queryForObject(sql, Integer.class, name);
            return count != null && count > 0;
        } catch (Exception e) {
            return false;
        }
    }

    public void put(String id, Map<String, Object> dataset) {
        try {
            String name = asString(dataset.get("name"), null);
            String description = asString(dataset.get("description"), null);
            String fileName = asString(dataset.get("fileName"), null);
            String filePath = asString(dataset.get("filePath"), null);
            Integer itemCount = dataset.get("itemCount") != null
                    ? ((Number) dataset.get("itemCount")).intValue()
                    : 0;

            boolean hasExpectedResult = asBoolean(firstNonNull(dataset, "hasExpectedResult", "has_expected_result"), false);

            String datasetType = asString(firstNonNull(dataset, "datasetType", "dataset_type"), DEFAULT_DATASET_TYPE);
            if (datasetType == null || datasetType.trim().isEmpty()) {
                datasetType = DEFAULT_DATASET_TYPE;
            }
            datasetType = datasetType.trim().toLowerCase(Locale.ROOT);

            boolean hasMultimodal = asBoolean(
                    firstNonNull(dataset, "hasMultimodal", "has_multimodal"),
                    "multimodal".equalsIgnoreCase(datasetType)
            );

            List<String> modalities = normalizeModalities(dataset.get("modalities"));
            if (modalities.isEmpty()) {
                modalities.add("text");
            }

            String schemaVersion = asString(firstNonNull(dataset, "schemaVersion", "schema_version"), DEFAULT_SCHEMA_VERSION);
            if (schemaVersion == null || schemaVersion.trim().isEmpty()) {
                schemaVersion = DEFAULT_SCHEMA_VERSION;
            }

            String sourceFormat = asString(firstNonNull(dataset, "sourceFormat", "source_format"), DEFAULT_SOURCE_FORMAT);
            if (sourceFormat == null || sourceFormat.trim().isEmpty()) {
                sourceFormat = DEFAULT_SOURCE_FORMAT;
            }

            String itemsJson = toJson(dataset.get("items"));
            String modalitiesJson = toJson(modalities);
            String createdAt = asString(dataset.get("createdAt"), null);
            String updatedAt = asString(dataset.get("updatedAt"), null);
            boolean starred = asBoolean(dataset.get("starred"), false);
            boolean hasPptExpected = asBoolean(firstNonNull(dataset, "hasPptExpected", "has_ppt_expected"), false);
            boolean hasHtmlExpected = asBoolean(firstNonNull(dataset, "hasHtmlExpected", "has_html_expected"), false);

            jdbcTemplate.update(
                    "INSERT INTO dataset (" +
                            "id, name, description, file_name, file_path, item_count, has_expected_result, " +
                            "items, dataset_type, has_multimodal, modalities, schema_version, source_format, starred, " +
                            "has_ppt_expected, has_html_expected, created_at, updated_at" +
                            ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                            "ON DUPLICATE KEY UPDATE " +
                            "name=VALUES(name), description=VALUES(description), " +
                            "file_name=VALUES(file_name), file_path=VALUES(file_path), item_count=VALUES(item_count), " +
                            "has_expected_result=VALUES(has_expected_result), items=VALUES(items), " +
                            "dataset_type=VALUES(dataset_type), has_multimodal=VALUES(has_multimodal), " +
                            "modalities=VALUES(modalities), schema_version=VALUES(schema_version), source_format=VALUES(source_format), " +
                            "starred=VALUES(starred), has_ppt_expected=VALUES(has_ppt_expected), has_html_expected=VALUES(has_html_expected), " +
                            "updated_at=VALUES(updated_at)",
                    id,
                    name,
                    description,
                    fileName,
                    filePath,
                    itemCount,
                    hasExpectedResult ? 1 : 0,
                    itemsJson,
                    datasetType,
                    hasMultimodal ? 1 : 0,
                    modalitiesJson,
                    schemaVersion,
                    sourceFormat,
                    starred ? 1 : 0,
                    hasPptExpected ? 1 : 0,
                    hasHtmlExpected ? 1 : 0,
                    createdAt,
                    updatedAt
            );
        } catch (Exception e) {
            log.error("保存数据集失败: id={}", id, e);
            throw new RuntimeException("保存数据集失败", e);
        }
    }

    public void remove(String id) {
        jdbcTemplate.update("DELETE FROM dataset WHERE id = ?", id);
    }

    /**
     * 获取所有数据集（不含 items 字段，减少数据量）
     */
    public List<Map<String, Object>> getAll() {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT id, name, description, file_name, file_path, item_count, has_expected_result, " +
                            "dataset_type, has_multimodal, modalities, schema_version, source_format, starred, " +
                            "has_ppt_expected, has_html_expected, created_at, updated_at " +
                            "FROM dataset ORDER BY starred DESC, created_at DESC"
            );
            List<Map<String, Object>> result = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                result.add(rowToMap(row));
            }
            return result;
        } catch (Exception e) {
            log.error("查询数据集列表失败", e);
            return new ArrayList<>();
        }
    }

    private void ensureDatasetMetadataColumns() {
        addColumnIfMissing("dataset_type", "VARCHAR(32) NOT NULL DEFAULT 'text'");
        addColumnIfMissing("has_multimodal", "TINYINT(1) NOT NULL DEFAULT 0");
        addColumnIfMissing("modalities", "TEXT NULL");
        addColumnIfMissing("schema_version", "VARCHAR(32) NOT NULL DEFAULT 'v1'");
        addColumnIfMissing("source_format", "VARCHAR(32) NOT NULL DEFAULT 'legacy'");
        addColumnIfMissing("starred", "TINYINT(1) NOT NULL DEFAULT 0");
        addColumnIfMissing("has_ppt_expected", "TINYINT(1) NOT NULL DEFAULT 0");
        addColumnIfMissing("has_html_expected", "TINYINT(1) NOT NULL DEFAULT 0");
    }

    private void addColumnIfMissing(String columnName, String columnDefinition) {
        try {
            if (!columnExists(columnName)) {
                jdbcTemplate.execute("ALTER TABLE dataset ADD COLUMN " + columnName + " " + columnDefinition);
                log.info("dataset 表新增字段成功: {}", columnName);
            }
        } catch (Exception e) {
            log.warn("确保 dataset.{} 字段存在失败，可能字段已存在或当前数据库账号无 ALTER 权限: {}",
                    columnName, e.getMessage());
        }
    }

    private boolean columnExists(String columnName) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dataset' AND COLUMN_NAME = ?",
                    Integer.class,
                    columnName
            );
            return count != null && count > 0;
        } catch (Exception e) {
            log.warn("检查 dataset.{} 字段是否存在失败: {}", columnName, e.getMessage());
            return false;
        }
    }

    private void normalizeExistingDatasetMetadata() {
        try {
            if (columnExists("dataset_type")) {
                jdbcTemplate.update("UPDATE dataset SET dataset_type = 'text' WHERE dataset_type IS NULL OR dataset_type = ''");
            }
            if (columnExists("has_multimodal")) {
                jdbcTemplate.update("UPDATE dataset SET has_multimodal = 0 WHERE has_multimodal IS NULL");
            }
            if (columnExists("modalities")) {
                jdbcTemplate.update("UPDATE dataset SET modalities = '[\"text\"]' WHERE modalities IS NULL OR modalities = ''");
            }
            if (columnExists("schema_version")) {
                jdbcTemplate.update("UPDATE dataset SET schema_version = 'v1' WHERE schema_version IS NULL OR schema_version = ''");
            }
            if (columnExists("source_format")) {
                jdbcTemplate.update("UPDATE dataset SET source_format = 'legacy' WHERE source_format IS NULL OR source_format = ''");
            }
            backfillExpectedTypeFlags();
        } catch (Exception e) {
            log.warn("兼容历史数据集元信息失败: {}", e.getMessage());
        }
    }

    /**
     * has_ppt_expected/has_html_expected 是本次新增列，历史数据集从未写入过（此前 DatasetController 计算出这两个
     * 字段后从未持久化，只在单次请求响应体里瞬时存在）。仅靠 addColumnIfMissing 默认值 0 无法反映历史数据集的真实类型，
     * 必须解析已有 items JSON 重新探测一次并回填，否则列表页“类型”列会持续把 PPT/HTML 对比数据集误判为纯文本/多模态，
     * 直到用户下次编辑保存该数据集为止。
     */
    private void backfillExpectedTypeFlags() {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT id, items FROM dataset WHERE has_ppt_expected = 0 AND has_html_expected = 0");
            for (Map<String, Object> row : rows) {
                String id = (String) row.get("id");
                List<Map<String, Object>> items = fromJson(row.get("items"), new TypeReference<List<Map<String, Object>>>() {});
                boolean hasPpt = detectExpectedType(items, PptTypeUtils::isPptExpectedType);
                boolean hasHtml = detectExpectedType(items, HtmlTypeUtils::isHtmlExpectedType);
                if (hasPpt || hasHtml) {
                    jdbcTemplate.update(
                            "UPDATE dataset SET has_ppt_expected = ?, has_html_expected = ? WHERE id = ?",
                            hasPpt ? 1 : 0, hasHtml ? 1 : 0, id);
                }
            }
        } catch (Exception e) {
            log.warn("回填历史数据集 PPT/HTML 标准答案标记失败: {}", e.getMessage());
        }
    }

    private boolean detectExpectedType(List<Map<String, Object>> items, java.util.function.Predicate<String> typeMatcher) {
        if (items == null) {
            return false;
        }
        for (Map<String, Object> item : items) {
            if (item == null) {
                continue;
            }
            Object expected = item.get("expected");
            if (expected instanceof Map) {
                Object type = ((Map<?, ?>) expected).get("type");
                if (type != null && typeMatcher.test(type.toString())) {
                    return true;
                }
            }
        }
        return false;
    }

    private Map<String, Object> rowToMap(Map<String, Object> row) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", row.get("id"));
        map.put("name", row.get("name"));
        map.put("description", row.get("description"));
        map.put("fileName", row.get("file_name"));
        map.put("filePath", row.get("file_path"));
        map.put("itemCount", row.get("item_count"));

        Object hasExpected = row.get("has_expected_result");
        map.put("hasExpectedResult", asBoolean(hasExpected, false));

        String datasetType = asString(row.get("dataset_type"), DEFAULT_DATASET_TYPE);
        if (datasetType == null || datasetType.trim().isEmpty()) {
            datasetType = DEFAULT_DATASET_TYPE;
        }
        datasetType = datasetType.trim().toLowerCase(Locale.ROOT);

        boolean hasMultimodal = asBoolean(row.get("has_multimodal"), "multimodal".equalsIgnoreCase(datasetType));

        List<String> modalities = normalizeModalities(row.get("modalities"));
        if (modalities.isEmpty()) {
            modalities.add("text");
        }

        String schemaVersion = asString(row.get("schema_version"), DEFAULT_SCHEMA_VERSION);
        if (schemaVersion == null || schemaVersion.trim().isEmpty()) {
            schemaVersion = DEFAULT_SCHEMA_VERSION;
        }

        String sourceFormat = asString(row.get("source_format"), DEFAULT_SOURCE_FORMAT);
        if (sourceFormat == null || sourceFormat.trim().isEmpty()) {
            sourceFormat = DEFAULT_SOURCE_FORMAT;
        }

        map.put("datasetType", datasetType);
        map.put("hasMultimodal", hasMultimodal);
        map.put("modalities", modalities);
        map.put("schemaVersion", schemaVersion);
        map.put("sourceFormat", sourceFormat);
        map.put("starred", asBoolean(row.get("starred"), false));
        map.put("hasPptExpected", asBoolean(row.get("has_ppt_expected"), false));
        map.put("hasHtmlExpected", asBoolean(row.get("has_html_expected"), false));

        map.put("createdAt", row.get("created_at"));
        map.put("updatedAt", row.get("updated_at"));

        if (row.containsKey("items")) {
            map.put("items", fromJson(row.get("items"), new TypeReference<List<Map<String, Object>>>() {}));
        }
        return map;
    }

    private Object firstNonNull(Map<String, Object> map, String... keys) {
        if (map == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (map.containsKey(key) && map.get(key) != null) {
                return map.get(key);
            }
        }
        return null;
    }

    private String asString(Object value, String defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        String text = value.toString();
        return text == null ? defaultValue : text;
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

    private List<String> normalizeModalities(Object value) {
        LinkedHashSet<String> result = new LinkedHashSet<>();

        if (value == null) {
            return new ArrayList<>(result);
        }

        if (value instanceof Collection<?>) {
            for (Object item : (Collection<?>) value) {
                addNormalizedModality(result, item);
            }
            return new ArrayList<>(result);
        }

        String text = value.toString().trim();
        if (text.isEmpty()) {
            return new ArrayList<>(result);
        }

        if (text.startsWith("[") && text.endsWith("]")) {
            try {
                List<Object> list = objectMapper.readValue(text, new TypeReference<List<Object>>() {});
                for (Object item : list) {
                    addNormalizedModality(result, item);
                }
                return new ArrayList<>(result);
            } catch (Exception e) {
                log.warn("modalities JSON 解析失败，将按普通字符串处理: {}", e.getMessage());
            }
        }

        String cleaned = text.replace("[", "")
                .replace("]", "")
                .replace("\"", "")
                .replace("'", "");

        for (String part : cleaned.split(",")) {
            addNormalizedModality(result, part);
        }

        return new ArrayList<>(result);
    }

    private void addNormalizedModality(Set<String> result, Object value) {
        if (value == null) {
            return;
        }
        String text = value.toString().trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return;
        }

        if (text.contains("image") || text.contains("img") || text.contains("picture")) {
            result.add("image");
            return;
        }

        if (text.contains("file") || text.contains("document") || text.contains("doc") || text.contains("pdf")) {
            result.add("file");
            return;
        }

        if (text.contains("text")) {
            result.add("text");
            return;
        }

        result.add(text);
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
            return objectMapper.readValue(jsonObj.toString(), typeRef);
        } catch (Exception e) {
            log.warn("JSON反序列化失败", e);
            return null;
        }
    }
}
