# Makefile for ccn/doc/technical

DERIVED_HTML = DigestComponent.html InterestMessage.html NameConventions.html Staleness.html Registration.html URI.html
DERIVED_INDEX = ../index.html index.html index.txt
SRC_HTML = InterestMessage-changes.html InterestMessage-old.html

default:

all documentation: $(DERIVED_HTML) $(DERIVED_INDEX)

clean-documentation: _always
	rm -f $(DERIVED_HTML) $(DERIVED_INDEX)

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

../index.html: ../index.txt
	asciidoc ../index.txt

index.html: index.txt
	asciidoc index.txt

index.txt: $(DERIVED_HTML) .genindex.sh $(SRC_HTML)
	rm -f index.html
	sh .genindex.sh > index.txt

default all clean depend test check shared documentation testinstall install uninstall: _always

_always:

.PHONY: _always
