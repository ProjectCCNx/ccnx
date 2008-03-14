/*
 *  ccnd.c
 *  
 *  Copyright 2008 Palo Alto Research Center, Inc. All rights reserved.
 *
 */

#include <sys/types.h>
#include <errno.h>
#include <signal.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <ccn/ccnd.h>
#include <ccn/charbuf.h>
#include <ccn/coding.h>
#include <ccn/hashtb.h>

static const char *unlink_this_at_exit = NULL;
static void
cleanup_at_exit(void)
{
    if (unlink_this_at_exit != NULL) {
        unlink(unlink_this_at_exit);
        unlink_this_at_exit = NULL;
    }
}
static void
handle_fatal_signal(int sig) {
    cleanup_at_exit();
    _exit(sig);
}

static void
unlink_at_exit(const char *path)
{
    if (unlink_this_at_exit == NULL) {
        unlink_this_at_exit = path;
        signal(SIGTERM, &handle_fatal_signal);
        signal(SIGINT, &handle_fatal_signal);
        signal(SIGHUP, &handle_fatal_signal);
        atexit(&cleanup_at_exit);
    }
}

static int
create_local_listener(const char *sockname, int backlog)
{
    int res;
    int sock;
    struct sockaddr_un a = { 0 };
    res = unlink(sockname);
    if (!(res == 0 || errno == ENOENT))
        fprintf(stderr, "failed to unlink %s\n", sockname);
    a.sun_family = AF_UNIX;
    strncpy(a.sun_path, sockname, sizeof(a.sun_path));
    sock = socket(AF_UNIX, SOCK_STREAM, 0);
    if (sock == -1)
        return(sock);
    res = bind(sock, (struct sockaddr *)&a, sizeof(a));
    if (res == -1) {
        close(sock);
        return(-1);
    }
    unlink_at_exit(sockname);
    res = listen(sock, backlog);
    if (res == -1) {
        close(sock);
        return(-1);
    }
    return(sock);
}

int
main(int argc, char **argv)
{
    const char *sockname = CCN_DEFAULT_LOCAL_SOCKNAME;
    int ll;
    int fd;
    struct sockaddr who;
    socklen_t wholen = sizeof(who);
    char buf[1];
    
    ll = create_local_listener(sockname, 42);
    if (ll == -1) {
        perror(sockname);
        exit(1);
    }
    fprintf(stderr, "ccnd[%d] listening on %s\n", (int)getpid(), sockname);
    fd = accept(ll, &who, &wholen);
    if (fd == -1) {
        perror("accept");
        exit(1);
    }
    while (read(fd, buf, 1) == 1)
        continue;
    close(fd);
    fprintf(stderr, "ccnd[%d] exiting.\n", (int)getpid());
    exit(0);
}
