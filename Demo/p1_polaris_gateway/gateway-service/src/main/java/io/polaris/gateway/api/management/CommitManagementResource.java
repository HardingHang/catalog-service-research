package io.polaris.gateway.api.management;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;

@Path("/api/v1/catalogs/{catalog}")
@Produces(MediaType.APPLICATION_JSON)
public class CommitManagementResource {
  @GET
  @Path("/commits/{commit}")
  public Response getCommit(
      @PathParam("catalog") String catalog, @PathParam("commit") String commit) {
    return Response.status(Response.Status.NOT_IMPLEMENTED)
        .entity(
            Map.of(
                "code", "PHASE_1_PLACEHOLDER",
                "operation", "getCommit",
                "catalog", catalog,
                "commit", commit))
        .build();
  }

  @GET
  @Path("/refs/{ref}/contents")
  public Response listRefContents(
      @PathParam("catalog") String catalog,
      @PathParam("ref") String ref,
      @QueryParam("pageToken") String pageToken) {
    return Response.status(Response.Status.NOT_IMPLEMENTED)
        .entity(
            Map.of(
                "code", "PHASE_1_PLACEHOLDER",
                "operation", "listRefContents",
                "catalog", catalog,
                "ref", ref))
        .build();
  }
}
