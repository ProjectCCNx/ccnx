/**
 * @file ccntimefromdatetime.c
 *
 * A little utility for converting canonical dateTime values to
 * the scaled binary form used by ccn
 *
 * A CCNx command-line utility.
 *
 * Copyright (C) 2008, 2009 Palo Alto Research Center, Inc.
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
