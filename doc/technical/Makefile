# Makefile for ccn/doc/technical

DERIVED_HTML = DigestComponent.html InterestMessage.html NameConventions.html Staleness.html Registration.html URI.html Name.html ContentObject.html DTAG.html dtd.html xsd.html

DERIVED_INDEX = ../index.html index.html index.txt
DEBRIS = .tagname.csv
SRC_HTML = # any checked-in html files should go here

default:
	: To build the documentation: $(MAKE) documentation

all documentation: $(DERIVED_HTML) $(DERIVED_INDEX)

clean-documentation: _always
	rm -f $(DERIVED_HTML) $(DERIVED_INDEX) $(DEBRIS)

clean:
	: To clean the documentation: $(MAKE) clean-documentation 
	rm -f $(DEBRIS)

DigestComponent.html: DigestComponent.txt
	asciidoc DigestComponent.txt

DTAG.html: DTAG.txt .tagname.csv
	asciidoc --unsafe DTAG.txt

.tagname.csv: ../../schema/tagname.csvdict
	sed -e 's/$$/,,,,/g' ../../schema/tagname.csvdict | cut -d , -f 1-3 > .tagname.csv

dtd.html: dtd.txt ../../schema/ccn.dtd
	asciidoc --unsafe dtd.txt

xsd.html: xsd.txt ../../schema/ccn.xsd
	asciidoc --unsafe xsd.txt

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

ContentObject.html: ContentObject.txt
	asciidoc ContentObject.txt

Name.html: Name.txt
	asciidoc Name.txt

../index.html: ../index.txt ../../README ../../LICENSE
	asciidoc --unsafe ../index.txt

index.html: index.txt
	asciidoc index.txt

index.txt: $(DERIVED_HTML) .genindex.sh $(SRC_HTML)
	rm -f index.html
	sh .genindex.sh > index.txt

default all clean depend test check shared documentation testinstall install uninstall: _always

_always:

.PHONY: _always
