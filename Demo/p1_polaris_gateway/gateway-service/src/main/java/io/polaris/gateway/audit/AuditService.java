package io.polaris.gateway.audit;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

@ApplicationScoped
public class AuditService {
  private DataSource dataSource;

  public AuditService() {}

  @Inject
  public AuditService(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  public void record(
      String eventId,
      String catalogId,
      String refName,
      String actor,
      String action,
      String commitId,
      String requestId,
      String payloadJson) {
    try (Connection conn = dataSource.getConnection()) {
      DSLContext ctx = DSL.using(conn, SQLDialect.POSTGRES);
      ctx.insertInto(table("audit_events"))
          .columns(
              field("event_id"),
              field("catalog_id"),
              field("ref_name"),
              field("actor"),
              field("action"),
              field("commit_id"),
              field("request_id"),
              field("payload"))
          .values(
              eventId,
              catalogId,
              refName,
              actor,
              action,
              commitId,
              requestId,
              JSONB.valueOf(payloadJson != null ? payloadJson : "{}"))
          .execute();
    } catch (SQLException e) {
      throw new IllegalStateException("Audit insert failed", e);
    }
  }
}
