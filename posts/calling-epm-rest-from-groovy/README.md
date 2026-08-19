# Calling EPM Cloud REST from Groovy via Connections

Post: [epmnerd.com/blog/calling-epm-rest-from-groovy](https://epmnerd.com/blog/calling-epm-rest-from-groovy)

One Calc Manager Groovy rule. Paste `RunPipeline.groovy` as-is. It POSTs a Data Integration pipeline through a named Connection, then polls until the job is done or the wait cap hits.

## Before you paste

1. Connections → **Other Web Service Provider**. Name it `EPM_REST`. URL is `BASE-URL` only (no `/epmcloud`).
2. Create string RTPs on the rule: `PIPELINE_NAME`, `PIPELINE_START_PERIOD`, `PIPELINE_END_PERIOD`, `PIPELINE_IMPORT_MODE`, `PIPELINE_EXPORT_MODE`, `PIPELINE_ATTACH_LOGS`, `PIPELINE_SEND_MAIL`, `PIPELINE_SEND_TO`.
3. `PIPELINE_NAME` is the Pipeline **code**, not the pretty name. Periods are Data Integration names (`Jan-26`).

## Planning STC landmines (already handled in the file)

- Closure for `callEpm`, not a nested method (`run()` wrap).
- `JsonOutput.toJson`, not `json()`.
- `Map` + `get("jobId")` after `parseText`. `job.jobId` will not save.
- No `Connection.patch`. GET / POST / PUT / DELETE only.
- JSON `status` is a string. Cap the poll loop.

## Other endpoints

Keep the class and `callEpm`. Swap the `EpmRestConfig` at the bottom. GET/DELETE use `payload: null`.
