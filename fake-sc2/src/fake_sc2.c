/**
 * Copyright (c) 2019 Parrot Drones SAS
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *   * Redistributions of source code must retain the above copyright
 *     notice, this list of conditions and the following disclaimer.
 *   * Redistributions in binary form must reproduce the above copyright
 *     notice, this list of conditions and the following disclaimer in the
 *     documentation and/or other materials provided with the distribution.
 *   * Neither the name of the Parrot Drones SAS Company nor the
 *     names of its contributors may be used to endorse or promote products
 *     derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE PARROT DRONES SAS COMPANY BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <errno.h>
#include <signal.h>
#include <unistd.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <stdint.h>

#include <libpomp.h>
#include <libmux.h>

#include "arsdk/arsdk.h"
#define ULOG_TAG device
#include "ulog.h"
ULOG_DECLARE_TAG(device);

#define LOGD(_fmt, ...)	ULOGD(_fmt, ##__VA_ARGS__)
#define LOGI(_fmt, ...)	ULOGI(_fmt, ##__VA_ARGS__)
#define LOGW(_fmt, ...)	ULOGW(_fmt, ##__VA_ARGS__)
#define LOGE(_fmt, ...)	ULOGE(_fmt, ##__VA_ARGS__)

/** Log error with errno */
#define LOG_ERRNO(_fct, _err) \
	LOGE("%s:%d: %s err=%d(%s)", __func__, __LINE__, \
			_fct, _err, strerror(_err))

/** Log error with fd and errno */
#define LOG_FD_ERRNO(_fct, _fd, _err) \
	LOGE("%s:%d: %s(fd=%d) err=%d(%s)", __func__, __LINE__, \
			_fct, _fd, _err, strerror(_err))

/** */
/* fake-sc2: current Disco telemetry, set through the control socket and sent
 * to the app on change and with the drone's AllStates answer. */
struct telemetry {
	uint8_t  battery;        /* drone battery, percent */
	uint8_t  rc_battery;     /* controller battery, percent */
	double   altitude;       /* m, relative to take-off */
	float    roll, pitch, yaw;       /* rad */
	float    speed_x, speed_y, speed_z; /* m/s, NED */
	float    airspeed;       /* m/s */
	double   latitude, longitude, gps_altitude; /* 500 = unknown */
	uint8_t  gps_fixed;
	uint8_t  satellites;
	int32_t  flying_state;   /* ARSDK_ARDRONE3_PILOTINGSTATE_FLYINGSTATECHANGED_STATE_* */
};

struct app {
	int                          stopped;
	enum arsdk_backend_type      backend_type;
	struct pomp_loop             *loop;
	struct arsdk_mngr            *mngr;
	struct arsdk_backend_mux     *backend_mux;
	struct arsdk_publisher_mux   *publisher_mux;
	struct arsdk_backend_net     *backend_net;
	struct arsdk_publisher_avahi *publisher_avahi;
	struct arsdk_publisher_net   *publisher_net;
	int                          use_publisher_avahi;
	int                          use_publisher_net;
	struct arsdk_peer            *peer;
	struct arsdk_cmd_itf         *cmd_itf;

	struct {
		struct mux_ctx    *ctx;
		int               sockfd_server;
		int               sockfd;
		uint16_t          port;
	} mux;

	struct {
		const char        *filepath;
		FILE              *file;
		struct pomp_timer *timer;
	} replay;

	/* fake-sc2: line-based control socket (see ctrl_handle_line) */
	struct {
		int               server_fd;
		int               client_fd;
		uint16_t          port;
		char              buf[1024];
		size_t            len;
	} ctrl;

	struct telemetry   tm;
};

/** */
static struct app s_app = {
	.stopped = 0,
	.backend_type = ARSDK_BACKEND_TYPE_UNKNOWN,
	.loop = NULL,
	.backend_net = NULL,
	.backend_mux = NULL,
	.publisher_mux = NULL,
	.publisher_avahi = NULL,
	.publisher_net = NULL,
	.use_publisher_avahi = 1,
	.use_publisher_net = 0,
	.peer = NULL,
	.cmd_itf = NULL,
	.mux = {
		.ctx = NULL,
		.sockfd_server = -1,
		.sockfd = -1,
		.port = 4321,
	},
	.replay = {
		.filepath = NULL,
		.file = NULL,
		.timer = NULL,
	},
	.ctrl = {
		.server_fd = -1,
		.client_fd = -1,
		.port = 4322,
		.len = 0,
	},
	.tm = {
		.battery = 100,
		.rc_battery = 100,
		.latitude = 500.0,
		.longitude = 500.0,
		.gps_altitude = 500.0,
		.flying_state = ARSDK_ARDRONE3_PILOTINGSTATE_FLYINGSTATECHANGED_STATE_LANDED,
	},
};

/**
 */
static void send_status(struct arsdk_cmd_itf *itf,
		const struct arsdk_cmd *cmd,
		enum arsdk_cmd_itf_send_status status,
		int done,
		void *userdata)
{
	LOGI("cmd %u,%u,%u: %s%s", cmd->prj_id, cmd->cls_id, cmd->cmd_id,
			arsdk_cmd_itf_send_status_str(status),
			done ? "(DONE)" : "");
}

/**
 */
static void link_quality(struct arsdk_cmd_itf *itf,
		int32_t tx_quality,
		int32_t rx_quality,
		int32_t rx_useful,
		void *userdata)
{
	LOGI("link_quality tx_quality:%d%% rx_quality:%d%% rx_useful:%d%%",
			tx_quality,
			rx_quality,
			rx_useful);

}

/* fake-sc2: identity of the drone behind the controller */
#define FAKE_DRONE_NAME     "DISCO-FAKE01"
#define FAKE_DRONE_PRODUCT  0x090e /* ARSDK_DEVICE_TYPE_EVINRUDE: Disco */
#define FAKE_SC2_SOFTWARE   "1.0.9"
#define FAKE_SC2_HARDWARE   "1.0"
#define FAKE_DRONE_SOFTWARE "1.7.1"
#define FAKE_DRONE_HARDWARE "1.0"

#define SEND(_res, _call) do { \
		(_res) = (_call); \
		if ((_res) < 0) \
			LOG_ERRNO(#_call, -(_res)); \
	} while (0)

/** fake-sc2: send the controller's own state (battery). */
static void send_rc_state(struct app *app)
{
	int res = 0;
	if (app->cmd_itf == NULL)
		return;
	SEND(res, arsdk_cmd_send_Skyctrl_SkyControllerState_BatteryChanged(
			app->cmd_itf, &send_status, app, app->tm.rc_battery));
}

/** fake-sc2: send the whole Disco telemetry. */
static void send_telemetry(struct app *app)
{
	int res = 0;
	struct telemetry *tm = &app->tm;
	if (app->cmd_itf == NULL)
		return;
	SEND(res, arsdk_cmd_send_Common_CommonState_BatteryStateChanged(
			app->cmd_itf, &send_status, app, tm->battery));
	SEND(res, arsdk_cmd_send_Ardrone3_PilotingState_FlyingStateChanged(
			app->cmd_itf, &send_status, app, tm->flying_state));
	SEND(res, arsdk_cmd_send_Ardrone3_PilotingState_AltitudeChanged(
			app->cmd_itf, &send_status, app, tm->altitude));
	SEND(res, arsdk_cmd_send_Ardrone3_PilotingState_AttitudeChanged(
			app->cmd_itf, &send_status, app,
			tm->roll, tm->pitch, tm->yaw));
	SEND(res, arsdk_cmd_send_Ardrone3_PilotingState_SpeedChanged(
			app->cmd_itf, &send_status, app,
			tm->speed_x, tm->speed_y, tm->speed_z));
	SEND(res, arsdk_cmd_send_Ardrone3_PilotingState_AirSpeedChanged(
			app->cmd_itf, &send_status, app, tm->airspeed));
	SEND(res, arsdk_cmd_send_Ardrone3_GPSSettingsState_GPSFixStateChanged(
			app->cmd_itf, &send_status, app, tm->gps_fixed));
	SEND(res, arsdk_cmd_send_Ardrone3_GPSState_NumberOfSatelliteChanged(
			app->cmd_itf, &send_status, app, tm->satellites));
	SEND(res, arsdk_cmd_send_Ardrone3_PilotingState_PositionChanged(
			app->cmd_itf, &send_status, app,
			tm->latitude, tm->longitude, tm->gps_altitude));
}

/**
 * fake-sc2: answer the GroundSDK connection sequences.
 * Controller (Skyctrl feature): AllSettings -> AllSettingsChanged, then
 * AllStates -> drone connection state + AllStatesChanged. The SDK then
 * connects to the drone through the same link with the Common feature.
 */
static void recv_cmd(struct arsdk_cmd_itf *itf,
		const struct arsdk_cmd *cmd,
		void *userdata)
{
	int res = 0;
	char buf[512] = "";
	struct app *app = userdata;

	/* Format and log received command */
	arsdk_cmd_fmt(cmd, buf, sizeof(buf));
	LOGI("recv %s", buf);

	switch (cmd->id) {
	/* SkyController */
	case ARSDK_ID_SKYCTRL_SETTINGS_ALLSETTINGS:
		SEND(res, arsdk_cmd_send_Skyctrl_SettingsState_ProductVersionChanged(
				app->cmd_itf, &send_status, app,
				FAKE_SC2_SOFTWARE, FAKE_SC2_HARDWARE));
		SEND(res, arsdk_cmd_send_Skyctrl_SettingsState_AllSettingsChanged(
				app->cmd_itf, &send_status, app));
		break;
	case ARSDK_ID_SKYCTRL_COMMON_ALLSTATES:
		SEND(res, arsdk_cmd_send_Skyctrl_DeviceState_ConnexionChanged(
				app->cmd_itf, &send_status, app,
				ARSDK_SKYCTRL_DEVICESTATE_CONNEXIONCHANGED_STATUS_CONNECTED,
				FAKE_DRONE_NAME, FAKE_DRONE_PRODUCT));
		send_rc_state(app);
		SEND(res, arsdk_cmd_send_Skyctrl_CommonState_AllStatesChanged(
				app->cmd_itf, &send_status, app));
		break;

	/* Drone, proxied by the controller */
	case ARSDK_ID_COMMON_SETTINGS_ALLSETTINGS:
		SEND(res, arsdk_cmd_send_Common_SettingsState_ProductVersionChanged(
				app->cmd_itf, &send_status, app,
				FAKE_DRONE_SOFTWARE, FAKE_DRONE_HARDWARE));
		SEND(res, arsdk_cmd_send_Common_SettingsState_AllSettingsChanged(
				app->cmd_itf, &send_status, app));
		break;
	case ARSDK_ID_COMMON_COMMON_ALLSTATES:
		send_telemetry(app);
		SEND(res, arsdk_cmd_send_Common_CommonState_AllStatesChanged(
				app->cmd_itf, &send_status, app));
		break;
	default:
		break;
	}
}

/**
 */
static void connected(struct arsdk_peer *peer,
		const struct arsdk_peer_info *info,
		void *userdata)
{
	int res = 0;
	struct app *app = userdata;
	struct arsdk_cmd_itf_cbs cmd_cbs;
	LOGI("%s", __func__);

	/* Create command interface object */
	memset(&cmd_cbs, 0, sizeof(cmd_cbs));
	cmd_cbs.userdata = app;
	cmd_cbs.recv_cmd = &recv_cmd;
	cmd_cbs.send_status = &send_status;
	cmd_cbs.link_quality = &link_quality;
	res = arsdk_peer_create_cmd_itf(peer, &cmd_cbs, &app->cmd_itf);
	if (res < 0)
		LOG_ERRNO("arsdk_peer_create_cmd_itf", -res);
}

/**
 */
static void disconnected(struct arsdk_peer *peer,
		const struct arsdk_peer_info *info,
		void *userdata)
{
	struct app *app = userdata;
	LOGI("%s", __func__);

	app->cmd_itf = NULL;
	app->peer = NULL;
}

/**
 */
static void canceled(struct arsdk_peer *peer,
		const struct arsdk_peer_info *info,
		enum arsdk_conn_cancel_reason reason,
		void *userdata)
{
	struct app *app = userdata;
	LOGI("%s: reason=%s", __func__, arsdk_conn_cancel_reason_str(reason));
	app->peer = NULL;
}

/**
 */
static void link_status(struct arsdk_peer *peer,
		const struct arsdk_peer_info *info,
		enum arsdk_link_status status,
		void *userdata)
{
	LOGI("%s: status=%s", __func__, arsdk_link_status_str(status));
	if (status == ARSDK_LINK_STATUS_KO)
		arsdk_peer_disconnect(peer);
}

/**
 */
static void conn_req(struct arsdk_peer *peer,
		const struct arsdk_peer_info *info,
		void *userdata)
{
	int res = 0;
	struct app *app = userdata;
	struct arsdk_peer_conn_cfg cfg;
	struct arsdk_peer_conn_cbs cbs;
	static const char json[] = "{"
		"\"arstream_fragment_size\": 65000, "
		"\"arstream_fragment_maximum_number\": 4, "
		"\"c2d_update_port\": 51 ,"
		"\"c2d_user_port\": 21"
		"}";
	LOGI("%s", __func__);

	/* Only one peer at a time */
	if (app->peer != NULL) {
		res = arsdk_peer_reject(peer);
		if (res < 0)
			LOG_ERRNO("arsdk_peer_reject", -res);
		return;
	}

	/* Save peer */
	app->peer = peer;

	memset(&cfg, 0, sizeof(cfg));
	cfg.json = json;

	memset(&cbs, 0, sizeof(cbs));
	cbs.userdata = app;
	cbs.connected = &connected;
	cbs.disconnected = &disconnected;
	cbs.canceled = &canceled;
	cbs.link_status = &link_status;

	/* Accept connection */
	res = arsdk_peer_accept(peer, &cfg, &cbs, app->loop);
	if (res < 0)
		LOG_ERRNO("arsdk_peer_accept", -res);
}

/**
 */
static void socket_cb(struct arsdk_backend_net *self, int fd,
		enum arsdk_socket_kind kind, void *userdata)
{
	LOGI("socket_cb : self:%p fd:%d kind:%s userdata:%p",
			self, fd, arsdk_socket_kind_str(kind), userdata);
}

/**
 */
static void backend_create(struct app *app)
{
	int res = 0;
	struct arsdk_backend_net_cfg backend_net_cfg;
	struct arsdk_backend_mux_cfg backend_mux_cfg;
	struct arsdk_publisher_avahi_cfg publisher_avahi_cfg;
	struct arsdk_publisher_net_cfg publisher_net_cfg;
	/* fake-sc2: announce a SkyController 2 (product 0x090f) */
	struct arsdk_publisher_cfg publisher_cfg = {
		.name = "SkyController 2 (fake)",
		.type = ARSDK_DEVICE_TYPE_SKYCTRL_2,
		.id = "PI040443FAKESC2",
	};
	struct arsdk_backend_listen_cbs listen_cbs = {
		.userdata = app,
		.conn_req = &conn_req,
	};
	uint16_t net_listen_port = 44444;

	switch (app->backend_type) {
	case ARSDK_BACKEND_TYPE_NET:
		memset(&backend_net_cfg, 0, sizeof(backend_net_cfg));
		res = arsdk_backend_net_new(app->mngr, &backend_net_cfg,
				&app->backend_net);
		if (res < 0)
			LOG_ERRNO("arsdk_backend_net_new", -res);

		res = arsdk_backend_net_set_socket_cb(app->backend_net,
				&socket_cb, &s_app);
		if (res < 0)
			LOG_ERRNO("arsdk_backend_net_set_socket_cb", -res);

		res = arsdk_backend_net_start_listen(app->backend_net,
				&listen_cbs, net_listen_port);
		if (res < 0)
			LOG_ERRNO("arsdk_backend_net_start_listen", -res);

		if (app->use_publisher_avahi) {
			/* start avahi publisher */
			memset(&publisher_avahi_cfg, 0,
					sizeof(publisher_avahi_cfg));
			publisher_avahi_cfg.base = publisher_cfg;
			publisher_avahi_cfg.port = net_listen_port;

			res = arsdk_publisher_avahi_new(app->backend_net,
					app->loop, &app->publisher_avahi);
			if (res < 0)
				LOG_ERRNO("arsdk_publisher_avahi_new", -res);

			res = arsdk_publisher_avahi_start(app->publisher_avahi,
					&publisher_avahi_cfg);
			if (res < 0)
				LOG_ERRNO("arsdk_publisher_avahi_start", -res);

		}

		if (app->use_publisher_net) {
			/* start net publisher */
			memset(&publisher_net_cfg, 0,
					sizeof(publisher_net_cfg));
			publisher_net_cfg.base = publisher_cfg;
			publisher_net_cfg.port = net_listen_port;

			res = arsdk_publisher_net_new(app->backend_net,
					app->loop, NULL, &app->publisher_net);
			if (res < 0)
				LOG_ERRNO("arsdk_publisher_net_new", -res);

			res = arsdk_publisher_net_start(app->publisher_net,
					&publisher_net_cfg);
			if (res < 0)
				LOG_ERRNO("arsdk_publisher_net_start", -res);
		}
		break;
	case ARSDK_BACKEND_TYPE_MUX:
		memset(&backend_mux_cfg, 0, sizeof(backend_mux_cfg));
		backend_mux_cfg.mux = app->mux.ctx;
		res = arsdk_backend_mux_new(app->mngr, &backend_mux_cfg,
				&app->backend_mux);
		if (res < 0)
			LOG_ERRNO("arsdk_backend_mux_new", -res);
		res = arsdk_backend_mux_start_listen(app->backend_mux,
				&listen_cbs);
		if (res < 0)
			LOG_ERRNO("arsdk_backend_mux_start_listen", -res);

		/* start mux publisher */
		res = arsdk_publisher_mux_new(app->backend_mux, app->mux.ctx,
				&app->publisher_mux);
		if (res < 0)
			LOG_ERRNO("arsdk_publisher_mux_new", -res);

		res = arsdk_publisher_mux_start(app->publisher_mux,
				&publisher_cfg);
		if (res < 0)
			LOG_ERRNO("arsdk_publisher_mux_start", -res);
		break;

	case ARSDK_BACKEND_TYPE_BLE: /* NO BREAK */
	case ARSDK_BACKEND_TYPE_UNKNOWN: /* NO BREAK */
	default:
		LOGW("Unsupported backend: %s",
				arsdk_backend_type_str(app->backend_type));
		return;
	}

	/* wait for connection request */
}

/**
 */
static void backend_destroy(struct app *app)
{
	int res = 0;

	if (app->publisher_avahi) {
		res = arsdk_publisher_avahi_stop(app->publisher_avahi);
		if (res < 0)
			LOG_ERRNO("arsdk_publisher_avahi_stop", -res);

		arsdk_publisher_avahi_destroy(app->publisher_avahi);
		app->publisher_avahi = NULL;
	}

	if (app->publisher_net) {
		res = arsdk_publisher_net_stop(app->publisher_net);
		if (res < 0)
			LOG_ERRNO("arsdk_publisher_net_stop", -res);

		arsdk_publisher_net_destroy(app->publisher_net);
		app->publisher_net = NULL;
	}

	if (app->publisher_mux) {
		res = arsdk_publisher_mux_stop(app->publisher_mux);
		if (res < 0)
			LOG_ERRNO("arsdk_publisher_mux_stop", -res);

		arsdk_publisher_mux_destroy(app->publisher_mux);
		app->publisher_mux = NULL;
	}

	if (app->peer != NULL) {
		res = arsdk_peer_disconnect(app->peer);
		if (res < 0)
			LOG_ERRNO("arsdk_peer_disconnect", -res);
		if (app->peer != NULL)
			LOGE("s_app.peer should be NULL");
		app->peer = NULL;
	}

	if (app->backend_mux != NULL) {
		res = arsdk_backend_mux_destroy(app->backend_mux);
		if (res < 0)
			LOG_ERRNO("arsdk_backend_mux_destroy", -res);

		app->backend_mux = NULL;
	}

	if (app->backend_net != NULL) {
		res = arsdk_backend_net_stop_listen(app->backend_net);
		if (res < 0)
			LOG_ERRNO("arsdk_backend_net_stop_listen", -res);

		res = arsdk_backend_net_destroy(app->backend_net);
		if (res < 0)
			LOG_ERRNO("arsdk_backend_net_destroy", -res);

		app->backend_net = NULL;
	}
}

/**
 */
static void mux_server_fdeof(struct mux_ctx *ctx, void *userdata)
{
	struct app *app = userdata;
	mux_stop(app->mux.ctx);
	backend_destroy(app);
	mux_unref(app->mux.ctx);
	app->mux.ctx = NULL;
	close(app->mux.sockfd);
	app->mux.sockfd = -1;
}

/**
 */
static void mux_server_fd_event_cb(int fd, uint32_t revents, void *userdata)
{
	struct app *app = userdata;
	int sockfd = -1;
	struct mux_ops ops;

	sockfd = accept(app->mux.sockfd_server, NULL, NULL);
	if (sockfd < 0)
		LOG_FD_ERRNO("accept", app->mux.sockfd_server, errno);

	if (app->mux.sockfd != -1) {
		close(sockfd);
		return;
	}

	/* For testing purposes, set the not pollable flag for fd */
	memset(&ops, 0, sizeof(ops));
	ops.userdata = app;
	ops.fdeof = mux_server_fdeof;
	app->mux.sockfd = sockfd;
	app->mux.ctx = mux_new(app->mux.sockfd, app->loop, &ops,
			MUX_FLAG_FD_NOT_POLLABLE);

	backend_create(app);
}

/**
 */
static void mux_server_create(struct app *app)
{
	int res = 0;
	struct sockaddr_in addr;
	socklen_t addrlen = 0;
	int sockopt = 0;

	app->mux.sockfd_server = socket(AF_INET, SOCK_STREAM |
			SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
	if (app->mux.sockfd_server < 0)
		LOG_ERRNO("socket", errno);

	/* Allow reuse of address */
	sockopt = 1;
	if (setsockopt(app->mux.sockfd_server, SOL_SOCKET, SO_REUSEADDR,
			&sockopt, sizeof(sockopt)) < 0) {
		LOG_FD_ERRNO("setsockopt.SO_REUSEADDR", app->mux.sockfd_server,
				errno);
	}

	/* Setup address */
	memset(&addr, 0, sizeof(addr));
	addr.sin_family = AF_INET;
	addr.sin_addr.s_addr = htonl(INADDR_ANY);
	addr.sin_port = htons(app->mux.port);
	addrlen = sizeof(addr);

	if (bind(app->mux.sockfd_server,
			(const struct sockaddr *)&addr, addrlen) < 0) {
		LOG_FD_ERRNO("bind", app->mux.sockfd_server, errno);
	}

	if (listen(app->mux.sockfd_server, SOMAXCONN) < 0)
		LOG_FD_ERRNO("listen", app->mux.sockfd_server, errno);

	res = pomp_loop_add(app->loop, app->mux.sockfd_server,
			POMP_FD_EVENT_IN, &mux_server_fd_event_cb, app);
	if (res < 0)
		LOG_ERRNO("pomp_loop_add", -res);
}

/**
 */
static void mux_server_destroy(struct app *app)
{
	if (app->mux.ctx != NULL) {
		shutdown(app->mux.sockfd, SHUT_RDWR);
		mux_server_fdeof(app->mux.ctx, app);
	}

	if (app->mux.sockfd_server >= 0) {
		pomp_loop_remove(app->loop, app->mux.sockfd_server);
		close(app->mux.sockfd_server);
		app->mux.sockfd_server = -1;
	}
}

/**
 */
static void sig_handler(int signum)
{
	LOGI("signal %d(%s) received", signum, strsignal(signum));
	s_app.stopped = 1;
	if (s_app.loop != NULL)
		pomp_loop_wakeup(s_app.loop);
}

/*
 * fake-sc2 control socket: one TCP client at a time on 127.0.0.1:<ctrl port>,
 * one command per line, answered "ok" or "error <reason>":
 *   battery <percent>            drone battery
 *   rc_battery <percent>         controller battery
 *   altitude <m>
 *   attitude <roll> <pitch> <yaw>   radians
 *   speed <vx> <vy> <vz>         m/s (NED)
 *   airspeed <m/s>
 *   gps <lat> <lon> <alt>        degrees, m; also sets the fix
 *   gps_lost                     fix lost, position unknown (500)
 *   satellites <n>
 *   flying_state <landed|takingoff|hovering|flying|landing|emergency>
 *   send_all                     resend all telemetry
 *   ping
 */
static int ctrl_handle_line(struct app *app, char *line,
		char *reply, size_t reply_size)
{
	struct telemetry *tm = &app->tm;
	char word[32] = "";
	double a = 0, b = 0, c = 0;
	int n = 0;
	int res = 0;

	n = sscanf(line, "%31s %lf %lf %lf", word, &a, &b, &c);
	if (n < 1)
		return snprintf(reply, reply_size, "error empty\n");

	if (strcmp(word, "ping") == 0) {
		return snprintf(reply, reply_size, "ok %s\n",
				app->cmd_itf != NULL ? "connected" : "idle");
	} else if (strcmp(word, "battery") == 0 && n == 2) {
		tm->battery = (uint8_t)a;
		if (app->cmd_itf)
			SEND(res, arsdk_cmd_send_Common_CommonState_BatteryStateChanged(
				app->cmd_itf, &send_status, app, tm->battery));
	} else if (strcmp(word, "rc_battery") == 0 && n == 2) {
		tm->rc_battery = (uint8_t)a;
		send_rc_state(app);
	} else if (strcmp(word, "altitude") == 0 && n == 2) {
		tm->altitude = a;
		if (app->cmd_itf)
			SEND(res, arsdk_cmd_send_Ardrone3_PilotingState_AltitudeChanged(
				app->cmd_itf, &send_status, app, tm->altitude));
	} else if (strcmp(word, "attitude") == 0 && n == 4) {
		tm->roll = (float)a; tm->pitch = (float)b; tm->yaw = (float)c;
		if (app->cmd_itf)
			SEND(res, arsdk_cmd_send_Ardrone3_PilotingState_AttitudeChanged(
				app->cmd_itf, &send_status, app,
				tm->roll, tm->pitch, tm->yaw));
	} else if (strcmp(word, "speed") == 0 && n == 4) {
		tm->speed_x = (float)a; tm->speed_y = (float)b;
		tm->speed_z = (float)c;
		if (app->cmd_itf)
			SEND(res, arsdk_cmd_send_Ardrone3_PilotingState_SpeedChanged(
				app->cmd_itf, &send_status, app,
				tm->speed_x, tm->speed_y, tm->speed_z));
	} else if (strcmp(word, "airspeed") == 0 && n == 2) {
		tm->airspeed = (float)a;
		if (app->cmd_itf)
			SEND(res, arsdk_cmd_send_Ardrone3_PilotingState_AirSpeedChanged(
				app->cmd_itf, &send_status, app, tm->airspeed));
	} else if (strcmp(word, "gps") == 0 && n == 4) {
		tm->latitude = a; tm->longitude = b; tm->gps_altitude = c;
		tm->gps_fixed = 1;
		if (tm->satellites == 0)
			tm->satellites = 10;
		send_telemetry(app);
	} else if (strcmp(word, "gps_lost") == 0 && n == 1) {
		tm->latitude = tm->longitude = tm->gps_altitude = 500.0;
		tm->gps_fixed = 0;
		tm->satellites = 0;
		send_telemetry(app);
	} else if (strcmp(word, "satellites") == 0 && n == 2) {
		tm->satellites = (uint8_t)a;
		if (app->cmd_itf)
			SEND(res, arsdk_cmd_send_Ardrone3_GPSState_NumberOfSatelliteChanged(
				app->cmd_itf, &send_status, app, tm->satellites));
	} else if (strcmp(word, "flying_state") == 0) {
		char state[32] = "";
		static const struct { const char *name; int32_t value; } states[] = {
			{ "landed", ARSDK_ARDRONE3_PILOTINGSTATE_FLYINGSTATECHANGED_STATE_LANDED },
			{ "takingoff", ARSDK_ARDRONE3_PILOTINGSTATE_FLYINGSTATECHANGED_STATE_TAKINGOFF },
			{ "hovering", ARSDK_ARDRONE3_PILOTINGSTATE_FLYINGSTATECHANGED_STATE_HOVERING },
			{ "flying", ARSDK_ARDRONE3_PILOTINGSTATE_FLYINGSTATECHANGED_STATE_FLYING },
			{ "landing", ARSDK_ARDRONE3_PILOTINGSTATE_FLYINGSTATECHANGED_STATE_LANDING },
			{ "emergency", ARSDK_ARDRONE3_PILOTINGSTATE_FLYINGSTATECHANGED_STATE_EMERGENCY },
		};
		size_t i;
		if (sscanf(line, "%*s %31s", state) != 1)
			return snprintf(reply, reply_size, "error missing state\n");
		for (i = 0; i < sizeof(states) / sizeof(states[0]); i++) {
			if (strcmp(state, states[i].name) == 0)
				break;
		}
		if (i == sizeof(states) / sizeof(states[0]))
			return snprintf(reply, reply_size, "error unknown state\n");
		tm->flying_state = states[i].value;
		if (app->cmd_itf)
			SEND(res, arsdk_cmd_send_Ardrone3_PilotingState_FlyingStateChanged(
				app->cmd_itf, &send_status, app, tm->flying_state));
	} else if (strcmp(word, "send_all") == 0) {
		send_rc_state(app);
		send_telemetry(app);
	} else {
		return snprintf(reply, reply_size, "error unknown command or arguments\n");
	}
	if (res < 0)
		return snprintf(reply, reply_size, "error send %d\n", res);
	return snprintf(reply, reply_size, "ok\n");
}

static void ctrl_client_close(struct app *app)
{
	if (app->ctrl.client_fd < 0)
		return;
	pomp_loop_remove(app->loop, app->ctrl.client_fd);
	close(app->ctrl.client_fd);
	app->ctrl.client_fd = -1;
	app->ctrl.len = 0;
}

static void ctrl_client_cb(int fd, uint32_t revents, void *userdata)
{
	struct app *app = userdata;
	char reply[128];
	char *nl = NULL;
	ssize_t r = read(fd, app->ctrl.buf + app->ctrl.len,
			sizeof(app->ctrl.buf) - 1 - app->ctrl.len);
	if (r <= 0) {
		ctrl_client_close(app);
		return;
	}
	app->ctrl.len += (size_t)r;
	app->ctrl.buf[app->ctrl.len] = '\0';
	while ((nl = strchr(app->ctrl.buf, '\n')) != NULL) {
		int len;
		*nl = '\0';
		if (nl > app->ctrl.buf && nl[-1] == '\r')
			nl[-1] = '\0';
		LOGI("ctrl: %s", app->ctrl.buf);
		len = ctrl_handle_line(app, app->ctrl.buf, reply, sizeof(reply));
		if (len > 0 && write(fd, reply, (size_t)len) < 0)
			LOG_FD_ERRNO("write", fd, errno);
		app->ctrl.len -= (size_t)(nl + 1 - app->ctrl.buf);
		memmove(app->ctrl.buf, nl + 1, app->ctrl.len + 1);
	}
	if (app->ctrl.len >= sizeof(app->ctrl.buf) - 1) {
		LOGW("ctrl: line too long, dropping");
		app->ctrl.len = 0;
	}
}

static void ctrl_server_cb(int fd, uint32_t revents, void *userdata)
{
	struct app *app = userdata;
	int res;
	int client = accept(fd, NULL, NULL);
	if (client < 0) {
		LOG_FD_ERRNO("accept", fd, errno);
		return;
	}
	/* one client at a time: a new one replaces the previous */
	ctrl_client_close(app);
	app->ctrl.client_fd = client;
	res = pomp_loop_add(app->loop, client, POMP_FD_EVENT_IN,
			&ctrl_client_cb, app);
	if (res < 0)
		LOG_ERRNO("pomp_loop_add", -res);
}

static void ctrl_server_create(struct app *app)
{
	int res = 0;
	int opt = 1;
	struct sockaddr_in addr;

	app->ctrl.server_fd = socket(AF_INET,
			SOCK_STREAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
	if (app->ctrl.server_fd < 0) {
		LOG_ERRNO("socket", errno);
		return;
	}
	setsockopt(app->ctrl.server_fd, SOL_SOCKET, SO_REUSEADDR,
			&opt, sizeof(opt));
	memset(&addr, 0, sizeof(addr));
	addr.sin_family = AF_INET;
	addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
	addr.sin_port = htons(app->ctrl.port);
	if (bind(app->ctrl.server_fd, (const struct sockaddr *)&addr,
			sizeof(addr)) < 0
			|| listen(app->ctrl.server_fd, 4) < 0) {
		LOG_FD_ERRNO("bind/listen", app->ctrl.server_fd, errno);
		close(app->ctrl.server_fd);
		app->ctrl.server_fd = -1;
		return;
	}
	res = pomp_loop_add(app->loop, app->ctrl.server_fd, POMP_FD_EVENT_IN,
			&ctrl_server_cb, app);
	if (res < 0)
		LOG_ERRNO("pomp_loop_add", -res);
	LOGI("control socket on 127.0.0.1:%u", app->ctrl.port);
}

static void ctrl_server_destroy(struct app *app)
{
	ctrl_client_close(app);
	if (app->ctrl.server_fd >= 0) {
		pomp_loop_remove(app->loop, app->ctrl.server_fd);
		close(app->ctrl.server_fd);
		app->ctrl.server_fd = -1;
	}
}

/**
 */
static void usage(const char *progname)
{
	fprintf(stderr, "usage: %s [<options>]\n", progname);
	fprintf(stderr, "  -h --help   : print this help message and exit\n"
		"  --publisher-avahi\n"
		"  --no-publisher-avahi\n"
		"  --publisher-net\n"
		"  --no-publisher-net\n"
		"  --mux\n"
		"  --mux-port <port>  : TCP port of the mux listener (default 4321)\n"
		"  --ctrl-port <port> : TCP port of the control socket on 127.0.0.1 (default 4322)\n"
		"  --codec <codec>\n"
		"  --replay-file <file>\n");
}

/**
 */
int main(int argc, char *argv[])
{
	int res = 0;
	int argidx = 0;

	/* Setup signal handlers */
	signal(SIGINT, &sig_handler);
	signal(SIGTERM, &sig_handler);
	signal(SIGPIPE, SIG_IGN);

	s_app.backend_type = ARSDK_BACKEND_TYPE_NET;
	for (argidx = 1; argidx < argc; argidx++) {
		if (argv[argidx][0] != '-') {
			/* End of options */
			break;
		} else if (strcmp(argv[argidx], "-h") == 0
				|| strcmp(argv[argidx], "--help") == 0) {
			/* Help */
			usage(argv[0]);
			return 0;
		} else if (strcmp(argv[argidx], "--replay-file") == 0) {
			if (argidx + 1 >= argc) {
				fprintf(stderr, "Missing replay file\n");
				usage(argv[0]);
				return -1;
			}
			s_app.replay.filepath = argv[++argidx];
		} else if (strcmp(argv[argidx], "--publisher-avahi") == 0) {
			s_app.backend_type = ARSDK_BACKEND_TYPE_NET;
			s_app.use_publisher_avahi = 1;
		} else if (strcmp(argv[argidx], "--no-publisher-avahi") == 0) {
			s_app.use_publisher_avahi = 0;
		} else if (strcmp(argv[argidx], "--mux") == 0) {
			s_app.backend_type = ARSDK_BACKEND_TYPE_MUX;
		} else if (strcmp(argv[argidx], "--mux-port") == 0) {
			if (argidx + 1 >= argc) {
				fprintf(stderr, "Missing mux port\n");
				usage(argv[0]);
				return -1;
			}
			s_app.mux.port = (uint16_t)atoi(argv[++argidx]);
		} else if (strcmp(argv[argidx], "--ctrl-port") == 0) {
			if (argidx + 1 >= argc) {
				fprintf(stderr, "Missing control port\n");
				usage(argv[0]);
				return -1;
			}
			s_app.ctrl.port = (uint16_t)atoi(argv[++argidx]);
		} else if (strcmp(argv[argidx], "--publisher-net") == 0) {
			s_app.backend_type = ARSDK_BACKEND_TYPE_NET;
			s_app.use_publisher_net = 1;
		} else if (strcmp(argv[argidx], "--no-publisher-net") == 0) {
			s_app.use_publisher_net = 0;
		}
	}

	/* Create loop */
	s_app.loop = pomp_loop_new();

	/* Create manager */
	res = arsdk_mngr_new(s_app.loop, &s_app.mngr);
	if (res < 0) {
		LOG_ERRNO("arsdk_mngr_new", -res);
		goto out;
	}

	/* fake-sc2: control socket */
	ctrl_server_create(&s_app);

	/* Create backend */
	if (s_app.backend_type == ARSDK_BACKEND_TYPE_MUX)
		mux_server_create(&s_app);
	else
		backend_create(&s_app);

	/* Run loop */
	while (!s_app.stopped)
		pomp_loop_wait_and_process(s_app.loop, -1);

	/* Cleanup */
out:
	if (s_app.backend_type == ARSDK_BACKEND_TYPE_MUX)
		mux_server_destroy(&s_app);

	backend_destroy(&s_app);
	ctrl_server_destroy(&s_app);

	if (s_app.loop != NULL) {
		res = pomp_loop_destroy(s_app.loop);
		if (res < 0)
			LOG_ERRNO("pomp_loop_destroy", -res);
		s_app.loop = NULL;
	}

	return 0;
}
