# iceberg_fdw CMake（对齐 openGauss contrib/file_fdw/CMakeLists.txt 形态）
# 放置方式：拷为 openGauss 源码树 contrib/iceberg_fdw 后，由顶层 CMake 纳入。

AUX_SOURCE_DIRECTORY(${CMAKE_CURRENT_SOURCE_DIR}/src TGT_iceberg_fdw_SRC)
AUX_SOURCE_DIRECTORY(${CMAKE_CURRENT_SOURCE_DIR}/src/plan TGT_iceberg_fdw_SRC)
AUX_SOURCE_DIRECTORY(${CMAKE_CURRENT_SOURCE_DIR}/src/exec TGT_iceberg_fdw_SRC)
AUX_SOURCE_DIRECTORY(${CMAKE_CURRENT_SOURCE_DIR}/src/ddl TGT_iceberg_fdw_SRC)

set(TGT_iceberg_fdw_INC
    ${CMAKE_CURRENT_SOURCE_DIR}/src
    ${PROJECT_OPENGS_DIR}/contrib
)

set(iceberg_fdw_DEF_OPTIONS ${MACRO_OPTIONS})
set(iceberg_fdw_COMPILE_OPTIONS ${OPTIMIZE_OPTIONS} ${OS_OPTIONS} ${PROTECT_OPTIONS} ${WARNING_OPTIONS} ${BIN_SECURE_OPTIONS} ${CHECK_OPTIONS})
set(iceberg_fdw_LINK_OPTIONS ${BIN_LINK_OPTIONS})
add_static_objtarget(contrib_iceberg_fdw TGT_iceberg_fdw_SRC TGT_iceberg_fdw_INC "${iceberg_fdw_DEF_OPTIONS}" "${iceberg_fdw_COMPILE_OPTIONS}" "${iceberg_fdw_LINK_OPTIONS}")

install(FILES ${CMAKE_CURRENT_SOURCE_DIR}/iceberg_fdw--1.0.sql
    DESTINATION share/postgresql/extension/
)
install(FILES ${CMAKE_CURRENT_SOURCE_DIR}/iceberg_fdw.control
    DESTINATION share/postgresql/extension/
)
