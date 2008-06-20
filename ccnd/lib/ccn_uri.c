#include <string.h>
#include <ccn/ccn.h>
#include <ccn/charbuf.h>
#include <ccn/coding.h>

/*********
RFC 3986                   URI Generic Syntax               January 2005


      reserved    = gen-delims / sub-delims

      gen-delims  = ":" / "/" / "?" / "#" / "[" / "]" / "@"

      sub-delims  = "!" / "$" / "&" / "'" / "(" / ")"
                  / "*" / "+" / "," / ";" / "="
...
      unreserved  = ALPHA / DIGIT / "-" / "." / "_" / "~"

*********/

void
ccn_uri_append_comp(struct ccn_charbuf *c, const unsigned char *data, size_t size)
{
    size_t i;
    unsigned char ch;
    for (i = 0; i < size && data[i] == '.'; i++)
        continue;
    /* For a component that consists solely of zero or more dots, add 3 more */
    if (i == size)
        ccn_charbuf_append(c, "...", 3);
    for (i = 0; i < size; i++) {
        ch = data[i];
        /*
         * Leave unescaped only the generic URI unreserved characters.
         * See RFC 3986. Here we assume the compiler uses ASCII.
         */
        if (('a' <= ch && ch <= 'z') ||
            ('A' <= ch && ch <= 'Z') ||
            ('0' <= ch && ch <= '9') ||
            ch == '-' || ch == '.' || ch == '_' || ch == '~')
            ccn_charbuf_append(c, &(data[i]), 1);
        else
            ccn_charbuf_putf(c, "%%%02X", (unsigned)ch);
    }
}

/*
 * ccn_uri_append:
 * This appends to c a URI representation of the ccnb-encoded Name element
 * passed in.  For convenience, it will also look inside of a ContentObject
 * or Interest object to find the Name.
 * Components that consist of solely of zero or more dots are converted
 * by adding 3 more dots so there are no ambiguities with . or .. or whether
 * a component is empty or absent.
 */

int
ccn_uri_append(struct ccn_charbuf *c, const unsigned char *ccnb, size_t size)
{
    int ncomp = 0;
    const unsigned char *comp = NULL;
    size_t compsize = 0;
    struct ccn_buf_decoder decoder;
    struct ccn_buf_decoder *d = ccn_buf_decoder_start(&decoder, ccnb, size);
    if (ccn_buf_match_dtag(d, CCN_DTAG_Interest) ||
        ccn_buf_match_dtag(d, CCN_DTAG_ContentObject))
        ccn_buf_advance(d);
    if (!ccn_buf_match_dtag(d, CCN_DTAG_Name))
        return(-1);
    ccn_buf_advance(d);
    while (ccn_buf_match_dtag(d, CCN_DTAG_Component)) {
        ccn_buf_advance(d);
        compsize = 0;
        if (ccn_buf_match_blob(d, &comp, &compsize))
            ccn_buf_advance(d);
        ccn_buf_check_close(d);
        if (d->decoder.state < 0)
            return(d->decoder.state);
        ncomp += 1;
        ccn_charbuf_append(c, "/", 1);
        ccn_uri_append_comp(c, comp, compsize);
    }
    ccn_buf_check_close(d);
    if (d->decoder.state < 0)
        return (d->decoder.state);
    return(ncomp);
}

static int
hexit(int c)
{
    if ('0' <= c && c <= '9')
        return(c - '0');
    if ('A' <= c && c <= 'F')
        return(c - 'A' + 10);
    if ('a' <= c && c <= 'f')
        return(c - 'a' + 10);
    return(-1);
}

/*
 * ccn_append_uri_component:
 * This takes as input the escaped URI component at s and appends it
 * to c.  This does not do any ccnb-related stuff.
 * Processing stops at an error or if an unescaped nul, '/', '?', or '#' is found.
 * A component that consists solely of dots gets special treatment to reverse
 * the addition of ... by ccn_uri_append_comp.
 * A positive return value indicates there were unescaped reserved or
 * non-printable characters found.  This might warrant some extra checking
 * by the caller.
 * A return value of -1 indicates the component was "..", so the caller
 * will need to do something extra to handle this as appropriate.
 * A return value of -1 indicates a bad %-escaped sequence.
 * If cont is not NULL, *cont is set to the number of input characters processed.
 * 
 */
int
ccn_append_uri_component(struct ccn_charbuf *c, const char *s, size_t limit, size_t *cont)
{
    size_t start = c->length;
    size_t i;
    int err = 0;
    int d1, d2;
    unsigned char ch;
    for (i = 0; i < limit; i++) {
        ch = s[i];
        switch (ch) {
            case 0:
            case '/':
            case '?':
            case '#':
                limit = i;
                break;
            case '%':
                if (i + 3 > limit || (d1 = hexit(s[i+1])) < 0 ||
                                     (d2 = hexit(s[i+2])) < 0   ) {
                    limit = i;
                    err = -2;
                    break;
                }
                ch = d1 * 16 + d2;
                i += 2;
                ccn_charbuf_append(c, &ch, 1);
                break;
            case ':': case '[': case ']': case '@':
            case '!': case '$': case '&': case '\'': case '(': case ')':
            case '*': case '+': case ',': case ';': case '=':
                err++;
                /* FALLTHROUGH */
            default:
                if (ch <= ' ' || ch > '~')
                    err++;
                ccn_charbuf_append(c, &ch, 1);
                break;
        }
    }
    for (i = start; i < c->length && c->buf[i] == '.'; i++)
        continue;
    if (i == c->length) {
        /* all dots */
        i -= start;
        if (i <= 1)
            c->length = start;
        else if (i == 2) {
            c->length = start;
            err = -1;
        }
        else
            c->length -= 3;
    }
    if (cont != NULL)
        *cont = limit;
    return(err);
}
