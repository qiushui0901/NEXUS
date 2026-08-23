package com.example.requirementrag.requirement.graph.document;

import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.BuildFingerprint;
import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.DocumentStructureNode;
import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.EvidenceBundle;
import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.EvidenceItem;
import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.LogicalUnit;
import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.SourceAnchor;
import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.SupportMode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 文档级需求抽取结构存储（Phase 2/4）：
 * 结构树、稳定锚点、逻辑单元、多片段证据包与构建指纹。
 *
 * <p>锚点绑定不可变 documentRevision；Qdrant 块只作检索加速，不作为唯一证据真源。
 */
@Component
public class RequirementDocumentStructureStore {
    private final String jdbcUrl;

    public RequirementDocumentStructureStore() {
        this("data/requirement-document-structure.db");
    }

    public RequirementDocumentStructureStore(String databasePath) {
        try {
            Path database = Path.of(databasePath).toAbsolutePath().normalize();
            if (database.getParent() != null) Files.createDirectories(database.getParent());
            this.jdbcUrl = "jdbc:sqlite:" + database;
            initialize();
        } catch (IOException exception) {
            throw new IllegalStateException("无法初始化文档结构库目录", exception);
        }
    }

    private void initialize() {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.executeUpdate("""
                    create table if not exists source_anchor(
                      id text primary key,
                      document_id text not null,
                      document_revision text not null,
                      anchor_type text not null,
                      start_offset integer not null,
                      end_offset integer not null,
                      locator_text_hash text not null,
                      original_text text not null,
                      created_at text not null
                    )
                    """);
            statement.executeUpdate("""
                    create table if not exists document_structure_node(
                      id text primary key,
                      document_id text not null,
                      requirement_version text not null,
                      node_type text not null,
                      number_path text,
                      title text not null,
                      parent_node_id text,
                      ord integer not null,
                      source_anchor_id text not null,
                      created_at text not null
                    )
                    """);
            statement.executeUpdate("""
                    create table if not exists logical_unit(
                      id text primary key,
                      document_id text not null,
                      document_revision text not null,
                      unit_type text not null,
                      structure_node_ids text not null,
                      source_anchor_ids text not null,
                      text text not null,
                      previous_unit_summary text,
                      referenced_requirement_ids text not null default '[]',
                      created_at text not null
                    )
                    """);
            statement.executeUpdate("""
                    create table if not exists evidence_bundle(
                      id text primary key,
                      document_id text not null,
                      requirement_version text not null,
                      support_mode text not null,
                      items text not null,
                      created_at text not null
                    )
                    """);
            statement.executeUpdate("""
                    create table if not exists build_fingerprint(
                      id text primary key,
                      document_id text not null,
                      requirement_version text not null,
                      source_revision text not null,
                      document_parser_version text not null,
                      chunking_strategy_version text not null,
                      window_planner_version text not null,
                      ontology_version text not null,
                      prompt_version text not null,
                      model_id text not null,
                      cross_window_integration_version text not null,
                      fingerprint text not null,
                      created_at text not null,
                      unique(document_id, requirement_version)
                    )
                    """);
            statement.executeUpdate("create index if not exists idx_structure_doc on document_structure_node(document_id,requirement_version)");
            statement.executeUpdate("create index if not exists idx_anchor_doc on source_anchor(document_id,document_revision)");
            statement.executeUpdate("create index if not exists idx_unit_doc on logical_unit(document_id,document_revision)");
        } catch (SQLException exception) {
            throw new IllegalStateException("初始化文档结构库失败", exception);
        }
    }

    public void clearDocument(String documentId, String requirementVersion, String documentRevision) {
        execute("delete from evidence_bundle where document_id=? and requirement_version=?", documentId, requirementVersion);
        execute("delete from logical_unit where document_id=? and document_revision=?", documentId, documentRevision);
        execute("delete from document_structure_node where document_id=? and requirement_version=?", documentId, requirementVersion);
        execute("delete from build_fingerprint where document_id=? and requirement_version=?", documentId, requirementVersion);
        execute("delete from source_anchor where document_id=? and document_revision=?", documentId, documentRevision);
    }

    public String saveAnchor(SourceAnchor anchor) {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("""
                     insert or replace into source_anchor(id,document_id,document_revision,anchor_type,
                       start_offset,end_offset,locator_text_hash,original_text,created_at)
                     values(?,?,?,?,?,?,?,?,?)
                     """)) {
            statement.setString(1, anchor.id());
            statement.setString(2, anchor.documentId());
            statement.setString(3, anchor.documentRevision());
            statement.setString(4, anchor.anchorType());
            statement.setInt(5, anchor.startOffset());
            statement.setInt(6, anchor.endOffset());
            statement.setString(7, anchor.locatorTextHash());
            statement.setString(8, anchor.originalText());
            statement.setString(9, Instant.now().toString());
            statement.executeUpdate();
            return anchor.id();
        } catch (SQLException exception) {
            throw new IllegalStateException("保存源锚点失败", exception);
        }
    }

    public String saveStructureNode(DocumentStructureNode node) {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("""
                     insert or replace into document_structure_node(id,document_id,requirement_version,node_type,
                       number_path,title,parent_node_id,ord,source_anchor_id,created_at)
                     values(?,?,?,?,?,?,?,?,?,?)
                     """)) {
            statement.setString(1, node.id());
            statement.setString(2, node.documentId());
            statement.setString(3, node.requirementVersion());
            statement.setString(4, node.nodeType().name());
            statement.setString(5, node.numberPath());
            statement.setString(6, node.title());
            statement.setString(7, node.parentNodeId());
            statement.setInt(8, node.order());
            statement.setString(9, node.sourceAnchorId());
            statement.setString(10, Instant.now().toString());
            statement.executeUpdate();
            return node.id();
        } catch (SQLException exception) {
            throw new IllegalStateException("保存结构节点失败", exception);
        }
    }

    public String saveLogicalUnit(LogicalUnit unit) {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("""
                     insert or replace into logical_unit(id,document_id,document_revision,unit_type,
                       structure_node_ids,source_anchor_ids,text,previous_unit_summary,referenced_requirement_ids,created_at)
                     values(?,?,?,?,?,?,?,?,?,?)
                     """)) {
            statement.setString(1, unit.id());
            statement.setString(2, unit.documentId());
            statement.setString(3, unit.documentRevision());
            statement.setString(4, unit.unitType());
            statement.setString(5, String.join("|", unit.structureNodeIds()));
            statement.setString(6, String.join("|", unit.sourceAnchorIds()));
            statement.setString(7, unit.text());
            statement.setString(8, unit.previousUnitSummary());
            statement.setString(9, String.join("|", unit.referencedRequirementIds()));
            statement.setString(10, Instant.now().toString());
            statement.executeUpdate();
            return unit.id();
        } catch (SQLException exception) {
            throw new IllegalStateException("保存逻辑单元失败", exception);
        }
    }

    public String saveEvidenceBundle(String documentId, String requirementVersion, EvidenceBundle bundle) {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("""
                     insert or replace into evidence_bundle(id, document_id, requirement_version, support_mode, items, created_at)
                     values(?,?,?,?,?,?)
                     """)) {
            statement.setString(1, bundle.id());
            statement.setString(2, documentId);
            statement.setString(3, requirementVersion);
            statement.setString(4, bundle.supportMode().name());
            statement.setString(5, String.join("|", bundle.items().stream().map(EvidenceItem::sourceAnchorId).toList()));
            statement.setString(6, Instant.now().toString());
            statement.executeUpdate();
            return bundle.id();
        } catch (SQLException exception) {
            throw new IllegalStateException("保存证据包失败", exception);
        }
    }

    public String saveFingerprint(String documentId, String requirementVersion, BuildFingerprint fingerprint) {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("""
                     insert into build_fingerprint(id,document_id,requirement_version,source_revision,
                       document_parser_version,chunking_strategy_version,window_planner_version,ontology_version,
                       prompt_version,model_id,cross_window_integration_version,fingerprint,created_at)
                     values(?,?,?,?,?,?,?,?,?,?,?,?,?)
                     on conflict(document_id, requirement_version) do update set
                       source_revision=excluded.source_revision, document_parser_version=excluded.document_parser_version,
                       chunking_strategy_version=excluded.chunking_strategy_version,
                       window_planner_version=excluded.window_planner_version, ontology_version=excluded.ontology_version,
                       prompt_version=excluded.prompt_version, model_id=excluded.model_id,
                       cross_window_integration_version=excluded.cross_window_integration_version,
                       fingerprint=excluded.fingerprint
                     """)) {
            String id = "fp:" + documentId + ":" + requirementVersion;
            statement.setString(1, id);
            statement.setString(2, documentId);
            statement.setString(3, requirementVersion);
            statement.setString(4, fingerprint.sourceRevision());
            statement.setString(5, fingerprint.documentParserVersion());
            statement.setString(6, fingerprint.chunkingStrategyVersion());
            statement.setString(7, fingerprint.windowPlannerVersion());
            statement.setString(8, fingerprint.ontologyVersion());
            statement.setString(9, fingerprint.promptVersion());
            statement.setString(10, fingerprint.modelId());
            statement.setString(11, fingerprint.crossWindowIntegrationVersion());
            statement.setString(12, fingerprint.fingerprint());
            statement.setString(13, Instant.now().toString());
            statement.executeUpdate();
            return id;
        } catch (SQLException exception) {
            throw new IllegalStateException("保存构建指纹失败", exception);
        }
    }

    public List<DocumentStructureNode> findStructureNodes(String documentId, String requirementVersion) {
        List<DocumentStructureNode> result = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "select * from document_structure_node where document_id=? and requirement_version=? order by ord")) {
            statement.setString(1, documentId);
            statement.setString(2, requirementVersion);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(new DocumentStructureNode(
                            rows.getString("id"), rows.getString("document_id"), rows.getString("requirement_version"),
                            DocumentLevelModels.StructureNodeType.valueOf(rows.getString("node_type")),
                            rows.getString("number_path"), rows.getString("title"), rows.getString("parent_node_id"),
                            rows.getInt("ord"), rows.getString("source_anchor_id")));
                }
            }
            return List.copyOf(result);
        } catch (SQLException exception) {
            throw new IllegalStateException("读取结构节点失败", exception);
        }
    }

    public List<SourceAnchor> findAnchors(String documentId, String documentRevision) {
        List<SourceAnchor> result = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "select * from source_anchor where document_id=? and document_revision=? order by start_offset")) {
            statement.setString(1, documentId);
            statement.setString(2, documentRevision);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(new SourceAnchor(
                            rows.getString("id"), rows.getString("document_id"), rows.getString("document_revision"),
                            rows.getString("anchor_type"), rows.getInt("start_offset"), rows.getInt("end_offset"),
                            rows.getString("locator_text_hash"), rows.getString("original_text")));
                }
            }
            return List.copyOf(result);
        } catch (SQLException exception) {
            throw new IllegalStateException("读取源锚点失败", exception);
        }
    }

    public List<LogicalUnit> findLogicalUnits(String documentId, String documentRevision) {
        List<LogicalUnit> result = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "select * from logical_unit where document_id=? and document_revision=? order by id")) {
            statement.setString(1, documentId);
            statement.setString(2, documentRevision);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(new LogicalUnit(
                            rows.getString("id"), rows.getString("document_id"), rows.getString("document_revision"),
                            rows.getString("unit_type"),
                            split(rows.getString("structure_node_ids")), split(rows.getString("source_anchor_ids")),
                            rows.getString("text"), rows.getString("previous_unit_summary"),
                            split(rows.getString("referenced_requirement_ids"))));
                }
            }
            return List.copyOf(result);
        } catch (SQLException exception) {
            throw new IllegalStateException("读取逻辑单元失败", exception);
        }
    }

    public List<EvidenceBundle> findEvidenceBundles(String documentId, String requirementVersion) {
        List<EvidenceBundle> result = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "select * from evidence_bundle where document_id=? and requirement_version=? order by id")) {
            statement.setString(1, documentId);
            statement.setString(2, requirementVersion);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    SupportMode mode = SupportMode.valueOf(rows.getString("support_mode"));
                    List<EvidenceItem> items = new ArrayList<>();
                    for (String anchorId : split(rows.getString("items"))) {
                        items.add(new EvidenceItem(anchorId, null, "", 0, 0, "RELATION_ASSERTION", "RULE"));
                    }
                    result.add(new EvidenceBundle(rows.getString("id"), mode, items));
                }
            }
            return List.copyOf(result);
        } catch (SQLException exception) {
            throw new IllegalStateException("读取证据包失败", exception);
        }
    }

    private void execute(String sql, String... args) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) statement.setString(i + 1, args[i]);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("文档结构库写操作失败", exception);
        }
    }

    private List<String> split(String value) {
        if (value == null || value.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        for (String item : value.split("\\|")) {
            if (!item.isBlank()) result.add(item);
        }
        return List.copyOf(result);
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }
}