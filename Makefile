PROGRAMS = ccnxchat
INSTALL_BASE = /usr/local
INSTALL_BASE = $$HOME/ccnx
IFLAGS = -I$(INSTALL_BASE)/include
LFLAGS = -L$(INSTALL_BASE)/lib -lccn -lcrypto -lc # -lsocket

CFLAGS = -g

default: $(PROGRAMS)

ccnxchat: ccnxchat.c lned.c
	$(CC) $(CFLAGS) $(IFLAGS) -o ccnxchat lned.c ccnxchat.c $(LFLAGS)

clean:
	rm -f $(PROGRAMS) *.o
