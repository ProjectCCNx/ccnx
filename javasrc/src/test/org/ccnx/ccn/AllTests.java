/*
 * CCNx AllTests
 *
 * Copyright (C) 2013 Palo Alto Research Center, Inc.
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
 * Boston, MA 02
 */
package org.ccnx.ccn;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;

@RunWith(Suite.class)

@SuiteClasses({
	org.ccnx.ccn.encoding.AllTests.class,
	org.ccnx.ccn.impl.AllTests.class,
    org.ccnx.ccn.protocol.AllTests.class,
    org.ccnx.ccn.io.AllTests.class,
    org.ccnx.ccn.profiles.AllTests.class,
    org.ccnx.ccn.repo.AllTests.class,
    org.ccnx.ccn.security.crypto.AllTests.class
})
public class AllTests {
}