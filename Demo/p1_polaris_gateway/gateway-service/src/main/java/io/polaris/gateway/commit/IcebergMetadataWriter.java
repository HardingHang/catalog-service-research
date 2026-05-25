package io.polaris.gateway.commit;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.iceberg.aws.s3.S3FileIO;
import org.apache.iceberg.io.OutputFile;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class IcebergMetadataWriter {

  @ConfigProperty(name = "gateway.s3.endpoint", defaultValue = "")
  String s3Endpoint;

  @ConfigProperty(name = "gateway.s3.access-key-id", defaultValue = "")
  String accessKeyId;

  @ConfigProperty(name = "gateway.s3.secret-access-key", defaultValue = "")
  String secretAccessKey;

  @ConfigProperty(name = "gateway.s3.region", defaultValue = "us-east-1")
  String region;

  /**
   * Writes {@code metadataJson} to object storage under {@code tableLocation}/metadata/ and returns
   * the new file path.
   */
  public String write(String metadataJson, String tableLocation) {
    String base = tableLocation.endsWith("/") ? tableLocation : tableLocation + "/";
    String newLocation = base + "metadata/" + UUID.randomUUID() + ".metadata.json";

    Map<String, String> props = new HashMap<>();
    props.put("s3.endpoint", s3Endpoint);
    props.put("s3.access-key-id", accessKeyId);
    props.put("s3.secret-access-key", secretAccessKey);
    props.put("s3.path-style-access", "true");
    props.put("client.region", region);

    try (S3FileIO fileIO = new S3FileIO()) {
      fileIO.initialize(props);
      OutputFile out = fileIO.newOutputFile(newLocation);
      try (OutputStream os = out.createOrOverwrite()) {
        os.write(metadataJson.getBytes(StandardCharsets.UTF_8));
      }
    } catch (IOException e) {
      throw new MetadataWriteException("Failed to write metadata to " + newLocation, e);
    }
    return newLocation;
  }

  public static class MetadataWriteException extends RuntimeException {
    public MetadataWriteException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
