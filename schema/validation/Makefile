# $Id$
W3REFS=XMLSchema.dtd XMLSchema.xsd datatypes.dtd xml.xsd
FUZZED= ccn-bad11.xml ccn-bad06.xml ccn-schemabad10.xml ccn-schemabad01.xml ccn-schemabad02.xml ccn-schemabad12.xml ccn-schemabad04.xml

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
	@diff expectedfailures.ref expectedfailures.out || echo "***" $(LECTURE)
	: --------------------------- :
	: Schema validation complete  :
	: --------------------------- :

LECTURE = Expected failures do not match - to make this message go away, \
	  review the expectedfailures.out log and if it looks like \
	  the failures are happening for the correct reasons do this: \
		cp expectedfailures.out expectedfailures.ref

always:

# Generated "bad" test data obtained by tweaking the good examples.
# The fuzzed targets named ccn-schemabad* have problems that the schema should
# be able to catch, but that the DTD won't notice.
# The fuzzed targets named ccn-bad* should be caught by both the schema and the DTD.
# Review expectedfailures.out after changing things to make sure the shouldfail tests
# are actually failing for the right reason.
check: ccn-schemabad01.xml
ccn-schemabad01.xml: ccn-test01.xml
	sed -e '/Witness/s/BlahBlah/foo/' $? > $@

check: ccn-schemabad02.xml
ccn-schemabad02.xml: ccn-test01.xml
	sed -e '/Witness/s/BlahBlah/bad!/' $? > $@

check: ccn-bad02.xml
ccn-bad02.xml: ccn-test02.xml
	sed -e 's/^.*Timestamp.*$//' $? > $@

check: ccn-schemabad04.xml
ccn-schemabad04.xml: ccn-test04.xml
	sed -e '/NameComponentCount/s/[1-9]/E/' $? > $@

check: ccn-bad06.xml
ccn-bad06.xml: ccn-test06.xml
	sed -e 's/Publisher/Spublisher/g' $? > $@

check: ccn-schemabad10.xml
ccn-schemabad10.xml: ccn-test10.xml
	sed -e '/Timestamp/s/BHsH/+B+A+D+T/' $? > $@

check: ccn-bad11.xml
ccn-bad11.xml: ccn-test11.xml
	sed -e 's/<\/Bloom>/<\/Bloom><Bloom\/>/' $? > $@

check: ccn-schemabad12.xml
ccn-schemabad12.xml: ccn-test12.xml
	sed -e 's/Type>DATA/Type>FUZZ/' $? > $@
