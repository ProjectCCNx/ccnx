# Makefile for ccn/Documentation/technical

DERIVED_HTML = NameConventions.html InterestMessage.html Staleness.html

default all: $(DERIVED_HTML)

clean:
	rm -f $(DERIVED_HTML)

InterestMessage.html: InterestMessage.txt
	asciidoc InterestMessage.txt

NameConventions.html: NameConventions.txt
	asciidoc NameConventions.txt

Staleness.html: Staleness.txt
	asciidoc Staleness.txt

default all clean depend test check shared install uninstall: _always

_always:

.PHONY: _always
