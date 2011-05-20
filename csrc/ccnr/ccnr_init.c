#include "common.h"

/**
 * Start a new ccnr instance
 * @param progname - name of program binary, used for locating helpers
 * @param logger - logger function
 * @param loggerdata - data to pass to logger function
 */
PUBLIC struct ccnr_handle *
r_init_create(const char *progname, ccnr_logger logger, void *loggerdata)
{
    char *sockname;
    // const char *portstr;
    const char *debugstr;
    const char *entrylimit;
    const char *listen_on;
    struct ccnr_handle *h;
    struct hashtb_param param = {0};
    
    sockname = r_net_get_local_sockname();
    h = calloc(1, sizeof(*h));
    if (h == NULL)
        return(h);
    h->logger = logger;
    h->loggerdata = loggerdata;
    h->appnonce = &r_fwd_append_plain_nonce;
    h->logpid = (int)getpid();
    h->progname = progname;
    h->debug = -1;
    h->skiplinks = ccn_indexbuf_create();
    param.finalize_data = h;
    h->face_limit = 10; /* soft limit */
    h->fdholder_by_fd = calloc(h->face_limit, sizeof(h->fdholder_by_fd[0]));
    param.finalize = &r_fwd_finalize_nameprefix;
    h->nameprefix_tab = hashtb_create(sizeof(struct nameprefix_entry), &param);
    param.finalize = &r_fwd_finalize_propagating;
    h->propagating_tab = hashtb_create(sizeof(struct propagating_entry), &param);
    param.finalize = 0;
    h->min_stale = ~0;
    h->max_stale = 0;
    h->unsol = ccn_indexbuf_create();
    h->ticktock.descr[0] = 'C';
    h->ticktock.micros_per_base = 1000000;
    h->ticktock.gettime = &r_util_gettime;
    h->ticktock.data = h;
    h->sched = ccn_schedule_create(h, &h->ticktock);
    h->starttime = h->sec;
    h->starttime_usec = h->usec;
    h->oldformatcontentgrumble = 1;
    h->oldformatinterestgrumble = 1;
    debugstr = getenv("CCNR_DEBUG");
    if (debugstr != NULL && debugstr[0] != 0) {
        h->debug = atoi(debugstr);
        if (h->debug == 0 && debugstr[0] != '0')
            h->debug = 1;
    }
    else
        h->debug = 1;
    // portstr = getenv(CCN_LOCAL_PORT_ENVNAME);
    // if (portstr == NULL || portstr[0] == 0 || strlen(portstr) > 10)
        // portstr = CCN_DEFAULT_UNICAST_PORT;
    h->portstr = "8008"; // XXX - make configurable.
    entrylimit = getenv("CCNR_CAP");
    h->capacity = ~0;
    if (entrylimit != NULL && entrylimit[0] != 0) {
        h->capacity = atol(entrylimit);
        if (h->capacity <= 0)
            h->capacity = 10;
    }
    ccnr_msg(h, "CCNR_DEBUG=%d CCNR_CAP=%lu", h->debug, h->capacity);
    listen_on = getenv("CCNR_LISTEN_ON");
    if (listen_on != NULL && listen_on[0] != 0)
        ccnr_msg(h, "CCNR_LISTEN_ON=%s", listen_on);
    h->appnonce = &r_fwd_append_debug_nonce;
    ccnr_init_internal_keystore(h);
    /* XXX - need to bail if keystore is not OK. */
    r_util_reseed(h);
    if (h->face0 == NULL) {
        struct fdholder *fdholder;
        fdholder = calloc(1, sizeof(*fdholder));
        fdholder->recv_fd = -1;
        fdholder->sendface = 0;
        fdholder->flags = (CCN_FACE_GG | CCN_FACE_LOCAL);
        h->face0 = fdholder;
    }
    r_io_enroll_face(h, h->face0);
    r_net_listen_on(h, listen_on);
    r_fwd_age_forwarding_needed(h);
    ccnr_internal_client_start(h);
    free(sockname);
    sockname = NULL;
    return(h);
}

/**
 * Destroy the ccnr instance, releasing all associated resources.
 */
PUBLIC void
r_init_destroy(struct ccnr_handle **pccnr)
{
    struct ccnr_handle *h = *pccnr;
    if (h == NULL)
        return;
    r_io_shutdown_all(h);
    ccnr_internal_client_stop(h);
    ccn_schedule_destroy(&h->sched);
    hashtb_destroy(&h->content_tab);
    hashtb_destroy(&h->propagating_tab);
    hashtb_destroy(&h->nameprefix_tab);
    if (h->fds != NULL) {
        free(h->fds);
        h->fds = NULL;
        h->nfds = 0;
    }
    if (h->fdholder_by_fd != NULL) {
        free(h->fdholder_by_fd);
        h->fdholder_by_fd = NULL;
        h->face_limit = h->face_gen = 0;
    }
    if (h->content_by_accession != NULL) {
        free(h->content_by_accession);
        h->content_by_accession = NULL;
        h->content_by_accession_window = 0;
    }
    ccn_charbuf_destroy(&h->scratch_charbuf);
    ccn_indexbuf_destroy(&h->skiplinks);
    ccn_indexbuf_destroy(&h->scratch_indexbuf);
    ccn_indexbuf_destroy(&h->unsol);
    if (h->face0 != NULL) {
        ccn_charbuf_destroy(&h->face0->inbuf);
        ccn_charbuf_destroy(&h->face0->outbuf);
        free(h->face0);
        h->face0 = NULL;
    }
    free(h);
    *pccnr = NULL;
}
