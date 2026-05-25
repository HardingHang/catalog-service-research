package io.polaris.gateway.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import io.polaris.gateway.store.PostgreSQLTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(PostgreSQLTestResource.class)
class LoadTableIT {

  private static final String CATALOG = "test";
  private static final String METADATA_LOCATION =
      "s3://test-bucket/sales.orders/metadata-v1.json";

  @Inject DataSource dataSource;

  @BeforeEach
  void resetStore() throws SQLException {
    try (Connection conn = dataSource.getConnection();
        var stmt = conn.createStatement()) {
      stmt.execute(
          "TRUNCATE catalog_content_index, catalog_refs, audit_events,"
              + " catalog_commit_ops, outbox_materializations, catalog_ref_indexes,"
              + " catalog_commits, catalog_content_values CASCADE");
    }
    insertTestData();
  }

  @Test
  void loadTable_returnsMetadataLocation() {
    given()
        .header("X-Catalog-Ref", "main")
        .when()
        .get("/iceberg/v1/" + CATALOG + "/namespaces/sales/tables/orders")
        .then()
        .statusCode(200)
        .body("metadata-location", not(emptyOrNullString()))
        .body("metadata-location", equalTo(METADATA_LOCATION));
  }

  @Test
  void loadTable_noRefHeader_usesMain() {
    given()
        .when()
        .get("/iceberg/v1/" + CATALOG + "/namespaces/sales/tables/orders")
        .then()
        .statusCode(200)
        .body("metadata-location", is(METADATA_LOCATION));
  }

  @Test
  void loadTable_unknownRef_returns404() {
    given()
        .header("X-Catalog-Ref", "nonexistent")
        .when()
        .get("/iceberg/v1/" + CATALOG + "/namespaces/sales/tables/orders")
        .then()
        .statusCode(404);
  }

  @Test
  void listNamespaces_unknownRef_returns404() {
    given()
        .header("X-Catalog-Ref", "nonexistent")
        .when()
        .get("/iceberg/v1/" + CATALOG + "/namespaces")
        .then()
        .statusCode(404);
  }

  @Test
  void listTables_unknownRef_returns404() {
    given()
        .header("X-Catalog-Ref", "nonexistent")
        .when()
        .get("/iceberg/v1/" + CATALOG + "/namespaces/sales/tables")
        .then()
        .statusCode(404);
  }

  @Test
  void loadTable_unknownTable_returns404() {
    given()
        .header("X-Catalog-Ref", "main")
        .when()
        .get("/iceberg/v1/" + CATALOG + "/namespaces/sales/tables/no_such_table")
        .then()
        .statusCode(404);
  }

  @Test
  void listTables_returnsTableInNamespace() {
    given()
        .header("X-Catalog-Ref", "main")
        .when()
        .get("/iceberg/v1/" + CATALOG + "/namespaces/sales/tables")
        .then()
        .statusCode(200)
        .body("identifiers[0].name", is("orders"));
  }

  @Test
  void listNamespaces_returnsNamespace() {
    given()
        .header("X-Catalog-Ref", "main")
        .when()
        .get("/iceberg/v1/" + CATALOG + "/namespaces")
        .then()
        .statusCode(200)
        .body("namespaces[0][0]", is("sales"));
  }

  private void insertTestData() throws SQLException {
    String metadataJson =
        """
        {"format-version":2,"table-uuid":"test-uuid-001",\
        "location":"s3://test-bucket/sales.orders",\
        "current-snapshot-id":-1,"schemas":[],"partition-specs":[],"sort-orders":[]}
        """.strip();

    try (Connection conn = dataSource.getConnection()) {
      exec(
          conn,
          """
          INSERT INTO catalog_content_values(
            value_id, catalog_id, content_id, content_key, content_type,
            metadata_location, table_uuid, metadata_summary_json)
          VALUES ('v0', ?, 'test-uuid-001', 'sales.orders', 'ICEBERG_TABLE', ?, 'test-uuid-001', ?::jsonb)
          """,
          CATALOG,
          METADATA_LOCATION,
          metadataJson);

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

      exec(
          conn,
          """
          INSERT INTO catalog_refs(
            catalog_id, ref_name, ref_type, head_commit_id, deleted, created_by)
          VALUES (?, 'main', 'BRANCH', 'c0', false, 'test')
          """,
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
