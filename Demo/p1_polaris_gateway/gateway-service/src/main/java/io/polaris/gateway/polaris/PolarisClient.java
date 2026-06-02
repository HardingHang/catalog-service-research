package io.polaris.gateway.polaris;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class PolarisClient {
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  @Inject ObjectMapper objectMapper;

  @ConfigProperty(name = "polaris.catalog.url", defaultValue = "http://localhost:8281")
  String polarisUrl;

  @ConfigProperty(name = "polaris.catalog.client-id", defaultValue = "root")
  String clientId;

  @ConfigProperty(name = "polaris.catalog.client-secret", defaultValue = "s3cr3t")
  String clientSecret;

  @SuppressWarnings("unchecked")
  public String authenticate() {
    String body =
        "grant_type=client_credentials"
            + "&client_id="
            + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
            + "&client_secret="
            + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8)
            + "&scope=PRINCIPAL_ROLE%3AALL";
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(polarisUrl + "/api/catalog/v1/oauth/tokens"))
              .header("Content-Type", "application/x-www-form-urlencoded")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();
      HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new PolarisCallException(
            "Polaris auth failed: " + response.statusCode() + " " + response.body());
      }
      Map<String, Object> json = objectMapper.readValue(response.body(), Map.class);
      return (String) json.get("access_token");
    } catch (IOException | InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new PolarisCallException("Polaris auth error", e);
    }
  }

  @SuppressWarnings("unchecked")
  public List<String> listNamespaces(String token, String catalog) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(polarisUrl + "/api/catalog/v1/" + catalog + "/namespaces"))
              .header("Authorization", "Bearer " + token)
              .GET()
              .build();
      HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new PolarisCallException("Polaris listNamespaces failed: " + response.statusCode());
      }
      Map<String, Object> json = objectMapper.readValue(response.body(), Map.class);
      List<List<String>> namespaces = (List<List<String>>) json.get("namespaces");
      if (namespaces == null) return List.of();
      return namespaces.stream().map(parts -> String.join(".", parts)).toList();
    } catch (IOException | InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new PolarisCallException("Polaris listNamespaces error", e);
    }
  }

  @SuppressWarnings("unchecked")
  public List<String> listTables(String token, String catalog, String namespace) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(
                  URI.create(
                      polarisUrl
                          + "/api/catalog/v1/"
                          + catalog
                          + "/namespaces/"
                          + namespace
                          + "/tables"))
              .header("Authorization", "Bearer " + token)
              .GET()
              .build();
      HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new PolarisCallException("Polaris listTables failed: " + response.statusCode());
      }
      Map<String, Object> json = objectMapper.readValue(response.body(), Map.class);
      List<Map<String, Object>> identifiers = (List<Map<String, Object>>) json.get("identifiers");
      if (identifiers == null) return List.of();
      return identifiers.stream().map(id -> (String) id.get("name")).toList();
    } catch (IOException | InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new PolarisCallException("Polaris listTables error", e);
    }
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> loadTable(
      String token, String catalog, String namespace, String table) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(
                  URI.create(
                      polarisUrl
                          + "/api/catalog/v1/"
                          + catalog
                          + "/namespaces/"
                          + namespace
                          + "/tables/"
                          + table))
              .header("Authorization", "Bearer " + token)
              .GET()
              .build();
      HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new PolarisCallException("Polaris loadTable failed: " + response.statusCode());
      }
      return objectMapper.readValue(response.body(), Map.class);
    } catch (IOException | InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new PolarisCallException("Polaris loadTable error", e);
    }
  }

  @SuppressWarnings("unchecked")
  public void materializeTable(
      String token,
      String catalogId,
      String contentKey,
      String metadataLocation,
      String metadataSummaryJson) {
    int dot = contentKey.indexOf('.');
    if (dot < 0) return;
    String namespace = contentKey.substring(0, dot);
    String table = contentKey.substring(dot + 1);

    Map<String, Object> metadata;
    try {
      metadata = objectMapper.readValue(metadataSummaryJson, Map.class);
    } catch (IOException e) {
      throw new PolarisCallException("Failed to parse metadata JSON for " + contentKey, e);
    }

    Object rawSnapshotId = metadata.get("current-snapshot-id");
    if (rawSnapshotId == null) return;
    long targetSnapshotId = ((Number) rawSnapshotId).longValue();
    if (targetSnapshotId == -1L) return;

    // Idempotency: skip if Polaris already reflects this snapshot
    String polarisSnapshotId = getTableSnapshotId(token, catalogId, contentKey);
    if (String.valueOf(targetSnapshotId).equals(polarisSnapshotId)) return;

    List<Map<String, Object>> snapshots =
        (List<Map<String, Object>>) metadata.getOrDefault("snapshots", List.of());
    Map<String, Object> targetSnapshot = null;
    for (Map<String, Object> snap : snapshots) {
      Object id = snap.get("snapshot-id");
      if (id != null && ((Number) id).longValue() == targetSnapshotId) {
        targetSnapshot = snap;
        break;
      }
    }
    if (targetSnapshot == null) {
      throw new PolarisCallException(
          "Snapshot " + targetSnapshotId + " not found in metadata for " + contentKey);
    }

    List<Map<String, Object>> updates =
        List.of(
            Map.of("action", "add-snapshot", "snapshot", targetSnapshot),
            Map.of(
                "action", "set-snapshot-ref",
                "ref-name", "main",
                "snapshot-id", targetSnapshotId,
                "type", "branch"));
    Map<String, Object> requestBody = Map.of("requirements", List.of(), "updates", updates);

    try {
      String bodyJson = objectMapper.writeValueAsString(requestBody);
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(
                  URI.create(
                      polarisUrl
                          + "/api/catalog/v1/"
                          + catalogId
                          + "/namespaces/"
                          + namespace
                          + "/tables/"
                          + table))
              .header("Authorization", "Bearer " + token)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
              .build();
      HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new PolarisCallException(
            "Polaris materializeTable failed: " + response.statusCode() + " " + response.body());
      }
    } catch (IOException | InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new PolarisCallException("Polaris materializeTable error", e);
    }
  }

  @SuppressWarnings("unchecked")
  public String getTableSnapshotId(String token, String catalogId, String contentKey) {
    int dot = contentKey.indexOf('.');
    if (dot < 0) return null;
    String ns = contentKey.substring(0, dot);
    String table = contentKey.substring(dot + 1);
    try {
      Map<String, Object> result = loadTable(token, catalogId, ns, table);
      Map<String, Object> metadata = (Map<String, Object>) result.getOrDefault("metadata", Map.of());
      Object id = metadata.get("current-snapshot-id");
      if (id == null) return null;
      long val = ((Number) id).longValue();
      return val == -1L ? null : String.valueOf(val);
    } catch (PolarisCallException e) {
      return null;
    }
  }

  public static class PolarisCallException extends RuntimeException {
    public PolarisCallException(String message) {
      super(message);
    }

    public PolarisCallException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
