/**
 * @file ccn_extend_dict.c
 * @brief Routines for extending a dictionary such as that which represents default DTAG table.
 * 
 * Part of the CCNx C Library.
 *
 * Copyright (C) 2010-2012 Palo Alto Research Center, Inc.
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation.
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. You should have received
 * a copy of the GNU Lesser General Public License along with this library;
 * if not, write to the Free Software Foundation, Inc., 51 Franklin Street,
 * Fifth Floor, Boston, MA 02110-1301 USA.
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ctype.h>

#include <ccn/charbuf.h>
#include <ccn/extend_dict.h>

static int
qsort_compare_dict_names(const void *x, const void *y)
{
    const struct ccn_dict_entry *ex = x;
    const struct ccn_dict_entry *ey = y;
    return (strcmp(ex->name, ey->name));
}

/* compare entries based on index, except that an entry with a NULL name
 * field is always greater than a non-NULL name field, which allows us
 * to bubble exact duplicates eliminated after the name sort to the end
 */
static int
qsort_compare_dict_indices(const void *x, const void *y)
{
    const struct ccn_dict_entry *ex = x;
    const struct ccn_dict_entry *ey = y;
    if (ex->name == NULL)
        return ((ey->name == NULL) ? 0 : 1);
    if (ey->name == NULL) return (-1);
    if (ex->index == ey->index) return (0);
    return ((ex->index < ey->index) ? -1 : 1);
}

/**
 * Destroy a dictionary dynamically allocated by ccn_extend_dict
 * @param dp    Pointer to a pointer to a ccn_dict which will be freed
 *              and set to NULL
 */
void
ccn_destroy_dict(struct ccn_dict **dp)
{
    struct ccn_dict *d = *dp;
    int i;
    if (d != NULL) {
        for (i = 0; i < d->count; i++) {
            if (d->dict[i].name != NULL)
                free((void *)d->dict[i].name);
        }
        free(d);
    }
    *dp = NULL;
}

/**
 * Create a dictionary by combining a file of key/value pairs with an existing
 * dictionary.
 * 
 * @param dict_file     the name of a file containing integer,name pairs one per line
 * @param d             a pre-existing dictionary that will be copied in the result
 * @param rdp           a pointer to storage into which a pointer to the result
 *                      dictionary will be stored
 * @result 0 if the new dictionary was created successfully, otherwise -1.
 */
int
ccn_extend_dict(const char *dict_file, struct ccn_dict *d, struct ccn_dict **rdp)
{
    FILE *df = NULL;
    int i, c;
    struct ccn_dict_entry *ndd = NULL;
    struct ccn_dict_entry *ndd_tmp = NULL;
    int ndc = 0;
    struct ccn_charbuf *enamebuf = NULL;
    unsigned int eindex = 0;;
    struct ccn_dict *nd = NULL;
    enum scanner_state {
        S_OVERFLOW = -2,
        S_ERROR = -1,
        S_INITIAL = 0,
        S_INDEX = 1,
        S_NAME = 2,
        S_FLUSH = 3
    } s = S_INITIAL;
    
    if (rdp == NULL)
        return (-1);

    enamebuf = ccn_charbuf_create();
    if (enamebuf == NULL)
        return (-1);
    
    df = fopen(dict_file, "r");
    if (df == NULL)
        goto err;
    
    
    /* preload result with copy of supplied dictionary */
    if (d) {
        ndd = calloc(d->count, sizeof(*(d->dict)));
        for (ndc = 0; ndc < d->count; ndc++) {
            ndd[ndc].index = d->dict[ndc].index;
            ndd[ndc].name = strdup(d->dict[ndc].name);
        }
    }
    
    /* parse csv format file */
    while ((c = fgetc(df)) != EOF && s >= S_INITIAL) {
        switch (s) {
            case S_INITIAL:
                if (isdigit(c)) {
                    s = S_INDEX;
                    eindex = c - '0';
                } else
                    s = S_ERROR;
                break;
            case S_INDEX:
                if (isdigit(c)) {
                    unsigned int teindex = eindex;
                    eindex = 10 * eindex + (c - '0');
                    if (eindex < teindex)
                        s = S_OVERFLOW;
                } else if (c == ',')
                    s = S_NAME;
                else
                    s = S_ERROR;
                break;
            case S_NAME:
                if (isalnum(c)) {
                    ccn_charbuf_append_value(enamebuf, c, 1);
                } else if (c == ',' || c == '\n') {
                    /* construct entry */
                    ndd_tmp = realloc(ndd, sizeof(*ndd) * (ndc + 1));
                    if (ndd_tmp == NULL)
                        goto err;
                    ndd = ndd_tmp;
                    ndd[ndc].index = eindex;
                    ndd[ndc].name = strdup(ccn_charbuf_as_string(enamebuf));
                    ndc++;
                    ccn_charbuf_reset(enamebuf);
                    s = (c == ',') ? S_FLUSH : S_INITIAL;
                } else
                    s = S_ERROR;
                break;
            case S_FLUSH:
                if (c == '\n')
                    s = S_INITIAL;
                break;
            default:
                break;
        }
    }
    fclose(df);
    df = NULL;
    
    /* handle error exit from parsing and pick up trailing entry without newline */
    if (s < 0 || s == S_INDEX)
        goto err;
    else if (s == S_NAME) {
        ndd_tmp = realloc(ndd, sizeof(*ndd) * (ndc + 1));
        if (ndd_tmp == NULL)
            goto err;
        ndd = ndd_tmp;
        ndd[ndc].index = eindex;
        ndd[ndc].name = strdup(ccn_charbuf_as_string(enamebuf));
        ndc++;
    }
    ccn_charbuf_destroy(&enamebuf);
    
    /* check for inconsistent duplicate names, mark exact duplicates for removal */
    qsort(ndd, ndc, sizeof(*ndd), qsort_compare_dict_names);
    for (i = 1; i < ndc; i++) {
        if (strcmp(ndd[i-1].name, ndd[i].name) == 0) {
            if (ndd[i-1].index == ndd[i].index) {
                free((void *)ndd[i-1].name);
                ndd[i-1].name = NULL;
            } else
                goto err;
        }
    }
    /* check for inconsistent duplicate index values,
     * trim the array when we reach the duplicates, marked above,
     * which sorted to the end.
     */
    qsort(ndd, ndc, sizeof(*ndd), qsort_compare_dict_indices);
    for (i = 1; i < ndc; i++) {
        if (ndd[i].name == NULL) {
            ndc = i;
            ndd_tmp = realloc(ndd, sizeof(*ndd) * ndc);
            if (ndd_tmp == NULL)
                goto err;
            ndd = ndd_tmp;
            break;
        }
        if (ndd[i-1].index == ndd[i].index)
            goto err;
    }
    
    /* construct the final dictionary object */
    nd = calloc(1, sizeof(*nd));
    if (nd == NULL)
        goto err;
    nd->dict = ndd;
    nd->count = ndc;
    *rdp = nd;
    return (0);
    
err:
    ccn_charbuf_destroy(&enamebuf);
    if (df != NULL)
        fclose(df);
    if (ndd != NULL) {
        for (ndc--; ndc >= 0; ndc--) {
            free((void *)ndd[ndc].name);
        }
        free(ndd);
    }
    return (-1);
    
}
