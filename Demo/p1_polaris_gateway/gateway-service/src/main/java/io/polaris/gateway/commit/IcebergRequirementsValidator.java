package io.polaris.gateway.commit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.polaris.gateway.store.ContentValueRepository.ContentValueRecord;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class IcebergRequirementsValidator {

  @Inject ObjectMapper objectMapper;

  public void validate(List<Map<String, Object>> requirements, ContentValueRecord current) {
    if (requirements == null || requirements.isEmpty()) return;
    Map<String, Object> meta = parseMeta(current.metadataSummaryJson());
    for (Map<String, Object> req : requirements) {
      String type = (String) req.get("type");
      if (type == null) continue;
      switch (type) {
        case "assert-create" ->
            throw new RequirementFailedException("assert-create: table already exists");
        case "assert-table-uuid" -> {
          String expected = (String) req.get("uuid");
          if (expected != null && !expected.equals(current.tableUuid())) {
            throw new RequirementFailedException(
                "assert-table-uuid: expected " + expected + " got " + current.tableUuid());
          }
        }
        case "assert-ref-snapshot-id" -> {
          long expected = toLong(req.get("snapshot-id"));
          long actual = current.snapshotId() != null ? Long.parseLong(current.snapshotId()) : -1L;
          if (expected != actual) {
            throw new RequirementFailedException(
                "assert-ref-snapshot-id: expected " + expected + " actual " + actual);
          }
        }
        case "assert-last-assigned-field-id" -> {
          long expected = toLong(req.get("last-assigned-field-id"));
          long actual = toLong(meta.get("last-column-id"));
          if (expected != actual) {
            throw new RequirementFailedException(
                "assert-last-assigned-field-id: expected " + expected + " actual " + actual);
          }
        }
        case "assert-current-schema-id" -> {
          int expected = toInt(req.get("current-schema-id"));
          int actual = toInt(meta.get("current-schema-id"));
          if (expected != actual) {
            throw new RequirementFailedException(
                "assert-current-schema-id: expected " + expected + " actual " + actual);
          }
        }
        case "assert-last-assigned-partition-id" -> {
          long expected = toLong(req.get("last-assigned-partition-id"));
          long actual = toLong(meta.get("last-partition-id"));
          if (expected != actual) {
            throw new RequirementFailedException(
                "assert-last-assigned-partition-id: expected " + expected + " actual " + actual);
          }
        }
        case "assert-default-spec-id" -> {
          int expected = toInt(req.get("default-spec-id"));
          int actual = toInt(meta.get("default-spec-id"));
          if (expected != actual) {
            throw new RequirementFailedException(
                "assert-default-spec-id: expected " + expected + " actual " + actual);
          }
        }
        case "assert-default-sort-order-id" -> {
          int expected = toInt(req.get("default-sort-order-id"));
          int actual = toInt(meta.get("default-sort-order-id"));
          if (expected != actual) {
            throw new RequirementFailedException(
                "assert-default-sort-order-id: expected " + expected + " actual " + actual);
          }
        }
        default -> { /* unknown requirement types tolerated */ }
      }
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> parseMeta(String json) {
    if (json == null || json.isBlank()) return Map.of();
    try {
      return objectMapper.readValue(json, new TypeReference<>() {});
    } catch (Exception e) {
      return Map.of();
    }
  }

  private static long toLong(Object v) {
    return v instanceof Number n ? n.longValue() : 0L;
  }

  private static int toInt(Object v) {
    return v instanceof Number n ? n.intValue() : 0;
  }

  public static class RequirementFailedException extends RuntimeException {
    public RequirementFailedException(String message) {
      super(message);
    }
  }
}
