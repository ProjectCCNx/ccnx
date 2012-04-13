# Copyright (C) 2009-2012 Palo Alto Research Center, Inc.
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

LOCAL_MODULE		:= libccnr
LOCAL_C_INCLUDES	:= $(LOCAL_PATH)
LOCAL_C_INCLUDES	+= $(LOCAL_PATH)/../include 
LOCAL_C_INCLUDES	+= $(LOCAL_PATH)/..

LOCAL_C_INCLUDES	+= $(LOCAL_PATH)/../../android/external/openssl-armv5/include

CCNROBJ := ccnr_dispatch.o ccnr_forwarding.o ccnr_init.o ccnr_internal_client.o ccnr_io.o ccnr_link.o ccnr_main.o ccnr_match.o ccnr_msg.o ccnr_net.o ccnr_proto.o ccnr_sendq.o ccnr_stats.o ccnr_store.o ccnr_sync.o ccnr_util.o ../sync/IndexSorter.o ../sync/SyncActions.o ../sync/SyncBase.o ../sync/SyncHashCache.o ../sync/SyncNode.o ../sync/SyncRoot.o ../sync/SyncTreeWorker.o ../sync/SyncUtil.o
CCNRSRC := $(CCNROBJ:.o=.c)

LOCAL_SRC_FILES := $(CCNRSRC)
LOCAL_CFLAGS := -g
LOCAL_STATIC_LIBRARIES := libcrypto libccnx
LOCAL_SHARED_LIBRARIES :=

include $(BUILD_STATIC_LIBRARY)
