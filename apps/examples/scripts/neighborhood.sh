# apps/examples/scripts/neighborhood.sh
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

# This is an example of discovery of neighboring CCNx nodes.
# It works by local broadcast.

# The all-ones IP broadcast address is used by default.
# An alternative would be a link-local multicast address, but that
# depends on the neighbors having joined the group.
ADDR=${1:-255.255.255.255}

ccndc -t 3 add ccnx:/%C1.M.S.neighborhood/%C1.M.SRV/ccnd/KEY udp $ADDR
CCN_SCOPE=2 ccnls ccnx:/%C1.M.S.neighborhood/%C1.M.SRV/ccnd/KEY
ccnrm -o neighborhood.ccnb ccnx:/%C1.M.S.neighborhood/%C1.M.SRV/ccnd/KEY
ccndc destroy udp $ADDR
ccndstatus