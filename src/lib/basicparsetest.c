#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <ccn/ccn.h>
#include <ccn/charbuf.h>
#include <ccn/coding.h>
#include <ccn/face_mgmt.h>
#include <ccn/sockcreate.h>
#include <ccn/reg_mgmt.h>

/* For manual testing. Run under a debugger. */
int
main (int argc, char **argv)
{
	unsigned char buf[1000];
	ssize_t size;
	struct ccn_face_instance *face_instance;
        struct ccn_forwarding_entry *forwarding_entry;
        int res = 1;

	size = read(0, buf, sizeof(buf));
	if (size < 0)
		exit(0);
	
        face_instance = ccn_face_instance_parse(buf, size);
	if (face_instance != NULL) {
		printf("face_instance OK\n");
                res = 0;
	}
	ccn_face_instance_destroy(& face_instance);
        
        forwarding_entry = ccn_forwarding_entry_parse(buf, size);
        if (forwarding_entry != NULL) {
		printf("forwarding_entry OK\n");
                res = 0;
	}
        ccn_forwarding_entry_destroy(&forwarding_entry);
        
        if (res != 0) {
                printf("URP\n");
        }
	exit(res);
}
