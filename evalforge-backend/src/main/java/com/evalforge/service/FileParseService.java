package com.evalforge.service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.poi.hssf.usermodel.HSSFClientAnchor;
import org.apache.poi.hssf.usermodel.HSSFPicture;
import org.apache.poi.hssf.usermodel.HSSFPictureData;
import org.apache.poi.hssf.usermodel.HSSFPatriarch;
import org.apache.poi.hssf.usermodel.HSSFShape;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.openxml4j.opc.PackageRelationship;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFPicture;
import org.apache.poi.xssf.usermodel.XSSFPictureData;
import org.apache.poi.xssf.usermodel.XSSFShape;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 文件解析服务
 *
 * 已支持：
 * 1. 旧文本 / 表格格式：Word .doc / .docx，Excel .xls / .xlsx
 * 2. 新多模态数据集格式：.json、.jsonl / .ndjson、.zip
 *
 * 兼容原则：
 * 1. 保留旧字段：question、expectedAnswer、images
 * 2. 新增字段：questionImages、files、questionFiles、input、expected、evaluation、metadata、modalities
 * 3. 后续 EvaluationService 可以逐步切换到 input / expected / modalities，旧文本评测链路仍可继续读取 question / expectedAnswer。
 */
@Service
public class FileParseService {

    @Autowired
    private MinioStorageService minioStorageService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String UPLOAD_DIR = "uploads/";
    private static final String IMAGE_DIR = "uploads/images/";

    /** 用于识别“用户提问”列的关键词 */
    private static final Set<String> QUESTION_HEADERS = Set.of(
            "用户提问", "提问", "问题", "问", "question"
    );

    /** 用于识别“预期回答”列的关键词 */
    private static final Set<String> ANSWER_HEADERS = Set.of(
            "预期回答", "预期结果", "期望回答", "标准答案", "答案", "answer", "a", "expectedanswer"
    );

    private static final Set<String> IMAGE_KEYS = Set.of(
            "questionImages", "question_images", "images", "image", "imageUrls", "imageUrl",
            "image_url", "pictures", "picture", "imgs", "img"
    );

    private static final Set<String> FILE_KEYS = Set.of(
            "questionFiles", "question_files", "files", "file", "fileUrls", "file_urls",
            "fileUrl", "file_url", "attachments", "attachment", "documents", "document",
            "docs", "doc", "pdfs", "pdf"
    );

    private static final Set<String> SAMPLE_FILE_NAMES = Set.of(
            "samples.jsonl", "samples.ndjson", "samples.json", "sample.jsonl", "sample.ndjson",
            "sample.json", "dataset.jsonl", "dataset.ndjson", "dataset.json", "manifest.jsonl",
            "manifest.ndjson", "manifest.json", "data.jsonl", "data.ndjson", "data.json",
            "records.jsonl", "records.ndjson", "records.json"
    );

    /** 正则：匹配 Word XML 中的图片关系 ID（r:embed="rIdXX"） */
    private static final Pattern REL_EMBED_PATTERN = Pattern.compile("r:embed=\"([^\"]+)\"");

    /** 标准化表头文本：去掉空白等符号，转小写 */
    private static String normalizeHeader(String header) {
        if (header == null) {
            return "";
        }
        return header.trim().replaceAll("[\\s\\p{Punct}，。；：！？、（）【】《》“”‘’]+", "").toLowerCase(Locale.ROOT);
    }

    /**
     * 解析上传文件，提取问答列表。
     *
     * @param file 上传的文件
     * @return 解析后的数据项列表
     */
    public List<Map<String, Object>> parseDocument(MultipartFile file) throws Exception {
        String fileName = file.getOriginalFilename();
        if (fileName == null || fileName.trim().isEmpty()) {
            throw new IllegalArgumentException("文件名不能为空");
        }

        String ext = getFileExtension(fileName);
        switch (ext) {
            case "docx":
                return parseDocx(file);
            case "doc":
                return parseDoc(file);
            case "xlsx":
                return parseXlsx(file);
            case "xls":
                return parseXls(file);
            case "jsonl":
            case "ndjson":
                return parseJsonLinesDataset(file.getInputStream(), Collections.emptyMap());
            case "json":
                return parseJsonDataset(file.getInputStream(), Collections.emptyMap());
            case "zip":
                return parseZipDataset(file);
            default:
                throw new IllegalArgumentException("不支持的文件格式：" + ext
                        + "，仅支持 .doc/.docx/.xls/.xlsx/.json/.jsonl/.ndjson/.zip");
        }
    }

    // ========================================================================
    // 新增：多模态 zip / json / jsonl 解析
    // ========================================================================

    /**
     * 解析 zip 多模态数据集。
     *
     * 推荐 zip 结构：
     *   dataset.zip
     *   ├── samples.json 或 samples.jsonl
     *   ├── images/ (可选，图片资源)
     *   └── files/ (可选，文件资源)
     *
     * 兼容 samples.json / sample.json / dataset.json / manifest.json / data.json 等文件名。
     */
    private List<Map<String, Object>> parseZipDataset(MultipartFile file) throws Exception {
        Path tempDir = Files.createTempDirectory("evalforge-dataset-zip-");
        try {
            unzipToDirectory(file, tempDir);

            // zip 内的 images / files / attachments 等资源上传到 MinIO，建立 zip 路径 -> 可访问 URL 的映射。
            Map<String, String> pathToUrl = uploadZipAssets(tempDir);

            Path jsonLinesFile = findFirstFileByName(tempDir,
                    "samples.jsonl",
                    "samples.ndjson",
                    "sample.jsonl",
                    "sample.ndjson",
                    "dataset.jsonl",
                    "dataset.ndjson",
                    "manifest.jsonl",
                    "manifest.ndjson",
                    "data.jsonl",
                    "data.ndjson",
                    "records.jsonl",
                    "records.ndjson");
            if (jsonLinesFile == null) {
                jsonLinesFile = findFirstDatasetFileByExtension(tempDir, "jsonl", "ndjson");
            }

            if (jsonLinesFile != null) {
                try (InputStream in = Files.newInputStream(jsonLinesFile)) {
                    return parseJsonLinesDataset(in, pathToUrl);
                }
            }

            Path jsonFile = findFirstFileByName(tempDir,
                    "samples.json", "sample.json", "dataset.json", "manifest.json", "data.json", "records.json");
            if (jsonFile == null) {
                jsonFile = findFirstDatasetFileByExtension(tempDir, "json");
            }
            if (jsonFile == null) {
                throw new IllegalArgumentException(
                        "zip 数据集缺少 samples.json / samples.jsonl / dataset.json / manifest.json / data.json 文件");
            }

            try (InputStream in = Files.newInputStream(jsonFile)) {
                return parseJsonDataset(in, pathToUrl);
            }
        } finally {
            deleteDirectoryQuietly(tempDir);
        }
    }

    /**
     * zip 解压。先按 UTF-8 解压；如果遇到 Windows / WinRAR 压缩包文件名编码问题，再按 GBK 兜底。
     */
    private void unzipToDirectory(MultipartFile file, Path targetDir) throws Exception {
        try {
            unzipToDirectory(file, targetDir, StandardCharsets.UTF_8);
        } catch (Exception utf8Error) {
            deleteDirectoryContentsQuietly(targetDir);
            try {
                unzipToDirectory(file, targetDir, Charset.forName("GBK"));
            } catch (Exception gbkError) {
                throw new IllegalArgumentException("zip 解压失败，请确认压缩包未损坏，且内部包含 samples.json / samples.jsonl。"
                        + "UTF-8 错误：" + utf8Error.getMessage() + "；GBK 错误：" + gbkError.getMessage());
            }
        }
    }

    private void unzipToDirectory(MultipartFile file, Path targetDir, Charset charset) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(file.getInputStream(), charset)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path outPath = targetDir.resolve(entry.getName()).normalize();

                // 防止 zip slip
                if (!outPath.startsWith(targetDir)) {
                    throw new IllegalArgumentException("zip 文件中包含非法路径：" + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(outPath);
                    continue;
                }

                Files.createDirectories(outPath.getParent());
                Files.copy(zis, outPath, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /**
     * 上传 zip 内的 images / files / attachments 等资源到 MinIO，并建立路径映射。
     */
    private Map<String, String> uploadZipAssets(Path rootDir) throws Exception {
        Map<String, String> pathToUrl = new HashMap<>();
        try (Stream<Path> stream = Files.walk(rootDir)) {
            Iterator<Path> iterator = stream.filter(Files::isRegularFile).iterator();
            while (iterator.hasNext()) {
                Path filePath = iterator.next();
                String relativePath = normalizePath(rootDir.relativize(filePath).toString());
                String fileName = filePath.getFileName().toString();

                if (isDatasetDefinitionFile(relativePath)) {
                    continue;
                }
                if (!isLikelyZipAsset(relativePath)) {
                    continue;
                }

                byte[] data = Files.readAllBytes(filePath);
                String contentType = contentTypeByFileName(fileName);
                String prefix = isImageFile(fileName) ? "images/" : "files/";
                String objectName = prefix
                        + System.currentTimeMillis()
                        + "_"
                        + UUID.randomUUID().toString().replace("-", "")
                        + "_"
                        + sanitizeFileName(fileName);

                minioStorageService.upload(objectName, data, contentType);
                String url = minioStorageService.getUrl(objectName);
                putPathMapping(pathToUrl, relativePath, url);
                putPathMapping(pathToUrl, "/" + relativePath, url);
                putPathMapping(pathToUrl, fileName, url);
            }
        }
        return pathToUrl;
    }

    private void putPathMapping(Map<String, String> pathToUrl, String path, String url) {
        if (path == null || path.trim().isEmpty()) {
            return;
        }
        String normalized = normalizePath(path);
        pathToUrl.put(normalized, url);
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
            pathToUrl.put(normalized, url);
        }

        // 兼容用户路径中误写了少量空格的情况，例如 images/1 .png
        String compact = normalized.replaceAll("\\s+", "");
        if (!compact.equals(normalized)) {
            pathToUrl.put(compact, url);
        }
    }

    private boolean isDatasetDefinitionFile(String relativePath) {
        String fileName = Paths.get(relativePath).getFileName().toString().toLowerCase(Locale.ROOT);
        return SAMPLE_FILE_NAMES.contains(fileName);
    }

    private boolean isLikelyZipAsset(String relativePath) {
        String normalized = normalizePath(relativePath).toLowerCase(Locale.ROOT);
        String fileName = Paths.get(normalized).getFileName().toString();
        if (normalized.startsWith("images/")
                || normalized.startsWith("image/")
                || normalized.startsWith("files/")
                || normalized.startsWith("file/")
                || normalized.startsWith("attachments/")
                || normalized.startsWith("attachment/")
                || normalized.startsWith("documents/")
                || normalized.startsWith("document/")
                || normalized.contains("/images/")
                || normalized.contains("/image/")
                || normalized.contains("/files/")
                || normalized.contains("/file/")
                || normalized.contains("/attachments/")
                || normalized.contains("/attachment/")
                || normalized.contains("/documents/")
                || normalized.contains("/document/")) {
            return true;
        }
        return isImageFile(fileName) || isSupportedQuestionFile(fileName);
    }

    private Path findFirstFileByName(Path rootDir, String... names) throws IOException {
        Set<String> targetNames = new HashSet<>();
        for (String name : names) {
            targetNames.add(name.toLowerCase(Locale.ROOT));
        }

        try (Stream<Path> stream = Files.walk(rootDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> targetNames.contains(path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .findFirst()
                    .orElse(null);
        }
    }

    private Path findFirstDatasetFileByExtension(Path rootDir, String... extensions) throws IOException {
        Set<String> targetExts = new HashSet<>();
        for (String ext : extensions) {
            targetExts.add(ext.toLowerCase(Locale.ROOT));
        }

        try (Stream<Path> stream = Files.walk(rootDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String relativePath = normalizePath(rootDir.relativize(path).toString());
                        if (isUnderAssetDirectory(relativePath)) {
                            return false;
                        }
                        String fileName = path.getFileName().toString();
                        return targetExts.contains(getFileExtension(fileName));
                    })
                    .findFirst()
                    .orElse(null);
        }
    }

    private boolean isUnderAssetDirectory(String relativePath) {
        String normalized = "/" + normalizePath(relativePath).toLowerCase(Locale.ROOT);
        return normalized.contains("/images/")
                || normalized.contains("/image/")
                || normalized.contains("/files/")
                || normalized.contains("/file/")
                || normalized.contains("/attachments/")
                || normalized.contains("/attachment/")
                || normalized.contains("/documents/")
                || normalized.contains("/document/");
    }

    private List<Map<String, Object>> parseJsonLinesDataset(InputStream inputStream,
            Map<String, String> pathToUrl) throws Exception {
        List<Map<String, Object>> items = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }

                try {
                    Object raw = objectMapper.readValue(trimmed, Object.class);
                    Map<String, Object> item = normalizeSample(raw, pathToUrl, items.size());
                    if (isValidItem(item)) {
                        items.add(item);
                    }
                } catch (Exception e) {
                    throw new IllegalArgumentException("解析 samples.jsonl 第 " + lineNo + " 行失败：" + e.getMessage());
                }
            }
        }

        if (items.isEmpty()) {
            throw new IllegalArgumentException("samples.jsonl 中没有解析到有效样本");
        }
        return items;
    }

    private List<Map<String, Object>> parseJsonDataset(InputStream inputStream,
            Map<String, String> pathToUrl) throws Exception {
        Object root = objectMapper.readValue(inputStream, Object.class);
        List<Map<String, Object>> items = normalizeSamplesFromJsonRoot(root, pathToUrl);
        if (items.isEmpty()) {
            throw new IllegalArgumentException("json 数据集中没有解析到有效样本");
        }
        return items;
    }

    private List<Map<String, Object>> normalizeSamplesFromJsonRoot(Object root,
            Map<String, String> pathToUrl) {
        List<?> rawSamples = null;

        if (root instanceof List<?>) {
            rawSamples = (List<?>) root;
        } else if (root instanceof Map<?, ?>) {
            Map<String, Object> map = castToStringObjectMap((Map<?, ?>) root);
            Object samples = firstPresent(map, "samples", "items", "data", "records", "examples");
            if (samples instanceof List<?>) {
                rawSamples = (List<?>) samples;
            } else if (looksLikeSingleSample(map)) {
                rawSamples = Collections.singletonList(map);
            }
        }

        if (rawSamples == null) {
            return Collections.emptyList();
        }

        List<Map<String, Object>> items = new ArrayList<>();
        for (Object raw : rawSamples) {
            Map<String, Object> item = normalizeSample(raw, pathToUrl, items.size());
            if (isValidItem(item)) {
                items.add(item);
            }
        }
        return items;
    }

    private Map<String, Object> normalizeSample(Object raw, Map<String, String> pathToUrl, int index) {
        Map<String, Object> sample;
        if (raw instanceof Map<?, ?>) {
            sample = castToStringObjectMap((Map<?, ?>) raw);
        } else if (raw != null) {
            sample = new LinkedHashMap<>();
            sample.put("question", raw.toString());
        } else {
            sample = new LinkedHashMap<>();
        }

        Map<String, Object> inputMap = null;
        Object inputObj = firstPresent(sample, "input");
        if (inputObj instanceof Map<?, ?>) {
            inputMap = castToStringObjectMap((Map<?, ?>) inputObj);
        }

        String question = firstNonBlank(
                asString(firstPresent(sample, "question", "prompt", "query", "userQuestion", "user_question", "text")),
                extractTextFromInput(inputObj),
                inputMap == null ? null : extractQuestionFromMessages(firstPresent(inputMap, "messages")),
                extractTextFromInput(inputMap),
                extractQuestionFromMessages(firstPresent(sample, "messages"))
        );

        String expectedAnswer = firstNonBlank(
                asString(firstPresent(sample, "expectedAnswer", "expected_answer", "answer", "expectedResult",
                        "expected_result", "reference", "referenceAnswer")),
                extractExpectedText(firstPresent(sample, "expected")),
                extractExpectedText(firstPresent(sample, "output")),
                extractExpectedText(firstPresent(sample, "target"))
        );

        List<String> images = new ArrayList<>();
        collectMediaFromRefs(images, firstPresent(sample, IMAGE_KEYS), pathToUrl, true);
        collectMediaFromMessages(images, firstPresent(sample, "messages"), pathToUrl, true);
        collectMediaFromContentParts(images, firstPresent(sample, "content"), pathToUrl, true);
        if (inputMap != null) {
            collectMediaFromRefs(images, firstPresent(inputMap, IMAGE_KEYS), pathToUrl, true);
            collectMediaFromMessages(images, firstPresent(inputMap, "messages"), pathToUrl, true);
            collectMediaFromContentParts(images, firstPresent(inputMap, "content"), pathToUrl, true);
        }

        List<String> files = new ArrayList<>();
        collectMediaFromRefs(files, firstPresent(sample, FILE_KEYS), pathToUrl, false);
        collectMediaFromMessages(files, firstPresent(sample, "messages"), pathToUrl, false);
        collectMediaFromContentParts(files, firstPresent(sample, "content"), pathToUrl, false);
        if (inputMap != null) {
            collectMediaFromRefs(files, firstPresent(inputMap, FILE_KEYS), pathToUrl, false);
            collectMediaFromMessages(files, firstPresent(inputMap, "messages"), pathToUrl, false);
            collectMediaFromContentParts(files, firstPresent(inputMap, "content"), pathToUrl, false);
        }

        LinkedHashSet<String> modalities = new LinkedHashSet<>();
        modalities.add("text");
        for (String modality : normalizeModalities(firstPresent(sample, "modalities"))) {
            modalities.add(modality);
        }
        if (!images.isEmpty()) {
            modalities.add("image");
        }
        if (!files.isEmpty()) {
            modalities.add("file");
        }

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", firstNonBlank(asString(firstPresent(sample, "id")), "json-" + System.currentTimeMillis() + "-" + index));
        item.put("question", question == null ? null : question.trim());
        item.put("expectedAnswer", expectedAnswer != null && !expectedAnswer.trim().isEmpty()
                ? expectedAnswer.trim() : null);
        if (images.isEmpty()) {
            item.put("images", new ArrayList<>());
        } else {
            item.put("images", new ArrayList<>(images));
            item.put("questionImages", new ArrayList<>(images));
        }
        if (files.isEmpty()) {
            item.put("files", new ArrayList<>());
        } else {
            item.put("files", new ArrayList<>(files));
            item.put("questionFiles", new ArrayList<>(files));
        }

        Map<String, Object> normalizedInput = new LinkedHashMap<>();
        normalizedInput.put("text", question == null ? "" : question.trim());
        if (!images.isEmpty()) {
            normalizedInput.put("images", new ArrayList<>(images));
        }
        if (!files.isEmpty()) {
            normalizedInput.put("files", new ArrayList<>(files));
        }
        item.put("input", normalizedInput);

        Map<String, Object> normalizedExpected = buildExpected(firstPresent(sample, "expected", "output", "target"),
                expectedAnswer, pathToUrl);
        if (normalizedExpected == null) {
            normalizedExpected = new LinkedHashMap<>();
            if (expectedAnswer != null && !expectedAnswer.trim().isEmpty()) {
                normalizedExpected.put("text", expectedAnswer.trim());
            }
        }
        item.put("expected", normalizedExpected);

        Object evaluation = firstPresent(sample, "evaluation", "judge", "scoring");
        if (evaluation instanceof Map<?, ?>) {
            item.put("evaluation", castToStringObjectMap((Map<?, ?>) evaluation));
        }

        Map<String, Object> metadataMap = new LinkedHashMap<>();
        Object metadata = firstPresent(sample, "metadata", "meta");
        if (metadata instanceof Map<?, ?>) {
            metadataMap.putAll(castToStringObjectMap((Map<?, ?>) metadata));
        }
        putIfPresent(metadataMap, "category", firstPresent(sample, "category"));
        putIfPresent(metadataMap, "tags", firstPresent(sample, "tags"));
        putIfPresent(metadataMap, "difficulty", firstPresent(sample, "difficulty"));
        item.put("metadata", metadataMap);
        item.put("modalities", new ArrayList<>(modalities));
        return item;
    }

    /**
     * 从 samples.json 中解析 {"type":"pptx","file":"expected/xx.pptx"} 或 {"type":"html","file":"expected/xx.html"} 的标准答案文档引用，结合 zip 内文件路径 -> URL 映射。
     * 找不到文件时不创建 expected 文件引用，交由原有纯文本 expected 处理。
     */
    private Map<String, Object> buildExpected(Object rawExpected, String expectedAnswer,
            Map<String, String> pathToUrl) {
        if (!(rawExpected instanceof Map<?, ?>)) {
            return null;
        }
        Map<String, Object> expectedMap = castToStringObjectMap((Map<?, ?>) rawExpected);
        String rawType = asString(firstPresent(expectedMap, "type"));
        boolean isPpt = PptTypeUtils.isPptExpectedType(rawType);
        boolean isHtml = HtmlTypeUtils.isHtmlExpectedType(rawType);
        if (!isPpt && !isHtml) {
            return null;
        }

        Object fileRef = firstPresent(expectedMap, "file", "path", "filePath", "file_path");
        String url = resolveMediaRef(fileRef, pathToUrl);
        if (url == null || url.trim().isEmpty()) {
            return null;
        }

        String fileName = asString(firstPresent(expectedMap, "fileName", "file_name"));
        if (fileName == null || fileName.trim().isEmpty()) {
            String rawPath = String.valueOf(fileRef).trim().replace("\\", "/");
            int idx = rawPath.lastIndexOf('/');
            fileName = idx >= 0 ? rawPath.substring(idx + 1) : rawPath;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", isPpt ? "pptx" : "html");
        result.put("fileName", fileName);
        result.put("url", url);
        String text = asString(firstPresent(expectedMap, "text", "content"));
        if (text == null || text.trim().isEmpty()) {
            text = expectedAnswer;
        }
        if (text != null && !text.trim().isEmpty()) {
            result.put("text", text.trim());
        }
        return result;
    }

    private boolean looksLikeSingleSample(Map<String, Object> map) {
        return firstPresent(map, "question", "prompt", "query", "input", "messages") != null;
    }

    private boolean isValidItem(Map<String, Object> item) {
        if (item == null) {
            return false;
        }
        String question = asString(item.get("question"));
        if (question != null && !question.trim().isEmpty()) {
            return true;
        }
        Object images = item.get("images");
        if (images instanceof Collection<?> && !((Collection<?>) images).isEmpty()) {
            return true;
        }
        Object files = item.get("files");
        return files instanceof Collection<?> && !((Collection<?>) files).isEmpty();
    }

    private String extractTextFromInput(Object inputObj) {
        if (inputObj == null) {
            return null;
        }
        if (inputObj instanceof String) {
            return (String) inputObj;
        }
        if (inputObj instanceof Map<?, ?>) {
            Map<String, Object> inputMap = castToStringObjectMap((Map<?, ?>) inputObj);
            return firstNonBlank(
                    asString(firstPresent(inputMap, "text", "question", "prompt")),
                    extractTextFromContent(firstPresent(inputMap, "content")),
                    extractQuestionFromMessages(firstPresent(inputMap, "messages"))
            );
        }
        return null;
    }

    private String extractExpectedText(Object expectedObj) {
        if (expectedObj == null) {
            return null;
        }
        if (expectedObj instanceof String || expectedObj instanceof Number || expectedObj instanceof Boolean) {
            return expectedObj.toString();
        }
        if (expectedObj instanceof Map<?, ?>) {
            Map<String, Object> expectedMap = castToStringObjectMap((Map<?, ?>) expectedObj);
            return firstNonBlank(
                    asString(firstPresent(expectedMap, "text", "value", "answer")),
                    extractTextFromContent(firstPresent(expectedMap, "content"))
            );
        }
        return null;
    }

    private String extractQuestionFromMessages(Object messagesObj) {
        if (!(messagesObj instanceof List<?>)) {
            return null;
        }

        List<?> messages = (List<?>) messagesObj;
        String lastUserContent = null;
        for (Object msgObj : messages) {
            if (!(msgObj instanceof Map<?, ?>)) {
                continue;
            }
            Map<String, Object> msg = castToStringObjectMap((Map<?, ?>) msgObj);
            String role = asString(firstPresent(msg, "role"));
            if (role != null && !role.trim().isEmpty() && !"user".equalsIgnoreCase(role.trim())) {
                continue;
            }

            String text = firstNonBlank(
                    extractTextFromContent(firstPresent(msg, "content", "parts")),
                    asString(firstPresent(msg, "text", "value"))
            );
            if (text != null && !text.trim().isEmpty()) {
                lastUserContent = text.trim();
            }
        }
        return lastUserContent;
    }

    private String extractTextFromContent(Object contentObj) {
        if (contentObj == null) {
            return null;
        }
        if (contentObj instanceof String || contentObj instanceof Number || contentObj instanceof Boolean) {
            return contentObj.toString();
        }

        if (contentObj instanceof List<?>) {
            StringBuilder sb = new StringBuilder();
            for (Object partObj : (List<?>) contentObj) {
                if (partObj instanceof String || partObj instanceof Number || partObj instanceof Boolean) {
                    appendLine(sb, partObj.toString());
                } else if (partObj instanceof Map<?, ?>) {
                    Map<String, Object> part = castToStringObjectMap((Map<?, ?>) partObj);

                    // 兼容 messages[] 结构里的单个 message
                    if (part.containsKey("role") && part.containsKey("content")) {
                        appendLine(sb, extractTextFromContent(firstPresent(part, "content", "parts")));
                        continue;
                    }

                    String type = asString(firstPresent(part, "type"));
                    String normalizedType = type == null ? "" : type.toLowerCase(Locale.ROOT);
                    Object textObj = firstPresent(part, "text", "content", "value");

                    if (textObj instanceof String || textObj instanceof Number || textObj instanceof Boolean) {
                        String text = textObj.toString();
                        if (text != null && !text.trim().isEmpty()) {
                            if (normalizedType.isEmpty() || normalizedType.contains("text")) {
                                appendLine(sb, text);
                            }
                        }
                    } else if (textObj instanceof List<?> || textObj instanceof Map<?, ?>) {
                        appendLine(sb, extractTextFromContent(textObj));
                    }
                }
            }
            return sb.length() == 0 ? null : sb.toString();
        }

        if (contentObj instanceof Map<?, ?>) {
            Map<String, Object> contentMap = castToStringObjectMap((Map<?, ?>) contentObj);
            Object textObj = firstPresent(contentMap, "text", "content", "value");
            if (textObj instanceof List<?> || textObj instanceof Map<?, ?>) {
                return extractTextFromContent(textObj);
            }
            return asString(textObj);
        }

        return null;
    }

    private void appendLine(StringBuilder sb, String text) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append("\n");
        }
        sb.append(text.trim());
    }

    private void collectMediaFromMessages(List<String> result, Object messagesObj,
            Map<String, String> pathToUrl, boolean image) {
        if (!(messagesObj instanceof List<?>)) {
            return;
        }
        for (Object msgObj : (List<?>) messagesObj) {
            if (!(msgObj instanceof Map<?, ?>)) {
                continue;
            }
            Map<String, Object> msg = castToStringObjectMap((Map<?, ?>) msgObj);
            collectMediaFromContentParts(result, firstPresent(msg, "content", "parts"), pathToUrl, image);
        }
    }

    private void collectMediaFromContentParts(List<String> result, Object contentObj,
            Map<String, String> pathToUrl, boolean image) {
        if (contentObj instanceof Map<?, ?>) {
            Map<String, Object> map = castToStringObjectMap((Map<?, ?>) contentObj);
            collectMediaFromContentParts(result, firstPresent(map, "content", "parts"), pathToUrl, image);
            return;
        }
        if (!(contentObj instanceof List<?>)) {
            return;
        }

        for (Object partObj : (List<?>) contentObj) {
            if (!(partObj instanceof Map<?, ?>)) {
                continue;
            }
            Map<String, Object> part = castToStringObjectMap((Map<?, ?>) partObj);

            // 兼容 messages[] 结构里的单个 message
            if (part.containsKey("role") && part.containsKey("content")) {
                collectMediaFromContentParts(result, firstPresent(part, "content", "parts"), pathToUrl, image);
                continue;
            }

            String type = asString(firstPresent(part, "type"));
            String normalizedType = type == null ? "" : type.toLowerCase(Locale.ROOT);
            boolean matched = image
                    ? normalizedType.contains("image") || normalizedType.contains("img") || normalizedType.contains("picture")
                    : normalizedType.contains("file") || normalizedType.contains("document") || normalizedType.contains("pdf");
            if (!matched) {
                continue;
            }

            Object mediaObj = image
                    ? firstPresent(part, "image_url", "imageUrl", "url", "uri", "href", "imageUrl", "source", "path", "file", "data")
                    : firstPresent(part, "file_url", "fileUrl", "url", "uri", "href", "source", "path", "file", "data");
            collectMediaFromRefs(result, mediaObj, pathToUrl, image);
        }
    }

    private void collectMediaFromRefs(List<String> result, Object value,
            Map<String, String> pathToUrl, boolean image) {
        if (value == null) {
            return;
        }

        if (value instanceof Collection<?>) {
            for (Object item : (Collection<?>) value) {
                collectMediaFromRefs(result, item, pathToUrl, image);
            }
            return;
        }

        if (value instanceof Map<?, ?>) {
            Map<String, Object> map = castToStringObjectMap((Map<?, ?>) value);
            Object direct = firstPresent(
                    map,
                    "value", "url", "href", "ref", "uri", "path", "filePath", "file_path",
                    "name", "fileName", "file_name", "data"
            );
            if (direct != null) {
                addUnique(result, resolveMediaRef(direct, pathToUrl));
            }

            Object source = firstPresent(map, "source");
            if (source != null) {
                collectMediaFromRefs(result, source, pathToUrl, image);
            }
            return;
        }

        addUnique(result, resolveMediaRef(value, pathToUrl));
    }

    private String resolveMediaRef(Object value, Map<String, String> pathToUrl) {
        if (value == null) {
            return null;
        }
        String raw = value.toString().trim();
        if (raw.isEmpty()) {
            return null;
        }

        if (isExternalOrApiUrl(raw) || raw.startsWith("data:")) {
            return raw;
        }

        String normalized = normalizePath(raw);
        String withoutLeadingSlash = normalized.startsWith("/") ? normalized.substring(1) : normalized;
        if (pathToUrl.containsKey(normalized)) {
            return pathToUrl.get(normalized);
        }
        if (pathToUrl.containsKey(withoutLeadingSlash)) {
            return pathToUrl.get(withoutLeadingSlash);
        }

        String compact = withoutLeadingSlash.replaceAll("\\s+", "");
        if (pathToUrl.containsKey(compact)) {
            return pathToUrl.get(compact);
        }

        String fileName = Paths.get(withoutLeadingSlash).getFileName().toString();
        if (pathToUrl.containsKey(fileName)) {
            return pathToUrl.get(fileName);
        }
        String compactFileName = fileName.replaceAll("\\s+", "");
        if (pathToUrl.containsKey(compactFileName)) {
            return pathToUrl.get(compactFileName);
        }
        return raw;
    }

    private boolean isExternalOrApiUrl(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://")
                || lower.startsWith("https://")
                || lower.startsWith("/api/")
                || lower.startsWith("api/");
    }

    private void addUnique(List<String> list, String value) {
        if (value == null || value.trim().isEmpty()) {
            return;
        }

        String trimmed = value.trim();
        if (!list.contains(trimmed)) {
            list.add(trimmed);
        }
    }

    private Object firstPresent(Map<String, Object> map, Collection<String> keys) {
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

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }

    private Map<String, Object> castToStringObjectMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() != null) {
                result.put(entry.getKey().toString(), entry.getValue());
            }
        }
        return result;
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
        if (text.contains("file") || text.contains("document") || text.contains("pdf")) {
            result.add("file");
            return;
        }
        if (text.contains("text")) {
            result.add("text");
            return;
        }
        result.add(text);
    }

    private void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (map == null || key == null || value == null) {
            return;
        }
        map.putIfAbsent(key, value);
    }

    // ========================================================================
    // Word 解析
    // ========================================================================

    /** 解析 .docx 文件 */
    private List<Map<String, Object>> parseDocx(MultipartFile file) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(file.getInputStream())) {
            // 优先从表格中提取结构化数据（图片直接从单元格提取）
            List<Map<String, Object>> items = extractFromWordTables(doc);
            if (!items.isEmpty()) {
                return items;
            }

            // 兜底：逐段落解析，保留图片与所属 Q/A 区域的关联
            return parseDocxByParagraphs(doc);
        }
    }

    /**
     * 逐段解析 docx 文档，按【用户提问】/【预期回答】标记分段。
     * 同时收集文档段落和表格内段落，覆盖各种文档结构。
     */
    private List<Map<String, Object>> parseDocxByParagraphs(XWPFDocument doc) throws Exception {
        Map<String, String> relIdToPath = saveAllImagesWithRelMapping(doc, null);

        // 收集所有段落：文档正文 + 表格单元格中的段落
        List<XWPFParagraph> allParagraphs = new ArrayList<>();
        allParagraphs.addAll(doc.getParagraphs());
        for (XWPFTable table : doc.getTables()) {
            for (XWPFTableRow row : table.getRows()) {
                for (XWPFTableCell cell : row.getTableCells()) {
                    allParagraphs.addAll(cell.getParagraphs());
                }
            }
        }

        List<Map<String, Object>> items = new ArrayList<>();
        String currentQuestion = null;
        StringBuilder currentAnswer = null;
        List<String> currentQuestionImages = new ArrayList<>();
        boolean inAnswer = false;

        for (XWPFParagraph para : allParagraphs) {
            String text = para.getText();
            if (text == null) {
                text = "";
            }
            text = text.trim();

            // 检测【用户提问】标记
            String qValue = extractMarkerValue(text, "用户提问", "提问", "问题");
            if (qValue != null) {
                // 保存上一组 Q&A
                if (currentQuestion != null && !currentQuestion.isEmpty()) {
                    String answer = currentAnswer != null ? currentAnswer.toString().trim() : null;
                    items.add(buildItem(currentQuestion, answer, currentQuestionImages, items.size()));
                }
                currentQuestion = qValue;
                currentAnswer = null;
                currentQuestionImages = new ArrayList<>();
                inAnswer = false;
                continue;
            }

            // 检测【预期回答】标记
            String aValue = extractMarkerValue(text, "预期回答", "回答", "答案");
            if (aValue != null && currentQuestion != null) {
                currentAnswer = new StringBuilder();
                inAnswer = true;
                if (!aValue.isEmpty()) {
                    currentAnswer.append(aValue);
                }
                appendParagraphImages(para, relIdToPath, currentAnswer);
                continue;
            }

            // 跳过空段落（无文字也无图片）
            if (text.isEmpty() && !paragraphHasImages(para, relIdToPath)) {
                continue;
            }

            if (inAnswer && currentAnswer != null) {
                // 在预期回答区域：文字和图片都归入答案
                if (currentAnswer.length() > 0 && !text.isEmpty()) {
                    currentAnswer.append("\n");
                }
                if (!text.isEmpty()) {
                    currentAnswer.append(text);
                }
                appendParagraphImages(para, relIdToPath, currentAnswer);
            } else if (currentQuestion != null) {
                // 在用户提问区域
                if (currentQuestion.isEmpty()) {
                    currentQuestion = text;
                } else if (!text.isEmpty()) {
                    currentQuestion += "\n" + text;
                }

                // 提问区域的图片加入图片列表
                for (String relId : findImageRelsInParagraph(para)) {
                    String path = relIdToPath.get(relId);
                    if (path != null && !currentQuestionImages.contains(path)) {
                        currentQuestionImages.add(path);
                    }
                }
            }
        }

        // 保存最后一组
        if (currentQuestion != null && !currentQuestion.isEmpty()) {
            String answer = currentAnswer != null ? currentAnswer.toString().trim() : null;
            items.add(buildItem(currentQuestion, answer, currentQuestionImages, items.size()));
        }
        return items;
    }

    /** 将段落中的图片以 markdown 格式追加到 StringBuilder */
    private void appendParagraphImages(XWPFParagraph para, Map<String, String> relIdToPath,
            StringBuilder sb) {
        Set<String> seen = new HashSet<>();
        for (XWPFRun run : para.getRuns()) {
            String runXml = run.getCTR().xmlText();
            Matcher matcher = REL_EMBED_PATTERN.matcher(runXml);
            while (matcher.find()) {
                String relId = matcher.group(1);
                if (seen.add(relId)) {
                    String path = relIdToPath.get(relId);
                    if (path != null) {
                        sb.append("\n[图片](").append(path).append(")\n");
                    }
                }
            }
        }
    }

    /** 检查段落是否包含图片 */
    private boolean paragraphHasImages(XWPFParagraph para, Map<String, String> relIdToPath) {
        for (XWPFRun run : para.getRuns()) {
            String runXml = run.getCTR().xmlText();
            Matcher matcher = REL_EMBED_PATTERN.matcher(runXml);
            while (matcher.find()) {
                if (relIdToPath.containsKey(matcher.group(1))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 从段落所有 run 中提取图片关系 ID */
    private List<String> findImageRelsInParagraph(XWPFParagraph para) {
        List<String> relIds = new ArrayList<>();
        for (XWPFRun run : para.getRuns()) {
            String runXml = run.getCTR().xmlText();
            Matcher matcher = REL_EMBED_PATTERN.matcher(runXml);
            while (matcher.find()) {
                String relId = matcher.group(1);
                if (!relIds.contains(relId)) {
                    relIds.add(relId);
                }
            }
        }
        return relIds;
    }

    /** 解析 .doc 文件 */
    private List<Map<String, Object>> parseDoc(MultipartFile file) throws IOException {
        try (HWPFDocument doc = new HWPFDocument(file.getInputStream())) {
            WordExtractor extractor = new WordExtractor(doc);
            String content = extractor.getText();
            return parseTextContent(content, Collections.emptyList());
        }
    }

    /**
     * 从 Word 表格提取数据
     * 使用关系 ID（r:embed）精确映射图片到对应单元格，避免全局索引或 getEmbeddedPictures() 导致的图片归属错误。
     */
    private List<Map<String, Object>> extractFromWordTables(XWPFDocument doc) throws Exception {
        List<Map<String, Object>> items = new ArrayList<>();

        // 预先保存所有图片到 MinIO，建立关系 ID -> 访问路径映射
        Map<String, String> relIdToPath = saveAllImagesWithRelMapping(doc, null);

        for (XWPFTable table : doc.getTables()) {
            List<XWPFTableRow> rows = table.getRows();
            if (rows.size() < 2) {
                continue;
            }

            // 检测表头
            XWPFTableRow headerRow = rows.get(0);
            int questionCol = -1;
            int answerCol = -1;
            for (int j = 0; j < headerRow.getTableCells().size(); j++) {
                String header = normalizeHeader(getCellText(headerRow.getTableCells().get(j)));
                if (questionCol < 0 && QUESTION_HEADERS.contains(header)) {
                    questionCol = j;
                } else if (answerCol < 0 && ANSWER_HEADERS.contains(header)) {
                    answerCol = j;
                }
            }
            if (questionCol < 0) {
                continue;
            }

            // 逐行提取数据
            for (int i = 1; i < rows.size(); i++) {
                List<XWPFTableCell> cells = rows.get(i).getTableCells();
                String question = questionCol < cells.size() ? getCellText(cells.get(questionCol)) : "";
                if (question.trim().isEmpty()) {
                    continue;
                }

                // 从提问单元格 XML 中提取图片
                List<String> itemImages = new ArrayList<>();
                if (questionCol < cells.size()) {
                    for (String relId : findImageRelsInCell(cells.get(questionCol))) {
                        String path = relIdToPath.get(relId);
                        if (path != null && !itemImages.contains(path)) {
                            itemImages.add(path);
                        }
                    }
                }

                // 从预期回答单元格提取富文本（文字 + 内嵌图片）
                String answer = null;
                if (answerCol >= 0 && answerCol < cells.size()) {
                    answer = getCellRichContent(cells.get(answerCol), relIdToPath);
                }

                items.add(buildItem(
                        question.trim(),
                        answer != null && !answer.trim().isEmpty() ? answer.trim() : null,
                        itemImages,
                        items.size()
                ));
            }
        }
        return items;
    }

    /** 保存文档中所有图片到 MinIO，建立关系 ID -> 访问路径映射 */
    private Map<String, String> saveAllImagesWithRelMapping(XWPFDocument doc, File imageDir)
            throws Exception {
        Map<String, String> relIdToPath = new HashMap<>();

        // 建立文件名 -> 图片数据映射
        Map<String, XWPFPictureData> fileNameToPic = new HashMap<>();
        for (XWPFPictureData pic : doc.getAllPictures()) {
            String partName = pic.getPackagePart().getPartName().getName();
            String fileName = partName.substring(partName.lastIndexOf('/') + 1);
            fileNameToPic.put(fileName, pic);
        }

        // 遍历文档关系，为每个图片关系建立 relId -> MinIO URL
        int imgCount = 0;
        for (PackageRelationship rel : doc.getPackagePart().getRelationships()) {
            if (!rel.getRelationshipType().contains("image")) {
                continue;
            }
            String target = rel.getTargetURI().toString();
            String targetFileName = target.substring(target.lastIndexOf('/') + 1);
            XWPFPictureData pic = fileNameToPic.get(targetFileName);
            if (pic == null) {
                continue;
            }

            String ext = pic.suggestFileExtension();
            String imageName = System.currentTimeMillis() + "_" + (imgCount++) + "." + ext;
            String objectName = IMAGE_DIR + imageName;
            minioStorageService.upload(objectName, pic.getData(), contentTypeByFileName(imageName));
            relIdToPath.put(rel.getId(), minioStorageService.getUrl(objectName));

            if (imageDir != null) {
                Files.createDirectories(imageDir.toPath());
                Files.write(imageDir.toPath().resolve(imageName), pic.getData());
            }
        }
        return relIdToPath;
    }

    /** 从单元格 XML 中提取所有图片关系 ID（支持内嵌、浮动、VML 等图片类型） */
    private List<String> findImageRelsInCell(XWPFTableCell cell) {
        List<String> relIds = new ArrayList<>();
        String xml = cell.getCTTc().xmlText();
        Matcher matcher = REL_EMBED_PATTERN.matcher(xml);
        while (matcher.find()) {
            String relId = matcher.group(1);
            if (!relIds.contains(relId)) {
                relIds.add(relId);
            }
        }
        return relIds;
    }

    /** 提取 Word 单元格文本 */
    private String getCellText(XWPFTableCell cell) {
        StringBuilder sb = new StringBuilder();
        for (XWPFParagraph para : cell.getParagraphs()) {
            String text = para.getText();
            if (text != null && !text.trim().isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(" ");
                }
                sb.append(text.trim());
            }
        }
        return sb.toString();
    }

    /**
     * 提取单元格富文本内容（文字 + 内嵌图片）
     * 逐 run 遍历保持文字与图片的先后顺序，通过 run 中的 r:embed 关系 ID 查找图片 URL。
     */
    private String getCellRichContent(XWPFTableCell cell, Map<String, String> relIdToPath) {
        StringBuilder sb = new StringBuilder();
        for (XWPFParagraph para : cell.getParagraphs()) {
            StringBuilder paraSb = new StringBuilder();
            for (XWPFRun run : para.getRuns()) {
                String text = run.getText(0);
                if (text != null) {
                    paraSb.append(text);
                }

                String runXml = run.getCTR().xmlText();
                Matcher matcher = REL_EMBED_PATTERN.matcher(runXml);
                Set<String> seen = new HashSet<>();
                while (matcher.find()) {
                    String relId = matcher.group(1);
                    if (seen.add(relId)) {
                        String path = relIdToPath.get(relId);
                        if (path != null) {
                            paraSb.append("\n[图片](").append(path).append(")");
                        }
                    }
                }
            }

            String paraText = paraSb.toString().trim();
            if (!paraText.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append(paraText);
            }
        }
        return sb.toString();
    }

    // ========================================================================
    // Excel 解析
    // ========================================================================

    /** 解析 .xlsx 文件 */
    private List<Map<String, Object>> parseXlsx(MultipartFile file) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(file.getInputStream())) {
            XSSFSheet sheet = workbook.getSheetAt(0);
            Map<String, List<String>> cellImages = extractImagesFromXlsx(sheet);
            return parseExcelSheet(sheet, cellImages);
        }
    }

    /** 解析 .xls 文件 */
    private List<Map<String, Object>> parseXls(MultipartFile file) throws Exception {
        try (HSSFWorkbook workbook = new HSSFWorkbook(file.getInputStream())) {
            HSSFSheet sheet = workbook.getSheetAt(0);
            Map<String, List<String>> cellImages = extractImagesFromXls(sheet);
            return parseExcelSheet(sheet, cellImages);
        }
    }

    /** 通用 Excel Sheet 解析逻辑 */
    private List<Map<String, Object>> parseExcelSheet(Sheet sheet,
            Map<String, List<String>> cellImages) {
        List<Map<String, Object>> items = new ArrayList<>();
        Row headerRow = sheet.getRow(0);
        if (headerRow == null) {
            return items;
        }

        int questionCol = -1;
        int answerCol = -1;
        for (int j = 0; j < headerRow.getLastCellNum(); j++) {
            Cell cell = headerRow.getCell(j);
            if (cell == null) {
                continue;
            }
            String header = normalizeHeader(getCellStringValue(cell));
            if (questionCol < 0 && QUESTION_HEADERS.contains(header)) {
                questionCol = j;
            } else if (answerCol < 0 && ANSWER_HEADERS.contains(header)) {
                answerCol = j;
            }
        }

        if (questionCol < 0) {
            throw new IllegalArgumentException("在表头中未找到【用户提问】列，请确保表头包含以下关键词之一：用户提问、提问、问题");
        }

        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null) {
                continue;
            }

            Cell qCell = row.getCell(questionCol);
            String question = qCell != null ? getCellStringValue(qCell) : "";
            if (question.trim().isEmpty()) {
                continue;
            }

            String answer = null;
            if (answerCol >= 0) {
                Cell aCell = row.getCell(answerCol);
                answer = aCell != null ? getCellStringValue(aCell) : null;
            }

            // 提问列图片
            List<String> images = cellImages.getOrDefault(i + "_" + questionCol, Collections.emptyList());

            // 预期回答图片 -> 以 markdown 图片语法嵌入 answer 文本
            if (answerCol >= 0) {
                List<String> answerImages = cellImages.getOrDefault(i + "_" + answerCol, Collections.emptyList());
                if (!answerImages.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    if (answer != null && !answer.trim().isEmpty()) {
                        sb.append(answer.trim());
                    }
                    for (String img : answerImages) {
                        if (sb.length() > 0) {
                            sb.append("\n");
                        }
                        sb.append("[图片](").append(img).append(")");
                    }
                    answer = sb.toString();
                }
            }

            items.add(buildItem(
                    question.trim(),
                    answer != null && !answer.trim().isEmpty() ? answer.trim() : null,
                    new ArrayList<>(images),
                    items.size()
            ));
        }
        return items;
    }

    /** 获取 Excel 单元格的字符串值 */
    private String getCellStringValue(Cell cell) {
        if (cell == null) {
            return "";
        }
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getLocalDateTimeCellValue().toString();
                }
                double num = cell.getNumericCellValue();
                if (num == Math.floor(num) && Double.isFinite(num)) {
                    return String.valueOf((long) num);
                }
                return String.valueOf(num);
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                try {
                    return cell.getStringCellValue();
                } catch (Exception e) {
                    try {
                        return String.valueOf(cell.getNumericCellValue());
                    } catch (Exception ignored) {
                        return "";
                    }
                }
            default:
                return "";
        }
    }

    /** 从 .xlsx 中提取图片并上传到 MinIO，按行_列建立映射 */
    private Map<String, List<String>> extractImagesFromXlsx(XSSFSheet sheet) throws Exception {
        Map<String, List<String>> cellImages = new HashMap<>();
        XSSFDrawing drawing = sheet.getDrawingPatriarch();
        if (drawing == null) {
            return cellImages;
        }

        int imgCount = 0;
        for (XSSFShape shape : drawing.getShapes()) {
            if (shape instanceof XSSFPicture) {
                XSSFPicture picture = (XSSFPicture) shape;
                XSSFClientAnchor anchor = (XSSFClientAnchor) picture.getAnchor();
                int row = anchor.getRow1();
                int col = anchor.getCol1();

                XSSFPictureData picData = picture.getPictureData();
                String ext = picData.suggestFileExtension();
                String imageName = System.currentTimeMillis() + "_r" + row + "_c" + col + "_" + (imgCount++) + "." + ext;
                String objectName = "images/" + imageName;
                minioStorageService.upload(objectName, picData.getData(), contentTypeByFileName(imageName));

                String key = row + "_" + col;
                cellImages.computeIfAbsent(key, k -> new ArrayList<>()).add(minioStorageService.getUrl("images/" + imageName));
            }
        }
        return cellImages;
    }

    /** 从 .xls 中提取图片并上传到 MinIO，按行_列建立映射 */
    private Map<String, List<String>> extractImagesFromXls(HSSFSheet sheet) throws Exception {
        Map<String, List<String>> cellImages = new HashMap<>();
        HSSFPatriarch patriarch = sheet.getDrawingPatriarch();
        if (patriarch == null) {
            return cellImages;
        }

        int imgCount = 0;
        for (HSSFShape shape : patriarch.getChildren()) {
            if (shape instanceof HSSFPicture) {
                HSSFPicture picture = (HSSFPicture) shape;
                HSSFClientAnchor anchor = (HSSFClientAnchor) picture.getAnchor();
                int row = anchor.getRow1();
                int col = anchor.getCol1();

                HSSFPictureData picData = picture.getPictureData();
                String ext = picData.suggestFileExtension();
                String imageName = System.currentTimeMillis() + "_r" + row + "_c" + col + "_" + (imgCount++) + "." + ext;
                String objectName = "images/" + imageName;
                minioStorageService.upload(objectName, picData.getData(), contentTypeByFileName(imageName));

                String key = row + "_" + col;
                cellImages.computeIfAbsent(key, k -> new ArrayList<>()).add(minioStorageService.getUrl("images/" + imageName));
            }
        }
        return cellImages;
    }

    // ========================================================================
    // 文本内容解析
    // ========================================================================

    /** 解析纯文本内容，按“用户提问 / 预期回答”标记拆分 */
    private List<Map<String, Object>> parseTextContent(String content, List<String> images) {
        List<Map<String, Object>> items = new ArrayList<>();
        content = content.replace("\r\n", "\n").replace("\r", "\n");
        String[] lines = content.split("\n");

        String currentQuestion = null;
        String currentAnswer = null;
        int imageIndex = 0;

        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty()) {
                continue;
            }

            String qValue = extractMarkerValue(t, "用户提问", "提问", "问题");
            if (qValue != null) {
                // 保存上一对
                if (currentQuestion != null) {
                    List<String> itemImages = imageIndex < images.size()
                            ? Collections.singletonList(images.get(imageIndex++))
                            : Collections.emptyList();
                    items.add(buildItem(currentQuestion, currentAnswer, itemImages, items.size()));
                }
                currentQuestion = qValue;
                currentAnswer = null;
                continue;
            }

            String aValue = extractMarkerValue(t, "预期回答", "回答", "答案");
            if (aValue != null && currentQuestion != null) {
                currentAnswer = aValue;
                continue;
            }

            // 普通行追加
            if (currentAnswer != null) {
                currentAnswer += "\n" + t;
            } else if (currentQuestion != null) {
                currentQuestion += "\n" + t;
            }
        }

        // 保存最后一对
        if (currentQuestion != null) {
            List<String> itemImages = imageIndex < images.size()
                    ? Collections.singletonList(images.get(imageIndex))
                    : Collections.emptyList();
            items.add(buildItem(currentQuestion, currentAnswer, itemImages, items.size()));
        }
        return items;
    }

    /**
     * 从文本行中提取标记值，支持多种格式：
     * - “用户提问：xxx” -> xxx
     * - “用户提问: xxx” -> xxx
     * - “【用户提问】xxx” -> xxx
     * - “【用户提问】” -> ""（内容在后续行）
     */
    private String extractMarkerValue(String line, String... markers) {
        for (String marker : markers) {
            // 格式：marker：value 或 marker: value
            if (line.startsWith(marker + "：") || line.startsWith(marker + ":")) {
                return line.substring(marker.length() + 1).trim();
            }

            // 格式：【marker】 value 或 【marker】（独立行）
            String bracketed = "【" + marker + "】";
            if (line.startsWith(bracketed)) {
                return line.substring(bracketed.length()).trim();
            }
        }
        return null;
    }

    /** 构造单条数据项 */
    private Map<String, Object> buildItem(String question, String answer,
            List<String> images, int index) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("id", "q-parsed-" + System.currentTimeMillis() + "-" + index);
        raw.put("question", question);
        raw.put("expectedAnswer", answer);
        raw.put("images", images == null ? Collections.emptyList() : images);
        return normalizeSample(raw, Collections.emptyMap(), index);
    }

    // ========================================================================
    // 文件保存
    // ========================================================================

    /** 保存上传文件到 MinIO */
    public String saveFile(MultipartFile file) throws Exception {
        String fileName = UPLOAD_DIR + System.currentTimeMillis() + "_" + sanitizeFileName(file.getOriginalFilename());
        minioStorageService.upload(fileName, file.getInputStream(), file.getSize(), contentTypeByFileName(fileName));
        return minioStorageService.getUrl(fileName);
    }

    // ========================================================================
    // 通用工具方法
    // ========================================================================

    private String getFileExtension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dotIndex + 1).trim().toLowerCase(Locale.ROOT);
    }

    private boolean isImageFile(String fileName) {
        String ext = getFileExtension(fileName);
        return "png".equals(ext)
                || "jpg".equals(ext)
                || "jpeg".equals(ext)
                || "gif".equals(ext)
                || "bmp".equals(ext)
                || "webp".equals(ext);
    }

    private boolean isSupportedQuestionFile(String fileName) {
        String ext = getFileExtension(fileName);
        return "pdf".equals(ext)
                || "doc".equals(ext)
                || "docx".equals(ext)
                || "xls".equals(ext)
                || "xlsx".equals(ext)
                || "ppt".equals(ext)
                || "pptx".equals(ext)
                || "txt".equals(ext)
                || "md".equals(ext)
                || "html".equals(ext)
                || "htm".equals(ext)
                || "csv".equals(ext)
                || "json".equals(ext)
                || "jsonl".equals(ext)
                || "ndjson".equals(ext);
    }

    private String contentTypeByFileName(String fileName) {
        String ext = getFileExtension(fileName);
        switch (ext) {
            case "png":
                return "image/png";
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "gif":
                return "image/gif";
            case "bmp":
                return "image/bmp";
            case "webp":
                return "image/webp";
            case "pdf":
                return "application/pdf";
            case "doc":
                return "application/msword";
            case "docx":
                return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xls":
                return "application/vnd.ms-excel";
            case "xlsx":
                return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "ppt":
                return "application/vnd.ms-powerpoint";
            case "pptx":
                return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "txt":
                return "text/plain";
            case "md":
                return "text/markdown";
            case "html":
            case "htm":
                return "text/html";
            case "csv":
                return "text/csv";
            case "json":
                return "application/json";
            case "jsonl":
                return "application/x-ndjson";
            case "ndjson":
                return "application/x-ndjson";
            default:
                return "application/octet-stream";
        }
    }

    private String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            return "file";
        }
        String sanitized = fileName.trim()
                .replace("\\", "_")
                .replace("/", "_")
                .replace(":", "_")
                .replace("*", "_")
                .replace("?", "_")
                .replace("\"", "_")
                .replace("<", "_")
                .replace(">", "_")
                .replace("|", "_");
        return sanitized.replaceAll("\\s+", "_");
    }

    private String normalizePath(String path) {
        if (path == null) {
            return "";
        }
        String normalized = path.trim().replace("\\", "/");
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    private String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private void deleteDirectoryQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception ignored) {
                            // ignore
                        }
                    });
        } catch (Exception ignored) {
            // ignore
        }
    }

    private void deleteDirectoryContentsQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                    .filter(path -> !path.equals(dir))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception ignored) {
                            // ignore
                        }
                    });
        } catch (Exception ignored) {
            // ignore
        }
    }
}
