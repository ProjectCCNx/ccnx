/*
 * A little utility for converting canonical dateTime values to the scaled binary form used by ccn
 * Copyright 2009 Palo Alto Research Center, Inc. All rights reserved.
 */
#include <time.h>
#include <math.h>
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

static int
cvt_a_date(char *s)
{
    char *leftover;
    char *z = "?";
    struct tm tm = {0};
    time_t seconds;
    double fraction = 0.0;
    double fulltime;
    double back;
    int res = 0;
    intmax_t fixedscaled;
    
    leftover = strptime(s, "%FT%T", &tm);
    seconds = timegm(&tm);
    if (leftover != NULL)
        fraction = strtod(leftover, &z);
    if (0 != strcmp(z, "Z") || seconds <= 0 ||
        fraction < 0.0 || fraction >= 1.0) {
        res = 1;
        fprintf(stderr, "problem converting %s\n", s);
    }
    else {
        fulltime = (((double)seconds) + fraction);
        fixedscaled = (intmax_t)round(fulltime * (double)(1U << 12));
        back = (double)fixedscaled / 4096.0; /* Check */
        printf("%s\t%012jX\t%f\t%f\n", s, fixedscaled, fulltime, back);
    }
    return(res);
}

int
main(int argc, char **argv)
{
    int i;
    int res = 0;
    for (i = 1; i < argc; i++)
        res |= cvt_a_date(argv[i]);
    return(res == 0 ? 0 : 1);
}
