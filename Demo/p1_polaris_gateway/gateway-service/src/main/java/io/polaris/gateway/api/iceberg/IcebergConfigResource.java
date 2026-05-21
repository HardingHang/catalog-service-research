package io.polaris.gateway.api.iceberg;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;

@Path("/iceberg/v1/config")
@Produces(MediaType.APPLICATION_JSON)
public class IcebergConfigResource {
  @GET
  public Response config() {
    return Response.ok(
            Map.of(
                "defaults", Map.of("p1.ref.default", "main"),
                "overrides", Map.of("p1.gateway.phase", "phase-1")))
        .build();
  }
}
