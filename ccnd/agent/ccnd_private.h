/*
 * ccnd_private.h
 * 
 * Copyright 2008 Palo Alto Research Center, Inc. All rights reserved.
 * Private definitions for the CCN daemon
 *
 * Data structures are described here so that logging and status
 * routines can be compiled separately.
 *
 * $Id$
 */

#ifndef CCND_PRIVATE_DEFINED
#define CCND_PRIVATE_DEFINED

#include <poll.h>
#include <stddef.h>
#include <stdint.h>
#include <sys/socket.h>
#include <sys/types.h>

#include <ccn/coding.h>

/*
 * These are defined in other ccn headers, but the incomplete types suffice
 * for the purposes of this header.
 */
struct ccn_charbuf;
struct hashtb;
struct ccn_matrix;
struct ccn_schedule;

/*
 * These are defined in this header.
 */
struct ccnd;
struct face;
struct content_entry;
struct interest_entry;
struct propagating_entry;

/*
 * We pass this handle almost everywhere.
 */
struct ccnd {
    struct hashtb *faces_by_fd;     /* keyed by fd */
    struct hashtb *dgram_faces;     /* keyed by sockaddr */
    struct hashtb *content_tab;     /* keyed by initial fragment of ContentObject */
    struct hashtb *interest_tab;    /* keyed by name components */
    struct hashtb *propagating_tab; /* keyed by nonce */
    struct ccn_matrix *backlinks;   /* for linking to earlier content */
    unsigned face_gen;
    unsigned face_rover;            /* for faceid allocation */
    unsigned face_limit;
    struct face **faces_by_faceid;  /* array with face_limit elements */
    struct ccn_scheduled_event *reaper;
    struct ccn_scheduled_event *age;
    struct ccn_scheduled_event *clean;
    int local_listener_fd;
    nfds_t nfds;
    struct pollfd *fds;
    struct ccn_schedule *sched;
    struct ccn_charbuf *scratch_charbuf;
    struct ccn_indexbuf *scratch_indexbuf;
    uint_least64_t accession_base;
    unsigned content_by_accession_window;
    struct content_entry **content_by_accession;
    uint_least64_t accession;
    unsigned long content_dups_recvd;
    unsigned long content_items_sent;
    unsigned long interests_accepted;
    unsigned long interests_dropped;
    unsigned long interests_sent;
    unsigned short seed[3];
};

/*
 * Each face is referenced by a number, the faceid.  The low-order
 * bits (under the MAXFACES) constitute a slot number that is
 * unique (for this ccnd) among the faces that are alive at a given time.
 * The rest of the bits form a generation number that make the
 * entire faceid unique over time, even for faces that are defunct.
 */
#define FACESLOTBITS 18
#define MAXFACES ((1U << FACESLOTBITS) - 1)

/*
 * One of our active interfaces
 */
struct face {
    int fd;
    int flags;
    unsigned faceid;            /* internal face id */
    unsigned recvcount;         /* for activity level monitoring */
    struct ccn_charbuf *inbuf;
    struct ccn_skeleton_decoder decoder;
    size_t outbufindex;
    struct ccn_charbuf *outbuf;
    const struct sockaddr *addr;
    socklen_t addrlen;
};
/* face flags */
#define CCN_FACE_LINK   (1 << 0) /* Elements wrapped by CCNProtocolDataUnit */
#define CCN_FACE_DGRAM  (1 << 1) /* Datagram interface, respect packets */

/*
 * The interest hash table is keyed by the Component elements of the Name
 */
struct interest_entry {
    struct ccn_indexbuf *interested_faceid;
    struct ccn_indexbuf *counters;
    uint_least64_t newest;
    uint_least64_t cached_accession;
    unsigned       cached_faceid;
    int ncomp;                   /* Number of name components */
};
/* The interest counters are scaled by a factor of CCN_UNIT_INTEREST */
#define CCN_UNIT_INTEREST 5

/*
 *  The content hash table is keyed by the initial portion of the ContentObject
 *  that contains all the parts of the complete name, so that the original
 *  ContentObject may be reconstructed simply by gluing this together with
 *  the remainder of the object, represented by tail.
 */
struct content_entry {
    uint_least64_t accession;   /* assigned in arrival order */
    unsigned short *comps;      /* Name Component byte boundary offsets */
    int ncomps;                 /* Number of name components plus one */
    const unsigned char *key;	/* ContentObject fragment prior to Content */
    int key_size;
    unsigned char *tail;        /* ContentObject fragment starting at Content */
    int tail_size;
    int nface_old;              /* Used for cleaning supression state */
    int nface_done;             /* How many faces have seen the content */
    struct ccn_indexbuf *faces; /* These faceids have or want the content */
    struct ccn_scheduled_event *sender;
};

/*
 * The propagating interest hash table is keyed by Nonce.
 */
struct propagating_entry {
    unsigned char *interest_msg;
    size_t size;
    struct ccn_indexbuf *outbound;
};

/* Consider a separate header for these */
int ccnd_stats_httpd_start(struct ccnd *);
void ccnd_msg(struct ccnd *, const char *, ...);

#endif
