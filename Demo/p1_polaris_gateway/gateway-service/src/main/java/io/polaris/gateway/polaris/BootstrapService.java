package io.polaris.gateway.polaris;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.polaris.gateway.store.CommitRepository;
import io.polaris.gateway.store.CommitRepository.CommitRecord;
import io.polaris.gateway.store.ContentValueRepository;
import io.polaris.gateway.store.ContentValueRepository.ContentValueRecord;
import io.polaris.gateway.store.IndexRepository;
import io.polaris.gateway.store.IndexRepository.ContentIndexRecord;
import io.polaris.gateway.store.IndexRepository.IndexKind;
import io.polaris.gateway.store.IndexRepository.IndexRecord;
import io.polaris.gateway.store.RefRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class BootstrapService {

  @Inject RefRepository refRepository;
  @Inject CommitRepository commitRepository;
  @Inject IndexRepository indexRepository;
  @Inject ContentValueRepository contentValueRepository;
  @Inject PolarisClient polarisClient;
  @Inject ObjectMapper objectMapper;

  @Transactional
  public String bootstrap(String catalogId) {
    if (refRepository.getRef(catalogId, "main").isPresent()) {
      throw new AlreadyBootstrappedException("Catalog already bootstrapped: " + catalogId);
    }

    String token = polarisClient.authenticate();
    String commitId = "c0-" + UUID.randomUUID();
    String indexId = "i0-" + UUID.randomUUID();

    // Insert commit first (catalog_commits.index_id has no FK constraint)
    CommitRecord commit =
        new CommitRecord(commitId, catalogId, null, "gateway", "bootstrap main", null, "{}", indexId, "main", null);
    commitRepository.insertCommit(commit);

    IndexRecord index =
        new IndexRecord(indexId, catalogId, commitId, null, IndexKind.FULL, 0, 1, null);
    indexRepository.insertIndex(index);

    for (String ns : polarisClient.listNamespaces(token, catalogId)) {
      for (String tableName : polarisClient.listTables(token, catalogId, ns)) {
        Map<String, Object> loadResult = polarisClient.loadTable(token, catalogId, ns, tableName);
        ContentValueRecord value = toContentValue(catalogId, ns, tableName, loadResult);
        contentValueRepository.insert(value);
        String contentKey = ns + "." + tableName;
        indexRepository.insertContentIndex(
            new ContentIndexRecord(
                indexId, ns, contentKey, value.contentId(), value.valueId(), "ICEBERG_TABLE", false));
      }
    }

    // object_count is informational; Phase 3 leaves it at 0 (set at index creation above)

    refRepository.createBranch(catalogId, "main", commitId, "gateway");
    return commitId;
  }

  @SuppressWarnings("unchecked")
  private ContentValueRecord toContentValue(
      String catalogId, String ns, String tableName, Map<String, Object> loadResult) {
    String metadataLocation = (String) loadResult.get("metadata-location");
    Map<String, Object> metadata =
        (Map<String, Object>) loadResult.getOrDefault("metadata", Map.of());

    String tableUuid = (String) metadata.get("table-uuid");
    Integer formatVersion = toInt(metadata.get("format-version"));
    String snapshotId = toSnapshotString(metadata.get("current-snapshot-id"));
    Integer schemaId = toInt(metadata.get("current-schema-id"));
    Integer specId = toInt(metadata.get("default-spec-id"));
    Integer sortOrderId = toInt(metadata.get("default-sort-order-id"));
    String tableLocation = (String) metadata.get("location");
    Long lastSequenceNumber = toLong(metadata.get("last-sequence-number"));
    String contentKey = ns + "." + tableName;

    String metadataSummaryJson;
    try {
      metadataSummaryJson = objectMapper.writeValueAsString(metadata);
    } catch (JsonProcessingException e) {
      metadataSummaryJson = "{}";
    }

    return new ContentValueRecord(
        UUID.randomUUID().toString(),
        catalogId,
        tableUuid != null ? tableUuid : UUID.randomUUID().toString(),
        contentKey,
        "ICEBERG_TABLE",
        metadataLocation,
        snapshotId,
        schemaId,
        specId,
        sortOrderId,
        tableUuid,
        tableLocation,
        formatVersion,
        lastSequenceNumber,
        null,
        metadataSummaryJson);
  }

  private static Integer toInt(Object value) {
    return value instanceof Number n ? n.intValue() : null;
  }

  private static Long toLong(Object value) {
    return value instanceof Number n ? n.longValue() : null;
  }

  private static String toSnapshotString(Object value) {
    if (value == null) return null;
    // Iceberg current-snapshot-id == -1 means no snapshot yet
    long id = ((Number) value).longValue();
    return id == -1L ? null : String.valueOf(id);
  }

  public static class AlreadyBootstrappedException extends RuntimeException {
    public AlreadyBootstrappedException(String message) {
      super(message);
    }
  }
}
