#!/usr/bin/env bash
# Build the Databricks OSS JDBC driver from the jdk-8 branch and check that it runs on Java 8.
#
#   ./build-jdk8-driver.sh [clone-dir]
#
# Needs git, Maven 3.6+ and a JDK 8. Set JAVA_HOME to the JDK 8 home first; on macOS the script
# finds it for you with /usr/libexec/java_home.
set -euo pipefail

DIR="${1:-databricks-jdbc-jdk8}"

if [ -z "${JAVA_HOME:-}" ] && [ -x /usr/libexec/java_home ]; then
  JAVA_HOME="$(/usr/libexec/java_home -v 1.8)"
fi
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

echo "== Toolchain"
JAVA_VERSION="$(java -version 2>&1)"
echo "$JAVA_VERSION"
mvn -v | sed -n '1,3p'
case "$JAVA_VERSION" in
  *'"1.8.'*) ;;
  *) echo "JAVA_HOME is not a JDK 8: $JAVA_HOME" >&2; exit 1 ;;
esac

echo
echo "== Source"
if [ ! -d "$DIR" ]; then
  git clone --branch jdk-8 --single-branch https://github.com/databricks/databricks-jdbc.git "$DIR"
fi
cd "$DIR"
git log -1 --format='%h %ad %s' --date=short
grep -E -n 'maven\.compiler\.(source|target)' pom.xml
grep -n 'Auth_Scope' NEXT_CHANGELOG.md CHANGELOG.md | grep -i 'M2M' \
  || echo "WARNING: no Auth_Scope M2M entry in the changelogs on this branch"

echo
echo "== Build"
mvn -B -DskipTests clean package

JAR="$(ls assembly-uber/target/databricks-jdbc-*.jar | grep -v -e original -e sources)"
echo
echo "== Artifact"
ls -lh "$JAR"
shasum -a 256 "$JAR" 2>/dev/null || sha256sum "$JAR"

echo
echo "== Class file versions (52 = Java 8)"
python3 - "$JAR" <<'EOF'
import collections, sys, zipfile
z = zipfile.ZipFile(sys.argv[1])
c = collections.Counter()
for n in z.namelist():
    if n.endswith(".class"):
        c[int.from_bytes(z.read(n)[6:8], "big")] += 1
for major, count in sorted(c.items()):
    print(f"major {major}: {count} classes")
if max(c) > 52:
    sys.exit(f"FAIL: found class files newer than Java 8 (major {max(c)})")
print("OK: nothing newer than Java 8")
EOF

echo
echo "== Load test on Java 8"
TMP="$(mktemp -d)"
cat > "$TMP/LoadDriver.java" <<'EOF'
import java.sql.Driver;
import java.sql.DriverManager;

public class LoadDriver {
  public static void main(String[] args) throws Exception {
    Class.forName("com.databricks.client.jdbc.Driver");
    Driver d = DriverManager.getDriver("jdbc:databricks://example:443");
    System.out.println("java.version = " + System.getProperty("java.version"));
    System.out.println("driver       = " + d.getClass().getName() + " " + d.getMajorVersion() + "." + d.getMinorVersion());
  }
}
EOF
javac -d "$TMP" "$TMP/LoadDriver.java"
java -cp "$JAR:$TMP" LoadDriver

echo
echo "Done: $PWD/$JAR"
