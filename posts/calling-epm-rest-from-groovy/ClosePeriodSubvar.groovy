/* RTPS: {RUN_TASK} */

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

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

    println "status=${response.status} ${response.statusText} ${method} ${cfg.path}"
    println response.body

    if (response.status < 200 || response.status >= 300) {
        throwVetoException("EPM REST ${method} ${cfg.path} failed: ${response.status} ${response.body}")
    }

    return response
}

// --- Custom. Close-period substitution variables. ---
//
// RUN_TASK = update | validate | dryrun
//   update   : scheduled refresh of app substitution variables
//   validate : load pipeline go/no-go (same rule, RUN_TASK=validate)
//   dryrun   : log derived values only, no REST writes
//
// Subvars written by update:
//   ClosePeriod   MMM-yy (e.g. Apr-26)
//   CloseYear     FYyy from calendar year of close period (e.g. FY26)
//   CloseMonth    MMM (e.g. Apr)
//   CloseFile     SAP_<ClosePeriod>.txt (e.g. SAP_Apr-26.txt)
//   CloseSVStatus 0 = last update succeeded, 1 = last update failed
//
// Close window:
//   Day 1–18: close period = prior month
//   After day 18: close period = current month
// Fiscal year label in logs: FY starts April, ends March (Apr-26 -> FY27).
//
// Validate reads live values via REST and compares them to what derivation
// would produce now. Pipelines freeze subvars in memory at launch — if they
// differ, the scheduled refresh has not caught up.

class RuleConfig {
    static final String CONNECTION_NAME = "EPM_REST"
    static final String REST_BASE_PATH = "/HyperionPlanning/rest"
    static final String API_VERSION = "v3"
    static final String SUB_VAR_ENDPOINT_SUFFIX = "substitutionvariables"

    static final String SUB_VAR_CLOSE_PERIOD = "ClosePeriod"
    static final String SUB_VAR_CLOSE_YEAR = "CloseYear"
    static final String SUB_VAR_CLOSE_MONTH = "CloseMonth"
    static final String SUB_VAR_CLOSE_FILE = "CloseFile"
    static final String SUB_VAR_CLOSE_SV_STATUS = "CloseSVStatus"

    static final String CLOSE_SV_STATUS_SUCCESS = "0"
    static final String CLOSE_SV_STATUS_ERROR = "1"

    static final String CLOSE_FILE_PREFIX = "SAP_"
    static final String CLOSE_FILE_EXTENSION = ".txt"
    static final String DEFAULT_PLAN_TYPE = "ALL"
    static final String CLOSE_PERIOD_PATTERN = "MMM-yy"
    static final String CLOSE_MONTH_PATTERN = "MMM"

    static final int CLOSE_WINDOW_END_DAY = 18
    static final int CLOSE_PERIOD_OFFSET_DURING_CLOSE = -1
    static final int CLOSE_PERIOD_OFFSET_AFTER_CLOSE = 0
    static final int FISCAL_YEAR_START_MONTH = Calendar.APRIL + 1

    static final String TASK_UPDATE = "update"
    static final String TASK_VALIDATE = "validate"
    static final String TASK_DRYRUN = "dryrun"
}

int determineClosePeriodOffset(int dayOfMonth) {
    if (dayOfMonth <= RuleConfig.CLOSE_WINDOW_END_DAY) {
        return RuleConfig.CLOSE_PERIOD_OFFSET_DURING_CLOSE
    }
    return RuleConfig.CLOSE_PERIOD_OFFSET_AFTER_CLOSE
}

Map deriveClosePeriod(Date runtimeDate) {
    Calendar calendar = Calendar.getInstance()
    calendar.setTime(runtimeDate)
    int dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH)
    int closePeriodOffset = determineClosePeriodOffset(dayOfMonth)
    calendar.add(Calendar.MONTH, closePeriodOffset)

    return [
        year : calendar.get(Calendar.YEAR),
        month: calendar.get(Calendar.MONTH) + 1
    ]
}

// FY label for operator logs only (Apr->Mar fiscal year).
String deriveFiscalYearLabel(Map period) {
    int month = period.month as int
    int year = period.year as int
    int fiscalYearEnd = month >= RuleConfig.FISCAL_YEAR_START_MONTH ? year + 1 : year
    return "FY${String.format('%02d', fiscalYearEnd % 100)}"
}

String formatPeriodWithPattern(Map period, String pattern) {
    Calendar calendar = Calendar.getInstance()
    calendar.clear()
    calendar.set(Calendar.YEAR, period.year as int)
    calendar.set(Calendar.MONTH, (period.month as int) - 1)
    calendar.set(Calendar.DAY_OF_MONTH, 1)
    return new SimpleDateFormat(pattern, Locale.US).format(calendar.getTime())
}

String toClosePeriodValue(Map period) {
    return formatPeriodWithPattern(period, RuleConfig.CLOSE_PERIOD_PATTERN)
}

String toCloseYearValue(Map period) {
    int year = period.year as int
    return String.format("FY%02d", year % 100)
}

String toCloseMonthValue(Map period) {
    return formatPeriodWithPattern(period, RuleConfig.CLOSE_MONTH_PATTERN)
}

String toCloseFileValue(String closePeriodValue) {
    return "${RuleConfig.CLOSE_FILE_PREFIX}${closePeriodValue}${RuleConfig.CLOSE_FILE_EXTENSION}"
}

Map buildDerivedCloseValues(Date runtimeDate) {
    Map period = deriveClosePeriod(runtimeDate)
    String closePeriodValue = toClosePeriodValue(period)

    return [
        period           : period,
        fiscalYearLabel  : deriveFiscalYearLabel(period),
        closePeriodValue : closePeriodValue,
        closeYearValue   : toCloseYearValue(period),
        closeMonthValue  : toCloseMonthValue(period),
        closeFileValue   : toCloseFileValue(closePeriodValue)
    ]
}

void logDerivedCloseValues(Date runtimeDate, Map derived) {
    println("Runtime date: ${runtimeDate}")
    println("Derived close period: ${derived.closePeriodValue} (${derived.fiscalYearLabel})")
    println("Derived close year: ${derived.closeYearValue}")
    println("Derived close month: ${derived.closeMonthValue}")
    println("Derived close file: ${derived.closeFileValue}")
}

Map buildSubVarPayload(Map variableValues) {
    List items = new ArrayList()
    variableValues.each { variableName, variableValue ->
        items.add([
            name    : variableName == null ? "" : variableName.toString(),
            value   : variableValue == null ? "" : variableValue.toString(),
            planType: RuleConfig.DEFAULT_PLAN_TYPE
        ])
    }
    return [items: items]
}

String buildSubVarPostPath(String applicationName) {
    return "${RuleConfig.REST_BASE_PATH}/${RuleConfig.API_VERSION}/applications/${applicationName}/${RuleConfig.SUB_VAR_ENDPOINT_SUFFIX}"
}

String buildSubVarGetPath(String applicationName, String subVarName) {
    return "${RuleConfig.REST_BASE_PATH}/${RuleConfig.API_VERSION}/applications/${applicationName}/${RuleConfig.SUB_VAR_ENDPOINT_SUFFIX}/${subVarName}"
}

// Closures (not methods) so STC can see callEpm — Planning wraps the rule in run().

def postSubstitutionVariables = { String applicationName, Map variableValues ->
    EpmRestConfig cfg = new EpmRestConfig(
        connectionName: RuleConfig.CONNECTION_NAME,
        method: "POST",
        path: buildSubVarPostPath(applicationName),
        payload: buildSubVarPayload(variableValues)
    )
    callEpm(cfg)
}

def getSubVarValue = { String applicationName, String subVarName ->
    EpmRestConfig cfg = new EpmRestConfig(
        connectionName: RuleConfig.CONNECTION_NAME,
        method: "GET",
        path: buildSubVarGetPath(applicationName, subVarName),
        payload: null
    )
    HttpResponse response = callEpm(cfg)

    // parseText is Object to STC. Cast to Map and use get() — body.value will not save.
    JsonSlurper slurper = new JsonSlurper()
    Map body = slurper.parseText(response.body as String) as Map
    String value = body.get("value") == null ? null : body.get("value").toString()
    if (value == null || value.trim().isEmpty()) {
        throwVetoException(
            "Substitution variable ${subVarName} is missing or empty. Body=${response.body}"
        )
    }
    return value.trim()
}

// Best-effort flag so validate can block loads after a failed update run.
def markCloseSvStatusError = { String applicationName ->
    try {
        postSubstitutionVariables(applicationName, [
            (RuleConfig.SUB_VAR_CLOSE_SV_STATUS): RuleConfig.CLOSE_SV_STATUS_ERROR
        ])
    } catch (Exception statusEx) {
        println("Unable to set CloseSVStatus=1: ${statusEx.message}")
    }
}

String normalizeTaskValue(String rawTask) {
    String task = rawTask == null ? "" : rawTask.toString().trim().toLowerCase()
    if (task.isEmpty() || "#missing".equals(task)) {
        return ""
    }
    if ("dry-run".equals(task) || "dry_run".equals(task)) {
        return RuleConfig.TASK_DRYRUN
    }
    return task
}

String readRunTask() {
    String taskRaw = rtps.RUN_TASK
    println("RUN_TASK is : ${taskRaw}")

    String task = normalizeTaskValue(taskRaw)
    if (task.isEmpty()) {
        throwVetoException("RUN_TASK runtime prompt is required. Allowed values: update, validate, dryrun.")
    }
    if (!RuleConfig.TASK_UPDATE.equals(task) &&
        !RuleConfig.TASK_VALIDATE.equals(task) &&
        !RuleConfig.TASK_DRYRUN.equals(task)) {
        throwVetoException("Invalid RUN_TASK value '${taskRaw}'. Allowed values: update, validate, dryrun.")
    }
    return task
}

def runDryRun = { Date runtimeDate, Map derived ->
    logDerivedCloseValues(runtimeDate, derived)
    println("Dry run complete. No substitution variables were updated.")
}

def runValidate = { Date runtimeDate, Map derived ->
    String applicationName = operation.application.name
    logDerivedCloseValues(runtimeDate, derived)

    String closeSvStatus = getSubVarValue(applicationName, RuleConfig.SUB_VAR_CLOSE_SV_STATUS)
    if (RuleConfig.CLOSE_SV_STATUS_ERROR.equals(closeSvStatus)) {
        throwVetoException("CloseSVStatus=1 from the last update run. Fix before loading.")
    }
    if (!RuleConfig.CLOSE_SV_STATUS_SUCCESS.equals(closeSvStatus)) {
        throwVetoException("CloseSVStatus must be 0 before loading. Actual='${closeSvStatus}'.")
    }

    List compareItems = [
        [name: RuleConfig.SUB_VAR_CLOSE_PERIOD, expected: derived.closePeriodValue],
        [name: RuleConfig.SUB_VAR_CLOSE_YEAR, expected: derived.closeYearValue],
        [name: RuleConfig.SUB_VAR_CLOSE_MONTH, expected: derived.closeMonthValue],
        [name: RuleConfig.SUB_VAR_CLOSE_FILE, expected: derived.closeFileValue]
    ]

    List mismatches = []
    compareItems.each { item ->
        String subVarName = item.name as String
        String expectedValue = item.expected as String
        String storedValue = getSubVarValue(applicationName, subVarName)
        println("Stored ${subVarName}=${storedValue}")
        if (!expectedValue.equals(storedValue)) {
            mismatches.add("${subVarName} stored='${storedValue}' expected='${expectedValue}'")
        }
    }

    if (!mismatches.isEmpty()) {
        mismatches.each { println("STALE: ${it}") }
        throwVetoException("Close subvariables are stale. Wait for the next successful update run.")
    }

    println("Close subvariable validation passed.")
}

def runUpdate = { Map derived ->
    String applicationName = operation.application.name
    boolean updateSucceeded = false

    try {
        Map postValues = [
            (RuleConfig.SUB_VAR_CLOSE_PERIOD)   : derived.closePeriodValue,
            (RuleConfig.SUB_VAR_CLOSE_YEAR)     : derived.closeYearValue,
            (RuleConfig.SUB_VAR_CLOSE_MONTH)    : derived.closeMonthValue,
            (RuleConfig.SUB_VAR_CLOSE_FILE)     : derived.closeFileValue,
            (RuleConfig.SUB_VAR_CLOSE_SV_STATUS): RuleConfig.CLOSE_SV_STATUS_SUCCESS
        ]
        postSubstitutionVariables(applicationName, postValues)
        updateSucceeded = true
        println("Successfully updated substitution variables for app ${applicationName}.")
    } finally {
        if (!updateSucceeded) {
            markCloseSvStatusError(applicationName)
        }
    }
}

String task = readRunTask()
Date runtimeDate = new Date()
Map derived = buildDerivedCloseValues(runtimeDate)

println("Dispatching RUN_TASK=${task}")

if (RuleConfig.TASK_DRYRUN.equals(task)) {
    runDryRun(runtimeDate, derived)
} else if (RuleConfig.TASK_VALIDATE.equals(task)) {
    runValidate(runtimeDate, derived)
} else {
    logDerivedCloseValues(runtimeDate, derived)
    runUpdate(derived)
}

/*
 Validation examples (Apr->Mar fiscal year):
 - runtime=2026-05-01 -> closePeriod=Apr-26 (FY27)  // during close window
 - runtime=2026-05-18 -> closePeriod=Apr-26 (FY27)  // final close day
 - runtime=2026-05-19 -> closePeriod=May-26 (FY27)  // switch after close window
 - runtime=2026-01-10 -> closePeriod=Dec-25 (FY26)  // year crossover
*/
