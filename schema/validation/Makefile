# $Id$
W3REFS=XMLSchema.dtd XMLSchema.xsd datatypes.dtd xml.xsd
FUZZED= ccn-schemabad01.xml ccn-schemabad02.xml ccn-schemabad04.xml ccn-bad06.xml ccn-schemabad10.xml ccn-bad11.xml
DEBRIS= ccn-schemabad06.xml ccn-schemabad11.xml

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
	rm -f $(W3REFS) $(FUZZED) expectedfailures.out
	test -L ccn.xsd && rm ccn.xsd || :
	test -L ccn.dtd && rm ccn.dtd || :

pretty:
	echo ccn-test??.xml | xargs -n 1 sh prettify.sh

check: ccn.xsd ccn.dtd $(W3REFS) $(FUZZED) always
	@:> expectedfailures.out
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
	@diff expectedfailures.ref expectedfailures.out || :
	: --------------------------- :
	: Schema validation complete  :
	: --------------------------- :

always:

# Generated "bad" test data obtained by tweaking the good examples.
# The fuzzed targets named ccn-schemabad* have problems that the schema should
# be able to catch, but that the DTD won't notice.
# The fuzzed targets named ccn-bad* should be caught by both the schema and the DTD.
# Review expectedfailures.out after changing things to make sure the shouldfail tests
# are actually failing for the right reason.
ccn-schemabad01.xml: ccn-test01.xml
	sed -e '/Witness/s/BlahBlah/foo/' $? > $@

ccn-schemabad02.xml: ccn-test01.xml
	sed -e '/Witness/s/BlahBlah/bad!/' $? > $@

ccn-schemabad04.xml: ccn-test04.xml
	sed -e '/NameComponentCount/s/[1-9]/E/' $? > $@

ccn-bad06.xml: ccn-test06.xml
	sed -e '/PublisherID type/s/ISSUER_KEY/ISSUER_KEYS/' $? > $@

ccn-schemabad10.xml: ccn-test10.xml
	sed -e '/Timestamp/s/11T16/11 16/' $? > $@

ccn-bad11.xml: ccn-test11.xml
	sed -e 's/<\/Bloom>/<\/Bloom><Bloom\/>/' $? > $@
