package io.polaris.gateway.api.iceberg;

import io.polaris.gateway.commit.CommitService;
import io.polaris.gateway.commit.CommitService.CommitConflictException;
import io.polaris.gateway.commit.CommitService.MultiTableCommitRequest;
import io.polaris.gateway.commit.CommitService.MultiTableCommitResult;
import io.polaris.gateway.commit.CommitService.NoSuchTableException;
import io.polaris.gateway.commit.CommitService.RefNotFoundException;
import io.polaris.gateway.commit.CommitService.TableChange;
import io.polaris.gateway.commit.IcebergRequirementsValidator.RequirementFailedException;
import io.polaris.gateway.ref.RefResolver;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;

@Path("/iceberg/v1/{prefix}")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class IcebergOperationsResource {

  @Inject CommitService commitService;
  @Inject RefResolver refResolver;

  @POST
  @Path("/tables/rename")
  public Response renameTable(
      @PathParam("prefix") String prefix, Map<String, Object> request) {
    return phaseOnePlaceholder("renameTable", prefix);
  }

  @SuppressWarnings("unchecked")
  @POST
  @Path("/transactions/commit")
  public Response commitTransaction(
      @PathParam("prefix") String prefix,
      @HeaderParam("X-Catalog-Ref") String refHeader,
      @HeaderParam("Idempotency-Key") String idempotencyKey,
      @HeaderParam("X-Request-ID") String requestId,
      Map<String, Object> body) {
    String ref = refResolver.resolve(refHeader);
    List<Map<String, Object>> rawChanges =
        body != null ? (List<Map<String, Object>>) body.get("table-changes") : List.of();
    if (rawChanges == null) rawChanges = List.of();

    List<TableChange> tableChanges =
        rawChanges.stream()
            .map(
                tc -> {
                  Map<String, Object> identifier = (Map<String, Object>) tc.get("identifier");
                  List<String> nsList = (List<String>) identifier.get("namespace");
                  String namespace = String.join(".", nsList);
                  String tableName = (String) identifier.get("name");
                  List<Map<String, Object>> reqs =
                      (List<Map<String, Object>>) tc.get("requirements");
                  List<Map<String, Object>> updates =
                      (List<Map<String, Object>>) tc.get("updates");
                  return new TableChange(namespace, tableName, reqs, updates);
                })
            .toList();

    MultiTableCommitRequest request =
        new MultiTableCommitRequest(prefix, ref, idempotencyKey, requestId, tableChanges);
    try {
      MultiTableCommitResult result = commitService.multiTableCommit(request);
      return Response.ok(Map.of("commitId", result.commitId())).build();
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

  private static Response phaseOnePlaceholder(String operation, String prefix) {
    return Response.status(Response.Status.NOT_IMPLEMENTED)
        .entity(
            Map.of(
                "code", "PHASE_1_PLACEHOLDER",
                "operation", operation,
                "prefix", prefix))
        .build();
  }
}
