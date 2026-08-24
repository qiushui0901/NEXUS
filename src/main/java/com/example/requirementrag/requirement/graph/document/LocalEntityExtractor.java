package com.example.requirementrag.requirement.graph.document;

import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.LocalExtraction;
import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.LogicalUnit;
import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.SourceAnchor;

import java.util.List;

/** 局部实体抽取 SPI：在单个逻辑单元内抽取实体与局部关系（规则或 LLM）。 */
public interface LocalEntityExtractor {
    LocalExtraction extract(LogicalUnit unit, List<SourceAnchor> unitAnchors);
}