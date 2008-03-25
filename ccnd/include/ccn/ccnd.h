/*
 * ccn/ccnd.h
 * 
 * Copyright 2008 Palo Alto Research Center, Inc. All rights reserved.
 * Definitions pertaining to the CCN daemon
 *
 * $Id$
 */

#ifndef CCN_CCND_DEFINED
#define CCN_CCND_DEFINED

#define CCN_DEFAULT_LOCAL_SOCKNAME "/tmp/.ccnd.sock"

/* link adapters sign on by sending this greeting to ccnd */
#define CCN_EMPTY_PDU "CCN\202\000"
#define CCN_EMPTY_PDU_LENGTH 5
#endif
