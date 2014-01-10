# ccnd/gen_stregistry.sh
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

# Construct source code for the table that maps
# textual strategy identifers to procedure pointers.

PRODUCT=ccnd_stregistry.h
TF=new_$PRODUCT
PATLEFT=ccnd_
PATRIGHT=_strategy_impl

# Overall generator
# Arguments are source files to scan
# Writes to stdout.
Gen () {
    PROCNAMES=`GetAllStrategyProcNames "$@"`
    Preamble
    StartPrototypes
    for procname in $PROCNAMES; do
        Prototype $procname
    done
    EndPrototypes
    StartArray
    for procname in $PROCNAMES; do
        ArrayItem $procname
    done
    EndArray
    Postamble
}

# Any front matter is generated here
Preamble () {
cat << EOF
/* generated code, do not edit */
#include "ccnd_strategy.h"
EOF
}

# Wrap it up
Postamble () {
cat << EOF

/* end */
EOF
}

# About to generate prototypes
StartPrototypes () {
cat << EOF

/* prototypes */
EOF
}

# Generate a prototype for the named strategy procedure
Prototype () {
PROCNAME=$1
cat << EOF
void $PROCNAME(struct ccnd_handle *h,
      struct strategy_instance *instance,
      struct ccn_strategy *s,
      enum ccn_strategy_op op,
      unsigned faceid);
EOF
}

# All the prototypes are done
EndPrototypes () {
cat << EOF

EOF
}

# Open the array declaration
StartArray () {
cat << EOF
#ifdef DEFINE_STRATEGY_CLASSES
/* strategy class table */
const struct strategy_class ccnd_strategy_classes[] = {
EOF
}

# StrategyID from procedure name
# Reads stdin, writes stdout
StrNameFromProcNameFilter () {
    sed -e 's/^'$PATLEFT// -e s/$PATRIGHT'$//'
}

# Declare one item in the array
ArrayItem () {
    PROCNAME=$1
    STRNAME=`echo $PROCNAME | StrNameFromProcNameFilter`
    echo '    {"'$STRNAME'", &'$PROCNAME'},'
}

# Close off the end of the array declaration
EndArray () {
cat << EOF
    {"", 0},	/* provide space for a few dynamic slots */
    {"", 0},
    {"", 0},
    {"", 0},
    {"", 0},
    {"", 0}};
#endif
EOF
}

# Scan the named source files for procedures that
# match the name template.
# Writes to stdout.
GetAllStrategyProcNames () {
    while [ $# -gt 0 ]; do
        GetStrategyProcNamesInFile $1
        shift
    done
}

# Scan a source file for procedures that match the
# name template.
# Writes to stdout.
GetStrategyProcNamesInFile () {
    grep '^'$PATLEFT'.[A-Za-z0-9_]*'$PATRIGHT $1 | \
    cut -d '(' -f 1
}

# Main - call Gen to do the work, and write result source
# to the output file if it has changed since last time.
trap "[ -f $TF ] && rm $TF" 0
set -e
Gen "$@" > $TF
cmp $TF $PRODUCT >/dev/null 2>/dev/null || mv $TF $PRODUCT
