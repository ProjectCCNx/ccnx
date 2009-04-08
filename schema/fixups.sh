#!/bin/sh
# A one-time conversion script for the tests.
P () 
{ 
	sed -f fixups.sed $1 | xmllint - | ccn_xmltoccnb -w - | ccn_ccnbtoxml -b - | xmllint --format - > ${1}+
	diff $1 ${1}+
	test -f OK && mv ${1}+ $1
}
for i in `grep -l Timestamp validation/*.xml`; do P $i; done
