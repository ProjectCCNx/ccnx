#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <netdb.h>
#include <poll.h>
#include <signal.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <arpa/inet.h>
#include <sys/time.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/un.h>
#include <netinet/in.h>

#include <ccn/bloom.h>
#include <ccn/ccn.h>
#include <ccn/ccn_private.h>
#include <ccn/charbuf.h>
#include <ccn/face_mgmt.h>
#include <ccn/hashtb.h>
#include <ccn/indexbuf.h>
#include <ccn/schedule.h>
#include <ccn/reg_mgmt.h>
#include <ccn/uri.h>

#include "ccnr_private.h"

#define PUBLIC

PUBLIC struct ccn_charbuf *
r_util_charbuf_obtain(struct ccnr_handle * h);
PUBLIC void
r_util_charbuf_release(struct ccnr_handle * h, struct ccn_charbuf * c);
PUBLIC struct ccn_indexbuf *
r_util_indexbuf_obtain(struct ccnr_handle * h);
PUBLIC void
r_util_indexbuf_release(struct ccnr_handle * h, struct ccn_indexbuf * c);
PUBLIC struct fdholder *
r_io_fdholder_from_fd(struct ccnr_handle * h, unsigned filedesc);
PUBLIC int
r_io_enroll_face(struct ccnr_handle * h, struct fdholder * fdholder);
PUBLIC void
r_sendq_content_queue_destroy(struct ccnr_handle * h, struct content_queue ** pq);
PUBLIC struct content_entry *
r_store_content_from_accession(struct ccnr_handle * h, ccn_accession_t accession);
PUBLIC void
r_store_enroll_content(struct ccnr_handle * h, struct content_entry * content);
PUBLIC void
r_store_finalize_content(struct hashtb_enumerator *content_enumerator);
PUBLIC void
r_store_content_skiplist_insert(struct ccnr_handle * h, struct content_entry * content);
PUBLIC struct content_entry *
r_store_find_first_match_candidate(struct ccnr_handle * h,
						   const unsigned char *interest_msg,
						   const struct ccn_parsed_interest * pi);
PUBLIC int
r_store_content_matches_interest_prefix(struct ccnr_handle * h,
								struct content_entry * content,
								const unsigned char *interest_msg,
								struct ccn_indexbuf * comps,
								int prefix_comps);
PUBLIC          ccn_accession_t
r_store_content_skiplist_next(struct ccnr_handle * h, struct content_entry * content);
PUBLIC void
r_match_consume_interest(struct ccnr_handle * h, struct propagating_entry * pe);
PUBLIC void
r_fwd_finalize_nameprefix(struct hashtb_enumerator * e);
PUBLIC void
r_fwd_finalize_propagating(struct hashtb_enumerator * e);
PUBLIC struct fdholder *
r_io_record_connection(struct ccnr_handle * h, int fd,
				  struct sockaddr * who, socklen_t wholen,
				  int setflags);
PUBLIC int
r_io_accept_connection(struct ccnr_handle * h, int listener_fd);
PUBLIC void
r_io_shutdown_client_fd(struct ccnr_handle * h, int fd);
PUBLIC void
r_link_send_content(struct ccnr_handle * h, struct fdholder * fdholder, struct content_entry * content);
PUBLIC int
r_sendq_face_send_queue_insert(struct ccnr_handle * h,
					   struct fdholder * fdholder, struct content_entry * content);
PUBLIC int
r_match_consume_matching_interests(struct ccnr_handle * h,
						   struct nameprefix_entry * npe,
						   struct content_entry * content,
						   struct ccn_parsed_ContentObject * pc,
						   struct fdholder * fdholder);
PUBLIC void
r_fwd_adjust_npe_predicted_response(struct ccnr_handle * h,
							  struct nameprefix_entry * npe, int up);
PUBLIC int
r_match_match_interests(struct ccnr_handle * h, struct content_entry * content,
				struct ccn_parsed_ContentObject * pc,
				struct fdholder * fdholder, struct fdholder * from_face);
PUBLIC void
r_link_stuff_and_send(struct ccnr_handle * h, struct fdholder * fdholder,
			   const unsigned char *data1, size_t size1,
			   const unsigned char *data2, size_t size2);
PUBLIC void
r_link_ccn_link_state_init(struct ccnr_handle * h, struct fdholder * fdholder);
PUBLIC int
r_link_process_incoming_link_message(struct ccnr_handle * h,
							  struct fdholder * fdholder, enum ccn_dtag dtag,
							  unsigned char *msg, size_t size);
PUBLIC void
r_fwd_reap_needed(struct ccnr_handle * h, int init_delay_usec);
PUBLIC int
r_store_remove_content(struct ccnr_handle * h, struct content_entry * content);
PUBLIC void
r_fwd_age_forwarding_needed(struct ccnr_handle * h);
PUBLIC void
r_io_register_new_face(struct ccnr_handle * h, struct fdholder * fdholder);
PUBLIC void
r_fwd_update_forward_to(struct ccnr_handle * h, struct nameprefix_entry * npe);
PUBLIC void
r_fwd_append_debug_nonce(struct ccnr_handle * h, struct fdholder * fdholder, struct ccn_charbuf * cb);
PUBLIC void
r_fwd_append_plain_nonce(struct ccnr_handle * h, struct fdholder * fdholder, struct ccn_charbuf * cb);
PUBLIC int
r_fwd_propagate_interest(struct ccnr_handle * h,
				   struct fdholder * fdholder,
				   unsigned char *msg,
				   struct ccn_parsed_interest * pi,
				   struct nameprefix_entry * npe);
PUBLIC int
r_fwd_is_duplicate_flooded(struct ccnr_handle * h, unsigned char *msg,
					 struct ccn_parsed_interest * pi, unsigned filedesc);
PUBLIC int
r_fwd_nameprefix_seek(struct ccnr_handle * h, struct hashtb_enumerator * e,
				const unsigned char *msg, struct ccn_indexbuf * comps, int ncomps);
PUBLIC struct content_entry *
r_store_next_child_at_level(struct ccnr_handle * h,
					struct content_entry * content, int level);
PUBLIC void
r_store_mark_stale(struct ccnr_handle * h, struct content_entry * content);
PUBLIC void
r_store_set_content_timer(struct ccnr_handle * h, struct content_entry * content,
				  struct ccn_parsed_ContentObject * pco);
PUBLIC void
r_dispatch_process_internal_client_buffer(struct ccnr_handle * h);
PUBLIC void
r_link_do_deferred_write(struct ccnr_handle * h, int fd);
PUBLIC void
r_io_prepare_poll_fds(struct ccnr_handle * h);
PUBLIC void
r_util_reseed(struct ccnr_handle * h);
PUBLIC char    *
r_net_get_local_sockname(void);
PUBLIC void
r_util_gettime(const struct ccn_gettime * self, struct ccn_timeval * result);
PUBLIC int
r_net_listen_on_wildcards(struct ccnr_handle * h);
PUBLIC int
r_net_listen_on_address(struct ccnr_handle * h, const char *addr);
PUBLIC int
r_net_listen_on(struct ccnr_handle * h, const char *addrs);
PUBLIC void
r_io_shutdown_all(struct ccnr_handle * h);

