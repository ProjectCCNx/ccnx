# tests/dir.mk
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

SCRIPTSRC = testdriver.sh functions preamble settings $(ALLTESTS)
DUPDIR = stubs

TESTS = $(ALLTESTS)
ALLTESTS = \
  test_alone \
  test_ccndid \
  test_ccnls_meta \
  test_coders \
  test_destroyface \
  test_child_selector \
  test_extopt \
  test_final_teardown \
  test_finished \
  test_happy_face \
  test_interest_suppression \
  test_answered_interest_suppression \
  test_key_fetch \
  test_late \
  test_local_tcp \
  test_long_consumer \
  test_long_consumer2 \
  test_long_producer \
  test_new_provider \
  test_newface \
  test_prefixreg \
  test_selfreg \
  test_short_stuff \
  test_single_ccnd \
  test_single_ccnd_teardown \
  test_spur_traffic \
  test_scope0 \
  test_scope2 \
  test_stale \
  test_twohop_ccnd \
  test_twohop_ccnd_teardown \
  test_unreg \
  test_symmetric

default all: $(SCRIPTSRC) testdriver

clean:
	rm -rf log logs depend testdriver STATUS SKIPPED FAILING \
        *.out *.ccnb *pre.html *post.html *status*.html

check test: $(SCRIPTSRC) testdriver stubs
	mkdir -p log
	./testdriver $(TESTS)
	: -------------- :
	:  TESTS PASSED  :
	: -------------- :

testdriver: testdriver.sh
	../util/shebang $(SH) testdriver.sh > testdriver
	chmod +x testdriver

default all clean check test: _always
_always:
