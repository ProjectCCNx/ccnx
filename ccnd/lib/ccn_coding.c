#include <fcntl.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <ccn/coding.h>

#include <ccn/charbuf.h>

struct ccn_decoder_stack_item {
    size_t nameindex; /* byte index into stringstack */
    size_t itemcount;
    size_t savedss;
    struct ccn_decoder_stack_item *link;
};

struct ccn_decoder {
    int state;
    int tagstate;
    int bits;
    size_t numval;
    struct ccn_decoder_stack_item *stack;
    struct ccn_charbuf *stringstack;
};

struct ccn_decoder *
ccn_decoder_create(void)
{
    struct ccn_decoder *d;
    d = calloc(1, sizeof(*d));
    d->stringstack = ccn_charbuf_create();
    if (d->stringstack == NULL) {
        free(d);
        d = NULL;
    }
    return(d);
}

struct ccn_decoder_stack_item *
ccn_decoder_push(struct ccn_decoder *d) {
    struct ccn_decoder_stack_item *s;
    s = calloc(1, sizeof(*s));
    if (s != NULL) {
        s->link = d->stack;
        s->savedss = d->stringstack->length;
        d->stack = s;
    }
}

void
ccn_decoder_pop(struct ccn_decoder *d) {
    struct ccn_decoder_stack_item *s = d->stack;
    if (s != NULL) {
        d->stack = s->link;
        d->stringstack->length = s->savedss;
        free(s);
    }
}

void
ccn_decoder_destroy(struct ccn_decoder **dp)
{
    struct ccn_decoder *d = *dp;
    if (d != NULL) {
        while (d->stack != NULL) {
            ccn_decoder_pop(d);
        }
        ccn_charbuf_destroy(&(d->stringstack));
        free(d);
        *dp = NULL;
    }
}


const char * const vocab[] = { "foo", "bar", "baz" };

const size_t vocab_limit = sizeof(vocab) / sizeof(vocab[0]);

static const char Base64[] =
  "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

ssize_t
ccn_decoder_decode(struct ccn_decoder *d, unsigned char p[], size_t n)
{
    int state = d->state;
    int tagstate = 0;
    size_t numval = d->numval;
    ssize_t i = 0;
    unsigned char c;
    size_t chunk;
    struct ccn_decoder_stack_item *s;
    while (i < n) {
        switch (state) {
            case 0: /* start new token */
                numval = 0;
                state = 1;
                /* FALLTHRU */
            case 1: /* parsing numval */
                c = p[i++];
                if (c != (c & 127))
                    numval = (numval << 7) + (c & 127);
                else {
                    numval = (numval << 3) + (c >> 4);
                    c &= 15;
                    if (tagstate == 1 && c != CCN_ATTR) {
                        tagstate = 0;
                        printf(">");
                    }
                    switch (c) {
                        case CCN_INTVAL:
                            printf("%lu", (unsigned long)numval);
                            state = 0;
                            break;
                        case CCN_BLOB:
                            state = (numval == 0) ? 0 : 10;
                            break;
                        case CCN_UDATA:
                            state = (numval == 0) ? 0 : 3;
                            break;
                        case CCN_SYMBOL:
                            if (numval < vocab_limit) {
                                printf("%s", vocab[numval]);
                                state = 0;
                            }
                            else
                                state = -__LINE__;
                            break;
                        case CCN_STRUCTURE:
                            s = d->stack;
                            if (s == NULL || s->itemcount != 1 || tagstate != 0) {
                                state = -__LINE__;
                                break;
                                }
                            s->itemcount = numval + 1;
                            state = 0;
                            break;
                        case CCN_ATTR:
                            if (tagstate != 1) {
                                state = -__LINE__;
                                break;
                            }
                            /* FALLTHRU */
                        case CCN_TAG:
                            numval += 1; /* encoded as length-1 */
                            s = ccn_decoder_push(d);
                            ccn_charbuf_reserve(d->stringstack, numval + 1);
                            s->nameindex = d->stringstack->length;
                            state = (c == CCN_TAG) ? 4 : 5;
                            break;
                        default:
                            state = -__LINE__;
                    }
                }
                break;
            case 2: /* hex BLOB - this case currently unused */
                c = p[i++];
                printf("%02x", c);
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
                    ccn_charbuf_append(d->stringstack, (unsigned char *)"\0", 1);
                    s = d->stack;
                    if (s == NULL ||
                        strlen((char*)d->stringstack->buf + s->nameindex) != 
                            d->stringstack->length -1 - s->nameindex) {
                        state = -__LINE__;
                        break;
                    }
                    s->itemcount = 1;
                    if (state == 4) {
                        printf("<%s", d->stringstack->buf + s->nameindex);
                        numval = 0;
                        state = 1;
                        tagstate = 1;
                    }
                    else {
                        printf(" %s=\"", d->stringstack->buf + s->nameindex);
                        numval = 0;
                        state = 1;
                        tagstate = 2;
                    }
                }
                break;
            case 10: /* base 64 BLOB - phase 0 */
                c = p[i++];
                printf("%c", Base64[c >> 2]);
                if (--numval == 0) {
                    printf("%c==", Base64[(c & 3) << 4]);
                    state = 0;
                }
                else {
                    d->bits = (c & 3);
                    state = 11;
                }
                break;
            case 11: /* base 64 BLOB - phase 1 */
                c = p[i++];
                printf("%c", Base64[((d->bits & 3) << 4) + (c >> 4)]);
                if (--numval == 0) {
                    printf("%c=", Base64[(c & 0xF) << 2]);
                    state = 0;
                }
                else {
                    d->bits = (c & 0xF);
                    state = 12;
                }
                break;
            case 12: /* base 64 BLOB - phase 2 */
                c = p[i++];
                printf("%c%c", Base64[((d->bits & 0xF) << 2) + (c >> 6)],
                               Base64[c & 0x3F]);
                if (--numval == 0) {
                    state = 0;
                }
                else {
                    state = 10;
                }
                break;
            default:
                n = i;
        }
        while (state == 0 && (s = d->stack) != NULL) {
            // fflush(stdout);fprintf(stderr, "\n<!-- %d -->\n", (int)s->itemcount);
            if (tagstate == 2) {
                printf("\"");
                tagstate = 1;
                ccn_decoder_pop(d);
            }
            else if (tagstate == 0 && --(s->itemcount) == 0) {
                printf("</%s>", d->stringstack->buf + s->nameindex);
                ccn_decoder_pop(d);
            }
            else {
                numval = 0;
                state = 1;
            }
        }
    }
    d->state = state;
    d->tagstate = tagstate;
    d->numval = numval;
    return(i);
}

static int
process_fd(int fd)
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
    fprintf(stderr, "%6lu bytes\n", (unsigned long)c->length);
    ccn_charbuf_destroy(&c);
    return(res);
}


static int
process_file(char *path) {
    int fd = 0;
    int res = 0;
    if (0 != strcmp(path, "-")) {
        fd = open(path, O_RDONLY);
        if (-1 == fd) {
            perror(path);
            return(1);
        }
    }
    
    fprintf(stderr, "<!-- Processing %s -->\n", path);
    
    res = process_fd(fd);
    
    if (fd > 0)
        close(fd);
    return(res);
}

unsigned char test1[] = {
    (2 << 4) + CCN_TAG, 'F', 'o', 'o',
    (5 << 4) + CCN_STRUCTURE,
    (0 << 4) + CCN_TAG, 'a',
    (0 << 4) + CCN_SYMBOL,
    (0 << 4) + CCN_TAG, 'b',
    (3 << 4) + CCN_ATTR, 't', 'y', 'p', 'e',
    (5 << 4) + CCN_UDATA, 'e', 'm', 'p', 't', 'y',
    (0 << 4) + CCN_STRUCTURE,
    (2 << 4) + CCN_TAG, 'b', 'i', 'n',
    (4 << 4) + CCN_BLOB, 1, 0x23, 0x45, 0x67,
    (2 << 4) + CCN_TAG, 'i', 'n', 't',
    128 + 42/8,
    ((42 % 8) << 4) + CCN_INTVAL,
    (6 << 4) + CCN_UDATA,
    'H','i','&','b','y','e'
};

static int
process_test(unsigned char *data, size_t n) {
    struct ccn_decoder *d = ccn_decoder_create();
    int res = 0;
    size_t s;
    s = ccn_decoder_decode(d, data, n);
    if (d->state != 0 || s < n) {
        res = 1;
        fprintf(stderr, "error state %d after %d of %d chars\n",
            (int)d->state, (int)s, (int)n);
    }
    ccn_decoder_destroy(&d);
    return(res);
}

int
main(int argc, char **argv) {
    int i;
    int res = 0;
    for (i = 1; argv[i] != 0; i++) {
        fprintf(stderr, "<!-- Processing %s -->\n", argv[i]);
        if (0 == strcmp(argv[i], "-test1")) {
            res |= process_test(test1, sizeof(test1));
            printf("\n");
        }
        else
            res |= process_file(argv[i]);
    }
    return(res);
}

