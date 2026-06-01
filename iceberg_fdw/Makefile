#-------------------------------------------------------------------------
#
# Makefile for iceberg_fdw（openGauss in-tree contrib build）
#
# 放置方式：把本目录拷为 openGauss 源码树下的 contrib/iceberg_fdw，
# 然后 `make -C contrib/iceberg_fdw install`。openGauss contrib 扩展走
# 源码树内构建（include $(top_builddir)/src/Makefile.global），非独立 PGXS。
# 模板来源：contrib/file_fdw/Makefile。
#
#-------------------------------------------------------------------------

MODULE_big = iceberg_fdw

EXTENSION = iceberg_fdw

OBJS = src/iceberg_fdw.o \
       src/plan/plan_callbacks.o \
       src/plan/fdw_private.o \
       src/exec/scan_callbacks.o \
       src/exec/vec_scan_callbacks.o \
       src/catalog/catalog_resolve.o \
       src/ddl/validate_table_def.o

DATA = iceberg_fdw.control iceberg_fdw--1.0.sql

subdir = contrib/iceberg_fdw
top_builddir = ../..
include $(top_builddir)/src/Makefile.global

# 内部头文件（src/iceberg_fdw.h、src/exec/scan_state.h）查找路径
override CPPFLAGS := -I$(srcdir)/src $(CPPFLAGS)

# Shared library stuff
include $(top_srcdir)/src/gausskernel/common.mk

install:install-data

.PHONY: install-data
install-data: installdirs
	$(INSTALL_DATA) $(addprefix $(srcdir)/, $(DATA)) '$(DESTDIR)$(datadir)/extension/'

installdirs:
	$(MKDIR_P) '$(DESTDIR)$(datadir)/extension'

uninstall:uninstall-data

.PHONY: uninstall-data
uninstall-data:
	rm -f $(addprefix '$(DESTDIR)$(datadir)/extension'/, $(notdir $(DATA)))

clean:
	rm -f $(OBJS)
