# Design

## Architecture

Data flow:

`versioned source JSON -> WikiGenerationService validation/render -> data/wiki/<project>/<version>/{index.json,pages/*.json,pages/*.md} -> WikiRepository -> WikiController -> static wiki.html`

The structured source JSON is the generation boundary. Retrieval/LLM can populate it later, while deterministic generation and browsing remain testable without external services.

## Storage contract

Source: `data/wiki-sources/<project>-v<version>.json`.

Generated:

- `data/wiki/<project>/<version>/index.json`
- `data/wiki/<project>/<version>/pages/<featureId>.json`
- `data/wiki/<project>/<version>/pages/<featureId>.md`

`index.json` contains project/version metadata and page summaries. Page JSON is the browser/API contract. Markdown is the human-readable, Git-manageable artifact.

## Backend boundaries

- `WikiProperties`: root and source paths under `app.rag.wiki`.
- `WikiModels`: typed source/page/index/evidence/relation/status contracts.
- `WikiGenerationService`: validation, normalization, Markdown rendering and atomic publish.
- `WikiRepository`: safe path resolution and read-only discovery.
- `WikiController`: PUBLIC_READ browse APIs and DATA_ADMIN generation API.
- Existing `ApiExceptionHandler` maps illegal input to 400 and missing artifacts to 404 via `ResponseStatusException`.

## Security and integrity

Identifiers allow only letters, digits, dot, underscore and hyphen. Every resolved path must remain below the configured root. Generator writes to a sibling temporary directory, then replaces the version directory. Browser renders text with DOM APIs/escaped HTML rather than trusting generated HTML.

## UI

`wiki.html` is a dependency-free three-column knowledge workspace:

- left: project/version selectors, search and feature list;
- center: role tabs and page content;
- right: metadata, relations and evidence.

Responsive breakpoints collapse the right rail and then the navigation. API key can be supplied from localStorage for installations with auth enabled.

## Compatibility

No existing Qdrant schema or retrieval behavior changes. Wiki browsing works when all model/vector dependencies are down. Generated files are small source artifacts, not vector database data.
