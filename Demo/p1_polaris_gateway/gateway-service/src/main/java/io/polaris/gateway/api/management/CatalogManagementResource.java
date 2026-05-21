package io.polaris.gateway.api.management;

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
@Consumes(MediaType.APPLICATION_JSON)
public class CatalogManagementResource {
  @POST
  @Path("/bootstrap")
  public Response bootstrap(@PathParam("catalog") String catalog) {
    return phaseOnePlaceholder("bootstrap", catalog);
  }

  @GET
  @Path("/drift-report")
  public Response driftReport(@PathParam("catalog") String catalog) {
    return phaseOnePlaceholder("driftReport", catalog);
  }

  @POST
  @Path("/gc/dry-run")
  public Response gcDryRun(@PathParam("catalog") String catalog, Map<String, Object> request) {
    return phaseOnePlaceholder("gcDryRun", catalog);
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
}
