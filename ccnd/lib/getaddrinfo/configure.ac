AC_INIT
AC_CONFIG_SRCDIR(getaddrinfo.c)
AC_PREREQ(2.59)

AC_PROG_MAKE_SET
AC_PROG_CC
AC_C_CONST
AC_HEADER_STDC
AC_CHECK_HEADERS(string.h memory.h stdlib.h)
AC_CHECK_FUNCS(memcpy)
AC_REPLACE_FUNCS(memset)
AC_TYPE_SOCKLEN_T
AC_TYPE_SA_FAMILY_T
AC_TYPE_IN_PORT_T
AC_DECL_H_ERRNO
AC_CHECK_FUNCS(getaddrinfo getnameinfo)

AC_STRUCT_IN6_ADDR
if test "$ac_cv_struct_in6_addr" = no; then
    AC_LIBOBJ(dummyin6)
fi

AC_CHECK_LIB(resolv, res_query)
if test "$ac_cv_lib_resolv_res_query" = no ; then
    AC_CHECK_LIB(bind, res_query)
fi
AC_CHECK_LIB(nsl, gethostname)
AC_CHECK_LIB(socket, socket)

AC_CONFIG_FILES(Makefile)
AC_OUTPUT
