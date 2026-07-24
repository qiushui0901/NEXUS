# Error Handling

> How errors are handled in this project.

---

## RAG Outcome Semantics

RAG stages and public development-plan responses use `RagOutcomeStatus`:

- `SUCCESS`: the stage completed and produced usable output.
- `NO_RESULTS`: the dependency call succeeded but returned no matches.
- `DEGRADED`: a non-critical stage failed or returned unusable model output, and a safe fallback is being used.
- `FAILED`: a core stage failed and there is no usable evidence to continue.

Do not translate dependency exceptions into an empty list. Empty successful results and failed retrievals must remain distinguishable.

## Warnings and Diagnostics

Public degradation details use `RagWarning` and `RagStageDiagnostic`.

- Warning codes are stable identifiers suitable for clients and metrics.
- Warning messages must be safe and deterministic.
- Never expose raw exception messages, provider URLs, credentials, request bodies, or stack traces.
- Record the internal exception through `RagObservability` while returning only the safe warning.

## API Error Responses

When document and code retrieval have no usable evidence and at least one core retrieval failed, throw `RagUnavailableException`.
`ApiExceptionHandler` maps it to HTTP 503 with:

- `outcome: FAILED`
- a safe `detail`
- the collected safe `warnings`

Existing response fields and SSE event names are compatibility contracts. Add fields or events instead of removing or renaming existing ones.

## SSE Behavior

The development-plan stream keeps `started`, `retrieval`, `references`, `completed`, and `error` events.
A degraded stage additionally emits `warning`.

- A partial model stream with at least one valid event is retained and completes as `DEGRADED`.
- A provider failure or completed stream with no valid event emits `error` and does not emit a false successful completion.
- Core retrieval failure with no evidence emits safe warnings followed by `error`.

## Common Mistakes

- Returning `NO_RESULTS` after a dependency exception.
- Marking a null or empty model response as `SUCCESS`.
- Logging or returning raw exception messages in recent-event or API payloads.
- Treating a failed health probe as `UP`.
