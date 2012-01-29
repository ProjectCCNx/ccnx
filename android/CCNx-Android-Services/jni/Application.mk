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
# For Android 1.5 static compilation, we need to put
# everything in one bundle.  ANything newer could use
# multiple .so files, but why for this?
#

# Don't need this in a jni/Application.mk
#APP_PROJECT_PATH := 

# The libraries
APP_MODULES      := libccnx
# APP_MODULES      += lib2ccnx
APP_MODULES      += libccnd 
# APP_MODULES      += libsync
APP_MODULES      += libccnr 
APP_MODULES      += libcrypto
APP_MODULES      += libssl

# This is the "main" function that will route
# calls to all the linked in libraries
APP_MODULES		+=	controller

