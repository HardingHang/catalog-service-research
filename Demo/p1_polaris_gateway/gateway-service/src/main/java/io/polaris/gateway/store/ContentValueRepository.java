package io.polaris.gateway.store;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

@ApplicationScoped
public class ContentValueRepository {
  private DataSource dataSource;

  public ContentValueRepository() {}

  @Inject
  public ContentValueRepository(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  public void insert(ContentValueRecord value) {
    withDsl(
        ctx -> {
          ctx.insertInto(table("catalog_content_values"))
              .columns(
                  field("value_id"),
                  field("catalog_id"),
                  field("content_id"),
                  field("content_key"),
                  field("content_type"),
                  field("metadata_location"),
                  field("snapshot_id"),
                  field("schema_id"),
                  field("spec_id"),
                  field("sort_order_id"),
                  field("table_uuid"),
                  field("table_location"),
                  field("format_version"),
                  field("last_sequence_number"),
                  field("metadata_fingerprint"),
                  field("metadata_summary_json"))
              .values(
                  value.valueId(),
                  value.catalogId(),
                  value.contentId(),
                  value.contentKey(),
                  value.contentType(),
                  value.metadataLocation(),
                  value.snapshotId(),
                  value.schemaId(),
                  value.specId(),
                  value.sortOrderId(),
                  value.tableUuid(),
                  value.tableLocation(),
                  value.formatVersion(),
                  value.lastSequenceNumber(),
                  value.metadataFingerprint(),
                  JSONB.valueOf(
                      value.metadataSummaryJson() != null ? value.metadataSummaryJson() : "{}"))
              .execute();
          return null;
        });
  }

  public Optional<ContentValueRecord> findByValueId(String valueId) {
    return withDsl(
        ctx ->
            ctx.select(
                    field("value_id", String.class),
                    field("catalog_id", String.class),
                    field("content_id", String.class),
                    field("content_key", String.class),
                    field("content_type", String.class),
                    field("metadata_location", String.class),
                    field("snapshot_id", String.class),
                    field("schema_id", Integer.class),
                    field("spec_id", Integer.class),
                    field("sort_order_id", Integer.class),
                    field("table_uuid", String.class),
                    field("table_location", String.class),
                    field("format_version", Integer.class),
                    field("last_sequence_number", Long.class),
                    field("metadata_fingerprint", String.class),
                    field("metadata_summary_json", JSONB.class))
                .from(table("catalog_content_values"))
                .where(field("value_id").eq(valueId))
                .fetchOptional(
                    record ->
                        new ContentValueRecord(
                            record.value1(),
                            record.value2(),
                            record.value3(),
                            record.value4(),
                            record.value5(),
                            record.value6(),
                            record.value7(),
                            record.value8(),
                            record.value9(),
                            record.value10(),
                            record.value11(),
                            record.value12(),
                            record.value13(),
                            record.value14(),
                            record.value15(),
                            record.value16() == null ? "{}" : record.value16().data())));
  }

  private <T> T withDsl(SqlWork<T> work) {
    try (Connection connection = dataSource.getConnection()) {
      DSLContext ctx = DSL.using(connection, SQLDialect.POSTGRES);
      return work.apply(ctx);
    } catch (SQLException e) {
      throw new IllegalStateException("Version Store SQL operation failed", e);
    }
  }

  @FunctionalInterface
  private interface SqlWork<T> {
    T apply(DSLContext ctx) throws SQLException;
  }

  public record ContentValueRecord(
      String valueId,
      String catalogId,
      String contentId,
      String contentKey,
      String contentType,
      String metadataLocation,
      String snapshotId,
      Integer schemaId,
      Integer specId,
      Integer sortOrderId,
      String tableUuid,
      String tableLocation,
      Integer formatVersion,
      Long lastSequenceNumber,
      String metadataFingerprint,
      String metadataSummaryJson) {}
}
