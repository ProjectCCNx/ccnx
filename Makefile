PROGRAMS = ccnxchat
INSTALL_BASE = /usr/local
INSTALL_BASE = $$HOME/ccnx
IFLAGS = -I$(INSTALL_BASE)/include
LFLAGS = -L$(INSTALL_BASE)/lib -lccn -lcrypto

CFLAGS = -g

default: $(PROGRAMS)

ccnxchat: ccnxchat.c
	$(CC) $(CFLAGS) $(IFLAGS) -o ccnxchat ccnxchat.c $(LFLAGS)

clean:
	rm -f $(PROGRAMS) *.o
