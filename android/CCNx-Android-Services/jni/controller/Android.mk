# Copyright (C) 2010 Palo Alto Research Center, Inc.
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

LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE        := controller

LOCAL_C_INCLUDES    += $(LOCAL_PATH)/../csrc/ccnd
LOCAL_C_INCLUDES    += $(LOCAL_PATH)/../csrc/sync
LOCAL_C_INCLUDES    += $(LOCAL_PATH)/../csrc/ccnr
LOCAL_C_INCLUDES    += $(LOCAL_PATH)/../csrc/include

LOCAL_SRC_FILES     := \
			ctl_ccnd.c \
			ctl_ccnr.c 
LOCAL_CFLAGS		:= $(M_CFLAGS) $(OS_CFLAGS)
LOCAL_LDLIBS        := -ldl -llog $(OS_LDFLAGS)

LOCAL_STATIC_LIBRARIES := \
	libccnd \
	libccnr \
	libccnx \
	libssl \
	libcrypto 

include $(BUILD_SHARED_LIBRARY)
