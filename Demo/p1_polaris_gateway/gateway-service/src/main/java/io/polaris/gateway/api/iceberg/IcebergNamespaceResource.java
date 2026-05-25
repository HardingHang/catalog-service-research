package io.polaris.gateway.api.iceberg;

import io.polaris.gateway.ref.RefResolver;
import io.polaris.gateway.store.CommitRepository;
import io.polaris.gateway.store.IndexRepository;
import io.polaris.gateway.store.RefRepository;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;

@Path("/iceberg/v1/{prefix}/namespaces")
@Produces(MediaType.APPLICATION_JSON)
public class IcebergNamespaceResource {

  @Inject RefResolver refResolver;
  @Inject RefRepository refRepository;
  @Inject CommitRepository commitRepository;
  @Inject IndexRepository indexRepository;

  @GET
  public Response listNamespaces(
      @PathParam("prefix") String catalog,
      @HeaderParam("X-Catalog-Ref") String refHeader) {
    String ref = refResolver.resolve(refHeader);
    var refRecord = refRepository.getRef(catalog, ref);
    if (refRecord.isEmpty()) {
      return Response.status(Response.Status.NOT_FOUND)
          .entity(Map.of("error", "ref not found: " + ref))
          .build();
    }
    if (refRecord.get().headCommitId() == null) {
      return Response.ok(Map.of("namespaces", List.of())).build();
    }
    String indexId = resolveIndexId(refRecord.get().headCommitId());
    if (indexId == null) {
      return Response.ok(Map.of("namespaces", List.of())).build();
    }
    List<List<String>> result =
        indexRepository.listNamespaces(indexId).stream()
            .map(ns -> List.of(ns.split("\\.")))
            .toList();
    return Response.ok(Map.of("namespaces", result)).build();
  }

  @GET
  @Path("/{namespace}")
  public Response getNamespace(
      @PathParam("prefix") String catalog,
      @PathParam("namespace") String namespace,
      @HeaderParam("X-Catalog-Ref") String refHeader) {
    String ref = refResolver.resolve(refHeader);
    var refRecord = refRepository.getRef(catalog, ref);
    if (refRecord.isEmpty()) {
      return Response.status(Response.Status.NOT_FOUND)
          .entity(Map.of("error", "ref not found: " + ref))
          .build();
    }
    String indexId = resolveIndexId(refRecord.get().headCommitId());
    if (indexId == null || !indexRepository.listNamespaces(indexId).contains(namespace)) {
      return Response.status(Response.Status.NOT_FOUND)
          .entity(Map.of("error", "namespace not found: " + namespace))
          .build();
    }
    return Response.ok(
            Map.of(
                "namespace", List.of(namespace.split("\\.")),
                "properties", Map.of()))
        .build();
  }

  private String resolveIndexId(String headCommitId) {
    if (headCommitId == null) return null;
    return commitRepository.findById(headCommitId)
        .map(CommitRepository.CommitRecord::indexId)
        .orElse(null);
  }
}
