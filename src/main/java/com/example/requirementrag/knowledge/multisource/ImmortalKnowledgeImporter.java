package com.example.requirementrag.knowledge.multisource;

import com.example.requirementrag.conflict.KnowledgeConflictModels.Authority;
import com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType;
import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.KnowledgeClaimRecord;
import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.KnowledgeDocument;
import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.KnowledgeDocumentVersion;
import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.KnowledgeEvidence;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.DoubtClaim;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.ParameterClaim;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.TestCaseClaim;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Immortal 知识导入编排器：把 document/immortal 下的 prd/data/qa/case 导入
 * 到多源知识主库（catalog + 业务表 + 统一 Claim + Evidence）。
 *
 * <p>纯 Java 可独立运行（不依赖 Spring 上下文）。PRD 生成轻量 REQUIREMENT Claim；
 * 全量需求图/向量索引仍走现有标准链路。
 */
public class ImmortalKnowledgeImporter {

    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private final MultiSourceKnowledgeStore store;
    private final ConfigTableLoader configLoader;
    private final XlsxTestCaseLoader caseLoader;
    private final DoubtClaimParser doubtParser;

    public ImmortalKnowledgeImporter(MultiSourceKnowledgeStore store) {
        this(store, new ConfigTableLoader(), new XlsxTestCaseLoader(), new DoubtClaimParser());
    }

    public ImmortalKnowledgeImporter(MultiSourceKnowledgeStore store, ConfigTableLoader configLoader,
                                     XlsxTestCaseLoader caseLoader, DoubtClaimParser doubtParser) {
        this.store = store;
        this.configLoader = configLoader;
        this.caseLoader = caseLoader;
        this.doubtParser = doubtParser;
    }

    /** 导入结果汇总。 */
    public record ImportSummary(int documents, int versions, int evidences,
                                int parameters, int doubts, int testCases, int requirementClaims) {
        @Override
        public String toString() {
            return "documents=" + documents + ", versions=" + versions + ", evidences=" + evidences
                    + ", parameters=" + parameters + ", doubts=" + doubts + ", testCases=" + testCases
                    + ", requirementClaims=" + requirementClaims;
        }
    }

    /** 导入全部四类知识目录。 */
    public ImportSummary importAll(String projectId, String businessVersion, Path root) throws IOException {
        int documents = 0;
        int versions = 0;
        int evidences = 0;
        int parameters = 0;
        int doubts = 0;
        int testCases = 0;
        int requirementClaims = 0;

        Path caseDir = root.resolve("immortal-case");
        for (Path file : listXlsx(caseDir)) {
            Imported imported = importTestCases(projectId, businessVersion, file);
            documents += imported.documents();
            versions += imported.versions();
            evidences += imported.evidences();
            testCases += imported.claims();
        }

        Path dataDir = root.resolve("immortal-data");
        for (Path file : listXlsx(dataDir)) {
            Imported imported = importParameters(projectId, businessVersion, file);
            documents += imported.documents();
            versions += imported.versions();
            evidences += imported.evidences();
            parameters += imported.claims();
        }

        Path qaDir = root.resolve("immortal-qa");
        for (Path file : listXlsx(qaDir)) {
            Imported imported = importDoubts(projectId, businessVersion, file);
            documents += imported.documents();
            versions += imported.versions();
            evidences += imported.evidences();
            doubts += imported.claims();
        }

        Path prdDir = root.resolve("immortal-prd-test").resolve("封神");
        if (Files.isDirectory(prdDir)) {
            List<Path> html = listFiles(prdDir, ".html");
            for (Path file : html) {
                Imported imported = importRequirementHtml(projectId, businessVersion, file);
                documents += imported.documents();
                versions += imported.versions();
                evidences += imported.evidences();
                requirementClaims += imported.claims();
            }
        }

        return new ImportSummary(documents, versions, evidences, parameters, doubts, testCases, requirementClaims);
    }

    private static final String PARSER_VERSION = "immortal";

    /** 单文件注册结果：skipped=true 表示该内容已存在（缓存命中）。 */
    private record Registration(String dvId, boolean skipped) {
    }

    private Imported importTestCases(String projectId, String businessVersion, Path file) throws IOException {
        String docId = docId(projectId, "case", file);
        Registration registration = registerIfAbsent(projectId, businessVersion, SourceType.TEST_CASE,
                Authority.SECONDARY, "case", docId, file);
        if (registration.skipped()) {
            return Imported.empty();
        }
        String dvId = registration.dvId();
        List<TestCaseClaim> claims = caseLoader.parse(file, projectId, businessVersion);
        if (claims.isEmpty()) {
            return Imported.empty();
        }
        store.saveTestCases(projectId, businessVersion, claims);
        Map<String, String> evidenceMap = new LinkedHashMap<>();
        java.util.Set<String> claimIds = new java.util.LinkedHashSet<>();
        for (TestCaseClaim claim : claims) {
            String excerpt = claim.expectedResult() == null ? claim.title() : claim.expectedResult();
            evidenceMap.put(claim.claimId(), saveEvidence(dvId, projectId, SourceType.TEST_CASE,
                    claim.evidenceLocation(), excerpt));
            claimIds.add(claim.claimId());
        }
        store.syncClaims(projectId, businessVersion, dvId, evidenceMap, claimIds);
        return Imported.of(dvId, claims.size(), evidenceMap.size());
    }

    private Imported importParameters(String projectId, String businessVersion, Path file) throws IOException {
        String docId = docId(projectId, "data", file);
        Registration registration = registerIfAbsent(projectId, businessVersion, SourceType.PARAMETER_TABLE,
                Authority.PRIMARY, "data", docId, file);
        if (registration.skipped()) {
            return Imported.empty();
        }
        String dvId = registration.dvId();
        List<ParameterClaim> claims = configLoader.parse(file, projectId, businessVersion);
        if (claims.isEmpty()) {
            return Imported.empty();
        }
        store.saveParameters(projectId, businessVersion, claims);
        Map<String, String> evidenceMap = new LinkedHashMap<>();
        java.util.Set<String> claimIds = new java.util.LinkedHashSet<>();
        for (ParameterClaim claim : claims) {
            evidenceMap.put(claim.claimId(), saveEvidence(dvId, projectId, SourceType.PARAMETER_TABLE,
                    claim.evidenceLocation(), claim.normalizedValue() == null ? claim.rawValue() : claim.normalizedValue()));
            claimIds.add(claim.claimId());
        }
        store.syncClaims(projectId, businessVersion, dvId, evidenceMap, claimIds);
        return Imported.of(dvId, claims.size(), evidenceMap.size());
    }

    private Imported importDoubts(String projectId, String businessVersion, Path file) throws IOException {
        String docId = docId(projectId, "qa", file);
        Registration registration = registerIfAbsent(projectId, businessVersion, SourceType.DOUBT,
                Authority.PRIMARY, "qa", docId, file);
        if (registration.skipped()) {
            return Imported.empty();
        }
        String dvId = registration.dvId();
        List<XlsxTableReader.XlsxSheet> sheets = new XlsxTableReader().read(file);
        List<DoubtClaim> claims = new ArrayList<>();
        for (XlsxTableReader.XlsxSheet sheet : sheets) {
            List<String> headers = sheet.headers();
            Map<String, Integer> columns = doubtColumns(headers);
            if (columns.get("question") == null) {
                continue;
            }
            List<Map<String, String>> rows = sheet.rows();
            for (int index = 0; index < rows.size(); index++) {
                Map<String, String> row = rows.get(index);
                Map<String, String> mapped = new LinkedHashMap<>();
                mapped.put("question", value(row, columns.get("question")));
                if (columns.get("answer") != null) mapped.put("answer", value(row, columns.get("answer")));
                if (columns.get("owner") != null) mapped.put("owner", value(row, columns.get("owner")));
                if (columns.get("status") != null) mapped.put("status", value(row, columns.get("status")));
                if (mapped.get("question").isBlank()) continue;
                claims.add(doubtParser.parse(mapped, projectId, businessVersion, sheet.sheetName(), index + 1));
            }
        }
        if (claims.isEmpty()) {
            return Imported.empty();
        }
        store.saveDoubts(projectId, businessVersion, claims);
        Map<String, String> evidenceMap = new LinkedHashMap<>();
        java.util.Set<String> claimIds = new java.util.LinkedHashSet<>();
        for (DoubtClaim claim : claims) {
            String evidenceId = saveEvidence(dvId, projectId, SourceType.DOUBT, claim.evidenceLocation(), claim.question());
            evidenceMap.put(claim.doubtId(), evidenceId);
            claimIds.add(claim.doubtId());
        }
        store.syncClaims(projectId, businessVersion, dvId, evidenceMap, claimIds);
        return Imported.of(dvId, claims.size(), evidenceMap.size());
    }

    private Imported importRequirementHtml(String projectId, String businessVersion, Path file) throws IOException {
        String docId = docId(projectId, "prd", file);
        Registration registration = registerIfAbsent(projectId, businessVersion, SourceType.REQUIREMENT,
                Authority.PRIMARY, "prd", docId, file);
        if (registration.skipped()) {
            return Imported.empty();
        }
        String dvId = registration.dvId();
        String content = Files.readString(file, StandardCharsets.UTF_8);
        String title = extractTitle(content, file.getFileName().toString());
        String subject = file.getFileName().toString().replaceFirst("\\.html$", "");
        String locator = file.getFileName() + "#全文";
        String excerpt = title.substring(0, Math.min(title.length(), 200));
        String evidenceId = saveEvidence(dvId, projectId, SourceType.REQUIREMENT, locator, excerpt);
        String claimId = "req:" + sha256(projectId + "|" + businessVersion + "|" + subject).substring(0, 32);
        store.saveClaim(new KnowledgeClaimRecord(claimId, projectId, dvId, SourceType.REQUIREMENT,
                Authority.PRIMARY, KnowledgeFactKeyGenerator.generate(projectId, businessVersion, subject, subject, "document"),
                subject, "document", title, "TEXT", null, "SUPPORTED",
                null, null, null, "RULE", null, null, null));
        store.linkClaimEvidence(claimId, evidenceId, "SUPPORTS");
        return Imported.of(dvId, 1, 1);
    }

    /** 内容 hash 缓存：相同 (document, businessVersion, contentHash, parserVersion, extractionVersion) 直接跳过。 */
    private Registration registerIfAbsent(String projectId, String businessVersion, SourceType sourceType,
                                          Authority authority, String category, String docId, Path file) throws IOException {
        String logicalName = file.getFileName().toString().replaceFirst("\\.(xlsx|html)$", "");
        String contentHash = sha256(Files.readAllBytes(file));
        if (store.findDocumentVersion(docId, businessVersion, contentHash, PARSER_VERSION, "v1").isPresent()) {
            return new Registration("", true);
        }
        store.registerDocument(new KnowledgeDocument(docId, projectId, sourceType, logicalName,
                file.getFileName().toString(), file.toUri().toString(), authority, null));
        String dvId = "dv-" + projectId + "-" + businessVersion + "-" + category + "-" + logicalName + "-"
                + sha256(contentHash).substring(0, 8);
        store.upsertDocumentVersion(new KnowledgeDocumentVersion(dvId, docId, projectId, businessVersion,
                contentHash, PARSER_VERSION, "v1", null, "DRAFT", null, null));
        return new Registration(dvId, false);
    }

    private String saveEvidence(String dvId, String projectId, SourceType sourceType, String locator, String excerpt) {
        String excerptHash = sha256(excerpt == null ? "" : excerpt);
        String evidenceId = KnowledgeEvidenceIdGenerator.generate(projectId, dvId, locator, excerptHash);
        store.saveEvidence(new KnowledgeEvidence(evidenceId, dvId, projectId, sourceType,
                locator, excerpt == null ? "" : excerpt, excerptHash,
                null, null, null, null, null, null, null, null, null));
        return evidenceId;
    }

    private Map<String, Integer> doubtColumns(List<String> headers) {
        Map<String, Integer> columns = new LinkedHashMap<>();
        for (int index = 0; index < headers.size(); index++) {
            String header = headers.get(index).trim().toLowerCase(Locale.ROOT);
            String role = switch (header) {
                case "问题", "疑问", "question" -> "question";
                case "产品答疑", "产品解答", "解答", "答案", "answer" -> "answer";
                case "跟进人", "负责人", "owner" -> "owner";
                case "状态", "处理状态", "status" -> "status";
                default -> null;
            };
            if (role != null) columns.putIfAbsent(role, index);
        }
        return columns;
    }

    private String value(Map<String, String> row, Integer column) {
        if (column == null) return "";
        String value = row.get(column.toString());
        return value == null ? "" : value.trim();
    }

    private String extractTitle(String content, String fallback) {
        java.util.regex.Matcher matcher = Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.DOTALL).matcher(content);
        if (matcher.find() && !matcher.group(1).isBlank()) {
            return TAG.matcher(matcher.group(1)).replaceAll("").trim();
        }
        java.util.regex.Matcher h1 = Pattern.compile("<h1[^>]*>(.*?)</h1>", Pattern.DOTALL).matcher(content);
        if (h1.find()) {
            return TAG.matcher(h1.group(1)).replaceAll("").trim();
        }
        return fallback;
    }

    private List<Path> listXlsx(Path dir) {
        return listFiles(dir, ".xlsx");
    }

    private List<Path> listFiles(Path dir, String suffix) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
            return stream.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().toLowerCase(Locale.ROOT)
                            .endsWith(suffix))
                    .sorted()
                    .toList();
        } catch (IOException exception) {
            return List.of();
        }
    }

    private String docId(String projectId, String category, Path file) {
        String name = file.getFileName().toString().replaceFirst("\\.(xlsx|html)$", "");
        return projectId + "-" + category + "-" + name;
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    /** 单文件导入计数。 */
    private record Imported(int documents, int versions, int evidences, int claims) {
        static Imported empty() {
            return new Imported(0, 0, 0, 0);
        }

        static Imported of(String dvId, int claims, int evidences) {
            return new Imported(1, 1, evidences, claims);
        }
    }
}