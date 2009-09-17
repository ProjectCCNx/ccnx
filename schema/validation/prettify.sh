# validation/prettify.sh
# 
# Part of the CCNx distribution.
#
# Copyright (C) 2009 Palo Alto Research Center, Inc.
#
# This work is free software; you can redistribute it and/or modify it under
# the terms of the GNU General Public License version 2 as published by the
# Free Software Foundation.
# This work is distributed in the hope that it will be useful, but WITHOUT ANY
# WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
# FOR A PARTICULAR PURPOSE.
#
test -f "$1" || exit 1
FILE="$1"
Cleanup () {
  cat $$errs >&2
  rm -f $$errs "$FILE".new$$
}
trap Cleanup 0
xmllint --format "$1" 2>$$errs >"$1".new$$ || exit 1
grep . $$errs && exit 1
cmp -s "$1".new$$ "$1" && exit 0
echo $0 $1 >&2
mv "$1".new$$ "$1"
exit 0
