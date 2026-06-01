#!/usr/bin/env bash
# iceberg_fdw Phase 1 —— docker 内 openGauss 5.0.0 构建 + 实跑验收（已验证）。
# 用法：bash test/docker/run_acceptance.sh   （在 iceberg_fdw/ 目录或仓库根执行）
#
# 依赖：本机 docker；openGauss 源码克隆位于 ../src_ref/opengauss-server
#       （用于补齐 openGauss 安装未随附的内部头文件，版本对齐 v5.0.0）。
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"   # iceberg_fdw/
OG_SRC="${OG_SRC:-$HERE/../src_ref/opengauss-server}"
GH=/usr/local/opengauss
CT=igtest
ENV="export GAUSSHOME=$GH PATH=$GH/bin:\$PATH LD_LIBRARY_PATH=$GH/lib"

echo "== 1) 构建带 g++ 的镜像 =="
docker build -f "$HERE/test/docker/Dockerfile.build" -t iceberg-fdw-build:5.0.0 "$HERE"

echo "== 2) 启动 gaussdb 实例 =="
docker rm -f "$CT" 2>/dev/null || true
docker run -d --name "$CT" -e GS_PASSWORD=Enmo@123 iceberg-fdw-build:5.0.0
until docker logs "$CT" 2>&1 | grep -q "database system is ready to accept connections"; do sleep 4; done

echo "== 3) 补齐安装缺失的内部头（版本对齐 v5.0.0）=="
# openGauss 安装的 include 集不完整（如 storage/file/fio_device_com.h 缺失），
# 用同版本源码 src/include 以 no-clobber 方式补齐，不覆盖已安装/生成的头。
rm -rf /tmp/ogsrc_include && mkdir -p /tmp/ogsrc_include
git -C "$OG_SRC" fetch --depth 1 --filter=blob:none origin v5.0.0
git -C "$OG_SRC" archive FETCH_HEAD src/include | tar -x -C /tmp/ogsrc_include
docker cp /tmp/ogsrc_include/src/include "$CT":/tmp/ogsrc_include
docker exec "$CT" bash -c "cp -rn /tmp/ogsrc_include/* $GH/include/postgresql/server/"

echo "== 4) 拷源码、编译、安装扩展（out-of-tree PGXS）=="
docker exec "$CT" bash -c 'rm -rf /tmp/iceberg_fdw'
docker cp "$HERE" "$CT":/tmp/iceberg_fdw
docker exec "$CT" bash -c 'chown -R opengauss:opengauss /tmp/iceberg_fdw'
docker exec -u opengauss "$CT" bash -c "$ENV; cd /tmp/iceberg_fdw && \
    make -f Makefile.pgxs USE_PGXS=1 PG_CONFIG=$GH/bin/pg_config clean; \
    make -f Makefile.pgxs USE_PGXS=1 PG_CONFIG=$GH/bin/pg_config install"

echo "== 5) 重启 gaussdb 以加载新 .so =="
# openGauss 是单多线程进程：dlopen 的扩展在进程内缓存，重装 .so 后必须重启
# 才会被新会话加载（与 PG 每后端进程模型不同）。
docker restart "$CT" >/dev/null
until docker logs "$CT" 2>&1 | tail -20 | grep -q "database system is ready to accept connections"; do sleep 3; done

echo "== 6) 跑 Phase 1 / Phase 2 验收 =="
docker exec -u opengauss "$CT" bash -c "$ENV; gsql -d postgres -p 5432 -f /tmp/iceberg_fdw/test/routine_skeleton_test.sql"
docker exec -u opengauss "$CT" bash -c "$ENV; gsql -d postgres -p 5432 -f /tmp/iceberg_fdw/test/catalog_resolve_test.sql"
