# Oracle Cloud EPM 26.11 REST endpoints: paths, payloads, and curl (Part 2)

Post: [epmnerd.com/blog/oracle-cloud-epm-26-11-rest-endpoints](https://epmnerd.com/blog/oracle-cloud-epm-26-11-rest-endpoints)

Series: [Oracle Cloud EPM 26.11 REST APIs](../README.md)

| File | What it does |
| --- | --- |
| `epm-26-11-rest.sh` | One curl command per 26.11 REST call: Get Insight Details, Execute Insight Analysis, dashboard definitions and data, the worklist, activity reports and access logs, and connections |

## Setup

1. Install `curl` and `jq`.
2. Export your pod and login. Use the service URL without `/epmcloud`, and the plain user name. 26.11 rejects the `accountName.` prefix.

   ```bash
   export EPM_URL="https://<base-url>"
   export EPM_USER="service.user@example.com"
   export EPM_PASS="<password>"
   export EPM_APP="Vision"
   ```

3. Run `./epm-26-11-rest.sh` with no arguments to list the commands, then call one, for example `./epm-26-11-rest.sh my-worklist-summary`.

Most calls need Service Administrator. Dashboard definition calls also accept a role with the Interactive User granular role.

`dash-export` runs the dashboard's load rules and callbacks, so do not retry it blindly. `dash-import` can return 201 with `FAILED` in the body, so read the body. The post covers both.

No credentials or tenant URLs live in this folder.
