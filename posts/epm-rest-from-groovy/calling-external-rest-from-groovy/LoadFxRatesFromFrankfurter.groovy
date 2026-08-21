/* RTPS: {fxPeriod} {fxYear} */

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.HashMap
import java.util.List
import java.util.Map

// --- Boilerplate. Paste once. Do not edit. ---
// Connection has get/post/put/delete — no patch.
// Use JsonOutput.toJson, not json(). This is a closure on purpose:
// Planning injects the rule into run(), so a nested method will not save.

class EpmRestConfig {
    String connectionName
    String method
    String path
    Object payload
}

def callEpm = { EpmRestConfig cfg ->
    def conn = operation.application.getConnection(cfg.connectionName)
    String method = cfg.method.toUpperCase()
    String jsonBody = cfg.payload == null ? null : JsonOutput.toJson(cfg.payload)
    HttpResponse response

    switch (method) {
        case "GET":
            response = conn.get(cfg.path).asString()
            break
        case "POST":
            response = jsonBody == null
                ? conn.post(cfg.path).asString()
                : conn.post(cfg.path)
                    .body(jsonBody)
                    .asString()
            break
        case "PUT":
            response = jsonBody == null
                ? conn.put(cfg.path).asString()
                : conn.put(cfg.path)
                    .body(jsonBody)
                    .asString()
            break
        case "DELETE":
            response = conn.delete(cfg.path).asString()
            break
        default:
            throw new IllegalArgumentException(
                "Unsupported method: ${cfg.method}. Use GET, POST, PUT, or DELETE."
            )
    }

    println "status=" + response.status + " " + response.statusText + " " + method + " " + cfg.path

    if (response.status < 200 || response.status >= 300) {
        throwVetoException("EPM REST " + method + " " + cfg.path + " failed: " + response.status + " " + response.body)
    }

    return response
}

// --- Config. Edit for your app. ---

String fxConnection       = "FX_API"      // external: https://api.frankfurter.dev
String epmConnection      = "EPM_REST"    // same pod: pipeline + poll
String reportingCurrency  = "USD"
List entityCurrencies     = ["EUR", "GBP", "CAD", "MXN", "JPY"] as List
String ratesFile          = "frankfurter_fccs_rates.csv"
String pipelineCode       = "PL-ERAPI-FCCS-RATES"
// Out-of-box pipeline variables (same keys as Part 2). Edit to match your Pipeline.
String importMode         = "Replace"
String exportMode         = "None"
String attachLogs         = "Yes"
String sendMail           = "Always"
String sendTo             = "john.doe@email.com"

Map periodToMonth = new HashMap()
periodToMonth.put("Jan", Integer.valueOf(1))
periodToMonth.put("Feb", Integer.valueOf(2))
periodToMonth.put("Mar", Integer.valueOf(3))
periodToMonth.put("Apr", Integer.valueOf(4))
periodToMonth.put("May", Integer.valueOf(5))
periodToMonth.put("Jun", Integer.valueOf(6))
periodToMonth.put("Jul", Integer.valueOf(7))
periodToMonth.put("Aug", Integer.valueOf(8))
periodToMonth.put("Sep", Integer.valueOf(9))
periodToMonth.put("Oct", Integer.valueOf(10))
periodToMonth.put("Nov", Integer.valueOf(11))
periodToMonth.put("Dec", Integer.valueOf(12))

try {
    String period = rtps.fxPeriod.toString()
    String year   = rtps.fxYear.toString()

    String periodKey = period.length() >= 3 ? period.substring(0, 3) : period
    periodKey = periodKey.substring(0, 1).toUpperCase() + periodKey.substring(1).toLowerCase()
    Object monthObj = periodToMonth.get(periodKey)
    if (monthObj == null) {
        throwVetoException("Unknown fxPeriod=" + period + ". Expected Jan..Dec (optional suffix ok).")
    }
    int monthNum = Integer.parseInt(monthObj.toString())

    StringBuilder yearDigitsBuf = new StringBuilder()
    int yi = 0
    while (yi < year.length()) {
        char ch = year.charAt(yi)
        if (ch >= (char) 48 && ch <= (char) 57) {
            yearDigitsBuf.append(ch)
        }
        yi++
    }
    String yearDigits = yearDigitsBuf.toString()
    if (yearDigits.length() == 0) {
        throwVetoException("fxYear has no digits: " + year)
    }
    int calYear = Integer.parseInt(yearDigits)
    if (calYear < 100) {
        calYear = 2000 + calYear
    }

    int lastDay = 31
    if (monthNum == 4 || monthNum == 6 || monthNum == 9 || monthNum == 11) {
        lastDay = 30
    } else if (monthNum == 2) {
        boolean leap = (calYear % 4 == 0 && (calYear % 100 != 0 || calYear % 400 == 0))
        lastDay = leap ? 29 : 28
    }

    String monthNumPad = monthNum < 10 ? "0" + monthNum : "" + monthNum
    String lastDayPad = lastDay < 10 ? "0" + lastDay : "" + lastDay
    String fromDate = "" + calYear + "-" + monthNumPad + "-01"
    String toDate = "" + calYear + "-" + monthNumPad + "-" + lastDayPad

    StringBuilder symbols = new StringBuilder()
    int s = 0
    while (s < entityCurrencies.size()) {
        if (s > 0) {
            symbols.append(",")
        }
        symbols.append(entityCurrencies.get(s).toString())
        s++
    }

    // v2 query form avoids ".." in the path (Connections can choke on that).
    String fxPath = "/v2/rates?from=" + fromDate + "&to=" + toDate +
        "&base=" + reportingCurrency + "&quotes=" + symbols.toString()

    EpmRestConfig cfg = new EpmRestConfig(
        connectionName: fxConnection,
        method: "GET",
        path: fxPath,
        payload: null
    )

    HttpResponse response = callEpm(cfg)
    JsonSlurper slurper = new JsonSlurper()
    Object parsed = slurper.parseText(response.body as String)
    if (!(parsed instanceof List)) {
        throwVetoException("Frankfurter v2 expected a JSON array. Got: " + parsed.getClass().getName())
    }
    List rows = (List) parsed
    if (rows.isEmpty()) {
        throwVetoException("Frankfurter returned no rows for " + fromDate + ".." + toDate)
    }

    // Per currency: running sum/count + last date/rate (rows are chronological).
    Map sumByCcy = new HashMap()
    Map countByCcy = new HashMap()
    Map endDateByCcy = new HashMap()
    Map endRateByCcy = new HashMap()

    int r = 0
    while (r < rows.size()) {
        Object rowObj = rows.get(r)
        Map row = (Map) rowObj
        String day = row.get("date").toString()
        String quote = row.get("quote").toString()
        BigDecimal rate = new BigDecimal(row.get("rate").toString())

        BigDecimal sum = (BigDecimal) sumByCcy.get(quote)
        Integer count = (Integer) countByCcy.get(quote)
        if (sum == null) {
            sum = BigDecimal.ZERO
            count = Integer.valueOf(0)
        }
        sumByCcy.put(quote, sum.add(rate))
        countByCcy.put(quote, Integer.valueOf(count.intValue() + 1))
        endDateByCcy.put(quote, day)
        endRateByCcy.put(quote, rate)
        r++
    }

    StringBuilder csv = new StringBuilder()
    csv.append("FromCurrency,ToCurrency,RateType,Rate,Period,Year,EffectiveDate\n")

    int i = 0
    while (i < entityCurrencies.size()) {
        String ccy = entityCurrencies.get(i).toString()
        Integer count = (Integer) countByCcy.get(ccy)
        BigDecimal sum = (BigDecimal) sumByCcy.get(ccy)
        String endDate = (String) endDateByCcy.get(ccy)
        BigDecimal endRate = (BigDecimal) endRateByCcy.get(ccy)

        if (count == null || count.intValue() == 0 || endDate == null || endRate == null || sum == null) {
            throwVetoException("No Frankfurter rates for " + ccy + " in " + fromDate + ".." + toDate)
        }

        BigDecimal avgRate = sum.divide(new BigDecimal(count.intValue()), 8, RoundingMode.HALF_UP)

        csv.append(ccy).append(",").append(reportingCurrency).append(",Ending,")
            .append(endRate.toPlainString()).append(",").append(period).append(",")
            .append(String.valueOf(calYear)).append(",").append(endDate).append("\n")
        csv.append(ccy).append(",").append(reportingCurrency).append(",Average,")
            .append(avgRate.toPlainString()).append(",").append(period).append(",")
            .append(String.valueOf(calYear)).append(",").append(endDate).append("\n")
        i++
    }

    Writer writer = null
    try {
        writer = createFileWriter(ratesFile)
        writer.write(csv.toString())
    } finally {
        if (writer != null) {
            writer.close()
        }
    }
    println "Wrote " + ratesFile + " Ending+Average for " + entityCurrencies.size() + " currencies " + fromDate + ".." + toDate

    cfg = new EpmRestConfig(
        connectionName: epmConnection,
        method: "POST",
        path: "/aif/rest/V1/jobs",
        payload: [
            jobName: pipelineCode,
            jobType: "pipeline",
            variables: [
                STARTPERIOD: period,
                ENDPERIOD:   period,
                IMPORTMODE:  importMode,
                EXPORTMODE:  exportMode,
                ATTACH_LOGS: attachLogs,
                SEND_MAIL:   sendMail,
                SEND_TO:     sendTo
            ]
        ]
    )

    response = callEpm(cfg)
    Map job = slurper.parseText(response.body as String) as Map
    String jobId = job.get("jobId").toString()
    int jobStatus = Integer.parseInt(job.get("status").toString())

    int pollMs = 15000
    int maxPolls = 40
    int polls = 0

    while (jobStatus == -1 && polls < maxPolls) {
        sleep(pollMs)
        polls++
        cfg = new EpmRestConfig(
            connectionName: epmConnection,
            method: "GET",
            path: "/aif/rest/V1/jobs/" + jobId,
            payload: null
        )
        response = callEpm(cfg)
        job = slurper.parseText(response.body as String) as Map
        jobStatus = Integer.parseInt(job.get("status").toString())
        println "poll " + polls + "/" + maxPolls + " jobId=" + jobId + " status=" + jobStatus + " " + job.get("jobStatus")
    }

    if (jobStatus == -1) {
        throwVetoException("Pipeline " + pipelineCode + " still running after " + maxPolls + " polls (jobId=" + jobId + ")")
    }
    if (jobStatus != 0) {
        throwVetoException("Pipeline " + pipelineCode + " failed: status=" + jobStatus + " jobId=" + jobId + " " + response.body)
    }
} catch (Exception e) {
    String msg = e.getMessage()
    if (msg == null || msg.length() == 0) {
        msg = e.toString()
    }
    throwVetoException("FX rule failed: " + msg)
}
