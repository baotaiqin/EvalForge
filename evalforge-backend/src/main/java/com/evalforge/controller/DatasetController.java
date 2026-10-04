package com.evalforge.controller;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.evalforge.service.PptTypeUtils;
import com.evalforge.service.HtmlTypeUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.evalforge.service.DatasetStoreService;
import com.evalforge.service.FileParseService;
import com.evalforge.service.MinioStorageService;

@RestController
@RequestMapping("/api/datasets")
@CrossOrigin(origins = "*")
public class DatasetController {

    private static final String DATASET_TYPE_TEXT = "text";
    private static final String DATASET_TYPE_MULTIMODAL = "multimodal";
    private static final String DEFAULT_SCHEMA_VERSION = "v1";

    private static final Set<String> SUPPORTED_PARSE_EXTENSIONS = new HashSet<>(Arrays.asList(
            "doc", "docx", "xls", "xlsx", "json", "jsonl", "ndjson", "zip"
    ));

    private static final String SUPPORTED_PARSE_FORMAT_MESSAGE =
            "仅支持上传 .doc/.docx/.xls/.xlsx/.json/.jsonl/.ndjson/.zip 格式文件";


    private static final Set<String> IMAGE_KEYS = new HashSet<>(Arrays.asList(
            "questionimages",
            "images",
            "image",
            "imageurls",
            "imageurl",
            "pictures",
            "picture",
            "imgs",
            "img"
    ));

    private static final Set<String> FILE_KEYS = new HashSet<>(Arrays.asList(
            "questionfiles",
            "files",
            "file",
            "fileurls",
            "fileurl",
            "attachments",
            "attachment",
            "documents",
            "document",
            "docs",
            "doc",
            "pdfs",
            "pdf"
    ));

    @Autowired
    private FileParseService fileParseService;

    @Autowired
    private DatasetStoreService datasetStore;

    @Autowired
    private MinioStorageService minioStorageService;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 获取所有数据集列表（不含 items 详情）
     */
    @GetMapping
    public Map<String, Object> listDatasets() {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> ds : datasetStore.getAll()) {
            Map<String, Object> brief = new HashMap<>(ds);
            brief.remove("items");
            list.add(brief);
        }
        result.put("code", 0);
        result.put("data", list);
        return result;
    }

    /**
     * 获取单个数据集详情（含 items）
     */
    @GetMapping("/{id}")
    public Map<String, Object> getDataset(@PathVariable String id) {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> ds = datasetStore.get(id);
        if (ds == null) {
            result.put("code", 404);
            result.put("message", "数据集不存在");
            return result;
        }
        result.put("code", 0);
        result.put("data", ds);
        return result;
    }

    /**
     * 创建数据集
     */
    @PostMapping
    public Map<String, Object> createDataset(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        try {
            String name = body.get("name") == null ? null : body.get("name").toString();
            if (name == null || name.trim().isEmpty()) {
                result.put("code", 400);
                result.put("message", "数据集名称不能为空");
                return result;
            }
            if (datasetStore.existsByName(name.trim(), null)) {
                result.put("code", 400);
                result.put("message", "数据集名称已存在，请使用其他名称");
                return result;
            }

            String id = datasetStore.nextId();
            Map<String, Object> ds = new HashMap<>(body);
            ds.put("id", id);
            ds.put("name", name.trim());
            refreshItemCount(ds);
            normalizeExpectedResult(ds, false);
            normalizeDatasetMetadata(ds, resolveSourceFormatFromFileName(asString(ds.get("fileName")), "manual"));
            String now = LocalDateTime.now().format(FMT);
            ds.put("createdAt", now);
            ds.put("updatedAt", now);
            datasetStore.put(id, ds);
            result.put("code", 0);
            result.put("data", ds);
        } catch (Exception e) {
            result.put("code", 500);
            result.put("message", "创建数据集失败：" + e.getMessage());
        }
        return result;
    }

    /**
     * 更新数据集
     */
    @PutMapping("/{id}")
    public Map<String, Object> updateDataset(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> ds = datasetStore.get(id);
        if (ds == null) {
            result.put("code", 404);
            result.put("message", "数据集不存在");
            return result;
        }

        // 更新时校验名称不与其他数据集重复
        if (body.containsKey("name")) {
            String newName = body.get("name") == null ? null : body.get("name").toString();
            if (newName != null && !newName.trim().isEmpty()
                    && datasetStore.existsByName(newName.trim(), id)) {
                result.put("code", 400);
                result.put("message", "数据集名称已存在，请使用其他名称");
                return result;
            }
            if (newName != null) {
                body.put("name", newName.trim());
            }
        }

        // items 变更且未显式传入 metadata 时清理旧值
        boolean itemsChanged = body.containsKey("items");
        boolean explicitExpectedResult = body.containsKey("hasExpectedResult")
                || body.containsKey("has_expected_result");
        boolean explicitDatasetMetadata = body.containsKey("datasetType")
                || body.containsKey("dataset_type")
                || body.containsKey("hasMultimodal")
                || body.containsKey("has_multimodal")
                || body.containsKey("modalities");

        ds.putAll(body);
        ds.put("id", id);
        if (itemsChanged && !explicitDatasetMetadata) {
            ds.remove("datasetType");
            ds.remove("dataset_type");
            ds.remove("hasMultimodal");
            ds.remove("has_multimodal");
            ds.remove("modalities");
        }
        // items 变更后重新计算条目数与数据集元信息
        refreshItemCount(ds);
        normalizeExpectedResult(ds, itemsChanged && !explicitExpectedResult);
        normalizeDatasetMetadata(ds, resolveSourceFormatFromFileName(asString(ds.get("fileName")), "manual"));
        ds.put("updatedAt", LocalDateTime.now().format(FMT));
        datasetStore.put(id, ds);
        result.put("code", 0);
        result.put("data", ds);
        return result;
    }

    /**
     * 删除数据集
     */
    @DeleteMapping("/{id}")
    public Map<String, Object> deleteDataset(@PathVariable String id) {
        Map<String, Object> result = new HashMap<>();
        datasetStore.remove(id);
        result.put("code", 0);
        return result;
    }

    /**
     * 上传文件并解析为数据集条目
     *
     * 支持：
     * 1. 旧文本 / 图文问答数据集：.doc/.docx/.xls/.xlsx
     * 2. 新多模态数据集：.json/.jsonl/.ndjson/.zip
     *
     * @param file 上传的文件
     * @return { code: 0, data: { items: [...], fileName: "xxx", filePath: "...", itemCount: N, datasetType: "...", modalities: [...] } }
     */
    @PostMapping("/parse")
    public Map<String, Object> parseDataset(@RequestParam("file") MultipartFile file) {
        Map<String, Object> result = new HashMap<>();
        try {
            String fileName = file.getOriginalFilename();
            String ext = getFileExtension(fileName);
            if (fileName == null || fileName.trim().isEmpty() || !SUPPORTED_PARSE_EXTENSIONS.contains(ext)) {
                result.put("code", 400);
                result.put("message", SUPPORTED_PARSE_FORMAT_MESSAGE);
                return result;
            }

            List<Map<String, Object>> items = fileParseService.parseDocument(file);
            String filePath = fileParseService.saveFile(file);
            Map<String, Object> data = new HashMap<>();
            data.put("items", items);
            data.put("fileName", fileName);
            data.put("filePath", filePath);
            data.put("itemCount", items.size());
            normalizeExpectedResult(data, false);
            normalizeDatasetMetadata(data, resolveSourceFormatFromFileName(fileName, ext));
            result.put("code", 0);
            result.put("data", data);
        } catch (IllegalArgumentException e) {
            result.put("code", 400);
            result.put("message", e.getMessage());
        } catch (Exception e) {
            result.put("code", 500);
            result.put("message", "文件解析失败：" + e.getMessage());
        }
        return result;
    }

    /**
     * 提供图片文件访问（兼容存量本地文件 + 新数据 MinIO）
     */
    @GetMapping("/images/{fileName}")
    public void getImage(@PathVariable String fileName,
            jakarta.servlet.http.HttpServletResponse response) {
        try {
            // 存量本地文件优先
            java.io.File file = new java.io.File("uploads/images/" + fileName).getAbsoluteFile();
            if (file.exists()) {
                String ext = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
                switch (ext) {
                    case "png":
                        response.setContentType("image/png");
                        break;
                    case "jpg":
                    case "jpeg":
                        response.setContentType("image/jpeg");
                        break;
                    case "gif":
                        response.setContentType("image/gif");
                        break;
                    default:
                        response.setContentType("application/octet-stream");
                }
                java.nio.file.Files.copy(file.toPath(), response.getOutputStream());
                return;
            }
            // 本地不存在，重定向到 MinIO
            response.sendRedirect(minioStorageService.getUrl("images/" + fileName));
        } catch (Exception e) {
            response.setStatus(500);
        }
    }

    private void refreshItemCount(Map<String, Object> ds) {
        Object items = ds.get("items");
        ds.put("itemCount", items instanceof List ? ((List<?>) items).size() : 0);
    }

    private void normalizeExpectedResult(Map<String, Object> ds, boolean forceDetect) {
        Object explicitValue = firstPresent(ds,
                "hasExpectedResult", "has_expected_result");
        if (!forceDetect && explicitValue != null) {
            ds.put("hasExpectedResult", toBoolean(explicitValue, false));
            ds.remove("has_expected_result");
            return;
        }
        boolean hasExpected = false;
        Object items = ds.get("items");
        if (items instanceof List<?>) {
            for (Object item : (List<?>) items) {
                if (!(item instanceof Map<?, ?>)) {
                    continue;
                }
                Map<?, ?> itemMap = (Map<?, ?>) item;
                Object expectedAnswer = itemMap.get("expectedAnswer");
                if (expectedAnswer != null && !expectedAnswer.toString().trim().isEmpty()) {
                    hasExpected = true;
                    break;
                }
                Object expected = itemMap.get("expected");
                if (expected instanceof Map<?, ?>) {
                    Map<?, ?> expectedMap = (Map<?, ?>) expected;
                    Object expectedText = expectedMap.get("text");
                    if (expectedText != null && !expectedText.toString().trim().isEmpty()) {
                        hasExpected = true;
                        break;
                    }
                    Object expectedType = expectedMap.get("type");
                    if (expectedType != null
                            && (PptTypeUtils.isPptExpectedType(expectedType.toString())
                            || HtmlTypeUtils.isHtmlExpectedType(expectedType.toString()))) {
                        hasExpected = true;
                        break;
                    }
                }
            }
        }
        ds.put("hasExpectedResult", hasExpected);
        ds.remove("has_expected_result");
    }

    private void normalizeDatasetMetadata(Map<String, Object> ds, String defaultSourceFormat) {
        Set<String> detectedModalities = detectModalitiesFromItems(ds.get("items"));
        List<String> inputModalities = normalizeModalities(firstPresent(ds, "modalities"));
        LinkedHashSet<String> finalModalities = new LinkedHashSet<>();
        finalModalities.addAll(inputModalities);
        finalModalities.addAll(detectedModalities);

        String inputDatasetType = normalizeDatasetType(firstPresent(ds,
                "datasetType", "dataset_type"));
        Boolean inputHasMultimodal = toBooleanObject(firstPresent(ds,
                "hasMultimodal", "has_multimodal"));
        boolean detectedMultimodal = detectedModalities.contains("image")
                || detectedModalities.contains("file");
        boolean hasMultimodal = detectedMultimodal
                || DATASET_TYPE_MULTIMODAL.equals(inputDatasetType)
                || Boolean.TRUE.equals(inputHasMultimodal);
        if (Boolean.FALSE.equals(inputHasMultimodal)
                && !detectedMultimodal
                && !DATASET_TYPE_MULTIMODAL.equals(inputDatasetType)) {
            hasMultimodal = false;
        }

        String datasetType = hasMultimodal ? DATASET_TYPE_MULTIMODAL : DATASET_TYPE_TEXT;
        if (!hasMultimodal) {
            finalModalities.clear();
            finalModalities.add(DATASET_TYPE_TEXT);
        }
        String schemaVersion = asString(firstPresent(ds,
                "schemaVersion", "schema_version"));
        if (schemaVersion == null || schemaVersion.trim().isEmpty()) schemaVersion = DEFAULT_SCHEMA_VERSION;
        String sourceFormat = asString(firstPresent(ds,
                "sourceFormat", "source_format"));
        if (sourceFormat == null || sourceFormat.trim().isEmpty()) sourceFormat = defaultSourceFormat;
        if (sourceFormat == null || sourceFormat.trim().isEmpty()) sourceFormat = "manual";

        ds.put("datasetType", datasetType);
        ds.put("hasMultimodal", hasMultimodal);
        ds.put("modalities", new ArrayList<>(finalModalities));
        ds.put("hasPptExpected", detectHasPptExpected(ds.get("items")));
        ds.put("hasHtmlExpected", detectHasHtmlExpected(ds.get("items")));
        ds.put("schemaVersion", schemaVersion.trim());
        ds.put("sourceFormat", sourceFormat.trim().toLowerCase(Locale.ROOT));
        ds.remove("dataset_type");
        ds.remove("has_multimodal");
        ds.remove("schema_version");
        ds.remove("source_format");
    }

    // 数据集是否存在 PPT 对比评测的标准答案（item.expected.type=pptx/ppt），供列表页/上传预览区分数据集类型
    private boolean detectHasPptExpected(Object items) {
        if (!(items instanceof List<?>)) {
            return false;
        }
        for (Object item : (List<?>) items) {
            if (!(item instanceof Map<?, ?>)) {
                continue;
            }
            Map<?, ?> itemMap = (Map<?, ?>) item;
            Object expected = itemMap.get("expected");
            if (expected instanceof Map<?, ?>) {
                Map<?, ?> expectedMap = (Map<?, ?>) expected;
                Object type = expectedMap.get("type");
                if (type != null && PptTypeUtils.isPptExpectedType(type.toString())) {
                    return true;
                }
            }
        }
        return false;
    }

    // 数据集是否存在 HTML 对比评测的标准答案（item.expected.type=html），供列表页/上传预览区分数据集类型
    private boolean detectHasHtmlExpected(Object items) {
        if (!(items instanceof List<?>)) {
            return false;
        }
        for (Object item : (List<?>) items) {
            if (!(item instanceof Map<?, ?>)) {
                continue;
            }
            Map<?, ?> itemMap = (Map<?, ?>) item;
            Object expected = itemMap.get("expected");
            if (expected instanceof Map<?, ?>) {
                Map<?, ?> expectedMap = (Map<?, ?>) expected;
                Object type = expectedMap.get("type");
                if (type != null && HtmlTypeUtils.isHtmlExpectedType(type.toString())) {
                    return true;
                }
            }
        }
        return false;
    }

    private Set<String> detectModalitiesFromItems(Object items) {
        LinkedHashSet<String> modalities = new LinkedHashSet<>();
        if (items instanceof List<?>) {
            for (Object item : (List<?>) items) {
                collectModalities(item, modalities, 0);
            }
        }
        return modalities;
    }

    private void collectModalities(Object value, Set<String> modalities, int depth) {
        if (value == null || depth > 8) {
            return;
        }
        if (value instanceof Map<?, ?>) {
            Map<?, ?> map = (Map<?, ?>) value;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = normalizeKey(entry.getKey());
                Object entryValue = entry.getValue();
                if (IMAGE_KEYS.contains(key) && isNonEmpty(entryValue)) {
                    modalities.add("image");
                }
                if (FILE_KEYS.contains(key) && isNonEmpty(entryValue)) {
                    modalities.add("file");
                }
                if ("type".equals(key) && entryValue != null) {
                    String typeValue = entryValue.toString().toLowerCase(Locale.ROOT);
                    if (typeValue.contains("image")
                            || typeValue.contains("img")
                            || typeValue.contains("picture")) {
                        modalities.add("image");
                    }
                    if (PptTypeUtils.isPptExpectedType(entryValue.toString())
                            || HtmlTypeUtils.isHtmlExpectedType(entryValue.toString())
                            || typeValue.contains("file")
                            || typeValue.contains("document")
                            || typeValue.contains("doc")
                            || typeValue.contains("pdf")) {
                        modalities.add("file");
                    }
                }
                collectModalities(entryValue, modalities, depth + 1);
            }
        } else if (value instanceof Iterable<?>) {
            for (Object item : (Iterable<?>) value) {
                collectModalities(item, modalities, depth + 1);
            }
        }
    }

    private String normalizeKey(Object key) {
        if (key == null) {
            return "";
        }
        return key.toString()
                .trim()
                .replace("_", "")
                .replace("-", "")
                .toLowerCase(Locale.ROOT);
    }

    private boolean isNonEmpty(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Collection<?>) {
            return !((Collection<?>) value).isEmpty();
        }
        if (value instanceof Map<?, ?>) {
            return !((Map<?, ?>) value).isEmpty();
        }
        return !value.toString().trim().isEmpty();
    }

    private String normalizeDatasetType(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim().toLowerCase(Locale.ROOT);
        if (DATASET_TYPE_MULTIMODAL.equals(text)) {
            return DATASET_TYPE_MULTIMODAL;
        }
        if (DATASET_TYPE_TEXT.equals(text)) {
            return DATASET_TYPE_TEXT;
        }
        return null;
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
        String cleaned = text.replace("[", "").replace("]", "")
                .replace("\"", "").replace("'", "");
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
        if (text.contains("image")
                || text.contains("img")
                || text.contains("picture")) {
            result.add("image");
        } else if (text.contains("file")
                || text.contains("document")
                || text.contains("doc")
                || text.contains("pdf")) {
            result.add("file");
        } else if (text.contains("text")) {
            result.add("text");
        } else {
            result.add(text);
        }
    }

    private Boolean toBooleanObject(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        String text = value.toString().trim().toLowerCase(Locale.ROOT);
        if ("true".equals(text)
                || "1".equals(text)
                || "yes".equals(text)
                || "y".equals(text)) {
            return true;
        }
        if ("false".equals(text)
                || "0".equals(text)
                || "no".equals(text)
                || "n".equals(text)) {
            return false;
        }
        return null;
    }

    private boolean toBoolean(Object value, boolean defaultValue) {
        Boolean parsed = toBooleanObject(value);
        return parsed == null ? defaultValue : parsed;
    }

    private String asString(Object value) {
        return value == null ? null : value.toString();
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

    private String getFileExtension(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            return "";
        }
        String trimmed = fileName.trim();
        int dotIndex = trimmed.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex >= trimmed.length() - 1) {
            return "";
        }
        return trimmed.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }

    private String resolveSourceFormatFromFileName(String fileName, String defaultValue) {
        String ext = getFileExtension(fileName);
        if (ext != null && !ext.trim().isEmpty()) {
            return ext;
        }
        if (defaultValue == null || defaultValue.trim().isEmpty()) {
            return "manual";
        }
        return defaultValue.trim().toLowerCase(Locale.ROOT);
    }
}
