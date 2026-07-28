package com.example.requirementrag.model;

import jakarta.validation.constraints.NotBlank;

/** Request for bounded static symbol traversal. */
public record SymbolGraphRequest(String projectId, @NotBlank String symbol, String direction,
                                 Integer depth, Integer limit) {
}
