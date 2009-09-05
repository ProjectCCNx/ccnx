# makefile for csrc/util directory

SCRIPTSRC = ccndstart.sh shebang
PROGRAMS = $(INSTALLED_PROGRAMS)
INSTALLED_PROGRAMS = ccndstart

default all: $(SCRIPTSRC) $(PROGRAMS)

ccndstart: ccndstart.sh
	./shebang $(SH) ccndstart.sh > ccndstart
	chmod +x ccndstart

clean:
	rm -f $(PROGRAMS)

test:
	@echo "Sorry, no libexec unit tests at this time"
