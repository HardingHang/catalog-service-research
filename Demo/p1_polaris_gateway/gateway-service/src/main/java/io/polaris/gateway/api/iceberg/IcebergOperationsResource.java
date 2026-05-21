package io.polaris.gateway.api.iceberg;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;

@Path("/iceberg/v1/{prefix}")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class IcebergOperationsResource {
  @POST
  @Path("/tables/rename")
  public Response renameTable(
      @PathParam("prefix") String prefix, Map<String, Object> request) {
    return phaseOnePlaceholder("renameTable", prefix);
  }

  @POST
  @Path("/transactions/commit")
  public Response commitTransaction(
      @PathParam("prefix") String prefix, Map<String, Object> request) {
    return phaseOnePlaceholder("commitTransaction", prefix);
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
