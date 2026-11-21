/*
 * Disco FPV test rig. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 *
 * LD_PRELOAD library for the simulator containers (SITL, FlightGear,
 * Gazebo), which share the host's network: a socket bound to every
 * interface (0.0.0.0 or ::) is bound to loopback instead, so their ports
 * (MAVLink, simulated RC input, FlightGear's property server, which can
 * run scripts) are not reachable from the LAN. Everything in the rig talks
 * to them over 127.0.0.1. Explicit addresses are left alone, and so are
 * the ports listed in LOOPBACK_BIND_KEEP ("10317,10318": Gazebo's
 * multicast discovery, which needs every interface).
 *
 * Build: cc -shared -fPIC -O2 -o loopback-bind.so loopback-bind.c -ldl
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <netinet/in.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>

/* port (host order) listed in LOOPBACK_BIND_KEEP */
static int keep(unsigned port)
{
	const char *p = getenv("LOOPBACK_BIND_KEEP");
	char *end;

	while (p && *p) {
		unsigned long v = strtoul(p, &end, 10);

		if (end == p)
			return 0;
		if (v == port)
			return 1;
		p = *end == ',' ? end + 1 : end;
	}
	return 0;
}

int bind(int fd, const struct sockaddr *addr, socklen_t len)
{
	static int (*real_bind)(int, const struct sockaddr *, socklen_t);

	if (!real_bind)
		real_bind = (int (*)(int, const struct sockaddr *, socklen_t))dlsym(RTLD_NEXT, "bind");
	if (addr && addr->sa_family == AF_INET && len >= (socklen_t)sizeof(struct sockaddr_in)) {
		struct sockaddr_in in;

		memcpy(&in, addr, sizeof(in));
		if (in.sin_addr.s_addr == htonl(INADDR_ANY) && !keep(ntohs(in.sin_port))) {
			in.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
			return real_bind(fd, (const struct sockaddr *)&in, sizeof(in));
		}
	} else if (addr && addr->sa_family == AF_INET6 && len >= (socklen_t)sizeof(struct sockaddr_in6)) {
		struct sockaddr_in6 in6;

		memcpy(&in6, addr, sizeof(in6));
		if (memcmp(&in6.sin6_addr, &in6addr_any, sizeof(in6addr_any)) == 0
				&& !keep(ntohs(in6.sin6_port))) {
			in6.sin6_addr = in6addr_loopback;
			return real_bind(fd, (const struct sockaddr *)&in6, sizeof(in6));
		}
	}
	return real_bind(fd, addr, len);
}
