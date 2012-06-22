/**
 * @file lned.c
 * 
 * Part of the CCNx C Library.
 */
/* Copyright (C) 2012 Palo Alto Research Center, Inc.
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation.
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. You should have received
 * a copy of the GNU Lesser General Public License along with this library;
 * if not, write to the Free Software Foundation, Inc., 51 Franklin Street,
 * Fifth Floor, Boston, MA 02110-1301 USA.
 */

#include <sys/types.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/wait.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <termios.h>
#include <unistd.h>

#include "lned.h"

#define MAX_TERM_WIDTH 255
#define CTL(x) ((x)-'@')

static int fillout(char ch, int k);
static int takedown(int n, int r);
static int term_width(int fd);
static int shuttle(int peer, const char *prompt);

/**
 * Get the terminal width, if possible
 */
static int
term_width(int fd)
{
    int ans = 80;
#ifdef TIOCGWINSZ
    /* Just ignore the structs and pull out the second halfword.        */
    /* If that is wrong, it will be obvious, and won't break horribly.  */
    unsigned short ws[8] = {0}; /* rows, cols, etc. */
    int res;
    res = ioctl(fd, TIOCGWINSZ, ws);
    if (res == 0)
        ans = ws[1];
#endif
    if (ans > MAX_TERM_WIDTH)
        ans = MAX_TERM_WIDTH;
    else if (ans < 12)
        ans = 12;
    return(ans);
}

/**
 * Copy from the peer fd to stdout, and from stdin to peer.
 *
 * A very basic line editor is provided on the input side.
 * The peer will get a line at a time (unless the input is
 * oversize, in which case the input will arrive in chunks).
 * The peer is responsible for echoing the input lines, if
 * appropriate for the application.
 */
static int
shuttle(int peer, const char *prompt)
{
    struct pollfd fds[3];
    char line[MAX_TERM_WIDTH];
    char buf[32];   /* for reading from peer */
    ssize_t sres = 0;
    int e = 0;
    int n = 0;      /* total valid chars in line, including prompt */
    int nmax;       /* limit on n, based on window */
    int ip = 0;     /* insertion point */
    int pl = 0;     /* prompt length */
    int nfds = 0;   /* number of fds to poll */
    int timeout = -1; /* timeout for poll */
    int shows = 0;
    int res;
    char ch;

    nmax = term_width(0);
    memset(fds, 0, sizeof(fds));
    fds[0].fd = peer;
    fds[0].events = POLLIN;
    fds[1].fd = 0;
    fds[1].events = POLLIN;
    fds[2].fd = 1;
    fds[2].events = POLLOUT;
    nfds = 2;
    pl = 0;
    if (prompt != NULL) {
        pl = strlen(prompt);
        if (pl >= nmax)
            pl = 0;
        memcpy(line, prompt, pl);
        n = ip = pl;
    }
    for (;;) {
        if (n == nmax) {
            if (shows != 0)
                takedown(ip, n - ip);
            shows = 0;
            if (ip == pl)
                ip = pl + 1;
            sres = write(peer, line + pl, ip - pl);
            memmove(line + pl, line + ip, n - ip);
            n -= (ip - pl);
            ip = pl;
            continue;
        }
        res = poll(fds, nfds, shows ? timeout : 50);
        if (res < 0) {
            perror("poll");
            if (errno == EINTR)
                res = 0;
            else
                return(-1);
        }
        if (res == 0) {
            if (shows == 0) {
                write(2, line, n);
                fillout('\b', n - ip);
                shows = 1;
            }
        }
        if ((fds[0].revents & POLLIN) != 0) {
            if (shows != 0)
                takedown(ip, n - ip);
            shows = 0;
            sres = read(peer, buf, sizeof(buf));
            if (sres == 0)
                return(n);
            if (sres < 0)
                return(-1);
            write(1, buf, sres);
        }
        if ((fds[1].revents & POLLNVAL) != 0) {
            /* could be a broken poll implementation */
            nfds = 1;
            fds[1].revents = POLLIN;
            timeout = 150;
            fcntl(0, F_SETFL, O_NONBLOCK);
        }
        ch = '\0';
        sres = 0;
        if ((fds[1].revents & POLLIN) != 0) {
            sres = read(0, &ch, 1);
            if (sres == 0 || (sres < 0 && errno != EAGAIN))
                ch = CTL('D');
        }
        if (ch != 0) {
            if (' ' <= ch && ch <= '~') {
                if (ip < n) {
                    takedown(ip, n - ip);
                    shows = 0;
                    memmove(line+ip+1, line+ip, n - ip);
                }
                line[ip++] = ch;
                n = n + 1;
                if (shows != 0)
                    write(2, &ch, 1);
                continue;
            }
            if (ch == CTL('D')) {
                e = errno;
                takedown(ip, n - ip);
                write(peer, line + pl, n - pl);
                errno = e;
                return(sres);
            }
            if (ch == CTL('B') && ip > pl) {
                if (shows != 0)
                    write(2, "\b", 1);
                ip = ip - 1;
                continue;
            }
            if (ch == CTL('F') && ip < n) {
                if (shows != 0)
                    write(2, line + ip, 1);
                ip = ip + 1;
                continue;
            }
            if (ch == CTL('K')) {
                if (shows != 0)
                    takedown(0, n - ip);
                n = ip;
                continue;
            }
            if (ch == CTL('D') && ip < n) {
                if (shows != 0)
                    takedown(ip, n - ip);
                shows = 0;
                n = n - 1;
                memmove(line+ip, line+ip+1, n - ip);
                continue;
            }
            if ((ch == '\b' || ch == '\177') && ip > pl) {
                if (ip < n) {
                    if (shows != 0)
                        takedown(ip, n - ip);
                    shows = 0;
                    memmove(line+ip-1, line+ip, n - ip);
                }
                if (shows != 0)
                    write(2, "\b \b", 3);
                ip = ip - 1;
                n = n - 1;
                continue;
            }
            if (ch == '\n') {
                if (shows != 0)
                    takedown(ip, n - ip);
                shows = 0;
                line[n++] = ch;
                sres = write(peer, line + pl, n - pl);
                n = ip = pl;
                continue;
            }
            if (ch == CTL('A')) {
                if (shows != 0)
                    fillout('\b', ip - pl);
                ip = pl;
                continue;
            }
            if (ch == CTL('E')) {
                if (shows != 0 && ip < n)
                    write(2, line + ip, n - ip);
                ip = n;
                continue;
            }
            write(2, "\007", 1);  /* BEL */
            continue;
        }
    }
}

/**
 * Write k instances of ch.
 */
static int
fillout(char ch, int k)
{
    char buf[32];
    
    memset(buf, ch, sizeof(buf));
    while (k > sizeof(buf)) {
        write(2, buf, sizeof(buf));
        k -= sizeof(buf);
    }
    if (k > 0)
        write(2, buf, k);
    return(0);
}

/**
 * Erase n chars to the left of the cursor, and r to the right.
 */
static int
takedown(int n, int r)
{
    if (r > 0) {
        fillout(' ', r);
        fillout('\b', r);
    }
    if (n > 0) {
        fillout('\b', n);
        fillout(' ', n);
        fillout('\b', n);
    }
    return(0);
}

/**
 * Interpose a simple line editor in front of a command-line utility
 *
 * This should be called early in the application's main program,
 * in particular before the creation of threads or the use of stdio.
 *
 * If both stdin and stdout are tty devices, worker() is called in a forked
 * process, and it may use the standard file descriptors in a conventional
 * fashion.  Otherwise worker() is just called directly.
 */
int
lned_run(int argc, char** argv, const char *prompt, int (*worker)(int, char**))
{
    struct termios tc[4];
    struct termios *t;
    char cb[8];
    int sp[2] = {-1, -1};
    int res;
    int i;
    int e = 0;
    int st;
    pid_t pid;

    memset(tc, 0, sizeof(tc));
    for (i = 0; i < 3; i++) {
        res = tcgetattr(i, &(tc[i]));
        if (res < 0 && i < 2)
            goto Direct;
    }
    res = socketpair(AF_UNIX, SOCK_STREAM, 0, sp);
    if (res < 0) goto Direct;
    t = &(tc[3]);
    *t = tc[0];
    t->c_lflag &= ~(ECHO | ICANON);
    t->c_cc[VMIN] = 1;
    t->c_cc[VTIME] = 0;
    res = tcsetattr(0, TCSANOW, t);
    if (res < 0) goto Direct;
    pid = fork();
    if (pid == 0) {
        dup2(sp[1], 0);
        dup2(sp[1], 1);
        if (isatty(2))
            dup2(sp[1], 2);
        close(sp[0]);
        close(sp[1]);
        goto Direct;
    }
    close(sp[1]);
    shuttle(sp[0], prompt);
    shutdown(sp[0], SHUT_WR);
    while (read(sp[0], cb, 1) == 1)
        write(1, cb, 1);
    wait(&st);
    tcsetattr(0, TCSANOW, &(tc[0]));
    return(st);
Direct:
    return(worker(argc, argv));
}
