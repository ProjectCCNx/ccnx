/**
 * @file ccndc-srv.h
 * @brief ccndc handling of SRV record lookup
 *
 * A CCNx program.
 *
 * Copyright (C) 2009-2010 Palo Alto Research Center, Inc.
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

#ifndef CCNDC_SRV_H
#define CCNDC_SRV_H

/**
 * @brief Register / prefix and start dynamically creating entries based on SRV records
 *
 * This call will block thread forever
 */
void
ccndc_daemonize (struct ccndc_data *ccndc);

/**
 * @brief Query SRV record of the domain
 * @param domain        domain name
 * @param domain_size   size of the "domain" variable
 * @param hostp         [out] gateway's IP address specified by an SRV record
 * @param portp         [out] gateway's port specified by an SRV record
 * @param proto         [out] gateway's protocol specified by an SRV record
 * @return 0 on success
 */
int
ccndc_query_srv (const unsigned char *domain, int domain_size,
                 char **hostp, int *portp, char **proto);

#endif
