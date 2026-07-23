# NEXUS Streaming Plan and Source Drawer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver true SSE development-plan streaming, a bottom/fullscreen source viewer, overflow-safe cards, and a compact graph canvas toolbar.

**Architecture:** Spring MVC exposes a POST `text/event-stream` endpoint backed by `SseEmitter`. A focused stream service retrieves Qdrant evidence, emits progress, consumes `ChatClient.stream().content()`, buffers NDJSON lines, and emits typed events. The Vue page reads the response stream with `fetch`, incrementally updates the structured plan, and keeps source viewing in an overlay drawer independent of the right sidebar.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring MVC `SseEmitter`, Spring AI `ChatClient`, Jackson, Vue 3 browser build, JUnit 5, AssertJ.

## Global Constraints

- Preserve `/api/assistant/development-plan` as the synchronous compatibility endpoint.
- Add `/api/assistant/development-plan/stream` with `text/event-stream`.
- Do not alter code-vector indexing or product-question generation.
- Do not add a separate frontend build system or use the native Fullscreen API.
- The page must not gain horizontal overflow at 1280px or narrow layouts.
- The project is not a Git repository; replace commit steps with test checkpoints.

---

### Task 1: NDJSON stream parser and event contract

**Files:**
- Create: `src/main/java/com/example/requirementrag/model/DevelopmentPlanStreamEvent.java`
- Create: `src/main/java/com/example/requirementrag/service/DevelopmentPlanStreamParser.java`
- Test: `src/test/java/com/example/requirementrag/service/DevelopmentPlanStreamParserTest.java`

**Interfaces:**
- Produces: `DevelopmentPlanStreamEvent(String type, long sequence, Object payload, String message)`.
- Produces: `DevelopmentPlanStreamParser.accept(String chunk)` returning all complete events parsed from arbitrary text chunks.
- Produces: `DevelopmentPlanStreamParser.finish()` for a final non-newline-terminated event.

- [ ] **Step 1: Write failing parser tests** covering split JSON across chunks, multiple lines in one chunk, blank/code-fence lines, an invalid line followed by a valid line, and final buffered content.
- [ ] **Step 2: Run** `mvn -Dtest=DevelopmentPlanStreamParserTest test` and verify missing-class compilation failure.
- [ ] **Step 3: Implement** the event record and a line-buffered parser using Jackson `ObjectMapper`; invalid or non-object lines return no event and do not poison subsequent lines.
- [ ] **Step 4: Re-run the parser test** and require all cases to pass.

### Task 2: Real SSE development-plan endpoint

**Files:**
- Create: `src/main/java/com/example/requirementrag/service/DevelopmentPlanStreamService.java`
- Modify: `src/main/java/com/example/requirementrag/web/AssistantController.java`
- Test: `src/test/java/com/example/requirementrag/web/AssistantControllerStreamTest.java`

**Interfaces:**
- Consumes: `DevelopmentPlanRequest` and `DevelopmentPlanStreamEvent`.
- Produces: `SseEmitter DevelopmentPlanStreamService.stream(DevelopmentPlanRequest request)`.
- Produces: `POST /api/assistant/development-plan/stream`, `Content-Type: text/event-stream`.

- [ ] **Step 1: Write a failing MVC test** that injects a mocked stream service, calls the endpoint, and asserts status 200 plus `text/event-stream`.
- [ ] **Step 2: Run** `mvn -Dtest=AssistantControllerStreamTest test` and verify the route is missing.
- [ ] **Step 3: Add the controller route** while leaving the existing synchronous route unchanged.
- [ ] **Step 4: Implement stream orchestration** with `SseEmitter`, a virtual thread, ordered `started` and `retrieval` events, a model prompt requiring one JSON object per line, and `ChatClient.stream().content()` feeding `DevelopmentPlanStreamParser`.
- [ ] **Step 5: Emit references and terminal state** after model completion; emit `error` on failure and complete the emitter exactly once.
- [ ] **Step 6: Run parser and controller tests** and require both to pass.

### Task 3: Incremental Vue development-plan rendering

**Files:**
- Modify: `src/main/resources/static/monitor.html`
- Modify: `src/test/java/com/example/requirementrag/web/MonitorWorkbenchPageTest.java`

**Interfaces:**
- Consumes: SSE events from `/api/assistant/development-plan/stream`.
- Produces: `streamDevelopmentPlan()`, `applyPlanEvent(event)`, `parseSseBlock(block)`, and `cancelPlanStream()`.

- [ ] **Step 1: Extend the page test** to require the stream endpoint, `response.body.getReader()`, `AbortController`, `applyPlanEvent`, and a visible generation-stage element.
- [ ] **Step 2: Run** `mvn -Dtest=MonitorWorkbenchPageTest test` and verify the new assertions fail.
- [ ] **Step 3: Add stream state** (`planStreaming`, `planStage`, `planAbortController`) and initialize an empty structured plan.
- [ ] **Step 4: Implement the POST stream reader** with UTF-8 decoding, SSE block buffering, JSON event parsing, cancellation before restart, and retained partial content on error.
- [ ] **Step 5: Incrementally map event types** into `summary`, `productUnderstanding`, `developmentConstraints`, `chainOverview`, `sections`, `implementationOrder`, `risks`, `documentReferences`, and `codeReferences`.
- [ ] **Step 6: Add overflow-safe styling** using `min-width:0`, `overflow-wrap:anywhere`, flexible button groups, natural card height, and code-only horizontal scrolling.
- [ ] **Step 7: Re-run the page test** and require it to pass.

### Task 4: Bottom source drawer, fullscreen state, and compact graph controls

**Files:**
- Modify: `src/main/resources/static/monitor.html`
- Modify: `src/test/java/com/example/requirementrag/web/MonitorWorkbenchPageTest.java`

**Interfaces:**
- Produces: `sourceDrawerOpen`, `sourceFullscreen`, `toggleSourceFullscreen()`, `closeSourceDrawer()`, and document-level Escape handling.
- Changes: `loadSource(nodeOrHit)` opens the drawer and does not change `sidebarTab`.

- [ ] **Step 1: Extend the page test** to require `.source-drawer`, `.source-drawer.fullscreen`, `toggleSourceFullscreen`, `closeSourceDrawer`, Escape handling, and `.graph-floating-tools`.
- [ ] **Step 2: Run** the page test and verify the drawer assertions fail.
- [ ] **Step 3: Replace right-sidebar source content** with the related-file list only; make each file call `loadSource(file)`.
- [ ] **Step 4: Add the bottom drawer** with metadata, line-number source body, fullscreen/restore, close, backdrop, and reduced-motion behavior.
- [ ] **Step 5: Add keyboard handling** so Escape exits fullscreen first, then closes a non-fullscreen drawer.
- [ ] **Step 6: Remove the full-width graph toolbar** and place zoom, fit, and path controls in a compact top-right overlay; place counts at bottom-left.
- [ ] **Step 7: Re-run the page test** and require it to pass.

### Task 5: Regression and visual verification

**Files:**
- Verify: all files above.

- [ ] **Step 1: Run focused tests** with `mvn -Dtest=DevelopmentPlanStreamParserTest,AssistantControllerStreamTest,MonitorWorkbenchPageTest test`.
- [ ] **Step 2: Run the complete suite** with `mvn test` under Java 21 and require zero failures.
- [ ] **Step 3: Start the app on port 18080** and verify in a browser that plan sections arrive over time rather than all at once.
- [ ] **Step 4: Verify graph behavior** at 1280×720: all nodes fit initially, no document horizontal overflow, compact floating controls remain reachable.
- [ ] **Step 5: Verify source behavior**: node/file opens bottom drawer, fullscreen covers the workbench, Escape restores, close removes the overlay, source scroll position remains usable.
- [ ] **Step 6: Verify narrow layout**: text wraps inside every card and only the source body can scroll horizontally.
