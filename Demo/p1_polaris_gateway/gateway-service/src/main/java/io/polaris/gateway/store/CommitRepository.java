package io.polaris.gateway.store;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

@ApplicationScoped
public class CommitRepository {
  private DataSource dataSource;

  public CommitRepository() {}

  @Inject
  public CommitRepository(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  public void insertCommit(CommitRecord commit) {
    withDsl(
        ctx -> {
          ctx.insertInto(table("catalog_commits"))
              .columns(
                  field("commit_id"),
                  field("catalog_id"),
                  field("parent_commit_id"),
                  field("author"),
                  field("message"),
                  field("operation_summary"),
                  field("index_id"),
                  field("source_ref"),
                  field("request_id"))
              .values(
                  commit.commitId(),
                  commit.catalogId(),
                  commit.parentCommitId(),
                  commit.author(),
                  commit.message(),
                  JSONB.valueOf(commit.operationSummaryJson()),
                  commit.indexId(),
                  commit.sourceRef(),
                  commit.requestId())
              .execute();
          return null;
        });
  }

  public Optional<CommitRecord> findById(String commitId) {
    return withDsl(
        ctx ->
            ctx.select(
                    field("commit_id", String.class),
                    field("catalog_id", String.class),
                    field("parent_commit_id", String.class),
                    field("author", String.class),
                    field("message", String.class),
                    field("created_at", OffsetDateTime.class),
                    field("operation_summary", JSONB.class),
                    field("index_id", String.class),
                    field("source_ref", String.class),
                    field("request_id", String.class))
                .from(table("catalog_commits"))
                .where(field("commit_id").eq(commitId))
                .fetchOptional(
                    record ->
                        new CommitRecord(
                            record.value1(),
                            record.value2(),
                            record.value3(),
                            record.value4(),
                            record.value5(),
                            record.value6(),
                            record.value7() == null ? "{}" : record.value7().data(),
                            record.value8(),
                            record.value9(),
                            record.value10())));
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

  public record CommitRecord(
      String commitId,
      String catalogId,
      String parentCommitId,
      String author,
      String message,
      OffsetDateTime createdAt,
      String operationSummaryJson,
      String indexId,
      String sourceRef,
      String requestId) {
    public static CommitRecord create(
        String commitId, String catalogId, String parentCommitId, String sourceRef) {
      return new CommitRecord(
          commitId,
          catalogId,
          parentCommitId,
          "test",
          "test commit",
          null,
          "{}",
          null,
          sourceRef,
          null);
    }
  }
}
