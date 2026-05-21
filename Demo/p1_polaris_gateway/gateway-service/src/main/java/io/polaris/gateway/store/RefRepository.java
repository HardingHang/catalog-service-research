package io.polaris.gateway.store;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;

@ApplicationScoped
public class RefRepository {
  private DataSource dataSource;

  public RefRepository() {}

  @Inject
  public RefRepository(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  public RefRecord createBranch(
      String catalogId, String branchName, String sourceHeadCommitId, String createdBy) {
    try {
      withDsl(
          ctx -> {
            ctx.insertInto(table("catalog_refs"))
                .columns(
                    field("catalog_id"),
                    field("ref_name"),
                    field("ref_type"),
                    field("head_commit_id"),
                    field("deleted"),
                    field("created_by"))
                .values(catalogId, branchName, RefType.BRANCH.name(), sourceHeadCommitId, false, createdBy)
                .execute();
            return null;
          });
      return getRef(catalogId, branchName).orElseThrow();
    } catch (DataAccessException e) {
      if (isUniqueViolation(e)) {
        throw new StoreConflictException("Ref already exists: " + catalogId + "/" + branchName, e);
      }
      throw e;
    }
  }

  public Optional<RefRecord> getRef(String catalogId, String refName) {
    return withDsl(
        ctx ->
            ctx.select(
                    field("catalog_id", String.class),
                    field("ref_name", String.class),
                    field("ref_type", String.class),
                    field("head_commit_id", String.class),
                    field("deleted", Boolean.class),
                    field("created_by", String.class),
                    field("created_at", OffsetDateTime.class),
                    field("updated_at", OffsetDateTime.class),
                    field("version", Long.class))
                .from(table("catalog_refs"))
                .where(field("catalog_id").eq(catalogId))
                .and(field("ref_name").eq(refName))
                .fetchOptional(
                    record ->
                        new RefRecord(
                            record.value1(),
                            record.value2(),
                            RefType.valueOf(record.value3()),
                            record.value4(),
                            Boolean.TRUE.equals(record.value5()),
                            record.value6(),
                            record.value7(),
                            record.value8(),
                            record.value9())));
  }

  public List<RefRecord> listBranches(String catalogId, int limit, int offset) {
    return withDsl(
        ctx ->
            ctx.select(
                    field("catalog_id", String.class),
                    field("ref_name", String.class),
                    field("ref_type", String.class),
                    field("head_commit_id", String.class),
                    field("deleted", Boolean.class),
                    field("created_by", String.class),
                    field("created_at", OffsetDateTime.class),
                    field("updated_at", OffsetDateTime.class),
                    field("version", Long.class))
                .from(table("catalog_refs"))
                .where(field("catalog_id").eq(catalogId))
                .and(field("ref_type").eq(RefType.BRANCH.name()))
                .and(field("deleted").eq(false))
                .orderBy(field("ref_name").asc())
                .limit(limit)
                .offset(offset)
                .fetch(
                    record ->
                        new RefRecord(
                            record.value1(),
                            record.value2(),
                            RefType.valueOf(record.value3()),
                            record.value4(),
                            Boolean.TRUE.equals(record.value5()),
                            record.value6(),
                            record.value7(),
                            record.value8(),
                            record.value9())));
  }

  public int casUpdateHead(
      String catalogId, String refName, String expectedHeadCommitId, String newHeadCommitId) {
    return withDsl(
        ctx ->
            ctx.update(table("catalog_refs"))
                .set(field("head_commit_id"), newHeadCommitId)
                .set(field("updated_at"), OffsetDateTime.now())
                .set(field("version"), field("version", Long.class).plus(1))
                .where(field("catalog_id").eq(catalogId))
                .and(field("ref_name").eq(refName))
                .and(field("head_commit_id").eq(expectedHeadCommitId))
                .and(field("deleted").eq(false))
                .execute());
  }

  public int deleteRef(String catalogId, String refName) {
    return withDsl(
        ctx ->
            ctx.update(table("catalog_refs"))
                .set(field("deleted"), true)
                .set(field("updated_at"), OffsetDateTime.now())
                .set(field("version"), field("version", Long.class).plus(1))
                .where(field("catalog_id").eq(catalogId))
                .and(field("ref_name").eq(refName))
                .and(field("deleted").eq(false))
                .execute());
  }

  private static boolean isUniqueViolation(DataAccessException e) {
    Throwable current = e;
    while (current != null) {
      if (current instanceof SQLIntegrityConstraintViolationException) {
        return true;
      }
      if ("23505".equals(sqlState(current))) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  private static String sqlState(Throwable throwable) {
    return throwable instanceof SQLException sqlException ? sqlException.getSQLState() : null;
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

  public enum RefType {
    BRANCH,
    TAG
  }

  public record RefRecord(
      String catalogId,
      String refName,
      RefType refType,
      String headCommitId,
      boolean deleted,
      String createdBy,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      long version) {}
}
