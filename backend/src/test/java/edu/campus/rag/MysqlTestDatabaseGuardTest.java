package edu.campus.rag;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;

class MysqlTestDatabaseGuardTest {
  @Test
  void rejectsProductionVectorNamespaceBeforeJdbcAccess() {
    try (var context = new GenericApplicationContext()) {
      context.setEnvironment(
          new MockEnvironment()
              .withProperty("spring.datasource.url", "jdbc:mysql://127.0.0.1:3306/campus_rag_test")
              .withProperty("app.vector-store", "qdrant")
              .withProperty("app.vector-namespace", "campus"));
      var failure =
          assertThrows(
              IllegalStateException.class, () -> new MysqlTestDatabaseGuard().initialize(context));
      assertTrue(failure.getMessage().contains("namespace"));
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "jdbc:mysql://127.0.0.1:3306/campus_rag",
        "jdbc:mysql://127.0.0.1:3306/campus_rag_test_backup",
        "jdbc:postgresql://127.0.0.1:5432/campus_rag_test"
      })
  void rejectsUnsafeDatasourceBeforeAccessingDatabase(String url) {
    try (var context = new GenericApplicationContext()) {
      // Credentials are intentionally absent: rejection must precede JDBC access.
      context.setEnvironment(new MockEnvironment().withProperty("spring.datasource.url", url));
      assertThrows(
          IllegalStateException.class, () -> new MysqlTestDatabaseGuard().initialize(context));
    }
  }
}
