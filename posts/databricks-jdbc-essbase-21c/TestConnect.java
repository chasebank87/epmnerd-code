import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;

/**
 * Java 8 live test for the Auth_Scope M2M patch. All inputs come from environment variables; the
 * secret is passed as a connection property and never appears in the URL or in any output.
 *
 * <p>DBX_HOST, DBX_WAREHOUSE_ID, DBX_CLIENT_ID, DBX_SECRET (required); DBX_SCOPE (default: sql).
 */
public class TestConnect {
  public static void main(String[] args) throws Exception {
    String host = requireEnv("DBX_HOST");
    String warehouseId = requireEnv("DBX_WAREHOUSE_ID");
    String clientId = requireEnv("DBX_CLIENT_ID");
    String secret = requireEnv("DBX_SECRET");
    String scope = System.getenv("DBX_SCOPE") != null ? System.getenv("DBX_SCOPE") : "sql";

    String url =
        "jdbc:databricks://"
            + host
            + ":443/default;transportMode=http;ssl=1;httpPath=/sql/1.0/warehouses/"
            + warehouseId
            + ";AuthMech=11;Auth_Flow=1;OAuth2ClientId="
            + clientId
            + ";Auth_Scope="
            + scope;

    Properties props = new Properties();
    props.setProperty("OAuth2Secret", secret);

    Class.forName("com.databricks.client.jdbc.Driver");
    System.out.println("java.version = " + System.getProperty("java.version"));
    System.out.println("URL (no secret) = " + url);
    try (Connection conn = DriverManager.getConnection(url, props);
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT 1")) {
      rs.next();
      System.out.println("SELECT 1 -> " + rs.getInt(1));
    }
  }

  private static String requireEnv(String name) {
    String v = System.getenv(name);
    if (v == null || v.trim().isEmpty()) {
      throw new IllegalStateException("Missing environment variable: " + name);
    }
    return v;
  }
}
