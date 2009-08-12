# Makefile for ccn/Documentation/technical

DERIVED_HTML = DigestComponent.html InterestMessage.html NameConventions.html Staleness.html Registration.html URI.html

default:

all documentation: $(DERIVED_HTML)

clean-documentation: _always
	rm -f $(DERIVED_HTML)

DigestComponent.html: DigestComponent.txt
	asciidoc DigestComponent.txt

InterestMessage.html: InterestMessage.txt
	asciidoc InterestMessage.txt

NameConventions.html: NameConventions.txt
	asciidoc NameConventions.txt

Registration.html: Registration.txt
	asciidoc Registration.txt

Staleness.html: Staleness.txt
	asciidoc Staleness.txt

URI.html: URI.txt
	asciidoc URI.txt

default all clean depend test check shared documentation testinstall install uninstall: _always

_always:

.PHONY: _always
