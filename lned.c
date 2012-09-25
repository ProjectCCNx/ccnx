/*
 * Copyright (C) 2012 Palo Alto Research Center, Inc.
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

#include <sys/types.h>
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

#define LINE_MAX 40
#define CTL(x) ((x)-'@')

int child_main(int argc, char** argv);
int main(int argc, char** argv);
static int shuttle(int peer, const char *prompt);
static int fillout(char ch, int k);
static int takedown(int n, int extra);

/**
 * Copy from the peer fd to stdout, and from stdin to peer.
 *
 * A very basic line editor is provided on the input side.
 * The peer will get a line at a time (unless the input is
 * oversize, in which case the input will arrive in chunks).
 * in which case the input will arrive in chunks).
 * The peer is responsible for echoing the input lines, if
 * appropriate for the application.
 */
static int
shuttle(int peer, const char *prompt)
{
    struct pollfd fds[3];
    char line[LINE_MAX];
    ssize_t sres = 0;
    int e = 0;
    int n = 0;      /* total valid chars in line, including prompt */
    int ip = 0;     /* insertion point */
    int pl = 0;     /* prompt length */
    int shows = 0;
    int res;
    char ch;

    memset(fds, 0, sizeof(fds));
    fds[0].fd = 0;
    fds[0].events = POLLIN;
    fds[1].fd = 1;
    fds[1].events = 0;
    fds[2].fd = peer;
    fds[2].events = POLLIN;
    pl = 0;
    if (prompt != NULL) {
        pl = strlen(prompt);
        if (pl >= LINE_MAX)
            pl = 0;
        memcpy(line, prompt, pl);
        n = ip = pl;
    }
    for (;;) {
        if (n == LINE_MAX) {
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
        res = poll(fds, 3, shows ? -1 : 50);
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
            continue;
        }
        if ((fds[0].revents & POLLIN) != 0) {
            sres = read(0, &ch, 1);
            if (sres == 1 && ch == CTL('D') && n == ip)
                sres = 0;  /* ^D at EOL is EOF */
            if (sres <= 0) {
                e = errno;
                takedown(ip, n - ip);
                write(peer, line + pl, n - pl);
                errno = e;
                return(sres);
            }
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
        if ((fds[2].revents & POLLIN) != 0) {
            if (shows != 0)
                takedown(ip, n - ip);
            shows = 0;
            sres = read(peer, &ch, 1);
            if (sres == 0)
                return(n);
            if (sres < 0)
                return(-1);
            write(1, &ch, 1);
        }
    }
}

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

static int
takedown(int n, int extra)
{
    if (extra > 0) {
        fillout(' ', extra);
        fillout('\b', extra);
    }
    if (n > 0) {
        fillout('\b', n);
        fillout(' ', n);
        fillout('\b', n);
    }
    return(0);
}

int
main(int argc, char** argv)
{
    struct termios tc[4];
    struct termios *t;
    char cb[8];
    int sp[2] = {-1, -1};
    int i;
    int res;
    int st;
    pid_t pid;

    memset(tc, 0, sizeof(tc));
    for (i = 0; i < 3; i++) {
        res = tcgetattr(i, &(tc[i]));
        if (res < 0 && i < 2)
            goto Direct;
    }
    res = socketpair(AF_UNIX, SOCK_STREAM, 0, sp);
    if (res < 0) perror("socketpair");
    t = &(tc[3]);
    *t = tc[0];
    t->c_lflag &= ~(ECHO | ECHOCTL | ICANON);
    res = tcsetattr(0, TCSANOW, t);
    if (res < 0) {
        perror("tcsetattr stdin");
        exit(1);
    }
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
    fprintf(stderr, "Child is %d; gdb %s %d\n", (int)pid, argv[0], (int)getpid());
    shuttle(sp[0], "Chat.. ");
    shutdown(sp[0], SHUT_WR);
    while (read(sp[0], cb, 1) == 1)
        write(1, cb, 1);
    wait(&st);
    tcsetattr(0, TCSANOW, &(tc[0]));
    return(st);
Direct:
    st = child_main(argc, argv);
    return(st);
}

#if 0
int
child_main(int argc, char** argv)
{
    char ch;
    ssize_t sres;

    write(1, "Hello, world\n", 13);
    for (;;) {
        sres = read(0, &ch, 1);
        if (sres <= 0)
            break;
        if ('a' <= ch && ch <= 'z')
            ch -= 'z'-'Z';
        sleep(1);
        write(1, &ch, 1);
    }
    return(0);
}
#endif
