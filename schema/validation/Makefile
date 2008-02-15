# $Id$
W3REFS=XMLSchema.dtd XMLSchema.xsd datatypes.dtd xml.xsd

default: check

$(W3REFS):
	type gzcat>>/dev/null && { gzcat w3downloads.tgz | tar xf -; } || tar xfz w3downloads.tgz

w3downloads.tgz: always
	tar cvfz w3downloads.tgz $(W3REFS)

ccn.xsd:
	ln -s ../ccn.xsd

clean:
	rm -f $(W3REFS)
	test -L ccn.xsd && rm ccn.xsd || :

check: ccn.xsd $(W3REFS) always
	xmllint --noout ccn.xsd
	@echo === ccn.xsd is well-formed
	xmllint --valid --nowarning --noout ccn.xsd
	@echo === ccn.xsd is valid according to its dtd
	xmllint --schema XMLSchema.xsd --noout ccn.xsd
	xmllint --noout --schema ccn.xsd ccn-test01.xml
	xmllint --noout --schema ccn.xsd ccn-test02.xml
	xmllint --noout --schema ccn.xsd ccn-test03.xml
	xmllint --noout --schema ccn.xsd ccn-test04.xml
	xmllint --noout --schema ccn.xsd ccn-test05.xml
	xmllint --noout --schema ccn.xsd ccn-test06.xml
	xmllint --noout --schema ccn.xsd ccn-test07.xml
	xmllint --noout --schema ccn.xsd ccn-test08.xml
	xmllint --noout --schema ccn.xsd ccn-test09.xml

always:

