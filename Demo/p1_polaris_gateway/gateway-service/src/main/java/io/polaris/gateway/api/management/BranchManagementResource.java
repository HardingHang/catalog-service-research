package io.polaris.gateway.api.management;

import io.polaris.gateway.audit.AuditService;
import io.polaris.gateway.diff.DiffService;
import io.polaris.gateway.diff.DiffService.DiffResult;
import io.polaris.gateway.publish.LockService.LockConflictException;
import io.polaris.gateway.publish.PublishPlanner;
import io.polaris.gateway.publish.PublishPlanner.PublishPlan;
import io.polaris.gateway.publish.PublishService;
import io.polaris.gateway.publish.PublishService.PublishConflictException;
import io.polaris.gateway.publish.PublishService.PublishRejectedException;
import io.polaris.gateway.store.RefRepository;
import io.polaris.gateway.store.StoreConflictException;
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
import java.util.UUID;

@Path("/api/v1/catalogs/{catalog}/branches")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class BranchManagementResource {

  @Inject RefRepository refRepository;
  @Inject AuditService auditService;
  @Inject DiffService diffService;
  @Inject PublishPlanner publishPlanner;
  @Inject PublishService publishService;

  @POST
  public Response createBranch(
      @PathParam("catalog") String catalog, Map<String, Object> request) {
    if (request == null) {
      return Response.status(Response.Status.BAD_REQUEST)
          .entity(Map.of("error", "request body required"))
          .build();
    }
    String name = (String) request.get("name");
    String sourceRef = request.containsKey("sourceRef") ? (String) request.get("sourceRef") : "main";
    if (name == null || name.isBlank()) {
      return Response.status(Response.Status.BAD_REQUEST)
          .entity(Map.of("error", "name is required"))
          .build();
    }
    var sourceOpt = refRepository.getRef(catalog, sourceRef);
    if (sourceOpt.isEmpty() || sourceOpt.get().deleted()) {
      return Response.status(Response.Status.NOT_FOUND)
          .entity(Map.of("error", "sourceRef not found: " + sourceRef))
          .build();
    }
    String sourceHead = sourceOpt.get().headCommitId();
    try {
      RefRepository.RefRecord created = refRepository.createBranch(catalog, name, sourceHead, "gateway");
      auditService.record(
          UUID.randomUUID().toString(), catalog, name, "gateway",
          "CREATE_BRANCH", sourceHead, null, "{}");
      return Response.status(Response.Status.CREATED)
          .entity(
              Map.of(
                  "name", created.refName(),
                  "headCommitId", created.headCommitId() != null ? created.headCommitId() : "",
                  "sourceRef", sourceRef))
          .build();
    } catch (StoreConflictException e) {
      return Response.status(Response.Status.CONFLICT)
          .entity(Map.of("error", e.getMessage()))
          .build();
    }
  }

  @GET
  public Response listBranches(@PathParam("catalog") String catalog) {
    return phaseOnePlaceholder("listBranches", catalog);
  }

  @GET
  @Path("/{branch}")
  public Response getBranch(@PathParam("catalog") String catalog, @PathParam("branch") String branch) {
    return phaseOnePlaceholder("getBranch", catalog, branch);
  }

  @DELETE
  @Path("/{branch}")
  public Response deleteBranch(
      @PathParam("catalog") String catalog, @PathParam("branch") String branch) {
    return phaseOnePlaceholder("deleteBranch", catalog, branch);
  }

  @POST
  @Path("/{branch}/diff")
  public Response diffBranch(
      @PathParam("catalog") String catalog,
      @PathParam("branch") String branch,
      Map<String, Object> body) {
    String baseRef = body != null ? (String) body.getOrDefault("baseRef", "main") : "main";
    try {
      DiffResult result = diffService.diff(catalog, branch, baseRef);
      return Response.ok(result).build();
    } catch (IllegalArgumentException e) {
      return Response.status(Response.Status.NOT_FOUND)
          .entity(Map.of("error", e.getMessage()))
          .build();
    }
  }

  @POST
  @Path("/{branch}/publish-plan")
  public Response publishPlan(
      @PathParam("catalog") String catalog,
      @PathParam("branch") String branch,
      Map<String, Object> request) {
    String targetRef = request != null ? (String) request.getOrDefault("targetRef", "main") : "main";
    try {
      PublishPlan plan = publishPlanner.plan(catalog, branch, targetRef);
      return Response.ok(
              Map.of(
                  "publishable", plan.publishable(),
                  "branchHead", plan.branchHead() != null ? plan.branchHead() : "",
                  "targetHead", plan.targetHead() != null ? plan.targetHead() : "",
                  "conflicts", plan.conflicts()))
          .build();
    } catch (IllegalArgumentException e) {
      return Response.status(Response.Status.NOT_FOUND)
          .entity(Map.of("error", e.getMessage()))
          .build();
    }
  }

  @POST
  @Path("/{branch}/publish")
  public Response publish(
      @PathParam("catalog") String catalog,
      @PathParam("branch") String branch,
      @HeaderParam("Idempotency-Key") String idempotencyKey,
      @HeaderParam("X-Request-ID") String requestId,
      Map<String, Object> request) {
    if (request == null) {
      return Response.status(Response.Status.BAD_REQUEST)
          .entity(Map.of("error", "request body required"))
          .build();
    }
    String targetRef = (String) request.getOrDefault("targetRef", "main");
    String expectedTargetHead = (String) request.get("expectedTargetHead");
    String expectedSourceHead = (String) request.get("expectedSourceHead");
    if (expectedTargetHead == null || expectedSourceHead == null) {
      return Response.status(Response.Status.BAD_REQUEST)
          .entity(Map.of("error", "expectedTargetHead and expectedSourceHead are required"))
          .build();
    }
    try {
      PublishService.PublishResult result =
          publishService.publish(
              new PublishService.PublishRequest(
                  catalog, branch, targetRef,
                  expectedTargetHead, expectedSourceHead,
                  idempotencyKey, requestId));
      return Response.ok(Map.of("publishedCommitId", result.publishedCommitId())).build();
    } catch (PublishConflictException e) {
      return Response.status(Response.Status.CONFLICT)
          .entity(Map.of("error", e.getMessage()))
          .build();
    } catch (PublishRejectedException | IllegalArgumentException e) {
      return Response.status(Response.Status.NOT_FOUND)
          .entity(Map.of("error", e.getMessage()))
          .build();
    } catch (LockConflictException e) {
      return Response.status(Response.Status.CONFLICT)
          .entity(Map.of("error", e.getMessage()))
          .build();
    }
  }

  private static Response phaseOnePlaceholder(String operation, String catalog) {
    return Response.status(Response.Status.NOT_IMPLEMENTED)
        .entity(
            Map.of(
                "code", "PHASE_1_PLACEHOLDER",
                "operation", operation,
                "catalog", catalog))
        .build();
  }

  private static Response phaseOnePlaceholder(String operation, String catalog, String branch) {
    return Response.status(Response.Status.NOT_IMPLEMENTED)
        .entity(
            Map.of(
                "code", "PHASE_1_PLACEHOLDER",
                "operation", operation,
                "catalog", catalog,
                "branch", branch))
        .build();
  }
}
