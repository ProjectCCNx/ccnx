# $Id$
W3REFS=XMLSchema.dtd XMLSchema.xsd datatypes.dtd xml.xsd

default all test: check

$(W3REFS):
	type gzcat>>/dev/null && { gzcat w3downloads.tgz | tar xf -; } || tar xfz w3downloads.tgz

w3downloads.tgz: always
	tar cvfz w3downloads.tgz $(W3REFS)

ccn.xsd:
	ln -s ../ccn.xsd

ccn.dtd:
	ln -s ../ccn.dtd

clean:
	rm -f $(W3REFS)
	test -L ccn.xsd && rm ccn.xsd || :
	test -L ccn.dtd && rm ccn.dtd || :

check: ccn.xsd ccn.dtd $(W3REFS) always
	xmllint --noout --schema XMLSchema.xsd XMLSchema.xsd || ./oops
	xmllint --noout ccn.xsd
	@echo === ccn.xsd is well-formed
	xmllint --valid --nowarning --noout ccn.xsd
	@echo === ccn.xsd is valid according to its dtd
	xmllint --schema XMLSchema.xsd --noout ccn.xsd
	xmllint --noout --schema ccn.xsd ccn-test??.xml
	xmllint --noout --dtdvalid ccn.dtd ccn-test??.xml
	ls ccn-schemabad??.xml ccn-bad??.xml | while read i && ./shouldfail xmllint --noout --schema ccn.xsd "$$i"; do :; done
	ls ccn-dtdbad??.xml ccn-bad??.xml | while read i && ./shouldfail xmllint --noout --dtdvalid ccn.dtd "$$i"; do :; done
always:

