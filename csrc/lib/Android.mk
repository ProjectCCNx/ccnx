# Copyright (C) 2009,2010 Palo Alto Research Center, Inc.
#
# This work is free software; you can redistribute it and/or modify it under
# the terms of the GNU General Public License version 2 as published by the
# Free Software Foundation.
# This work is distributed in the hope that it will be useful, but WITHOUT ANY
# WARRANTY; without even the implied warranty of MERCHANTABILITY or
# FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
# for more details. You should have received a copy of the GNU General Public
# License along with this program; if not, write to the
# Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor,
# Boston, MA 02110-1301, USA.
#
LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE		:= libccnx
LOCAL_C_INCLUDES	:= $(LOCAL_PATH)
LOCAL_C_INCLUDES	+= $(LOCAL_PATH)/../include 

# LOCAL_PATH = project_root/csrc/lib
LOCAL_C_INCLUDES	+= $(LOCAL_PATH)/../../android/external/openssl-armv5/include

CCNLIBOBJ := ccn_client.o ccn_charbuf.o ccn_indexbuf.o ccn_coding.o \
		ccn_dtag_table.o ccn_schedule.o ccn_matrix.o \
		ccn_buf_decoder.o ccn_uri.o ccn_buf_encoder.o \
		ccn_bloom.o ccn_name_util.o ccn_face_mgmt.o ccn_reg_mgmt.o \
		ccn_digest.o ccn_interest.o ccn_keystore.o \
                ccn_signing.o ccn_sockcreate.o \
		ccn_traverse.o ccn_match.o hashtb.o ccn_merkle_path_asn1.o \
		ccn_setup_sockaddr_un.o ccn_bulkdata.o ccn_versioning.o \
		ccn_seqwriter.o ccn_sockaddrutil.o

CCNLIBSRC := $(CCNLIBOBJ:.o=.c)

LOCAL_SRC_FILES := $(CCNLIBSRC)
LOCAL_CFLAGS := -g
LOCAL_STATIC_LIBRARIES := libcrypto libssl
LOCAL_SHARED_LIBRARIES :=

include $(BUILD_STATIC_LIBRARY)

