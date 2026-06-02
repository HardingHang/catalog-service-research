package io.polaris.gateway.api.iceberg;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.polaris.gateway.commit.CommitService;
import io.polaris.gateway.commit.CommitService.CommitConflictException;
import io.polaris.gateway.commit.CommitService.CommitRequest;
import io.polaris.gateway.commit.CommitService.CommitResult;
import io.polaris.gateway.commit.CommitService.NoSuchTableException;
import io.polaris.gateway.commit.CommitService.RefNotFoundException;
import io.polaris.gateway.commit.IcebergRequirementsValidator.RequirementFailedException;
import io.polaris.gateway.ref.RefResolver;
import io.polaris.gateway.store.CommitRepository;
import io.polaris.gateway.store.ContentValueRepository;
import io.polaris.gateway.store.IndexRepository;
import io.polaris.gateway.store.RefRepository;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;

@Path("/iceberg/v1/{prefix}/namespaces/{namespace}/tables")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class IcebergTableResource {

  @Inject RefResolver refResolver;
  @Inject RefRepository refRepository;
  @Inject CommitRepository commitRepository;
  @Inject IndexRepository indexRepository;
  @Inject ContentValueRepository contentValueRepository;
  @Inject ObjectMapper objectMapper;
  @Inject CommitService commitService;

  @GET
  public Response listTables(
      @PathParam("prefix") String catalog,
      @PathParam("namespace") String namespace,
      @HeaderParam("X-Catalog-Ref") String refHeader) {
    String ref = refResolver.resolve(refHeader);
    var refRecord = refRepository.getRef(catalog, ref);
    if (refRecord.isEmpty()) {
      return Response.status(Response.Status.NOT_FOUND)
          .entity(Map.of("error", "ref not found: " + ref))
          .build();
    }
    if (refRecord.get().headCommitId() == null) {
      return Response.ok(Map.of("identifiers", List.of())).build();
    }
    String indexId = resolveIndexId(refRecord.get().headCommitId());
    if (indexId == null) {
      return Response.ok(Map.of("identifiers", List.of())).build();
    }
    List<Map<String, Object>> identifiers =
        indexRepository.listEffectiveByNamespace(indexId, namespace).stream()
            .map(
                e -> {
                  String key = e.contentKey();
                  String name = key.contains(".") ? key.substring(key.lastIndexOf('.') + 1) : key;
                  return Map.<String, Object>of(
                      "namespace", List.of(namespace.split("\\.")), "name", name);
                })
            .toList();
    return Response.ok(Map.of("identifiers", identifiers)).build();
  }

  @POST
  public Response createTable(
      @PathParam("prefix") String prefix,
      @PathParam("namespace") String namespace,
      Map<String, Object> request) {
    return phaseOnePlaceholder("createTable", prefix, namespace, null);
  }

  @GET
  @Path("/{table}")
  public Response loadTable(
      @PathParam("prefix") String catalog,
      @PathParam("namespace") String namespace,
      @PathParam("table") String table,
      @HeaderParam("X-Catalog-Ref") String refHeader) {
    String ref = refResolver.resolve(refHeader);
    var refOpt = refRepository.getRef(catalog, ref);
    if (refOpt.isEmpty() || refOpt.get().headCommitId() == null) {
      return Response.status(Response.Status.NOT_FOUND)
          .entity(Map.of("error", "ref not found: " + ref))
          .build();
    }
    String indexId = resolveIndexId(refOpt.get().headCommitId());
    if (indexId == null) {
      return Response.status(Response.Status.NOT_FOUND)
          .entity(Map.of("error", "no index for ref: " + ref))
          .build();
    }
    String contentKey = namespace + "." + table;
    var contentIndex = indexRepository.findEffectiveContent(indexId, contentKey);
    if (contentIndex.isEmpty()) {
      return Response.status(Response.Status.NOT_FOUND)
          .entity(Map.of("error", "table not found: " + contentKey))
          .build();
    }
    var valueOpt = contentValueRepository.findByValueId(contentIndex.get().valueId());
    if (valueOpt.isEmpty()) {
      return Response.status(Response.Status.NOT_FOUND)
          .entity(Map.of("error", "content value not found"))
          .build();
    }
    var value = valueOpt.get();
    Object metadataObj;
    try {
      metadataObj = objectMapper.readValue(value.metadataSummaryJson(), Object.class);
    } catch (JsonProcessingException e) {
      metadataObj = Map.of();
    }
    return Response.ok(
            Map.of(
                "metadata-location",
                value.metadataLocation() != null ? value.metadataLocation() : "",
                "metadata",
                metadataObj,
                "config",
                Map.of()))
        .build();
  }

  @POST
  @Path("/{table}")
  public Response commitTable(
      @PathParam("prefix") String catalog,
      @PathParam("namespace") String namespace,
      @PathParam("table") String table,
      @HeaderParam("X-Catalog-Ref") String refHeader,
      @HeaderParam("Idempotency-Key") String idempotencyKey,
      @HeaderParam("X-Request-ID") String requestId,
      Map<String, Object> body) {
    String ref = refResolver.resolve(refHeader);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> requirements =
        body != null ? (List<Map<String, Object>>) body.get("requirements") : null;
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> updates =
        body != null ? (List<Map<String, Object>>) body.get("updates") : null;

    CommitRequest request =
        new CommitRequest(
            catalog, ref, namespace, table, idempotencyKey, requestId, requirements, updates);
    try {
      CommitResult result = commitService.commit(request);
      Object metadata;
      try {
        metadata = objectMapper.readValue(result.metadataJson(), new TypeReference<>() {});
      } catch (JsonProcessingException e) {
        metadata = Map.of();
      }
      return Response.ok(
              Map.of("metadata-location", result.metadataLocation(), "metadata", metadata))
          .build();
    } catch (RequirementFailedException | CommitConflictException e) {
      return Response.status(Response.Status.CONFLICT)
          .entity(Map.of("error", e.getMessage()))
          .build();
    } catch (RefNotFoundException | NoSuchTableException e) {
      return Response.status(Response.Status.NOT_FOUND)
          .entity(Map.of("error", e.getMessage()))
          .build();
    }
  }

  @DELETE
  @Path("/{table}")
  public Response dropTable(
      @PathParam("prefix") String prefix,
      @PathParam("namespace") String namespace,
      @PathParam("table") String table) {
    return phaseOnePlaceholder("dropTable", prefix, namespace, table);
  }

  @GET
  @Path("/{table}/credentials")
  public Response tableCredentials(
      @PathParam("prefix") String prefix,
      @PathParam("namespace") String namespace,
      @PathParam("table") String table) {
    return phaseOnePlaceholder("tableCredentials", prefix, namespace, table);
  }

  private String resolveIndexId(String headCommitId) {
    if (headCommitId == null) return null;
    return commitRepository
        .findById(headCommitId)
        .map(CommitRepository.CommitRecord::indexId)
        .orElse(null);
  }

  private static Response phaseOnePlaceholder(
      String operation, String prefix, String namespace, String table) {
    return Response.status(Response.Status.NOT_IMPLEMENTED)
        .entity(
            Map.of(
                "code", "PHASE_1_PLACEHOLDER",
                "operation", operation,
                "prefix", prefix,
                "namespace", namespace,
                "table", table == null ? "" : table))
        .build();
  }
}
