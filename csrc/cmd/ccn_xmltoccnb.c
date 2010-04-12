/**
 * @file ccn_xmltoccnb.c
 * Utility to convert XML into ccn binary encoded data (ccnb).
 *
 * A CCNx command-line utility.
 *
 * Copyright (C) 2008, 2009 Palo Alto Research Center, Inc.
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
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <expat.h>

#include <ccn/coding.h>
#include <ccn/charbuf.h>
#include <ccn/extend_dict.h>

static void
usage(const char *progname)
{
    fprintf(stderr,
            "usage: %s [-h] [-w] [-d dict]* file ...\n"
            " Utility to convert XML into ccn binary encoded data (ccnb)\n"
            "  -h       print usage and exit\n"
            "  -w       toss UDATA content consisting of only whitespace\n"
            "  -d dict  additional csv format dictionary file(s)\n"
            " use - for file to specify filter mode (stdin, stdout)\n"
            " otherwise output files get .ccnb extension\n",
            progname);
    exit(1);
}

struct ccn_encoder_stack_item {
    size_t start;
    size_t end;
    struct ccn_encoder_stack_item *link;
};

struct ccn_encoder {
    struct ccn_charbuf *openudata;
    int is_base64binary;
    int is_hexBinary;
    int is_text;
    int toss_white;
    const struct ccn_dict_entry *tagdict;
    int tagdict_count;
    FILE *outfile;
};

struct base64_decoder {
    size_t input_processed;
    size_t result_size;
    unsigned char *output;
    size_t output_size;
    unsigned partial;
    int phase;
};

/* "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/" */
static void
base64_decode_bytes(struct base64_decoder *d, const void *p, size_t count)
{
    size_t i;
    size_t oi = d->result_size;
    const char *s = p;
    unsigned partial = d->partial;
    unsigned endgame = partial & 0x100;
    int phase = d->phase;
    char ch;
    if (phase < 0)
        return;
    for (i = 0; i < count; i++) {
        ch = s[i];
        /*
         * We know we have UTF-8, hence ascii for the characters we care about.
         * Thus the range checks here are legitimate.
         */
        if ('A' <= ch && ch <= 'Z')
            ch -= 'A';
        else if ('a' <= ch && ch <= 'z')
            ch -= 'a' - 26;
        else if ('0' <= ch && ch <= '9')
            ch -= '0' - 52;
        else if (ch == '+')
            ch = 62;
        else if (ch == '/')
            ch = 63;
        else if (ch == ' ' || ch == '\t' || ch == '\n')
            continue;
        else if (ch == '=')
            if (phase > 4 || (partial & 3) != 0)
                phase = -1;
            else {
                phase -= 2;
                partial >>= 2;
                endgame = 0x100;
                continue;
            }
        else {
            phase = -1;
            break;
        }
        if (endgame != 0) {
            phase = -1;
            break;
        }
        partial <<= 6;
        partial |= ch;
        phase += 6;
        if (phase >= 8) {
            if (oi < d->output_size)
                d->output[oi] = partial >> (phase - 8);
            oi += 1;
            phase -= 8;
        }
    }
    d->phase = phase;
    d->partial = partial & ((1<<6)-1);
    d->result_size = oi;
}

static int
dict_lookup(const char *key, const struct ccn_dict_entry *dict, int n)
{
    int i;
    for (i = 0; i < n; i++)
        if (0 == strcmp(key, dict[i].name))
            return (dict[i].index);
    return (-1);
}

struct ccn_encoder *
ccn_encoder_create(FILE *outfile, const struct ccn_dict *dtags)
{
    struct ccn_encoder *c;
    c = calloc(1, sizeof(*c));
    if (c) {
        c->openudata = ccn_charbuf_create();
        if (c->openudata != NULL)
            ccn_charbuf_reserve(c->openudata, 128);
        c->outfile = outfile;
        c->tagdict = dtags->dict;
        c->tagdict_count = dtags->count;
    }
    return(c);
}

void
ccn_encoder_destroy(struct ccn_encoder **cbp)
{
    struct ccn_encoder *c = *cbp;
    if (c != NULL) {
        ccn_charbuf_destroy(&c->openudata);
        free(c);
        *cbp = NULL;
    }
}

static void
emit_bytes(struct ccn_encoder *u, const void *p, size_t length)
{
    /* Write errors to files are checked with ferror before close. */
    (void)fwrite(p, 1, length, u->outfile);
}

static void
emit_tt(struct ccn_encoder *u, size_t numval, enum ccn_tt tt)
{
    unsigned char buf[1+8*((sizeof(numval)+6)/7)];
    unsigned char *p = buf + (sizeof(buf)-1);
    int n = 1;
    p[0] = (CCN_TT_HBIT & ~CCN_CLOSE) |
           ((numval & CCN_MAX_TINY) << CCN_TT_BITS) |
           (CCN_TT_MASK & tt);
    numval >>= (7-CCN_TT_BITS);
    while (numval != 0) {
        (--p)[0] = (((unsigned char)numval) & ~CCN_TT_HBIT) | CCN_CLOSE;
        n++;
        numval >>= 7;
    }
    emit_bytes(u, p, n);
}

static int
all_whitespace(struct ccn_charbuf *b)
{
    size_t i;
    size_t n = b->length;
    for (i = 0; i < n; i++) {
        switch (b->buf[i]) {
            case ' ':
            case '\t':
            case '\n':
                continue;
        }
        return(0);
    }
    return(1);
}

static void
finish_openudata(struct ccn_encoder *u)
{
    if (u->is_base64binary) {
        unsigned char *obuf = NULL;
        ssize_t len = -1;
        size_t maxbinlen = u->openudata->length * 3 / 4 + 4;
        struct base64_decoder d = { 0 };
        u->is_base64binary = 0;
        obuf = ccn_charbuf_reserve(u->openudata, maxbinlen);
        if (obuf != NULL) {
            d.output = obuf;
            d.output_size = maxbinlen;
            base64_decode_bytes(&d, u->openudata->buf, u->openudata->length);
            if (d.phase == 0 && d.result_size <= d.output_size)
                len = d.result_size;
        }
        if (len == -1) {
            fprintf(stderr,
                "could not decode base64binary, leaving as character data\n");
        }
        else {
            emit_tt(u, len, CCN_BLOB);
            emit_bytes(u, obuf, len);
            u->openudata->length = 0;
            return;
        }
    }
    else if (u->is_hexBinary) {
        size_t maxbinlen = (u->openudata->length + 1)/2;
        unsigned char *obuf = NULL;
        int v = -1;
        size_t i;
        size_t j = 0;
        unsigned char ch;
        u->is_hexBinary = 0;
        obuf = ccn_charbuf_reserve(u->openudata, maxbinlen);
        if (obuf != NULL) {
            for (v = 1, i = 0, j = 0; v > 0 && i < u->openudata->length; i++) {
                ch = u->openudata->buf[i];
                if (ch <= ' ')
                    continue;
                v = (v << 4) + (('0' <= ch && ch <= '9') ? (ch - '0') :
                                ('A' <= ch && ch <= 'F') ? (ch - 'A' + 10) :
                                ('a' <= ch && ch <= 'f') ? (ch - 'a' + 10) :
                                -1024);
                if (v > 255) {
                    if (j >= maxbinlen)
                        break;
                    obuf[j++] = v & 255;
                    v = 1;
                }
            }
        }
        if (v != 1) {
            fprintf(stderr,
                    "could not decode hexBinary, leaving as character data\n");
        }
        else {
            emit_tt(u, j, CCN_BLOB);
            emit_bytes(u, obuf, j);
            u->openudata->length = 0;
            return;
        }
    }
    else if (u->is_text) {
        u->is_text = 0;
        emit_tt(u, u->openudata->length, CCN_BLOB);
        emit_bytes(u, u->openudata->buf, u->openudata->length);
        u->openudata->length = 0;
        return;
    }
    if (u->openudata->length != 0) {
        if (!(u->toss_white && all_whitespace(u->openudata))) {
            emit_tt(u, u->openudata->length, CCN_UDATA);
            emit_bytes(u, u->openudata->buf, u->openudata->length);
        }
        u->openudata->length = 0;
    }
}

static void
emit_name(struct ccn_encoder *u, enum ccn_tt tt, const void *name)
{
    size_t length = strlen(name);
    int dictindex = -1;
    if (length == 0) return; /* should never happen */
    finish_openudata(u);
    if (tt == CCN_TAG) {
        dictindex = dict_lookup(name, u->tagdict, u->tagdict_count);
        if (dictindex >= 0) {
            emit_tt(u, dictindex, CCN_DTAG);
            return;
        }
    }
    emit_tt(u, length-1, tt);
    emit_bytes(u, name, length);
}

static void
emit_xchars(struct ccn_encoder *u, const XML_Char *xchars)
{
    size_t length = strlen(xchars);
    finish_openudata(u);
    emit_tt(u, length, CCN_UDATA);
    emit_bytes(u, xchars, length);
}

static void
emit_closer(struct ccn_encoder *u)
{
    static const unsigned char closer[] = { CCN_CLOSE };
    finish_openudata(u);
    emit_bytes(u, closer, sizeof(closer));
}

static void
do_start_element(void *ud, const XML_Char *name,
                 const XML_Char **atts)
{
    struct ccn_encoder *u = ud;
    const XML_Char **att;
    int is_base64binary = 0;
    int is_hexBinary = 0;
    int is_text = 0;
    emit_name(u, CCN_TAG, name);
    for (att = atts; att[0] != NULL; att += 2) {
        if (0 == strcmp(att[0], "ccnbencoding")) {
            if (0 == strcmp(att[1], "base64Binary")) {
                is_base64binary = 1;
                continue;
            }
            if (0 == strcmp(att[1], "hexBinary")) {
                is_hexBinary = 1;
                continue;
            }
            if (0 == strcmp(att[1], "text")) {
                is_text = 1;
                continue;
            }
            fprintf(stderr, "warning - unknown ccnbencoding found (%s)\n", att[1]);
        }
        emit_name(u, CCN_ATTR, att[0]);
        emit_xchars(u, att[1]);
    }
    u->is_base64binary = is_base64binary;
    u->is_hexBinary = is_hexBinary;
    u->is_text = is_text;
}

static void
do_end_element(void *ud, const XML_Char *name)
{
    struct ccn_encoder *u = ud;
    emit_closer(u);
}

static void
do_character_data(void *ud, const XML_Char *s, int len)
{
     struct ccn_encoder *u = ud;
     ccn_charbuf_append(u->openudata, s, len);
}

static void
do_processing_instructions(void *ud, const XML_Char *target, const XML_Char *data)
{
     struct ccn_encoder *u = ud;
     finish_openudata(u);
     emit_tt(u, CCN_PROCESSING_INSTRUCTIONS, CCN_EXT);
     emit_xchars(u, target);
     emit_xchars(u, data);
     emit_closer(u);
}

#define TOSS_WHITE 1
static int
process_fd(int fd, FILE *outfile, int flags, const struct ccn_dict *dtags)
{
    char buf[17];
    ssize_t len;
    int res = 0;
    struct ccn_encoder *u;
    XML_Parser p;
    u = ccn_encoder_create(outfile, dtags);
    if (u == NULL) return(1);
    if (flags & TOSS_WHITE) {
        u->toss_white = 1;
    }
    p = XML_ParserCreate(NULL);
    XML_SetUserData(p, u);
    XML_SetElementHandler(p, &do_start_element, &do_end_element);
    XML_SetCharacterDataHandler(p, &do_character_data);
    XML_SetProcessingInstructionHandler(p, &do_processing_instructions);
    
    while ((len = read(fd, buf, sizeof(buf))) > 0) {
        if (XML_Parse(p, buf, len, 0) != XML_STATUS_OK) {
            res |= 1;
            break;
        }
    }
    if (len < 0) {
        perror("read");
        res |= 1;
    }
    if (XML_Parse(p, buf, 0, 1) != XML_STATUS_OK) {
      fprintf(stderr, "xml parse error line %ld\n", (long)XML_GetCurrentLineNumber(p));
        res |= 1;
    }
    XML_ParserFree(p);
    ccn_encoder_destroy(&u);
    
    return(res);
}

static int
process_file(char *path, int flags, const struct ccn_dict *dtags)
{
    int fd = 0;
    int res = 0;
    FILE *outfile = stdout;
    const char *basename;
    const char *ext;
    char *outname = NULL;
    const char outext[] = ".ccnb\0";
    if (0 != strcmp(path, "-")) {
        fd = open(path, O_RDONLY);
        if (-1 == fd) {
            perror(path);
            return(1);
        }
        basename = strrchr(path, '/');
        if (basename == NULL)
            basename = path;
        else
            basename++;
        ext = strrchr(basename, '.');
        if (ext == NULL || 0 != strcasecmp(ext, ".xml"))
            ext = strrchr(basename, 0);
        outname = calloc(1, ext - basename + sizeof(outext));
        if (outname == NULL) { perror("calloc"); exit(1); }
        memcpy(outname, basename, ext - basename);
        memcpy(outname + (ext - basename), outext, sizeof(outext));
        outfile = fopen(outname, "wb");
        if (outfile == NULL) {
            perror(outname);
            free(outname);
            outname = NULL;
            res |= 1;
        }
    }
    if (res == 0) {
        res = process_fd(fd, outfile, flags, dtags);
        fflush(outfile);
    }
    if (outfile != NULL && outfile != stdout) {
        if (ferror(outfile)) {
            res |= 1;
            fprintf(stderr, " %s: output error\n", outname);
            clearerr(outfile);
        }
        fclose(outfile);
        if (res == 0)
            fprintf(stderr, " %s written.\n", outname);
    }
    if (fd > 0)
        close(fd);
    if (res != 0 && outname != NULL) {
        unlink(outname);
    }
    if (outname != NULL)
        free(outname);
    return(res);
}

int
main(int argc, char **argv)
{
    int i;
    int res = 0;
    int dictres = 0;
    int flags = 0;
    struct ccn_dict *dtags = (struct ccn_dict *)&ccn_dtag_dict;
    
    if (argv[1] == NULL)
        usage(argv[0]);
    for (i = 1; argv[i] != 0; i++) {
        if (0 == strcmp(argv[i], "-h")) {
            usage(argv[0]);
        }
        if (0 == strcmp(argv[i], "-w")) {
            flags |= TOSS_WHITE;
            continue;
        }
        if (0 == strcmp(argv[i], "-d")) {
            if (argv[i+1] != 0) {
                if (0 > ccn_extend_dict(argv[i+1], dtags, &dtags)) {
                    fprintf(stderr, "Unable to load dtag dictionary %s\n", argv[i+1]);
                    dictres = -1;
                }
                i++;
            }
            continue;
        }
        if (dictres < 0)
            exit(1);
        fprintf(stderr, "<!-- Processing %s -->\n", argv[i]);
        res |= process_file(argv[i], flags, dtags);
    }
    return(res);
}
