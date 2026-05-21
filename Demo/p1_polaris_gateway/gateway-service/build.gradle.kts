plugins {
  java
  id("io.quarkus") version "3.29.2"
}

group = "io.polaris.gateway"
version = "0.1.0-SNAPSHOT"

java {
  sourceCompatibility = JavaVersion.VERSION_21
  targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
  implementation(platform("io.quarkus.platform:quarkus-bom:3.29.2"))
  implementation(platform("org.apache.iceberg:iceberg-bom:1.10.1"))

  implementation("io.quarkus:quarkus-arc")
  implementation("io.quarkus:quarkus-rest-jackson")
  implementation("io.quarkus:quarkus-smallrye-openapi")
  implementation("io.quarkus:quarkus-smallrye-health")
  implementation("io.quarkus:quarkus-jdbc-postgresql")
  implementation("io.quarkus:quarkus-flyway")
  implementation("io.quarkus:quarkus-micrometer")
  implementation("io.quarkus:quarkus-micrometer-registry-prometheus")
  implementation("io.quarkus:quarkus-opentelemetry")

  implementation("org.apache.iceberg:iceberg-api")
  implementation("org.apache.iceberg:iceberg-core")
  implementation("org.apache.iceberg:iceberg-aws")
  implementation("org.jooq:jooq:3.19.24")

  testImplementation("io.quarkus:quarkus-junit5")
  testImplementation("org.flywaydb:flyway-core")
  testImplementation("org.flywaydb:flyway-database-postgresql")
  testImplementation("org.postgresql:postgresql")
  testImplementation(enforcedPlatform("org.testcontainers:testcontainers-bom:1.20.6"))
  testImplementation("org.testcontainers:junit-jupiter")
  testImplementation("org.testcontainers:postgresql")
}

tasks.withType<Test> {
  useJUnitPlatform()
  if (System.getProperty("os.name").lowercase().contains("windows")) {
    environment("DOCKER_HOST", "npipe:////./pipe/dockerDesktopLinuxEngine")
    environment("DOCKER_API_VERSION", "1.54")
  }
}
