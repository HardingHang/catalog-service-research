package io.polaris.gateway.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.polaris.gateway.store.CommitRepository.CommitRecord;
import io.polaris.gateway.store.IndexRepository.ContentIndexRecord;
import io.polaris.gateway.store.IndexRepository.IndexKind;
import io.polaris.gateway.store.IndexRepository.IndexRecord;
import io.polaris.gateway.store.RefRepository.RefRecord;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RefRepositoryIT {
  private static final String CATALOG = "test";
  private static final String EXTERNAL_JDBC_URL_ENV = "P1_EXTERNAL_POSTGRES_JDBC_URL";
  private static final String EXTERNAL_USER_ENV = "P1_EXTERNAL_POSTGRES_USER";
  private static final String EXTERNAL_PASSWORD_ENV = "P1_EXTERNAL_POSTGRES_PASSWORD";

  private PostgreSQLContainer<?> postgres;

  private DataSource dataSource;
  private RefRepository refRepository;
  private CommitRepository commitRepository;
  private IndexRepository indexRepository;

  @BeforeAll
  void startPostgresWhenNeeded() {
    if (System.getenv(EXTERNAL_JDBC_URL_ENV) != null) {
      return;
    }
    postgres = new PostgreSQLContainer<>("postgres:16");
    postgres.start();
  }

  @AfterAll
  void stopPostgresWhenNeeded() {
    if (postgres != null) {
      postgres.stop();
    }
  }

  @BeforeEach
  void resetStore() {
    dataSource = dataSource();
    Flyway flyway =
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .cleanDisabled(false)
            .load();
    flyway.clean();
    flyway.migrate();

    refRepository = new RefRepository(dataSource);
    commitRepository = new CommitRepository(dataSource);
    indexRepository = new IndexRepository(dataSource);
  }

  @Test
  void createBranch_success() {
    insertCommit("c0", null, "main");

    RefRecord branch = refRepository.createBranch(CATALOG, "dev_a", "c0", "agent");

    assertEquals("dev_a", branch.refName());
    assertEquals("c0", branch.headCommitId());
    assertFalse(branch.deleted());
  }

  @Test
  void createBranch_duplicateName() {
    insertCommit("c0", null, "main");
    refRepository.createBranch(CATALOG, "dev_a", "c0", "agent");

    StoreConflictException conflict =
        assertThrows(
            StoreConflictException.class,
            () -> refRepository.createBranch(CATALOG, "dev_a", "c0", "agent"));

    assertTrue(conflict.getMessage().contains("dev_a"));
  }

  @Test
  void casUpdate_succeeds() {
    insertCommit("c0", null, "main");
    insertCommit("c1", "c0", "dev_a");
    refRepository.createBranch(CATALOG, "dev_a", "c0", "agent");

    int rows = refRepository.casUpdateHead(CATALOG, "dev_a", "c0", "c1");

    assertEquals(1, rows);
    RefRecord branch = refRepository.getRef(CATALOG, "dev_a").orElseThrow();
    assertEquals("c1", branch.headCommitId());
    assertEquals(1, branch.version());
  }

  @Test
  void casUpdate_conflictsOnMismatch() {
    insertCommit("c0", null, "main");
    insertCommit("c1", "c0", "dev_a");
    refRepository.createBranch(CATALOG, "dev_a", "c0", "agent");

    int rows = refRepository.casUpdateHead(CATALOG, "dev_a", "wrong-head", "c1");

    assertEquals(0, rows);
    assertEquals("c0", refRepository.getRef(CATALOG, "dev_a").orElseThrow().headCommitId());
  }

  @Test
  void listBranches_excludesDeleted() {
    insertCommit("c0", null, "main");
    refRepository.createBranch(CATALOG, "dev_a", "c0", "agent");
    refRepository.createBranch(CATALOG, "dev_b", "c0", "agent");
    refRepository.deleteRef(CATALOG, "dev_b");

    var branches = refRepository.listBranches(CATALOG, 100, 0);

    assertEquals(1, branches.size());
    assertEquals("dev_a", branches.getFirst().refName());
  }

  @Test
  void insertAndFindCommit_success() {
    insertCommit("c0", null, "main");

    CommitRecord commit = commitRepository.findById("c0").orElseThrow();

    assertEquals(CATALOG, commit.catalogId());
    assertEquals("main", commit.sourceRef());
  }

  @Test
  void insertAndFindIndex_success() {
    insertCommit("c0", null, "main");
    indexRepository.insertIndex(
        new IndexRecord("i0", CATALOG, "c0", null, IndexKind.FULL, 1, 1, null));
    insertContentValue("v0", "content-0", "sales.orders");
    indexRepository.insertContentIndex(
        new ContentIndexRecord(
            "i0", "sales", "sales.orders", "content-0", "v0", "ICEBERG_TABLE", false));

    assertEquals(IndexKind.FULL, indexRepository.findById("i0").orElseThrow().indexKind());
    assertEquals(
        "v0", indexRepository.findContent("i0", "sales.orders").orElseThrow().valueId());
  }

  @Test
  void shouldCompact_atTwentyIncrementalIndexes() {
    for (int i = 0; i < IndexRepository.INCREMENTAL_INDEX_COMPACTION_THRESHOLD - 1; i++) {
      insertCommit("c" + i, null, "dev_a");
      indexRepository.insertIndex(
          new IndexRecord("i" + i, CATALOG, "c" + i, null, IndexKind.INCREMENTAL, 1, 1, null));
    }
    assertFalse(indexRepository.shouldCompact(CATALOG, "dev_a"));

    int last = IndexRepository.INCREMENTAL_INDEX_COMPACTION_THRESHOLD - 1;
    insertCommit("c" + last, null, "dev_a");
    indexRepository.insertIndex(
        new IndexRecord("i" + last, CATALOG, "c" + last, null, IndexKind.INCREMENTAL, 1, 1, null));

    assertTrue(indexRepository.shouldCompact(CATALOG, "dev_a"));
  }

  @Test
  void shouldCompact_whenOpsExceedThreshold() throws SQLException {
    insertCommit("c-ops", null, "dev_a");
    insertCommitOps("c-ops", IndexRepository.INDEX_OPS_COMPACTION_THRESHOLD + 1);

    assertTrue(indexRepository.shouldCompact(CATALOG, "dev_a"));
  }

  private DataSource dataSource() {
    PGSimpleDataSource source = new PGSimpleDataSource();
    String externalJdbcUrl = System.getenv(EXTERNAL_JDBC_URL_ENV);
    if (externalJdbcUrl != null) {
      source.setUrl(externalJdbcUrl);
      source.setUser(System.getenv().getOrDefault(EXTERNAL_USER_ENV, "p1_gateway"));
      source.setPassword(System.getenv().getOrDefault(EXTERNAL_PASSWORD_ENV, "p1_gateway"));
      return source;
    }
    source.setUrl(postgres.getJdbcUrl());
    source.setUser(postgres.getUsername());
    source.setPassword(postgres.getPassword());
    return source;
  }

  private void insertCommit(String commitId, String parentCommitId, String sourceRef) {
    commitRepository.insertCommit(CommitRecord.create(commitId, CATALOG, parentCommitId, sourceRef));
  }

  private void insertContentValue(String valueId, String contentId, String contentKey) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
                INSERT INTO catalog_content_values(
                  value_id, catalog_id, content_id, content_key, content_type
                ) VALUES (?, ?, ?, ?, 'ICEBERG_TABLE')
                """)) {
      statement.setString(1, valueId);
      statement.setString(2, CATALOG);
      statement.setString(3, contentId);
      statement.setString(4, contentKey);
      statement.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private void insertCommitOps(String commitId, int count) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
                INSERT INTO catalog_commit_ops(
                  commit_id, op_ordinal, content_key, op_type
                ) VALUES (?, ?, ?, 'UNCHANGED')
                """)) {
      for (int i = 0; i < count; i++) {
        statement.setString(1, commitId);
        statement.setInt(2, i);
        statement.setString(3, "sales.table_" + i);
        statement.addBatch();
      }
      statement.executeBatch();
    }
  }
}
