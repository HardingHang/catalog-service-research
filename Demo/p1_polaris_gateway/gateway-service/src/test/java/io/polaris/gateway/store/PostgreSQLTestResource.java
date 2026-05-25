package io.polaris.gateway.store;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.Map;
import org.testcontainers.containers.PostgreSQLContainer;

public class PostgreSQLTestResource implements QuarkusTestResourceLifecycleManager {
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Override
  public Map<String, String> start() {
    POSTGRES.start();
    return Map.of(
        "quarkus.datasource.jdbc.url", POSTGRES.getJdbcUrl(),
        "quarkus.datasource.username", POSTGRES.getUsername(),
        "quarkus.datasource.password", POSTGRES.getPassword());
  }

  @Override
  public void stop() {
    POSTGRES.stop();
  }
}
