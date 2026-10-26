# Databricks JDBC for Essbase 21c (Part 1)

Post: [epmnerd.com/blog/databricks-jdbc-essbase-21c](https://epmnerd.com/blog/databricks-jdbc-essbase-21c)

Series: [Databricks JDBC for Essbase 21c](../README.md)

**This is an unofficial custom build.** It is not a Databricks release. Prefer an official Java 8 binary if Databricks ever ships one. Until then, rebuild from their [`jdk-8`](https://github.com/databricks/databricks-jdbc/tree/jdk-8) branch, which now has the `Auth_Scope` fix in source.

| File | What it is |
| --- | --- |
| `databricks-jdbc-3.2.2-jdk8-authscope.1.jar` | Shaded uber JAR, ~37 MB, class files are Java 8 |
| `databricks-jdbc-3.2.2-jdk8-authscope.1.jar.sha256` | SHA-256 of that JAR |
| `jdk8-m2m-auth-scope.patch` | Diff against `databricks/databricks-jdbc` branch `jdk-8` at `6d3a31b3` |
| `TestConnect.java` | Java 8 live test. Host, warehouse, client id, and secret come from the environment |
| `LICENSE` / `NOTICE` | Apache 2.0 from upstream, required to redistribute the JAR |

Driver class: `com.databricks.client.jdbc.Driver`

SHA-256: `4518285cb36068e42a55939d8cdf49b00ebee7150ae7fb31dd18d1c1b8925d9f`

## Drop it on Essbase 21c

1. Stop the Essbase services that load the JDBC driver.
2. Remove the existing Databricks JDBC JAR. Keep exactly one Databricks driver on the classpath. That includes any `DatabricksJDBC42.jar` (Simba) under the same class name.
3. Copy this JAR into place and check its SHA-256 against the value above.
4. URL. Put `OAuth2Secret` in a credential field where Essbase allows it, rather than baking it into a stored URL:

```text
jdbc:databricks://<workspace-host>:443/default;transportMode=http;ssl=1;httpPath=/sql/1.0/warehouses/<warehouse-id>;AuthMech=11;Auth_Flow=1;OAuth2ClientId=<client-id>;OAuth2Secret=<secret>;Auth_Scope=sql
```

The property is `Auth_Scope`, with the underscore. `AuthScope` is ignored.

## Live test

```bash
export DBX_HOST=adb-xxxx.azuredatabricks.net
export DBX_WAREHOUSE_ID=xxxx
export DBX_CLIENT_ID=xxxx
read -rs DBX_SECRET && export DBX_SECRET
javac -source 1.8 -target 1.8 TestConnect.java
"$(/usr/libexec/java_home -v 1.8)/bin/java" -cp databricks-jdbc-3.2.2-jdk8-authscope.1.jar:. TestConnect
```

No credentials in this repo. Do not commit a filled-in URL.

## Rebuild

This JAR was built from `jdk-8` @ `6d3a31b3` (2026-03-12) with the patch applied, Temurin 1.8.0_504, and Maven 3.10.0:

```bash
git clone https://github.com/databricks/databricks-jdbc.git
cd databricks-jdbc
git checkout 6d3a31b3
git apply /path/to/jdk8-m2m-auth-scope.patch
mvn -B -DskipTests clean package
```

The shaded JAR lands in `assembly-uber/target/`.

As of 2026-10-26 the `jdk-8` branch has been synced to 3.4.3 and already contains the scope fix. A Java 8 shop can build that branch and skip this patch. Databricks still publishes no Java 8 binary on Maven Central.

Upstream: [issue #1706](https://github.com/databricks/databricks-jdbc/issues/1706), [PR #1707](https://github.com/databricks/databricks-jdbc/pull/1707).
