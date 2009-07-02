#include <stdlib.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <ccn/matrix.h>

void
Dump(struct ccn_matrix *m)
{
    struct ccn_matrix_bounds r;
    int res = ccn_matrix_getbounds(m, &r);
    printf("ccn_matrix_getbounds res = %d, rows [%ld..%ld), cols [%ld..%ld)\n",
            res,
            (long)r.row_min, (long)r.row_max, 
            (long)r.col_min, (long)r.col_max);
}

int
main(int argc, char **argv)
{
    //char buf[1024] = {0};
    struct ccn_matrix *m;
    
    m = ccn_matrix_create();
    
    Dump(m);
    printf("%d: %ld\n", __LINE__, (long)ccn_matrix_fetch(m, 1, 100));
    Dump(m); printf("                   ccn_matrix_store(m, 1, 100, 30);"  "\n");
                                        ccn_matrix_store(m, 1, 100, 30);
    Dump(m);
    printf("%d: %ld\n", __LINE__, (long)ccn_matrix_fetch(m, 31415926, 101));
    printf("%d: %ld\n", __LINE__, (long)ccn_matrix_fetch(m, 1, 100));
    Dump(m); printf("                   ccn_matrix_store(m, 31415926, 101, 43);"  "\n");
                                        ccn_matrix_store(m, 31415926, 101, 43);
    printf("%d: %ld\n", __LINE__, (long)ccn_matrix_fetch(m, 31415926, 101));
    printf("%d: %ld\n", __LINE__, (long)ccn_matrix_fetch(m, 1, 100));
    Dump(m);
    printf("%d: %ld\n", __LINE__, (long)ccn_matrix_fetch(m, 1, 100));
    Dump(m); printf("                   ccn_matrix_store(m, 1, 100, 0);"  "\n");
                                        ccn_matrix_store(m, 1, 100, 0);
    printf("%d: %ld\n", __LINE__, (long)ccn_matrix_fetch(m, 31415926, 101));
    printf("%d: %ld\n", __LINE__, (long)ccn_matrix_fetch(m, 1, 100));                                         
    Dump(m); printf("                   ccn_matrix_store(m, 31415926, 101, 0);"  "\n");
                                        ccn_matrix_store(m, 31415926, 101, 0);
    printf("%d: %ld\n", __LINE__, (long)ccn_matrix_fetch(m, 31415926, 101));
    printf("%d: %ld\n", __LINE__, (long)ccn_matrix_fetch(m, 1, 100));                                         
    Dump(m);
    ccn_matrix_destroy(&m);
    return(0);
}
