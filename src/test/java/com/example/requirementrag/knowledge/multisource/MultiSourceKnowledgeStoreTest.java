package com.example.requirementrag.knowledge.multisource;

import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.DoubtClaim;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.KnowledgeQueryIntent;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.ParameterClaim;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MultiSourceKnowledgeStoreTest {
    @TempDir Path tempDir;

    private final ParameterTableLoader loader = new ParameterTableLoader();
    private final DoubtClaimParser doubtParser = new DoubtClaimParser();
    private final MultiSourceKnowledgeGate gate = new MultiSourceKnowledgeGate();

    @Test
    void persistsAndLoadsParameterAndDoubtClaims() {
        MultiSourceKnowledgeStore store = new MultiSourceKnowledgeStore(
                tempDir.resolve("multi-source.db").toString(), new ObjectMapper());
        var layout = loader.parseHeaders(List.of("模块", "参数", "值", "单位", "版本"));
        List<ParameterClaim> parameters = loader.parse(layout,
                List.of(Map.of("0", "订单", "1", "传播时间", "2", "5分钟", "3", "分钟", "4", "5.1")),
                "fengshen", "5.1", "参数表.xlsx", "5.1参数");
        List<DoubtClaim> doubts = List.of(doubtParser.parse(
                Map.of("问题", "权限撤销未确认", "状态", "OPEN"), "fengshen", "5.1", "5.1存疑", 1));

        store.replaceProjectVersion("fengshen", "5.1");
        store.saveParameters("fengshen", "5.1", parameters);
        store.saveDoubts("fengshen", "5.1", doubts);

        assertThat(store.findParameters("fengshen", "5.1"))
                .extracting(ParameterClaim::parameter).containsExactly("传播时间");
        assertThat(store.findDoubts("fengshen", "5.1"))
                .extracting(DoubtClaim::question).containsExactly("权限撤销未确认");
    }

    @Test
    void storeDoubtsAreIsolatedFromNormativeQueriesByGate() {
        MultiSourceKnowledgeStore store = new MultiSourceKnowledgeStore(
                tempDir.resolve("multi-source-gate.db").toString(), new ObjectMapper());
        store.saveDoubts("fengshen", "5.1", List.of(doubtParser.parse(
                Map.of("问题", "待确认", "状态", "OPEN"), "fengshen", "5.1", "存疑", 1)));

        List<DoubtClaim> normative = gate.filterDoubts(store.findDoubts("fengshen", "5.1"),
                KnowledgeQueryIntent.NORMATIVE);
        List<DoubtClaim> doubtIntent = gate.filterDoubts(store.findDoubts("fengshen", "5.1"),
                KnowledgeQueryIntent.DOUBT);

        assertThat(normative).isEmpty();
        assertThat(doubtIntent).hasSize(1);
    }

    @Test
    void replaceSnapshotIsTransactionalAcrossAllSources() {
        MultiSourceKnowledgeStore store = new MultiSourceKnowledgeStore(
                tempDir.resolve("multi-source-snapshot.db").toString(), new ObjectMapper());
        var layout = loader.parseHeaders(List.of("参数", "值"));
        List<ParameterClaim> parameters = loader.parse(layout,
                List.of(Map.of("0", "a", "1", "1")), "fengshen", "5.1", "t.xlsx", "表");
        store.replaceSnapshot("fengshen", "5.1", parameters, List.of(), List.of(), List.of());

        assertThat(store.findParameters("fengshen", "5.1")).hasSize(1);
        assertThat(store.findDoubts("fengshen", "5.1")).isEmpty();

        store.replaceSnapshot("fengshen", "5.1", List.of(), List.of(doubtParser.parse(
                Map.of("问题", "新存疑", "状态", "OPEN"), "fengshen", "5.1", "存疑", 1)), List.of(), List.of());
        assertThat(store.findParameters("fengshen", "5.1")).isEmpty();
        assertThat(store.findDoubts("fengshen", "5.1")).hasSize(1);
    }

    @Test
    void replaceProjectVersionClearsOldClaims() {
        MultiSourceKnowledgeStore store = new MultiSourceKnowledgeStore(
                tempDir.resolve("multi-source-replace.db").toString(), new ObjectMapper());
        var layout = loader.parseHeaders(List.of("参数", "值"));
        store.saveParameters("fengshen", "5.1", loader.parse(layout,
                List.of(Map.of("0", "a", "1", "1")), "fengshen", "5.1", "t.xlsx", "表"));

        store.replaceProjectVersion("fengshen", "5.1");
        store.saveParameters("fengshen", "5.1", loader.parse(layout,
                List.of(Map.of("0", "b", "1", "2")), "fengshen", "5.1", "t.xlsx", "表"));

        assertThat(store.findParameters("fengshen", "5.1"))
                .extracting(ParameterClaim::parameter).containsExactly("b");
    }
}