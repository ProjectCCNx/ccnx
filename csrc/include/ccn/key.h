/**
 * @file ccn/key.h
 *
 * KEY interface
 * Needed because Openssl has no definition for symmetric keys
 *
 * Part of the CCNx C Library.
 *
 * Copyright (C) 2013 Palo Alto Research Center, Inc.
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation.
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. You should have received
 * a copy of the GNU Lesser General Public License along with this library;
 * if not, write to the Free Software Foundation, Inc., 51 Franklin Street,
 * Fifth Floor, Boston, MA 02110-1301 USA.
 */

#ifndef CCN_KEY_DEFINED
#define CCN_KEY_DEFINED

#define CCN_SECRET_KEY_LENGTH 256	/* We only support HMAC-SHA256 right now */

typedef struct ccn_symmetric_key_st {
    unsigned char *key;
    int keylength;
    char *digest_algorithm;
} ccn_symmetric_key;

#endif
