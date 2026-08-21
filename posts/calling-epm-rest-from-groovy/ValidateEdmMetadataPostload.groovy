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

    println "status=${response.status} ${response.statusText} ${method} ${cfg.path}"
    println response.body

    if (response.status < 200 || response.status >= 300) {
        throwVetoException("EPM REST ${method} ${cfg.path} failed: ${response.status} ${response.body}")
    }

    return response
}

// --- Custom. Post-load EDM vs FCCS metadata validation. ---
//
// Compares staged EDM extract files in the inbox to FCCS dimension members
// from Get Dimension Details (Planning REST), then pass/fail the load.
//
// Configure DIMENSIONS, EDM_FILE_NAME_TOKEN, and optional name maps below.

class ValidationConfig {
    static final int MAX_POSTLOAD_ABSOLUTE_DELTA = 25
    static final BigDecimal MAX_POSTLOAD_PERCENT_DELTA = 0.02G
    static final int MAX_MISMATCH_SAMPLE = 15

    static final String CONNECTION_NAME = "EPM_REST"
    static final String FILES_LIST_PATH = "/interop/rest/v2/files/list"
    static final String REST_BASE_PATH = "/HyperionPlanning/rest"
    static final String API_VERSION = "v3"
    // FCCS default consolidation cube/plan type.
    static final String FCCS_PLAN_TYPE = "Consol"

    // EDM exports are plain text files under the repository inbox.
    static final String EDM_EXTRACT_ROOT = "inbox/"

    static final List<String> DIMENSIONS = [
        "Account",
        "Entity",
        "Channel",
        "Location_Department",
        "Activity"
    ]
    // Optional when EDM file suffix and FCCS dimension name differ.
    static final Map<String, String> EDM_DIMENSION_SUFFIX_MAP = [:]
    static final Map<String, String> FCCS_DIMENSION_NAME_MAP = [:]

    static final List<String> EDM_ALLOWED_EXTENSIONS = [".txt"]
    // Discriminator so multiple .txt files in inbox do not collide. Match your export naming.
    static final String EDM_FILE_NAME_TOKEN = "EDMCS_"
}

class DeltaEvaluator {
    Map evaluate(String dimensionName, int sourceCount, int appCount) {
        int deltaAbs = Math.abs(sourceCount - appCount)
        BigDecimal deltaPct = appCount == 0
            ? (sourceCount == 0 ? 0G : 1G)
            : (new BigDecimal(deltaAbs) / new BigDecimal(Math.abs(appCount)))

        boolean blocked = (deltaAbs > ValidationConfig.MAX_POSTLOAD_ABSOLUTE_DELTA) ||
            (deltaPct > ValidationConfig.MAX_POSTLOAD_PERCENT_DELTA)
        return [
            status   : blocked ? "blocked" : "ok",
            source   : sourceCount,
            app      : appCount,
            deltaAbs : deltaAbs,
            deltaPct : deltaPct
        ]
    }
}

Set extractDistinctMembersFromEdmFile(String filePath) {
    Set memberKeys = new LinkedHashSet()
    boolean isFirstRow = true

    csvIterator(filePath).each { row ->
        if (row == null || row.length == 0) {
            return
        }

        String firstCol = row[0] == null ? "" : row[0].toString().trim()
        if (firstCol.isEmpty()) {
            return
        }

        if (isFirstRow) {
            isFirstRow = false
            String hdr = firstCol.toLowerCase()
            if (hdr.contains("member") || hdr.contains("name") || hdr.contains("node")) {
                return
            }
        }
        if (firstCol.startsWith("#")) {
            return
        }
        memberKeys.add(firstCol)
    }

    return memberKeys
}

String encodePathSegment(String value) {
    if (value == null) {
        return ""
    }
    StringBuilder encoded = new StringBuilder()
    value.toCharArray().each { ch ->
        int code = (int) ch
        boolean isSafe = (code >= 48 && code <= 57) ||
            (code >= 65 && code <= 90) ||
            (code >= 97 && code <= 122) ||
            code == 45 || code == 46 || code == 95 || code == 126
        if (isSafe) {
            encoded.append((char) code)
        } else {
            encoded.append(String.format("%%%02X", code))
        }
    }
    return encoded.toString()
}

String buildDimensionDetailsPath(String applicationName, String planType, String dimensionName) {
    String appSegment = encodePathSegment(applicationName)
    String planTypeSegment = encodePathSegment(planType)
    String dimSegment = encodePathSegment(dimensionName)
    return "${ValidationConfig.REST_BASE_PATH}/${ValidationConfig.API_VERSION}/applications/${appSegment}/plantypes/${planTypeSegment}/dimensions/${dimSegment}"
}

void collectHierarchyMembers(Object node, Set memberKeys) {
    if (node == null) {
        return
    }
    if (node instanceof List) {
        ((List) node).each { child -> collectHierarchyMembers(child, memberKeys) }
        return
    }
    if (!(node instanceof Map)) {
        return
    }

    Map currentNode = (Map) node
    Object nameObj = currentNode.get("name")
    String name = nameObj == null ? "" : nameObj.toString().trim()
    if (!name.isEmpty()) {
        memberKeys.add(name)
    }
    if (currentNode.containsKey("children")) {
        collectHierarchyMembers(currentNode.get("children"), memberKeys)
    }
}

void collectJsonNames(Object node, List fileNames) {
    if (node == null) {
        return
    }
    if (node instanceof List) {
        ((List) node).each { child -> collectJsonNames(child, fileNames) }
        return
    }
    if (!(node instanceof Map)) {
        return
    }
    Map current = (Map) node
    if (current.containsKey("name") && current.get("name") != null) {
        fileNames.add(current.get("name").toString())
    }
    current.each { key, value ->
        if (value instanceof Map || value instanceof List) {
            collectJsonNames(value, fileNames)
        }
    }
}

Map compareMemberSets(Set sourceMembers, Set targetMembers) {
    Set sourceOnly = new LinkedHashSet(sourceMembers)
    sourceOnly.removeAll(targetMembers)

    Set targetOnly = new LinkedHashSet(targetMembers)
    targetOnly.removeAll(sourceMembers)

    return [
        exactMatch              : sourceOnly.isEmpty() && targetOnly.isEmpty(),
        targetContainedInSource : targetOnly.isEmpty(),
        sourceOnly              : sourceOnly,
        targetOnly              : targetOnly
    ]
}

String previewMemberSet(Set members) {
    if (members == null || members.isEmpty()) {
        return "[]"
    }

    int maxItems = ValidationConfig.MAX_MISMATCH_SAMPLE
    List sample = []
    int index = 0
    members.each { memberName ->
        if (index < maxItems) {
            sample.add(memberName == null ? "" : memberName.toString())
        }
        index++
    }
    int remaining = members.size() - sample.size()
    String more = remaining > 0 ? ", ... (+${remaining} more)" : ""
    return "[${sample.join(', ')}${more}]"
}

String normalizeToken(String value) {
    return value == null ? "" : value.toUpperCase().replaceAll(/[^A-Z0-9]/, "")
}

Set normalizeMembersForComparison(Set memberNames, List excludedRootAliases) {
    Set filtered = new LinkedHashSet()
    Set excludedTokens = new LinkedHashSet()

    excludedRootAliases.each { alias ->
        String token = normalizeToken(alias == null ? null : alias.toString())
        if (!token.isEmpty()) {
            excludedTokens.add(token)
        }
    }

    memberNames.each { memberName ->
        String token = normalizeToken(memberName == null ? null : memberName.toString())
        if (!token.isEmpty() && !excludedTokens.contains(token)) {
            filtered.add(memberName)
        }
    }
    return filtered
}

String normalizeRepoPath(String value) {
    if (value == null) {
        return ""
    }
    String v = value.trim()
    while (v.startsWith("/")) {
        v = v.substring(1)
    }
    return v
}

boolean isPathUnderRoot(String filePath, String rootPrefix) {
    String fp = normalizeRepoPath(filePath)
    String rp = normalizeRepoPath(rootPrefix)
    if (rp.isEmpty() || "inbox".equalsIgnoreCase(rp) || "inbox/".equalsIgnoreCase(rp)) {
        return true
    }
    if (fp.startsWith(rp)) {
        return true
    }
    if (rp.toLowerCase().startsWith("inbox/")) {
        String withoutInbox = rp.substring("inbox/".length())
        return fp.startsWith(withoutInbox)
    }
    return false
}

String extractBaseName(String pathValue) {
    int idx = pathValue.lastIndexOf("/")
    return idx >= 0 ? pathValue.substring(idx + 1) : pathValue
}

String removeExtension(String baseName) {
    int idx = baseName.lastIndexOf(".")
    return idx > 0 ? baseName.substring(0, idx) : baseName
}

String resolveDimensionFileBySuffix(
    List repositoryFiles,
    String rootPrefix,
    String dimensionName,
    List allowedExtensions,
    String label,
    String requiredFileNameToken
) {
    String normalizedDim = normalizeToken(dimensionName)
    List candidates = []

    repositoryFiles.each { filePath ->
        String path = filePath == null ? "" : filePath.toString()
        if (!isPathUnderRoot(path, rootPrefix)) {
            return
        }
        String baseName = extractBaseName(path)
        String lowerBase = baseName.toLowerCase()
        boolean extMatch = allowedExtensions.any { ext ->
            lowerBase.endsWith(ext.toString().toLowerCase())
        }
        if (!extMatch) {
            return
        }
        if (requiredFileNameToken != null && !requiredFileNameToken.isEmpty()) {
            if (!baseName.toLowerCase().contains(requiredFileNameToken.toLowerCase())) {
                return
            }
        }
        String normalizedStem = normalizeToken(removeExtension(baseName))
        if (normalizedStem.endsWith(normalizedDim)) {
            candidates.add(path)
        }
    }

    if (candidates.isEmpty()) {
        String tokenMsg = (requiredFileNameToken == null || requiredFileNameToken.isEmpty())
            ? ""
            : ", token contains '${requiredFileNameToken}'"
        throwVetoException(
            "${label} file not found for dimension ${dimensionName}. Root=${rootPrefix}, " +
            "expected suffix *${dimensionName}{${allowedExtensions.join(',')}}${tokenMsg}"
        )
    }
    if (candidates.size() > 1) {
        throwVetoException(
            "${label} file is ambiguous for dimension ${dimensionName}. Matches=${candidates}. " +
            "Keep only one matching file per dimension in ${rootPrefix}"
        )
    }
    return candidates[0].toString()
}

// Closures (not methods) so STC can see callEpm — Planning wraps the rule in run().

def listRepositoryFileNames = {
    EpmRestConfig cfg = new EpmRestConfig(
        connectionName: ValidationConfig.CONNECTION_NAME,
        method: "GET",
        path: ValidationConfig.FILES_LIST_PATH,
        payload: null
    )
    HttpResponse response = callEpm(cfg)

    JsonSlurper slurper = new JsonSlurper()
    Object payload = slurper.parseText(response.body as String)
    List fileNames = []
    collectJsonNames(payload, fileNames)
    return fileNames
}

def extractDistinctMembersFromFccsDimensionApi = { String applicationName, String dimensionName ->
    String path = buildDimensionDetailsPath(
        applicationName,
        ValidationConfig.FCCS_PLAN_TYPE,
        dimensionName
    )
    EpmRestConfig cfg = new EpmRestConfig(
        connectionName: ValidationConfig.CONNECTION_NAME,
        method: "GET",
        path: path,
        payload: null
    )
    HttpResponse response = callEpm(cfg)

    JsonSlurper slurper = new JsonSlurper()
    Object payload = slurper.parseText(response.body as String)
    Map payloadMap = payload instanceof Map ? (Map) payload : [:]
    Set memberKeys = new LinkedHashSet()
    collectHierarchyMembers(payloadMap.get("children"), memberKeys)

    if (memberKeys.isEmpty()) {
        throwVetoException(
            "FCCS dimension ${dimensionName} returned no members from REST path ${path}. " +
            "Confirm plan type ${ValidationConfig.FCCS_PLAN_TYPE} and dimension name are correct."
        )
    }
    return memberKeys
}

def runPostloadValidation = {
    DeltaEvaluator evaluator = new DeltaEvaluator()
    List repositoryFiles = listRepositoryFileNames()

    List blockedMessages = []
    List passMessages = []

    ValidationConfig.DIMENSIONS.each { dimName ->
        String edmSuffix = ValidationConfig.EDM_DIMENSION_SUFFIX_MAP.containsKey(dimName)
            ? ValidationConfig.EDM_DIMENSION_SUFFIX_MAP[dimName].toString()
            : dimName.toString()
        String fccsDimensionName = ValidationConfig.FCCS_DIMENSION_NAME_MAP.containsKey(dimName)
            ? ValidationConfig.FCCS_DIMENSION_NAME_MAP[dimName].toString()
            : dimName.toString()

        String edmFilePath = resolveDimensionFileBySuffix(
            repositoryFiles,
            ValidationConfig.EDM_EXTRACT_ROOT,
            edmSuffix,
            ValidationConfig.EDM_ALLOWED_EXTENSIONS,
            "EDM",
            ValidationConfig.EDM_FILE_NAME_TOKEN
        )

        Set sourceMembers = normalizeMembersForComparison(
            extractDistinctMembersFromEdmFile(edmFilePath),
            [dimName.toString(), edmSuffix, fccsDimensionName]
        )
        int sourceCount = sourceMembers.size()

        Set appMembers
        try {
            appMembers = extractDistinctMembersFromFccsDimensionApi(
                operation.application.name,
                fccsDimensionName
            )
        } catch (Exception ex) {
            blockedMessages.add(
                "[${dimName}] FCCS dimension missing/unreadable via REST (${fccsDimensionName}). ${ex.message}".toString()
            )
            return
        }

        int appCount = appMembers.size()
        Map result = evaluator.evaluate(dimName.toString(), sourceCount, appCount)

        Map comparison = compareMemberSets(sourceMembers, appMembers)
        boolean targetContainedInSource = comparison.targetContainedInSource as boolean
        Set edmOnlyMembers = (Set) comparison.sourceOnly
        Set fccsOnlyMembers = (Set) comparison.targetOnly
        if (!targetContainedInSource) {
            String mismatchMessage = (
                "[${dimName}] member-name mismatch (FCCS members missing in EDM). " +
                "fccsOnly=${previewMemberSet(fccsOnlyMembers)}, " +
                "edmCount=${sourceCount}, fccsCount=${appCount}"
            ).toString()
            blockedMessages.add(mismatchMessage)
            return
        }

        BigDecimal deltaPct = (result.deltaPct as BigDecimal) * 100G
        String message = (
            "[${dimName}] edmFile=${edmFilePath}, fccsDimension=${fccsDimensionName}, " +
            "source=${result.source}, app=${result.app}, deltaAbs=${result.deltaAbs}, " +
            "deltaPct=${deltaPct.setScale(2, BigDecimal.ROUND_HALF_UP)}%, " +
            "edmOnly=${previewMemberSet(edmOnlyMembers)}"
        ).toString()
        if ("blocked".equals(result.status)) {
            blockedMessages.add(message)
        } else {
            passMessages.add(message)
        }
    }

    passMessages.each { println("POSTCHECK OK: ${it}") }
    if (!blockedMessages.isEmpty()) {
        blockedMessages.each { println("POSTCHECK BLOCK: ${it}") }
        throwVetoException(
            "Metadata post-load validation failed. One or more dimensions differ from source beyond thresholds " +
            "(MAX_POSTLOAD_ABSOLUTE_DELTA=${ValidationConfig.MAX_POSTLOAD_ABSOLUTE_DELTA}, " +
            "MAX_POSTLOAD_PERCENT_DELTA=${ValidationConfig.MAX_POSTLOAD_PERCENT_DELTA})."
        )
    }

    println("FCCS metadata source: REST endpoint")
    println("FCCS plan type: ${ValidationConfig.FCCS_PLAN_TYPE}")
    println("EDM extract root: ${ValidationConfig.EDM_EXTRACT_ROOT}")
    println("Metadata post-load validation passed against FCCS REST metadata.")
}

runPostloadValidation()
