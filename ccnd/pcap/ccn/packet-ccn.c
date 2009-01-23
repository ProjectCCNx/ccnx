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

/* forward reference */
void proto_register_ccn();
void proto_reg_handoff_ccn();
static void dissect_ccn(tvbuff_t *tvb, packet_info *pinfo, proto_tree *tree);
static gboolean dissect_ccn_heur( tvbuff_t *tvb, packet_info *pinfo,
                                  proto_tree *tree );

static int proto_ccn = -1;
static int global_ccn_port = 4485;
static dissector_handle_t ccn_handle;

/* Try heuristic CCN decode */
static gboolean global_ccn_heur = FALSE;


void
proto_register_ccn(void)
{
	if (proto_ccn == -1) {
		proto_ccn = proto_register_protocol (
			"CCN Protocol",	/* name */
			"CCN",		/* short name */
			"ccn"		/* abbrev */
			);
	}
}

void
proto_reg_handoff_ccn(void)
{
	static gboolean initialized = FALSE;

	if (!initialized) {
		ccn_handle = create_dissector_handle(dissect_ccn, proto_ccn);
		dissector_add("udp.port", global_ccn_port, ccn_handle);
        dissector_add_handle("udp.port", ccn_handle); 
        heur_dissector_add("udp", dissect_ccn_heur, proto_ccn);
		initialized = TRUE;
	}
}
   
static void
dissect_ccn(tvbuff_t *tvb, packet_info *pinfo, proto_tree *tree)
{
	if (check_col(pinfo->cinfo, COL_PROTOCOL)) {
		col_set_str(pinfo->cinfo, COL_PROTOCOL, "CCN");
	}
	/* Clear out stuff in the info column */
	if (check_col(pinfo->cinfo,COL_INFO)) {
		col_clear(pinfo->cinfo,COL_INFO);
	}
}

static gboolean
dissect_ccn_heur(tvbuff_t *tvb, packet_info *pinfo, proto_tree *tree)
{
    int is_ccn = 0;  /* eventually, have to guess... */

    if (!global_ccn_heur)
        return FALSE;

	/* This is a heuristic dissector, which means we get all the UDP
	 * traffic not sent to a known dissector and not claimed by
	 * a heuristic dissector called before us!
	 */
    if (is_ccn) {
        dissect_ccn(tvb, pinfo, tree);
        return TRUE;
    }

    return FALSE;
}

   
