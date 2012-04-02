# schema/validation/Makefile
# 
# Part of the CCNx distribution.
#
# Copyright (C) 2009-2012 Palo Alto Research Center, Inc.
#
# This work is free software; you can redistribute it and/or modify it under
# the terms of the GNU General Public License version 2 as published by the
# Free Software Foundation.
# This work is distributed in the hope that it will be useful, but WITHOUT ANY
# WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
# FOR A PARTICULAR PURPOSE.
#

W3REFS=XMLSchema.dtd XMLSchema.xsd datatypes.dtd xml.xsd
FUZZED= ccn-bad11.xml ccn-bad06.xml ccn-bad09.xml ccn-schemabad10.xml ccn-schemabad01.xml ccn-schemabad02.xml ccn-schemabad12.xml ccn-schemabad04.xml security-schemabad01.xml repository-bad01.xml repository-schemabad01.xml sync-bad00.xml sync-schemabad25.xml

default:

all test: check

$(W3REFS):
	type gzcat>>/dev/null && { gzcat w3downloads.tgz | tar xf -; } || tar xfz w3downloads.tgz

w3downloads.tgz: always
	tar cvfz w3downloads.tgz $(W3REFS)

# todo -- generalize these rules for a list of schemata
ccnx.xsd:
	ln -s ../ccnx.xsd

ccnx.dtd:
	ln -s ../ccnx.dtd

security.xsd:
	ln -s ../security.xsd

security.dtd:
	ln -s ../security.dtd

repository.xsd:
	ln -s ../repository.xsd

repository.dtd:
	ln -s ../repository.dtd

sync.xsd:
	ln -s ../sync.xsd

sync.dtd:
	ln -s ../sync.dtd

clean:
	rm -f $(W3REFS) $(FUZZED) expectedfailures.out
	test -L ccnx.xsd && rm ccnx.xsd || :
	test -L security.xsd && rm security.xsd || :
	test -L repository.xsd && rm repository.xsd || :
	test -L sync.xsd && rm sync.xsd || :
	test -L ccnx.dtd && rm ccnx.dtd || :
	test -L security.dtd && rm security.dtd || :
	test -L repository.dtd && rm repository.dtd || :
	test -L sync.dtd && rm sync.dtd || :

pretty:
	echo ccn-test??.xml | xargs -n 1 sh prettify.sh
	echo security-test??.xml | xargs -n 1 sh prettify.sh
	echo repository-test??.xml | xargs -n 1 sh prettify.sh
	echo sync-test??.xml | xargs -n 1 sh prettify.sh

check: ccnx.xsd ccnx.dtd security.xsd security.dtd repository.xsd repository.dtd sync.xsd sync.dtd $(W3REFS) $(FUZZED) always
	@:> expectedfailures.out
	xmllint --noout --schema XMLSchema.xsd XMLSchema.xsd || ./oops
	xmllint --noout ccnx.xsd
	@echo === ccnx.xsd is well-formed
	xmllint --noout security.xsd
	@echo === security.xsd is well-formed
	xmllint --noout repository.xsd
	@echo === repository.xsd is well-formed
	xmllint --noout sync.xsd
	@echo === sync.xsd is well-formed
	xmllint --valid --nowarning --noout ccnx.xsd
	@echo === ccnx.xsd is valid according to its dtd
	xmllint --valid --nowarning --noout security.xsd
	@echo === security.xsd is valid according to its dtd
	xmllint --valid --nowarning --noout repository.xsd
	@echo === repository.xsd is valid according to its dtd
	xmllint --valid --nowarning --noout sync.xsd
	@echo === sync.xsd is valid according to its dtd
	xmllint --schema XMLSchema.xsd --noout ccnx.xsd
	xmllint --noout --schema ccnx.xsd ccn-test??.xml
	xmllint --noout --dtdvalid ccnx.dtd ccn-test??.xml
	xmllint --schema XMLSchema.xsd --noout security.xsd
	xmllint --noout --schema security.xsd security-test??.xml
	xmllint --noout --dtdvalid security.dtd security-test??.xml
	xmllint --schema XMLSchema.xsd --noout repository.xsd
	xmllint --noout --schema repository.xsd repository-test??.xml
	xmllint --noout --dtdvalid repository.dtd repository-test??.xml
	xmllint --schema XMLSchema.xsd --noout sync.xsd
	xmllint --noout --schema sync.xsd sync-test??.xml
	xmllint --noout --dtdvalid sync.dtd sync-test??.xml

	ls ccn-schemabad??.xml ccn-bad??.xml | while read i && ./shouldfail xmllint --noout --schema ccnx.xsd "$$i"; do :; done
	ls security-schemabad??.xml security-bad??.xml | while read i && ./shouldfail xmllint --noout --schema security.xsd "$$i"; do :; done
	ls repository-schemabad??.xml security-bad??.xml | while read i && ./shouldfail xmllint --noout --schema repository.xsd "$$i"; do :; done
	ls sync-schemabad??.xml sync-bad??.xml | while read i && ./shouldfail xmllint --noout --schema sync.xsd "$$i"; do :; done
	ls ccn-bad??.xml | while read i && ./shouldfail xmllint --noout --dtdvalid ccnx.dtd "$$i"; do :; done
	ls security-bad??.xml | while read i && ./shouldfail xmllint --noout --dtdvalid security.dtd "$$i"; do :; done
	ls repository-bad??.xml | while read i && ./shouldfail xmllint --noout --dtdvalid repository.dtd "$$i"; do :; done
	ls sync-bad??.xml | while read i && ./shouldfail xmllint --noout --dtdvalid sync.dtd "$$i"; do :; done
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

check: ccn-bad09.xml
ccn-bad09.xml: ccn-test09.xml
	sed -e 's/^.*Timestamp.*$$//' $? > $@

check: ccn-schemabad04.xml
ccn-schemabad04.xml: ccn-test04.xml
	sed -e '/Scope/s/[1-9]/E/' $? > $@

check: ccn-bad06.xml
ccn-bad06.xml: ccn-test06.xml
	sed -e 's/Publisher/Spublisher/g' $? > $@

check: ccn-schemabad10.xml
ccn-schemabad10.xml: ccn-test10.xml
	sed -e '/Timestamp/s/BHsH/+B+A+D+T/' $? > $@

check: ccn-bad11.xml
ccn-bad11.xml: ccn-test11.xml
	sed -e 's/<\/Bloom>/<\/Bloom><Bloom\/>/' $? > $@

check: repository-bad01.xml
repository-bad01.xml: repository-test01.xml
	sed -e 's/GlobalPrefix/GlobalProofix/g' $? > $@

check: ccn-schemabad12.xml
ccn-schemabad12.xml: ccn-test12.xml
	sed -e 's/>DATA/>FUZZ/' $? > $@

check: security-schemabad01.xml
security-schemabad01.xml: security-test01.xml
	sed -e '/EncryptedKey/s/dmdxO6LZm7UUoUdyt6EG3KrnkNJkjdaayBppbvfD/bad!/' $? > $@

check: repository-schemabad01.xml
repository-schemabad01.xml: repository-test01.xml
	sed -e '/Type/s/INFO/bad!/' $? > $@

check: sync-bad00.xml
sync-bad00.xml: sync-test00.xml
	sed -e '/SyncConfigSliceList/d' $? > $@

check: sync-schemabad25.xml
sync-schemabad25.xml: sync-test25.xml
	sed -e 's/TSoztEvAt4W/TS@ztEvAt4W/' $? > $@

default all clean depend test check shared testinstall install uninstall: _always

_always:

.PHONY: _always

