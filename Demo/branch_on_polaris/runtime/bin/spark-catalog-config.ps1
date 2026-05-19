function Get-P0SparkCatalogArgs {
  <#
    Shared Spark/Iceberg REST Catalog configuration for this demo.

    The Spark catalog alias is `polaris`; the Polaris warehouse/catalog name is
    `quickstart_catalog`. Keeping these args in one place prevents the runtime
    SQL runner and the state collector from drifting apart.
  #>
  @(
    "--packages", "org.apache.iceberg:iceberg-spark-runtime-3.5_2.12:1.10.1,org.apache.iceberg:iceberg-aws-bundle:1.10.1",
    "--conf", "spark.sql.extensions=org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions",
    "--conf", "spark.sql.catalog.polaris=org.apache.iceberg.spark.SparkCatalog",
    "--conf", "spark.sql.catalog.polaris.type=rest",
    "--conf", "spark.sql.catalog.polaris.warehouse=quickstart_catalog",
    "--conf", "spark.sql.catalog.polaris.uri=http://polaris:8181/api/catalog",
    "--conf", "spark.sql.catalog.polaris.credential=root:s3cr3t",
    "--conf", "spark.sql.catalog.polaris.scope=PRINCIPAL_ROLE:ALL",
    "--conf", "spark.sql.catalog.polaris.s3.endpoint=http://rustfs:9000",
    "--conf", "spark.sql.catalog.polaris.s3.path-style-access=true",
    "--conf", "spark.sql.catalog.polaris.s3.access-key-id=polaris_root",
    "--conf", "spark.sql.catalog.polaris.s3.secret-access-key=polaris_pass",
    "--conf", "spark.sql.catalog.polaris.client.region=us-west-2",
    "--conf", "spark.sql.defaultCatalog=polaris",
    "--conf", "spark.sql.catalogImplementation=in-memory",
    "--conf", "spark.driver.extraJavaOptions=-Divy.cache.dir=/tmp -Divy.home=/tmp"
  )
}
