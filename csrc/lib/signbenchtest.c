/**
 * @file signbenchtest.c
 * 
 * A simple test program to benchmark signing performance.
 *
 * Copyright (C) 2009 Palo Alto Research Center, Inc.
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
 #include <stdlib.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <ccn/ccn.h>
#include <ccn/charbuf.h>
#include <ccn/keystore.h>
#include <time.h>
#include <sys/time.h>

#define FRESHNESS 10 
#define COUNT 3000
#define PAYLOAD_SIZE 51

int
main(int argc, char **argv)
{

  struct ccn_keystore *keystore = NULL;
  int res = 0;
  struct ccn_charbuf *signed_info = ccn_charbuf_create();
  int i;
  int sec, usec;
  char msgbuf[PAYLOAD_SIZE];
  struct timeval start, end;
  struct ccn_charbuf *message = ccn_charbuf_create();
  struct ccn_charbuf *path = ccn_charbuf_create();
  struct ccn_charbuf *seq = ccn_charbuf_create();

  struct ccn_charbuf *temp = ccn_charbuf_create();
  keystore = ccn_keystore_create();
  ccn_charbuf_putf(temp, "%s/.ccnx/.ccnx_keystore", getenv("HOME"));
  res = ccn_keystore_init(keystore,
			  ccn_charbuf_as_string(temp),
			  "Th1s1sn0t8g00dp8ssw0rd.");
  if (res != 0) {
    printf("Failed to initialize keystore %s\n", ccn_charbuf_as_string(temp));
    exit(1);
  }
  ccn_charbuf_destroy(&temp);
  
  res = ccn_signed_info_create(signed_info,
			       /* pubkeyid */ ccn_keystore_public_key_digest(keystore),
			       /* publisher_key_id_size */ ccn_keystore_public_key_digest_length(keystore),
			       /* datetime */ NULL,
			       /* type */ CCN_CONTENT_DATA,
			       /* freshness */ FRESHNESS,
                               /*finalblockid*/ NULL,
			       /* keylocator */ NULL);

  srandom(time(NULL));
  for (i=0; i<PAYLOAD_SIZE; i++) {
    msgbuf[i] = random();
  }

  printf("Generating %d signed ContentObjects (one . per 100)\n", COUNT);
  gettimeofday(&start, NULL);

  for (i=0; i<COUNT; i++) {
    
    if (i>0 && (i%100) == 0) {
      printf(".");
      fflush(stdout);
    }
    ccn_name_init(path);
    ccn_name_append_str(path, "rtp");
    ccn_name_append_str(path, "protocol");
    ccn_name_append_str(path, "13.2.117.34");
    ccn_name_append_str(path, "domain");
    ccn_name_append_str(path, "smetters");
    ccn_name_append_str(path, "principal");
    ccn_name_append_str(path, "2021915340");
    ccn_name_append_str(path, "id");
    ccn_charbuf_putf(seq, "%u", i);
    ccn_name_append(path, seq->buf, seq->length);
    ccn_name_append_str(path, "seq");
  
    res = ccn_encode_ContentObject(/* out */ message,
				   path, signed_info, 
				   msgbuf, PAYLOAD_SIZE,
				   /* digest_algorithm */ NULL, 
				   ccn_keystore_private_key(keystore));

    ccn_charbuf_reset(message);
    ccn_charbuf_reset(path);
    ccn_charbuf_reset(seq);
  }
  gettimeofday(&end, NULL);
  sec = end.tv_sec - start.tv_sec;
  usec = (int)end.tv_usec - (int)start.tv_usec;
  while (usec < 0) {
    sec--;
    usec += 1000000;
  }

  printf("\nComplete in %d.%06d secs\n", sec, usec);

  return(0);
}
