# conf/Darwin.sh
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
uname -r | tr . ' ' > verinfo
read major minor patch < verinfo
rm verinfo
[ $((major)) -lt 11 ] && exit 0
cat << 'EOF' >> conf.mk
# Darwin.sh was here
CWARNFLAGS = -Wall -Wno-deprecated-declarations -Wpointer-arith -Wreturn-type -Wstrict-prototypes
EOF
