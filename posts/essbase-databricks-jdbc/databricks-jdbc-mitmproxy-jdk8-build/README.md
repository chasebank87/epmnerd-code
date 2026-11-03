# mitmproxy capture and jdk-8 build (Part 2)

Post: [epmnerd.com/blog/databricks-jdbc-mitmproxy-jdk8-build](https://epmnerd.com/blog/databricks-jdbc-mitmproxy-jdk8-build)

Series: [Databricks JDBC for Essbase 21c](../README.md)

| File | What it does |
| --- | --- |
| `build-jdk8-driver.sh` | Clones the Databricks [`jdk-8`](https://github.com/databricks/databricks-jdbc/tree/jdk-8) branch, builds it on JDK 8, checks every class is Java 8 or older, and loads the driver on Java 8. |
| `fake_workspace.py` | mitmproxy addon that stands in for a Databricks workspace. The token endpoint returns 403 for `all-apis` and 200 for `sql`. Nothing leaves your machine. |
| `TestConnect.java` | Connects with OAuth client-secret M2M and runs `SELECT 1`. Reads everything from environment variables. `DBX_URL_EXTRA` is appended to the URL for proxy and trust store settings. |

## Build the driver

Needs git, Maven and a JDK 8. On macOS the script finds JDK 8 with `/usr/libexec/java_home`. Elsewhere, set `JAVA_HOME` first.

```bash
./build-jdk8-driver.sh            # clones into ./databricks-jdbc-jdk8
```

The JAR is `databricks-jdbc-jdk8/assembly-uber/target/databricks-jdbc-3.4.3.jar`.

## Watch the token request

```bash
brew install mitmproxy
mitmweb --listen-port 8888 -s fake_workspace.py --set connection_strategy=lazy

keytool -importcert -noprompt -alias mitmproxy \
  -file ~/.mitmproxy/mitmproxy-ca-cert.pem \
  -keystore mitm-truststore.jks -storepass changeit

export DBX_HOST="adb-1234567890123456.7.azuredatabricks.net"
export DBX_WAREHOUSE_ID="0123456789abcdef"
export DBX_CLIENT_ID="00000000-aaaa-bbbb-cccc-000000000000"
export DBX_SECRET="not-a-real-secret"
export DBX_SCOPE="sql"
export DBX_URL_EXTRA="UseProxy=1;ProxyHost=127.0.0.1;ProxyPort=8888;SSLTrustStore=mitm-truststore.jks;SSLTrustStorePwd=changeit;CheckCertRevocation=0;EnableTelemetry=0"

javac TestConnect.java
java -cp databricks-jdbc-jdk8/assembly-uber/target/databricks-jdbc-3.4.3.jar:. TestConnect
```

Open the mitmweb UI at `http://127.0.0.1:8081` and select the `POST /oidc/v1/token` flow.

`connection_strategy=lazy` stops mitmproxy from connecting to the fake host, so every request is answered by the addon. To capture a real workspace instead, drop `-s fake_workspace.py` and that option, and use your own values.

Notes:

- Keep `mitm-truststore.jks` for testing only. Don't import the mitmproxy CA into the system cacerts or Essbase's Java.
- The `Authorization: Basic` header in a capture is base64 of `client_id:client_secret`. Redact it before you share a screenshot.
- Remove `UseProxy`, `ProxyHost`, `ProxyPort`, `SSLTrustStore`, `SSLTrustStorePwd` and `CheckCertRevocation` before the URL goes to Essbase.

The JAR you build is not an official Databricks release.
