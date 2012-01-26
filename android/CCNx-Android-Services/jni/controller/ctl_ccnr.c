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


/*
 * Class:     org_ccnx_android_services_repo_RepoService
 * Method:    ccnrCreate
 * Signature: (Ljava/lang/String;)I
 */
JNIEXPORT jint JNICALL Java_org_ccnx_android_services_repo_RepoService_ccnrCreate
  (JNIEnv * env, jobject this, jstring version) {
  	return -1;
  }

/*
 * Class:     org_ccnx_android_services_repo_RepoService
 * Method:    ccnrRun
 * Signature: ()I
 */
JNIEXPORT jint JNICALL Java_org_ccnx_android_services_repo_RepoService_ccnrRun
  (JNIEnv * env, jobject this) {
	return -1;
  }

/*
 * Class:     org_ccnx_android_services_repo_RepoService
 * Method:    ccnrDestroy
 * Signature: ()I
 */
JNIEXPORT jint JNICALL Java_org_ccnx_android_services_repo_RepoService_ccnrDestroy
  (JNIEnv * env, jobject this) {
  	return -1;
  }

/*
 * Class:     org_ccnx_android_services_repo_RepoService
 * Method:    ccnrKill
 * Signature: ()I
 */
JNIEXPORT jint JNICALL Java_org_ccnx_android_services_repo_RepoService_ccnrKill
  (JNIEnv * env, jobject this) {
  	return -1;
  }

/*
 * Class:     org_ccnx_android_services_repo_RepoService
 * Method:    ccnrSetenv
 * Signature: (Ljava/lang/String;Ljava/lang/String;I)V
 */
JNIEXPORT void JNICALL Java_org_ccnx_android_services_repo_RepoService_ccnrSetenv
  (JNIEnv * env, jobject this, jstring jkey, jstring jvalue, jint joverwrite) {
  }

#ifdef __cplusplus
}
#endif
#endif
