PROGRAMS = ccnxchat

IFLAGS = -I$(INSTALL_BASE)/include $(OPENSSL_CFLAGS)
LFLAGS = -L$(INSTALL_BASE)/lib $(OPENSSL_LIBS) -lccn -lcrypto -lc # -lsocket

CFLAGS = -g

default: $(PROGRAMS)

ccnxchat: ccnxchat.c lned.c
	$(CC) $(CFLAGS) $(IFLAGS) -o ccnxchat lned.c ccnxchat.c $(LFLAGS)

clean:
	rm -f $(PROGRAMS) *.o

include ../ccnx/csrc/conf.mk
