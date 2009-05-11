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

#define CCN_MIN_PACKET_SIZE 5

/* forward reference */
void proto_register_ccn();
void proto_reg_handoff_ccn();
static int dissect_ccn(tvbuff_t *tvb, packet_info *pinfo, proto_tree *tree);
static int dissect_ccn_interest(const unsigned char *ccnb, size_t ccnb_size, tvbuff_t *tvb, packet_info *pinfo, proto_tree *tree);
static int dissect_ccn_contentobject(const unsigned char *ccnb, size_t ccnb_size, tvbuff_t *tvb, packet_info *pinfo, proto_tree *tree);
static gboolean dissect_ccn_heur(tvbuff_t *tvb, packet_info *pinfo, proto_tree *tree);

static int proto_ccn = -1;
static gint ett_ccn = -1;
static gint ett_signature = -1;
static gint ett_name = -1;
static gint ett_signedinfo = -1;
static gint ett_content = -1;

static gint hf_ccn_type = -1;

static int global_ccn_port = 4573;
static dissector_handle_t ccn_handle = NULL;


void
proto_register_ccn(void)
{
    module_t *ccn_module;
    static gint *ett[] = {
        &ett_ccn,
        &ett_signature,
        &ett_name,
        &ett_signedinfo,
        &ett_content,
    };
    
    static hf_register_info hf[] = {
        {&hf_ccn_type,
         {"Type", "ccn.type", FT_UINT32, BASE_DEC, NULL,
          0x0, "Type represents the type of the CCN packet", HFILL}}
    };

    proto_ccn = proto_register_protocol("Content-centric Networking Protocol", /* name */
                                        "CCN",		/* short name */
                                        "ccn");		/* abbrev */
    proto_register_subtree_array(ett, array_length(ett));
    hf[0].hfinfo.strings = ccn_dtag_dict.dict;
    proto_register_field_array(proto_ccn, hf, array_length(hf));
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
    guint tvb_size = 0;
    proto_tree *ccn_tree;
    proto_item *ti = NULL;
    const unsigned char *ccnb;
    struct ccn_skeleton_decoder skel_decoder;
    struct ccn_skeleton_decoder *sd;
    struct ccn_charbuf *c;
    int packet_type = 0;
    int packet_type_length = 0;
    /* a couple of basic checks to rule out packets that are definitely not ours */
    tvb_size = tvb_length(tvb);
    if (tvb_size < CCN_MIN_PACKET_SIZE || tvb_get_guint8(tvb, 0) == 0)
        return (0);

    sd = &skel_decoder;
    memset(sd, 0, sizeof(*sd));
    sd->state |= CCN_DSTATE_PAUSE;
    ccnb = ep_tvb_memdup(tvb, 0, tvb_size);
    ccn_skeleton_decode(sd, ccnb, tvb_size);
    if (sd->state < 0)
        return (0);
    if (CCN_GET_TT_FROM_DSTATE(sd->state) == CCN_DTAG) {
        packet_type = sd->numval;
        packet_type_length = sd->index;
    } else {
        return (0);
    }
    memset(sd, 0, sizeof(*sd));
    ccn_skeleton_decode(sd, ccnb, tvb_size);
    if (!CCN_FINAL_DSTATE(sd->state)) {
        pinfo->desegment_offset = 0;
        pinfo->desegment_len = DESEGMENT_ONE_MORE_SEGMENT;
        return (-1); /* what should this be? */
    }

    /* Make it visible that we're taking this packet */
    if (check_col(pinfo->cinfo, COL_PROTOCOL)) {
        col_set_str(pinfo->cinfo, COL_PROTOCOL, "CCN");
    }

    /* Clear out stuff in the info column */
    if (check_col(pinfo->cinfo, COL_INFO)) {
        col_clear(pinfo->cinfo, COL_INFO);
    }

    c = ccn_charbuf_create();
    ccn_uri_append(c, ccnb, tvb_size, 1);

    /* Add the packet type and CCN URI to the info column */
    if (check_col(pinfo->cinfo, COL_INFO)) {
        col_add_str(pinfo->cinfo, COL_INFO,
                    val_to_str(packet_type, VALS(ccn_dtag_dict.dict), "Unknown (0x%02x"));
        col_append_str(pinfo->cinfo, COL_INFO, ", ");
        col_append_str(pinfo->cinfo, COL_INFO, ccn_charbuf_as_string(c));
    }
    
    if (tree == NULL) {
        ccn_charbuf_destroy(&c);
        return (sd->index);
    }
    
    ti = proto_tree_add_protocol_format(tree, proto_ccn, tvb, 0, -1,
                                        "Content-centric Networking Protocol, %s, %s",
                                        val_to_str(packet_type, VALS(ccn_dtag_dict.dict), "Unknown (0x%02x"),
                                        ccn_charbuf_as_string(c));
    ccn_tree = proto_item_add_subtree(ti, ett_ccn);
    ccn_charbuf_destroy(&c);    
    ti = proto_tree_add_uint(ccn_tree, hf_ccn_type, tvb, 0, packet_type_length, packet_type);

    switch (packet_type) {
    case CCN_DTAG_ContentObject:
    case CCN_DTAG_ContentObjectV20080711:
        dissect_ccn_contentobject(ccnb, sd->index, tvb, pinfo, ccn_tree);
        break;
    case CCN_DTAG_Interest:
        dissect_ccn_interest(ccnb, sd->index, tvb, pinfo, ccn_tree);
        break;
    }

    return (sd->index);
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

static int
dissect_ccn_interest(const unsigned char *ccnb, size_t ccnb_size, tvbuff_t *tvb, packet_info *pinfo, proto_tree *tree)
{
    struct ccn_parsed_interest i;
    struct ccn_parsed_interest *pi = &i;
    struct ccn_indexbuf *comps;
    int res;

    comps = ccn_indexbuf_create();
    res = ccn_parse_interest(ccnb, ccnb_size, pi, comps);
    return (0);
    
}

static int
dissect_ccn_contentobject(const unsigned char *ccnb, size_t ccnb_size, tvbuff_t *tvb, packet_info *pinfo, proto_tree *tree)
{
    proto_tree *signature_tree;
    proto_item *signature_item;
    proto_tree *name_tree;
    proto_item *name_item;
    proto_tree *signedinfo_tree;
    proto_item *signedinfo_item;
    proto_tree *content_tree;
    proto_item *content_item;
    struct ccn_parsed_ContentObject co;
    struct ccn_parsed_ContentObject *pco = &co;
    /* struct ccn_indexbuf *comps; */
    int l;
    int res;
    
    /*    comps = ccn_indexbuf_create(); */
    res = ccn_parse_ContentObject(ccnb, ccnb_size, pco, NULL);
    if (res < 0) return (-1);
    
    l = pco->offset[CCN_PCO_E_Signature] - pco->offset[CCN_PCO_B_Signature];
    signature_item = proto_tree_add_text(tree, tvb,
                                         pco->offset[CCN_PCO_B_Signature], l,
                                         "%s", "Signature");
    signature_tree = proto_item_add_subtree(signature_item, ett_signature);

    l = pco->offset[CCN_PCO_E_Name] - pco->offset[CCN_PCO_B_Name];
    name_item = proto_tree_add_text(tree, tvb,
                                         pco->offset[CCN_PCO_B_Name], l,
                                         "%s", "Name");
    name_tree = proto_item_add_subtree(name_item, ett_name);
                                          
    l = pco->offset[CCN_PCO_E_SignedInfo] - pco->offset[CCN_PCO_B_SignedInfo];
    signedinfo_item = proto_tree_add_text(tree, tvb,
                                         pco->offset[CCN_PCO_B_SignedInfo], l,
                                         "SignedInfo");
    signedinfo_tree = proto_item_add_subtree(signedinfo_item, ett_signedinfo);
                                          
    l = pco->offset[CCN_PCO_E_Content] - pco->offset[CCN_PCO_B_Content];
    content_item = proto_tree_add_text(tree, tvb,
                                         pco->offset[CCN_PCO_B_Content], l,
                                         "Content");
    content_tree = proto_item_add_subtree(content_item, ett_content);
                                          
    return (ccnb_size);
}

