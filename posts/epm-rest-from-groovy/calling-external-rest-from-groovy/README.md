# Calling External REST APIs from Groovy (Part 3)

Post: [epmnerd.com/blog/calling-external-rest-from-groovy](https://epmnerd.com/blog/calling-external-rest-from-groovy)

Series: [EPM REST from Groovy via Connections](../README.md)

| File | What it does |
| --- | --- |
| `LoadFxRatesFromFrankfurter.groovy` | GET Frankfurter v2 daily series for a month, write Ending + Average CSV, run `PL-ERAPI-FCCS-RATES` |

## Setup

1. Connections → **Other Web Service Provider** named `FX_API`. URL `https://api.frankfurter.dev` only. No API key.
2. Keep `EPM_REST` from Part 1 for the pipeline POST/poll on the same pod.
3. Create string RTPs: `fxPeriod` (`Jan`…`Dec`), `fxYear` (calendar `YYYY`).
4. Edit the config block: `reportingCurrency`, `entityCurrencies`, `ratesFile`, `pipelineCode`.
5. Point pipeline `PL-ERAPI-FCCS-RATES` at `frankfurter_fccs_rates.csv`.

Uses `GET /v2/rates?from=&to=&base=&quotes=` (avoids `..` in the path).

`createFileWriter` **cannot** take a path (`inbox/…` is rejected). The file lands in **Application → Overview → Actions → Inbox/Outbox Explorer** as a bare filename. Wire the pipeline / file browser to that name.

CSV columns: `FromCurrency,ToCurrency,RateType,Rate,Period,Year,EffectiveDate`  
`RateType` is `Ending` (balance sheet / last day in series) or `Average` (P&L / mean of returned days).

Debug: `DEBUG = true` prints step lines; exceptions are caught and rethrown via `throwVetoException`. Set `DEBUG = false` when done.

Drop `YEAR` from the pipeline `variables` map if your Pipeline definition does not declare it.
