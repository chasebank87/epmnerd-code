/* RTPS: {PIPELINE_NAME} {PIPELINE_START_PERIOD} {PIPELINE_END_PERIOD} {PIPELINE_IMPORT_MODE} {PIPELINE_EXPORT_MODE} {PIPELINE_ATTACH_LOGS} {PIPELINE_SEND_MAIL} {PIPELINE_SEND_TO} */

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

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

    // response.status is HTTP. The Data Integration job status is in the JSON body.
    println "status=${response.status} ${response.statusText} ${method} ${cfg.path}"
    println response.body

    if (response.status < 200 || response.status >= 300) {
        throwVetoException("EPM REST ${method} ${cfg.path} failed: ${response.status} ${response.body}")
    }

    return response
}

// --- Custom. RTPs, submit, poll. ---

// Pipeline *code* from Data Integration, not the pretty name.
String pipeline     = rtps.PIPELINE_NAME
String startPeriod  = rtps.PIPELINE_START_PERIOD
String endPeriod    = rtps.PIPELINE_END_PERIOD
String importMode   = rtps.PIPELINE_IMPORT_MODE
String exportMode   = rtps.PIPELINE_EXPORT_MODE
String attachLogs   = rtps.PIPELINE_ATTACH_LOGS
String sendMail     = rtps.PIPELINE_SEND_MAIL
String sendTo       = rtps.PIPELINE_SEND_TO

// POST accepts the job. HTTP 200 is not "the pipeline finished."
EpmRestConfig cfg = new EpmRestConfig(
    connectionName: "EPM_REST",   // named Connection: host + Basic auth only
    method: "POST",
    path: "/aif/rest/V1/jobs",    // capital V. /aif/rest/v1 will 404
    payload: [
        jobName: pipeline,
        jobType: "pipeline",      // swap to INTEGRATION / DATARULE for those jobs
        variables: [
            STARTPERIOD: startPeriod,
            ENDPERIOD:   endPeriod,
            IMPORTMODE:  importMode,
            EXPORTMODE:  exportMode,
            ATTACH_LOGS: attachLogs,
            SEND_MAIL:   sendMail,
            SEND_TO:     sendTo
        ]
    ]
)

HttpResponse response = callEpm(cfg)

// parseText is Object to STC. Cast to Map and use get() — job.jobId will not save.
JsonSlurper slurper = new JsonSlurper()
Map job = slurper.parseText(response.body as String) as Map
String jobId = job.get("jobId").toString()
int jobStatus = Integer.parseInt(job.get("status").toString())

// 40 * 15s = 10 minutes. Size under the Groovy rule timeout. Keep the cap.
int pollMs = 15000
int maxPolls = 40
int polls = 0

while (jobStatus == -1 && polls < maxPolls) {
    sleep(pollMs)
    polls++
    // Same connection. GET this job — do not POST another pipeline.
    cfg = new EpmRestConfig(
        connectionName: "EPM_REST",
        method: "GET",
        path: "/aif/rest/V1/jobs/${jobId}",
        payload: null
    )
    response = callEpm(cfg)
    job = slurper.parseText(response.body as String) as Map
    jobStatus = Integer.parseInt(job.get("status").toString())
    println "poll ${polls}/${maxPolls} jobId=${jobId} status=${jobStatus} ${job.get('jobStatus')}"
}

if (jobStatus == -1) {
    throwVetoException("Pipeline ${pipeline} still running after ${maxPolls} polls (jobId=${jobId})")
}
if (jobStatus != 0) {
    // 1 error, 2 cancel pending, 3 cancelled, 4 invalid parameter
    throwVetoException("Pipeline ${pipeline} failed: status=${jobStatus} jobId=${jobId} ${response.body}")
}
