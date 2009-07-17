# Makefile for ccn/Documentation/technical

DERIVED_HTML = DigestComponent.html InterestMessage.html NameConventions.html Staleness.html URI.html

default all: $(DERIVED_HTML)

clean:
	rm -f $(DERIVED_HTML)

DigestComponent.html: DigestComponent.txt
	asciidoc DigestComponent.txt

InterestMessage.html: InterestMessage.txt
	asciidoc InterestMessage.txt

NameConventions.html: NameConventions.txt
	asciidoc NameConventions.txt

Staleness.html: Staleness.txt
	asciidoc Staleness.txt

URI.html: URI.txt
	asciidoc URI.txt

default all clean depend test check shared install uninstall: _always

_always:

.PHONY: _always
