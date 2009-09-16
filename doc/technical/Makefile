# Makefile for ccn/doc/technical

# The order here determines the order in the generated index.
DERIVED_HTML =				\
	BasicIdea.html			\
	CCNxProtocol.html		\
	ccnb.html			\
	Name.html			\
	URI.html			\
	ContentObject.html		\
	InterestMessage.html		\
	NameConventions.html		\
	DigestComponent.html		\
	Registration.html		\
	Staleness.html			\
	dtd.html			\
	xsd.html			\
	DTAG.html			\

DERIVED_INDEX = ../index.html index.html index.txt
DEBRIS = .tagname.csv
SRC_HTML = # any checked-in html files should go here
INDEXED_HTML = $(DERIVED_HTML) $(SRC_HTML)

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

DTAG.html: DTAG.txt ../../schema/tagname.csvdict
	sed -e 's/$$/,,,,/g' ../../schema/tagname.csvdict | \
					cut -d , -f 1-3   > .tagname.csv
	asciidoc --unsafe DTAG.txt
	rm -f .tagname.csv

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

BasicIdea.html: BasicIdea.txt
	asciidoc BasicIdea.txt

ccnb.html: ccnb.txt
	asciidoc ccnb.txt

CCNxProtocol.html: CCNxProtocol.txt
	asciidoc CCNxProtocol.txt

../index.html: ../index.txt ../../README ../../LICENSE
	asciidoc --unsafe ../index.txt

index.html: index.txt
	asciidoc index.txt

index.txt: $(INDEXED_HTML) .genindex.sh
	rm -f index.html
	sh .genindex.sh $(INDEXED_HTML) > index.txt

default all clean depend test check shared documentation testinstall install uninstall: _always

_always:

.PHONY: _always
