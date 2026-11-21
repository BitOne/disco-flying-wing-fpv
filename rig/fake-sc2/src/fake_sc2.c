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
#include <netinet/tcp.h>
#include <arpa/inet.h>
#include <stdint.h>
#include <time.h>
#include <math.h>

#include <libpomp.h>
#include <libmux.h>
#include <libmux-arsdk.h>

#include "arsdk/arsdk.h"
#include "disco.h"
#include "sc2.h"
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
	double   home_latitude, home_longitude, home_altitude; /* 500 = unset */
	uint8_t  satellites;
	int32_t  flying_state;   /* ARSDK_ARDRONE3_PILOTINGSTATE_FLYINGSTATECHANGED_STATE_* */
};

/* control socket clients at once (tests, simulator bridge, a shell) */
#define CTRL_MAX_CLIENTS 4

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
		/* address the listener binds (network order): loopback unless
		 * --mux-bind, since anyone reaching it takes over the session */
		in_addr_t         bind_addr;
		uint64_t          unplugged_until_ms; /* refuse phones until */
	} mux;

	/* fake-sc2: Disco out of radio range of the controller */
	int drone_link_lost;

	struct {
		const char        *filepath;
		FILE              *file;
		struct pomp_timer *timer;
	} replay;

	/* fake-sc2: line-based control socket (see ctrl_handle_line) */
	struct {
		int               server_fd;
		uint16_t          port;
		struct ctrl_client {
			int       fd;
			char      buf[1024];
			size_t    len;
		} clients[CTRL_MAX_CLIENTS];
	} ctrl;

	struct telemetry   tm;

	/* fake-sc2: video, RTP/RTCP from the fake camera forwarded into mux
	 * channels STREAM_DATA (4) and STREAM_CONTROL (5), like the real
	 * SkyController 2 does for the Disco's arstream2 stream. */
	struct {
		int               rtp_fd;
		int               rtcp_fd;
		uint16_t          port;      /* RTP; RTCP is port + 1 */
		int               enabled;   /* app sent VideoEnable=1 */
		int               channels;  /* mux channels opened */
		unsigned long     rtp_packets;
		unsigned long     rtcp_packets;
		unsigned long     dropped;
		/* Disco mode: RTP/RTCP sent over UDP to the controller */
		int               out_fd;
		struct sockaddr_in client_stream;
		struct sockaddr_in client_ctrl;
		int               client_valid;
	} video;

	/* --disco: be a Disco reached directly over Wi-Fi (net backend),
	 * instead of a SkyController 2 over mux */
	int disco_mode;

	/* fake-sc2: flight simulation driven by the app's piloting commands
	 * (see flight_tick). Values set from the control socket stop it, so
	 * scripted telemetry stays as set. */
	struct {
		struct pomp_timer *timer;
		int               active;    /* simulating */
		unsigned          state_ms;  /* time in the current flying state */
		int               home;      /* return home in progress */
		int               over_home; /* return home: home overflown */
		int               fp;        /* flight plan playing */
		int               fp_circling; /* flight plan: circling home */
		unsigned long     pcmd;      /* PCMD commands received */
		/* the app's last PCMD, percent */
		uint8_t           pcmd_flag;
		int8_t            pcmd_roll, pcmd_pitch, pcmd_gaz;
		uint64_t          pcmd_ms;
		/* the controller's sticks ("sticks"), percent */
		int               stick_roll, stick_pitch, stick_gaz;
		/* position, metres north/east of the origin (lat/lon) */
		double            origin_lat, origin_lon; /* 500 = unset */
		double            n, e;
		double            heading;   /* rad */
		/* circling: centre (m), angle of the plane on it, direction */
		double            cn, ce, circle;
		int               ccw;
		unsigned          link_lost_ms;
		int               low_battery_home; /* done for this flight */
		double            battery_used; /* percent, fraction not sent yet */
		struct pomp_timer *reboot_timer;
		/* the Disco's Wi-Fi restarting for a country or outdoor change */
		struct pomp_timer *wifi_timer;
		int               wifi_down;
	} flight;

	/* fake-sc2: the Disco's settings and other states (disco.c) */
	struct disco disco;

	/* fake-sc2: failures of the link and sensors handled here ("fail") */
	int video_failed;              /* camera stream lost */
	int gps_failed;
	double gps_saved[3];           /* position before the GPS failure */

	/* fake-sc2: commands nothing handles, for finding gaps ("unhandled") */
	char unhandled[4096];

	/* fake-sc2: "sim external": flight dynamics come from an external
	 * simulator (rig/sim/bridge); piloting commands are queued as events */
	int sim_external;
	char events[256];

	/* fake-sc2: the controller's mapper, inputs and settings (sc2.c) */
	struct sc2 sc2;
	struct pomp_timer *button_timer; /* release of the pressed button */
	uint32_t pressed_button;
	int button_pending;
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
		.port = 4322,
		.clients = { { .fd = -1 }, { .fd = -1 }, { .fd = -1 }, { .fd = -1 } },
	},
	.video = {
		.rtp_fd = -1,
		.rtcp_fd = -1,
		.port = 55004,
		.out_fd = -1,
	},
	.tm = {
		.battery = 100,
		.rc_battery = 100,
		.latitude = 500.0,
		.longitude = 500.0,
		.gps_altitude = 500.0,
		.home_latitude = 500.0,
		.home_longitude = 500.0,
		.home_altitude = 500.0,
		.flying_state = ARSDK_ARDRONE3_PILOTINGSTATE_FLYINGSTATECHANGED_STATE_LANDED,
	},
	.flight = {
		.origin_lat = 500.0,
		.origin_lon = 500.0,
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
#define REBOOT_MS           15000
/* a Wi-Fi country or outdoor change: the firmware takes the radio down and
 * up again (bcm_sel_country); the answers go out first */
#define WIFI_RESTART_DELAY_MS 500
#define WIFI_RESTART_MS       4000

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

static void send_home_state(struct app *app, int32_t reason);

/** fake-sc2: controller-to-drone link state, as the SkyController reports it. */
static void send_drone_link(struct app *app)
{
	int res = 0;
	if (app->cmd_itf == NULL)
		return;
	SEND(res, arsdk_cmd_send_Skyctrl_DeviceState_ConnexionChanged(
			app->cmd_itf, &send_status, app,
			app->drone_link_lost ?
			ARSDK_SKYCTRL_DEVICESTATE_CONNEXIONCHANGED_STATUS_NOTCONNECTED :
			ARSDK_SKYCTRL_DEVICESTATE_CONNEXIONCHANGED_STATUS_CONNECTED,
			app->disco.s.name, FAKE_DRONE_PRODUCT));
}

/* Release of a "button" press, sent a moment after the press like a real
 * one: back to back, the release could replace the press in the
 * controller's non-acknowledged queue. */
static void button_release_cb(struct pomp_timer *timer, void *userdata)
{
	int res = 0;
	struct app *app = userdata;
	(void)res;
	app->button_pending = 0;
	sc2_button(&app->sc2, app->pressed_button, 0);
}

static uint64_t now_ms(void)
{
	struct timespec ts;
	clock_gettime(CLOCK_MONOTONIC, &ts);
	return (uint64_t)ts.tv_sec * 1000 + (uint64_t)ts.tv_nsec / 1000000;
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
	disco_battery(&app->disco, tm->battery);
	disco_gps_fix(&app->disco, tm->gps_fixed);
	/* calibrated magnetometer: no "please calibrate" in FreeFlight */
	SEND(res, arsdk_cmd_send_Common_CalibrationState_MagnetoCalibrationStateChanged(
			app->cmd_itf, &send_status, app, 1, 1, 1, 0));
	SEND(res, arsdk_cmd_send_Common_CalibrationState_MagnetoCalibrationRequiredState(
			app->cmd_itf, &send_status, app, 0));
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
	SEND(res, arsdk_cmd_send_Ardrone3_GPSSettingsState_HomeChanged(
			app->cmd_itf, &send_status, app, tm->home_latitude,
			tm->home_longitude, tm->home_altitude));
	send_home_state(app,
		ARSDK_ARDRONE3_PILOTINGSTATE_NAVIGATEHOMESTATECHANGED_REASON_ENABLED);
}

/*
 * fake-sc2: flight simulation, the Disco's own sequences, with the values of
 * its firmware (parameters.conf: take-off to 50 m, 12 m/s cruise, 30 m
 * circles, circular landing):
 *   TakeOff: motor ramping, thrown (automatically after FLIGHT_THROW_MS, or
 *   "throw" on the control socket), taking off while climbing straight to
 *   the circling altitude, then hovering, which for the Disco means circling.
 *   UserTakeOff(1): armed for a hand launch, waits for "throw".
 *   Piloting (the controller's sticks, "sticks" on the control socket, or
 *   the app's PCMD): flying; roll banks and turns, pitch climbs or dives
 *   (reversed in inverted pitch mode), throttle sets the airspeed. Sticks
 *   centred: wings level, straight ahead. Circle(): back to circling, around
 *   a point beside the plane, in the given or the configured direction.
 *   NavigateHome(1): flies to the home position at the circling altitude,
 *   over it, then circles around it; also after return_home_delay seconds
 *   without the controller, and when the battery becomes critical.
 *   Landing: a descending circle (spiral landing) at 10 m/s on a 10 deg
 *   slope, then landed.
 *   Emergency: motor cut, on the ground after FLIGHT_EMERGENCY_MS.
 *   Geofence: altitude kept between the minimum and maximum altitudes; past
 *   the maximum distance (no_fly_over_max_distance), it turns back home.
 *   Flight plan (Mavlink.Start): takes off if needed, then flies to home and
 *   circles it until stopped (the plan file itself is not simulated).
 *   Battery: drains in flight (BATTERY_FLIGHT_S for a full one).
 */
#define FLIGHT_TICK_MS       100
#define FLIGHT_THROW_MS      2000
#define FLIGHT_CLIMB         5.0  /* m/s */
#define FLIGHT_DESCENT       4.0  /* m/s */
#define FLIGHT_AIRSPEED      12.0 /* m/s, NAV_DEFAULT_AIRSPEED */
#define FLIGHT_AIRSPEED_MIN  8.0
#define FLIGHT_AIRSPEED_MAX  20.0 /* GLOBAL_MAX_INFLIGHT_AIRSPEED */
#define FLIGHT_EMERGENCY_MS  1000
#define FLIGHT_CIRCLE_RADIUS 30.0 /* m, NAV_DEFAULT_RADIUS */
#define FLIGHT_MAX_BANK      0.61 /* rad, 35 deg */
#define FLIGHT_MAX_CLIMB     5.0  /* m/s, full pitch stick */
#define FLIGHT_LANDING_SPEED 10.0 /* m/s, CLDG_DEFAULT_LANDING_SPEED */
#define FLIGHT_LANDING_SLOPE (10.0 * M_PI / 180) /* CLDG_DEFAULT_LANDING_SLOPE */
#define FLIGHT_HOME_REACHED  15.0 /* m */
#define STICK_DEADZONE       5    /* percent */
#define STICK_TIMEOUT_MS     500  /* app PCMD older than this: centred */
#define BATTERY_FLIGHT_S     (45 * 60)
#define BATTERY_CRITICAL     10
#define EARTH_M_PER_DEG      111320.0
#define GRAVITY              9.81

#define FS(_s) ARSDK_ARDRONE3_PILOTINGSTATE_FLYINGSTATECHANGED_STATE_##_s
#define HOME_REASON(_r) ARSDK_ARDRONE3_PILOTINGSTATE_NAVIGATEHOMESTATECHANGED_REASON_##_r

static const struct { const char *name; int32_t value; } flying_states[] = {
	{ "landed", FS(LANDED) },
	{ "takingoff", FS(TAKINGOFF) },
	{ "hovering", FS(HOVERING) },
	{ "flying", FS(FLYING) },
	{ "landing", FS(LANDING) },
	{ "emergency", FS(EMERGENCY) },
	{ "usertakeoff", FS(USERTAKEOFF) },
	{ "motor_ramping", FS(MOTOR_RAMPING) },
	{ "emergency_landing", FS(EMERGENCY_LANDING) },
};
#define FLYING_STATES_COUNT (sizeof(flying_states) / sizeof(flying_states[0]))

static const char *flying_state_name(int32_t value)
{
	size_t i;
	for (i = 0; i < FLYING_STATES_COUNT; i++) {
		if (flying_states[i].value == value)
			return flying_states[i].name;
	}
	return "unknown";
}

static int is_airborne(int32_t state)
{
	return state == FS(TAKINGOFF) || state == FS(HOVERING)
		|| state == FS(FLYING);
}

static void send_home_state(struct app *app, int32_t reason)
{
	int res = 0;
	if (app->cmd_itf == NULL)
		return;
	SEND(res, arsdk_cmd_send_Ardrone3_PilotingState_NavigateHomeStateChanged(
			app->cmd_itf, &send_status, app,
			app->flight.home ?
			ARSDK_ARDRONE3_PILOTINGSTATE_NAVIGATEHOMESTATECHANGED_STATE_INPROGRESS :
			ARSDK_ARDRONE3_PILOTINGSTATE_NAVIGATEHOMESTATECHANGED_STATE_AVAILABLE,
			reason));
}

static void flight_set_state(struct app *app, int32_t state)
{
	int res = 0;
	LOGI("flying state %s -> %s", flying_state_name(app->tm.flying_state),
			flying_state_name(state));
	app->tm.flying_state = state;
	app->flight.state_ms = 0;
	if (app->cmd_itf != NULL)
		SEND(res, arsdk_cmd_send_Ardrone3_PilotingState_FlyingStateChanged(
				app->cmd_itf, &send_status, app, state));
	disco_flying_state(&app->disco, state);
	if (state == FS(LANDED) || state == FS(EMERGENCY))
		disco_flight_plan_stopped(&app->disco);
}

static void flight_set_motion(struct app *app, double altitude, float airspeed)
{
	int res = 0;
	struct telemetry *tm = &app->tm;
	if (altitude != tm->altitude) {
		tm->altitude = altitude;
		if (app->cmd_itf != NULL)
			SEND(res, arsdk_cmd_send_Ardrone3_PilotingState_AltitudeChanged(
					app->cmd_itf, &send_status, app, altitude));
	}
	if (airspeed != tm->airspeed) {
		tm->airspeed = airspeed;
		if (app->cmd_itf != NULL)
			SEND(res, arsdk_cmd_send_Ardrone3_PilotingState_AirSpeedChanged(
					app->cmd_itf, &send_status, app, airspeed));
	}
}

/*
 * Position in metres north and east of an origin: the home position, else
 * the first GPS fix; the telemetry's latitude and longitude follow it when
 * the GPS has a fix.
 */
static int flight_origin(struct app *app)
{
	struct telemetry *tm = &app->tm;
	if (app->flight.origin_lat != 500.0)
		return 1;
	if (tm->home_latitude != 500.0) {
		app->flight.origin_lat = tm->home_latitude;
		app->flight.origin_lon = tm->home_longitude;
	} else if (tm->gps_fixed) {
		app->flight.origin_lat = tm->latitude;
		app->flight.origin_lon = tm->longitude;
	} else {
		return 0;
	}
	app->flight.n = app->flight.e = 0;
	if (tm->gps_fixed && tm->latitude != 500.0) {
		app->flight.n = (tm->latitude - app->flight.origin_lat) * EARTH_M_PER_DEG;
		app->flight.e = (tm->longitude - app->flight.origin_lon) * EARTH_M_PER_DEG
			* cos(app->flight.origin_lat * M_PI / 180);
	}
	return 1;
}

/* Home in local metres (0, 0 without a home position). */
static void flight_home(struct app *app, double *n, double *e)
{
	struct telemetry *tm = &app->tm;
	*n = *e = 0;
	if (tm->home_latitude == 500.0 || !flight_origin(app))
		return;
	*n = (tm->home_latitude - app->flight.origin_lat) * EARTH_M_PER_DEG;
	*e = (tm->home_longitude - app->flight.origin_lon) * EARTH_M_PER_DEG
		* cos(app->flight.origin_lat * M_PI / 180);
}

static double normalize_pi(double a)
{
	while (a > M_PI)
		a -= 2 * M_PI;
	while (a < -M_PI)
		a += 2 * M_PI;
	return a;
}

/** Sends attitude, speed and position from the simulated motion. */
static void flight_send_motion(struct app *app)
{
	int res = 0;
	struct telemetry *tm = &app->tm;
	if (tm->gps_fixed && flight_origin(app)) {
		tm->latitude = app->flight.origin_lat + app->flight.n / EARTH_M_PER_DEG;
		tm->longitude = app->flight.origin_lon + app->flight.e
			/ (EARTH_M_PER_DEG * cos(app->flight.origin_lat * M_PI / 180));
	}
	if (app->cmd_itf == NULL)
		return;
	SEND(res, arsdk_cmd_send_Ardrone3_PilotingState_AttitudeChanged(
			app->cmd_itf, &send_status, app, tm->roll, tm->pitch, tm->yaw));
	SEND(res, arsdk_cmd_send_Ardrone3_PilotingState_SpeedChanged(
			app->cmd_itf, &send_status, app,
			tm->speed_x, tm->speed_y, tm->speed_z));
	if (tm->gps_fixed)
		SEND(res, arsdk_cmd_send_Ardrone3_PilotingState_PositionChanged(
				app->cmd_itf, &send_status, app,
				tm->latitude, tm->longitude, tm->gps_altitude));
}

/** Altitude towards a target at most at the given rates; returns it. */
static double flight_toward(double alt, double target, double up, double down,
		double dt)
{
	if (alt < target)
		return alt + up * dt > target ? target : alt + up * dt;
	if (alt > target)
		return alt - down * dt < target ? target : alt - down * dt;
	return alt;
}

/**
 * Straight flight on the current heading at the current airspeed, with a
 * given bank (turning) and vertical speed (m/s, up positive).
 */
static void flight_move(struct app *app, double bank, double climb, double dt)
{
	struct telemetry *tm = &app->tm;
	double v = tm->airspeed > 0 ? tm->airspeed : FLIGHT_AIRSPEED;
	double alt;
	app->flight.heading = normalize_pi(app->flight.heading
			+ GRAVITY * tan(bank) / v * dt);
	alt = tm->altitude + climb * dt;
	flight_origin(app);
	app->flight.n += v * cos(app->flight.heading) * dt;
	app->flight.e += v * sin(app->flight.heading) * dt;
	tm->yaw = (float)app->flight.heading;
	tm->roll = (float)bank;
	tm->pitch = (float)asin(fmax(-1, fmin(1, climb / v)));
	tm->speed_x = (float)(v * cos(app->flight.heading));
	tm->speed_y = (float)(v * sin(app->flight.heading));
	tm->speed_z = (float)-climb;
	flight_set_motion(app, alt, (float)v);
	flight_send_motion(app);
}

/** Bank to turn towards a heading (rad), proportional and limited. */
static double flight_bank_to(struct app *app, double heading)
{
	double err = normalize_pi(heading - app->flight.heading);
	double bank = err * 1.5;
	return fmax(-FLIGHT_MAX_BANK, fmin(FLIGHT_MAX_BANK, bank));
}

/**
 * Starts circling around a point beside the plane (right for clockwise),
 * so the circle continues the current path.
 */
static void flight_loiter_here(struct app *app, int ccw)
{
	double side = app->flight.heading + (ccw ? -M_PI / 2 : M_PI / 2);
	flight_origin(app);
	app->flight.ccw = ccw;
	app->flight.cn = app->flight.n + FLIGHT_CIRCLE_RADIUS * cos(side);
	app->flight.ce = app->flight.e + FLIGHT_CIRCLE_RADIUS * sin(side);
	app->flight.circle = atan2(app->flight.e - app->flight.ce,
			app->flight.n - app->flight.cn);
}

/** Starts circling around a centre, from where the plane is. */
static void flight_loiter_around(struct app *app, double cn, double ce, int ccw)
{
	app->flight.ccw = ccw;
	app->flight.cn = cn;
	app->flight.ce = ce;
	app->flight.circle = atan2(app->flight.e - ce, app->flight.n - cn);
}

/**
 * Circling, banked into the turn, around (cn, ce): clockwise seen from above
 * unless ccw. Converges to the target altitude.
 */
static void flight_circle_at(struct app *app, double target_alt, double v,
		double dt)
{
	struct telemetry *tm = &app->tm;
	const double r = FLIGHT_CIRCLE_RADIUS;
	double dir = app->flight.ccw ? -1 : 1, alt;

	flight_origin(app);
	app->flight.circle = normalize_pi(app->flight.circle + dir * v / r * dt);
	/* north r.cos(a), east r.sin(a): heading a + 90 deg (cw), a - 90 (ccw) */
	app->flight.heading = normalize_pi(app->flight.circle + dir * M_PI / 2);
	app->flight.n = app->flight.cn + r * cos(app->flight.circle);
	app->flight.e = app->flight.ce + r * sin(app->flight.circle);
	alt = flight_toward(tm->altitude, target_alt, FLIGHT_CLIMB, FLIGHT_DESCENT, dt);
	tm->yaw = (float)app->flight.heading;
	tm->roll = (float)(dir * atan(v * v / (GRAVITY * r)));
	tm->pitch = (float)asin((alt - tm->altitude) / dt / v);
	tm->speed_x = (float)(v * cos(app->flight.heading));
	tm->speed_y = (float)(v * sin(app->flight.heading));
	tm->speed_z = (float)-((alt - tm->altitude) / dt);
	flight_set_motion(app, alt, (float)v);
	flight_send_motion(app);
}

static void flight_circle(struct app *app, double target_alt, double dt)
{
	flight_circle_at(app, target_alt, FLIGHT_AIRSPEED, dt);
}

static void flight_stop_home(struct app *app, int32_t reason)
{
	if (!app->flight.home)
		return;
	app->flight.home = 0;
	send_home_state(app, reason);
}

static void flight_start_home(struct app *app, int32_t reason)
{
	if (app->flight.home)
		return;
	app->flight.active = 1;
	app->flight.home = 1;
	app->flight.over_home = 0;
	LOGI("return home (reason %d)", reason);
	send_home_state(app, reason);
	if (app->tm.flying_state != FS(FLYING))
		flight_set_state(app, FS(FLYING));
}

/** Hand launch: from motor ramping or armed user take-off to taking off. */
static int flight_throw(struct app *app)
{
	int32_t st = app->tm.flying_state;
	if (st != FS(MOTOR_RAMPING) && st != FS(USERTAKEOFF))
		return -EINVAL;
	app->flight.active = 1;
	flight_set_state(app, FS(TAKINGOFF));
	return 0;
}

/** The pilot's sticks: the controller's, else the app's recent PCMD. */
static int flight_sticks(struct app *app, double *roll, double *pitch,
		double *gaz)
{
	int r = app->flight.stick_roll, p = app->flight.stick_pitch;
	int g = app->flight.stick_gaz;
	/* a hobby radio on the Disco's receiver, enabled and given control
	 * (rc.h): its sticks fly it, the controller's are ignored */
	if (rc_piloting(&app->disco.rc, roll, pitch, gaz))
		return fabs(*roll) > 0.05 || fabs(*pitch) > 0.05 || fabs(*gaz) > 0.05;
	if (r == 0 && p == 0 && g == 0 && app->flight.pcmd_flag
			&& now_ms() - app->flight.pcmd_ms < STICK_TIMEOUT_MS) {
		r = app->flight.pcmd_roll;
		p = app->flight.pcmd_pitch;
		g = app->flight.pcmd_gaz;
	}
	if (abs(r) < STICK_DEADZONE) r = 0;
	if (abs(p) < STICK_DEADZONE) p = 0;
	if (abs(g) < STICK_DEADZONE) g = 0;
	/* inverted pitch mode: stick back to dive */
	if (app->disco.s.pitch_mode)
		p = -p;
	*roll = r / 100.0;
	*pitch = p / 100.0;
	*gaz = g / 100.0;
	return r != 0 || p != 0 || g != 0;
}

/** Return home navigation: to home, over it, then circling around it. */
static void flight_go_home(struct app *app, double target_alt, double dt,
		int finish)
{
	double hn, he, dn, de, dist, next_alt;
	flight_home(app, &hn, &he);
	dn = hn - app->flight.n;
	de = he - app->flight.e;
	dist = sqrt(dn * dn + de * de);
	if (!app->flight.over_home && dist < FLIGHT_HOME_REACHED)
		app->flight.over_home = 1;
	if (app->flight.over_home && dist >= FLIGHT_CIRCLE_RADIUS) {
		/* past home by a circle radius: circle around it */
		flight_loiter_around(app, hn, he,
				app->disco.s.circling_direction != 0);
		if (finish) {
			flight_stop_home(app, HOME_REASON(FINISHED));
			flight_set_state(app, FS(HOVERING));
		} else {
			app->flight.fp_circling = 1;
		}
		return;
	}
	next_alt = flight_toward(app->tm.altitude, target_alt, FLIGHT_CLIMB,
			FLIGHT_DESCENT, dt);
	flight_move(app, app->flight.over_home ? 0 :
			flight_bank_to(app, atan2(de, dn)),
			(next_alt - app->tm.altitude) / dt, dt);
}

static void flight_battery(struct app *app, double dt)
{
	struct telemetry *tm = &app->tm;
	int res = 0;
	uint8_t level;
	app->flight.battery_used += 100.0 * dt / BATTERY_FLIGHT_S;
	if (app->flight.battery_used < 1.0 || tm->battery == 0)
		return;
	app->flight.battery_used -= 1.0;
	level = tm->battery - 1;
	tm->battery = level;
	if (app->cmd_itf != NULL)
		SEND(res, arsdk_cmd_send_Common_CommonState_BatteryStateChanged(
				app->cmd_itf, &send_status, app, level));
	disco_battery(&app->disco, level);
}

/** The controller is out of reach: in mux mode the Disco-controller radio
 * link, in Disco mode the app itself. */
static int flight_link_lost(struct app *app)
{
	if (app->backend_type == ARSDK_BACKEND_TYPE_MUX)
		return app->drone_link_lost;
	return app->disco_mode && app->peer == NULL;
}

static void flight_manual(struct app *app, double dt)
{
	struct telemetry *tm = &app->tm;
	const struct disco_settings *s = &app->disco.s;
	double roll, pitch, gaz, climb, dist, hn, he;
	double v;

	flight_sticks(app, &roll, &pitch, &gaz);
	v = gaz >= 0 ? FLIGHT_AIRSPEED + gaz * (FLIGHT_AIRSPEED_MAX - FLIGHT_AIRSPEED)
		: FLIGHT_AIRSPEED + gaz * (FLIGHT_AIRSPEED - FLIGHT_AIRSPEED_MIN);
	tm->airspeed = (float)v;
	/* stick forward (positive pitch) dives */
	climb = -pitch * FLIGHT_MAX_CLIMB;
	/* geofence: between the minimum and maximum altitudes */
	if (climb > 0 && tm->altitude + climb * dt > s->max_altitude)
		climb = fmax(0, (s->max_altitude - tm->altitude) / dt);
	if (climb < 0 && tm->altitude + climb * dt < s->min_altitude)
		climb = fmin(0, (s->min_altitude - tm->altitude) / dt);
	/* geofence: past the maximum distance, it turns back */
	flight_home(app, &hn, &he);
	dist = hypot(app->flight.n - hn, app->flight.e - he);
	if (s->no_fly_over_max_distance && dist > s->max_distance) {
		flight_move(app, flight_bank_to(app,
				atan2(he - app->flight.e, hn - app->flight.n)),
				climb, dt);
		return;
	}
	flight_move(app, roll * FLIGHT_MAX_BANK, climb, dt);
}

static void flight_plan_cancel(struct app *app);
static void sim_event(struct app *app, const char *name);

/**
 * External simulation: the Disco's own safety returns still come from here,
 * as "home_start" events for the simulator (rig/sim/bridge): after
 * return_home_delay without the controller, and on a critical battery.
 */
static void flight_watch_external(struct app *app)
{
	struct telemetry *tm = &app->tm;
	int airborne = is_airborne(tm->flying_state);
	if (!airborne) {
		app->flight.low_battery_home = 0;
		app->flight.link_lost_ms = 0;
		return;
	}
	if (flight_link_lost(app) && !app->flight.home) {
		app->flight.link_lost_ms += FLIGHT_TICK_MS;
		if (app->flight.link_lost_ms == app->disco.s.return_home_delay * 1000u) {
			LOGI("connection lost: return home");
			sim_event(app, "home_start");
		}
	} else if (!flight_link_lost(app)) {
		app->flight.link_lost_ms = 0;
	}
	if (tm->battery <= BATTERY_CRITICAL && !app->flight.home
			&& !app->flight.low_battery_home) {
		app->flight.low_battery_home = 1;
		LOGI("critical battery: return home");
		sim_event(app, "home_start");
	}
}

static void flight_tick(struct pomp_timer *timer, void *userdata)
{
	struct app *app = userdata;
	struct telemetry *tm = &app->tm;
	const struct disco_settings *s = &app->disco.s;
	const double dt = FLIGHT_TICK_MS / 1000.0;
	double roll, pitch, gaz;
	int sticks;

	if (app->sim_external) {
		flight_watch_external(app);
		return;
	}
	if (!app->flight.active)
		return;
	app->flight.state_ms += FLIGHT_TICK_MS;
	sticks = flight_sticks(app, &roll, &pitch, &gaz);

	if (is_airborne(tm->flying_state) || tm->flying_state == FS(LANDING))
		flight_battery(app, dt);
	/* critical battery: back home, once per flight */
	if (tm->battery <= BATTERY_CRITICAL && is_airborne(tm->flying_state)
			&& !app->flight.home && !app->flight.low_battery_home) {
		app->flight.low_battery_home = 1;
		flight_plan_cancel(app);
		flight_start_home(app, HOME_REASON(LOWBATTERY));
	}

	/* connection lost: back home after return_home_delay */
	if (flight_link_lost(app) && is_airborne(tm->flying_state)
			&& !app->flight.home) {
		app->flight.link_lost_ms += FLIGHT_TICK_MS;
		if (app->flight.link_lost_ms >= s->return_home_delay * 1000u)
			flight_start_home(app, HOME_REASON(CONNECTIONLOST));
	} else if (!flight_link_lost(app)) {
		app->flight.link_lost_ms = 0;
	}

	switch (tm->flying_state) {
	case FS(MOTOR_RAMPING):
		if (app->flight.state_ms >= FLIGHT_THROW_MS)
			flight_throw(app);
		break;
	case FS(TAKINGOFF):
		flight_move(app, 0, FLIGHT_CLIMB, dt);
		if (tm->altitude >= s->circling_altitude) {
			flight_set_motion(app, s->circling_altitude, FLIGHT_AIRSPEED);
			flight_loiter_here(app, s->circling_direction != 0);
			flight_set_state(app, app->flight.fp ? FS(FLYING) : FS(HOVERING));
		}
		break;
	case FS(HOVERING):
		if (sticks) {
			/* the pilot takes over: flying from the current path */
			flight_set_state(app, FS(FLYING));
			flight_manual(app, dt);
			break;
		}
		flight_circle(app, s->circling_altitude, dt);
		break;
	case FS(FLYING):
		if (app->flight.home) {
			flight_go_home(app, s->circling_altitude, dt, 1);
		} else if (app->flight.fp) {
			if (app->flight.fp_circling)
				flight_circle(app, s->circling_altitude, dt);
			else
				flight_go_home(app, s->circling_altitude, dt, 0);
		} else {
			flight_manual(app, dt);
		}
		break;
	case FS(LANDING): {
		/* circular landing: a descending circle at the landing speed
		 * and slope */
		double alt = tm->altitude - FLIGHT_LANDING_SPEED
			* sin(FLIGHT_LANDING_SLOPE) * dt;
		if (alt <= 0) {
			flight_set_motion(app, 0, 0);
			tm->roll = tm->pitch = 0;
			tm->speed_x = tm->speed_y = tm->speed_z = 0;
			flight_send_motion(app);
			flight_set_state(app, FS(LANDED));
			app->flight.active = 0;
		} else {
			flight_circle_at(app, alt, FLIGHT_LANDING_SPEED, dt);
		}
		break;
	}
	case FS(EMERGENCY):
		if (app->flight.state_ms >= FLIGHT_EMERGENCY_MS) {
			flight_set_motion(app, 0, 0);
			flight_set_state(app, FS(LANDED));
			app->flight.active = 0;
		}
		break;
	default:
		break;
	}
}

/** Queues a piloting event for the external simulator ("events"). */
static void sim_event(struct app *app, const char *name)
{
	size_t len = strlen(app->events);
	LOGI("sim event %s", name);
	if (len + strlen(name) + 2 < sizeof(app->events))
		snprintf(app->events + len, sizeof(app->events) - len, "%s%s",
				len ? " " : "", name);
}

/** Decodes the app's piloting stream (sticks). */
static void flight_pcmd(struct app *app, const struct arsdk_cmd *cmd)
{
	uint8_t flag = 0;
	int8_t roll = 0, pitch = 0, yaw = 0, gaz = 0;
	uint32_t ts = 0;
	app->flight.pcmd++;
	if (arsdk_cmd_dec_Ardrone3_Piloting_PCMD(cmd, &flag, &roll, &pitch,
			&yaw, &gaz, &ts) < 0)
		return;
	app->flight.pcmd_flag = flag;
	app->flight.pcmd_roll = roll;
	app->flight.pcmd_pitch = pitch;
	app->flight.pcmd_gaz = gaz;
	app->flight.pcmd_ms = now_ms();
}

/** Piloting commands in external simulation mode: queued, not simulated. */
static int sim_recv_cmd(struct app *app, const struct arsdk_cmd *cmd)
{
	uint8_t arg = 0;
	int32_t dir = 0;
	switch (cmd->id) {
	case ARSDK_ID_ARDRONE3_PILOTING_PCMD:
		flight_pcmd(app, cmd);
		return 1;
	case ARSDK_ID_ARDRONE3_PILOTING_TAKEOFF:
		sim_event(app, "takeoff");
		return 1;
	case ARSDK_ID_ARDRONE3_PILOTING_USERTAKEOFF:
		if (arsdk_cmd_dec_Ardrone3_Piloting_UserTakeOff(cmd, &arg) >= 0 && arg)
			sim_event(app, "usertakeoff");
		return 1;
	case ARSDK_ID_ARDRONE3_PILOTING_LANDING:
		sim_event(app, "landing");
		return 1;
	case ARSDK_ID_ARDRONE3_PILOTING_EMERGENCY:
		sim_event(app, "emergency");
		return 1;
	case ARSDK_ID_ARDRONE3_PILOTING_NAVIGATEHOME:
		if (arsdk_cmd_dec_Ardrone3_Piloting_NavigateHome(cmd, &arg) >= 0)
			sim_event(app, arg ? "home_start" : "home_stop");
		return 1;
	case ARSDK_ID_ARDRONE3_PILOTING_CIRCLE:
		if (arsdk_cmd_dec_Ardrone3_Piloting_Circle(cmd, &dir) >= 0)
			sim_event(app, "circle");
		return 1;
	default:
		return 0;
	}
}

/** Starts the take-off sequence when landed; returns 1 if it did. */
static int flight_takeoff(struct app *app)
{
	if (app->tm.flying_state != FS(LANDED))
		return 0;
	app->flight.active = 1;
	app->flight.home = 0;
	app->flight.low_battery_home = 0;
	app->flight.origin_lat = 500.0; /* from the take-off position */
	app->flight.heading = app->tm.yaw;
	flight_set_state(app, FS(MOTOR_RAMPING));
	return 1;
}

/** Flight plan requests from disco.c (Mavlink feature). */
static void flight_plan(struct app *app)
{
	int32_t st = app->tm.flying_state;
	int req = app->disco.fp_request;
	app->disco.fp_request = DISCO_FP_NONE;
	switch (req) {
	case DISCO_FP_START:
		app->flight.fp = 1;
		app->flight.fp_circling = 0;
		app->flight.over_home = 0;
		flight_stop_home(app, HOME_REASON(STOPPED));
		if (!flight_takeoff(app) && (st == FS(HOVERING) || st == FS(FLYING))) {
			app->flight.active = 1;
			flight_set_state(app, FS(FLYING));
		}
		break;
	case DISCO_FP_PAUSE:
	case DISCO_FP_STOP:
		if (!app->flight.fp)
			break;
		app->flight.fp = 0;
		if (st == FS(FLYING)) {
			flight_loiter_here(app, app->disco.s.circling_direction != 0);
			flight_set_state(app, FS(HOVERING));
		}
		break;
	default:
		break;
	}
}

/** Ends a flight plan when another piloting command takes over. */
static void flight_plan_cancel(struct app *app)
{
	if (!app->flight.fp)
		return;
	app->flight.fp = 0;
	disco_flight_plan_stopped(&app->disco);
}

/** Piloting commands from the app; returns 1 if the command was one. */
static int flight_recv_cmd(struct app *app, const struct arsdk_cmd *cmd)
{
	int res = 0;
	uint8_t arg = 0;
	int32_t dir = 0;
	int32_t st = app->tm.flying_state;

	if (app->sim_external)
		return sim_recv_cmd(app, cmd);

	switch (cmd->id) {
	case ARSDK_ID_ARDRONE3_PILOTING_PCMD:
		flight_pcmd(app, cmd);
		return 1;
	case ARSDK_ID_ARDRONE3_PILOTING_TAKEOFF:
		flight_takeoff(app);
		return 1;
	case ARSDK_ID_ARDRONE3_PILOTING_USERTAKEOFF:
		res = arsdk_cmd_dec_Ardrone3_Piloting_UserTakeOff(cmd, &arg);
		if (res < 0)
			return 1;
		if (arg && st == FS(LANDED)) {
			app->flight.active = 1;
			app->flight.origin_lat = 500.0;
			app->flight.heading = app->tm.yaw;
			flight_set_state(app, FS(USERTAKEOFF));
		} else if (!arg && (st == FS(USERTAKEOFF)
				|| st == FS(MOTOR_RAMPING))) {
			flight_set_state(app, FS(LANDED));
			app->flight.active = 0;
		}
		return 1;
	case ARSDK_ID_ARDRONE3_PILOTING_LANDING:
		if (is_airborne(st)) {
			int res2 = 0;
			app->flight.active = 1;
			flight_plan_cancel(app);
			flight_stop_home(app, HOME_REASON(STOPPED));
			/* the descending circle starts beside the plane */
			if (st != FS(HOVERING))
				flight_loiter_here(app, app->disco.s.circling_direction != 0);
			if (app->cmd_itf != NULL)
				SEND(res2, arsdk_cmd_send_Ardrone3_PilotingState_LandingStateChanged(
						app->cmd_itf, &send_status, app,
						ARSDK_ARDRONE3_PILOTINGSTATE_LANDINGSTATECHANGED_STATE_SPIRAL));
			flight_set_state(app, FS(LANDING));
		} else if (st == FS(MOTOR_RAMPING) || st == FS(USERTAKEOFF)) {
			flight_set_state(app, FS(LANDED));
			app->flight.active = 0;
		}
		return 1;
	case ARSDK_ID_ARDRONE3_PILOTING_EMERGENCY:
		if (st != FS(LANDED)) {
			app->flight.active = 1;
			flight_plan_cancel(app);
			flight_stop_home(app, HOME_REASON(STOPPED));
			flight_set_motion(app, app->tm.altitude, 0);
			flight_set_state(app, FS(EMERGENCY));
		}
		return 1;
	case ARSDK_ID_ARDRONE3_PILOTING_NAVIGATEHOME:
		res = arsdk_cmd_dec_Ardrone3_Piloting_NavigateHome(cmd, &arg);
		if (res < 0)
			return 1;
		if (arg && !app->flight.home
				&& (st == FS(HOVERING) || st == FS(FLYING))) {
			flight_plan_cancel(app);
			flight_start_home(app, HOME_REASON(USERREQUEST));
		} else if (!arg && app->flight.home) {
			flight_stop_home(app, HOME_REASON(STOPPED));
			flight_loiter_here(app, app->disco.s.circling_direction != 0);
			flight_set_state(app, FS(HOVERING));
		} else {
			send_home_state(app, HOME_REASON(USERREQUEST));
		}
		return 1;
	case ARSDK_ID_ARDRONE3_PILOTING_CIRCLE:
		/* the loiter button: circle here, in the given direction */
		if (arsdk_cmd_dec_Ardrone3_Piloting_Circle(cmd, &dir) < 0)
			return 1;
		if (st == FS(FLYING) || st == FS(HOVERING)) {
			int ccw = dir == ARSDK_ARDRONE3_PILOTING_CIRCLE_DIRECTION_DEFAULT ?
				app->disco.s.circling_direction != 0 :
				dir == ARSDK_ARDRONE3_PILOTING_CIRCLE_DIRECTION_CCW;
			flight_plan_cancel(app);
			flight_stop_home(app, HOME_REASON(STOPPED));
			app->flight.active = 1;
			flight_loiter_here(app, ccw);
			if (st != FS(HOVERING))
				flight_set_state(app, FS(HOVERING));
		}
		return 1;
	default:
		return 0;
	}
}

/**
 * A SkyController button the app didn't grab acts on the drone, as on the
 * real controller: take-off/land (code 2) takes off when landed and lands
 * otherwise, return home (code 1) starts or stops it. Returns 0 if the
 * button has a drone action.
 */
static int drone_button(struct app *app, uint32_t code)
{
	struct arsdk_cmd cmd;
	int res = -ENOENT;
	arsdk_cmd_init(&cmd);
	if (code == 2) {
		res = app->tm.flying_state == FS(LANDED) ?
			arsdk_cmd_enc_Ardrone3_Piloting_TakeOff(&cmd) :
			arsdk_cmd_enc_Ardrone3_Piloting_Landing(&cmd);
	} else if (code == 1) {
		res = arsdk_cmd_enc_Ardrone3_Piloting_NavigateHome(&cmd,
				app->flight.home ? 0 : 1);
	}
	if (res >= 0) {
		LOGI("controller button %u: drone action", code);
		flight_recv_cmd(app, &cmd);
	}
	arsdk_cmd_clear(&cmd);
	return res < 0 ? res : 0;
}

/** Records a command nothing in the fake handles (once per name). */
static void note_unhandled(struct app *app, const struct arsdk_cmd *cmd)
{
	const struct arsdk_cmd_desc *desc = arsdk_cmd_find_desc(cmd);
	char name[96];
	size_t len = strlen(app->unhandled);
	if (desc != NULL)
		snprintf(name, sizeof(name), "%s", desc->name);
	else
		snprintf(name, sizeof(name), "%u.%u.%u", cmd->prj_id, cmd->cls_id, cmd->cmd_id);
	/* whole-word search in the space-separated list */
	{
		const char *p = app->unhandled;
		size_t n = strlen(name);
		while ((p = strstr(p, name)) != NULL) {
			if ((p == app->unhandled || p[-1] == ' ') && (p[n] == ' ' || p[n] == '\0'))
				return;
			p += n;
		}
	}
	LOGW("unhandled %s", name);
	if (len + strlen(name) + 2 < sizeof(app->unhandled))
		snprintf(app->unhandled + len, sizeof(app->unhandled) - len, "%s%s",
				len ? " " : "", name);
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

	/* Format and log received command (not the 25 Hz piloting stream) */
	if (cmd->id != ARSDK_ID_ARDRONE3_PILOTING_PCMD) {
		arsdk_cmd_fmt(cmd, buf, sizeof(buf));
		LOGI("recv %s", buf);
	}
	if (flight_recv_cmd(app, cmd))
		return;
	if (sc2_recv_cmd(&app->sc2, cmd))
		return;
	if (disco_recv_cmd(&app->disco, cmd)) {
		if (app->disco.fp_request != DISCO_FP_NONE && !app->sim_external)
			flight_plan(app);
		if (app->disco.wifi_restart) {
			app->disco.wifi_restart = 0;
			LOGI("wifi restart");
			if (app->backend_type == ARSDK_BACKEND_TYPE_MUX
					&& app->flight.wifi_timer != NULL
					&& !app->flight.wifi_down)
				pomp_timer_set(app->flight.wifi_timer,
						WIFI_RESTART_DELAY_MS);
		}
		return;
	}

	switch (cmd->id) {
	/* SkyController */
	case ARSDK_ID_SKYCTRL_SETTINGS_ALLSETTINGS:
		SEND(res, arsdk_cmd_send_Skyctrl_SettingsState_ProductVersionChanged(
				app->cmd_itf, &send_status, app,
				FAKE_SC2_SOFTWARE, FAKE_SC2_HARDWARE));
		SEND(res, arsdk_cmd_send_Skyctrl_SettingsState_ProductSerialChanged(
				app->cmd_itf, &send_status, app, "PI040443FAKESC2"));
		SEND(res, arsdk_cmd_send_Skyctrl_SettingsState_AllSettingsChanged(
				app->cmd_itf, &send_status, app));
		break;
	case ARSDK_ID_SKYCTRL_COMMON_ALLSTATES:
		send_drone_link(app);
		/* calibrated magnetometer: no "calibration" alert in FreeFlight */
		SEND(res, arsdk_cmd_send_Skyctrl_CalibrationState_MagnetoCalibrationStateV2(
				app->cmd_itf, &send_status, app,
				ARSDK_SKYCTRL_CALIBRATIONSTATE_MAGNETOCALIBRATIONSTATEV2_STATE_CALIBRATED));
		SEND(res, arsdk_cmd_send_Skyctrl_CalibrationState_MagnetoCalibrationState(
				app->cmd_itf, &send_status, app,
				ARSDK_SKYCTRL_CALIBRATIONSTATE_MAGNETOCALIBRATIONSTATE_STATUS_CALIBRATED,
				100, 100, 100));
		send_rc_state(app);
		sc2_send_states(&app->sc2);
		SEND(res, arsdk_cmd_send_Skyctrl_CommonState_AllStatesChanged(
				app->cmd_itf, &send_status, app));
		break;

	/* Drone, proxied by the controller */
	case ARSDK_ID_COMMON_SETTINGS_ALLSETTINGS:
		SEND(res, arsdk_cmd_send_Common_SettingsState_ProductVersionChanged(
				app->cmd_itf, &send_status, app,
				FAKE_DRONE_SOFTWARE, FAKE_DRONE_HARDWARE));
		disco_send_settings(&app->disco);
		SEND(res, arsdk_cmd_send_Common_SettingsState_AllSettingsChanged(
				app->cmd_itf, &send_status, app));
		break;
	case ARSDK_ID_ARDRONE3_MEDIASTREAMING_VIDEOENABLE: {
		uint8_t enable = 0;
		res = arsdk_cmd_dec_Ardrone3_MediaStreaming_VideoEnable(cmd, &enable);
		if (res < 0) {
			LOG_ERRNO("decode VideoEnable", -res);
			break;
		}
		app->video.enabled = enable != 0;
		LOGI("video %s", app->video.enabled ? "enabled" : "disabled");
		SEND(res, arsdk_cmd_send_Ardrone3_MediaStreamingState_VideoEnableChanged(
				app->cmd_itf, &send_status, app,
				app->video.enabled ?
				ARSDK_ARDRONE3_MEDIASTREAMINGSTATE_VIDEOENABLECHANGED_ENABLED_ENABLED :
				ARSDK_ARDRONE3_MEDIASTREAMINGSTATE_VIDEOENABLECHANGED_ENABLED_DISABLED));
		break;
	}
	case ARSDK_ID_COMMON_COMMON_ALLSTATES:
		send_telemetry(app);
		disco_send_states(&app->disco);
		SEND(res, arsdk_cmd_send_Common_CommonState_AllStatesChanged(
				app->cmd_itf, &send_status, app));
		break;
	case ARSDK_ID_COMMON_COMMON_REBOOT:
		/* the Disco restarts: gone for REBOOT_MS, settings kept */
		if (app->tm.flying_state != FS(LANDED))
			break;
		LOGI("reboot");
		if (app->backend_type == ARSDK_BACKEND_TYPE_MUX) {
			app->drone_link_lost = 1;
			send_drone_link(app);
		} else if (app->peer != NULL) {
			arsdk_peer_disconnect(app->peer);
		}
		if (app->flight.reboot_timer != NULL)
			pomp_timer_set(app->flight.reboot_timer, REBOOT_MS);
		break;
	/* the app's clock for the controller, its own position: no answer */
	case ARSDK_ID_SKYCTRL_COMMON_CURRENTDATETIME:
		break;
	default:
		note_unhandled(app, cmd);
		break;
	}
}

/** The Disco's Wi-Fi restarting: down, then back after WIFI_RESTART_MS. */
static void wifi_restart_cb(struct pomp_timer *timer, void *userdata)
{
	struct app *app = userdata;
	if (!app->flight.wifi_down) {
		LOGI("wifi down");
		app->flight.wifi_down = 1;
		app->drone_link_lost = 1;
		send_drone_link(app);
		pomp_timer_set(timer, WIFI_RESTART_MS);
	} else {
		LOGI("wifi up");
		app->flight.wifi_down = 0;
		app->drone_link_lost = 0;
		send_drone_link(app);
	}
}

/** End of a reboot: the Disco is back (mux mode). */
static void reboot_done_cb(struct pomp_timer *timer, void *userdata)
{
	struct app *app = userdata;
	LOGI("reboot done");
	if (app->backend_type == ARSDK_BACKEND_TYPE_MUX && app->drone_link_lost) {
		app->drone_link_lost = 0;
		send_drone_link(app);
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
	app->disco.itf = app->cmd_itf;
	app->sc2.itf = app->cmd_itf;
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
	app->disco.itf = NULL;
	app->peer = NULL;
	/* the SkyController 2 streams the Disco's video by itself (GroundSdk
	 * notes it; its VideoEnable is a no-op there): only a direct Wi-Fi
	 * controller (Disco mode) has to ask again */
	if (app->disco_mode)
		app->video.enabled = 0;
	app->sc2.grabbed_buttons = app->sc2.grabbed_axes = 0;
	app->sc2.itf = NULL;
	app->video.client_valid = 0;
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

/** fake-sc2: minimal integer lookup in a flat JSON object. */
static uint16_t json_int(const char *json, const char *key, uint16_t def)
{
	char pattern[96];
	const char *p;
	snprintf(pattern, sizeof(pattern), "\"%s\"", key);
	p = strstr(json, pattern);
	if (p == NULL)
		return def;
	p = strchr(p + strlen(pattern), ':');
	return p != NULL ? (uint16_t)atoi(p + 1) : def;
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
		"\"arstream_max_ack_interval\": -1, "
		"\"arstream2_server_stream_port\": 5004, "
		"\"arstream2_server_control_port\": 5005, "
		"\"c2d_update_port\": 51 ,"
		"\"c2d_user_port\": 21"
		"}";
	LOGI("%s: ctrl_name=%s ctrl_type=%s ctrl_addr=%s json=%s", __func__,
			info->ctrl_name, info->ctrl_type, info->ctrl_addr,
			info->json);

	/* One peer at a time. A new controller replaces the current one: a
	 * killed app never closes its session, so it would otherwise lock
	 * the fake until the link times out (same as the mux mode). Done
	 * first, since disconnected() resets the video client. */
	if (app->peer != NULL) {
		LOGI("replacing the current controller");
		res = arsdk_peer_disconnect(app->peer);
		if (res < 0)
			LOG_ERRNO("arsdk_peer_disconnect", -res);
		app->cmd_itf = NULL;
		app->disco.itf = NULL;
		app->sc2.itf = NULL;
		app->peer = NULL;
	}

	/* fake-sc2 Disco mode: the controller tells where it wants video */
	if (app->disco_mode && info->ctrl_addr != NULL && info->json != NULL) {
		uint16_t stream = json_int(info->json,
				"arstream2_client_stream_port", 55004);
		uint16_t ctrl = json_int(info->json,
				"arstream2_client_control_port", 55005);
		memset(&app->video.client_stream, 0, sizeof(struct sockaddr_in));
		app->video.client_stream.sin_family = AF_INET;
		app->video.client_stream.sin_port = htons(stream);
		app->video.client_ctrl = app->video.client_stream;
		app->video.client_ctrl.sin_port = htons(ctrl);
		app->video.client_valid = inet_pton(AF_INET, info->ctrl_addr,
				&app->video.client_stream.sin_addr) == 1;
		app->video.client_ctrl.sin_addr =
				app->video.client_stream.sin_addr;
		LOGI("video: client %s stream %u control %u (%s)",
				info->ctrl_addr, stream, ctrl,
				app->video.client_valid ? "ok" : "invalid address");
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
	if (app->disco_mode) {
		publisher_cfg.name = app->disco.s.name;
		publisher_cfg.type = ARSDK_DEVICE_TYPE_EVINRUDE;
		publisher_cfg.id = "PI040409FAKEDISCO";
	}
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

/* fake-sc2: data the app sends on the video channels (RTCP receiver reports)
 * is not needed; only log channel resets. */
static void video_mux_cb(struct mux_ctx *ctx, uint32_t chanid,
		enum mux_channel_event event, struct pomp_buffer *buf,
		void *userdata)
{
	if (event == MUX_CHANNEL_RESET)
		LOGI("video: mux channel 0x%08x reset", chanid);
}

static void video_channels_open(struct app *app)
{
	int res;
	res = mux_channel_open(app->mux.ctx, MUX_ARSDK_CHANNEL_ID_STREAM_DATA,
			&video_mux_cb, app);
	if (res < 0) {
		LOG_ERRNO("mux_channel_open(STREAM_DATA)", -res);
		return;
	}
	res = mux_channel_open(app->mux.ctx, MUX_ARSDK_CHANNEL_ID_STREAM_CONTROL,
			&video_mux_cb, app);
	if (res < 0) {
		LOG_ERRNO("mux_channel_open(STREAM_CONTROL)", -res);
		return;
	}
	app->video.channels = 1;
}

static void video_udp_cb(int fd, uint32_t revents, void *userdata)
{
	struct app *app = userdata;
	uint8_t data[65536];
	ssize_t len;
	int rtcp = fd == app->video.rtcp_fd;

	while ((len = recv(fd, data, sizeof(data), MSG_DONTWAIT)) > 0) {
		struct pomp_buffer *buf;
		int res;
		if (app->disco_mode) {
			const struct sockaddr_in *to = rtcp ?
				&app->video.client_ctrl : &app->video.client_stream;
			if (!app->video.client_valid || !app->video.enabled ||
					app->video_failed ||
					app->cmd_itf == NULL || app->video.out_fd < 0) {
				app->video.dropped++;
				continue;
			}
			if (sendto(app->video.out_fd, data, (size_t)len, 0,
					(const struct sockaddr *)to,
					sizeof(*to)) < 0) {
				app->video.dropped++;
				continue;
			}
			if (rtcp)
				app->video.rtcp_packets++;
			else if (++app->video.rtp_packets % 3000 == 1)
				LOGI("video: sent rtp=%lu rtcp=%lu dropped=%lu",
						app->video.rtp_packets,
						app->video.rtcp_packets,
						app->video.dropped);
			continue;
		}
		if (app->mux.ctx == NULL || !app->video.channels ||
				!app->video.enabled || app->cmd_itf == NULL ||
				app->drone_link_lost || app->video_failed) {
			app->video.dropped++;
			continue;
		}
		buf = pomp_buffer_new_with_data(data, (size_t)len);
		if (buf == NULL)
			continue;
		res = mux_encode(app->mux.ctx, rtcp ?
				MUX_ARSDK_CHANNEL_ID_STREAM_CONTROL :
				MUX_ARSDK_CHANNEL_ID_STREAM_DATA, buf);
		pomp_buffer_unref(buf);
		if (res < 0) {
			app->video.dropped++;
			continue;
		}
		if (rtcp)
			app->video.rtcp_packets++;
		else if (++app->video.rtp_packets % 3000 == 1)
			LOGI("video: forwarded rtp=%lu rtcp=%lu dropped=%lu",
					app->video.rtp_packets,
					app->video.rtcp_packets,
					app->video.dropped);
	}
}

static int video_udp_bind(struct app *app, uint16_t port)
{
	int res;
	struct sockaddr_in addr;
	int fd = socket(AF_INET, SOCK_DGRAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
	if (fd < 0) {
		LOG_ERRNO("socket", errno);
		return -1;
	}
	memset(&addr, 0, sizeof(addr));
	addr.sin_family = AF_INET;
	addr.sin_addr.s_addr = htonl(app->disco_mode ? INADDR_ANY : INADDR_LOOPBACK);
	addr.sin_port = htons(port);
	if (bind(fd, (const struct sockaddr *)&addr, sizeof(addr)) < 0) {
		LOG_FD_ERRNO("bind", fd, errno);
		close(fd);
		return -1;
	}
	res = pomp_loop_add(app->loop, fd, POMP_FD_EVENT_IN, &video_udp_cb, app);
	if (res < 0)
		LOG_ERRNO("pomp_loop_add", -res);
	return fd;
}

static void video_udp_create(struct app *app)
{
	if (app->disco_mode) {
		app->video.out_fd = socket(AF_INET,
				SOCK_DGRAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
		if (app->video.out_fd < 0)
			LOG_ERRNO("socket", errno);
	}
	app->video.rtp_fd = video_udp_bind(app, app->video.port);
	app->video.rtcp_fd = video_udp_bind(app, (uint16_t)(app->video.port + 1));
	LOGI("video: RTP on 127.0.0.1:%u, RTCP on %u", app->video.port,
			app->video.port + 1);
}

static void video_udp_destroy(struct app *app)
{
	int *fds[] = { &app->video.rtp_fd, &app->video.rtcp_fd };
	size_t i;
	if (app->video.out_fd >= 0) {
		close(app->video.out_fd);
		app->video.out_fd = -1;
	}
	for (i = 0; i < 2; i++) {
		if (*fds[i] < 0)
			continue;
		pomp_loop_remove(app->loop, *fds[i]);
		close(*fds[i]);
		*fds[i] = -1;
	}
}

/**
 */
static void mux_server_fdeof(struct mux_ctx *ctx, void *userdata)
{
	struct app *app = userdata;
	app->video.channels = 0;
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

	if (sockfd < 0)
		return;

	/* fake-sc2: "unplug <s>": no phone connection for a while */
	if (now_ms() < app->mux.unplugged_until_ms) {
		close(sockfd);
		return;
	}

	/* fake-sc2: like the real controller's single USB port, a new phone
	 * connection replaces the previous session (an app killed on the
	 * emulator leaves its session open until mux pings time out). */
	if (app->mux.sockfd != -1) {
		LOGI("mux: new connection, closing the previous session");
		shutdown(app->mux.sockfd, SHUT_RDWR);
		mux_server_fdeof(app->mux.ctx, app);
	}

	/* fake-sc2: ARSDK acknowledged commands go one at a time, each waiting
	 * for its small ACK frame: with Nagle's algorithm and delayed ACKs,
	 * every one waited ~80 ms over TCP (a USB link has no such delay) */
	{
		int one = 1;
		if (setsockopt(sockfd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one)) < 0)
			LOG_FD_ERRNO("setsockopt.TCP_NODELAY", sockfd, errno);
	}

	/* For testing purposes, set the not pollable flag for fd */
	memset(&ops, 0, sizeof(ops));
	ops.userdata = app;
	ops.fdeof = mux_server_fdeof;
	app->mux.sockfd = sockfd;
	app->mux.ctx = mux_new(app->mux.sockfd, app->loop, &ops,
			MUX_FLAG_FD_NOT_POLLABLE);

	backend_create(app);
	video_channels_open(app);
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
	addr.sin_addr.s_addr = app->mux.bind_addr;
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

/** The controller's mapped button actions on the Disco (sc2.c). */
static void sc2_button_action(void *userdata, int32_t action)
{
	struct app *app = userdata;
	struct arsdk_cmd cmd;
	switch (action) {
	case ARSDK_MAPPER_BUTTON_ACTION_RETURN_HOME:
		drone_button(app, 1);
		break;
	case ARSDK_MAPPER_BUTTON_ACTION_TAKEOFF_LAND:
		drone_button(app, 2);
		break;
	case ARSDK_MAPPER_BUTTON_ACTION_VIDEO_RECORD:
		disco_video_toggle(&app->disco);
		break;
	case ARSDK_MAPPER_BUTTON_ACTION_TAKE_PICTURE:
		disco_take_picture(&app->disco);
		break;
	case ARSDK_MAPPER_BUTTON_ACTION_CAMERA_EXPOSITION_INC:
		disco_exposure_step(&app->disco, 1);
		break;
	case ARSDK_MAPPER_BUTTON_ACTION_CAMERA_EXPOSITION_DEC:
		disco_exposure_step(&app->disco, -1);
		break;
	case ARSDK_MAPPER_BUTTON_ACTION_CENTER_CAMERA:
		disco_center_camera(&app->disco);
		break;
	case ARSDK_MAPPER_BUTTON_ACTION_EMERGENCY:
		arsdk_cmd_init(&cmd);
		if (arsdk_cmd_enc_Ardrone3_Piloting_Emergency(&cmd) >= 0)
			flight_recv_cmd(app, &cmd);
		arsdk_cmd_clear(&cmd);
		break;
	default:
		LOGI("controller action %d: nothing on a Disco", action);
		break;
	}
}

/** The controller's mapped axes: sticks and the camera slider (sc2.c). */
static void sc2_axis_action(void *userdata, int32_t action, int value)
{
	struct app *app = userdata;
	switch (action) {
	case ARSDK_MAPPER_AXIS_ACTION_ROLL:
		app->flight.stick_roll = value;
		break;
	case ARSDK_MAPPER_AXIS_ACTION_PITCH:
		app->flight.stick_pitch = value;
		break;
	case ARSDK_MAPPER_AXIS_ACTION_GAZ:
		app->flight.stick_gaz = value;
		break;
	case ARSDK_MAPPER_AXIS_ACTION_CAMERA_TILT:
		/* the slider moves the camera at up to its velocity range */
		disco_camera_velocity(&app->disco, (float)value / 100.0f * 20.0f,
				app->disco.cam_vel_pan);
		break;
	case ARSDK_MAPPER_AXIS_ACTION_CAMERA_PAN:
		disco_camera_velocity(&app->disco, app->disco.cam_vel_tilt,
				(float)value / 100.0f * 20.0f);
		break;
	default:
		/* yaw: a flying wing has no rudder */
		break;
	}
}

/**
 * Failures for robustness tests ("fail <name> [on|off]"): the GPS fix, the
 * camera stream, a motor cut-out (alert, then a gliding descent), and the
 * Disco's own (disco_fail).
 */
static int fail(struct app *app, const char *name, int on)
{
	struct telemetry *tm = &app->tm;
	int res = 0;
	if (strcmp(name, "gps") == 0) {
		if (on && !app->gps_failed) {
			app->gps_failed = 1;
			app->gps_saved[0] = tm->latitude;
			app->gps_saved[1] = tm->longitude;
			app->gps_saved[2] = tm->gps_altitude;
			tm->latitude = tm->longitude = tm->gps_altitude = 500.0;
			tm->gps_fixed = 0;
			tm->satellites = 0;
		} else if (!on && app->gps_failed) {
			app->gps_failed = 0;
			tm->latitude = app->gps_saved[0];
			tm->longitude = app->gps_saved[1];
			tm->gps_altitude = app->gps_saved[2];
			tm->gps_fixed = tm->latitude != 500.0;
			tm->satellites = tm->gps_fixed ? 10 : 0;
		}
		send_telemetry(app);
		return 0;
	}
	if (strcmp(name, "video") == 0) {
		app->video_failed = on;
		LOGI("video %s", on ? "lost" : "back");
		return 0;
	}
	if (strcmp(name, "cut_out") == 0) {
		disco_set_alert(&app->disco, on ? "cut_out" : "auto");
		if (!on)
			disco_battery(&app->disco, tm->battery);
		if (on && is_airborne(tm->flying_state) && !app->sim_external) {
			struct arsdk_cmd cmd;
			arsdk_cmd_init(&cmd);
			if (arsdk_cmd_enc_Ardrone3_Piloting_Landing(&cmd) >= 0)
				flight_recv_cmd(app, &cmd);
			arsdk_cmd_clear(&cmd);
		} else if (on && app->sim_external) {
			sim_event(app, "landing");
		}
		return 0;
	}
	res = disco_fail(&app->disco, name, on);
	return res;
}

/*
 * fake-sc2 control socket: up to CTRL_MAX_CLIENTS TCP clients on 127.0.0.1:<ctrl port>,
 * one command per line, answered "ok" or "error <reason>":
 *   battery <percent>            drone battery
 *   rc_battery <percent>         controller battery
 *   altitude <m>
 *   attitude <roll> <pitch> <yaw>   radians
 *   speed <vx> <vy> <vz>         m/s (NED)
 *   airspeed <m/s>
 *   gps <lat> <lon> <alt>        degrees, m; also sets the fix, and the
 *                                home position at the first fix
 *   home <lat> <lon> <alt>       home position
 *   gps_lost                     fix lost, position unknown (500)
 *   satellites <n>
 *   flying_state <landed|takingoff|hovering|flying|landing|emergency|
 *                 usertakeoff|motor_ramping|emergency_landing>
 *   send_all                     resend all telemetry
 *   throw                        hand launch after TakeOff / UserTakeOff
 *   sim external|internal        flight dynamics from an external simulator
 *                                (rig/sim/bridge: telemetry through this socket,
 *                                piloting commands queued) or the built-in one
 *   events                       "ok <queued piloting events>" (external sim):
 *                                takeoff usertakeoff landing emergency
 *                                home_start home_stop
 *   position <lat> <lon> <alt>   GPS position only (fix kept)
 *   rth <0|1>                    return home in progress or not
 *   circle                       straight to circling at the circling altitude
 *                                (around home if set), with the flight
 *                                simulation running (demos)
 *   sticks <roll> <pitch> <gaz>  the controller's sticks, percent (-100..100;
 *                                0 0 0 centred): pilot the simulated Disco
 *   pcmd                         "ok <on> <roll> <pitch> <gaz>": the sticks in
 *                                use (controller's, else the app's), as
 *                                flown (pitch reversed in inverted mode)
 *   alert <name|auto>            Disco alert: none user cut_out
 *                                low_battery critical_battery
 *                                almost_empty_battery too_much_angle; auto =
 *                                from the battery level
 *   disco                        "ok <settings and states>" (disco.c)
 *   settings_reset               the Disco's settings back to their
 *                                defaults (name kept)
 *   factory_country <CC|none>    the Disco's factory country: CA, IL and
 *                                US refuse a manual Wi-Fi country
 *   button <name>                press and release a controller button:
 *                                to the app if grabbed, else its mapped
 *                                action (sc2.c: rth, takeoff, record,
 *                                picture, exposure, app buttons)
 *   axis <name> <-100..100>      move a stick or slider (left_h left_v
 *                                right_h right_v left_slider right_slider):
 *                                to the app if grabbed, else its mapped
 *                                action (left_slider: camera tilt)
 *   sc2                          "ok <grab, mapping, axes, access point>"
 *   fail <name> [on|off]         inject or clear a failure: gps video
 *                                cut_out motor magneto pitot overheat
 *                                camera storage imu barometer ultrasound
 *                                vertical_camera link_weak
 *   unhandled [clear]            "ok <commands nothing handled>"
 *   state                        "ok <flying state> alt=<m> home=<0|1> pcmd=<n>
 *                                grabbed=<buttons> fp=<0|1> battery=<%>
 *                                dist=<m from home> heading=<deg>"
 *   drone_link <lost|ok>         Disco out of / back in the controller's
 *                                range (mux mode): drone disconnected for
 *                                the app, no video
 *   unplug <seconds>             close the phone's session and refuse a new
 *                                one for that long (mux mode)
 *   ping
 * Setting altitude, airspeed or flying_state stops the flight simulation
 * (flight_tick) until the app's next piloting command.
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
		app->flight.battery_used = 0;
		if (app->cmd_itf)
			SEND(res, arsdk_cmd_send_Common_CommonState_BatteryStateChanged(
				app->cmd_itf, &send_status, app, tm->battery));
		disco_battery(&app->disco, tm->battery);
	} else if (strcmp(word, "rc_battery") == 0 && n == 2) {
		tm->rc_battery = (uint8_t)a;
		send_rc_state(app);
	} else if (strcmp(word, "state") == 0 && n == 1) {
		/* distance to home from the telemetry (internal or external
		 * simulation), else from the internal one's local position */
		double hn, he, dist;
		if (tm->gps_fixed && tm->home_latitude != 500.0) {
			hn = (tm->latitude - tm->home_latitude) * EARTH_M_PER_DEG;
			he = (tm->longitude - tm->home_longitude) * EARTH_M_PER_DEG
				* cos(tm->home_latitude * M_PI / 180);
			dist = hypot(hn, he);
		} else {
			flight_home(app, &hn, &he);
			dist = hypot(app->flight.n - hn, app->flight.e - he);
		}
		return snprintf(reply, reply_size,
				"ok %s alt=%.1f home=%d pcmd=%lu grabbed=0x%x fp=%d "
				"battery=%u dist=%.0f heading=%.0f\n",
				flying_state_name(tm->flying_state), tm->altitude,
				app->flight.home, app->flight.pcmd,
				app->sc2.grabbed_buttons, app->flight.fp, tm->battery,
				dist, tm->yaw * 180 / M_PI);
	} else if (strcmp(word, "rc") == 0) {
		return rc_ctrl(&app->disco.rc, line, reply, reply_size);
	} else if (strcmp(word, "disco") == 0 && n == 1) {
		char buf[512];
		disco_status(&app->disco, buf, sizeof(buf));
		return snprintf(reply, reply_size, "ok %s\n", buf);
	} else if (strcmp(word, "settings_reset") == 0 && n == 1) {
		/* back to default-dragon.conf, name kept (tests) */
		char name[sizeof(app->disco.s.name)];
		snprintf(name, sizeof(name), "%s", app->disco.s.name);
		disco_reset_settings(&app->disco.s, name);
		if (app->disco.itf != NULL)
			disco_send_settings(&app->disco);
	} else if (strcmp(word, "factory_country") == 0) {
		char cc[8] = "";
		if (sscanf(line, "%*s %7s", cc) != 1
				|| (strlen(cc) != 2 && strcmp(cc, "none") != 0))
			return snprintf(reply, reply_size, "error factory_country <CC|none>\n");
		snprintf(app->disco.factory_country, sizeof(app->disco.factory_country),
				"%s", strcmp(cc, "none") == 0 ? "" : cc);
	} else if (strcmp(word, "alert") == 0) {
		char name[32] = "";
		if (sscanf(line, "%*s %31s", name) != 1
				|| disco_set_alert(&app->disco, name) < 0)
			return snprintf(reply, reply_size, "error alert <name|auto>\n");
		if (strcmp(name, "auto") == 0)
			disco_battery(&app->disco, tm->battery);
	} else if (strcmp(word, "sticks") == 0 && n == 4) {
		app->flight.stick_roll = (int)fmax(-100, fmin(100, a));
		app->flight.stick_pitch = (int)fmax(-100, fmin(100, b));
		app->flight.stick_gaz = (int)fmax(-100, fmin(100, c));
	} else if (strcmp(word, "pcmd") == 0 && n == 1) {
		double r, p, g;
		/* as flown: inverted pitch mode applied */
		int on = flight_sticks(app, &r, &p, &g);
		return snprintf(reply, reply_size, "ok %d %d %d %d\n", on,
				(int)lround(r * 100), (int)lround(p * 100),
				(int)lround(g * 100));
	} else if (strcmp(word, "drone_link") == 0) {
		char what[16] = "";
		if (app->backend_type != ARSDK_BACKEND_TYPE_MUX)
			return snprintf(reply, reply_size, "error no controller (mux mode only)\n");
		if (sscanf(line, "%*s %15s", what) != 1
				|| (strcmp(what, "lost") != 0 && strcmp(what, "ok") != 0))
			return snprintf(reply, reply_size, "error drone_link lost|ok\n");
		app->drone_link_lost = strcmp(what, "lost") == 0;
		LOGI("drone link %s", app->drone_link_lost ? "lost" : "back");
		send_drone_link(app);
	} else if (strcmp(word, "unplug") == 0 && n == 2) {
		if (app->backend_type != ARSDK_BACKEND_TYPE_MUX)
			return snprintf(reply, reply_size, "error no controller (mux mode only)\n");
		app->mux.unplugged_until_ms = now_ms() + (uint64_t)(a * 1000);
		LOGI("unplugged for %.1f s", a);
		if (app->mux.sockfd != -1) {
			shutdown(app->mux.sockfd, SHUT_RDWR);
			mux_server_fdeof(app->mux.ctx, app);
		}
	} else if (strcmp(word, "button") == 0) {
		char name[32] = "";
		int bit, grabbed;
		if (sscanf(line, "%*s %31s", name) != 1)
			return snprintf(reply, reply_size, "error button <name>\n");
		bit = sc2_button_bit(name);
		if (bit < 0)
			return snprintf(reply, reply_size, "error unknown button\n");
		/* a press still waiting for its release: release it first */
		if (app->button_timer != NULL && app->button_pending) {
			pomp_timer_clear(app->button_timer);
			button_release_cb(app->button_timer, app);
		}
		grabbed = app->cmd_itf != NULL && (app->sc2.grabbed_buttons & (1u << bit));
		if (sc2_button(&app->sc2, (uint32_t)bit, 1) < 0) {
			sc2_button(&app->sc2, (uint32_t)bit, 0);
			return snprintf(reply, reply_size,
					"error button %s not grabbed nor mapped\n", name);
		}
		app->pressed_button = (uint32_t)bit;
		app->button_pending = 1;
		if (app->button_timer != NULL)
			pomp_timer_set(app->button_timer, 150);
		if (!grabbed)
			return snprintf(reply, reply_size, "ok mapped action\n");
	} else if (strcmp(word, "axis") == 0) {
		char name[32] = "";
		int value = 0, idx;
		if (sscanf(line, "%*s %31s %d", name, &value) != 2)
			return snprintf(reply, reply_size, "error axis <name> <-100..100>\n");
		idx = sc2_axis_index(name);
		if (idx < 0)
			return snprintf(reply, reply_size, "error unknown axis\n");
		if (sc2_axis(&app->sc2, idx, value) < 0)
			return snprintf(reply, reply_size, "error axis %s not grabbed nor mapped\n", name);
	} else if (strcmp(word, "sc2") == 0 && n == 1) {
		char buf[900];
		sc2_status(&app->sc2, buf, sizeof(buf));
		return snprintf(reply, reply_size, "ok %s\n", buf);
	} else if (strcmp(word, "unhandled") == 0) {
		char what[16] = "";
		int len;
		if (sscanf(line, "%*s %15s", what) == 1 && strcmp(what, "clear") == 0) {
			app->unhandled[0] = '\0';
			return snprintf(reply, reply_size, "ok\n");
		}
		len = snprintf(reply, reply_size, "ok %s\n", app->unhandled);
		return len;
	} else if (strcmp(word, "fail") == 0) {
		char name[32] = "", what[8] = "on";
		int on;
		if (sscanf(line, "%*s %31s %7s", name, what) < 1)
			return snprintf(reply, reply_size, "error fail <name> [on|off]\n");
		on = strcmp(what, "off") != 0;
		res = fail(app, name, on);
		if (res < 0)
			return snprintf(reply, reply_size,
					"error unknown failure (gps video cut_out %s)\n",
					disco_fail_names());
		res = 0;
	} else if (strcmp(word, "sim") == 0) {
		char what[16] = "";
		if (sscanf(line, "%*s %15s", what) != 1
				|| (strcmp(what, "external") != 0 && strcmp(what, "internal") != 0))
			return snprintf(reply, reply_size, "error sim external|internal\n");
		app->sim_external = strcmp(what, "external") == 0;
		app->flight.active = 0;
		app->events[0] = '\0';
		LOGI("flight simulation %s", what);
	} else if (strcmp(word, "events") == 0 && n == 1) {
		int len = snprintf(reply, reply_size, "ok%s%s\n",
				app->events[0] ? " " : "", app->events);
		app->events[0] = '\0';
		return len;
	} else if (strcmp(word, "position") == 0 && n == 4) {
		/* the fix state is acknowledged: sent on change only, as the
		 * Disco does (at the bridge's rate it would queue acknowledgements
		 * faster than a slow link turns them round) */
		int was_fixed = tm->gps_fixed;
		tm->latitude = a; tm->longitude = b; tm->gps_altitude = c;
		tm->gps_fixed = 1;
		disco_gps_fix(&app->disco, 1);
		if (app->cmd_itf) {
			SEND(res, arsdk_cmd_send_Ardrone3_PilotingState_PositionChanged(
				app->cmd_itf, &send_status, app,
				tm->latitude, tm->longitude, tm->gps_altitude));
			if (!was_fixed)
				SEND(res, arsdk_cmd_send_Ardrone3_GPSSettingsState_GPSFixStateChanged(
					app->cmd_itf, &send_status, app, 1));
		}
	} else if (strcmp(word, "rth") == 0 && n == 2) {
		int on = a != 0;
		if (on != app->flight.home) {
			app->flight.home = on;
			send_home_state(app, on ?
				ARSDK_ARDRONE3_PILOTINGSTATE_NAVIGATEHOMESTATECHANGED_REASON_USERREQUEST :
				ARSDK_ARDRONE3_PILOTINGSTATE_NAVIGATEHOMESTATECHANGED_REASON_FINISHED);
		}
	} else if (strcmp(word, "circle") == 0 && n == 1) {
		double hn, he;
		app->flight.active = 1;
		flight_set_motion(app, app->disco.s.circling_altitude, FLIGHT_AIRSPEED);
		if (flight_origin(app) && tm->home_latitude != 500.0) {
			flight_home(app, &hn, &he);
			app->flight.n = hn + FLIGHT_CIRCLE_RADIUS;
			app->flight.e = he;
			flight_loiter_around(app, hn, he,
					app->disco.s.circling_direction != 0);
		} else {
			flight_loiter_here(app, app->disco.s.circling_direction != 0);
		}
		flight_set_state(app, FS(HOVERING));
	} else if (strcmp(word, "throw") == 0 && n == 1) {
		if (flight_throw(app) < 0)
			return snprintf(reply, reply_size, "error not ready for launch (%s)\n",
					flying_state_name(tm->flying_state));
	} else if (strcmp(word, "altitude") == 0 && n == 2) {
		app->flight.active = 0;
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
		app->flight.active = 0;
		tm->airspeed = (float)a;
		if (app->cmd_itf)
			SEND(res, arsdk_cmd_send_Ardrone3_PilotingState_AirSpeedChanged(
				app->cmd_itf, &send_status, app, tm->airspeed));
	} else if (strcmp(word, "gps") == 0 && n == 4) {
		tm->latitude = a; tm->longitude = b; tm->gps_altitude = c;
		tm->gps_fixed = 1;
		if (tm->satellites == 0)
			tm->satellites = 10;
		if (tm->home_latitude == 500.0) {
			tm->home_latitude = a; tm->home_longitude = b;
			tm->home_altitude = c;
		}
		send_telemetry(app);
	} else if (strcmp(word, "home") == 0 && n == 4) {
		tm->home_latitude = a; tm->home_longitude = b; tm->home_altitude = c;
		if (tm->flying_state == FS(HOVERING) && !app->flight.fp) {
			/* circle around the new home */
			double hn, he;
			flight_home(app, &hn, &he);
			flight_loiter_around(app, hn, he, app->flight.ccw);
		}
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
		size_t i;
		if (sscanf(line, "%*s %31s", state) != 1)
			return snprintf(reply, reply_size, "error missing state\n");
		for (i = 0; i < FLYING_STATES_COUNT; i++) {
			if (strcmp(state, flying_states[i].name) == 0)
				break;
		}
		if (i == FLYING_STATES_COUNT)
			return snprintf(reply, reply_size, "error unknown state\n");
		app->flight.active = 0;
		tm->flying_state = flying_states[i].value;
		rc_landed(&app->disco.rc, tm->flying_state ==
				ARSDK_ARDRONE3_PILOTINGSTATE_FLYINGSTATECHANGED_STATE_LANDED);
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

static struct ctrl_client *ctrl_client_of(struct app *app, int fd)
{
	int i;
	for (i = 0; i < CTRL_MAX_CLIENTS; i++) {
		if (app->ctrl.clients[i].fd == fd)
			return &app->ctrl.clients[i];
	}
	return NULL;
}

static void ctrl_client_close(struct app *app, struct ctrl_client *c)
{
	if (c == NULL || c->fd < 0)
		return;
	pomp_loop_remove(app->loop, c->fd);
	close(c->fd);
	c->fd = -1;
	c->len = 0;
}

static void ctrl_client_cb(int fd, uint32_t revents, void *userdata)
{
	struct app *app = userdata;
	struct ctrl_client *c = ctrl_client_of(app, fd);
	char reply[4608];
	char *nl = NULL;
	ssize_t r;
	if (c == NULL)
		return;
	r = read(fd, c->buf + c->len, sizeof(c->buf) - 1 - c->len);
	if (r <= 0) {
		ctrl_client_close(app, c);
		return;
	}
	c->len += (size_t)r;
	c->buf[c->len] = '\0';
	while ((nl = strchr(c->buf, '\n')) != NULL) {
		int len;
		*nl = '\0';
		if (nl > c->buf && nl[-1] == '\r')
			nl[-1] = '\0';
		LOGD("ctrl: %s", c->buf);
		len = ctrl_handle_line(app, c->buf, reply, sizeof(reply));
		if (len > 0 && write(fd, reply, (size_t)len) < 0)
			LOG_FD_ERRNO("write", fd, errno);
		c->len -= (size_t)(nl + 1 - c->buf);
		memmove(c->buf, nl + 1, c->len + 1);
	}
	if (c->len >= sizeof(c->buf) - 1) {
		LOGW("ctrl: line too long, dropping");
		c->len = 0;
	}
}

static void ctrl_server_cb(int fd, uint32_t revents, void *userdata)
{
	struct app *app = userdata;
	struct ctrl_client *c;
	int res;
	int client = accept(fd, NULL, NULL);
	if (client < 0) {
		LOG_FD_ERRNO("accept", fd, errno);
		return;
	}
	/* several clients (tests and the simulator bridge); when all slots
	 * are taken, the oldest connection gives way */
	c = ctrl_client_of(app, -1);
	if (c == NULL) {
		c = &app->ctrl.clients[0];
		ctrl_client_close(app, c);
	}
	c->fd = client;
	c->len = 0;
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
	int i;
	for (i = 0; i < CTRL_MAX_CLIENTS; i++)
		ctrl_client_close(app, &app->ctrl.clients[i]);
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
		"  --mux-bind <addr>  : address it listens on (default 127.0.0.1; 0.0.0.0\n"
		"                       for a phone over Wi-Fi: anyone on the network can then\n"
		"                       take the session over)\n"
		"  --disco            : be a Disco over Wi-Fi (net backend, TCP 44444)\n"
		"                       instead of a SkyController 2 over mux\n"
		"  --ctrl-port <port> : TCP port of the control socket on 127.0.0.1 (default 4322)\n"
		"  --video-port <port>: UDP port receiving RTP on 127.0.0.1, RTCP on port+1 (default 55004)\n"
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
	s_app.mux.bind_addr = htonl(INADDR_LOOPBACK);
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
		} else if (strcmp(argv[argidx], "--disco") == 0) {
			s_app.disco_mode = 1;
			s_app.backend_type = ARSDK_BACKEND_TYPE_NET;
			s_app.use_publisher_avahi = 0;
			s_app.use_publisher_net = 0;
		} else if (strcmp(argv[argidx], "--mux-bind") == 0) {
			if (argidx + 1 >= argc
					|| inet_pton(AF_INET, argv[argidx + 1], &s_app.mux.bind_addr) != 1) {
				fprintf(stderr, "Missing or bad mux bind address\n");
				usage(argv[0]);
				return -1;
			}
			argidx++;
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
		} else if (strcmp(argv[argidx], "--video-port") == 0) {
			if (argidx + 1 >= argc) {
				fprintf(stderr, "Missing video port\n");
				usage(argv[0]);
				return -1;
			}
			s_app.video.port = (uint16_t)atoi(argv[++argidx]);
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

	/* fake-sc2: control socket and video input */
	ctrl_server_create(&s_app);
	video_udp_create(&s_app);
	s_app.button_timer = pomp_timer_new(s_app.loop, &button_release_cb, &s_app);
	disco_init(&s_app.disco, s_app.loop, FAKE_DRONE_NAME);
	{
		struct sc2_ops ops = {
			.button_action = &sc2_button_action,
			.axis_action = &sc2_axis_action,
			.userdata = &s_app,
		};
		sc2_init(&s_app.sc2, FAKE_DRONE_PRODUCT, "SkyController_fake", &ops);
	}
	s_app.flight.reboot_timer = pomp_timer_new(s_app.loop, &reboot_done_cb, &s_app);
	s_app.flight.wifi_timer = pomp_timer_new(s_app.loop, &wifi_restart_cb, &s_app);
	s_app.flight.timer = pomp_timer_new(s_app.loop, &flight_tick, &s_app);
	if (s_app.flight.timer != NULL)
		pomp_timer_set_periodic(s_app.flight.timer,
				FLIGHT_TICK_MS, FLIGHT_TICK_MS);

	/* SkyController mode: video on from the start, like the real one */
	if (s_app.backend_type == ARSDK_BACKEND_TYPE_MUX)
		s_app.video.enabled = 1;

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
	video_udp_destroy(&s_app);
	if (s_app.button_timer != NULL) {
		pomp_timer_clear(s_app.button_timer);
		pomp_timer_destroy(s_app.button_timer);
	}
	if (s_app.flight.timer != NULL) {
		pomp_timer_clear(s_app.flight.timer);
		pomp_timer_destroy(s_app.flight.timer);
		s_app.flight.timer = NULL;
	}
	if (s_app.flight.wifi_timer != NULL) {
		pomp_timer_clear(s_app.flight.wifi_timer);
		pomp_timer_destroy(s_app.flight.wifi_timer);
		s_app.flight.wifi_timer = NULL;
	}
	if (s_app.flight.reboot_timer != NULL) {
		pomp_timer_clear(s_app.flight.reboot_timer);
		pomp_timer_destroy(s_app.flight.reboot_timer);
		s_app.flight.reboot_timer = NULL;
	}
	disco_clear(&s_app.disco);

	if (s_app.loop != NULL) {
		res = pomp_loop_destroy(s_app.loop);
		if (res < 0)
			LOG_ERRNO("pomp_loop_destroy", -res);
		s_app.loop = NULL;
	}

	return 0;
}
