/**
 * @file ccn_ccnbtoxml.c
 * Utility to convert ccn binary encoded data into XML form.
 *
 * A CCNx command-line utility.
 *
 * Copyright (C) 2008-2010, 2013 Palo Alto Research Center, Inc.
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

#include <fcntl.h>
#include <limits.h>
#include <stddef.h>
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <ctype.h>

#include <ccn/charbuf.h>
#include <ccn/coding.h>
#include <ccn/extend_dict.h>

static void
usage(const char *progname)
{
    fprintf(stderr,
            "usage: %s [-h] [-b] [-t] [-v] [-x] [-s prefix] [-d dict]* file ...\n"
            " Utility to convert ccn binary encoded data into XML form.\n"
            "  -b      force (base64 or hex) Binary output instead of text\n"
            "  -t      test, when specified, should be only switch\n"
            "  -v      verbose - do extra decoding as comments\n"
            "  -x      prefer hex output to base64\n"
            "  -s pat  provide a single pattern to be used when "
            "splitting one or more input files\n"
            "  -d dict use this option one or more times to specify additional\n"
            "          csv format dictionary files that extend the builtin dtag table\n"
            " switches may not be mixed with file name arguments\n"
            " use - for file to specify stdin\n"
            " in absence of -s option, result is on stdout\n",
            progname);
    exit(1);
}

#define CCN_NO_SCHEMA INT_MIN
#define CCN_UNKNOWN_SCHEMA (INT_MIN+1)

struct ccn_decoder_stack_item {
    size_t nameindex; /* byte index into stringstack */
    size_t savedss;
    int saved_schema;
    int saved_schema_state;
    struct ccn_decoder_stack_item *link;
};

struct ccn_decoder;
enum callback_kind {
    CALLBACK_INITIAL,
    CALLBACK_OBJECTEND,
    CALLBACK_FINAL
};

typedef void (*ccn_decoder_callback)(
    struct ccn_decoder *d,
    enum callback_kind kind,
    void *data
);

struct ccn_decoder {
    int state;
    int tagstate;
    int bits;
    size_t numval;
    uintmax_t bignumval;
    int schema;
    int sstate;
    struct ccn_decoder_stack_item *stack;
    struct ccn_charbuf *stringstack;
    const struct ccn_dict_entry *tagdict;
    int tagdict_count;
    ccn_decoder_callback callback;
    void *callbackdata;
    int formatting_flags;
    int base64_char_count;
    struct ccn_charbuf *annotation;
};
/* formatting_flags */
#define FORCE_BINARY   (1 << 0)
#define PREFER_HEX     (1 << 1)
#define VERBOSE_DECODE (1 << 2)

struct ccn_decoder *
ccn_decoder_create(int formatting_flags, const struct ccn_dict *dtags)
{
    struct ccn_decoder *d;
    d = calloc(1, sizeof(*d));
    d->stringstack = ccn_charbuf_create();
    if (d->stringstack == NULL) {
        free(d);
        d = NULL;
        return(NULL);
    }
    d->schema = CCN_NO_SCHEMA;
    d->tagdict = dtags->dict;
    d->tagdict_count = dtags->count;
    d->formatting_flags = formatting_flags;
    d->annotation = NULL;
    return(d);
}

void
ccn_decoder_set_callback(struct ccn_decoder *d, ccn_decoder_callback c, void *data)
{
    d->callback = c;
    if (c == NULL) {
        d->callbackdata = NULL;
    } else {
        d->callbackdata = data;
        c(d, CALLBACK_INITIAL, data);
    }
}

struct ccn_decoder_stack_item *
ccn_decoder_push(struct ccn_decoder *d)
{
    struct ccn_decoder_stack_item *s;
    s = calloc(1, sizeof(*s));
    if (s != NULL) {
        s->link = d->stack;
        s->savedss = d->stringstack->length;
        s->saved_schema = d->schema;
        s->saved_schema_state = d->sstate;
        d->stack = s;
    }
    return(s);
}

void
ccn_decoder_pop(struct ccn_decoder *d)
{
    struct ccn_decoder_stack_item *s = d->stack;
    if (s != NULL) {
        d->stack = s->link;
        d->stringstack->length = s->savedss;
        d->schema = s->saved_schema;
        d->sstate = s->saved_schema_state;
        free(s);
    }
}

void
ccn_decoder_destroy(struct ccn_decoder **dp)
{
    struct ccn_decoder *d = *dp;
    if (d != NULL) {
        if (d->callback != NULL) {
            d->callback(d, CALLBACK_FINAL, d->callbackdata);
        }
        while (d->stack != NULL) {
            ccn_decoder_pop(d);
        }
        ccn_charbuf_destroy(&(d->stringstack));
        free(d);
        *dp = NULL;
    }
}

static const char *
dict_name_from_number(int ndx, const struct ccn_dict_entry *dict, int n)
{
    int i;
    for (i = 0; i < n; i++)
        if (ndx == dict[i].index)
            return (dict[i].name);
    return (NULL);
}

static const char Base64[] =
  "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

static int
is_text_encodable(unsigned char p[], size_t start, size_t length)
{
    size_t i;

    if (length == 0) return (0);
    for (i = 0; i < length; i++) {
        char c = p[start + i];
        if (c < ' ' || c > '~') return (0);
        if (c == '<' || c == '>' || c == '&') return (0);
    }
    return (1);
}

/* c.f. ccn_uri_append_percentescaped */
static void
print_percent_escaped(const unsigned char *data, size_t size)
{
    size_t i;
    unsigned char ch;
    for (i = 0; i < size && data[i] == '.'; i++)
        continue;
    /* For a component that consists solely of zero or more dots, add 3 more */
    if (i == size)
        printf("...");
    for (i = 0; i < size; i++) {
        ch = data[i];
        /*
         * Leave unescaped only the generic URI unreserved characters.
         * See RFC 3986. Here we assume the compiler uses ASCII.
         * Note: "--" is not legal in an XML comment, we'll percent-escape
         * any after the first without an intervening non-hyphen.
         */
        if (('a' <= ch && ch <= 'z') ||
            ('A' <= ch && ch <= 'Z') ||
            ('0' <= ch && ch <= '9') ||
            (ch == '-' && ! (i > 0 && data[i - 1] == '-'))||
            ch == '.' || ch == '_' || ch == '~')
            printf("%c", ch);
        else
            printf("%%%02X", (unsigned)ch);
    }
}

size_t
ccn_decoder_decode(struct ccn_decoder *d, unsigned char p[], size_t n)
{
    int state = d->state;
    int tagstate = 0;
    size_t numval = d->numval;
    size_t i = 0;
    unsigned char c;
    size_t chunk;
    struct ccn_decoder_stack_item *s;
    const char *tagname;
    while (i < n) {
        switch (state) {
            case 0: /* start new thing */
                if (tagstate > 1 && tagstate-- == 2) {
                    printf("\""); /* close off the attribute value */
                    ccn_decoder_pop(d);
                } 
                if (p[i] == CCN_CLOSE) {
                    i++;
                    s = d->stack;
                    if (s == NULL || tagstate > 1) {
                        state = -__LINE__;
                        break;
                    }
                    if (tagstate == 1) {
                        tagstate = 0;
                        printf("/>");
                    }
                    else if (d->schema == -1-CCN_PROCESSING_INSTRUCTIONS) {
                        printf("?>");
                        if (d->sstate != 2) {
                            state = -__LINE__;
                            break;
                        }
                    }
                    else {
                        printf("</%s>", d->stringstack->buf + s->nameindex);
                    }
                    if (d->annotation != NULL) {
                        if (d->annotation->length > 0) {
                            printf("<!--       ");
                            print_percent_escaped(d->annotation->buf, d->annotation->length);
                            printf(" -->");
                        }
                        ccn_charbuf_destroy(&d->annotation);
                    }
                    ccn_decoder_pop(d);
                    if (d->stack == NULL) {
                        if (d->callback != NULL)
                            d->callback(d, CALLBACK_OBJECTEND, d->callbackdata);
                        else
                            printf("\n");
                    }
                    break;
                }
                numval = 0;
                state = 1;
                /* FALLTHRU */
            case 1: /* parsing numval */
                c = p[i++];
                if ((c & CCN_TT_HBIT) == CCN_CLOSE) {
                    if (numval > (numval << 7)) {
                        state = 9;
                        d->bignumval = numval;
                        i--;
                        continue;
                    }
                    numval = (numval << 7) + (c & 127);
                    if (numval > (numval << (7-CCN_TT_BITS))) {
                        state = 9;
                        d->bignumval = numval;
                    }
                }
                else {
                    numval = (numval << (7-CCN_TT_BITS)) +
                             ((c >> CCN_TT_BITS) & CCN_MAX_TINY);
                    c &= CCN_TT_MASK;
                    switch (c) {
                        case CCN_EXT:
                            if (tagstate == 1) {
                                tagstate = 0;
                                printf(">");
                            }
                            s = ccn_decoder_push(d);
                            s->nameindex = d->stringstack->length;
                            d->schema = -1-numval;
                            d->sstate = 0;
                            switch (numval) {
                                case CCN_PROCESSING_INSTRUCTIONS:
                                    printf("<?");
                                    break;
                                default:
                                    state = -__LINE__;
                            }
                            state = 0;
                            break;
                        case CCN_DTAG:
                            if (tagstate == 1) {
                                tagstate = 0;
                                printf(">");
                            }
                            s = ccn_decoder_push(d);
                            s->nameindex = d->stringstack->length;
                            d->schema = numval;
                            d->sstate = 0;
                            tagname = NULL;
                            if (numval <= INT_MAX)
                                tagname = dict_name_from_number(numval, d->tagdict, d->tagdict_count);
                            if (tagname == NULL) {
                                fprintf(stderr,
                                        "*** Warning: unrecognized DTAG %lu\n",
                                        (unsigned long)numval);
                                ccn_charbuf_append(d->stringstack,
                                                   "UNKNOWN_DTAG",
                                                   sizeof("UNKNOWN_DTAG"));
                                printf("<%s code=\"%lu\"",
                                       d->stringstack->buf + s->nameindex,
                                       (unsigned long)d->schema);
                                d->schema = CCN_UNKNOWN_SCHEMA;
                            }
                            else {
                                ccn_charbuf_append(d->stringstack, tagname, strlen(tagname)+1);
                                printf("<%s", tagname);
                            }
                            if ((d->formatting_flags & VERBOSE_DECODE) != 0) {
                                if (d->annotation != NULL)
                                    abort();
                                if (numval == 15 /* Component */)
                                    d->annotation = ccn_charbuf_create();
                            }
                            tagstate = 1;
                            state = 0;
                            break;
                        case CCN_BLOB:
                            if (numval > n - i) {
                                state = -__LINE__;
                                break;
                            }                                                        
                            if (tagstate == 1) {
                                tagstate = 0;
                                if ((d->formatting_flags & FORCE_BINARY) == 0 && is_text_encodable(p, i, numval)) {
                                    printf(" ccnbencoding=\"text\">");
                                    state =  6;
                                }
                                else if ((d->formatting_flags & PREFER_HEX) != 0) {
                                    printf(" ccnbencoding=\"hexBinary\">");
                                    state = 2;
                                }
                                else {
                                    printf(" ccnbencoding=\"base64Binary\">");
                                    state = 10;
                                }
                            }
                            else {
                                fprintf(stderr, "blob not tagged in xml output\n");
                                state = 10;
                            }
                            state = (numval == 0) ? 0 : state;
                            d->base64_char_count = 0;
                            break;
                        case CCN_UDATA:
                            if (tagstate == 1) {
                                tagstate = 0;
                                printf(">");
                            }
                            state = 3;
                            if (d->schema == -1-CCN_PROCESSING_INSTRUCTIONS) {
                                if (d->sstate > 0) {
                                    printf(" ");
                                }
                                state = 6;
                                d->sstate += 1;
                            }
                            if (numval == 0)
                                state = 0;
                            break;
                        case CCN_DATTR:
                            if (tagstate != 1) {
                                state = -__LINE__;
                                break;
                            }
                            s = ccn_decoder_push(d);
                            ccn_charbuf_reserve(d->stringstack, 1);
                            s->nameindex = d->stringstack->length;
                            printf(" UNKNOWN_DATTR_%lu=\"", (unsigned long)numval);
                            tagstate = 3;
                            state = 0;
                            break;
                        case CCN_ATTR:
                            if (tagstate != 1) {
                                state = -__LINE__;
                                break;
                            }
                            if (numval >= n - i) {
                                state = -__LINE__;
                                break;
                            }                            
                            numval += 1; /* encoded as length-1 */
                            s = ccn_decoder_push(d);
                            ccn_charbuf_reserve(d->stringstack, numval + 1);
                            s->nameindex = d->stringstack->length;
                            state = 5;
                            break;
                        case CCN_TAG:
                            if (tagstate == 1) {
                                tagstate = 0;
                                printf(">");
                            }
                            if (numval >= n - i) {
                                state = -__LINE__;
                                break;
                            }                                                        
                            numval += 1; /* encoded as length-1 */
                            s = ccn_decoder_push(d);
                            ccn_charbuf_reserve(d->stringstack, numval + 1);
                            s->nameindex = d->stringstack->length;
                            state = 4;
                            break;
                        default:
                            state = -__LINE__;
                    }
                }
                break;
            case 2: /* hex BLOB */
                c = p[i++];
                if (d->annotation != NULL)
                    ccn_charbuf_append_value(d->annotation, c, 1);
                printf("%02X", c);
                if (--numval == 0) {
                    state = 0;
                }
                break;
            case 3: /* utf-8 data */
                c = p[i++];
                if (--numval == 0) {
                    state = 0;
                }
                switch (c) {
                    case 0:
                        state = -__LINE__;
                        break;
                    case '&':
                        printf("&amp;");
                        break;
                    case '<':
                        printf("&lt;");
                        break;
                    case '>':
                        printf("&gt;");
                        break;
                    case '"':
                        printf("&quot;");
                        break;
                    default:
                        printf("%c", c);
                }
                break;
            case 4: /* parsing tag name */
            case 5: /* parsing attribute name */
                chunk = n - i;
                if (chunk > numval) {
                    chunk = numval;
                }
                if (chunk == 0) {
                    state = -__LINE__;
                    break;
                }
                ccn_charbuf_append(d->stringstack, p + i, chunk);
                numval -= chunk;
                i += chunk;
                if (numval == 0) {
                    ccn_charbuf_append(d->stringstack, (const unsigned char *)"\0", 1);
                    s = d->stack;
                    if (s == NULL ||
                        strlen((char*)d->stringstack->buf + s->nameindex) != 
                            d->stringstack->length -1 - s->nameindex) {
                        state = -__LINE__;
                        break;
                    }
                    if (state == 4) {
                        printf("<%s", d->stringstack->buf + s->nameindex);
                        tagstate = 1;
                    }
                    else {
                        printf(" %s=\"", d->stringstack->buf + s->nameindex);
                        tagstate = 3;
                    }
                    state = 0;
                }
                break;
            case 6: /* processing instructions and text blobs */
                c = p[i++];
                if (--numval == 0) {
                    state = 0;
                }
                printf("%c", c);
                break;
            case 9: /* parsing big numval - cannot be a length anymore */
                c = p[i++];
                if ((c & CCN_TT_HBIT) == CCN_CLOSE) {
                    d->bignumval = (d->bignumval << 7) + (c & 127);
                }
                else {
                    d->bignumval = (d->bignumval << (7-CCN_TT_BITS)) +
                                   ((c >> CCN_TT_BITS) & CCN_MAX_TINY);
                    c &= CCN_TT_MASK;
                    if (tagstate == 1) {
                        tagstate = 0;
                        printf(">");
                    }
                    /*
                     * There's nothing that we actually need the bignumval
                     * for, so we can probably GC this whole state and
                     * give up earlier.
                     */
                    switch (c) {
                        default:
                            state = -__LINE__;
                    }
                }
                break;
            case 10: /* base 64 BLOB - phase 0 */
                c = p[i++];
                if (d->annotation != NULL)
                    ccn_charbuf_append_value(d->annotation, c, 1);
                printf("%c", Base64[c >> 2]);
                d->base64_char_count++;
                if (--numval == 0) {
                    printf("%c==", Base64[(c & 3) << 4]);
                    state = 0;
                    d->base64_char_count += 3;
                }
                else {
                    d->bits = (c & 3);
                    state = 11;
                }
                if ((d->formatting_flags & FORCE_BINARY) == 0 && d->base64_char_count >= 64) {
                    d->base64_char_count = 0;
                    printf("\n");
                }
                break;
            case 11: /* base 64 BLOB - phase 1 */
                c = p[i++];
                if (d->annotation != NULL)
                    ccn_charbuf_append_value(d->annotation, c, 1);
                printf("%c", Base64[((d->bits & 3) << 4) + (c >> 4)]);
                d->base64_char_count++;
                if (--numval == 0) {
                    printf("%c=", Base64[(c & 0xF) << 2]);
                    state = 0;
                    d->base64_char_count += 2;
                }
                else {
                    d->bits = (c & 0xF);
                    state = 12;
                }
                if ((d->formatting_flags & FORCE_BINARY) == 0 && d->base64_char_count >= 64) {
                    d->base64_char_count = 0;
                    printf("\n");
                }
                break;
            case 12: /* base 64 BLOB - phase 2 */
                c = p[i++];
                if (d->annotation != NULL)
                    ccn_charbuf_append_value(d->annotation, c, 1);
                printf("%c%c", Base64[((d->bits & 0xF) << 2) + (c >> 6)],
                               Base64[c & 0x3F]);
                d->base64_char_count += 2;
                if (--numval == 0) {
                    state = 0;
                }
                else {
                    state = 10;
                }
                if ((d->formatting_flags & FORCE_BINARY) == 0 && d->base64_char_count >= 64) {
                    d->base64_char_count = 0;
                    printf("\n");
                }
                break;
            default:
                n = i;
        }
    }
    d->state = state;
    d->tagstate = tagstate;
    d->numval = numval;
    return(i);
}

static int
process_data(struct ccn_decoder *d, unsigned char *data, size_t n)
{
    int res = 0;
    size_t s;
    printf("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    s = ccn_decoder_decode(d, data, n);
    if (d->state != 0 || s < n || d->stack != NULL || d->tagstate != 0) {
        res = 1;
        fprintf(stderr, "error state %d after %d of %d chars\n",
            (int)d->state, (int)s, (int)n);
    }
    return(res);
}

static int
process_fd(struct ccn_decoder *d, int fd)
{
    struct ccn_charbuf *c = ccn_charbuf_create();
    ssize_t len;
    int res = 0;
    for (;;) {
        unsigned char *p = ccn_charbuf_reserve(c, 80);
        if (p == NULL) {
            perror("ccn_charbuf_reserve");
            res = 1;
            break;
        }
        len = read(fd, p, c->limit - c->length);
        if (len <= 0) {
            if (len == -1) {
                perror("read");
                res = 1;
            }
            break;
        }
        c->length += len;
    }
    fprintf(stderr, " <!-- input is %6lu bytes -->\n", (unsigned long)c->length);
    res |= process_data(d, c->buf, c->length);
    ccn_charbuf_destroy(&c);
    return(res);
}


static int
process_file(char *path, int formatting_flags, const struct ccn_dict *dtags)
{
    int fd = 0;
    int res = 0;
    struct ccn_decoder *d;

    if (0 != strcmp(path, "-")) {
        fd = open(path, O_RDONLY);
        if (-1 == fd) {
            perror(path);
            return(1);
        }
    }

    d = ccn_decoder_create(formatting_flags, dtags);
    if (d == NULL) {
        fprintf(stderr, "Unable to allocate decoder\n");
        return(1);
    }
    res = process_fd(d, fd);
    ccn_decoder_destroy(&d);

    if (fd > 0)
        close(fd);
    return(res);
}

struct callback_state {
    int fragment;
    char *fileprefix;
};

static void
set_stdout(struct ccn_decoder *d, enum callback_kind kind, void *data)
{
    struct callback_state *cs = (struct callback_state *)data;
    char filename[256];
    FILE *fp;
    switch (kind) {
    case CALLBACK_INITIAL:
    case CALLBACK_OBJECTEND:
        snprintf(filename, sizeof(filename), "%s%05d.xml", cs->fileprefix, cs->fragment++);
        fprintf(stderr, " <!-- attaching stdout to %s --!>\n", filename);
        fp = freopen(filename, "w+", stdout);
        if (fp == NULL)
            perror(filename);
        break;
    case CALLBACK_FINAL:
        fflush(stdout);
        fclose(stdout);
        free(cs);
        break;
    }
}

static int
process_split_file(char *base, char *path, int formatting_flags,
                   const struct ccn_dict *dtags, int *suffix)
{
    int fd = 0;
    int res = 0;
    struct ccn_decoder *d;
    struct callback_state *cs;

    if (0 != strcmp(path, "-")) {
        fd = open(path, O_RDONLY);
        if (-1 == fd) {
            perror(path);
            return(1);
        }
    }
    
    cs = calloc(1, sizeof(*cs));
    cs->fileprefix = base;
    cs->fragment = *suffix;
    d = ccn_decoder_create(formatting_flags, dtags);
    if (d == NULL) {
        fprintf(stderr, "Unable to allocate decoder\n");
        return(1);
    }
    ccn_decoder_set_callback(d, set_stdout, cs);
    res = process_fd(d, fd);
    *suffix = cs->fragment;
    ccn_decoder_destroy(&d);
    if (fd > 0)
        close(fd);
    return(res);
}

#define L (CCN_TT_HBIT & ~CCN_CLOSE)
unsigned char test1[] = {
    (2 << CCN_TT_BITS) + CCN_TAG + L, 'F', 'o', 'o',
    (0 << CCN_TT_BITS) + CCN_TAG + L, 'a',
    (1 << CCN_TT_BITS) + CCN_UDATA + L, 'X',
               CCN_CLOSE,
    (0 << CCN_TT_BITS) + CCN_TAG + L, 'b',
    (3 << CCN_TT_BITS) + CCN_ATTR + L, 't', 'y', 'p', 'e',
    (5 << CCN_TT_BITS) + CCN_UDATA + L, 'e', 'm', 'p', 't', 'y',
               CCN_CLOSE,
    (2 << CCN_TT_BITS) + CCN_TAG + L, 'b', 'i', 'n',
    (4 << CCN_TT_BITS) + CCN_BLOB + L, 1, 0x23, 0x45, 0x67,
               CCN_CLOSE,
    (CCN_CLOSE + ((20-1) >> (7-CCN_TT_BITS))),
    (((20-1) & CCN_TT_MASK) << CCN_TT_BITS) + CCN_TAG + L,
        'a', 'b', 'c', 'd',  'a', 'b', 'c', 'd', 
        'a', 'b', 'c', 'd',  'a', 'b', 'c', 'd',
        'a', 'b', 'c', 'd',
               CCN_CLOSE,
    (2 << CCN_TT_BITS) + CCN_TAG + L, 'i', 'n', 't',
    (3 << CCN_TT_BITS) + CCN_ATTR + L, 't', 'y', 'p', 'e',
    (3 << CCN_TT_BITS) + CCN_UDATA + L, 'B', 'I', 'G',
               CCN_CLOSE,
    (6 << CCN_TT_BITS) + CCN_UDATA + L,
    'H','i','&','b','y','e',
               CCN_CLOSE,
};

int
main(int argc, char **argv)
{
    extern char *optarg;
    extern int optind, optopt;
    int opt;
    int tflag = 0, formatting_flags = 0, errflag = 0;
    char *sarg = NULL;
    int res = 0;
    int suffix = 0;
    struct ccn_decoder *d;
    struct ccn_dict *dtags = (struct ccn_dict *)&ccn_dtag_dict;

    while ((opt = getopt(argc, argv, ":hbd:s:tvx")) != -1) {
        switch (opt) {
            case 'h':
                usage(argv[0]);
                break;
            case 'b':
                formatting_flags |= FORCE_BINARY;
                break;
            case 'd':
                if (0 != ccn_extend_dict(optarg, dtags, &dtags)) {
                    fprintf(stderr, "Unable to load dtag dictionary %s\n", optarg);
                    errflag = 1;
                }
                break;
            case 's':
                sarg = optarg;
                break;
            case 't':
                tflag = 1;
                break;
            case 'v':
                formatting_flags |= VERBOSE_DECODE;
                break;
            case 'x':
                formatting_flags |= PREFER_HEX;
                break;
            case '?':
                fprintf(stderr, "Unrecognized option: -%c\n", optopt);
                errflag = 1;
        }
    }
    if (tflag && (sarg != NULL || formatting_flags != 0))
        errflag = 1;

    if (errflag || (tflag && (optind < argc)))
        usage(argv[0]);
    
    if (tflag) {
        d = ccn_decoder_create(1, &ccn_dtag_dict);
        if (d == NULL) {
            fprintf(stderr, "Unable to allocate decoder\n");
            exit(1);
        }
        res |= process_data(d, test1, sizeof(test1));
        ccn_decoder_destroy(&d);
        return (res);
    }
    
    for (suffix = 0; optind < argc; optind++) {
        if (sarg) {
            fprintf(stderr, "<!-- Processing %s into %s -->\n", argv[optind], sarg);
            res |= process_split_file(sarg, argv[optind], formatting_flags,
                                      dtags, &suffix);
        }
        else {
            fprintf(stderr, "<!-- Processing %s -->\n", argv[optind]);
            res |= process_file(argv[optind], formatting_flags, dtags);
        }
    }
    return(res);
}

