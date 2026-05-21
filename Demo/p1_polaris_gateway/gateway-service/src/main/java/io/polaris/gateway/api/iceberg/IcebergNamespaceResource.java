package io.polaris.gateway.api.iceberg;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;

@Path("/iceberg/v1/{prefix}/namespaces")
@Produces(MediaType.APPLICATION_JSON)
public class IcebergNamespaceResource {
  @GET
  public Response listNamespaces(@PathParam("prefix") String prefix) {
    return phaseOnePlaceholder("listNamespaces", prefix, null);
  }

  @GET
  @Path("/{namespace}")
  public Response getNamespace(
      @PathParam("prefix") String prefix, @PathParam("namespace") String namespace) {
    return phaseOnePlaceholder("getNamespace", prefix, namespace);
  }

  private static Response phaseOnePlaceholder(String operation, String prefix, String namespace) {
    return Response.status(Response.Status.NOT_IMPLEMENTED)
        .entity(
            Map.of(
                "code", "PHASE_1_PLACEHOLDER",
                "operation", operation,
                "prefix", prefix,
                "namespace", namespace == null ? "" : namespace))
        .build();
  }
}
