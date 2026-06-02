package io.polaris.gateway.store;

import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

@ApplicationScoped
public class IndexRepository {
  public static final int INCREMENTAL_INDEX_COMPACTION_THRESHOLD = 20;
  public static final int INDEX_OPS_COMPACTION_THRESHOLD = 1000;

  private DataSource dataSource;

  public IndexRepository() {}

  @Inject
  public IndexRepository(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  public void insertIndex(IndexRecord index) {
    withDsl(
        ctx -> {
          ctx.insertInto(table("catalog_ref_indexes"))
              .columns(
                  field("index_id"),
                  field("catalog_id"),
                  field("commit_id"),
                  field("parent_index_id"),
                  field("index_kind"),
                  field("object_count"),
                  field("stripe_count"))
              .values(
                  index.indexId(),
                  index.catalogId(),
                  index.commitId(),
                  index.parentIndexId(),
                  index.indexKind().name(),
                  index.objectCount(),
                  index.stripeCount())
              .execute();
          return null;
        });
  }

  public Optional<IndexRecord> findById(String indexId) {
    return withDsl(
        ctx ->
            ctx.select(
                    field("index_id", String.class),
                    field("catalog_id", String.class),
                    field("commit_id", String.class),
                    field("parent_index_id", String.class),
                    field("index_kind", String.class),
                    field("object_count", Long.class),
                    field("stripe_count", Integer.class),
                    field("created_at", OffsetDateTime.class))
                .from(table("catalog_ref_indexes"))
                .where(field("index_id").eq(indexId))
                .fetchOptional(
                    record ->
                        new IndexRecord(
                            record.value1(),
                            record.value2(),
                            record.value3(),
                            record.value4(),
                            IndexKind.valueOf(record.value5()),
                            record.value6(),
                            record.value7(),
                            record.value8())));
  }

  public void insertContentIndex(ContentIndexRecord content) {
    withDsl(
        ctx -> {
          ctx.insertInto(table("catalog_content_index"))
              .columns(
                  field("index_id"),
                  field("namespace"),
                  field("content_key"),
                  field("content_id"),
                  field("value_id"),
                  field("content_type"),
                  field("deleted"))
              .values(
                  content.indexId(),
                  content.namespace(),
                  content.contentKey(),
                  content.contentId(),
                  content.valueId(),
                  content.contentType(),
                  content.deleted())
              .execute();
          return null;
        });
  }

  public Optional<ContentIndexRecord> findContent(String indexId, String contentKey) {
    return withDsl(
        ctx ->
            ctx.select(
                    field("index_id", String.class),
                    field("namespace", String.class),
                    field("content_key", String.class),
                    field("content_id", String.class),
                    field("value_id", String.class),
                    field("content_type", String.class),
                    field("deleted", Boolean.class))
                .from(table("catalog_content_index"))
                .where(field("index_id").eq(indexId))
                .and(field("content_key").eq(contentKey))
                .fetchOptional(
                    record ->
                        new ContentIndexRecord(
                            record.value1(),
                            record.value2(),
                            record.value3(),
                            record.value4(),
                            record.value5(),
                            record.value6(),
                            Boolean.TRUE.equals(record.value7()))));
  }

  public List<String> listNamespaces(String indexId) {
    return withDsl(
        ctx ->
            ctx.selectDistinct(field("namespace", String.class))
                .from(table("catalog_content_index"))
                .where(field("index_id").eq(indexId))
                .and(field("deleted").eq(false))
                .orderBy(field("namespace").asc())
                .fetch(record -> record.value1()));
  }

  public List<ContentIndexRecord> listByNamespace(String indexId, String namespace) {
    return withDsl(
        ctx ->
            ctx.select(
                    field("index_id", String.class),
                    field("namespace", String.class),
                    field("content_key", String.class),
                    field("content_id", String.class),
                    field("value_id", String.class),
                    field("content_type", String.class),
                    field("deleted", Boolean.class))
                .from(table("catalog_content_index"))
                .where(field("index_id").eq(indexId))
                .and(field("namespace").eq(namespace))
                .and(field("deleted").eq(false))
                .fetch(
                    record ->
                        new ContentIndexRecord(
                            record.value1(),
                            record.value2(),
                            record.value3(),
                            record.value4(),
                            record.value5(),
                            record.value6(),
                            Boolean.TRUE.equals(record.value7()))));
  }

  public List<ContentIndexRecord> listAllContentAtIndex(String indexId) {
    return withDsl(
        ctx ->
            ctx.select(
                    field("index_id", String.class),
                    field("namespace", String.class),
                    field("content_key", String.class),
                    field("content_id", String.class),
                    field("value_id", String.class),
                    field("content_type", String.class),
                    field("deleted", Boolean.class))
                .from(table("catalog_content_index"))
                .where(field("index_id").eq(indexId))
                .fetch(
                    record ->
                        new ContentIndexRecord(
                            record.value1(),
                            record.value2(),
                            record.value3(),
                            record.value4(),
                            record.value5(),
                            record.value6(),
                            Boolean.TRUE.equals(record.value7()))));
  }

  /**
   * Walks the incremental index chain from {@code indexId} up to the FULL root, returning the
   * effective (non-deleted) content map keyed by content_key. Entries from newer indexes take
   * precedence over parent indexes.
   */
  public Map<String, ContentIndexRecord> resolveAllContent(String indexId) {
    Map<String, ContentIndexRecord> byKey = new LinkedHashMap<>();
    String cursor = indexId;
    while (cursor != null) {
      Optional<IndexRecord> idx = findById(cursor);
      if (idx.isEmpty()) break;
      for (ContentIndexRecord r : listAllContentAtIndex(cursor)) {
        byKey.putIfAbsent(r.contentKey(), r);
      }
      cursor = idx.get().parentIndexId();
    }
    byKey.values().removeIf(ContentIndexRecord::deleted);
    return byKey;
  }

  /**
   * Walks the index chain from {@code indexId} toward the FULL root, returning the first
   * non-deleted entry for {@code contentKey}. An explicit deleted=true entry stops the search.
   */
  public Optional<ContentIndexRecord> findEffectiveContent(String indexId, String contentKey) {
    String cursor = indexId;
    while (cursor != null) {
      Optional<ContentIndexRecord> found = findContent(cursor, contentKey);
      if (found.isPresent()) {
        return found.get().deleted() ? Optional.empty() : found;
      }
      Optional<IndexRecord> idx = findById(cursor);
      if (idx.isEmpty()) break;
      cursor = idx.get().parentIndexId();
    }
    return Optional.empty();
  }

  /** Returns the effective (non-deleted) content entries visible at {@code indexId} for the given namespace. */
  public List<ContentIndexRecord> listEffectiveByNamespace(String indexId, String namespace) {
    return resolveAllContent(indexId).values().stream()
        .filter(r -> namespace.equals(r.namespace()))
        .toList();
  }

  /** Returns distinct namespaces visible at {@code indexId} across the full index chain. */
  public List<String> listEffectiveNamespaces(String indexId) {
    return resolveAllContent(indexId).values().stream()
        .map(ContentIndexRecord::namespace)
        .collect(Collectors.toCollection(java.util.LinkedHashSet::new))
        .stream()
        .sorted()
        .toList();
  }

  public boolean shouldCompact(String catalogId, String refName) {
    return incrementalIndexCount(catalogId, refName) >= INCREMENTAL_INDEX_COMPACTION_THRESHOLD
        || commitOpCount(catalogId, refName) > INDEX_OPS_COMPACTION_THRESHOLD;
  }

  private int incrementalIndexCount(String catalogId, String refName) {
    return withDsl(
        ctx ->
            ctx.selectCount()
                .from(table("catalog_ref_indexes").as("i"))
                .join(table("catalog_commits").as("c"))
                .on(field("i.commit_id").eq(field("c.commit_id")))
                .where(field("i.catalog_id").eq(catalogId))
                .and(field("i.index_kind").eq(IndexKind.INCREMENTAL.name()))
                .and(field("c.source_ref").eq(refName))
                .fetchOne(0, int.class));
  }

  private int commitOpCount(String catalogId, String refName) {
    return withDsl(
        ctx ->
            ctx.select(count())
                .from(table("catalog_commit_ops").as("o"))
                .join(table("catalog_commits").as("c"))
                .on(field("o.commit_id").eq(field("c.commit_id")))
                .where(field("c.catalog_id").eq(catalogId))
                .and(field("c.source_ref").eq(refName))
                .fetchOne(0, int.class));
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

  public enum IndexKind {
    FULL,
    INCREMENTAL
  }

  public record IndexRecord(
      String indexId,
      String catalogId,
      String commitId,
      String parentIndexId,
      IndexKind indexKind,
      long objectCount,
      int stripeCount,
      OffsetDateTime createdAt) {}

  public record ContentIndexRecord(
      String indexId,
      String namespace,
      String contentKey,
      String contentId,
      String valueId,
      String contentType,
      boolean deleted) {}
}
