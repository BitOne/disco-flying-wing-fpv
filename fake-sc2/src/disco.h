/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 *
 * fake-sc2: the Disco's own settings and states, beyond the flight itself.
 *
 * Modelled on the Disco firmware 1.7.1 (disco_update.plf): the commands its
 * flight program (dragon-prog) decodes and the events it generates, with the
 * defaults of its etc/default-dragon.conf. See NOTES.md, "Disco firmware vs
 * the fake".
 */
#ifndef FAKE_DISCO_H
#define FAKE_DISCO_H

#include <stdint.h>
#include <libpomp.h>
#include "arsdk/arsdk.h"

/* Settings: kept across connections, like the drone's config file. */
struct disco_settings {
	char     name[33];          /* product name, also the Wi-Fi SSID */
	char     country[3];
	uint8_t  auto_country;
	uint8_t  outdoor;
	/* geofence and piloting */
	float    max_altitude, min_altitude, max_distance;
	uint8_t  no_fly_over_max_distance;
	int32_t  circling_direction;   /* 0 CW, 1 CCW */
	uint16_t circling_altitude;    /* m */
	int32_t  pitch_mode;           /* 0 normal, 1 inverted */
	/* return home */
	int32_t  home_type;            /* 0 take-off, 1 pilot */
	uint16_t return_home_delay;    /* s */
	uint8_t  rth_on_disconnect;    /* flight plan: return home on disconnect */
	/* camera */
	int32_t  picture_format, white_balance;
	float    exposure, saturation;
	uint8_t  timelapse;
	float    timelapse_interval;
	uint8_t  video_autorecord;
	int32_t  video_stabilization, video_recording_mode;
	int32_t  video_framerate, video_resolutions;
	float    camera_tilt, camera_pan;  /* digital camera orientation, deg */
	/* Wi-Fi */
	int32_t  wifi_type, wifi_band;
	uint8_t  wifi_channel;
	int32_t  wifi_security;
	char     wifi_key[65];
};

struct disco {
	struct pomp_loop      *loop;
	struct arsdk_cmd_itf  *itf;     /* NULL when no app is connected */
	struct disco_settings s;

	/* media */
	int        recording;           /* video recording in progress */
	uint64_t   recording_start_ms;
	uint16_t   photos, videos;
	int        picture_busy;
	struct pomp_timer *picture_timer;

	/* flight meter, counted by disco_flying_state() */
	uint16_t   flights;
	uint16_t   last_flight_s;
	uint32_t   total_flight_s;
	uint64_t   takeoff_ms;          /* 0 when on the ground */
	char       run_id[33];

	/* calibrations */
	int        magneto_step;        /* 0 idle, 1..3 axis being calibrated */
	int        pitot_calibrating;
	struct pomp_timer *calib_timer;

	/* alerts */
	int32_t    alert;               /* ARSDK_ARDRONE3_PILOTINGSTATE_ALERTSTATECHANGED_STATE_* */
	int        alert_forced;        /* set from the control socket */

	/* pilot position (controller GPS), for the pilot home type */
	double     pilot_lat, pilot_lon;  /* 500 = unknown */

	/* flight plan (Mavlink feature): playing state only, no file transfer */
	int32_t    mavlink_state;       /* ARSDK_COMMON_MAVLINKSTATE_MAVLINKFILEPLAYINGSTATECHANGED_STATE_* */
	char       mavlink_file[128];
	int        fp_request;          /* DISCO_FP_*: for the fake's flight, reset by it */

	uint8_t    is_piloting;
	uint8_t    gps_fixed;

	/* camera orientation moving at a velocity (the controller's slider,
	 * Camera.Velocity), deg/s */
	float      cam_vel_tilt, cam_vel_pan;
	struct pomp_timer *cam_timer;

	/* injected failures (disco_fail), DISCO_FAIL_* bits */
	uint32_t   failures;
};

/* Failures of the Disco itself (the control socket's "fail"). */
enum {
	DISCO_FAIL_MOTOR        = 1 << 0,  /* motor error (stalled) */
	DISCO_FAIL_MAGNETO      = 1 << 1,  /* magnetometer needs calibrating */
	DISCO_FAIL_PITOT        = 1 << 2,  /* pitot needs calibrating */
	DISCO_FAIL_OVERHEAT     = 1 << 3,
	DISCO_FAIL_CAMERA       = 1 << 4,  /* camera KO: no picture, video */
	DISCO_FAIL_STORAGE      = 1 << 5,  /* internal memory full */
	DISCO_FAIL_IMU          = 1 << 6,  /* sensors reported KO */
	DISCO_FAIL_BAROMETER    = 1 << 7,
	DISCO_FAIL_ULTRASOUND   = 1 << 8,
	DISCO_FAIL_VERTICAL_CAMERA = 1 << 9,
	DISCO_FAIL_LINK_WEAK    = 1 << 10, /* weak Wi-Fi signal */
};

enum { DISCO_FP_NONE, DISCO_FP_START, DISCO_FP_PAUSE, DISCO_FP_STOP };

void disco_init(struct disco *d, struct pomp_loop *loop, const char *name);
void disco_clear(struct disco *d);
void disco_reset_settings(struct disco_settings *s, const char *name);

/* Connection: the drone's answers to AllSettings and AllStates, sent
 * before AllSettingsChanged / AllStatesChanged. */
void disco_send_settings(struct disco *d);
void disco_send_states(struct disco *d);

/* A command from the app; returns 1 if it is one of the Disco's own. */
int disco_recv_cmd(struct disco *d, const struct arsdk_cmd *cmd);

/* Events from the flight: flying state changes (auto record, flight meter),
 * battery level (alerts), GPS fix (home type availability). */
void disco_flying_state(struct disco *d, int32_t state);
void disco_battery(struct disco *d, uint8_t battery);
void disco_gps_fix(struct disco *d, uint8_t fixed);

/* The controller's mapped buttons and slider (sc2.c). */
void disco_video_toggle(struct disco *d);
void disco_take_picture(struct disco *d);
void disco_exposure_step(struct disco *d, int step);
void disco_center_camera(struct disco *d);
void disco_camera_velocity(struct disco *d, float tilt, float pan);

/* Injects or clears a failure by name (motor, magneto, pitot, overheat,
 * camera, storage, imu, barometer, ultrasound, vertical_camera, link_weak);
 * -EINVAL for an unknown name. */
int disco_fail(struct disco *d, const char *name, int on);
const char *disco_fail_names(void);

/* The flight plan ended (landed, or stopped by another command). */
void disco_flight_plan_stopped(struct disco *d);

/* Control socket helpers. */
int disco_set_alert(struct disco *d, const char *name);
int disco_status(const struct disco *d, char *buf, size_t len);

#endif /* FAKE_DISCO_H */
