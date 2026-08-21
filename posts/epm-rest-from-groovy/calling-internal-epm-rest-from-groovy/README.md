# Calling Internal EPM REST APIs from Groovy (Part 2)

Post: [epmnerd.com/blog/calling-internal-epm-rest-from-groovy](https://epmnerd.com/blog/calling-internal-epm-rest-from-groovy)

Series: [EPM REST from Groovy via Connections](../README.md)

All files share the same `EpmRestConfig` + `callEpm` boilerplate from the post. Paste one rule at a time.

| File | What it does |
| --- | --- |
| `RunPipeline.groovy` | POST a Data Integration pipeline, poll until done |
| `ClosePeriodSubvar.groovy` | Derive close-period subvars; `update` / `validate` / `dryrun` |
| `ValidateEdmMetadataPreload.groovy` | Gate metadata import: EDM inbox vs FCCS REST before load |
| `ValidateEdmMetadataPostload.groovy` | Reconcile staged EDM inbox files vs FCCS dimension REST after load |

## Shared setup

1. Connections → **Other Web Service Provider**. Name it `EPM_REST`. URL is `BASE-URL` only (no `/epmcloud`). See [Part 1](../named-connections-for-epm-rest/).
2. Planning STC landmines already handled in these files:
   - Closure for `callEpm`, not a nested method (`run()` wrap).
   - Anything that calls `callEpm` must also be a closure (methods cannot see it).
   - `JsonOutput.toJson`, not `json()`.
   - `Map` + `get("…")` after `parseText`.
   - No `Connection.patch`. GET / POST / PUT / DELETE only.

## RunPipeline.groovy

Create string RTPs: `PIPELINE_NAME`, `PIPELINE_START_PERIOD`, `PIPELINE_END_PERIOD`, `PIPELINE_IMPORT_MODE`, `PIPELINE_EXPORT_MODE`, `PIPELINE_ATTACH_LOGS`, `PIPELINE_SEND_MAIL`, `PIPELINE_SEND_TO`.

`PIPELINE_NAME` is the Pipeline **code**, not the pretty name. Periods are Data Integration names (`Jan-26`). Cap the poll loop — JSON `status` is a string.

## ClosePeriodSubvar.groovy

Create string RTP: `RUN_TASK` (`update` | `validate` | `dryrun`).

- **update** — scheduled refresh (~every 15 min). Writes `ClosePeriod`, `CloseYear`, `CloseMonth`, `CloseFile`, `CloseSVStatus`.
- **validate** — load pipeline go/no-go. Requires `CloseSVStatus=0`, then compares live REST values to what derivation would produce now (stale check for pipelines that freeze subvars at launch).
- **dryrun** — log derived values only; no REST writes.

Close window: day 1–18 → prior month; after day 18 → current month. Fiscal year label in logs starts April.

## ValidateEdmMetadataPreload.groovy

Create string RTPs: `bypassDeltaCheck`, `bypassMemberMatchCheck` (`true`/`false`/`yes`/`no`/`1`/`0`). Empty uses defaults (both false).

Edit `ValidationConfig` before paste: `DIMENSIONS`, `EDM_FILE_NAME_TOKEN` (default `EDMCS_`), optional name maps, thresholds (`MAX_ABSOLUTE_DELTA`, `MAX_PERCENT_DELTA`).

Expects EDM `.txt` extracts in the inbox and reads FCCS members via Planning `v3` dimension details (`Consol`). Blocks the pipeline when FCCS has members missing from EDM, or count deltas exceed thresholds — unless the matching bypass RTP is true.

## ValidateEdmMetadataPostload.groovy

No RTPs. Edit `ValidationConfig` before paste:

- `DIMENSIONS` — dimensions to check
- `EDM_FILE_NAME_TOKEN` — inbox filename discriminator (default `EDMCS_`)
- optional `EDM_DIMENSION_SUFFIX_MAP` / `FCCS_DIMENSION_NAME_MAP` when names differ
- thresholds: `MAX_POSTLOAD_ABSOLUTE_DELTA`, `MAX_POSTLOAD_PERCENT_DELTA`

Expects EDM `.txt` extracts in the inbox and reads FCCS members via Planning `v3` dimension details (`Consol` plan type). Fails the rule if FCCS has members missing from EDM, or count deltas exceed the thresholds.

## Other endpoints

Keep the class and `callEpm`. Swap the custom block. GET/DELETE use `payload: null`.
