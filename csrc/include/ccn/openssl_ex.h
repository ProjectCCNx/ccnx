/**
 * @file ccn/openssl_ex.h
 *
 * OpenSSL extension interface.
 *
 * Used to define our own OpenSSL 1.0.X extensions that will go away when
 * we switch to OpenSSL 1.0.X
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

#ifndef CCN_OPENSSL_EX_DEFINED
#define CCN_OPENSSL_EX_DEFINED

#include <openssl/evp.h>

#ifndef EVP_PKEY_HMAC

#define EVP_PKEY_HMAC	NID_hmac
#define NEED_OPENSSL_1_0_COMPAT

void *EVP_PKEY_get0(EVP_PKEY *pkey);
EVP_PKEY *EVP_PKEY_new_mac_key(int type, ENGINE *e, const unsigned char *key, int keylen);
int ccn_PKEY_assign(EVP_PKEY *pkey, int type, char *key);
#else
#define ccn_PKEY_assign(pkey, type, key) EVP_PKEY_assign(pkey, type, key)
#endif

#endif
