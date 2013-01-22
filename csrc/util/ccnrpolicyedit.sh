# util/ccnrpolicyedit.sh
# 
# Part of the CCNx distribution.
#
# Copyright (C) 2013 Palo Alto Research Center, Inc.
#
# This work is free software; you can redistribute it and/or modify it under
# the terms of the GNU General Public License version 2 as published by the
# Free Software Foundation.
# This work is distributed in the hope that it will be useful, but WITHOUT ANY
# WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
# FOR A PARTICULAR PURPOSE.
#

D=`dirname "$0"`
export PATH="$D:$PATH"
Fail () {
  echo Failed - $* >&2
  exit 1
}
. $HOME/.ccnx/ccndrc
[ -z "$CCNR_GLOBAL_PREFIX" ] && \
  Fail CCNR_GLOBAL_PREFIX is not set in $HOME/.ccnx/ccndrc
X=`mktemp ${TMPDIR:-/tmp}/policyXXXXXX`
if ! type xmllint 2>/dev/null >/dev/null; then
  xmllint () {
    cat
  }
fi
trap "rm -f $X $X.ccnb" EXIT
ccncat $CCNR_GLOBAL_PREFIX/data/policy.xml | \
  ccn_ccnbtoxml -xv - | xmllint --format - > $X
${EDITOR:-vi} $X || Fail edit aborted
ccn_xmltoccnb -w - < $X > $X.ccnb || Fail Malformed XML
ccnseqwriter -r $CCNR_GLOBAL_PREFIX/data/policy.xml < $X.ccnb
