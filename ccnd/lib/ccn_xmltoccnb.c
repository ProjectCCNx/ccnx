#include <fcntl.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <expat.h>

#include <ccn/coding.h>
#include <ccn/charbuf.h>

struct ccn_encoder_stack_item {
    size_t start;
    size_t end;
    struct ccn_encoder_stack_item *link;
};

struct ccn_encoder {
    struct ccn_charbuf *openudata;
    FILE *outfile;
};


struct ccn_encoder *
ccn_encoder_create(FILE *outfile)
{
    struct ccn_encoder *c;
    c = calloc(1, sizeof(*c));
    if (c) {
        c->openudata = ccn_charbuf_create();
        if (c->openudata != NULL)
            ccn_charbuf_reserve(c->openudata, 128);
        c->outfile = outfile;
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
    fwrite(p, 1, length, u->outfile);
}

static void
emit_tt(struct ccn_encoder *u, size_t numval, enum ccn_tt tt)
{
    unsigned char buf[1+8*((sizeof(numval)+6)/7)];
    unsigned char *p = buf + (sizeof(buf)-1);
    int n = 1;
    p[0] = ((numval & CCN_MAX_TINY) << CCN_TT_BITS) + (CCN_TT_MASK & tt);
    numval >>= (7-CCN_TT_BITS);
    while (numval != 0) {
        (--p)[0] = ((unsigned char)numval) | 128;
        n++;
        numval >>= 7;
    }
    emit_bytes(u, p, n);
}

static void
emit_name(struct ccn_encoder *u, enum ccn_tt tt, const void *name)
{
    size_t length = strlen(name);
    if (length == 0) return; /* should never happen */
    emit_tt(u, length-1, tt);
    emit_bytes(u, name, length);
}

static void
finish_openudata(struct ccn_encoder *u)
{
    if (u->openudata->length != 0) {
        emit_tt(u, u->openudata->length, CCN_UDATA);
        emit_bytes(u, u->openudata->buf, u->openudata->length);
        u->openudata->length = 0;
    }
}

static void
do_start_element(void *ud, const XML_Char *name,
                          const XML_Char **atts)
{
    struct ccn_encoder *u = ud;
    const XML_Char **att;
    finish_openudata(u);
    emit_name(u, CCN_TAG, name);
    for (att = atts; att[0] != NULL; att += 2) {
        size_t attrlen = strlen(att[1]);
        emit_name(u, CCN_ATTR, att[0]);
        emit_tt(u, attrlen, CCN_UDATA);
        emit_bytes(u, att[1], attrlen);
    }
}

static void
do_end_element(void *ud, const XML_Char *name)
{
    struct ccn_encoder *u = ud;
    static const unsigned char closer[] = { CCN_CLOSE };
    finish_openudata(u);
    emit_bytes(u, closer, sizeof(closer));
}

static void
do_character_data(void *ud, const XML_Char *s, int len)
{
     struct ccn_encoder *u = ud;
     ccn_charbuf_append(u->openudata, s, len);
}

static int
process_fd(int fd, FILE *outfile) {
    char buf[17];
    ssize_t len;
    int res = 0;
    struct ccn_encoder *u;
    XML_Parser p;
    u = ccn_encoder_create(outfile);
    if (u == NULL) return(1);
    p = XML_ParserCreate(NULL);
    XML_SetUserData(p, u);
    XML_SetElementHandler(p, &do_start_element, &do_end_element);
    XML_SetCharacterDataHandler(p, &do_character_data);
    
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
        fprintf(stderr, "xml parse error\n");
        res |= 1;
    }
    XML_ParserFree(p);
    ccn_encoder_destroy(&u);
    
    return(res);
}

static int
process_file(char *path) {
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
        res = process_fd(fd, outfile);
        fflush(outfile);
    }
    if (outfile != NULL && outfile != stdout) {
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
main(int argc, char **argv) {
    int i;
    int res = 0;
    for (i = 1; argv[i] != 0; i++) {
        fprintf(stderr, "<!-- Processing %s -->\n", argv[i]);
        res |= process_file(argv[i]);
    }
    return(res);
}

