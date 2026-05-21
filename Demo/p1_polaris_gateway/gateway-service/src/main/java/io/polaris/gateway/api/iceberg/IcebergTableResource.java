package io.polaris.gateway.api.iceberg;

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

@Path("/iceberg/v1/{prefix}/namespaces/{namespace}/tables")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class IcebergTableResource {
  @GET
  public Response listTables(
      @PathParam("prefix") String prefix, @PathParam("namespace") String namespace) {
    return phaseOnePlaceholder("listTables", prefix, namespace, null);
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
      @PathParam("prefix") String prefix,
      @PathParam("namespace") String namespace,
      @PathParam("table") String table) {
    return phaseOnePlaceholder("loadTable", prefix, namespace, table);
  }

  @POST
  @Path("/{table}")
  public Response commitTable(
      @PathParam("prefix") String prefix,
      @PathParam("namespace") String namespace,
      @PathParam("table") String table,
      Map<String, Object> request) {
    return phaseOnePlaceholder("commitTable", prefix, namespace, table);
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
