#!/bin/sh
# schema/validate.sh

# Copyright (C) 2011 Palo Alto Research Center, Inc.
# 
# This work is free software; you can redistribute it and/or modify it under
#  the terms of the GNU General Public License version 2 as published by the
#  Free Software Foundation.
#  This work is distributed in the hope that it will be useful, but WITHOUT ANY
#  WARRANTY; without even the implied warranty of MERCHANTABILITY or
#  FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
#  for more details. You should have received a copy of the GNU General Public
#  License along with this program; if not, write to the
# Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor,
#  Boston, MA 02110-1301, USA.

SCHEMA=example.xsd
XML_EXAMPLES="2-integers-test01 complicated-test01 complicated-test02"

set -e
CCNxDIR=`dirname $0`/../../../../
echo == Make sure CCNx directories have been prepared
test -x $CCNxDIR/bin/ccn_xmltoccnb || exit 1
export PATH=$CCNxDIR/bin:$PATH
test -f $CCNxDIR/schema/validation/XMLSchema.xsd || (cd $CCNxDIR/schema/validation && make test)
echo == Creating symlinks to access external schemata
EXTSCHEMA=`(cd $CCNxDIR/schema/validation && echo *.xsd)`
for x in $EXTSCHEMA; do
    test -f $CCNxDIR/schema/validation/$x && \
      rm -f $x                            && \
      ln -s $CCNxDIR/schema/validation/$x
done

echo == Validating $SCHEMA
xmllint --schema XMLSchema.xsd --noout $SCHEMA

ValidateXML () {
local X
X="$1"
echo == Normalizing ${X}.xml to use base64Binary
# Note for this purpose it does not matter that ccn_ccnbtoxml is ignorant of
#  the project-specific DTAG values, since we're not trying to do anything
#  with the intermediate ccnb except to turn it right back into text.
cat ${X}.xml | ccn_xmltoccnb -w - | ccn_ccnbtoxml -b - | xmllint --format - > ${X}-base64.xml
echo == Validating ${X}
xmllint --schema $SCHEMA --noout ${X}-base64.xml
}
for i in $XML_EXAMPLES; do
    ValidateXML $i
done

echo == Yippee\!
