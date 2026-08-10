package com.example.requirementrag.wiki.module;

import com.example.requirementrag.code.SQLiteSymbolGraphStore;
import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.wiki.WikiModels.Claim;
import com.example.requirementrag.wiki.WikiModels.ClaimSupport;
import com.example.requirementrag.wiki.WikiModels.Evidence;
import com.example.requirementrag.wiki.WikiModels.PageSource;
import com.example.requirementrag.wiki.WikiModels.PageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Module 页面发布质量门：真实代码证据、commit 一致性、文件/行号有效性与 CONFLICT 拦截。 */
@Component
public class ModuleClaimQualityGate {
    private static final Logger log = LoggerFactory.getLogger(ModuleClaimQualityGate.class);

    /** 模块证据 ID 的最后一段是页面 evidence 列表下标，namespace 表示事实类型。 */
    private static final Pattern EVIDENCE_INDEX = Pattern.compile(
            "^[a-z][a-z0-9-]*:[A-Za-z0-9._-]+:(\\d+)$");
    private static final Pattern LEGACY_EVIDENCE_INDEX = Pattern.compile("^(requirement|code):(\\d+)$");
    /** 证据位置形如 lines=12-40。 */
    private static final Pattern LOCATION_LINES = Pattern.compile("^lines=(\\d+)-(\\d+)$");

    private final ProjectRegistry projectRegistry;
    private final SQLiteSymbolGraphStore graphStore;

    public ModuleClaimQualityGate(ProjectRegistry projectRegistry, SQLiteSymbolGraphStore graphStore) {
        this.projectRegistry = projectRegistry;
        this.graphStore = graphStore;
    }

    /** 不校验任何内容的门禁实例（旧测试/构造路径的兼容占位）。 */
    public static ModuleClaimQualityGate lenient() {
        return new ModuleClaimQualityGate(null, null) {
            @Override
            public void validate(String projectId, String version, String codeCommit, List<PageSource> pages) {
            }
        };
    }

    /**
     * 校验 MODULE 页面发布质量，硬约束：
     * <ol>
     *   <li>至少一条真实 CODE 证据（仅类型为 CODE 的条目才算数）；</li>
     *   <li>任何 CONFLICT 声明阻止发布；</li>
     *   <li>代码证据必须绑定目标代码提交（缺 commit 或与目标不一致均拦截，fail-closed）；</li>
     *   <li>代码类证据文件必须存在于仓库根目录内、行号不越界、符号仍存在于符号图快照；</li>
     *   <li>Claim 引用下标有效、证据 ID 的 namespace 前缀与实际证据类型一致、不跨项目/版本。</li>
     * </ol>
     *
     * @throws IllegalArgumentException 门禁失败时
     */
    public void validate(String projectId, String version, String codeCommit, List<PageSource> pages) {
        for (PageSource page : pages) {
            if (page.pageType() != PageType.MODULE) continue;
            validateRealCodeEvidence(page);
            validateConflicts(page);
            validateCommitConsistency(page, codeCommit);
            validateFileAndLines(projectId, page);
            validateSymbolPresence(projectId, page);
            validateClaimReferences(projectId, version, page);
        }
    }

    /** 硬约束 1：MODULE 页面必须至少有一条类型为 CODE 的真实代码证据。 */
    private void validateRealCodeEvidence(PageSource page) {
        boolean hasRealCode = page.evidence().stream().anyMatch(evidence -> "CODE".equals(evidence.type()));
        if (!hasRealCode) {
            throw new IllegalArgumentException("MODULE 页面没有真实 CODE 证据，禁止发布: " + page.featureId());
        }
    }

    /** 硬约束 2：任何 CONFLICT 声明都阻止发布。 */
    private void validateConflicts(PageSource page) {
        for (Claim claim : list(page.claims())) {
            if (claim.support() == ClaimSupport.CONFLICT) {
                throw new IllegalArgumentException("存在未处理的 CONFLICT 声明，禁止发布: " + claim.claimId());
            }
        }
    }

    /** 硬约束 3：目标提交与每条代码类证据的 commit 都必须存在且一致（fail-closed，不允许空值绕过）。 */
    private void validateCommitConsistency(PageSource page, String codeCommit) {
        if (codeCommit == null || codeCommit.isBlank()) {
            throw new IllegalArgumentException("MODULE 页面缺少目标代码提交，禁止发布: " + page.featureId());
        }
        for (int index = 0; index < page.evidence().size(); index++) {
            Evidence evidence = page.evidence().get(index);
            if (!FILE_EVIDENCE_TYPES.contains(evidence.type())) continue;
            if (evidence.commit() == null || evidence.commit().isBlank()) {
                throw new IllegalArgumentException("代码证据缺少 commit，禁止发布: " + page.featureId()
                        + " evidence[" + index + "]");
            }
            if (!codeCommit.equals(evidence.commit())) {
                throw new IllegalArgumentException("Claim 证据跨 commit（页面 " + codeCommit + "，证据 "
                        + evidence.commit() + "）: " + page.featureId() + " evidence[" + index + "]");
            }
        }
    }

    /** 需要核验文件与行号的证据类型（指向真实源码位置）。 */
    private static final Set<String> FILE_EVIDENCE_TYPES = Set.of(
            "CODE", "CODE_GRAPH", "ROUTE", "TEST_SYMBOL");

    /** 硬约束 4：代码类证据文件必须存在于仓库内且行号不越界；仓库不可读时按失败处理（fail-closed）。 */
    private void validateFileAndLines(String projectId, PageSource page) {
        RagProperties.ProjectConfig project = projectRegistry == null ? null : projectRegistry.require(projectId);
        String repositoryPath = project == null ? null : project.repositoryPath();
        if (repositoryPath == null || repositoryPath.isBlank()) {
            throw new IllegalArgumentException("项目代码仓库路径未配置，无法核验证据文件: " + page.featureId());
        }
        Path repository = Path.of(repositoryPath).toAbsolutePath().normalize();
        if (!Files.isDirectory(repository)) {
            throw new IllegalArgumentException("项目代码仓库不可用，无法核验证据文件: " + page.featureId());
        }
        for (int index = 0; index < page.evidence().size(); index++) {
            Evidence evidence = page.evidence().get(index);
            if (!FILE_EVIDENCE_TYPES.contains(evidence.type())) continue;
            String filePath = firstText(evidence.filePath(), evidence.source());
            if (filePath.isBlank()) {
                throw new IllegalArgumentException("证据缺少文件位置: " + page.featureId() + " evidence[" + index + "]");
            }
            Path file = repository.resolve(filePath).normalize();
            if (!file.startsWith(repository)) {
                throw new IllegalArgumentException("证据文件越出仓库根目录: " + page.featureId() + " evidence[" + index + "]");
            }
            int maxLine = fileLineCount(file);
            if (maxLine < 0) {
                throw new IllegalArgumentException("证据文件不存在或不可读: " + page.featureId() + " evidence["
                        + index + "] " + filePath);
            }
            int[] lines = parseLines(evidence.location());
            if (lines != null && lines[1] > maxLine) {
                throw new IllegalArgumentException("证据行号越界（文件共 " + maxLine + " 行，位置 "
                        + evidence.location() + "）: " + page.featureId() + " evidence[" + index + "] " + filePath);
            }
        }
    }

    /** 硬约束 5：CODE 类型证据的符号必须仍存在于当前符号图快照（文件在但符号被删除时拦截）。 */
    private void validateSymbolPresence(String projectId, PageSource page) {
        if (graphStore == null) return;
        String commit = null;
        try {
            commit = graphStore.latestCommit(projectId);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("符号图快照不可用，无法核验证据符号: " + page.featureId(), exception);
        }
        if (commit == null) {
            throw new IllegalArgumentException("符号图快照不存在，无法核验证据符号: " + page.featureId());
        }
        for (int index = 0; index < page.evidence().size(); index++) {
            Evidence evidence = page.evidence().get(index);
            if (!"CODE".equals(evidence.type())) continue;
            String symbol = evidence.symbol();
            if (symbol == null || symbol.isBlank()) continue;
            boolean present = graphStore.findSymbols(projectId, commit, symbol, 5).stream()
                    .anyMatch(found -> symbol.equals(found.qualifiedName()));
            if (!present) {
                throw new IllegalArgumentException("证据符号已不存在于代码快照: " + page.featureId()
                        + " evidence[" + index + "] " + symbol);
            }
        }
    }

    /** 既有约束：Claim 引用下标有效、证据 ID namespace 与实际类型一致、不跨项目/版本。 */
    private void validateClaimReferences(String projectId, String version, PageSource page) {
        for (Claim claim : list(page.claims())) {
            if (claim.support() == ClaimSupport.FULL && list(claim.evidenceIds()).isEmpty()) {
                throw new IllegalArgumentException("FULL Claim 缺少证据引用: " + claim.claimId());
            }
            if (claim.support() != ClaimSupport.UNSUPPORTED && list(claim.evidenceIds()).isEmpty()) {
                throw new IllegalArgumentException("受支持 Claim 缺少证据引用: " + claim.claimId());
            }
            for (String evidenceId : list(claim.evidenceIds())) {
                Integer index = evidenceIndex(evidenceId);
                if (index == null || index >= page.evidence().size()) {
                    throw new IllegalArgumentException("Claim 引用了不存在的证据: " + claim.claimId()
                            + " -> " + evidenceId);
                }
                Evidence evidence = page.evidence().get(index);
                String namespace = namespaceOf(evidenceId);
                if (namespace != null && !namespaceMatchesType(namespace, evidence.type())) {
                    throw new IllegalArgumentException("Claim 证据 ID 前缀与证据类型不一致: " + claim.claimId()
                            + " -> " + evidenceId + "（证据类型 " + evidence.type() + "）");
                }
                if (!"REQUIREMENT".equals(evidence.type()) && !projectId.equals(evidence.source())) {
                    throw new IllegalArgumentException("Claim 证据跨项目: " + claim.claimId() + " -> " + evidenceId);
                }
                if (!version.equals(evidence.version())) {
                    throw new IllegalArgumentException("Claim 证据跨版本: " + claim.claimId() + " -> " + evidenceId);
                }
            }
        }
    }

    /** 返回文件行数；文件不存在或读取失败返回 -1。 */
    private int fileLineCount(Path file) {
        try {
            long lines = Files.readAllLines(file, StandardCharsets.UTF_8).size();
            return lines > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) lines;
        } catch (IOException exception) {
            log.debug("Evidence file unreadable: {}", file, exception);
            return -1;
        }
    }

    /** 解析 evidence.location 中的行号区间；无法解析时返回 null（跳过行号校验）。 */
    private int[] parseLines(String location) {
        if (location == null) return null;
        Matcher matcher = LOCATION_LINES.matcher(location.trim());
        if (!matcher.matches()) return null;
        return new int[]{Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))};
    }

    private String firstText(String primary, String fallback) {
        return primary != null && !primary.isBlank() ? primary.trim() : (fallback == null ? "" : fallback.trim());
    }

    /** 提取证据 ID 的 namespace 前缀（形如 code:auth:0 -> code）；格式不识别时返回 null。 */
    static String namespaceOf(String evidenceId) {
        if (evidenceId == null) return null;
        int firstColon = evidenceId.indexOf(':');
        if (firstColon <= 0) return null;
        return evidenceId.substring(0, firstColon);
    }

    /** namespace 与 Evidence 类型的合法映射（code-graph <-> CODE_GRAPH 等）。 */
    static boolean namespaceMatchesType(String namespace, String evidenceType) {
        if (namespace == null || evidenceType == null) return false;
        return switch (namespace) {
            case "code" -> "CODE".equals(evidenceType);
            case "code-graph" -> "CODE_GRAPH".equals(evidenceType);
            case "route" -> "ROUTE".equals(evidenceType);
            case "dependency" -> "DEPENDENCY".equals(evidenceType);
            case "data" -> "DATA".equals(evidenceType);
            case "config" -> "CONFIG".equals(evidenceType);
            case "test" -> "TEST_SYMBOL".equals(evidenceType);
            case "diagnostic" -> "DIAGNOSTIC".equals(evidenceType);
            case "requirement" -> "REQUIREMENT".equals(evidenceType);
            default -> false;
        };
    }

    /** 解析证据 ID 为页面 evidence 下标；格式不识别时返回 null。 */
    static Integer evidenceIndex(String evidenceId) {
        if (evidenceId == null) return null;
        Matcher module = EVIDENCE_INDEX.matcher(evidenceId.trim());
        if (module.matches()) return Integer.valueOf(module.group(1));
        Matcher legacy = LEGACY_EVIDENCE_INDEX.matcher(evidenceId.trim());
        return legacy.matches() ? Integer.valueOf(legacy.group(2)) : null;
    }

    private static <T> List<T> list(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
