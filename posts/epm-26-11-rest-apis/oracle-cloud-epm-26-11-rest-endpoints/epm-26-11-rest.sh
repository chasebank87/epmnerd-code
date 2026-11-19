#!/usr/bin/env bash
# Oracle Cloud EPM 26.11 REST calls with curl.
# https://epmnerd.com/blog/oracle-cloud-epm-26-11-rest-endpoints
#
#   export EPM_URL="https://<base-url>"         # service URL without /epmcloud
#   export EPM_USER="service.user@example.com"  # no accountName. prefix from 26.11 on
#   export EPM_PASS="<password>"
#   export EPM_APP="Vision"
#   ./epm-26-11-rest.sh <command> [args]
#
# Needs curl and jq. Run with no command to list the commands.
set -euo pipefail

cmd="${1:-help}"
shift || true

if [[ "$cmd" == help ]]; then
  grep -E '^  [a-z-]+\)' "$0" | sed -E 's/^  [a-z-]+\) # /  /; s/^  ([a-z-]+)\)$/  \1/'
  exit 0
fi

: "${EPM_URL:?set EPM_URL}" "${EPM_USER:?set EPM_USER}" "${EPM_PASS:?set EPM_PASS}"
APP="${EPM_APP:-Vision}"
PLAN="$EPM_URL/HyperionPlanning/rest"
AUTH=(-sS -u "$EPM_USER:$EPM_PASS")
JSON=(-H "Content-Type: application/json")

enc() { jq -rn --arg v "$1" '$v|@uri'; }

case "$cmd" in
  # Insights
  insights-list)
    curl "${AUTH[@]}" -G "$PLAN/v3/applications/$APP/objects" \
      --data-urlencode 'q={"objectType":"Insights"}' \
      --data-urlencode 'fields=id,name,objectType'
    ;;
  insight) # insight <uuid>
    INSIGHT="$1"
    curl "${AUTH[@]}" "$PLAN/v3/applications/$APP/insights/$INSIGHT?loadActuals=true"
    ;;
  insight-kca) # insight-kca <uuid>
    INSIGHT="$1"
    curl "${AUTH[@]}" "${JSON[@]}" -X POST \
      "$PLAN/v3/applications/$APP/insights/$INSIGHT/action/analyze?includeGenAiSummary=false" \
      -d '{"insightAnalysisMethod":"KEY_CONTRIBUTOR_ANALYSIS"}'
    ;;
  insight-pvm) # insight-pvm <uuid> <volume account> <price account>
    INSIGHT="$1" VOLUME="$2" PRICE="$3"
    curl "${AUTH[@]}" "${JSON[@]}" -X POST \
      "$PLAN/v3/applications/$APP/insights/$INSIGHT/action/analyze" \
      -d "$(jq -n --arg v "$VOLUME" --arg p "$PRICE" \
        '{insightAnalysisMethod: "VARIANCE_DECOMPOSITION_ANALYSIS",
          relationshipType: "Price Volume Mix", volume: $v, price: $p}')"
    ;;

  # Dashboard definitions and data
  dash-get) # dash-get <name or uuid>
    DASH="$1"
    curl "${AUTH[@]}" "$PLAN/v3/applications/$APP/dashboarddefinitions/$(enc "$DASH")"
    ;;
  dash-create) # dash-create <definition.json>
    FILE="$1"
    curl "${AUTH[@]}" "${JSON[@]}" -X POST \
      "$PLAN/v3/applications/$APP/dashboarddefinitions" -d @"$FILE"
    ;;
  dash-replace) # dash-replace <name or uuid> <definition.json>
    DASH="$1" FILE="$2"
    curl "${AUTH[@]}" "${JSON[@]}" -X PUT \
      "$PLAN/v3/applications/$APP/dashboarddefinitions/$(enc "$DASH")" -d @"$FILE"
    ;;
  dash-describe) # dash-describe <name or uuid> <description>
    DASH="$1" TEXT="$2"
    curl "${AUTH[@]}" "${JSON[@]}" -X PATCH \
      "$PLAN/v3/applications/$APP/dashboarddefinitions/$(enc "$DASH")" \
      -d "$(jq -n --arg d "$TEXT" '{description: $d}')"
    ;;
  dash-title) # dash-title <name or uuid> <component key> <title>
    DASH="$1" KEY="$2" TEXT="$3"
    curl "${AUTH[@]}" "${JSON[@]}" -X PATCH \
      "$PLAN/v3/applications/$APP/dashboarddefinitions/$(enc "$DASH")/components/$KEY" \
      -d "$(jq -n --arg t "$TEXT" '{title: $t}')"
    ;;
  dash-delete) # dash-delete <name or uuid>
    DASH="$1"
    curl "${AUTH[@]}" -X DELETE -w '%{http_code}\n' \
      "$PLAN/v3/applications/$APP/dashboarddefinitions/$(enc "$DASH")"
    ;;
  dash-export) # dash-export <name or uuid> <body.json>   runs the dashboard's load rules
    DASH="$1" FILE="$2"
    curl "${AUTH[@]}" "${JSON[@]}" -X POST \
      "$PLAN/v3/applications/$APP/dashboards/$(enc "$DASH")/exportdata" -d @"$FILE"
    ;;
  dash-import) # dash-import <name or uuid> <body.json>   check the body, not the status code
    DASH="$1" FILE="$2"
    curl "${AUTH[@]}" "${JSON[@]}" -X POST \
      "$PLAN/v3/applications/$APP/dashboards/$(enc "$DASH")/importdata" -d @"$FILE"
    ;;

  # Worklist
  worklist)
    curl "${AUTH[@]}" -G "$PLAN/tmgr/v1/worklist" \
      --data-urlencode '$offset=0' --data-urlencode '$limit=25' \
      --data-urlencode '$includeTotalItems=true'
    ;;
  my-worklist)
    curl "${AUTH[@]}" -G "$PLAN/tmgr/v1/worklist/me" \
      --data-urlencode '$fields=id,name,source,year,period' \
      --data-urlencode '$limit=25'
    ;;
  worklist-summary)
    curl "${AUTH[@]}" "$PLAN/tmgr/v1/worklistsummary"
    ;;
  my-worklist-summary)
    curl "${AUTH[@]}" "$PLAN/tmgr/v1/worklistsummary/me"
    ;;

  # Activity reports and access logs (v2)
  activity-reports)
    curl "${AUTH[@]}" "${JSON[@]}" -X POST \
      "$EPM_URL/interop/rest/v2/activityreports/list" -d '{}'
    ;;
  activity-report) # activity-report [reportId]   defaults to the latest
    REPORT="${1:-latest}"
    curl "${AUTH[@]}" "${JSON[@]}" -X POST \
      "$EPM_URL/interop/rest/v2/activityreports/$REPORT" -d '{}'
    ;;
  access-logs)
    curl "${AUTH[@]}" "${JSON[@]}" -X POST \
      "$EPM_URL/interop/rest/v2/accesslogs/list" -d '{}'
    ;;
  access-log) # access-log [accessLogId] [user]   writes access_log.csv
    LOG="${1:-latest}" LOG_USER="${2:-}"
    curl "${AUTH[@]}" "${JSON[@]}" -X POST -o access_log.csv \
      "$EPM_URL/interop/rest/v2/accesslogs/$LOG" \
      -d "$(jq -n --arg u "$LOG_USER" 'if $u == "" then {} else {user: $u} end')"
    ;;

  # Connections (v2)
  conn-get) # conn-get <name or uuid>
    CONN="$1"
    curl "${AUTH[@]}" "$PLAN/epm/v2/applications/$APP/connections/$(enc "$CONN")"
    ;;
  conn-create) # conn-create <connection.json>
    FILE="$1"
    curl "${AUTH[@]}" "${JSON[@]}" -X POST \
      "$PLAN/epm/v2/applications/$APP/connections" -d @"$FILE"
    ;;
  conn-update) # conn-update <uuid> <changes.json>   partial update
    CONN="$1" FILE="$2"
    curl "${AUTH[@]}" "${JSON[@]}" -X PUT \
      "$PLAN/epm/v2/applications/$APP/connections/$CONN" -d @"$FILE"
    ;;
  conn-delete) # conn-delete <uuid>   Oracle has not documented the response
    CONN="$1"
    curl "${AUTH[@]}" -X DELETE -w '%{http_code}\n' \
      "$PLAN/epm/v2/applications/$APP/connections/$CONN"
    ;;

  *)
    grep -E '^  [a-z-]+\)' "$0" | sed -E 's/^  [a-z-]+\) # /  /; s/^  ([a-z-]+)\)$/  \1/'
    ;;
esac
