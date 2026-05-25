package io.polaris.gateway.api.management;

import io.polaris.gateway.polaris.BootstrapService;
import io.polaris.gateway.polaris.BootstrapService.AlreadyBootstrappedException;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;

@Path("/api/v1/catalogs/{catalog}")
@Produces(MediaType.APPLICATION_JSON)
public class CatalogManagementResource {

  @Inject BootstrapService bootstrapService;

  @POST
  @Path("/bootstrap")
  public Response bootstrap(@PathParam("catalog") String catalog) {
    try {
      String commitId = bootstrapService.bootstrap(catalog);
      return Response.ok(Map.of("commitId", commitId)).build();
    } catch (AlreadyBootstrappedException e) {
      return Response.status(Response.Status.CONFLICT)
          .entity(Map.of("error", e.getMessage()))
          .build();
    }
  }

  @GET
  @Path("/drift-report")
  public Response driftReport(@PathParam("catalog") String catalog) {
    return Response.status(Response.Status.NOT_IMPLEMENTED)
        .entity(
            Map.of(
                "code", "PHASE_1_PLACEHOLDER", "operation", "driftReport", "catalog", catalog))
        .build();
  }

  @POST
  @Path("/gc/dry-run")
  @Consumes(MediaType.APPLICATION_JSON)
  public Response gcDryRun(@PathParam("catalog") String catalog, Map<String, Object> request) {
    return Response.status(Response.Status.NOT_IMPLEMENTED)
        .entity(
            Map.of("code", "PHASE_1_PLACEHOLDER", "operation", "gcDryRun", "catalog", catalog))
        .build();
  }
}
