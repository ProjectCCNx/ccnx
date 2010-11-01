# Copyright (C) 2010 Palo Alto Research Center, Inc.
#
# This work is free software; you can redistribute it and/or modify it under
# the terms of the GNU General Public License version 2 as published by the
# Free Software Foundation.
# This work is distributed in the hope that it will be useful, but WITHOUT ANY
# WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
# FOR A PARTICULAR PURPOSE.

LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
	s2_meth.c   s2_srvr.c s2_clnt.c  s2_lib.c  s2_enc.c s2_pkt.c \
	s3_meth.c   s3_srvr.c s3_clnt.c  s3_lib.c  s3_enc.c s3_pkt.c s3_both.c \
	s23_meth.c s23_srvr.c s23_clnt.c s23_lib.c          s23_pkt.c \
	t1_meth.c   t1_srvr.c t1_clnt.c  t1_lib.c  t1_enc.c \
	d1_meth.c   d1_srvr.c d1_clnt.c  d1_lib.c  d1_pkt.c \
	d1_both.c d1_enc.c \
	ssl_lib.c ssl_err2.c ssl_cert.c ssl_sess.c \
	ssl_ciph.c ssl_stat.c ssl_rsa.c \
	ssl_asn1.c ssl_txt.c ssl_algs.c \
	bio_ssl.c ssl_err.c kssl.c t1_reneg.c

include $(LOCAL_PATH)/../android-config.mk

LOCAL_C_INCLUDES += \
	$(LOCAL_PATH)/.. \
	$(LOCAL_PATH)/../crypto\
	$(LOCAL_PATH)/../include 

LOCAL_STATIC_LIBRARIES += libcrypto 
#LOCAL_SHARED_LIBRARIES += libcrypto

LOCAL_LDLIBS        := -ldl

LOCAL_MODULE:= libssl

include $(BUILD_STATIC_LIBRARY)
