package io.polaris.gateway.commit;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.polaris.gateway.audit.AuditService;
import io.polaris.gateway.commit.IcebergRequirementsValidator.RequirementFailedException;
import io.polaris.gateway.store.CommitRepository;
import io.polaris.gateway.store.CommitRepository.CommitRecord;
import io.polaris.gateway.store.ContentValueRepository;
import io.polaris.gateway.store.ContentValueRepository.ContentValueRecord;
import io.polaris.gateway.store.IndexRepository;
import io.polaris.gateway.store.IndexRepository.ContentIndexRecord;
import io.polaris.gateway.store.IndexRepository.IndexKind;
import io.polaris.gateway.store.IndexRepository.IndexRecord;
import io.polaris.gateway.store.RefRepository;
import io.polaris.gateway.store.RefRepository.RefRecord;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

@ApplicationScoped
public class CommitService {

  @Inject RefRepository refRepository;
  @Inject CommitRepository commitRepository;
  @Inject IndexRepository indexRepository;
  @Inject ContentValueRepository contentValueRepository;
  @Inject IcebergRequirementsValidator requirementsValidator;
  @Inject IcebergMetadataWriter metadataWriter;
  @Inject AuditService auditService;
  @Inject ObjectMapper objectMapper;
  @Inject DataSource dataSource;

  public record CommitRequest(
      String catalogId,
      String ref,
      String namespace,
      String table,
      String idempotencyKey,
      String requestId,
      List<Map<String, Object>> requirements,
      List<Map<String, Object>> updates) {}

  public record CommitResult(String metadataLocation, String metadataJson) {}

  @Transactional
  public CommitResult commit(CommitRequest request) {
    // 1. Idempotency check
    if (request.idempotencyKey() != null) {
      Optional<String> cached = findCompletedIdempotency(request.idempotencyKey());
      if (cached.isPresent()) {
        try {
          return objectMapper.readValue(cached.get(), CommitResult.class);
        } catch (JsonProcessingException e) {
          throw new IllegalStateException("Failed to parse cached idempotency response", e);
        }
      }
    }

    // 2. Resolve ref state
    RefRecord ref =
        refRepository
            .getRef(request.catalogId(), request.ref())
            .filter(r -> !r.deleted())
            .orElseThrow(() -> new RefNotFoundException("ref not found: " + request.ref()));
    String expectedHead = ref.headCommitId();
    if (expectedHead == null) {
      throw new CommitConflictException("ref has no head commit: " + request.ref());
    }

    // 3. Resolve current content value via commit → index → content_index
    String currentIndexId =
        commitRepository
            .findById(expectedHead)
            .map(CommitRecord::indexId)
            .orElseThrow(
                () -> new IllegalStateException("commit not found: " + expectedHead));
    String contentKey = request.namespace() + "." + request.table();
    ContentIndexRecord indexEntry =
        indexRepository
            .findEffectiveContent(currentIndexId, contentKey)
            .orElseThrow(() -> new NoSuchTableException(contentKey));
    ContentValueRecord current =
        contentValueRepository
            .findByValueId(indexEntry.valueId())
            .orElseThrow(() -> new NoSuchTableException(contentKey));

    // 4. Validate requirements
    requirementsValidator.validate(request.requirements(), current);

    // 5. Apply updates → new metadata map
    Map<String, Object> updatedMeta;
    try {
      updatedMeta =
          applyUpdates(
              objectMapper.readValue(
                  current.metadataSummaryJson(), new TypeReference<>() {}),
              request.updates());
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to parse metadata JSON", e);
    }

    String updatedMetaJson;
    try {
      updatedMetaJson = objectMapper.writeValueAsString(updatedMeta);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize updated metadata", e);
    }

    // 6. Write new metadata file to object storage (non-JTA; orphaned on CAS failure)
    String tableLocation =
        current.tableLocation() != null ? current.tableLocation() : "";
    String newMetadataLocation = metadataWriter.write(updatedMetaJson, tableLocation);

    // 7. New IDs
    String newValueId = "v-" + UUID.randomUUID();
    String newCommitId = "c-" + UUID.randomUUID();
    String newIndexId = "i-" + UUID.randomUUID();

    // 8. Insert new content value
    String newSnapshotId = extractCurrentSnapshotId(updatedMeta);
    contentValueRepository.insert(
        new ContentValueRecord(
            newValueId,
            current.catalogId(),
            current.contentId(),
            contentKey,
            current.contentType(),
            newMetadataLocation,
            newSnapshotId,
            current.schemaId(),
            current.specId(),
            current.sortOrderId(),
            current.tableUuid(),
            current.tableLocation(),
            current.formatVersion(),
            current.lastSequenceNumber(),
            null,
            updatedMetaJson));

    // 9. Insert commit
    commitRepository.insertCommit(
        new CommitRecord(
            newCommitId,
            current.catalogId(),
            expectedHead,
            "gateway",
            "commit " + contentKey,
            null,
            "{}",
            newIndexId,
            request.ref(),
            request.requestId()));

    // 10. Insert incremental index + content index entry
    indexRepository.insertIndex(
        new IndexRecord(
            newIndexId,
            current.catalogId(),
            newCommitId,
            currentIndexId,
            IndexKind.INCREMENTAL,
            1,
            1,
            null));
    indexRepository.insertContentIndex(
        new ContentIndexRecord(
            newIndexId,
            request.namespace(),
            contentKey,
            current.contentId(),
            newValueId,
            current.contentType(),
            false));

    // 11. Insert commit op
    insertCommitOp(newCommitId, 0, contentKey, current.valueId(), newValueId);

    // 12. CAS: update ref head — if 0 rows, another commit won the race
    int rows =
        refRepository.casUpdateHead(
            request.catalogId(), request.ref(), expectedHead, newCommitId);
    if (rows == 0) {
      throw new CommitConflictException(
          "Concurrent modification on ref: " + request.ref());
    }

    // 13. Audit
    auditService.record(
        UUID.randomUUID().toString(),
        current.catalogId(),
        request.ref(),
        "gateway",
        "TABLE_COMMIT",
        newCommitId,
        request.requestId(),
        "{}");

    // 14. Record idempotency key as COMPLETED
    CommitResult result = new CommitResult(newMetadataLocation, updatedMetaJson);
    if (request.idempotencyKey() != null) {
      try {
        insertIdempotency(
            request.idempotencyKey(), objectMapper.writeValueAsString(result));
      } catch (JsonProcessingException e) {
        throw new IllegalStateException("Failed to serialize idempotency result", e);
      }
    }

    return result;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> applyUpdates(
      Map<String, Object> metadata, List<Map<String, Object>> updates) {
    Map<String, Object> result = new HashMap<>(metadata);
    if (updates == null) return result;
    for (Map<String, Object> update : updates) {
      String action = (String) update.get("action");
      if (action == null) continue;
      switch (action) {
        case "add-snapshot" -> {
          List<Map<String, Object>> snapshots =
              (List<Map<String, Object>>)
                  result.computeIfAbsent("snapshots", k -> new ArrayList<>());
          Object snap = update.get("snapshot");
          if (snap instanceof Map<?, ?> snapMap) {
            snapshots.add((Map<String, Object>) snapMap);
            Object seqNum = ((Map<String, Object>) snapMap).get("sequence-number");
            if (seqNum instanceof Number n) result.put("last-sequence-number", n.longValue());
          }
        }
        case "set-snapshot-ref" -> {
          String refName = (String) update.get("ref-name");
          Object snapshotId = update.get("snapshot-id");
          if ("main".equals(refName)) {
            result.put("current-snapshot-id", snapshotId);
          }
          Map<String, Object> refs =
              (Map<String, Object>) result.computeIfAbsent("refs", k -> new HashMap<>());
          Map<String, Object> refEntry = new HashMap<>();
          refEntry.put("snapshot-id", snapshotId);
          refEntry.put("type", update.getOrDefault("type", "branch"));
          refs.put(refName, refEntry);
        }
        case "set-current-schema" -> result.put("current-schema-id", update.get("schema-id"));
        case "set-default-partition-spec" ->
            result.put("default-spec-id", update.get("spec-id"));
        case "set-default-sort-order" ->
            result.put("default-sort-order-id", update.get("sort-order-id"));
        default -> { /* other action types ignored for Phase 4 */ }
      }
    }
    return result;
  }

  private static String extractCurrentSnapshotId(Map<String, Object> metadata) {
    Object id = metadata.get("current-snapshot-id");
    if (id == null) return null;
    long val = ((Number) id).longValue();
    return val == -1L ? null : String.valueOf(val);
  }

  private Optional<String> findCompletedIdempotency(String key) {
    try (Connection conn = dataSource.getConnection()) {
      DSLContext ctx = DSL.using(conn, SQLDialect.POSTGRES);
      return ctx.select(field("response_payload", JSONB.class))
          .from(table("idempotency_keys"))
          .where(field("key").eq(key))
          .and(field("status").eq("COMPLETED"))
          .fetchOptional(r -> r.value1() != null ? r.value1().data() : null);
    } catch (SQLException e) {
      throw new IllegalStateException("Idempotency check failed", e);
    }
  }

  private void insertIdempotency(String key, String responseJson) {
    try (Connection conn = dataSource.getConnection()) {
      DSLContext ctx = DSL.using(conn, SQLDialect.POSTGRES);
      ctx.insertInto(table("idempotency_keys"))
          .columns(
              field("key"),
              field("request_hash"),
              field("status"),
              field("response_payload"),
              field("expires_at"))
          .values(
              key,
              "",
              "COMPLETED",
              JSONB.valueOf(responseJson),
              OffsetDateTime.now().plusHours(24))
          .execute();
    } catch (SQLException e) {
      throw new IllegalStateException("Idempotency insert failed", e);
    }
  }

  private void insertCommitOp(
      String commitId, int ordinal, String contentKey, String expectedValueId, String newValueId) {
    try (Connection conn = dataSource.getConnection()) {
      DSLContext ctx = DSL.using(conn, SQLDialect.POSTGRES);
      ctx.insertInto(table("catalog_commit_ops"))
          .columns(
              field("commit_id"),
              field("op_ordinal"),
              field("content_key"),
              field("op_type"),
              field("expected_value_id"),
              field("new_value_id"))
          .values(commitId, ordinal, contentKey, "PUT", expectedValueId, newValueId)
          .execute();
    } catch (SQLException e) {
      throw new IllegalStateException("CommitOp insert failed", e);
    }
  }

  public record TableChange(
      String namespace,
      String table,
      List<Map<String, Object>> requirements,
      List<Map<String, Object>> updates) {}

  public record MultiTableCommitRequest(
      String catalogId,
      String ref,
      String idempotencyKey,
      String requestId,
      List<TableChange> tableChanges) {}

  public record MultiTableCommitResult(String commitId) {}

  private record TableChangeResult(
      String contentKey,
      String namespace,
      ContentValueRecord current,
      String newValueId) {}

  @Transactional
  public MultiTableCommitResult multiTableCommit(MultiTableCommitRequest request) {
    // 1. Idempotency check
    if (request.idempotencyKey() != null) {
      Optional<String> cached = findCompletedIdempotency(request.idempotencyKey());
      if (cached.isPresent()) {
        try {
          return objectMapper.readValue(cached.get(), MultiTableCommitResult.class);
        } catch (JsonProcessingException e) {
          throw new IllegalStateException("Failed to parse cached idempotency response", e);
        }
      }
    }

    // 2. Resolve ref state
    RefRecord ref =
        refRepository
            .getRef(request.catalogId(), request.ref())
            .filter(r -> !r.deleted())
            .orElseThrow(() -> new RefNotFoundException("ref not found: " + request.ref()));
    String expectedHead = ref.headCommitId();
    if (expectedHead == null) {
      throw new CommitConflictException("ref has no head commit: " + request.ref());
    }

    // 3. Resolve current index
    String currentIndexId =
        commitRepository
            .findById(expectedHead)
            .map(CommitRecord::indexId)
            .orElseThrow(() -> new IllegalStateException("commit not found: " + expectedHead));

    // 4. For each table: validate, apply updates, write metadata
    String newCommitId = "c-" + UUID.randomUUID();
    String newIndexId = "i-" + UUID.randomUUID();

    List<TableChangeResult> results = new ArrayList<>();
    for (TableChange change : request.tableChanges()) {
      String contentKey = change.namespace() + "." + change.table();
      ContentIndexRecord indexEntry =
          indexRepository
              .findEffectiveContent(currentIndexId, contentKey)
              .orElseThrow(() -> new NoSuchTableException(contentKey));
      ContentValueRecord current =
          contentValueRepository
              .findByValueId(indexEntry.valueId())
              .orElseThrow(() -> new NoSuchTableException(contentKey));

      requirementsValidator.validate(change.requirements(), current);

      Map<String, Object> updatedMeta;
      try {
        updatedMeta =
            applyUpdates(
                objectMapper.readValue(current.metadataSummaryJson(), new TypeReference<>() {}),
                change.updates());
      } catch (JsonProcessingException e) {
        throw new IllegalStateException("Failed to parse metadata JSON for " + contentKey, e);
      }

      String updatedMetaJson;
      try {
        updatedMetaJson = objectMapper.writeValueAsString(updatedMeta);
      } catch (JsonProcessingException e) {
        throw new IllegalStateException("Failed to serialize updated metadata for " + contentKey, e);
      }

      String tableLocation = current.tableLocation() != null ? current.tableLocation() : "";
      String newMetadataLocation = metadataWriter.write(updatedMetaJson, tableLocation);

      String newValueId = "v-" + UUID.randomUUID();
      String newSnapshotId = extractCurrentSnapshotId(updatedMeta);
      contentValueRepository.insert(
          new ContentValueRecord(
              newValueId,
              current.catalogId(),
              current.contentId(),
              contentKey,
              current.contentType(),
              newMetadataLocation,
              newSnapshotId,
              current.schemaId(),
              current.specId(),
              current.sortOrderId(),
              current.tableUuid(),
              current.tableLocation(),
              current.formatVersion(),
              current.lastSequenceNumber(),
              null,
              updatedMetaJson));

      results.add(new TableChangeResult(contentKey, change.namespace(), current, newValueId));
    }

    // 5. One commit record
    commitRepository.insertCommit(
        new CommitRecord(
            newCommitId,
            request.catalogId(),
            expectedHead,
            "gateway",
            "multi-table commit (" + results.size() + " tables)",
            null,
            "{}",
            newIndexId,
            request.ref(),
            request.requestId()));

    // 6. One incremental index + N content index entries
    indexRepository.insertIndex(
        new IndexRecord(
            newIndexId,
            request.catalogId(),
            newCommitId,
            currentIndexId,
            IndexKind.INCREMENTAL,
            results.size(),
            1,
            null));
    for (TableChangeResult r : results) {
      indexRepository.insertContentIndex(
          new ContentIndexRecord(
              newIndexId,
              r.namespace(),
              r.contentKey(),
              r.current().contentId(),
              r.newValueId(),
              r.current().contentType(),
              false));
    }

    // 7. N commit ops
    for (int i = 0; i < results.size(); i++) {
      TableChangeResult r = results.get(i);
      insertCommitOp(newCommitId, i, r.contentKey(), r.current().valueId(), r.newValueId());
    }

    // 8. CAS
    int rows =
        refRepository.casUpdateHead(
            request.catalogId(), request.ref(), expectedHead, newCommitId);
    if (rows == 0) {
      throw new CommitConflictException("Concurrent modification on ref: " + request.ref());
    }

    // 9. Audit
    auditService.record(
        UUID.randomUUID().toString(),
        request.catalogId(),
        request.ref(),
        "gateway",
        "TABLE_COMMIT",
        newCommitId,
        request.requestId(),
        "{}");

    // 10. Idempotency
    MultiTableCommitResult result = new MultiTableCommitResult(newCommitId);
    if (request.idempotencyKey() != null) {
      try {
        insertIdempotency(request.idempotencyKey(), objectMapper.writeValueAsString(result));
      } catch (JsonProcessingException e) {
        throw new IllegalStateException("Failed to serialize idempotency result", e);
      }
    }

    return result;
  }

  public static class CommitConflictException extends RuntimeException {
    public CommitConflictException(String message) {
      super(message);
    }
  }

  public static class RefNotFoundException extends RuntimeException {
    public RefNotFoundException(String message) {
      super(message);
    }
  }

  public static class NoSuchTableException extends RuntimeException {
    public NoSuchTableException(String message) {
      super(message);
    }
  }
}
