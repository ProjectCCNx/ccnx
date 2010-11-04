/*
 * Copyright (C) 2009,2010 Palo Alto Research Center, Inc.
 *
 * This work is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 2 as published by the
 * Free Software Foundation.
 * This work is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * for more details. You should have received a copy of the GNU General Public
 * License along with this program; if not, write to the
 * Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor,
 * Boston, MA 02110-1301, USA.
 */

/**
 * JNI wrapper functions for the ccnd process.  This uses android_main.c to
 * procedurally startup ccnd.
 *
 * The startup process is:
 *    1) call setenv to set any environment variables for CCND, as per
 *       normal CCND startup (e.g. working directory, capacity, debug level, etc.)
 *
 *    2) call ccndCreate
 *
 *    --> at this time, ccnd is ready to service requests
 *
 *    3) call ccndRun
 *
 *    --> caller is now blocked until ccnd exits
 *
 * To exit CCND, call kill.  This sets the "running" member of the ccnd handle
 * to zero, so ccnd will exit on its next main loop.  You should cleanup the
 * ccnd handle by calling ccndDestroy on it.
 *
 * The JNI methods are in the package org.ccnx.android.services.ccnd.  There are
 * also versions in org.ccnx.android.test.services.ccnd for JUnit testing
 */

#include <jni.h>
#include <stdlib.h>
#include <stdio.h>

// The Android log header
#include <android/log.h>

#include "ccnd_private.h"

JNIEXPORT void JNICALL Java_org_ccnx_android_services_ccnd_CcndService_ccndCreate
	(JNIEnv *env, jobject thiz);
JNIEXPORT void JNICALL Java_org_ccnx_android_services_ccnd_CcndService_ccndRun
	(JNIEnv *env, jobject thiz);
JNIEXPORT void JNICALL Java_org_ccnx_android_services_ccnd_CcndService_ccndDestroy
	(JNIEnv *env, jobject thiz);

JNIEXPORT void JNICALL Java_org_ccnx_android_services_ccnd_CcndService_setenv
	(JNIEnv *env, jobject thiz, jstring jkey, jstring jvalue, jint joverwrite);

JNIEXPORT void JNICALL Java_org_ccnx_android_services_ccnd_CcndService_kill
  (JNIEnv *env, jobject thiz);

extern int start_ccnd();

static struct ccnd_handle *h = NULL;

static int
androidlogger(void *loggerdata, const char *format, va_list ap)
{
	int len = 0;
	len = __android_log_vprint(ANDROID_LOG_INFO, "CCND", format, ap);
    return len;
}

JNIEXPORT void JNICALL Java_org_ccnx_android_test_ccnd_CcndThread_launch
  (JNIEnv *env, jobject thiz)
{
	Java_org_ccnx_android_services_ccnd_CcndService_ccndCreate(env, thiz);
	Java_org_ccnx_android_services_ccnd_CcndService_ccndRun(env, thiz);
	Java_org_ccnx_android_services_ccnd_CcndService_ccndDestroy(env, thiz);
    __android_log_print(ANDROID_LOG_INFO,"CCND", "ccnd launch exiting");
}

JNIEXPORT void JNICALL Java_org_ccnx_android_test_ccnd_CcndThread_setenv
  (JNIEnv *env, jobject thiz, jstring jkey, jstring jvalue, jint joverwrite) 
{
	Java_org_ccnx_android_services_ccnd_CcndService_setenv(env, thiz, jkey, jvalue, joverwrite);
}

JNIEXPORT void JNICALL Java_org_ccnx_android_test_ccnd_CcndThread_kill
  (JNIEnv *env, jobject thiz)
{
	Java_org_ccnx_android_services_ccnd_CcndService_kill(env, thiz);
}


JNIEXPORT void JNICALL Java_org_ccnx_android_services_ccnd_CcndService_ccndCreate
  (JNIEnv *env, jobject thiz)
{
    h = ccnd_create("ccnd", androidlogger, NULL);
    if (h == NULL) {
		__android_log_print(ANDROID_LOG_ERROR,"CCND", "ccnd_create returned NULL");
        return;
    }
}

JNIEXPORT void JNICALL Java_org_ccnx_android_services_ccnd_CcndService_ccndRun
  (JNIEnv *env, jobject thiz)
{
    __android_log_print(ANDROID_LOG_INFO,"CCND", "calling ccnd_run (%p)", h);

    ccnd_run(h);

    __android_log_print(ANDROID_LOG_INFO,"CCND", "ccnd_run exited");
}

JNIEXPORT void JNICALL Java_org_ccnx_android_services_ccnd_CcndService_ccndDestroy
  (JNIEnv *env, jobject thiz)
{
    __android_log_print(ANDROID_LOG_INFO,"CCND", "ccnd stopping");
    ccnd_destroy(&h);
}

JNIEXPORT void JNICALL Java_org_ccnx_android_services_ccnd_CcndService_setenv
  (JNIEnv *env, jobject thiz, jstring jkey, jstring jvalue, jint joverwrite)
{
	const char *key = (*env)->GetStringUTFChars(env, jkey, NULL);
	const char *value = (*env)->GetStringUTFChars(env, jvalue, NULL);

    __android_log_print(ANDROID_LOG_INFO,"CCND", "CcndService_setenv %s = %s", key, value);

	setenv(key, value, joverwrite);

	(*env)->ReleaseStringUTFChars(env, jkey, key);
	(*env)->ReleaseStringUTFChars(env, jvalue, value);

	return;
}

JNIEXPORT void JNICALL Java_org_ccnx_android_services_ccnd_CcndService_kill
  (JNIEnv *env, jobject thiz)
{
    if( h != NULL ) {
		__android_log_print(ANDROID_LOG_INFO,"CCND", "CcndService_kill set kill flag (%p)", h);
    	h->running = 0;
	} else {
		__android_log_print(ANDROID_LOG_INFO,"CCND", "CcndService_kill null handle");
	}

	return;
}

