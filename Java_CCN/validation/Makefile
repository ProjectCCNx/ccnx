# $Id$
W3REFS=XMLSchema.dtd XMLSchema.xsd datatypes.dtd

default: check

$(W3REFS):
	tar xfz w3downloads.tgz

ccn.xsd:
	ln -s ../ccn.xsd

clean:
	rm -f $(W3REFS)
	test -L ccn.xsd && rm ccn.xsd || :

check: ccn.xsd $(W3REFS) always
	xmllint --valid --noout ccn.xsd

always:
