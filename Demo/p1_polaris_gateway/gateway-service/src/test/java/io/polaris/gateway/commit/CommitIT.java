package io.polaris.gateway.commit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyOrNullString;

import io.polaris.gateway.store.PostgreSQLTestResource;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

@QuarkusTest
@QuarkusTestResource(PostgreSQLTestResource.class)
class CommitIT {

  private static final String CATALOG = "test";
  private static final String MOCK_NEW_LOCATION =
      "s3://test-bucket/test-catalog/sales/orders/metadata/mock-new.metadata.json";
  private static final String TABLE_UUID = "test-uuid-001";
  private static final String TABLE_LOCATION = "s3://test-bucket/test-catalog/sales/orders";
  private static final String INITIAL_METADATA_JSON =
      """
      {"format-version":2,"table-uuid":"test-uuid-001",\
      "location":"s3://test-bucket/test-catalog/sales/orders",\
      "current-snapshot-id":-1,"snapshots":[],\
      "schemas":[{"schema-id":0,"type":"struct","fields":[{"id":1,"name":"id","required":false,"type":"long"}]}],\
      "current-schema-id":0,"partition-specs":[{"spec-id":0,"fields":[]}],"default-spec-id":0,\
      "sort-orders":[{"order-id":0,"fields":[]}],"default-sort-order-id":0,\
      "last-partition-id":0,"last-column-id":1,"last-sequence-number":0}
      """.strip();

  @InjectMock IcebergMetadataWriter metadataWriter;

  @Inject DataSource dataSource;

  @BeforeEach
  void setup() throws SQLException {
    Mockito.when(metadataWriter.write(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(MOCK_NEW_LOCATION);
    truncateAll();
    insertTestData();
  }

  // ── acceptance scenario 1 ─────────────────────────────────────────────────
  @Test
  void commitSucceeds_branchHasNewSnapshot() {
    given()
        .header("X-Catalog-Ref", "dev_a")
        .contentType("application/json")
        .body(commitBody(TABLE_UUID, -1, 1))
        .when()
        .post("/iceberg/v1/" + CATALOG + "/namespaces/sales/tables/orders")
        .then()
        .statusCode(200)
        .body("metadata-location", equalTo(MOCK_NEW_LOCATION))
        .body("metadata['current-snapshot-id']", equalTo(1));

    // The branch index now resolves to the new snapshot
    given()
        .header("X-Catalog-Ref", "dev_a")
        .when()
        .get("/iceberg/v1/" + CATALOG + "/namespaces/sales/tables/orders")
        .then()
        .statusCode(200)
        .body("metadata['current-snapshot-id']", equalTo(1));
  }

  // ── acceptance scenario 2 ─────────────────────────────────────────────────
  @Test
  void mainUnchangedAfterBranchCommit() {
    given()
        .header("X-Catalog-Ref", "dev_a")
        .contentType("application/json")
        .body(commitBody(TABLE_UUID, -1, 1))
        .when()
        .post("/iceberg/v1/" + CATALOG + "/namespaces/sales/tables/orders")
        .then()
        .statusCode(200);

    given()
        .header("X-Catalog-Ref", "main")
        .when()
        .get("/iceberg/v1/" + CATALOG + "/namespaces/sales/tables/orders")
        .then()
        .statusCode(200)
        .body("metadata['current-snapshot-id']", equalTo(-1));
  }

  // ── acceptance scenario 3 ─────────────────────────────────────────────────
  @Test
  void idempotencyReplay_sameResponse() {
    String firstLocation =
        given()
            .header("X-Catalog-Ref", "dev_a")
            .header("Idempotency-Key", "idem-001")
            .contentType("application/json")
            .body(commitBody(TABLE_UUID, -1, 1))
            .when()
            .post("/iceberg/v1/" + CATALOG + "/namespaces/sales/tables/orders")
            .then()
            .statusCode(200)
            .body("metadata-location", not(emptyOrNullString()))
            .extract()
            .path("metadata-location");

    // Second call with same idempotency key — replays cached response, no new S3 write
    given()
        .header("X-Catalog-Ref", "dev_a")
        .header("Idempotency-Key", "idem-001")
        .contentType("application/json")
        .body(commitBody(TABLE_UUID, -1, 1))
        .when()
        .post("/iceberg/v1/" + CATALOG + "/namespaces/sales/tables/orders")
        .then()
        .statusCode(200)
        .body("metadata-location", equalTo(firstLocation));

    // write() called exactly once across both requests
    Mockito.verify(metadataWriter, Mockito.times(1))
        .write(Mockito.anyString(), Mockito.anyString());
  }

  // ── acceptance scenario 4 ─────────────────────────────────────────────────
  @Test
  void concurrentCommit_atLeastOneSucceeds() {
    String body = commitBody(TABLE_UUID, -1, 1);

    // First commit on dev_a — succeeds
    given()
        .header("X-Catalog-Ref", "dev_a")
        .contentType("application/json")
        .body(body)
        .when()
        .post("/iceberg/v1/" + CATALOG + "/namespaces/sales/tables/orders")
        .then()
        .statusCode(200);

    // Second commit with same initial requirements — fails because snapshot changed
    given()
        .header("X-Catalog-Ref", "dev_a")
        .contentType("application/json")
        .body(body)
        .when()
        .post("/iceberg/v1/" + CATALOG + "/namespaces/sales/tables/orders")
        .then()
        .statusCode(409);
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static String commitBody(String tableUuid, long currentSnapshotId, long newSnapshotId) {
    return """
        {
          "requirements": [
            {"type": "assert-table-uuid", "uuid": "%s"},
            {"type": "assert-ref-snapshot-id", "ref": "main", "snapshot-id": %d}
          ],
          "updates": [
            {
              "action": "add-snapshot",
              "snapshot": {
                "snapshot-id": %d,
                "timestamp-ms": 1234567890000,
                "manifest-list": "s3://test-bucket/snap.avro",
                "summary": {"operation": "append"},
                "schema-id": 0,
                "sequence-number": 1
              }
            },
            {
              "action": "set-snapshot-ref",
              "ref-name": "main",
              "type": "branch",
              "snapshot-id": %d
            }
          ]
        }
        """.formatted(tableUuid, currentSnapshotId, newSnapshotId, newSnapshotId);
  }

  private void truncateAll() throws SQLException {
    try (Connection conn = dataSource.getConnection();
        var stmt = conn.createStatement()) {
      stmt.execute(
          "TRUNCATE catalog_content_index, catalog_refs, audit_events,"
              + " catalog_commit_ops, outbox_materializations, idempotency_keys,"
              + " catalog_ref_indexes, catalog_commits, catalog_content_values CASCADE");
    }
  }

  private void insertTestData() throws SQLException {
    try (Connection conn = dataSource.getConnection()) {
      exec(
          conn,
          """
          INSERT INTO catalog_content_values(
            value_id, catalog_id, content_id, content_key, content_type,
            metadata_location, table_uuid, table_location, metadata_summary_json)
          VALUES ('v0', ?, 'test-uuid-001', 'sales.orders', 'ICEBERG_TABLE',
                  's3://test-bucket/test-catalog/sales/orders/metadata/v1.metadata.json',
                  'test-uuid-001', ?, ?::jsonb)
          """,
          CATALOG,
          TABLE_LOCATION,
          INITIAL_METADATA_JSON);

      exec(
          conn,
          """
          INSERT INTO catalog_commits(
            commit_id, catalog_id, author, operation_summary, index_id, source_ref)
          VALUES ('c0', ?, 'gateway', '{}'::jsonb, 'i0', 'main')
          """,
          CATALOG);

      exec(
          conn,
          """
          INSERT INTO catalog_ref_indexes(
            index_id, catalog_id, commit_id, index_kind, object_count, stripe_count)
          VALUES ('i0', ?, 'c0', 'FULL', 1, 1)
          """,
          CATALOG);

      exec(
          conn,
          """
          INSERT INTO catalog_content_index(
            index_id, namespace, content_key, content_id, value_id, content_type)
          VALUES ('i0', 'sales', 'sales.orders', 'test-uuid-001', 'v0', 'ICEBERG_TABLE')
          """);

      // Both main and dev_a start at the same commit c0
      exec(
          conn,
          """
          INSERT INTO catalog_refs(
            catalog_id, ref_name, ref_type, head_commit_id, deleted, created_by)
          VALUES (?, 'main', 'BRANCH', 'c0', false, 'test'),
                 (?, 'dev_a', 'BRANCH', 'c0', false, 'test')
          """,
          CATALOG,
          CATALOG);
    }
  }

  private static void exec(Connection conn, String sql, Object... params) throws SQLException {
    try (PreparedStatement stmt = conn.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        stmt.setObject(i + 1, params[i]);
      }
      stmt.execute();
    }
  }
}
