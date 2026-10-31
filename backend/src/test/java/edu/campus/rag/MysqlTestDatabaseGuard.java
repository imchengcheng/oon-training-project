package edu.campus.rag;

import java.net.URI;
import java.sql.DriverManager;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/** Reject a business database URL before Hibernate can execute the test create-drop DDL. */
public class MysqlTestDatabaseGuard
    implements ApplicationContextInitializer<ConfigurableApplicationContext> {
  @Override
  public void initialize(ConfigurableApplicationContext context) {
    var env = context.getEnvironment();
    String url = env.getRequiredProperty("spring.datasource.url");
    if (!url.startsWith("jdbc:mysql:")
        || !"/campus_rag_test".equals(URI.create(url.substring(5)).getPath())) {
      throw new IllegalStateException("Tests may only connect to MySQL database campus_rag_test");
    }
    if ("qdrant".equals(env.getProperty("app.vector-store"))
        && !env.getProperty("app.vector-namespace", "").startsWith("test-")) {
      throw new IllegalStateException(
          "Qdrant contract tests require a namespace beginning with test-");
    }
    try (var connection =
        DriverManager.getConnection(
            url,
            env.getRequiredProperty("spring.datasource.username"),
            env.getRequiredProperty("spring.datasource.password"))) {
      var metadata = connection.getMetaData();
      if (!"MySQL".equals(metadata.getDatabaseProductName())
          || metadata.getDatabaseMajorVersion() < 8) {
        throw new IllegalStateException("Tests require MySQL 8 or later");
      }
    } catch (java.sql.SQLException ex) {
      throw new IllegalStateException(
          "Cannot connect to local MySQL 8 as root. Check the service and DB_PASSWORD.", ex);
    }
  }
}
