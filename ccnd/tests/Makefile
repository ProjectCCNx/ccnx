# Makefile for tests directory
SCRIPTSRC = testdriver functions preamble settings $(ALLTESTS)
DUPDIR = stubs

TESTS = $(ALLTESTS)
ALLTESTS = \
  test_alone \
  test_final_teardown \
  test_finished \
  test_happy_face \
  test_inject \
  test_key_fetch \
  test_late \
  test_long_consumer \
  test_long_consumer2 \
  test_long_producer \
  test_sans_udplink \
  test_short_stuff \
  test_single_ccnd \
  test_single_ccnd_teardown \
  test_spur_traffic \
  test_stale \
  test_twohop_ccnd \
  test_twohop_ccnd_teardown

default all: $(SCRIPTSRC)
	@: nothing to do in tests

clean:
	rm -rf log logs depend STATUS SKIPPED FAILING *.out ephemeral*.ccnb keyfetch*.ccnb

check test: $(SCRIPTSRC)  stubs
	mkdir -p log
	./testdriver $(TESTS)
	: -------------- :
	:  TESTS PASSED  :
	: -------------- :

default all clean check test: _always
_always:
