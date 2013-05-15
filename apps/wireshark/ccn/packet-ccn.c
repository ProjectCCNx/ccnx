/**
 * @file glue/wireshark/ccn/packet-ccn.c
 * 
 * A wireshark plugin for CCNx protocols.
 *
 * Copyright (C) 2009,2011 Palo Alto Research Center, Inc.
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

/* Based on an example bearing this notice:
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

#include <stdlib.h>
#include <string.h>
#include <ccn/ccn.h>
#include <ccn/ccnd.h>
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
/*
 * The ett_ variables identify particular type of subtree so that if you expand
 * one of them, Wireshark keeps track of that and, when you click on
 * another packet, it automatically opens all subtrees of that type.
 * If you close one of them, all subtrees of that type will be closed when
 * you move to another packet.
 */
 
static gint ett_ccn = -1;
static gint ett_signature = -1;
static gint ett_name = -1;
static gint ett_signedinfo = -1;
static gint ett_finalblockid = -1;
static gint ett_keylocator = -1;
static gint ett_keylocator_name = -1;
static gint ett_content = -1;
static gint ett_exclude = -1;

/*
 * Header field variables
 */
static gint hf_ccn_type = -1;
static gint hf_ccn_name = -1;
static gint hf_ccn_name_components = -1;
static gint hf_ccn_signature = -1;
static gint hf_ccn_signaturedigestalg = -1;
static gint hf_ccn_signaturebits = -1;
static gint hf_ccn_publisherpublickeydigest = -1;
static gint hf_ccn_timestamp = -1;
static gint hf_ccn_contentdata = -1;
static gint hf_ccn_contenttype = -1;
static gint hf_ccn_freshnessseconds = -1;
static gint hf_ccn_finalblockid = -1;
static gint hf_ccn_finalblockid_final = -1;
static gint hf_ccn_keylocator_name = -1;
static gint hf_ccn_keylocator_name_components = -1;
static gint hf_ccn_keylocator_publisherpublickeydigest = -1;
static gint hf_ccn_keylocator_key = -1;
static gint hf_ccn_keylocator_certificate = -1;
static gint hf_ccn_extopt = -1;

static gint hf_ccn_minsuffixcomponents = -1;
static gint hf_ccn_maxsuffixcomponents = -1;
static gint hf_ccn_childselector = -1;

static const value_string childselectordirection_vals[] = {
    {0, "leftmost/least"},
    {1, "rightmost/greatest"},
    {0, NULL}
};

static gint hf_ccn_answeroriginkind = -1;
static gint hf_ccn_scope = -1;
static gint hf_ccn_interestlifetime = -1;
static gint hf_ccn_nonce = -1;

static dissector_handle_t ccn_handle = NULL;
static gboolean ccn_register_dtls = FALSE;


void
proto_register_ccn(void)
{
    module_t *ccn_module;

    static const value_string contenttype_vals[] = {
        {CCN_CONTENT_DATA, "Data"},
        {CCN_CONTENT_ENCR, "Encrypted"},
        {CCN_CONTENT_GONE, "Gone"},
        {CCN_CONTENT_KEY, "Key"},
        {CCN_CONTENT_LINK, "Link"},
        {CCN_CONTENT_NACK, "Nack"},
        {0, NULL}
    };
    
    static gint *ett[] = {
        &ett_ccn,
        &ett_signature,
        &ett_name,
        &ett_signedinfo,
        &ett_finalblockid,
        &ett_keylocator,
        &ett_keylocator_name,
        &ett_content,
        &ett_exclude,
    };
    
    
    static hf_register_info hf[] = {
        /* { &hf_PROTOABBREV_FIELDABBREV,
         { "FIELDNAME",           "PROTOABBREV.FIELDABBREV",
         FIELDTYPE, FIELDBASE, FIELDCONVERT, BITMASK,
         "FIELDDESCR", HFILL }
         */
        {&hf_ccn_type,
            {"Type", "ccn.type", FT_UINT32, BASE_DEC, NULL,
                0x0, "The type of the CCN packet", HFILL}},
        {&hf_ccn_name,
            {"Name", "ccn.name", FT_STRING, BASE_NONE, NULL,
                0x0, "The name of the content/interest in the CCN packet", HFILL}},
        {&hf_ccn_name_components,
            {"Component", "ccn.name.component", FT_STRING, BASE_NONE, NULL,
                0x0, "The individual components of the name", HFILL}},
        {&hf_ccn_signature,
            {"Signature", "ccn.signature", FT_NONE, BASE_NONE, NULL,
                0x0, "The signature collection of the CCN packet", HFILL}},
        {&hf_ccn_signaturedigestalg,
            {"Digest algorithm", "ccn.signature.digestalgorithm", FT_OID, BASE_NONE, NULL,
                0x0, "The OID of the signature digest algorithm", HFILL}},
        /* use BASE_NONE instead of ABSOLUTE_TIME_LOCAL for Wireshark 1.2.x */
        {&hf_ccn_timestamp,
            {"Timestamp", "ccn.timestamp", FT_ABSOLUTE_TIME, ABSOLUTE_TIME_LOCAL, NULL,
                0x0, "The time at creation of signed info", HFILL}},
        {&hf_ccn_signaturebits,
            {"Bits", "ccn.signature.bits", FT_BYTES, BASE_NONE, NULL,
                0x0, "The signature over the name through end of the content of the CCN packet", HFILL}},
        {&hf_ccn_publisherpublickeydigest,
            {"PublisherPublicKeyDigest", "ccn.publisherpublickeydigest", FT_BYTES, BASE_NONE, NULL,
                0x0, "The digest of the publisher's public key", HFILL}},
        {&hf_ccn_contenttype,
            {"Content type", "ccn.contenttype", FT_UINT32, BASE_HEX, &contenttype_vals,
                0x0, "Type of content", HFILL}},
        {&hf_ccn_freshnessseconds,
            {"Freshness seconds", "ccn.freshnessseconds", FT_UINT32, BASE_DEC, NULL,
                0x0, "Seconds before data becomes stale", HFILL}},
        {&hf_ccn_finalblockid,
            {"FinalBlockID", "ccn.finalblockid", FT_BYTES, BASE_NONE, NULL,
                0x0, "Indicates the identifier of the final block in a sequence of fragments", HFILL}},
        {&hf_ccn_finalblockid_final,
            {"IsFinal", "ccn.finalblockid.isfinal", FT_BOOLEAN, BASE_NONE, NULL,
                0x0, "True: this block is the final block; False: this block is not the final block", HFILL}},
        {&hf_ccn_keylocator_name,
            {"KeyName", "ccn.keylocator.name", FT_STRING, BASE_NONE, NULL,
                0x0, "The name of the key present in the KeyLocator", HFILL}},
        {&hf_ccn_keylocator_name_components,
            {"Component", "ccn.keylocator.name.component", FT_STRING, BASE_NONE, NULL,
                0x0, "The individual components of the name of the key", HFILL}},
        {&hf_ccn_keylocator_publisherpublickeydigest,
            {"PublisherPublicKeyDigest", "ccn.keylocator.publisherpublickeydigest", FT_BYTES, BASE_NONE, NULL,
                0x0, "The digest of the key's publisher's public key", HFILL}},
        {&hf_ccn_keylocator_key,
            {"Key", "ccn.keylocator.key", FT_BYTES, BASE_NONE, NULL,
                0x0, "The key present in the KeyLocator", HFILL}},
        {&hf_ccn_keylocator_certificate,
            {"Certificate", "ccn.keylocator.certificate", FT_BYTES, BASE_NONE, NULL,
                0x0, "The certificate present in the KeyLocator", HFILL}},
        {&hf_ccn_extopt,
            {"ExtOpt", "ccn.extopt", FT_BYTES, BASE_NONE, NULL,
                0x0, "Extension/Options field", HFILL}},
        {&hf_ccn_contentdata,
            {"Data", "ccn.data", FT_BYTES, BASE_NONE, NULL,
                0x0, "Raw data", HFILL}},
        {&hf_ccn_minsuffixcomponents,
            {"MinSuffixComponents", "ccn.minsuffixcomponents", FT_UINT32, BASE_DEC, NULL,
                0x0, "Minimum suffix components", HFILL}},
        {&hf_ccn_maxsuffixcomponents,
            {"MaxSuffixComponents", "ccn.maxsuffixcomponents", FT_UINT32, BASE_DEC, NULL,
                0x0, "Maximum suffix components", HFILL}},
        {&hf_ccn_childselector,
            {"ChildSelector", "ccn.childselector", FT_UINT8, BASE_DEC, NULL,
                0x0, "Preferred ordering of resulting content", HFILL}},
        {&hf_ccn_answeroriginkind,
            {"AnswerOriginKind", "ccn.answeroriginkind", FT_UINT8, BASE_HEX, NULL,
                0x0, "Acceptable sources of content (generated, stale)", HFILL}},
        {&hf_ccn_scope,
            {"Scope", "ccn.scope", FT_UINT8, BASE_DEC, NULL,
                0x0, "Limit of interest propagation", HFILL}},
        {&hf_ccn_interestlifetime,
            {"InterestLifetime", "ccn.interestlifetime", FT_DOUBLE, BASE_NONE, NULL,
                0x0, "The relative lifetime of the interest, stored in units of 1/4096 seconds", HFILL}},
        {&hf_ccn_nonce,
            {"Nonce", "ccn.nonce", FT_BYTES, BASE_NONE, NULL,
                0x0, "The nonce to distinguish interests", HFILL}},
    };
    
    proto_ccn = proto_register_protocol("Content-centric Networking Protocol", /* name */
                                        "CCN",		/* short name */
                                        "ccn");	/* abbrev */
    proto_register_subtree_array(ett, array_length(ett));
    hf[0].hfinfo.strings = ccn_dtag_dict.dict;
    proto_register_field_array(proto_ccn, hf, array_length(hf));
    ccn_module = prefs_register_protocol(proto_ccn, proto_reg_handoff_ccn);
    prefs_register_bool_preference(ccn_module, "register_dtls",
                                   "Register dissector for CCN over DTLS",
                                   "Whether the CCN dissector should register "
                                   "as a heuristic dissector for messages over DTLS",
                                   &ccn_register_dtls);
    
}

void
proto_reg_handoff_ccn(void)
{
    static gboolean initialized = FALSE;
    static int current_ccn_port = -1;
    int global_ccn_port = atoi(CCN_DEFAULT_UNICAST_PORT);
    
    if (!initialized) {
        ccn_handle = new_create_dissector_handle(dissect_ccn, proto_ccn);
        heur_dissector_add("udp", dissect_ccn_heur, proto_ccn);
        heur_dissector_add("tcp", dissect_ccn_heur, proto_ccn);
        if (ccn_register_dtls)
            heur_dissector_add("dtls", dissect_ccn_heur, proto_ccn);
        initialized = TRUE;
    }
    if (current_ccn_port != -1) {
        dissector_delete_uint("udp.port", current_ccn_port, ccn_handle);
        dissector_delete_uint("tcp.port", current_ccn_port, ccn_handle);
    }
    dissector_add_uint("udp.port", global_ccn_port, ccn_handle);
    dissector_add_uint("tcp.port", global_ccn_port, ccn_handle);
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
        col_append_sep_str(pinfo->cinfo, COL_INFO, NULL, ccn_charbuf_as_string(c));
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
            if (0 > dissect_ccn_contentobject(ccnb, sd->index, tvb, pinfo, ccn_tree))
                return (0);
            break;
        case CCN_DTAG_Interest:
            if (0 > dissect_ccn_interest(ccnb, sd->index, tvb, pinfo, ccn_tree))
                return (0);
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
    proto_tree *name_tree;
    proto_tree *exclude_tree;
    proto_item *titem;
    struct ccn_parsed_interest interest;
    struct ccn_parsed_interest *pi = &interest;
    struct ccn_buf_decoder decoder;
    struct ccn_buf_decoder *d;
    const unsigned char *bloom;
    size_t bloom_size = 0;
    struct ccn_charbuf *c;
    struct ccn_indexbuf *comps;
    const unsigned char *comp;
    size_t comp_size;
    const unsigned char *blob;
    size_t blob_size;
    ssize_t l;
    unsigned int i;
    double lifetime;
    int res;
    
    comps = ccn_indexbuf_create();
    res = ccn_parse_interest(ccnb, ccnb_size, pi, comps);
    if (res < 0)
        return (res);
    
    /* Name */
    l = pi->offset[CCN_PI_E_Name] - pi->offset[CCN_PI_B_Name];
    c = ccn_charbuf_create();
    ccn_uri_append(c, ccnb, ccnb_size, 1);
    titem = proto_tree_add_string(tree, hf_ccn_name, tvb,
                                  pi->offset[CCN_PI_B_Name], l,
                                  ccn_charbuf_as_string(c));
    name_tree = proto_item_add_subtree(titem, ett_name);
    
    for (i = 0; i < comps->n - 1; i++) {
        ccn_charbuf_reset(c);
        res = ccn_name_comp_get(ccnb, comps, i, &comp, &comp_size);
        ccn_uri_append_percentescaped(c, comp, comp_size);
        titem = proto_tree_add_string(name_tree, hf_ccn_name_components, tvb, comp - ccnb, comp_size, ccn_charbuf_as_string(c));
    }
    ccn_charbuf_destroy(&c);
    
    /* MinSuffixComponents */
    l = pi->offset[CCN_PI_E_MinSuffixComponents] - pi->offset[CCN_PI_B_MinSuffixComponents];
    if (l > 0) {
        i = pi->min_suffix_comps;
        titem = proto_tree_add_uint(tree, hf_ccn_minsuffixcomponents, tvb, pi->offset[CCN_PI_B_MinSuffixComponents], l, i);
    }
    
    /* MaxSuffixComponents */
    l = pi->offset[CCN_PI_E_MaxSuffixComponents] - pi->offset[CCN_PI_B_MaxSuffixComponents];
    if (l > 0) {
        i = pi->max_suffix_comps;
        titem = proto_tree_add_uint(tree, hf_ccn_maxsuffixcomponents, tvb, pi->offset[CCN_PI_B_MaxSuffixComponents], l, i);
    }
    
    /* PublisherPublicKeyDigest */
    /* Exclude */
    l = pi->offset[CCN_PI_E_Exclude] - pi->offset[CCN_PI_B_Exclude];
    if (l > 0) {
        c = ccn_charbuf_create();
        d = ccn_buf_decoder_start(&decoder, ccnb + pi->offset[CCN_PI_B_Exclude], l);
        if (!ccn_buf_match_dtag(d, CCN_DTAG_Exclude)) {
            ccn_charbuf_destroy(&c);
            return(-1);
        }
        ccn_charbuf_append_string(c, "Exclude: ");
        ccn_buf_advance(d);
        if (ccn_buf_match_dtag(d, CCN_DTAG_Any)) {
            ccn_buf_advance(d);
            ccn_charbuf_append_string(c, "* ");
            ccn_buf_check_close(d);
        }
        else if (ccn_buf_match_dtag(d, CCN_DTAG_Bloom)) {
            ccn_buf_advance(d);
            if (ccn_buf_match_blob(d, &bloom, &bloom_size))
                ccn_buf_advance(d);
            ccn_charbuf_append_string(c, "? ");
            ccn_buf_check_close(d);
        }
        while (ccn_buf_match_dtag(d, CCN_DTAG_Component)) {
            ccn_buf_advance(d);
            comp_size = 0;
            if (ccn_buf_match_blob(d, &comp, &comp_size))
                ccn_buf_advance(d);
            ccn_uri_append_percentescaped(c, comp, comp_size);
            ccn_charbuf_append_string(c, " ");
            ccn_buf_check_close(d);
            if (ccn_buf_match_dtag(d, CCN_DTAG_Any)) {
                ccn_buf_advance(d);
                ccn_charbuf_append_string(c, "* ");
                ccn_buf_check_close(d);
            }
            else if (ccn_buf_match_dtag(d, CCN_DTAG_Bloom)) {
                ccn_buf_advance(d);
                if (ccn_buf_match_blob(d, &bloom, &bloom_size))
                    ccn_buf_advance(d);
                ccn_charbuf_append_string(c, "? ");
                ccn_buf_check_close(d);
            }
        }
        
        titem = proto_tree_add_text(tree, tvb, pi->offset[CCN_PI_B_Exclude], l,
                                    "%s", ccn_charbuf_as_string(c));
        exclude_tree = proto_item_add_subtree(titem, ett_exclude);
        ccn_charbuf_destroy(&c);

    }
    /* ChildSelector */
    l = pi->offset[CCN_PI_E_ChildSelector] - pi->offset[CCN_PI_B_ChildSelector];
    if (l > 0) {
        i = pi->orderpref;
        titem = proto_tree_add_uint(tree, hf_ccn_childselector, tvb, pi->offset[CCN_PI_B_ChildSelector], l, i);
        proto_item_append_text(titem, ", %s", val_to_str(i & 1, VALS(childselectordirection_vals), ""));
        
    }
    
    /* AnswerOriginKind */
    l = pi->offset[CCN_PI_E_AnswerOriginKind] - pi->offset[CCN_PI_B_AnswerOriginKind];
    if (l > 0) {
        i = pi->answerfrom;
        titem = proto_tree_add_uint(tree, hf_ccn_answeroriginkind, tvb, pi->offset[CCN_PI_B_AnswerOriginKind], l, i);
    }
    
    /* Scope */
    l = pi->offset[CCN_PI_E_Scope] - pi->offset[CCN_PI_B_Scope];
    if (l > 0) {
        i = pi->scope;
        titem = proto_tree_add_uint(tree, hf_ccn_scope, tvb, pi->offset[CCN_PI_B_Scope], l, i);
    }
    
    /* InterestLifetime */
    l = pi->offset[CCN_PI_E_InterestLifetime] - pi->offset[CCN_PI_B_InterestLifetime];
    if (l > 0) {
        i = ccn_ref_tagged_BLOB(CCN_DTAG_InterestLifetime, ccnb,
                                pi->offset[CCN_PI_B_InterestLifetime],
                                pi->offset[CCN_PI_E_InterestLifetime],
                                &blob, &blob_size);
        lifetime = 0.0;
        for (i = 0; i < blob_size; i++)
            lifetime = lifetime * 256.0 + (double)blob[i];
        lifetime /= 4096.0;
        titem = proto_tree_add_double(tree, hf_ccn_interestlifetime, tvb, blob - ccnb, blob_size, lifetime);
    }
    
    /* Nonce */
    l = pi->offset[CCN_PI_E_Nonce] - pi->offset[CCN_PI_B_Nonce];
    if (l > 0) {
        i = ccn_ref_tagged_BLOB(CCN_DTAG_Nonce, ccnb,
                                pi->offset[CCN_PI_B_Nonce],
                                pi->offset[CCN_PI_E_Nonce],
                                &blob, &blob_size);
        if (check_col(pinfo->cinfo, COL_INFO)) {
            col_append_str(pinfo->cinfo, COL_INFO, ", <");
            for (i = 0; i < blob_size; i++)
                col_append_fstr(pinfo->cinfo, COL_INFO, "%02x", blob[i]);
            col_append_str(pinfo->cinfo, COL_INFO, ">");
        }
        titem = proto_tree_add_item(tree, hf_ccn_nonce, tvb,
                                    blob - ccnb, blob_size, ENC_NA);
    }
    
    return (1);
    
}

static int
dissect_ccn_contentobject(const unsigned char *ccnb, size_t ccnb_size, tvbuff_t *tvb, packet_info *pinfo, proto_tree *tree)
{
    proto_tree *signature_tree;
    proto_tree *name_tree;
    proto_tree *signedinfo_tree;
    proto_tree *finalblockid_tree;
    proto_tree *keylocator_tree;
    proto_tree *keylocatorname_tree;
    proto_tree *content_tree;
    proto_item *titem;
    struct ccn_parsed_ContentObject co;
    struct ccn_parsed_ContentObject *pco = &co;
    struct ccn_buf_decoder decoder;
    struct ccn_buf_decoder *d;
    struct ccn_charbuf *c;
    struct ccn_indexbuf *comps;
    const unsigned char *comp;
    size_t comp_size;
    size_t blob_size;
    const unsigned char *blob;
    const unsigned char *ccnb_item;
    int l;
    unsigned int i;
    double dt;
    nstime_t timestamp;
    int res;
    
    comps = ccn_indexbuf_create();
    res = ccn_parse_ContentObject(ccnb, ccnb_size, pco, comps);
    if (res < 0) return (-1);
    
    /* Signature */
    l = pco->offset[CCN_PCO_E_Signature] - pco->offset[CCN_PCO_B_Signature];
    titem = proto_tree_add_item(tree, hf_ccn_signature, tvb, pco->offset[CCN_PCO_B_Signature], l, ENC_NA);
    signature_tree = proto_item_add_subtree(titem, ett_signature);
    
    /* DigestAlgorithm */
    l = pco->offset[CCN_PCO_E_DigestAlgorithm] - pco->offset[CCN_PCO_B_DigestAlgorithm];
    if (l > 0) {
        res = ccn_ref_tagged_BLOB(CCN_DTAG_DigestAlgorithm, ccnb,
                                  pco->offset[CCN_PCO_B_DigestAlgorithm],
                                  pco->offset[CCN_PCO_E_DigestAlgorithm],
                                  &blob, &blob_size);
        titem = proto_tree_add_item(signature_tree, hf_ccn_signaturedigestalg, tvb,
                                    blob - ccnb, blob_size, ENC_NA);
    }
    /* Witness */
    l = pco->offset[CCN_PCO_E_Witness] - pco->offset[CCN_PCO_B_Witness];
    if (l > 0) {
        /* add the witness item to the signature tree */
    }
    
    /* Signature bits */
    l = pco->offset[CCN_PCO_E_SignatureBits] - pco->offset[CCN_PCO_B_SignatureBits];
    if (l > 0) {
        res = ccn_ref_tagged_BLOB(CCN_DTAG_SignatureBits, ccnb,
                                  pco->offset[CCN_PCO_B_SignatureBits],
                                  pco->offset[CCN_PCO_E_SignatureBits],
                                  &blob, &blob_size);
        titem = proto_tree_add_bytes(signature_tree, hf_ccn_signaturebits, tvb,
                                     blob - ccnb, blob_size, blob);
    }
    
    /* /Signature */
    
    /* Name */
    l = pco->offset[CCN_PCO_E_Name] - pco->offset[CCN_PCO_B_Name];
    c = ccn_charbuf_create();
    ccn_uri_append(c, ccnb, ccnb_size, 1);
    titem = proto_tree_add_string(tree, hf_ccn_name, tvb,
                                  pco->offset[CCN_PCO_B_Name], l,
                                  ccn_charbuf_as_string(c));
    name_tree = proto_item_add_subtree(titem, ett_name);
    
    /* Name Components */
    for (i = 0; i < comps->n - 1; i++) {
        ccn_charbuf_reset(c);
        res = ccn_name_comp_get(ccnb, comps, i, &comp, &comp_size);
        ccn_uri_append_percentescaped(c, comp, comp_size);
        titem = proto_tree_add_string(name_tree, hf_ccn_name_components, tvb, comp - ccnb, comp_size, ccn_charbuf_as_string(c));
    }
    ccn_charbuf_destroy(&c);

    /* /Name */
    
    /* SignedInfo */
    l = pco->offset[CCN_PCO_E_SignedInfo] - pco->offset[CCN_PCO_B_SignedInfo];
    titem = proto_tree_add_text(tree, tvb,
                                pco->offset[CCN_PCO_B_SignedInfo], l,
                                "SignedInfo");
    signedinfo_tree = proto_item_add_subtree(titem, ett_signedinfo);
    
    /* PublisherPublicKeyDigest */
    l = pco->offset[CCN_PCO_E_PublisherPublicKeyDigest] - pco->offset[CCN_PCO_B_PublisherPublicKeyDigest];
    if (l > 0) {
        res = ccn_ref_tagged_BLOB(CCN_DTAG_PublisherPublicKeyDigest, ccnb,
                                  pco->offset[CCN_PCO_B_PublisherPublicKeyDigest],
                                  pco->offset[CCN_PCO_E_PublisherPublicKeyDigest],
                                  &blob, &blob_size);
        titem = proto_tree_add_bytes(signedinfo_tree, hf_ccn_publisherpublickeydigest, tvb, blob - ccnb, blob_size, blob);
    }
    
    /* Timestamp */
    l = pco->offset[CCN_PCO_E_Timestamp] - pco->offset[CCN_PCO_B_Timestamp];
    if (l > 0) {
        res = ccn_ref_tagged_BLOB(CCN_DTAG_Timestamp, ccnb,
                                  pco->offset[CCN_PCO_B_Timestamp],
                                  pco->offset[CCN_PCO_E_Timestamp],
                                  &blob, &blob_size);
        dt = 0.0;
        for (i = 0; i < blob_size; i++)
            dt = dt * 256.0 + (double)blob[i];
        dt /= 4096.0;
        timestamp.secs = dt; /* truncates */
        timestamp.nsecs = (dt - (double) timestamp.secs) *  1000000000.0;
        titem = proto_tree_add_time(signedinfo_tree, hf_ccn_timestamp, tvb, blob - ccnb, blob_size, &timestamp);
    }
    
    /* Type */
    l = pco->offset[CCN_PCO_E_Type] - pco->offset[CCN_PCO_B_Type];
    if (l > 0) {
        res = ccn_ref_tagged_BLOB(CCN_DTAG_Type, ccnb,
                                  pco->offset[CCN_PCO_B_Type],
                                  pco->offset[CCN_PCO_E_Type],
                                  &blob, &blob_size);
        titem = proto_tree_add_uint(signedinfo_tree, hf_ccn_contenttype, tvb, blob - ccnb, blob_size, pco->type);
    } else {
        titem = proto_tree_add_uint(signedinfo_tree, hf_ccn_contenttype, NULL, 0, 0, pco->type);
    }
    
    /* FreshnessSeconds */
    l = pco->offset[CCN_PCO_E_FreshnessSeconds] - pco->offset[CCN_PCO_B_FreshnessSeconds];
    if (l > 0) {
        res = ccn_ref_tagged_BLOB(CCN_DTAG_FreshnessSeconds, ccnb,
                                  pco->offset[CCN_PCO_B_FreshnessSeconds],
                                  pco->offset[CCN_PCO_E_FreshnessSeconds],
                                  &blob, &blob_size);
        i = ccn_fetch_tagged_nonNegativeInteger(CCN_DTAG_FreshnessSeconds, ccnb,
                                                pco->offset[CCN_PCO_B_FreshnessSeconds],
                                                pco->offset[CCN_PCO_E_FreshnessSeconds]);
        
        titem = proto_tree_add_uint(signedinfo_tree, hf_ccn_freshnessseconds, tvb, blob - ccnb, blob_size, i);
    }
    
    /* FinalBlockID */
    l = pco->offset[CCN_PCO_E_FinalBlockID] - pco->offset[CCN_PCO_B_FinalBlockID];
    if (l > 0) {
        res = ccn_ref_tagged_BLOB(CCN_DTAG_FinalBlockID, ccnb,
                                  pco->offset[CCN_PCO_B_FinalBlockID],
                                  pco->offset[CCN_PCO_E_FinalBlockID],
                                  &blob, &blob_size);
        titem = proto_tree_add_item(signedinfo_tree, hf_ccn_finalblockid, tvb, blob - ccnb, blob_size, ENC_NA);
        finalblockid_tree = proto_item_add_subtree(titem, ett_finalblockid);
        titem = proto_tree_add_boolean(finalblockid_tree, hf_ccn_finalblockid_final, tvb, blob - ccnb, blob_size,
                                       ccn_is_final_pco(ccnb, pco, comps) ? TRUE : FALSE);
        PROTO_ITEM_SET_GENERATED(titem);
    }
    /* TODO: KeyLocator */
    //   The Key or Certificate or KeyName fields all end at the CCN_PCO_E_Key_Certificate_KeyName offset,
    //   and start at CCN_PCO_B_Key_Certificate_KeyName.   The Key and Certificate cases are blobs.
    //   If it's a KeyName then CCN_PCO_B_KeyName_Name/CCN_PCO_E_KeyName_Name locate the name,
    //      and there is an optional PublisherID located by CCN_PCO_B_KeyName_Pub/CCN_PCO_E_KeyName_Pub
    
    l = pco->offset[CCN_PCO_E_KeyLocator] - pco->offset[CCN_PCO_B_KeyLocator];
    if (l > 0) {
        titem = proto_tree_add_text(signedinfo_tree, tvb,
                                    pco->offset[CCN_PCO_B_KeyLocator], l,
                                    "KeyLocator");
        keylocator_tree = proto_item_add_subtree(titem, ett_keylocator);
        /* A KeyName with optional PublisherID*/
        if ((l = pco->offset[CCN_PCO_E_KeyName_Name] - pco->offset[CCN_PCO_B_KeyName_Name]) > 0) {
            /* Name */
            proto_item_append_text(titem, " [Name]");
            ccnb_item = ccnb + pco->offset[CCN_PCO_B_KeyName_Name];
            d = ccn_buf_decoder_start(&decoder, ccnb_item, l);
            ccn_parse_Name(d, comps);
            c = ccn_charbuf_create();
            ccn_uri_append(c, ccnb_item, l, 1);
            titem = proto_tree_add_string(keylocator_tree, hf_ccn_keylocator_name, tvb,
                                          pco->offset[CCN_PCO_B_KeyName_Name], l,
                                          ccn_charbuf_as_string(c));
            keylocatorname_tree = proto_item_add_subtree(titem, ett_keylocator_name);
            
            /* Name Components */
            for (i = 0; i < comps->n - 1; i++) {
                ccn_charbuf_reset(c);
                res = ccn_name_comp_get(ccnb_item, comps, i, &comp, &comp_size);
                ccn_uri_append_percentescaped(c, comp, comp_size);
                titem = proto_tree_add_string(keylocatorname_tree, hf_ccn_keylocator_name_components, tvb, comp - ccnb, comp_size, ccn_charbuf_as_string(c));
            }
            ccn_charbuf_destroy(&c);
            /* / Name */
            /* optional PublisherID */
            if ((l = pco->offset[CCN_PCO_E_KeyName_Pub] - pco->offset[CCN_PCO_B_KeyName_Pub]) > 0) {
                res = ccn_ref_tagged_BLOB(CCN_DTAG_PublisherPublicKeyDigest, ccnb,
                                          pco->offset[CCN_PCO_B_KeyName_Pub],
                                          pco->offset[CCN_PCO_E_KeyName_Pub],
                                          &blob, &blob_size);
                titem = proto_tree_add_bytes(signedinfo_tree, hf_ccn_keylocator_publisherpublickeydigest, tvb, blob - ccnb, blob_size, blob);
            }
            /* /PublisherID */
        } else {
            /* Either a Key or a Certificate - see which blob parses: CCN_DTAG_Key or CCN_DTAG_Certificate */
            if (0 == ccn_ref_tagged_BLOB(CCN_DTAG_Key, ccnb,
                                         pco->offset[CCN_PCO_B_Key_Certificate_KeyName],
                                         pco->offset[CCN_PCO_E_Key_Certificate_KeyName],
                                         &blob, &blob_size)) {
                /* Key */
                proto_item_append_text(titem, " [Key]");
                titem = proto_tree_add_item(keylocator_tree, hf_ccn_keylocator_key, tvb, blob - ccnb, blob_size, ENC_NA);
            } else if (0 == ccn_ref_tagged_BLOB(CCN_DTAG_Certificate, ccnb,
                                                pco->offset[CCN_PCO_B_Key_Certificate_KeyName],
                                                pco->offset[CCN_PCO_E_Key_Certificate_KeyName],
                                                &blob, &blob_size)) {
                /* Certificate */
                proto_item_append_text(titem, " [Certificate]");
                titem = proto_tree_add_item(keylocator_tree, hf_ccn_keylocator_certificate, tvb, blob - ccnb, blob_size, ENC_NA);
            }
        }
    }
    /* ExtOpt */
    l = pco->offset[CCN_PCO_E_ExtOpt] - pco->offset[CCN_PCO_B_ExtOpt];
    if (l > 0) {
        res = ccn_ref_tagged_BLOB(CCN_DTAG_ExtOpt, ccnb,
                                  pco->offset[CCN_PCO_B_ExtOpt],
                                  pco->offset[CCN_PCO_E_ExtOpt],
                                  &blob, &blob_size);
        
        titem = proto_tree_add_item(signedinfo_tree, hf_ccn_extopt, tvb, blob - ccnb, blob_size, ENC_NA);
    }
    /* /SignedInfo */
    
    /* Content */
    l = pco->offset[CCN_PCO_E_Content] - pco->offset[CCN_PCO_B_Content];
    res = ccn_ref_tagged_BLOB(CCN_DTAG_Content, ccnb,
                              pco->offset[CCN_PCO_B_Content],
                              pco->offset[CCN_PCO_E_Content],
                              &blob, &blob_size);
    titem = proto_tree_add_text(tree, tvb,
                                pco->offset[CCN_PCO_B_Content], l,
                                "Content: %zd bytes", blob_size);
    if (blob_size > 0) {
        content_tree = proto_item_add_subtree(titem, ett_content);
        titem = proto_tree_add_item(content_tree, hf_ccn_contentdata, tvb, blob - ccnb, blob_size, ENC_NA);
    }
    
    return (ccnb_size);
}
