#include <string.h>
#include <ccn/ccn.h>
#include <ccn/charbuf.h>
#include <ccn/bloom.h>
#include <ccn/coding.h>

int
ccn_content_matches_interest(const unsigned char *content_object,
                                 size_t content_object_size,
                                 const struct ccn_parsed_ContentObject *pc,
                                 const unsigned char *interest_msg,
                                 size_t interest_msg_size,
                                 const struct ccn_parsed_interest *pi)
{
    struct ccn_parsed_ContentObject pc_store;
    struct ccn_parsed_interest pi_store;
    int res;
    int prefixstart;
    int prefixbytes;
    int namecompstart;
    int namecompbytes;
    if (pc == NULL) {
        res = ccn_parse_ContentObject(content_object, content_object_size,
                                      &pc_store, NULL);
        if (res < 0) return(0);
        pc = &pc_store;
    }
    if (pi == NULL) {
        res = ccn_parse_interest(interest_msg, interest_msg_size, &pi_store, NULL);
        if (res < 0) return(0);
        pi = &pi_store;
    }
    prefixstart = pi->offset[CCN_PI_B_Component0];
    prefixbytes = pi->offset[CCN_PI_E_ComponentLast] - prefixstart;
    namecompstart = pc->offset[CCN_PCO_B_Component0];
    namecompbytes = pc->offset[CCN_PCO_E_ComponentN] - namecompstart;
    // XXX - fixthis - does not deal with the implicit content digest name component
    if (namecompbytes <= prefixbytes)
        return(0);
    if (0 != memcmp(interest_msg + prefixstart, content_object + namecompstart,
                    prefixbytes))
        return(0);
    /* Prefix matches, check the other parts */
    // XXX - NYI
    return(1);
}

