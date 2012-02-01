# Source file: util/ccnget.sh
# 
# Part of the CCNx distribution.
#
# Copyright (C) 2012 Palo Alto Research Center, Inc.
#
# This work is free software; you can redistribute it and/or modify it under
# the terms of the GNU General Public License version 2 as published by the
# Free Software Foundation.
# This work is distributed in the hope that it will be useful, but WITHOUT ANY
# WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
# FOR A PARTICULAR PURPOSE.
#
# This script should be installed in the same place as ccnpeek, ccnpoke, ...
# adjust the path to get consistency.
D=`dirname "$0"`
export PATH="$D:$PATH"
echo ccnput is a deprecated command name.  The new name is ccnpoke. 1>&2
ccnpoke "$@"
