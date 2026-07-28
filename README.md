# NEXUS

**Versioned knowledge for requirements, code, and tests — with evidence you can actually verify.**

NEXUS turns scattered product docs, Git repositories, and test signals into a **project- and version-scoped knowledge service**. Teams browse a shared Wiki; coding agents call the same facts over **MCP**, with stable citations instead of unverifiable model prose.

> Current version: `0.7.0-SNAPSHOT`
> Stack: Java 21 · Spring Boot 4.1 · Spring AI 2.0 · Qdrant · Tree-sitter

---

## Why NEXUS

Most “RAG for docs” tools answer questions but blur **which version**, **which project**, and **which evidence** the answer came from. NEXUS is built around a stricter contract:

| Principle | Meaning |
|-----------|---------|
| Version isolation | Answers for `v1` must not silently use `v2` content |
| Evidence first | Claims carry `requirement:*` / `code:*` IDs you can open |
| Safe degradation | Missing sources show as unavailable — never as “no change” |
| Draft ≠ published | Auto-built Wiki stays reviewable until humans approve |
| Agent-native | The same knowledge is exposed as MCP tools for Cursor / Codex |

---

## What it does

```text
Requirements + Git + tests
        │
        ▼
  Index & version facts ──► Wiki (JSON / Markdown / browser)
        │
        ├──► Retrieval pipeline ──► reviews, development plans, citations
        └──► MCP (/mcp) ──► IDE agents (search, source, impact, wiki, diffs)
```

**For product** — browse feature pages per version: rules, flows, risks, evidence.
**For developers** — search code (Java / Go / Python / TypeScript), read bounded source, inspect call graphs and impact.
**For agents** — call MCP tools that return structured data, warnings, and citation quality — not a black-box chat blob.
**For the team** — compare versions across requirements, code, tests, and Wiki with explicit availability flags.

---

## Highlights

- **Versioned Wiki** — `projectId + version + featureId`; human-readable Markdown plus machine-readable JSON
- **Unified retrieval** — shared pipeline for requirements and code, with degradation diagnostics
- **Citation whitelist** — models may only cite evidence from the current request
- **Code intelligence** — multi-language AST indexing, SQLite symbol graph, conservative impact analysis (`EXACT` / `SAME_FILE` vs `HEURISTIC` / `UNRESOLVED`)
- **MCP server** — Streamable HTTP at `/mcp` for Cursor, Codex, and compatible clients
- **Conflict checks** — structured claims; Wiki cannot override raw requirement/code/test evidence
- **Ops-friendly** — Docker Compose, health endpoints, Prometheus / OTLP hooks

---

## Quick start

```bash
cp .env.example .env          # fill tokens and service URLs
./scripts/nexus.sh start      # local Qdrant + app (see user guide)
```

Then open:

| Surface | URL |
|---------|-----|
| Home | http://localhost:8080/ |
| Wiki | http://localhost:8080/wiki |
| Versions | http://localhost:8080/versions |
| Monitor | http://localhost:8080/monitor |
| MCP | http://localhost:8080/mcp |

For Compose-based shared deployment, MCP client setup, indexing, and API recipes, see the docs below — not this README.

---

## Documentation

| Doc | Contents |
|-----|----------|
| [User guide](docs/user-guide.md) | Install, configure, run, index code, Wiki, APIs, data boundaries |
| [MCP quickstart](docs/mcp-quickstart.md) | Cursor / Codex / Claude Code setup and troubleshooting |
| [multipow × NEXUS](docs/multipow-nexus-integration.md) | Agent workspace scaffolding + evidence gates (code dual-copy model) |
| [Improvement roadmap](docs/nexus-improvement-roadmap.md) | Defects, version plan toward team-wide / GA use |
| [Changelog](CHANGELOG.md) | Release notes |

---

## Architecture (overview)

| Layer | Role |
|-------|------|
| Ingestion | Requirements (Tika documents), code scanners, optional test snapshots |
| Storage | Qdrant (vectors), SQLite (symbol graph), file-backed Wiki / manifests / drafts |
| Retrieval | `RetrievalPipeline` — routing, hybrid search, limits, warnings |
| Knowledge | Wiki generator, version compare, draft lifecycle, conflict analysis |
| Access | REST + SSE + MCP; API key, roles, project allowlists |

**Code model (important):** developers edit repositories on their machines (e.g. via multipow workspaces). NEXUS keeps a **server-side indexed copy** for search, source excerpts, and impact analysis. MCP tools are **read-only** for code; they do not replace local Git.

---

## Tech stack

- **Runtime:** Java 21, Spring Boot 4.1, Spring AI 2.0
- **Retrieval:** Qdrant dense + sparse, optional BGE rerank, Ollama embeddings, OpenAI-compatible chat
- **Code:** Tree-sitter multi-language parse, SQLite call graph
- **Delivery:** Maven, Docker / Compose, Actuator, Prometheus, OpenTelemetry

---

## Project status

NEXUS is under active development (`0.7.x`). Core evidence retrieval, versioned Wiki, MCP, and multi-language code intelligence are in place. Team-wide production hardening (shared repo sync, SSO, quotas, larger eval gates) is tracked in the [roadmap](docs/nexus-improvement-roadmap.md).

Automatic builds produce **draft / pending-review** knowledge only. Unpublished model output is never treated as confirmed product truth.

---

## Contributing

1. Use JDK 21 and `./mvnw -B verify` before sending changes.
2. Prefer small, focused PRs; follow existing evidence and version-isolation contracts.
3. Do not commit `.env`, Qdrant storage, vectors, or private business documents.
4. Read [`.trellis/spec/backend/retrieval-and-version-knowledge.md`](.trellis/spec/backend/retrieval-and-version-knowledge.md) when touching retrieval or version knowledge.

Bug reports and design notes are welcome via issues / MRs. A formal `CONTRIBUTING.md` and OSI license file will be added as the public packaging matures.

---

## License

License file not yet published in this repository. Treat the code as source-available for internal evaluation until an explicit license is added.
