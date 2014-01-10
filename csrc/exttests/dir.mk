# exttests/dir.mk
# 
# Part of the CCNx distribution.
#
# Copyright (C) 2009-2013 Palo Alto Research Center, Inc.
#
# This work is free software; you can redistribute it and/or modify it under
# the terms of the GNU General Public License version 2 as published by the
# Free Software Foundation.
# This work is distributed in the hope that it will be useful, but WITHOUT ANY
# WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
# FOR A PARTICULAR PURPOSE.
#

SCRIPTSRC = testdriver.sh functions preamble settings make_clean.sh $(ALLTESTS)
DUPDIR = stubs

TESTS = $(ALLTESTS)
ALLTESTS = \
  test_alone \
  test_btree_next_leaf \
  test_btree_prev_leaf \
  test_checked_startwrite \
  test_final_teardown \
  test_finished \
  test_happy_face \
  test_late \
  test_long_example \
  test_repo_performance \
  test_single_ccnd \
  test_single_ccnd_teardown \
  test_single_done \
  test_sync_basic \
  test_sync_read \
  test_sync_repo2 \
  test_twohop_ccnd \
  test_twohop_ccnd_teardown
  
default all: $(SCRIPTSRC) testdriver

depend: $(SCRIPTSRC) testlist
# This is a helper to make sure the ALLTESTS list is closed (contains all dependencies)
testlist: $(ALLTESTS)
	echo $(ALLTESTS) | xargs -n 1 | sort -u > seeds
	grep -e '^BEFORE :' -e '^AFTER :' $(ALLTESTS) > deps
	cat deps | cut -d : -f 3 | xargs -n 1 | sort -u > stems
	cat seeds stems | sort -u | diff -u seeds -
	#rm seeds stems deps

clean: make_clean.sh
	sh ./make_clean.sh

check test: $(SCRIPTSRC) testdriver stubs
	MAKE_TEST_TARGET=$@ ./testdriver $(TESTS)
	: ----------------------- :
	:  SCRIPTED TESTS PASSED  :
	: ----------------------- :

testdriver: testdriver.sh
	sh ../util/shebang $(SH) testdriver.sh > testdriver
	chmod +x testdriver

default all clean check test: _always
_always:
