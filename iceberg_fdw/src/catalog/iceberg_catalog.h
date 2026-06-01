/* -------------------------------------------------------------------------
 *
 * catalog.h
 *      Catalog 接入抽象（数据面↔控制面边界，doc 3 §7）。
 *
 * routine 上半层只需要一个**只读解析入口** IcebergCatalogResolveTable：
 * 外表 OPTIONS(namespace.table) → metadata_location + 当前 schema 摘要。
 * 控制面（namespace/table 写操作）在同一抽象上挂写侧，本版不实现。
 *
 * -------------------------------------------------------------------------
 */
#ifndef ICEBERG_CATALOG_H
#define ICEBERG_CATALOG_H

#include "postgres.h"

typedef enum { ICEBERG_CATALOG_PG_NATIVE, ICEBERG_CATALOG_REST } IcebergCatalogKind;

typedef struct IcebergCatalog {
    IcebergCatalogKind kind;
    const char* catalogSchema; /* pg_native: iceberg_catalog 系统表所在 schema */
    /* rest 后端的 host/prefix/token 留接入点，本版不实现 */
} IcebergCatalog;

/* 解析结果：当前生效的 metadata_location + schema 摘要（doc 3 §7） */
typedef struct IcebergResolved {
    char* metadataLocation; /* 权威 metadata.json 路径，非空 */
    int currentSchemaId;    /* 当前 schema 版本；NULL 时为 -1 */
    char* tableUuid;        /* 表稳定身份（text 形式） */
} IcebergResolved;

/*
 * 从外表 OID 取身份与后端配置：
 *   table OPTIONS → *ns / *table_name（必需）
 *   server OPTIONS → cat->kind(catalog_kind) / cat->catalogSchema(catalog_schema)
 * 缺必需 OPTION 报错。
 */
extern void IcebergGetTableIdentity(Oid foreigntableid, char** ns, char** table_name, IcebergCatalog* cat);

/*
 * 数据面只读解析入口（doc 3 §7）。
 *   pg_native：SPI 查 <catalogSchema>.tables 取 metadata_location 等。
 *   表不存在 → ereport(ERROR) 明确报错（NoSuchTable），不返回 NULL。
 *   rest：本版未实现，报错。
 */
extern IcebergResolved* IcebergCatalogResolveTable(IcebergCatalog* cat, const char* ns, const char* table_name);

#endif /* ICEBERG_CATALOG_H */
