# very picky gcc flags
# To use, do something like ln -s pickygcc.mk Darwin-9.3.0.mk
CWARNFLAGS = -Wall -Wswitch-enum -Wno-format-y2k -Wno-unused-parameter -Wstrict-prototypes -Wmissing-prototypes -Wpointer-arith -Wreturn-type -Wcast-qual -Wwrite-strings -Wswitch -Wshadow -Wcast-align -Wunused-parameter -Wchar-subscripts -Winline -Wnested-externs -Wredundant-decls -Wuninitialized -Wformat=2 -Wno-format-extra-args -Wno-unknown-pragmas
COPT = -O

