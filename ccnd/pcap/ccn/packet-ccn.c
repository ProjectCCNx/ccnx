/* packet-ccn.c
 * Routines for CCN Protocol disassembly
 * RFC 2257
 *
 * Wireshark - Network traffic analyzer
 * By Gerald Combs <gerald@wireshark.org>
 * Copyright 1999 Gerald Combs
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */

#ifdef HAVE_CONFIG_H
# include "config.h"
#endif

#include <epan/packet.h>
#include <epan/prefs.h>

#include <string.h>
#include <ccn/ccn.h>
#include <ccn/coding.h>
#include <ccn/uri.h>

/* forward reference */
void proto_register_ccn();
void proto_reg_handoff_ccn();
static int dissect_ccn(tvbuff_t *tvb, packet_info *pinfo, proto_tree *tree);
static gboolean dissect_ccn_heur(tvbuff_t *tvb, packet_info *pinfo, proto_tree *tree);

static int proto_ccn = -1;
static gint ett_ccn = -1;
static gint ett_type = -1;
static int global_ccn_port = 4485;
static dissector_handle_t ccn_handle = NULL;

void
proto_register_ccn(void)
{
    module_t *ccn_module;
    static gint *ett[] = {
        &ett_ccn,
        &ett_type,
    };
    proto_ccn = proto_register_protocol("Content-centric Networking Protocol", /* name */
                                        "CCN",		/* short name */
                                        "ccn");		/* abbrev */
    proto_register_subtree_array(ett, array_length(ett));
    ccn_module = prefs_register_protocol(proto_ccn, proto_reg_handoff_ccn);
}

void
proto_reg_handoff_ccn(void)
{
    static gboolean initialized = FALSE;
    static int current_ccn_port = -1;

    if (!initialized) {
        ccn_handle = new_create_dissector_handle(dissect_ccn, proto_ccn);
        heur_dissector_add("udp", dissect_ccn_heur, proto_ccn);
        initialized = TRUE;
    }
    if (current_ccn_port != -1) {
        dissector_delete("udp.port", current_ccn_port, ccn_handle);
    }
    dissector_add("udp.port", global_ccn_port, ccn_handle);
    current_ccn_port = global_ccn_port;
}

/*
 * Dissector that returns:
 *
 *	The amount of data in the protocol's PDU, if it was able to
 *	dissect all the data;
 *
 *	0, if the tvbuff doesn't contain a PDU for that protocol;
 *
 *	The negative of the amount of additional data needed, if
 *	we need more data (e.g., from subsequent TCP segments) to
 *	dissect the entire PDU.
 */ 
static int
dissect_ccn(tvbuff_t *tvb, packet_info *pinfo, proto_tree *tree)
{
    static const value_string packet_type_names[] = {
        {CCN_DTAG_Interest, "Interest"},
        {CCN_DTAG_ContentObject, "Content"},
        {CCN_DTAG_ContentObjectV20080711, "ContentV20080711"},
        {0, ""}
    };

    guint tvb_size = 0;
    proto_tree *ccn_tree;
    proto_item *ti = NULL;
    proto_tree *type_tree;
    proto_item *type_item;
    const unsigned char *data;
    struct ccn_skeleton_decoder skel_decoder;
    struct ccn_skeleton_decoder *sd;
    struct ccn_buf_decoder decoder;
    struct ccn_buf_decoder *d;
    int packet_type = 0;
    size_t s;
    
    /* if it passes through a skeleton decoder it's one of ours for sure */
    memset(&skel_decoder, 0, sizeof(skel_decoder));
    sd = &skel_decoder;
    tvb_size = tvb_length(tvb);
    data = ep_tvb_memdup(tvb, 0, tvb_size);
    s = ccn_skeleton_decode(sd, data, tvb_size);
    if (sd->state < 0)
        return (0);

    /* Make it visible that we're taking this packet */
    if (check_col(pinfo->cinfo, COL_PROTOCOL)) {
        col_set_str(pinfo->cinfo, COL_PROTOCOL, "CCN");
    }

    /* Clear out stuff in the info column */
    if (check_col(pinfo->cinfo, COL_INFO)) {
        col_clear(pinfo->cinfo, COL_INFO);
    }

    /* decide whether it is an Interest or ContentObject */
    memset(&decoder, 0, sizeof(decoder));
    d = ccn_buf_decoder_start(&decoder, data, tvb_size);
    if (d->decoder.state >= 0 && CCN_GET_TT_FROM_DSTATE(d->decoder.state) == CCN_DTAG) {
        packet_type = d->decoder.numval;
    }

    
    /* Add the packet type and CCN URI to the info column */
    if (check_col(pinfo->cinfo, COL_INFO)) {
        struct ccn_charbuf *c;
        col_add_str(pinfo->cinfo, COL_INFO,
                    val_to_str(packet_type, packet_type_names, "Unknown (0x%02x"));
        c = ccn_charbuf_create();
        ccn_uri_append(c, data, tvb_size, 1);
        col_append_str(pinfo->cinfo, COL_INFO, " ");
        col_append_str(pinfo->cinfo, COL_INFO, ccn_charbuf_as_string(c));
        ccn_charbuf_destroy(&c);
    }
    
    if (tree == NULL)
        return (s);

    
    ti = proto_tree_add_item(tree, proto_ccn, tvb, 0, -1, FALSE);
    ccn_tree = proto_item_add_subtree(ti, ett_ccn);
    /* proto_tree_add_item(ccn_tree, hf_ccn_xxx, tvb, offset, len, FALSE); */
        
    type_item = proto_tree_add_text(ccn_tree, tvb, 0, 3,
                                    val_to_str(packet_type, packet_type_names, "Unknown (0x%02x"));
    type_tree = proto_item_add_subtree(type_item, ett_type);
    return (s);
}

static gboolean
dissect_ccn_heur(tvbuff_t *tvb, packet_info *pinfo, proto_tree *tree)
{
    
    /* This is a heuristic dissector, which means we get all the UDP
     * traffic not sent to a known dissector and not claimed by
     * a heuristic dissector called before us!
     */

    if (dissect_ccn(tvb, pinfo, tree) > 0)
        return (TRUE);
    else
        return (FALSE);
}
