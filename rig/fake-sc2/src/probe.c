/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 *
 * disco-probe: a scripted ARSDK controller for the fake's protocol tests.
 * Connects to fake-sc2 over mux (TCP, like the phone through the
 * SkyController's USB link), then runs a script read from stdin, one step
 * per line:
 *   send <Project.Class.Command> [args...]   a command, its arguments by
 *                                   type: numbers, enum names (or values),
 *                                   strings ("" for an empty one)
 *   expect <text> [timeout_ms]      a received command (formatted like
 *                                   "Name | Arg=value | ...") containing the
 *                                   text, after the previous match
 *   absent <text> [ms]              no such command for that long
 *   mark                            later expects only see newer commands
 *   ctrl <line>                     a line to the fake's control socket,
 *                                   which must answer "ok"
 *   ctrl_expect <text> <line>       same, until the answer contains the
 *                                   text ("quoted" when it has spaces),
 *                                   polled up to the default timeout
 *   wait <ms>
 *   # comment
 * Every received command is printed ("< ..."), every sent one ("> ...").
 * Exit status: 0 when every step passed, 1 otherwise.
 *
 * usage: disco-probe [--port <mux port>] [--ctrl-port <port>]
 *                    [--timeout <default expect ms>] < script
 */
#include <stdlib.h>
#include <stdio.h>
#include <stdarg.h>
#include <string.h>
#include <strings.h>
#include <errno.h>
#include <signal.h>
#include <unistd.h>
#include <time.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <arpa/inet.h>

#include <libpomp.h>
#include <libmux.h>

#include "arsdkctrl/arsdkctrl.h"
#define ULOG_TAG probe
#include "ulog.h"
ULOG_DECLARE_TAG(probe);

#define MAX_STEPS    512
#define MAX_RECV     8192
#define TICK_MS      50
#define CONNECT_MS   15000

struct app {
	struct pomp_loop             *loop;
	struct pomp_timer            *timer;
	struct arsdk_ctrl            *ctrl;
	struct arsdkctrl_backend_mux *backend_mux;
	struct arsdk_discovery_mux   *discovery_mux;
	struct arsdk_device          *device;
	struct arsdk_cmd_itf         *cmd_itf;
	struct {
		struct pomp_ctx   *pompctx;
		struct mux_ctx    *muxctx;
		uint16_t          port;
	} mux;
	uint16_t  ctrl_port;
	int       default_timeout;

	char      *steps[MAX_STEPS];
	int       nsteps, step;
	uint64_t  step_deadline;     /* 0: step not started */
	char      *recv[MAX_RECV];
	int       nrecv, mark;
	int       done, failed;
	uint64_t  start_ms;
	uint64_t  last_ctrl_ms;      /* last ctrl_expect poll */
};

static struct app s_app;

static uint64_t now_ms(void)
{
	struct timespec ts;
	clock_gettime(CLOCK_MONOTONIC, &ts);
	return (uint64_t)ts.tv_sec * 1000 + (uint64_t)ts.tv_nsec / 1000000;
}

static void finish(struct app *app, int failed, const char *fmt, ...)
	__attribute__((format(printf, 3, 4)));

static void finish(struct app *app, int failed, const char *fmt, ...)
{
	va_list ap;
	if (app->done)
		return;
	app->done = 1;
	app->failed = failed;
	va_start(ap, fmt);
	vprintf(fmt, ap);
	va_end(ap);
	fflush(stdout);
	pomp_loop_wakeup(app->loop);
}

/* ------------------------------------------------------------------ */
/* Commands by name                                                    */
/* ------------------------------------------------------------------ */

static const struct arsdk_cmd_desc *find_desc(const char *name)
{
	const struct arsdk_cmd_desc * const * const * const *prj;
	const struct arsdk_cmd_desc * const * const *cls;
	const struct arsdk_cmd_desc * const *cmd;
	for (prj = g_arsdk_cmd_desc_table; *prj != NULL; prj++)
		for (cls = *prj; *cls != NULL; cls++)
			for (cmd = *cls; *cmd != NULL; cmd++)
				if (strcasecmp((*cmd)->name, name) == 0)
					return *cmd;
	return NULL;
}

static int parse_arg(const struct arsdk_arg_desc *desc, const char *tok,
		struct arsdk_value *val)
{
	char *end = NULL;
	uint32_t i;
	val->type = desc->type;
	switch (desc->type) {
	case ARSDK_ARG_TYPE_I8:  val->data.i8 = (int8_t)strtol(tok, &end, 0); break;
	case ARSDK_ARG_TYPE_U8:  val->data.u8 = (uint8_t)strtoul(tok, &end, 0); break;
	case ARSDK_ARG_TYPE_I16: val->data.i16 = (int16_t)strtol(tok, &end, 0); break;
	case ARSDK_ARG_TYPE_U16: val->data.u16 = (uint16_t)strtoul(tok, &end, 0); break;
	case ARSDK_ARG_TYPE_I32: val->data.i32 = (int32_t)strtol(tok, &end, 0); break;
	case ARSDK_ARG_TYPE_U32: val->data.u32 = (uint32_t)strtoul(tok, &end, 0); break;
	case ARSDK_ARG_TYPE_I64: val->data.i64 = strtoll(tok, &end, 0); break;
	case ARSDK_ARG_TYPE_U64: val->data.u64 = strtoull(tok, &end, 0); break;
	case ARSDK_ARG_TYPE_FLOAT: val->data.f32 = strtof(tok, &end); break;
	case ARSDK_ARG_TYPE_DOUBLE: val->data.f64 = strtod(tok, &end); break;
	case ARSDK_ARG_TYPE_STRING:
		val->data.cstr = strcmp(tok, "\"\"") == 0 ? "" : tok;
		return 0;
	case ARSDK_ARG_TYPE_ENUM:
		for (i = 0; i < desc->enum_desc_count; i++) {
			if (strcasecmp(desc->enum_desc_table[i].name, tok) == 0) {
				val->data.i32 = desc->enum_desc_table[i].value;
				return 0;
			}
		}
		val->data.i32 = (int32_t)strtol(tok, &end, 0);
		break;
	default:
		return -EINVAL;
	}
	return end != NULL && *end == '\0' && end != tok ? 0 : -EINVAL;
}

static int send_line(struct app *app, char *args)
{
	struct arsdk_value argv[16];
	struct arsdk_cmd cmd;
	const struct arsdk_cmd_desc *desc;
	char *save = NULL, *name, *tok;
	char buf[512];
	uint32_t i;
	int res;

	name = strtok_r(args, " \t", &save);
	if (name == NULL || (desc = find_desc(name)) == NULL) {
		printf("FAIL unknown command '%s'\n", name ? name : "");
		return -ENOENT;
	}
	if (desc->arg_desc_count > 16)
		return -E2BIG;
	for (i = 0; i < desc->arg_desc_count; i++) {
		tok = strtok_r(NULL, " \t", &save);
		if (tok == NULL || parse_arg(&desc->arg_desc_table[i], tok, &argv[i]) < 0) {
			printf("FAIL %s: argument %s: '%s'\n", desc->name,
					desc->arg_desc_table[i].name, tok ? tok : "(missing)");
			return -EINVAL;
		}
	}
	arsdk_cmd_init(&cmd);
	res = arsdk_cmd_enc_argv(&cmd, desc, desc->arg_desc_count, argv);
	if (res == 0) {
		arsdk_cmd_fmt(&cmd, buf, sizeof(buf));
		printf("> %s [%llu ms]\n", buf,
				(unsigned long long)(now_ms() - app->start_ms));
		res = arsdk_cmd_itf_send(app->cmd_itf, &cmd, NULL, NULL);
	}
	arsdk_cmd_clear(&cmd);
	if (res < 0)
		printf("FAIL send %s: %s\n", desc->name, strerror(-res));
	return res;
}

/* ------------------------------------------------------------------ */
/* Control socket                                                      */
/* ------------------------------------------------------------------ */

static int ctrl_query(struct app *app, const char *line, char *reply, size_t len)
{
	struct sockaddr_in addr;
	ssize_t n;
	size_t got = 0;
	int fd = socket(AF_INET, SOCK_STREAM, 0);
	if (fd < 0)
		return -errno;
	memset(&addr, 0, sizeof(addr));
	addr.sin_family = AF_INET;
	addr.sin_port = htons(app->ctrl_port);
	addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
	if (connect(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0
			|| write(fd, line, strlen(line)) < 0 || write(fd, "\n", 1) < 0) {
		int err = errno;
		close(fd);
		return -err;
	}
	while (got + 1 < len && (n = read(fd, reply + got, len - 1 - got)) > 0) {
		got += (size_t)n;
		if (memchr(reply, '\n', got) != NULL)
			break;
	}
	reply[got] = '\0';
	if (got > 0 && reply[got - 1] == '\n')
		reply[got - 1] = '\0';
	close(fd);
	return 0;
}

/* ------------------------------------------------------------------ */
/* Script                                                              */
/* ------------------------------------------------------------------ */

static int find_recv(struct app *app, const char *text)
{
	int i;
	for (i = app->mark; i < app->nrecv; i++) {
		if (strstr(app->recv[i], text) != NULL)
			return i;
	}
	return -1;
}

/* "<text> [ms]": splits the optional trailing timeout off the text. */
static int text_and_timeout(struct app *app, char *arg)
{
	char *sp = strrchr(arg, ' ');
	char *end = NULL;
	long ms;
	if (sp != NULL) {
		ms = strtol(sp + 1, &end, 10);
		if (end != sp + 1 && *end == '\0' && ms > 0) {
			*sp = '\0';
			return (int)ms;
		}
	}
	return app->default_timeout;
}

/* Runs steps until one has to wait; called on each event and tick. */
static void run_steps(struct app *app)
{
	char line[1024], reply[1024];
	char *verb, *arg;
	int idx, ms;

	while (!app->done && app->step < app->nsteps) {
		snprintf(line, sizeof(line), "%s", app->steps[app->step]);
		verb = line;
		arg = strchr(line, ' ');
		if (arg != NULL) {
			*arg++ = '\0';
			while (*arg == ' ')
				arg++;
		} else {
			arg = line + strlen(line);
		}
		if (app->step_deadline == 0)
			printf("## %s\n", app->steps[app->step]);

		if (strcmp(verb, "send") == 0) {
			if (send_line(app, arg) < 0)
				{ finish(app, 1, "FAIL step %d: %s\n",
						app->step + 1, app->steps[app->step]); return; }
		} else if (strcmp(verb, "expect") == 0 || strcmp(verb, "absent") == 0) {
			int absent = verb[0] == 'a';
			ms = text_and_timeout(app, arg);
			if (app->step_deadline == 0)
				app->step_deadline = now_ms() + (uint64_t)ms;
			idx = find_recv(app, arg);
			if (!absent && idx >= 0) {
				app->mark = idx + 1;
			} else if (absent && idx >= 0) {
				{ finish(app, 1, "FAIL step %d: unexpected '%s': %s\n",
						app->step + 1, arg, app->recv[idx]); return; }
			} else if (now_ms() < app->step_deadline) {
				return; /* wait */
			} else if (!absent) {
				{ finish(app, 1, "FAIL step %d: no '%s' within %d ms\n",
						app->step + 1, arg, ms); return; }
			}
		} else if (strcmp(verb, "mark") == 0) {
			app->mark = app->nrecv;
		} else if (strcmp(verb, "wait") == 0) {
			if (app->step_deadline == 0)
				app->step_deadline = now_ms() + (uint64_t)atoi(arg);
			if (now_ms() < app->step_deadline)
				return;
		} else if (strcmp(verb, "ctrl") == 0 || strcmp(verb, "ctrl_expect") == 0) {
			const char *want = "ok";
			char *query = arg;
			if (verb[4] == '_' && arg[0] == '"') {
				/* "text with spaces" <line> */
				char *q = strchr(arg + 1, '"');
				if (q == NULL || q[1] != ' ')
					{ finish(app, 1, "FAIL step %d: unterminated quote\n",
							app->step + 1); return; }
				*q = '\0';
				want = arg + 1;
				query = q + 2;
			} else if (verb[4] == '_') {
				char *sp = strchr(arg, ' ');
				if (sp == NULL)
					{ finish(app, 1, "FAIL step %d: ctrl_expect <text> <line>\n",
							app->step + 1); return; }
				*sp = '\0';
				want = arg;
				query = sp + 1;
			}
			if (app->step_deadline == 0) {
				app->step_deadline = now_ms() + (uint64_t)app->default_timeout;
			} else if (now_ms() - app->last_ctrl_ms < 200) {
				return; /* polling: not on every received command */
			}
			app->last_ctrl_ms = now_ms();
			if (ctrl_query(app, query, reply, sizeof(reply)) < 0)
				{ finish(app, 1, "FAIL step %d: control socket\n", app->step + 1); return; }
			int want_error = strncmp(want, "error", 5) == 0;
			if (strstr(reply, want) == NULL
					|| (strncmp(reply, "error", 5) == 0 && !want_error)) {
				/* ctrl_expect polls until the default timeout */
				if (verb[4] == '_' && (want_error || strncmp(reply, "error", 5) != 0)
						&& now_ms() < app->step_deadline)
					return;
				printf("ctrl %s -> %s\n", query, reply);
				{ finish(app, 1, "FAIL step %d: '%s' answered '%s'\n",
						app->step + 1, query, reply); return; }
			}
			printf("ctrl %s -> %s\n", query, reply);
		} else {
			{ finish(app, 1, "FAIL step %d: unknown step '%s'\n",
					app->step + 1, verb); return; }
		}
		app->step++;
		app->step_deadline = 0;
	}
	if (!app->done && app->step >= app->nsteps)
		finish(app, 0, "PASS %d steps\n", app->nsteps);
}

static void timer_cb(struct pomp_timer *timer, void *userdata)
{
	struct app *app = userdata;
	if (app->cmd_itf == NULL) {
		if (now_ms() - app->start_ms > CONNECT_MS)
			finish(app, 1, "FAIL not connected within %d ms\n", CONNECT_MS);
		return;
	}
	run_steps(app);
}

/* ------------------------------------------------------------------ */
/* ARSDK controller over mux (from Parrot's examples/controller.c)     */
/* ------------------------------------------------------------------ */

static void recv_cmd(struct arsdk_cmd_itf *itf, const struct arsdk_cmd *cmd,
		void *userdata)
{
	struct app *app = userdata;
	char buf[512];
	arsdk_cmd_fmt(cmd, buf, sizeof(buf));
	printf("< %s [%llu ms]\n", buf,
			(unsigned long long)(now_ms() - app->start_ms));
	if (app->nrecv < MAX_RECV)
		app->recv[app->nrecv++] = strdup(buf);
	run_steps(app);
}

static void send_status(struct arsdk_cmd_itf *itf, const struct arsdk_cmd *cmd,
		enum arsdk_cmd_itf_send_status status, int done, void *userdata)
{
}

static void connected(struct arsdk_device *device,
		const struct arsdk_device_info *info, void *userdata)
{
	struct app *app = userdata;
	struct arsdk_cmd_itf_cbs cbs;
	int res;
	printf("connected to %s (0x%04x)\n", info->name, info->type);
	memset(&cbs, 0, sizeof(cbs));
	cbs.userdata = app;
	cbs.recv_cmd = &recv_cmd;
	cbs.send_status = &send_status;
	res = arsdk_device_create_cmd_itf(device, &cbs, &app->cmd_itf);
	if (res < 0)
		finish(app, 1, "FAIL cmd itf: %s\n", strerror(-res));
}

static void disconnected(struct arsdk_device *device,
		const struct arsdk_device_info *info, void *userdata)
{
	struct app *app = userdata;
	app->cmd_itf = NULL;
	app->device = NULL;
	finish(app, 1, "FAIL disconnected at step %d\n", app->step + 1);
}

static void canceled(struct arsdk_device *device,
		const struct arsdk_device_info *info,
		enum arsdk_conn_cancel_reason reason, void *userdata)
{
	struct app *app = userdata;
	app->device = NULL;
	finish(app, 1, "FAIL connection canceled: %s\n",
			arsdk_conn_cancel_reason_str(reason));
}

static void connecting(struct arsdk_device *device,
		const struct arsdk_device_info *info, void *userdata)
{
}

static void link_status(struct arsdk_device *device,
		const struct arsdk_device_info *info,
		enum arsdk_link_status status, void *userdata)
{
	if (status == ARSDK_LINK_STATUS_KO)
		arsdk_device_disconnect(device);
}

static void device_added(struct arsdk_device *device, void *userdata)
{
	struct app *app = userdata;
	struct arsdk_device_conn_cfg cfg;
	struct arsdk_device_conn_cbs cbs;
	int res;
	if (app->device != NULL)
		return;
	app->device = device;
	memset(&cfg, 0, sizeof(cfg));
	cfg.ctrl_name = "disco-probe";
	cfg.ctrl_type = "test";
	memset(&cbs, 0, sizeof(cbs));
	cbs.userdata = app;
	cbs.connecting = &connecting;
	cbs.connected = &connected;
	cbs.disconnected = &disconnected;
	cbs.canceled = &canceled;
	cbs.link_status = &link_status;
	res = arsdk_device_connect(device, &cfg, &cbs, app->loop);
	if (res < 0)
		finish(app, 1, "FAIL connect: %s\n", strerror(-res));
}

static void device_removed(struct arsdk_device *device, void *userdata)
{
}

static void backend_create(struct app *app)
{
	struct arsdkctrl_backend_mux_cfg backend_cfg;
	struct arsdk_discovery_cfg discovery_cfg;
	static const enum arsdk_device_type types[] = {
		ARSDK_DEVICE_TYPE_SKYCTRL_2,
		ARSDK_DEVICE_TYPE_EVINRUDE,
	};
	int res;
	memset(&backend_cfg, 0, sizeof(backend_cfg));
	backend_cfg.mux = app->mux.muxctx;
	res = arsdkctrl_backend_mux_new(app->ctrl, &backend_cfg, &app->backend_mux);
	if (res < 0)
		{ finish(app, 1, "FAIL mux backend: %s\n", strerror(-res)); return; }
	memset(&discovery_cfg, 0, sizeof(discovery_cfg));
	discovery_cfg.types = types;
	discovery_cfg.count = sizeof(types) / sizeof(types[0]);
	res = arsdk_discovery_mux_new(app->ctrl, app->backend_mux,
			&discovery_cfg, app->mux.muxctx, &app->discovery_mux);
	if (res == 0)
		res = arsdk_discovery_mux_start(app->discovery_mux);
	if (res < 0)
		finish(app, 1, "FAIL mux discovery: %s\n", strerror(-res));
}

static void backend_destroy(struct app *app)
{
	if (app->discovery_mux != NULL) {
		arsdk_discovery_mux_stop(app->discovery_mux);
		arsdk_discovery_mux_destroy(app->discovery_mux);
		app->discovery_mux = NULL;
	}
	if (app->device != NULL) {
		arsdk_device_disconnect(app->device);
		app->device = NULL;
	}
	if (app->backend_mux != NULL) {
		arsdkctrl_backend_mux_destroy(app->backend_mux);
		app->backend_mux = NULL;
	}
}

static int mux_client_tx(struct mux_ctx *ctx, struct pomp_buffer *buf,
		void *userdata)
{
	struct app *app = userdata;
	return pomp_ctx_send_raw_buf(app->mux.pompctx, buf);
}

static void mux_client_event_cb(struct pomp_ctx *ctx, enum pomp_event event,
		struct pomp_conn *conn, const struct pomp_msg *msg, void *userdata)
{
	struct app *app = userdata;
	struct mux_ops ops;
	switch (event) {
	case POMP_EVENT_CONNECTED: {
		/* no Nagle: acknowledged commands wait for each small ACK */
		int one = 1;
		setsockopt(pomp_conn_get_fd(conn), IPPROTO_TCP, TCP_NODELAY,
				&one, sizeof(one));
		memset(&ops, 0, sizeof(ops));
		ops.userdata = app;
		ops.tx = &mux_client_tx;
		app->mux.muxctx = mux_new(-1, app->loop, &ops, 0);
		backend_create(app);
		break;
	}
	case POMP_EVENT_DISCONNECTED:
		app->cmd_itf = NULL;
		backend_destroy(app);
		if (app->mux.muxctx != NULL) {
			mux_stop(app->mux.muxctx);
			mux_unref(app->mux.muxctx);
			app->mux.muxctx = NULL;
		}
		finish(app, 1, "FAIL mux link closed at step %d\n", app->step + 1);
		break;
	default:
		break;
	}
}

static void mux_client_raw_cb(struct pomp_ctx *ctx, struct pomp_conn *conn,
		struct pomp_buffer *buf, void *userdata)
{
	struct app *app = userdata;
	mux_decode(app->mux.muxctx, buf);
}

static int read_script(struct app *app, FILE *f)
{
	char line[1024];
	size_t len;
	char *p;
	while (fgets(line, sizeof(line), f) != NULL) {
		len = strlen(line);
		while (len > 0 && (line[len - 1] == '\n' || line[len - 1] == '\r'
				|| line[len - 1] == ' '))
			line[--len] = '\0';
		for (p = line; *p == ' ' || *p == '\t'; p++)
			;
		if (*p == '\0' || *p == '#')
			continue;
		if (app->nsteps == MAX_STEPS)
			return -E2BIG;
		app->steps[app->nsteps++] = strdup(p);
	}
	return 0;
}

static void sig_handler(int signum)
{
	s_app.done = 1;
	s_app.failed = 1;
	if (s_app.loop != NULL)
		pomp_loop_wakeup(s_app.loop);
}

int main(int argc, char *argv[])
{
	struct arsdk_ctrl_device_cbs dev_cbs;
	struct sockaddr_in addr;
	int i;

	s_app.mux.port = 4321;
	s_app.ctrl_port = 4322;
	s_app.default_timeout = 5000;
	for (i = 1; i < argc; i++) {
		if (strcmp(argv[i], "--port") == 0 && i + 1 < argc)
			s_app.mux.port = (uint16_t)atoi(argv[++i]);
		else if (strcmp(argv[i], "--ctrl-port") == 0 && i + 1 < argc)
			s_app.ctrl_port = (uint16_t)atoi(argv[++i]);
		else if (strcmp(argv[i], "--timeout") == 0 && i + 1 < argc)
			s_app.default_timeout = atoi(argv[++i]);
		else {
			fprintf(stderr, "usage: %s [--port <mux port>] [--ctrl-port <port>]"
					" [--timeout <ms>] < script\n", argv[0]);
			return 2;
		}
	}
	setvbuf(stdout, NULL, _IOLBF, 0);
	if (read_script(&s_app, stdin) < 0) {
		fprintf(stderr, "script too long\n");
		return 2;
	}
	signal(SIGINT, &sig_handler);
	signal(SIGTERM, &sig_handler);
	signal(SIGPIPE, SIG_IGN);

	s_app.loop = pomp_loop_new();
	s_app.timer = pomp_timer_new(s_app.loop, &timer_cb, &s_app);
	pomp_timer_set_periodic(s_app.timer, TICK_MS, TICK_MS);
	s_app.start_ms = now_ms();

	memset(&dev_cbs, 0, sizeof(dev_cbs));
	dev_cbs.userdata = &s_app;
	dev_cbs.added = &device_added;
	dev_cbs.removed = &device_removed;
	if (arsdk_ctrl_new(s_app.loop, &s_app.ctrl) < 0)
		return 1;
	arsdk_ctrl_set_device_cbs(s_app.ctrl, &dev_cbs);

	s_app.mux.pompctx = pomp_ctx_new_with_loop(&mux_client_event_cb,
			&s_app, s_app.loop);
	pomp_ctx_set_raw(s_app.mux.pompctx, &mux_client_raw_cb);
	memset(&addr, 0, sizeof(addr));
	addr.sin_family = AF_INET;
	addr.sin_port = htons(s_app.mux.port);
	addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
	if (pomp_ctx_connect(s_app.mux.pompctx, (const struct sockaddr *)&addr,
			sizeof(addr)) < 0) {
		printf("FAIL cannot connect to 127.0.0.1:%u\n", s_app.mux.port);
		return 1;
	}

	while (!s_app.done)
		pomp_loop_wait_and_process(s_app.loop, -1);

	/* close the session cleanly, so the fake takes the next one at once */
	pomp_ctx_stop(s_app.mux.pompctx);
	pomp_ctx_destroy(s_app.mux.pompctx);
	arsdk_ctrl_destroy(s_app.ctrl);
	pomp_timer_clear(s_app.timer);
	pomp_timer_destroy(s_app.timer);
	pomp_loop_destroy(s_app.loop);
	for (i = 0; i < s_app.nsteps; i++)
		free(s_app.steps[i]);
	for (i = 0; i < s_app.nrecv; i++)
		free(s_app.recv[i]);
	return s_app.failed ? 1 : 0;
}
