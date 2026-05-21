package io.polaris.gateway.api.management;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;

@Path("/api/v1/catalogs/{catalog}/branches")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class BranchManagementResource {
  @POST
  public Response createBranch(@PathParam("catalog") String catalog, Map<String, Object> request) {
    return phaseOnePlaceholder("createBranch", catalog);
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
      Map<String, Object> request) {
    return phaseOnePlaceholder("diffBranch", catalog, branch);
  }

  @POST
  @Path("/{branch}/publish-plan")
  public Response publishPlan(
      @PathParam("catalog") String catalog,
      @PathParam("branch") String branch,
      Map<String, Object> request) {
    return phaseOnePlaceholder("publishPlan", catalog, branch);
  }

  @POST
  @Path("/{branch}/publish")
  public Response publish(
      @PathParam("catalog") String catalog,
      @PathParam("branch") String branch,
      Map<String, Object> request) {
    return phaseOnePlaceholder("publish", catalog, branch);
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
