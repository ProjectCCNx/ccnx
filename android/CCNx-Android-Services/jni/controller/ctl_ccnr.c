/**
 *
 * Copyright (C) 2012 Palo Alto Research Center, Inc.
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
#include <jni.h>
#include <stdlib.h>
#include <stdio.h>

// The Android log header
#include <android/log.h>

#include "ccnr_private.h"

#ifndef _Included_org_ccnx_android_services_repo_RepoService
#define _Included_org_ccnx_android_services_repo_RepoService
#ifdef __cplusplus
extern "C" {
#endif

extern int start_ccnr();

static struct ccnr_handle *h = NULL;

static int
androidlogger(void *loggerdata, const char *format, va_list ap)
{
	int len = 0;
	len = __android_log_vprint(ANDROID_LOG_INFO, "CCNR", format, ap);
	return len;
}

/*
 * Class:     org_ccnx_android_services_repo_RepoService
 * Method:    ccnrCreate
 * Signature: (Ljava/lang/String;)I
 */
JNIEXPORT jint JNICALL Java_org_ccnx_android_services_repo_RepoService_ccnrCreate
  (JNIEnv * env, jobject this, jstring version) {
    h = r_init_create("ccnr", androidlogger, NULL);
    if (h == NULL) {
        __android_log_print(ANDROID_LOG_ERROR,"CCNR", "ccnrCreate - r_init_create returned NULL");
        return -1;
    }
 	return 0;
  }

/*
 * Class:     org_ccnx_android_services_repo_RepoService
 * Method:    ccnrRun
 * Signature: ()I
 */
JNIEXPORT jint JNICALL Java_org_ccnx_android_services_repo_RepoService_ccnrRun
  (JNIEnv * env, jobject this) {
	__android_log_print(ANDROID_LOG_INFO,"CCNR", "ccnrRun - calling r_dispatch_run(%p)", h);
	r_dispatch_run(h);
	__android_log_print(ANDROID_LOG_INFO,"CCNR", "ccnrRun - r_dispatch_run exited");
  }

/*
 * Class:     org_ccnx_android_services_repo_RepoService
 * Method:    ccnrDestroy
 * Signature: ()I
 */
JNIEXPORT jint JNICALL Java_org_ccnx_android_services_repo_RepoService_ccnrDestroy
  (JNIEnv * env, jobject this) {
	__android_log_print(ANDROID_LOG_INFO,"CCNR", "ccnrDestroy - ccnr stopping");
	r_init_destroy(&h);
  }

/*
 * Class:     org_ccnx_android_services_repo_RepoService
 * Method:    ccnrKill
 * Signature: ()I
 */
JNIEXPORT jint JNICALL Java_org_ccnx_android_services_repo_RepoService_ccnrKill
  (JNIEnv * env, jobject this) {
	if( h != NULL ) {
		__android_log_print(ANDROID_LOG_INFO,"CCNR", "ccnrKill set kill flag (%p)", h);
		h->running = 0;
	} else {
		__android_log_print(ANDROID_LOG_INFO,"CCNR", "ccnrKill null handle");
	}
	return 0;
  }

/*
 * Class:     org_ccnx_android_services_repo_RepoService
 * Method:    ccnrSetenv
 * Signature: (Ljava/lang/String;Ljava/lang/String;I)V
 */
JNIEXPORT void JNICALL Java_org_ccnx_android_services_repo_RepoService_ccnrSetenv
  (JNIEnv * env, jobject this, jstring jkey, jstring jvalue, jint joverwrite) {
	const char *key = (*env)->GetStringUTFChars(env, jkey, NULL);
	const char *value = (*env)->GetStringUTFChars(env, jvalue, NULL);

	__android_log_print(ANDROID_LOG_INFO,"CCNR", "ccnrSetenv %s = %s", key, value);

	setenv(key, value, joverwrite);

	(*env)->ReleaseStringUTFChars(env, jkey, key);
	(*env)->ReleaseStringUTFChars(env, jvalue, value);
  }

#ifdef __cplusplus
}
#endif
#endif
