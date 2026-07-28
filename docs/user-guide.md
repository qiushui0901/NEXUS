# NEXUS User Guide

Operational guide for running and using NEXUS. For product positioning and architecture overview, see the [root README](../README.md).

---

## Table of contents

1. [Requirements](#1-requirements)
2. [Configuration](#2-configuration)
3. [Start the service](#3-start-the-service)
4. [Browser surfaces](#4-browser-surfaces)
5. [Requirements ingestion and review](#5-requirements-ingestion-and-review)
6. [Code index and intelligence](#6-code-index-and-intelligence)
7. [Development plans and citations](#7-development-plans-and-citations)
8. [Wiki and version knowledge](#8-wiki-and-version-knowledge)
9. [Version compare](#9-version-compare)
10. [Conflicts and monitoring](#10-conflicts-and-monitoring)
11. [MCP clients](#11-mcp-clients)
12. [Data and Git boundaries](#12-data-and-git-boundaries)
13. [Current limitations](#13-current-limitations)

---

## 1. Requirements

- JDK 21
- Maven 3.9+ (or use `./mvnw`)
- Docker (Qdrant; optional full Compose stack)
- Ollama with an embedding model (default `bge-m3`)
- Optional: BGE reranker exposing `/rerank` (retrieval degrades safely without it)

Pin JDK 21 for a single command if needed:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw test
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw package -DskipTests
```

---

## 2. Configuration

```bash
cp .env.example .env
```

Fill service URLs and tokens in `.env`. Never commit `.env`.

First-time embedding model:

```bash
ollama pull bge-m3
```

Multi-project registration uses `app.rag.projects` / `PROJECT_N_*` env vars (see `application.yml`). Each project should have its own requirement and code collections and a resolvable `repository-path` on the machine that runs NEXUS.

---

## 3. Start the service

### Local script

```bash
./scripts/nexus.sh start
./scripts/nexus.sh status
./scripts/nexus.sh logs
./scripts/nexus.sh stop
```

The script loads `.env`, checks JDK 21, starts local Qdrant, builds the app, and waits until NEXUS is ready. It will not stop unrelated processes that happen to occupy the same ports.

### Docker Compose

```bash
# set AUTH_USER_1_KEY and other secrets in the environment
docker compose up --build
```

Compose brings up NEXUS, Qdrant, Prometheus, and Grafana. Mount or sync repositories into the configured `CODE_REPOSITORY_PATH` (default volume `/workspace/repository`). Embedding / rerank URLs often point at `host.docker.internal` in the sample file — adjust for your network.

---

## 4. Browser surfaces

| Page | URL |
|------|-----|
| Home / runtime status | http://localhost:8080/ |
| Wiki | http://localhost:8080/wiki |
| Version center | http://localhost:8080/versions |
| Monitor | http://localhost:8080/monitor |

Wiki browsing can work from generated files without Qdrant / Ollama / BGE. Code search and LLM plans need those dependencies (or explicit degradation).

Deep link example:

```text
/wiki?projectId=...&version=...&featureId=...
```

---

## 5. Requirements ingestion and review

Pipeline:

```text
parse (Tika) → denoise → parent/child chunk → SHA-256 dedupe
  → dense + sparse hybrid search → optional BGE / LLM rerank
  → doubt generation with version isolation
```

Upload:

```bash
curl -X POST http://localhost:8080/api/requirements/documents \
  -H "X-API-Key: $NEXUS_API_KEY" \
  -F 'file=@requirements.docx' \
  -F 'version=1.1.0' \
  -F 'documentId=example-requirements'
```

Review:

```bash
curl -X POST http://localhost:8080/api/requirements/reviews \
  -H "X-API-Key: $NEXUS_API_KEY" \
  -H 'Content-Type: application/json' \
  -d '{"documentId":"example-requirements","version":"1.1.0","module":"example-module"}'
```

Requirement evidence is scoped by `documentId + version`. Reviews must not pull arbitrary other versions unless explicitly allowed.

---

## 6. Code index and intelligence

### Mental model

NEXUS indexes a **server-side repository path**. Developers still edit code in their own clones (for example multipow workspaces). MCP / REST code tools are **read-only**. See [multipow × NEXUS — §3 Code](multipow-nexus-integration.md#3-代码怎么处理核心).

Supported languages (0.7): Java, Go, Python, TypeScript. Kotlin is enabled only when Tree-sitter capability probes succeed.

### Index

```bash
# foreground full index
curl -X POST "http://localhost:8080/api/code/index?projectId=example-service" \
  -H "X-API-Key: $NEXUS_API_KEY"

# background job
curl -X POST "http://localhost:8080/api/code/index/start?projectId=example-service" \
  -H "X-API-Key: $NEXUS_API_KEY"

curl "http://localhost:8080/api/code/index/status?projectId=example-service" \
  -H "X-API-Key: $NEXUS_API_KEY"
```

Incremental (Git range or webhook-driven):

```text
POST /api/code/incremental-index?projectId=...&oldSha=...&newSha=...
POST /api/webhooks/gitlab
```

Full index writes:

- Qdrant chunks for semantic search
- SQLite symbol / call graph for impact analysis (project + commit scoped)

### Query APIs

```text
POST /api/code/search
POST /api/code/graph              # legacy semantic presentation graph
POST /api/code/graph/symbols      # persisted static symbol graph
POST /api/code/impact             # symbol XOR fromCommit+toCommit
GET  /api/code/source
GET  /api/code/status
POST /api/search/cross-project
```

Impact confidence:

- **Certain:** `EXACT` / `SAME_FILE` edges only
- **Inferred:** `HEURISTIC`
- **Unresolved:** dynamic / ambiguous calls — not counted as certain
- Missing graph for target commit → `NOT_AVAILABLE` + file-level fallback

Empty code collections after first boot are normal until you run an index once. Failed full index keeps the previous collection.

---

## 7. Development plans and citations

```text
POST /api/assistant/development-plan
POST /api/assistant/development-plan/stream
```

Both paths use `RetrievalPipeline`. Each request builds an evidence whitelist (`requirement:*`, `code:*`). Unknown citations are filtered; unsupported claims are marked for verification. Responses include citation quality and optional `conflictReport`.

---

## 8. Wiki and version knowledge

Stable page key:

```text
projectId + version + featureId
```

Paths:

```text
data/wiki-sources/                 structured sources
data/wiki/<projectId>/<version>/   generated index + pages
data/wiki-drafts/...               reviewable builds (never auto-publish)
```

Generate:

```bash
curl -X POST \
  "http://localhost:8080/api/wiki/generate?projectId=example-service&version=1.1.0" \
  -H "X-API-Key: $NEXUS_API_KEY"
```

```text
GET  /api/wiki/projects
GET  /api/wiki/versions?projectId=...
GET  /api/wiki/index?projectId=...&version=...
GET  /api/wiki/page?projectId=...&version=...&featureId=...
POST /api/knowledge/build
```

Knowledge build compares requirement versions (via content hashes), attaches candidate code evidence, and writes drafts under `data/wiki-drafts/`. Only approved drafts may publish into formal Wiki sources (lifecycle APIs under `/api/knowledge/drafts/...`).

Historical code-oriented Wiki backfill (optional tooling):

```bash
python3 tools/build-version-wiki.py --repo /absolute/path/to/your-repository
```

Requirement snapshots (local, gitignored business text):

```bash
python3 tools/build-requirement-snapshots.py
```

---

## 9. Version compare

```text
PUT  /api/versions/manifests
GET  /api/versions/manifests?projectId=...
GET  /api/versions/manifests/{version}?projectId=...
GET  /api/versions/compare?projectId=...&fromVersion=...&toVersion=...
```

The version center UI uses Wiki indexes and optional manifests to show requirement / code / test / Wiki diffs. Each source is `AVAILABLE` or `NOT_AVAILABLE`. Missing data must not be rendered as “unchanged”.

---

## 10. Conflicts and monitoring

```text
POST /api/knowledge/conflicts/analyze
```

Monitoring:

```text
http://localhost:8080/monitor

GET /actuator/health
GET /actuator/prometheus
GET /api/monitor/status
GET /api/monitor/rag-chain
GET /api/runtime/status
```

---

## 11. MCP clients

Endpoint: `http://localhost:8080/mcp` (or your reverse-proxied URL).

Require `X-API-Key` when auth is enabled:

```bash
export NEXUS_API_KEY='...'
export NEXUS_MCP_URL='http://127.0.0.1:8080/mcp'
```

Full Cursor / Codex / stdio bridge instructions: [mcp-quickstart.md](mcp-quickstart.md).

Representative tools: `nexus_search_requirements`, `nexus_search_code`, `nexus_get_source`, `nexus_development_plan`, `nexus_wiki_page`, `nexus_version_diff`, `nexus_code_graph`, `nexus_impact_analysis`, `nexus_review_doubts`.

---

## 12. Data and Git boundaries

**Commit**

- Application and test code
- Small structured fixtures
- Generated Wiki JSON/Markdown you intend to review in Git
- Config templates and docs

**Do not commit**

- `.env` and real credentials
- Qdrant storage, snapshots, WAL
- Vectors / local models
- Large raw document packs / private requirement snapshots (`data/requirement-snapshots/` is gitignored)

Auth: non-local deployments should keep `AUTH_ENABLED=true` with non-blank keys (startup fails closed when misconfigured).

---

## 13. Current limitations

Tracked in detail in [nexus-improvement-roadmap.md](nexus-improvement-roadmap.md). Short list:

1. Server still needs a filesystem (or volume) repository path — not “Git URL only” self-serve sync.
2. Rerank quality and eval gates are still maturing (larger gold sets, CI quality bars).
3. Real CI test-result ingestion is incomplete; UI says “no real execution snapshot” when absent.
4. Static impact cannot resolve all dynamic dispatch; unresolved edges stay explicit.
5. Multipow clone ≠ automatic NEXUS index; align `projectId` and indexed commit deliberately.

Draft / pending-review content must not be treated as confirmed product truth until approved and published.
