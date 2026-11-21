/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 *
 * fake-sc2: the Disco's own settings and states (see disco.h).
 *
 * Defaults come from the firmware's etc/default-dragon.conf. Ranges the
 * firmware computes at run time (its flight program only logs "cannot be set
 * to %f. Max value is %f.") are not in any file we have: the ones below
 * marked "range: assumed" are plausible values to check on a real Disco.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <time.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>
#include <dirent.h>

#include "disco.h"

#define ULOG_TAG disco
#include "ulog.h"
ULOG_DECLARE_TAG(disco);

#define LOGI(_fmt, ...)	ULOGI(_fmt, ##__VA_ARGS__)
#define LOGW(_fmt, ...)	ULOGW(_fmt, ##__VA_ARGS__)

/* Every send goes through the interface's own status callback (NULL). */
#define SEND(_call) do { \
		int _res = (_call); \
		if (_res < 0) \
			LOGW("%s: %s", #_call, strerror(-_res)); \
	} while (0)
#define ITF d->itf, NULL, NULL

/* range: assumed (see the file comment) */
#define MAX_ALTITUDE_MIN       25.0f
#define MAX_ALTITUDE_MAX      150.0f
#define MIN_ALTITUDE_MIN        5.0f
#define MIN_ALTITUDE_MAX       50.0f
#define MAX_DISTANCE_MIN       10.0f
#define MAX_DISTANCE_MAX     2000.0f
#define CIRCLING_ALTITUDE_MIN    25
#define CIRCLING_ALTITUDE_MAX   150
#define EXPOSURE_MIN           -3.0f
#define EXPOSURE_MAX            3.0f
#define SATURATION_MIN       -100.0f
#define SATURATION_MAX        100.0f
#define TIMELAPSE_MIN           1.0f
#define TIMELAPSE_MAX         300.0f
#define CAMERA_TILT_MIN       -80.0f
#define CAMERA_TILT_MAX        80.0f
#define CAMERA_PAN_MIN        -80.0f
#define CAMERA_PAN_MAX         80.0f
#define CAMERA_FOV             80.0f
/* default-dragon.conf: max_camera_velocity_tilt / _pan */
#define CAMERA_VELOCITY        20.0f

/* Internal flash of the Disco: 32 GB, reported in MB. */
#define STORAGE_SIZE_MB       29824
#define STORAGE_ID                0

#define PICTURE_MS              500  /* taking a picture */
#define MAGNETO_AXIS_MS        1500  /* each axis of a magnetometer calibration */
#define PITOT_MS               3000  /* pitot calibration */

/* Identity: fake values, in the formats a Disco reports. */
#define SERIAL_HIGH       "PI0404"
#define SERIAL_LOW        "16FAKE0001"
#define CPU_ID            "fake00c0ffee0123"
/* GroundSdk parses "major.minor.type.count" (AnafiMotors) and crashes on
 * fewer fields; the Disco has one motor */
#define MOTOR_SOFTWARE    "1.0.0.1"
#define GPS_SOFTWARE      "UBX-M8 3.01"
#define GPS_HARDWARE      "00080000"
#define ARCOMMANDS_VERSION "3.12.6"

static float clampf(float v, float lo, float hi)
{
	return v < lo ? lo : v > hi ? hi : v;
}

static uint64_t now_ms(void)
{
	struct timespec ts;
	clock_gettime(CLOCK_MONOTONIC, &ts);
	return (uint64_t)ts.tv_sec * 1000 + (uint64_t)ts.tv_nsec / 1000000;
}

static uint64_t epoch_ms(void)
{
	struct timespec ts;
	clock_gettime(CLOCK_REALTIME, &ts);
	return (uint64_t)ts.tv_sec * 1000 + (uint64_t)ts.tv_nsec / 1000000;
}

void disco_reset_settings(struct disco_settings *s, const char *name)
{
	memset(s, 0, sizeof(*s));
	snprintf(s->name, sizeof(s->name), "%s", name);
	/* default-dragon.conf "network" */
	snprintf(s->country, sizeof(s->country), "US");
	s->auto_country = 1;
	/* default-dragon.conf "ARDrone3" */
	s->max_altitude = 150.0f;
	s->min_altitude = 5.0f;
	s->max_distance = 2000.0f;
	s->no_fly_over_max_distance = 1;
	s->circling_direction = ARSDK_ARDRONE3_PILOTINGSETTINGSSTATE_CIRCLINGDIRECTIONCHANGED_VALUE_CW;
	s->circling_altitude = 50;
	s->pitch_mode = ARSDK_ARDRONE3_PILOTINGSETTINGSSTATE_PITCHMODECHANGED_VALUE_NORMAL;
	s->wifi_type = ARSDK_ARDRONE3_NETWORKSETTINGSSTATE_WIFISELECTIONCHANGED_TYPE_AUTO_2_4GHZ;
	s->wifi_band = ARSDK_ARDRONE3_NETWORKSETTINGSSTATE_WIFISELECTIONCHANGED_BAND_2_4GHZ;
	s->wifi_channel = 1;
	s->wifi_security = ARSDK_ARDRONE3_NETWORKSETTINGSSTATE_WIFISECURITYCHANGED_TYPE_OPEN;
	s->white_balance = ARSDK_ARDRONE3_PICTURESETTINGSSTATE_AUTOWHITEBALANCECHANGED_TYPE_AUTO;
	s->exposure = 0.0f;
	s->saturation = 0.0f;
	s->picture_format = ARSDK_ARDRONE3_PICTURESETTINGSSTATE_PICTUREFORMATCHANGED_TYPE_SNAPSHOT;
	s->timelapse = 0;
	s->timelapse_interval = 1.0f;
	s->video_autorecord = 1;
	s->video_stabilization = ARSDK_ARDRONE3_PICTURESETTINGSSTATE_VIDEOSTABILIZATIONMODECHANGED_MODE_NONE;
	s->video_recording_mode = ARSDK_ARDRONE3_PICTURESETTINGSSTATE_VIDEORECORDINGMODECHANGED_MODE_QUALITY;
	s->video_framerate = ARSDK_ARDRONE3_PICTURESETTINGSSTATE_VIDEOFRAMERATECHANGED_FRAMERATE_30_FPS;
	s->video_resolutions = ARSDK_ARDRONE3_PICTURESETTINGSSTATE_VIDEORESOLUTIONSCHANGED_TYPE_REC1080_STREAM480;
	s->outdoor = 1;            /* wifi_settings_outdoor */
	s->home_type = ARSDK_ARDRONE3_GPSSETTINGSSTATE_HOMETYPECHANGED_TYPE_TAKEOFF;
	s->return_home_delay = 10;
	s->rth_on_disconnect = 0; /* fp_rth_on_disconnect */
}

/* ------------------------------------------------------------------ */
/* Sending                                                             */
/* ------------------------------------------------------------------ */

static void count_media(struct disco *d);

static void send_storage(struct disco *d)
{
	uint32_t used;
	count_media(d);
	used = 120 + d->videos * 350u + d->photos * 4u;
	SEND(arsdk_cmd_send_Common_CommonState_MassStorageStateListChanged(ITF,
			STORAGE_ID, "internal_000"));
	SEND(arsdk_cmd_send_Common_CommonState_MassStorageInfoStateListChanged(ITF,
			STORAGE_ID, STORAGE_SIZE_MB,
			(d->failures & DISCO_FAIL_STORAGE) ? STORAGE_SIZE_MB : used, 1,
			used >= STORAGE_SIZE_MB || (d->failures & DISCO_FAIL_STORAGE), 1));
	SEND(arsdk_cmd_send_Common_CommonState_MassStorageContent(ITF,
			STORAGE_ID, d->photos, d->videos, d->flights, 0, 0));
	SEND(arsdk_cmd_send_Common_CommonState_MassStorageContentForCurrentRun(ITF,
			STORAGE_ID, d->photos, d->videos, 0));
}

static void send_max_altitude(struct disco *d)
{
	SEND(arsdk_cmd_send_Ardrone3_PilotingSettingsState_MaxAltitudeChanged(ITF,
			d->s.max_altitude, MAX_ALTITUDE_MIN, MAX_ALTITUDE_MAX));
}

static void send_min_altitude(struct disco *d)
{
	SEND(arsdk_cmd_send_Ardrone3_PilotingSettingsState_MinAltitudeChanged(ITF,
			d->s.min_altitude, MIN_ALTITUDE_MIN, MIN_ALTITUDE_MAX));
}

static void send_circling_altitude(struct disco *d)
{
	SEND(arsdk_cmd_send_Ardrone3_PilotingSettingsState_CirclingAltitudeChanged(ITF,
			d->s.circling_altitude, CIRCLING_ALTITUDE_MIN, CIRCLING_ALTITUDE_MAX));
}

static void send_home_types(struct disco *d)
{
	int pilot = d->pilot_lat != 500.0;
	int32_t chosen;
	SEND(arsdk_cmd_send_Ardrone3_GPSState_HomeTypeAvailabilityChanged(ITF,
			ARSDK_ARDRONE3_GPSSTATE_HOMETYPEAVAILABILITYCHANGED_TYPE_TAKEOFF,
			d->gps_fixed));
	SEND(arsdk_cmd_send_Ardrone3_GPSState_HomeTypeAvailabilityChanged(ITF,
			ARSDK_ARDRONE3_GPSSTATE_HOMETYPEAVAILABILITYCHANGED_TYPE_PILOT,
			pilot));
	SEND(arsdk_cmd_send_Ardrone3_GPSState_HomeTypeAvailabilityChanged(ITF,
			ARSDK_ARDRONE3_GPSSTATE_HOMETYPEAVAILABILITYCHANGED_TYPE_FIRST_FIX,
			d->gps_fixed));
	if (d->s.home_type == ARSDK_ARDRONE3_GPSSETTINGSSTATE_HOMETYPECHANGED_TYPE_PILOT
			&& pilot)
		chosen = ARSDK_ARDRONE3_GPSSTATE_HOMETYPECHOSENCHANGED_TYPE_PILOT;
	else
		chosen = ARSDK_ARDRONE3_GPSSTATE_HOMETYPECHOSENCHANGED_TYPE_TAKEOFF;
	SEND(arsdk_cmd_send_Ardrone3_GPSState_HomeTypeChosenChanged(ITF, chosen));
}

static void send_camera(struct disco *d)
{
	SEND(arsdk_cmd_send_Ardrone3_CameraState_Orientation(ITF,
			(int8_t)d->s.camera_tilt, (int8_t)d->s.camera_pan));
	SEND(arsdk_cmd_send_Ardrone3_CameraState_OrientationV2(ITF,
			d->s.camera_tilt, d->s.camera_pan));
}

static void send_wifi_selection(struct disco *d)
{
	SEND(arsdk_cmd_send_Ardrone3_NetworkSettingsState_WifiSelectionChanged(ITF,
			d->s.wifi_type, d->s.wifi_band, d->s.wifi_channel));
}

static void send_wifi_security(struct disco *d)
{
	SEND(arsdk_cmd_send_Ardrone3_NetworkSettingsState_WifiSecurityChanged(ITF,
			d->s.wifi_security));
	SEND(arsdk_cmd_send_Ardrone3_NetworkSettingsState_WifiSecurity(ITF,
			d->s.wifi_security, d->s.wifi_key,
			ARSDK_ARDRONE3_NETWORKSETTINGSSTATE_WIFISECURITY_KEYTYPE_PLAIN));
}

static void send_flight_meter(struct disco *d)
{
	SEND(arsdk_cmd_send_Ardrone3_SettingsState_MotorFlightsStatusChanged(ITF,
			d->flights, d->last_flight_s, d->total_flight_s));
}

static void send_flight_plan(struct disco *d)
{
	int calibrated = d->magneto_step == 0 && !(d->failures & DISCO_FAIL_MAGNETO);
	SEND(arsdk_cmd_send_Common_FlightPlanState_AvailabilityStateChanged(ITF,
			d->gps_fixed && calibrated));
	SEND(arsdk_cmd_send_Common_FlightPlanState_ComponentStateListChanged(ITF,
			ARSDK_COMMON_FLIGHTPLANSTATE_COMPONENTSTATELISTCHANGED_COMPONENT_GPS,
			d->gps_fixed));
	SEND(arsdk_cmd_send_Common_FlightPlanState_ComponentStateListChanged(ITF,
			ARSDK_COMMON_FLIGHTPLANSTATE_COMPONENTSTATELISTCHANGED_COMPONENT_CALIBRATION,
			calibrated));
	SEND(arsdk_cmd_send_Common_FlightPlanState_ComponentStateListChanged(ITF,
			ARSDK_COMMON_FLIGHTPLANSTATE_COMPONENTSTATELISTCHANGED_COMPONENT_MAVLINK_FILE,
			1));
	SEND(arsdk_cmd_send_Common_FlightPlanState_ComponentStateListChanged(ITF,
			ARSDK_COMMON_FLIGHTPLANSTATE_COMPONENTSTATELISTCHANGED_COMPONENT_TAKEOFF,
			1));
	SEND(arsdk_cmd_send_Common_FlightPlanState_ComponentStateListChanged(ITF,
			ARSDK_COMMON_FLIGHTPLANSTATE_COMPONENTSTATELISTCHANGED_COMPONENT_WAYPOINTSBEYONDGEOFENCE,
			1));
	SEND(arsdk_cmd_send_Common_FlightPlanState_ComponentStateListChanged(ITF,
			ARSDK_COMMON_FLIGHTPLANSTATE_COMPONENTSTATELISTCHANGED_COMPONENT_CAMERAAVAILABLE,
			1));
	SEND(arsdk_cmd_send_Common_FlightPlanState_LockStateChanged(ITF, 0));
}

static void send_mavlink_state(struct disco *d)
{
	SEND(arsdk_cmd_send_Common_MavlinkState_MavlinkFilePlayingStateChanged(ITF,
			d->mavlink_state, d->mavlink_file,
			ARSDK_COMMON_MAVLINKSTATE_MAVLINKFILEPLAYINGSTATECHANGED_TYPE_FLIGHTPLAN));
}

static void send_sensors(struct disco *d)
{
	static const struct { int32_t name; uint32_t fail; } sensors[] = {
		{ ARSDK_COMMON_COMMONSTATE_SENSORSSTATESLISTCHANGED_SENSORNAME_IMU, DISCO_FAIL_IMU },
		{ ARSDK_COMMON_COMMONSTATE_SENSORSSTATESLISTCHANGED_SENSORNAME_BAROMETER, DISCO_FAIL_BAROMETER },
		{ ARSDK_COMMON_COMMONSTATE_SENSORSSTATESLISTCHANGED_SENSORNAME_ULTRASOUND, DISCO_FAIL_ULTRASOUND },
		{ ARSDK_COMMON_COMMONSTATE_SENSORSSTATESLISTCHANGED_SENSORNAME_GPS, 0 },
		{ ARSDK_COMMON_COMMONSTATE_SENSORSSTATESLISTCHANGED_SENSORNAME_MAGNETOMETER, 0 },
		{ ARSDK_COMMON_COMMONSTATE_SENSORSSTATESLISTCHANGED_SENSORNAME_VERTICAL_CAMERA, DISCO_FAIL_VERTICAL_CAMERA },
	};
	size_t i;
	for (i = 0; i < sizeof(sensors) / sizeof(sensors[0]); i++)
		SEND(arsdk_cmd_send_Common_CommonState_SensorsStatesListChanged(ITF,
				sensors[i].name, (d->failures & sensors[i].fail) ? 0 : 1));
}

static void send_media_states(struct disco *d)
{
	int32_t err = (d->failures & DISCO_FAIL_CAMERA) ?
		ARSDK_ARDRONE3_MEDIARECORDSTATE_PICTURESTATECHANGEDV2_ERROR_CAMERA_KO :
		(d->failures & DISCO_FAIL_STORAGE) ?
		ARSDK_ARDRONE3_MEDIARECORDSTATE_PICTURESTATECHANGEDV2_ERROR_MEMORYFULL :
		ARSDK_ARDRONE3_MEDIARECORDSTATE_PICTURESTATECHANGEDV2_ERROR_OK;
	int ko = err != ARSDK_ARDRONE3_MEDIARECORDSTATE_PICTURESTATECHANGEDV2_ERROR_OK;
	SEND(arsdk_cmd_send_Ardrone3_MediaRecordState_PictureStateChangedV2(ITF,
			ko ? ARSDK_ARDRONE3_MEDIARECORDSTATE_PICTURESTATECHANGEDV2_STATE_NOTAVAILABLE :
			d->picture_busy ?
			ARSDK_ARDRONE3_MEDIARECORDSTATE_PICTURESTATECHANGEDV2_STATE_BUSY :
			ARSDK_ARDRONE3_MEDIARECORDSTATE_PICTURESTATECHANGEDV2_STATE_READY, err));
	/* the video state's error values are the same */
	SEND(arsdk_cmd_send_Ardrone3_MediaRecordState_VideoStateChangedV2(ITF,
			ko ? ARSDK_ARDRONE3_MEDIARECORDSTATE_VIDEOSTATECHANGEDV2_STATE_NOTAVAILABLE :
			d->recording ?
			ARSDK_ARDRONE3_MEDIARECORDSTATE_VIDEOSTATECHANGEDV2_STATE_STARTED :
			ARSDK_ARDRONE3_MEDIARECORDSTATE_VIDEOSTATECHANGEDV2_STATE_STOPPED, err));
}

static void send_pitot(struct disco *d)
{
	SEND(arsdk_cmd_send_Common_CalibrationState_PitotCalibrationStateChanged(ITF,
			d->pitot_calibrating ?
			ARSDK_COMMON_CALIBRATIONSTATE_PITOTCALIBRATIONSTATECHANGED_STATE_IN_PROGRESS :
			(d->failures & DISCO_FAIL_PITOT) ?
			ARSDK_COMMON_CALIBRATIONSTATE_PITOTCALIBRATIONSTATECHANGED_STATE_REQUIRED :
			ARSDK_COMMON_CALIBRATIONSTATE_PITOTCALIBRATIONSTATECHANGED_STATE_DONE,
			(d->failures & DISCO_FAIL_PITOT) ? 1 : 0));
}

static void send_motor(struct disco *d)
{
	int ko = (d->failures & DISCO_FAIL_MOTOR) != 0;
	SEND(arsdk_cmd_send_Ardrone3_SettingsState_MotorErrorStateChanged(ITF,
			ko ? 1 : 0, ko ?
			ARSDK_ARDRONE3_SETTINGSSTATE_MOTORERRORSTATECHANGED_MOTORERROR_ERRORMOTORSTALLED :
			ARSDK_ARDRONE3_SETTINGSSTATE_MOTORERRORSTATECHANGED_MOTORERROR_NOERROR));
	SEND(arsdk_cmd_send_Ardrone3_SettingsState_MotorErrorLastErrorChanged(ITF, ko ?
			ARSDK_ARDRONE3_SETTINGSSTATE_MOTORERRORLASTERRORCHANGED_MOTORERROR_ERRORMOTORSTALLED :
			ARSDK_ARDRONE3_SETTINGSSTATE_MOTORERRORLASTERRORCHANGED_MOTORERROR_NOERROR));
}

static void send_magneto_required(struct disco *d)
{
	int ko = (d->failures & DISCO_FAIL_MAGNETO) != 0;
	SEND(arsdk_cmd_send_Common_CalibrationState_MagnetoCalibrationStateChanged(ITF,
			!ko, !ko, !ko, ko));
	SEND(arsdk_cmd_send_Common_CalibrationState_MagnetoCalibrationRequiredState(ITF, ko));
}

void disco_send_settings(struct disco *d)
{
	struct disco_settings *s = &d->s;
	if (d->itf == NULL)
		return;
	SEND(arsdk_cmd_send_Common_SettingsState_ProductNameChanged(ITF, s->name));
	SEND(arsdk_cmd_send_Common_SettingsState_ProductSerialHighChanged(ITF, SERIAL_HIGH));
	SEND(arsdk_cmd_send_Common_SettingsState_ProductSerialLowChanged(ITF, SERIAL_LOW));
	SEND(arsdk_cmd_send_Common_SettingsState_CountryChanged(ITF, s->country));
	SEND(arsdk_cmd_send_Common_SettingsState_AutoCountryChanged(ITF, s->auto_country));
	SEND(arsdk_cmd_send_Common_WifiSettingsState_OutdoorSettingsChanged(ITF, s->outdoor));
	SEND(arsdk_cmd_send_Ardrone3_SettingsState_CPUID(ITF, CPU_ID));
	SEND(arsdk_cmd_send_Ardrone3_SettingsState_MotorSoftwareVersionChanged(ITF, MOTOR_SOFTWARE));
	SEND(arsdk_cmd_send_Ardrone3_SettingsState_ProductGPSVersionChanged(ITF,
			GPS_SOFTWARE, GPS_HARDWARE));
	send_motor(d);
	send_flight_meter(d);
	/* piloting */
	send_max_altitude(d);
	send_min_altitude(d);
	SEND(arsdk_cmd_send_Ardrone3_PilotingSettingsState_MaxDistanceChanged(ITF,
			s->max_distance, MAX_DISTANCE_MIN, MAX_DISTANCE_MAX));
	SEND(arsdk_cmd_send_Ardrone3_PilotingSettingsState_NoFlyOverMaxDistanceChanged(ITF,
			s->no_fly_over_max_distance));
	SEND(arsdk_cmd_send_Ardrone3_PilotingSettingsState_CirclingDirectionChanged(ITF,
			s->circling_direction));
	send_circling_altitude(d);
	SEND(arsdk_cmd_send_Ardrone3_PilotingSettingsState_PitchModeChanged(ITF,
			s->pitch_mode));
	/* return home */
	SEND(arsdk_cmd_send_Ardrone3_GPSSettingsState_HomeTypeChanged(ITF, s->home_type));
	SEND(arsdk_cmd_send_Ardrone3_GPSSettingsState_ReturnHomeDelayChanged(ITF,
			s->return_home_delay));
	SEND(arsdk_cmd_send_Common_FlightPlanSettingsState_ReturnHomeOnDisconnectChanged(ITF,
			s->rth_on_disconnect, 0));
	/* camera */
	SEND(arsdk_cmd_send_Ardrone3_PictureSettingsState_PictureFormatChanged(ITF,
			s->picture_format));
	SEND(arsdk_cmd_send_Ardrone3_PictureSettingsState_AutoWhiteBalanceChanged(ITF,
			s->white_balance));
	SEND(arsdk_cmd_send_Ardrone3_PictureSettingsState_ExpositionChanged(ITF,
			s->exposure, EXPOSURE_MIN, EXPOSURE_MAX));
	SEND(arsdk_cmd_send_Ardrone3_PictureSettingsState_SaturationChanged(ITF,
			s->saturation, SATURATION_MIN, SATURATION_MAX));
	SEND(arsdk_cmd_send_Ardrone3_PictureSettingsState_TimelapseChanged(ITF,
			s->timelapse, s->timelapse_interval, TIMELAPSE_MIN, TIMELAPSE_MAX));
	SEND(arsdk_cmd_send_Ardrone3_PictureSettingsState_VideoAutorecordChanged(ITF,
			s->video_autorecord, STORAGE_ID));
	SEND(arsdk_cmd_send_Ardrone3_PictureSettingsState_VideoStabilizationModeChanged(ITF,
			s->video_stabilization));
	SEND(arsdk_cmd_send_Ardrone3_PictureSettingsState_VideoRecordingModeChanged(ITF,
			s->video_recording_mode));
	SEND(arsdk_cmd_send_Ardrone3_PictureSettingsState_VideoFramerateChanged(ITF,
			s->video_framerate));
	SEND(arsdk_cmd_send_Ardrone3_PictureSettingsState_VideoResolutionsChanged(ITF,
			s->video_resolutions));
	/* Wi-Fi */
	send_wifi_selection(d);
	send_wifi_security(d);
}

void disco_send_states(struct disco *d)
{
	if (d->itf == NULL)
		return;
	SEND(arsdk_cmd_send_Common_ARLibsVersionsState_DeviceLibARCommandsVersion(ITF,
			ARCOMMANDS_VERSION));
	SEND(arsdk_cmd_send_Common_RunState_RunIdChanged(ITF, d->run_id));
	send_sensors(d);
	SEND(arsdk_cmd_send_Common_CommonState_WifiSignalChanged(ITF,
			(d->failures & DISCO_FAIL_LINK_WEAK) ? -88 : -45));
	send_storage(d);
	send_media_states(d);
	SEND(arsdk_cmd_send_Ardrone3_PilotingState_AlertStateChanged(ITF, d->alert));
	SEND(arsdk_cmd_send_Common_CalibrationState_MagnetoCalibrationStartedChanged(ITF,
			d->magneto_step != 0));
	send_pitot(d);
	send_motor(d);
	if (d->failures & DISCO_FAIL_MAGNETO)
		send_magneto_required(d);
	SEND(arsdk_cmd_send_Ardrone3_GPSSettingsState_GPSUpdateStateChanged(ITF,
			ARSDK_ARDRONE3_GPSSETTINGSSTATE_GPSUPDATESTATECHANGED_STATE_UPDATED));
	send_home_types(d);
	SEND(arsdk_cmd_send_Common_CameraSettingsState_CameraSettingsChanged(ITF,
			CAMERA_FOV, CAMERA_PAN_MAX, CAMERA_PAN_MIN,
			CAMERA_TILT_MAX, CAMERA_TILT_MIN));
	SEND(arsdk_cmd_send_Ardrone3_CameraState_DefaultCameraOrientation(ITF, 0, 0));
	SEND(arsdk_cmd_send_Ardrone3_CameraState_DefaultCameraOrientationV2(ITF, 0, 0));
	SEND(arsdk_cmd_send_Ardrone3_CameraState_VelocityRange(ITF,
			CAMERA_VELOCITY, CAMERA_VELOCITY));
	send_camera(d);
	send_flight_plan(d);
	send_mavlink_state(d);
	rc_set_itf(&d->rc, d->itf);
	rc_send_states(&d->rc);
}

/* ------------------------------------------------------------------ */
/* Media                                                               */
/* ------------------------------------------------------------------ */

/* ------------------------------------------------------------------ */
/* Files: what the Disco's FTP server shows (rig/disco-ftp)            */
/* ------------------------------------------------------------------ */

void disco_set_fs(struct disco *d, const char *fs, const char *samples)
{
	snprintf(d->fs, sizeof(d->fs), "%s", fs ? fs : "");
	snprintf(d->samples, sizeof(d->samples), "%s", samples ? samples : "");
}

/** The rig user owns the shared files, not root (the fake runs as root). */
static void own(const struct disco *d, const char *path)
{
	struct stat st;
	if (stat(d->fs, &st) == 0 && chown(path, st.st_uid, st.st_gid) < 0)
		LOGW("chown %s: %s", path, strerror(errno));
}

static void mkdirs(const struct disco *d, const char *dir)
{
	char path[512];
	size_t i;
	snprintf(path, sizeof(path), "%s", dir);
	for (i = 1; path[i] != '\0'; i++) {
		if (path[i] != '/')
			continue;
		path[i] = '\0';
		if (mkdir(path, 0755) == 0)
			own(d, path);
		path[i] = '/';
	}
	if (mkdir(path, 0755) == 0)
		own(d, path);
}

static int copy_file(const struct disco *d, const char *from, const char *to)
{
	char buf[16384];
	size_t n;
	int ok = 1;
	FILE *in = fopen(from, "rb"), *out;
	if (in == NULL)
		return -errno;
	out = fopen(to, "wb");
	if (out == NULL) {
		fclose(in);
		return -errno;
	}
	while ((n = fread(buf, 1, sizeof(buf), in)) > 0)
		ok &= fwrite(buf, 1, n, out) == n;
	fclose(in);
	ok &= fclose(out) == 0;
	own(d, to);
	return ok ? 0 : -EIO;
}

/** With shared files, the media counts are the files there (the app may
 * have deleted some over FTP). */
static void count_media(struct disco *d)
{
	char dir[512];
	DIR *dp;
	struct dirent *e;
	uint16_t photos = 0, videos = 0;
	if (d->fs[0] == '\0')
		return;
	snprintf(dir, sizeof(dir), "%s/internal_000/Disco/media", d->fs);
	dp = opendir(dir);
	if (dp == NULL)
		return;
	while ((e = readdir(dp)) != NULL) {
		const char *dot = strrchr(e->d_name, '.');
		if (dot == NULL)
			continue;
		if (strcmp(dot, ".mp4") == 0)
			videos++;
		else if (strcmp(dot, ".jpg") == 0 || strcmp(dot, ".dng") == 0)
			photos++;
	}
	closedir(dp);
	d->photos = photos;
	d->videos = videos;
}

/** A photo or video on the Disco's storage, named like the firmware's:
 * Disco_<local date>_<run id>.<ext>, with its thumbnail. */
static void media_file(struct disco *d, const char *ext)
{
	char media[512], thumb[512], name[128], date[32], from[300];
	time_t now = time(NULL);
	struct tm tm;
	int res;
	if (d->fs[0] == '\0')
		return;
	gmtime_r(&now, &tm);
	strftime(date, sizeof(date), "%Y-%m-%dT%H%M%S+0000", &tm);
	snprintf(name, sizeof(name), "Disco_%s_%s.%s", date, d->run_id, ext);
	snprintf(media, sizeof(media), "%s/internal_000/Disco/media", d->fs);
	snprintf(thumb, sizeof(thumb), "%s/internal_000/Disco/thumb", d->fs);
	mkdirs(d, media);
	mkdirs(d, thumb);
	snprintf(media, sizeof(media), "%s/internal_000/Disco/media/%s", d->fs, name);
	snprintf(from, sizeof(from), "%s/sample.%s", d->samples, ext);
	res = copy_file(d, from, media);
	if (res < 0) {
		LOGW("media %s: %s", media, strerror(-res));
		return;
	}
	/* GroundSdk: thumb/<name>.jpg for a video, thumb/<name> for a photo */
	snprintf(thumb, sizeof(thumb), "%s/internal_000/Disco/thumb/%s%s", d->fs, name,
			strcmp(ext, "mp4") == 0 ? ".jpg" : "");
	snprintf(from, sizeof(from), "%s/thumb.jpg", d->samples);
	copy_file(d, from, thumb);
	LOGI("media file %s", name);
}


static void video_record(struct disco *d, int start)
{
	if (start == d->recording)
		return;
	if (start && (d->failures & (DISCO_FAIL_CAMERA | DISCO_FAIL_STORAGE))) {
		if (d->itf != NULL)
			SEND(arsdk_cmd_send_Ardrone3_MediaRecordEvent_VideoEventChanged(ITF,
				ARSDK_ARDRONE3_MEDIARECORDEVENT_VIDEOEVENTCHANGED_EVENT_FAILED,
				(d->failures & DISCO_FAIL_CAMERA) ?
				ARSDK_ARDRONE3_MEDIARECORDEVENT_VIDEOEVENTCHANGED_ERROR_NOTAVAILABLE :
				ARSDK_ARDRONE3_MEDIARECORDEVENT_VIDEOEVENTCHANGED_ERROR_MEMORYFULL));
		return;
	}
	d->recording = start;
	if (start) {
		d->recording_start_ms = epoch_ms();
	} else {
		d->videos++;
		media_file(d, "mp4");
	}
	LOGI("video recording %s", start ? "started" : "stopped");
	if (d->itf == NULL)
		return;
	SEND(arsdk_cmd_send_Ardrone3_MediaRecordState_VideoStateChangedV2(ITF,
			start ? ARSDK_ARDRONE3_MEDIARECORDSTATE_VIDEOSTATECHANGEDV2_STATE_STARTED :
			ARSDK_ARDRONE3_MEDIARECORDSTATE_VIDEOSTATECHANGEDV2_STATE_STOPPED,
			ARSDK_ARDRONE3_MEDIARECORDSTATE_VIDEOSTATECHANGEDV2_ERROR_OK));
	SEND(arsdk_cmd_send_Ardrone3_MediaRecordEvent_VideoEventChanged(ITF,
			start ? ARSDK_ARDRONE3_MEDIARECORDEVENT_VIDEOEVENTCHANGED_EVENT_START :
			ARSDK_ARDRONE3_MEDIARECORDEVENT_VIDEOEVENTCHANGED_EVENT_STOP,
			ARSDK_ARDRONE3_MEDIARECORDEVENT_VIDEOEVENTCHANGED_ERROR_OK));
	SEND(arsdk_cmd_send_Common_CommonState_VideoRecordingTimestamp(ITF,
			d->recording_start_ms, start ? 0 : epoch_ms()));
	if (!start)
		send_storage(d);
}

static void picture_done(struct pomp_timer *timer, void *userdata)
{
	struct disco *d = userdata;
	d->picture_busy = 0;
	d->photos++;
	media_file(d, "jpg");
	LOGI("picture taken (%u)", d->photos);
	if (d->itf == NULL)
		return;
	SEND(arsdk_cmd_send_Ardrone3_MediaRecordEvent_PictureEventChanged(ITF,
			ARSDK_ARDRONE3_MEDIARECORDEVENT_PICTUREEVENTCHANGED_EVENT_TAKEN,
			ARSDK_ARDRONE3_MEDIARECORDEVENT_PICTUREEVENTCHANGED_ERROR_OK));
	SEND(arsdk_cmd_send_Ardrone3_MediaRecordState_PictureStateChangedV2(ITF,
			ARSDK_ARDRONE3_MEDIARECORDSTATE_PICTURESTATECHANGEDV2_STATE_READY,
			ARSDK_ARDRONE3_MEDIARECORDSTATE_PICTURESTATECHANGEDV2_ERROR_OK));
	send_storage(d);
}

static void take_picture(struct disco *d)
{
	if (d->failures & (DISCO_FAIL_CAMERA | DISCO_FAIL_STORAGE)) {
		SEND(arsdk_cmd_send_Ardrone3_MediaRecordEvent_PictureEventChanged(ITF,
				ARSDK_ARDRONE3_MEDIARECORDEVENT_PICTUREEVENTCHANGED_EVENT_FAILED,
				(d->failures & DISCO_FAIL_CAMERA) ?
				ARSDK_ARDRONE3_MEDIARECORDEVENT_PICTUREEVENTCHANGED_ERROR_NOTAVAILABLE :
				ARSDK_ARDRONE3_MEDIARECORDEVENT_PICTUREEVENTCHANGED_ERROR_MEMORYFULL));
		return;
	}
	if (d->picture_busy) {
		SEND(arsdk_cmd_send_Ardrone3_MediaRecordEvent_PictureEventChanged(ITF,
				ARSDK_ARDRONE3_MEDIARECORDEVENT_PICTUREEVENTCHANGED_EVENT_FAILED,
				ARSDK_ARDRONE3_MEDIARECORDEVENT_PICTUREEVENTCHANGED_ERROR_BUSY));
		return;
	}
	d->picture_busy = 1;
	SEND(arsdk_cmd_send_Ardrone3_MediaRecordState_PictureStateChangedV2(ITF,
			ARSDK_ARDRONE3_MEDIARECORDSTATE_PICTURESTATECHANGEDV2_STATE_BUSY,
			ARSDK_ARDRONE3_MEDIARECORDSTATE_PICTURESTATECHANGEDV2_ERROR_OK));
	pomp_timer_set(d->picture_timer, PICTURE_MS);
}

/* ------------------------------------------------------------------ */
/* Calibrations                                                        */
/* ------------------------------------------------------------------ */

static void send_magneto(struct disco *d)
{
	static const int32_t axes[] = {
		ARSDK_COMMON_CALIBRATIONSTATE_MAGNETOCALIBRATIONAXISTOCALIBRATECHANGED_AXIS_NONE,
		ARSDK_COMMON_CALIBRATIONSTATE_MAGNETOCALIBRATIONAXISTOCALIBRATECHANGED_AXIS_XAXIS,
		ARSDK_COMMON_CALIBRATIONSTATE_MAGNETOCALIBRATIONAXISTOCALIBRATECHANGED_AXIS_YAXIS,
		ARSDK_COMMON_CALIBRATIONSTATE_MAGNETOCALIBRATIONAXISTOCALIBRATECHANGED_AXIS_ZAXIS,
	};
	int step = d->magneto_step;
	if (d->itf == NULL)
		return;
	/* axes done so far; all of them once finished (step 0) */
	SEND(arsdk_cmd_send_Common_CalibrationState_MagnetoCalibrationStateChanged(ITF,
			step == 0 || step > 1, step == 0 || step > 2, step == 0, 0));
	SEND(arsdk_cmd_send_Common_CalibrationState_MagnetoCalibrationAxisToCalibrateChanged(ITF,
			axes[step]));
}

static void calib_tick(struct pomp_timer *timer, void *userdata)
{
	struct disco *d = userdata;
	if (d->magneto_step != 0) {
		d->magneto_step = d->magneto_step == 3 ? 0 : d->magneto_step + 1;
		send_magneto(d);
		if (d->magneto_step != 0) {
			pomp_timer_set(d->calib_timer, MAGNETO_AXIS_MS);
		} else if (d->itf != NULL) {
			LOGI("magnetometer calibrated");
			d->failures &= ~DISCO_FAIL_MAGNETO;
			SEND(arsdk_cmd_send_Common_CalibrationState_MagnetoCalibrationStartedChanged(ITF, 0));
			SEND(arsdk_cmd_send_Common_CalibrationState_MagnetoCalibrationRequiredState(ITF, 0));
			send_flight_plan(d);
		}
	}
	if (d->pitot_calibrating) {
		d->pitot_calibrating = 0;
		d->failures &= ~DISCO_FAIL_PITOT;
		LOGI("pitot calibrated");
		if (d->itf != NULL)
			SEND(arsdk_cmd_send_Common_CalibrationState_PitotCalibrationStateChanged(ITF,
				ARSDK_COMMON_CALIBRATIONSTATE_PITOTCALIBRATIONSTATECHANGED_STATE_DONE, 0));
	}
}

static void magneto_calibration(struct disco *d, int start)
{
	if (start && d->magneto_step == 0) {
		LOGI("magnetometer calibration started");
		d->magneto_step = 1;
		SEND(arsdk_cmd_send_Common_CalibrationState_MagnetoCalibrationStartedChanged(ITF, 1));
		send_magneto(d);
		pomp_timer_set(d->calib_timer, MAGNETO_AXIS_MS);
	} else if (!start && d->magneto_step != 0) {
		/* aborted: the previous calibration stays */
		LOGI("magnetometer calibration aborted");
		d->magneto_step = 0;
		if (!d->pitot_calibrating)
			pomp_timer_clear(d->calib_timer);
		SEND(arsdk_cmd_send_Common_CalibrationState_MagnetoCalibrationAxisToCalibrateChanged(ITF,
				ARSDK_COMMON_CALIBRATIONSTATE_MAGNETOCALIBRATIONAXISTOCALIBRATECHANGED_AXIS_NONE));
		SEND(arsdk_cmd_send_Common_CalibrationState_MagnetoCalibrationStartedChanged(ITF, 0));
	}
}

static void pitot_calibration(struct disco *d, int start)
{
	if (start && !d->pitot_calibrating) {
		LOGI("pitot calibration started");
		d->pitot_calibrating = 1;
		SEND(arsdk_cmd_send_Common_CalibrationState_PitotCalibrationStateChanged(ITF,
				ARSDK_COMMON_CALIBRATIONSTATE_PITOTCALIBRATIONSTATECHANGED_STATE_IN_PROGRESS, 0));
		if (d->magneto_step == 0)
			pomp_timer_set(d->calib_timer, PITOT_MS);
	} else if (!start && d->pitot_calibrating) {
		d->pitot_calibrating = 0;
		SEND(arsdk_cmd_send_Common_CalibrationState_PitotCalibrationStateChanged(ITF,
				ARSDK_COMMON_CALIBRATIONSTATE_PITOTCALIBRATIONSTATECHANGED_STATE_DONE, 0));
	}
}

/* ------------------------------------------------------------------ */
/* Wi-Fi                                                               */
/* ------------------------------------------------------------------ */

static void wifi_scan(struct disco *d, int32_t band)
{
	static const struct { const char *ssid; int16_t rssi; int32_t band; uint8_t ch; } nets[] = {
		{ "FieldHouse", -61, ARSDK_ARDRONE3_NETWORKSTATE_WIFISCANLISTCHANGED_BAND_2_4GHZ, 6 },
		{ "Clubhouse-Guest", -74, ARSDK_ARDRONE3_NETWORKSTATE_WIFISCANLISTCHANGED_BAND_2_4GHZ, 11 },
		{ "Clubhouse-5G", -80, ARSDK_ARDRONE3_NETWORKSTATE_WIFISCANLISTCHANGED_BAND_5GHZ, 44 },
	};
	size_t i;
	for (i = 0; i < sizeof(nets) / sizeof(nets[0]); i++) {
		if (band != ARSDK_ARDRONE3_NETWORK_WIFISCAN_BAND_ALL && band != nets[i].band)
			continue;
		SEND(arsdk_cmd_send_Ardrone3_NetworkState_WifiScanListChanged(ITF,
				nets[i].ssid, nets[i].rssi, nets[i].band, nets[i].ch));
	}
	SEND(arsdk_cmd_send_Ardrone3_NetworkState_AllWifiScanChanged(ITF));
}

static void wifi_auth_channels(struct disco *d)
{
	/* in_or_out: bit 0 allowed outdoor, bit 1 allowed indoor (US) */
	static const uint8_t ch5[] = { 36, 40, 44, 48, 149, 153, 157, 161, 165 };
	uint8_t ch;
	size_t i;
	for (ch = 1; ch <= 11; ch++)
		SEND(arsdk_cmd_send_Ardrone3_NetworkState_WifiAuthChannelListChanged(ITF,
				ARSDK_ARDRONE3_NETWORKSTATE_WIFIAUTHCHANNELLISTCHANGED_BAND_2_4GHZ,
				ch, 3));
	for (i = 0; i < sizeof(ch5); i++)
		SEND(arsdk_cmd_send_Ardrone3_NetworkState_WifiAuthChannelListChanged(ITF,
				ARSDK_ARDRONE3_NETWORKSTATE_WIFIAUTHCHANNELLISTCHANGED_BAND_5GHZ,
				ch5[i], ch5[i] < 100 ? 2 : 3));
	SEND(arsdk_cmd_send_Ardrone3_NetworkState_AllWifiAuthChannelChanged(ITF));
}

static void wifi_select(struct disco *d, int32_t type, int32_t band, uint8_t channel)
{
	struct disco_settings *s = &d->s;
	if (type == ARSDK_ARDRONE3_NETWORKSETTINGS_WIFISELECTION_TYPE_MANUAL) {
		s->wifi_type = ARSDK_ARDRONE3_NETWORKSETTINGSSTATE_WIFISELECTIONCHANGED_TYPE_MANUAL;
		s->wifi_band = band == ARSDK_ARDRONE3_NETWORKSETTINGS_WIFISELECTION_BAND_5GHZ ?
			ARSDK_ARDRONE3_NETWORKSETTINGSSTATE_WIFISELECTIONCHANGED_BAND_5GHZ :
			ARSDK_ARDRONE3_NETWORKSETTINGSSTATE_WIFISELECTIONCHANGED_BAND_2_4GHZ;
		s->wifi_channel = channel;
	} else {
		/* automatic: the drone picks the least crowded channel */
		switch (band) {
		case ARSDK_ARDRONE3_NETWORKSETTINGS_WIFISELECTION_BAND_5GHZ:
			s->wifi_type = ARSDK_ARDRONE3_NETWORKSETTINGSSTATE_WIFISELECTIONCHANGED_TYPE_AUTO_5GHZ;
			s->wifi_band = ARSDK_ARDRONE3_NETWORKSETTINGSSTATE_WIFISELECTIONCHANGED_BAND_5GHZ;
			s->wifi_channel = 149;
			break;
		case ARSDK_ARDRONE3_NETWORKSETTINGS_WIFISELECTION_BAND_2_4GHZ:
			s->wifi_type = ARSDK_ARDRONE3_NETWORKSETTINGSSTATE_WIFISELECTIONCHANGED_TYPE_AUTO_2_4GHZ;
			s->wifi_band = ARSDK_ARDRONE3_NETWORKSETTINGSSTATE_WIFISELECTIONCHANGED_BAND_2_4GHZ;
			s->wifi_channel = 1;
			break;
		default:
			s->wifi_type = ARSDK_ARDRONE3_NETWORKSETTINGSSTATE_WIFISELECTIONCHANGED_TYPE_AUTO_ALL;
			s->wifi_band = ARSDK_ARDRONE3_NETWORKSETTINGSSTATE_WIFISELECTIONCHANGED_BAND_2_4GHZ;
			s->wifi_channel = 1;
			break;
		}
	}
	send_wifi_selection(d);
}

/* ------------------------------------------------------------------ */
/* Commands                                                            */
/* ------------------------------------------------------------------ */

/** A Disco from the US, Canada or Israel keeps the automatic country. */
static int country_locked(const struct disco *d)
{
	return strcmp(d->factory_country, "US") == 0
		|| strcmp(d->factory_country, "CA") == 0
		|| strcmp(d->factory_country, "IL") == 0;
}

int disco_recv_cmd(struct disco *d, const struct arsdk_cmd *cmd)
{
	struct disco_settings *s = &d->s;
	const char *str = NULL, *str2 = NULL;
	float f = 0, f2 = 0;
	double lat = 0, lon = 0, alt = 0, h = 0, v = 0;
	int32_t i32 = 0, i32b = 0;
	uint16_t u16 = 0;
	uint8_t u8 = 0, u8b = 0;
	int8_t i8 = 0, i8b = 0;

	if (d->itf == NULL)
		return 0;
	rc_set_itf(&d->rc, d->itf);
	if (rc_recv_cmd(&d->rc, cmd))
		return 1;

	switch (cmd->id) {
	/* identity */
	case ARSDK_ID_COMMON_SETTINGS_PRODUCTNAME:
		if (arsdk_cmd_dec_Common_Settings_ProductName(cmd, &str) >= 0) {
			snprintf(s->name, sizeof(s->name), "%s", str);
			SEND(arsdk_cmd_send_Common_SettingsState_ProductNameChanged(ITF, s->name));
		}
		return 1;
	case ARSDK_ID_COMMON_SETTINGS_COUNTRY:
		if (arsdk_cmd_dec_Common_Settings_Country(cmd, &str) >= 0) {
			/* country_regul.yaml autolock: no manual country */
			if (!country_locked(d) && strlen(str) == 2
					&& strcmp(str, s->country) != 0) {
				snprintf(s->country, sizeof(s->country), "%s", str);
				d->wifi_restart = 1;
			}
			SEND(arsdk_cmd_send_Common_SettingsState_CountryChanged(ITF, s->country));
		}
		return 1;
	case ARSDK_ID_COMMON_SETTINGS_AUTOCOUNTRY:
		if (arsdk_cmd_dec_Common_Settings_AutoCountry(cmd, &u8) >= 0) {
			if (!country_locked(d) && s->auto_country != (u8 != 0)) {
				s->auto_country = u8 != 0;
				d->wifi_restart = 1;
			}
			SEND(arsdk_cmd_send_Common_SettingsState_AutoCountryChanged(ITF,
					s->auto_country));
		}
		return 1;
	case ARSDK_ID_COMMON_SETTINGS_RESET: {
		char name[sizeof(s->name)];
		LOGI("settings reset");
		snprintf(name, sizeof(name), "%s", s->name);
		disco_reset_settings(s, name);
		disco_send_settings(d);
		SEND(arsdk_cmd_send_Common_SettingsState_ResetChanged(ITF));
		return 1;
	}
	case ARSDK_ID_COMMON_WIFISETTINGS_OUTDOORSETTING:
		if (arsdk_cmd_dec_Common_WifiSettings_OutdoorSetting(cmd, &u8) >= 0) {
			if (s->outdoor != (u8 != 0))
				d->wifi_restart = 1;
			s->outdoor = u8 != 0;
			SEND(arsdk_cmd_send_Common_WifiSettingsState_OutdoorSettingsChanged(ITF,
					s->outdoor));
		}
		return 1;
	/* the app's clock */
	case ARSDK_ID_COMMON_COMMON_CURRENTDATE:
		if (arsdk_cmd_dec_Common_Common_CurrentDate(cmd, &str) >= 0)
			SEND(arsdk_cmd_send_Common_CommonState_CurrentDateChanged(ITF, str));
		return 1;
	case ARSDK_ID_COMMON_COMMON_CURRENTTIME:
		if (arsdk_cmd_dec_Common_Common_CurrentTime(cmd, &str) >= 0)
			SEND(arsdk_cmd_send_Common_CommonState_CurrentTimeChanged(ITF, str));
		return 1;
	case ARSDK_ID_COMMON_CONTROLLER_ISPILOTING:
		if (arsdk_cmd_dec_Common_Controller_IsPiloting(cmd, &u8) >= 0)
			d->is_piloting = u8 != 0;
		return 1;
	/* pilot position, for the pilot home type */
	case ARSDK_ID_ARDRONE3_GPSSETTINGS_SENDCONTROLLERGPS:
		if (arsdk_cmd_dec_Ardrone3_GPSSettings_SendControllerGPS(cmd,
				&lat, &lon, &alt, &h, &v) < 0)
			return 1;
		goto pilot_position;
	case ARSDK_ID_COMMON_GPS_CONTROLLERPOSITIONFORRUN:
		if (arsdk_cmd_dec_Common_GPS_ControllerPositionForRun(cmd, &lat, &lon) < 0)
			return 1;
		goto pilot_position;
	case ARSDK_ID_CONTROLLER_INFO_GPS: {
		float hacc, vacc, vn, ve, vd;
		double ts;
		if (arsdk_cmd_dec_Controller_info_Gps(cmd, &lat, &lon, &f,
				&hacc, &vacc, &vn, &ve, &vd, &ts) < 0)
			return 1;
		goto pilot_position;
	}
pilot_position: {
		int was_known = d->pilot_lat != 500.0;
		d->pilot_lat = lat;
		d->pilot_lon = lon;
		if (!was_known)
			send_home_types(d);
		return 1;
	}
	/* piloting settings, clamped to their range */
	case ARSDK_ID_ARDRONE3_PILOTINGSETTINGS_MAXALTITUDE:
		if (arsdk_cmd_dec_Ardrone3_PilotingSettings_MaxAltitude(cmd, &f) >= 0) {
			s->max_altitude = clampf(f, MAX_ALTITUDE_MIN, MAX_ALTITUDE_MAX);
			send_max_altitude(d);
		}
		return 1;
	case ARSDK_ID_ARDRONE3_PILOTINGSETTINGS_MINALTITUDE:
		if (arsdk_cmd_dec_Ardrone3_PilotingSettings_MinAltitude(cmd, &f) >= 0) {
			s->min_altitude = clampf(f, MIN_ALTITUDE_MIN, MIN_ALTITUDE_MAX);
			send_min_altitude(d);
		}
		return 1;
	case ARSDK_ID_ARDRONE3_PILOTINGSETTINGS_MAXDISTANCE:
		if (arsdk_cmd_dec_Ardrone3_PilotingSettings_MaxDistance(cmd, &f) >= 0) {
			s->max_distance = clampf(f, MAX_DISTANCE_MIN, MAX_DISTANCE_MAX);
			SEND(arsdk_cmd_send_Ardrone3_PilotingSettingsState_MaxDistanceChanged(ITF,
					s->max_distance, MAX_DISTANCE_MIN, MAX_DISTANCE_MAX));
		}
		return 1;
	case ARSDK_ID_ARDRONE3_PILOTINGSETTINGS_NOFLYOVERMAXDISTANCE:
		if (arsdk_cmd_dec_Ardrone3_PilotingSettings_NoFlyOverMaxDistance(cmd, &u8) >= 0) {
			s->no_fly_over_max_distance = u8 != 0;
			SEND(arsdk_cmd_send_Ardrone3_PilotingSettingsState_NoFlyOverMaxDistanceChanged(ITF,
					s->no_fly_over_max_distance));
		}
		return 1;
	case ARSDK_ID_ARDRONE3_PILOTINGSETTINGS_CIRCLINGDIRECTION:
		if (arsdk_cmd_dec_Ardrone3_PilotingSettings_CirclingDirection(cmd, &i32) >= 0) {
			/* "Circling direction cannot be set to %u. Set to %u." */
			s->circling_direction = i32 == ARSDK_ARDRONE3_PILOTINGSETTINGS_CIRCLINGDIRECTION_VALUE_CCW ?
				ARSDK_ARDRONE3_PILOTINGSETTINGSSTATE_CIRCLINGDIRECTIONCHANGED_VALUE_CCW :
				ARSDK_ARDRONE3_PILOTINGSETTINGSSTATE_CIRCLINGDIRECTIONCHANGED_VALUE_CW;
			SEND(arsdk_cmd_send_Ardrone3_PilotingSettingsState_CirclingDirectionChanged(ITF,
					s->circling_direction));
		}
		return 1;
	case ARSDK_ID_ARDRONE3_PILOTINGSETTINGS_CIRCLINGALTITUDE:
		if (arsdk_cmd_dec_Ardrone3_PilotingSettings_CirclingAltitude(cmd, &u16) >= 0) {
			s->circling_altitude = u16 < CIRCLING_ALTITUDE_MIN ? CIRCLING_ALTITUDE_MIN :
				u16 > CIRCLING_ALTITUDE_MAX ? CIRCLING_ALTITUDE_MAX : u16;
			send_circling_altitude(d);
		}
		return 1;
	case ARSDK_ID_ARDRONE3_PILOTINGSETTINGS_PITCHMODE:
		if (arsdk_cmd_dec_Ardrone3_PilotingSettings_PitchMode(cmd, &i32) >= 0) {
			s->pitch_mode = i32 == ARSDK_ARDRONE3_PILOTINGSETTINGS_PITCHMODE_VALUE_INVERTED;
			SEND(arsdk_cmd_send_Ardrone3_PilotingSettingsState_PitchModeChanged(ITF,
					s->pitch_mode));
		}
		return 1;
	/* return home */
	case ARSDK_ID_ARDRONE3_GPSSETTINGS_HOMETYPE:
		if (arsdk_cmd_dec_Ardrone3_GPSSettings_HomeType(cmd, &i32) >= 0) {
			s->home_type = i32 == ARSDK_ARDRONE3_GPSSETTINGS_HOMETYPE_TYPE_PILOT ?
				ARSDK_ARDRONE3_GPSSETTINGSSTATE_HOMETYPECHANGED_TYPE_PILOT :
				ARSDK_ARDRONE3_GPSSETTINGSSTATE_HOMETYPECHANGED_TYPE_TAKEOFF;
			SEND(arsdk_cmd_send_Ardrone3_GPSSettingsState_HomeTypeChanged(ITF,
					s->home_type));
			send_home_types(d);
		}
		return 1;
	case ARSDK_ID_ARDRONE3_GPSSETTINGS_RETURNHOMEDELAY:
		if (arsdk_cmd_dec_Ardrone3_GPSSettings_ReturnHomeDelay(cmd, &u16) >= 0) {
			s->return_home_delay = u16;
			SEND(arsdk_cmd_send_Ardrone3_GPSSettingsState_ReturnHomeDelayChanged(ITF,
					s->return_home_delay));
		}
		return 1;
	case ARSDK_ID_COMMON_FLIGHTPLANSETTINGS_RETURNHOMEONDISCONNECT:
		if (arsdk_cmd_dec_Common_FlightPlanSettings_ReturnHomeOnDisconnect(cmd, &u8) >= 0) {
			s->rth_on_disconnect = u8 != 0;
			SEND(arsdk_cmd_send_Common_FlightPlanSettingsState_ReturnHomeOnDisconnectChanged(ITF,
					s->rth_on_disconnect, 0));
		}
		return 1;
	/* camera settings */
	case ARSDK_ID_ARDRONE3_PICTURESETTINGS_PICTUREFORMATSELECTION:
		if (arsdk_cmd_dec_Ardrone3_PictureSettings_PictureFormatSelection(cmd, &i32) >= 0) {
			s->picture_format = i32;
			SEND(arsdk_cmd_send_Ardrone3_PictureSettingsState_PictureFormatChanged(ITF, i32));
		}
		return 1;
	case ARSDK_ID_ARDRONE3_PICTURESETTINGS_AUTOWHITEBALANCESELECTION:
		if (arsdk_cmd_dec_Ardrone3_PictureSettings_AutoWhiteBalanceSelection(cmd, &i32) >= 0) {
			s->white_balance = i32;
			SEND(arsdk_cmd_send_Ardrone3_PictureSettingsState_AutoWhiteBalanceChanged(ITF, i32));
		}
		return 1;
	case ARSDK_ID_ARDRONE3_PICTURESETTINGS_EXPOSITIONSELECTION:
		if (arsdk_cmd_dec_Ardrone3_PictureSettings_ExpositionSelection(cmd, &f) >= 0) {
			s->exposure = clampf(f, EXPOSURE_MIN, EXPOSURE_MAX);
			SEND(arsdk_cmd_send_Ardrone3_PictureSettingsState_ExpositionChanged(ITF,
					s->exposure, EXPOSURE_MIN, EXPOSURE_MAX));
		}
		return 1;
	case ARSDK_ID_ARDRONE3_PICTURESETTINGS_SATURATIONSELECTION:
		if (arsdk_cmd_dec_Ardrone3_PictureSettings_SaturationSelection(cmd, &f) >= 0) {
			s->saturation = clampf(f, SATURATION_MIN, SATURATION_MAX);
			SEND(arsdk_cmd_send_Ardrone3_PictureSettingsState_SaturationChanged(ITF,
					s->saturation, SATURATION_MIN, SATURATION_MAX));
		}
		return 1;
	case ARSDK_ID_ARDRONE3_PICTURESETTINGS_TIMELAPSESELECTION:
		if (arsdk_cmd_dec_Ardrone3_PictureSettings_TimelapseSelection(cmd, &u8, &f) >= 0) {
			s->timelapse = u8 != 0;
			s->timelapse_interval = clampf(f, TIMELAPSE_MIN, TIMELAPSE_MAX);
			SEND(arsdk_cmd_send_Ardrone3_PictureSettingsState_TimelapseChanged(ITF,
					s->timelapse, s->timelapse_interval,
					TIMELAPSE_MIN, TIMELAPSE_MAX));
		}
		return 1;
	case ARSDK_ID_ARDRONE3_PICTURESETTINGS_VIDEOAUTORECORDSELECTION:
		if (arsdk_cmd_dec_Ardrone3_PictureSettings_VideoAutorecordSelection(cmd, &u8, &u8b) >= 0) {
			s->video_autorecord = u8 != 0;
			SEND(arsdk_cmd_send_Ardrone3_PictureSettingsState_VideoAutorecordChanged(ITF,
					s->video_autorecord, STORAGE_ID));
		}
		return 1;
	case ARSDK_ID_ARDRONE3_PICTURESETTINGS_VIDEOSTABILIZATIONMODE:
		if (arsdk_cmd_dec_Ardrone3_PictureSettings_VideoStabilizationMode(cmd, &i32) >= 0) {
			s->video_stabilization = i32;
			SEND(arsdk_cmd_send_Ardrone3_PictureSettingsState_VideoStabilizationModeChanged(ITF, i32));
		}
		return 1;
	case ARSDK_ID_ARDRONE3_PICTURESETTINGS_VIDEORECORDINGMODE:
		if (arsdk_cmd_dec_Ardrone3_PictureSettings_VideoRecordingMode(cmd, &i32) >= 0) {
			s->video_recording_mode = i32;
			SEND(arsdk_cmd_send_Ardrone3_PictureSettingsState_VideoRecordingModeChanged(ITF, i32));
		}
		return 1;
	case ARSDK_ID_ARDRONE3_PICTURESETTINGS_VIDEOFRAMERATE:
		if (arsdk_cmd_dec_Ardrone3_PictureSettings_VideoFramerate(cmd, &i32) >= 0) {
			s->video_framerate = i32;
			SEND(arsdk_cmd_send_Ardrone3_PictureSettingsState_VideoFramerateChanged(ITF, i32));
		}
		return 1;
	case ARSDK_ID_ARDRONE3_PICTURESETTINGS_VIDEORESOLUTIONS:
		if (arsdk_cmd_dec_Ardrone3_PictureSettings_VideoResolutions(cmd, &i32) >= 0) {
			s->video_resolutions = i32;
			SEND(arsdk_cmd_send_Ardrone3_PictureSettingsState_VideoResolutionsChanged(ITF, i32));
		}
		return 1;
	/* camera orientation (the Disco's is digital, in the fisheye image) */
	case ARSDK_ID_ARDRONE3_CAMERA_ORIENTATION:
		if (arsdk_cmd_dec_Ardrone3_Camera_Orientation(cmd, &i8, &i8b) < 0)
			return 1;
		f = i8;
		f2 = i8b;
		goto camera_orientation;
	case ARSDK_ID_ARDRONE3_CAMERA_ORIENTATIONV2:
		if (arsdk_cmd_dec_Ardrone3_Camera_OrientationV2(cmd, &f, &f2) < 0)
			return 1;
camera_orientation:
		s->camera_tilt = clampf(f, CAMERA_TILT_MIN, CAMERA_TILT_MAX);
		s->camera_pan = clampf(f2, CAMERA_PAN_MIN, CAMERA_PAN_MAX);
		send_camera(d);
		return 1;
	case ARSDK_ID_ARDRONE3_CAMERA_VELOCITY:
		if (arsdk_cmd_dec_Ardrone3_Camera_Velocity(cmd, &f, &f2) >= 0)
			disco_camera_velocity(d, f, f2);
		return 1;
	/* media */
	case ARSDK_ID_ARDRONE3_MEDIARECORD_PICTUREV2:
		take_picture(d);
		return 1;
	case ARSDK_ID_ARDRONE3_MEDIARECORD_VIDEOV2:
		if (arsdk_cmd_dec_Ardrone3_MediaRecord_VideoV2(cmd, &i32) >= 0)
			video_record(d, i32 == ARSDK_ARDRONE3_MEDIARECORD_VIDEOV2_RECORD_START);
		return 1;
	/* calibrations */
	case ARSDK_ID_COMMON_CALIBRATION_MAGNETOCALIBRATION:
		if (arsdk_cmd_dec_Common_Calibration_MagnetoCalibration(cmd, &u8) >= 0)
			magneto_calibration(d, u8 != 0);
		return 1;
	case ARSDK_ID_COMMON_CALIBRATION_PITOTCALIBRATION:
		if (arsdk_cmd_dec_Common_Calibration_PitotCalibration(cmd, &u8) >= 0)
			pitot_calibration(d, u8 != 0);
		return 1;
	case ARSDK_ID_ARDRONE3_PILOTING_FLATTRIM:
		SEND(arsdk_cmd_send_Ardrone3_PilotingState_FlatTrimChanged(ITF));
		return 1;
	/* flight plan: the playing state; the flight itself is the fake's
	 * (fp_request) */
	case ARSDK_ID_COMMON_MAVLINK_START:
		if (arsdk_cmd_dec_Common_Mavlink_Start(cmd, &str, &i32) < 0)
			return 1;
		snprintf(d->mavlink_file, sizeof(d->mavlink_file), "%s", str);
		d->mavlink_state = ARSDK_COMMON_MAVLINKSTATE_MAVLINKFILEPLAYINGSTATECHANGED_STATE_PLAYING;
		d->fp_request = DISCO_FP_START;
		LOGI("flight plan %s playing", d->mavlink_file);
		send_mavlink_state(d);
		return 1;
	case ARSDK_ID_COMMON_MAVLINK_PAUSE:
		if (d->mavlink_state != ARSDK_COMMON_MAVLINKSTATE_MAVLINKFILEPLAYINGSTATECHANGED_STATE_PLAYING)
			return 1;
		d->mavlink_state = ARSDK_COMMON_MAVLINKSTATE_MAVLINKFILEPLAYINGSTATECHANGED_STATE_PAUSED;
		d->fp_request = DISCO_FP_PAUSE;
		send_mavlink_state(d);
		return 1;
	case ARSDK_ID_COMMON_MAVLINK_STOP:
		disco_flight_plan_stopped(d);
		d->fp_request = DISCO_FP_STOP;
		return 1;
	/* Wi-Fi */
	case ARSDK_ID_ARDRONE3_NETWORK_WIFISCAN:
		if (arsdk_cmd_dec_Ardrone3_Network_WifiScan(cmd, &i32) >= 0)
			wifi_scan(d, i32);
		return 1;
	case ARSDK_ID_ARDRONE3_NETWORK_WIFIAUTHCHANNEL:
		wifi_auth_channels(d);
		return 1;
	case ARSDK_ID_ARDRONE3_NETWORKSETTINGS_WIFISELECTION:
		if (arsdk_cmd_dec_Ardrone3_NetworkSettings_WifiSelection(cmd, &i32, &i32b, &u8) >= 0)
			wifi_select(d, i32, i32b, u8);
		return 1;
	case ARSDK_ID_ARDRONE3_NETWORKSETTINGS_WIFISECURITY:
		if (arsdk_cmd_dec_Ardrone3_NetworkSettings_WifiSecurity(cmd, &i32, &str2, &i32b) >= 0) {
			s->wifi_security = i32 == ARSDK_ARDRONE3_NETWORKSETTINGS_WIFISECURITY_TYPE_WPA2 ?
				ARSDK_ARDRONE3_NETWORKSETTINGSSTATE_WIFISECURITYCHANGED_TYPE_WPA2 :
				ARSDK_ARDRONE3_NETWORKSETTINGSSTATE_WIFISECURITYCHANGED_TYPE_OPEN;
			snprintf(s->wifi_key, sizeof(s->wifi_key), "%s",
					s->wifi_security ? str2 : "");
			send_wifi_security(d);
		}
		return 1;
	default:
		return 0;
	}
}

/* ------------------------------------------------------------------ */
/* Flight events                                                       */
/* ------------------------------------------------------------------ */

void disco_flying_state(struct disco *d, int32_t state)
{
	int airborne = state == ARSDK_ARDRONE3_PILOTINGSTATE_FLYINGSTATECHANGED_STATE_TAKINGOFF
		|| state == ARSDK_ARDRONE3_PILOTINGSTATE_FLYINGSTATECHANGED_STATE_HOVERING
		|| state == ARSDK_ARDRONE3_PILOTINGSTATE_FLYINGSTATECHANGED_STATE_FLYING
		|| state == ARSDK_ARDRONE3_PILOTINGSTATE_FLYINGSTATECHANGED_STATE_LANDING;

	rc_landed(&d->rc, state == ARSDK_ARDRONE3_PILOTINGSTATE_FLYINGSTATECHANGED_STATE_LANDED);
	if (airborne && d->takeoff_ms == 0) {
		d->takeoff_ms = now_ms();
		/* video_autorecord: records from take-off to landing */
		if (d->s.video_autorecord)
			video_record(d, 1);
	} else if (state == ARSDK_ARDRONE3_PILOTINGSTATE_FLYINGSTATECHANGED_STATE_LANDED
			&& d->takeoff_ms != 0) {
		uint64_t s = (now_ms() - d->takeoff_ms) / 1000;
		d->takeoff_ms = 0;
		d->flights++;
		d->last_flight_s = (uint16_t)s;
		d->total_flight_s += (uint32_t)s;
		LOGI("flight %u: %u s", d->flights, d->last_flight_s);
		if (d->itf != NULL)
			send_flight_meter(d);
		if (d->s.video_autorecord)
			video_record(d, 0);
	}
}

/* Battery alerts: returns 1 when the battery just became critical. */
static int32_t battery_alert(uint8_t battery)
{
	if (battery <= 5)
		return ARSDK_ARDRONE3_PILOTINGSTATE_ALERTSTATECHANGED_STATE_ALMOST_EMPTY_BATTERY;
	if (battery <= 10)
		return ARSDK_ARDRONE3_PILOTINGSTATE_ALERTSTATECHANGED_STATE_CRITICAL_BATTERY;
	if (battery <= 20)
		return ARSDK_ARDRONE3_PILOTINGSTATE_ALERTSTATECHANGED_STATE_LOW_BATTERY;
	return ARSDK_ARDRONE3_PILOTINGSTATE_ALERTSTATECHANGED_STATE_NONE;
}

void disco_battery(struct disco *d, uint8_t battery)
{
	int32_t alert;
	if (d->alert_forced)
		return;
	alert = battery_alert(battery);
	if (alert == d->alert)
		return;
	d->alert = alert;
	if (d->itf != NULL)
		SEND(arsdk_cmd_send_Ardrone3_PilotingState_AlertStateChanged(ITF, alert));
}

void disco_gps_fix(struct disco *d, uint8_t fixed)
{
	if (fixed == d->gps_fixed)
		return;
	d->gps_fixed = fixed;
	if (d->itf == NULL)
		return;
	send_home_types(d);
	send_flight_plan(d);
}

/* ------------------------------------------------------------------ */
/* The controller's mapped buttons and slider                          */
/* ------------------------------------------------------------------ */

void disco_video_toggle(struct disco *d)
{
	video_record(d, !d->recording);
}

void disco_take_picture(struct disco *d)
{
	if (d->itf != NULL)
		take_picture(d);
}

void disco_exposure_step(struct disco *d, int step)
{
	d->s.exposure = clampf(d->s.exposure + 0.3f * (float)step, EXPOSURE_MIN, EXPOSURE_MAX);
	if (d->itf != NULL)
		SEND(arsdk_cmd_send_Ardrone3_PictureSettingsState_ExpositionChanged(ITF,
				d->s.exposure, EXPOSURE_MIN, EXPOSURE_MAX));
}

void disco_center_camera(struct disco *d)
{
	d->s.camera_tilt = d->s.camera_pan = 0;
	if (d->itf != NULL)
		send_camera(d);
}

#define CAMERA_TICK_MS 100

static void camera_tick(struct pomp_timer *timer, void *userdata)
{
	struct disco *d = userdata;
	float dt = CAMERA_TICK_MS / 1000.0f;
	float tilt = clampf(d->s.camera_tilt + d->cam_vel_tilt * dt, CAMERA_TILT_MIN, CAMERA_TILT_MAX);
	float pan = clampf(d->s.camera_pan + d->cam_vel_pan * dt, CAMERA_PAN_MIN, CAMERA_PAN_MAX);
	if (tilt == d->s.camera_tilt && pan == d->s.camera_pan)
		return;
	d->s.camera_tilt = tilt;
	d->s.camera_pan = pan;
	if (d->itf != NULL)
		send_camera(d);
}

/* deg/s, limited to the velocity range */
void disco_camera_velocity(struct disco *d, float tilt, float pan)
{
	d->cam_vel_tilt = clampf(tilt, -CAMERA_VELOCITY, CAMERA_VELOCITY);
	d->cam_vel_pan = clampf(pan, -CAMERA_VELOCITY, CAMERA_VELOCITY);
	if (d->cam_timer == NULL)
		return;
	if (d->cam_vel_tilt != 0 || d->cam_vel_pan != 0)
		pomp_timer_set_periodic(d->cam_timer, CAMERA_TICK_MS, CAMERA_TICK_MS);
	else
		pomp_timer_clear(d->cam_timer);
}

/* ------------------------------------------------------------------ */
/* Failures                                                            */
/* ------------------------------------------------------------------ */

static const struct { const char *name; uint32_t bit; } s_failures[] = {
	{ "motor", DISCO_FAIL_MOTOR },
	{ "magneto", DISCO_FAIL_MAGNETO },
	{ "pitot", DISCO_FAIL_PITOT },
	{ "overheat", DISCO_FAIL_OVERHEAT },
	{ "camera", DISCO_FAIL_CAMERA },
	{ "storage", DISCO_FAIL_STORAGE },
	{ "imu", DISCO_FAIL_IMU },
	{ "barometer", DISCO_FAIL_BAROMETER },
	{ "ultrasound", DISCO_FAIL_ULTRASOUND },
	{ "vertical_camera", DISCO_FAIL_VERTICAL_CAMERA },
	{ "link_weak", DISCO_FAIL_LINK_WEAK },
};

const char *disco_fail_names(void)
{
	return "motor magneto pitot overheat camera storage imu barometer "
		"ultrasound vertical_camera link_weak";
}

int disco_fail(struct disco *d, const char *name, int on)
{
	size_t i;
	uint32_t bit = 0;
	for (i = 0; i < sizeof(s_failures) / sizeof(s_failures[0]); i++) {
		if (strcmp(name, s_failures[i].name) == 0)
			bit = s_failures[i].bit;
	}
	if (bit == 0)
		return -EINVAL;
	if (on)
		d->failures |= bit;
	else
		d->failures &= ~bit;
	LOGI("failure %s %s", name, on ? "on" : "off");
	if (d->itf == NULL)
		return 0;
	switch (bit) {
	case DISCO_FAIL_MOTOR:
		send_motor(d);
		break;
	case DISCO_FAIL_MAGNETO:
		send_magneto_required(d);
		send_flight_plan(d);
		break;
	case DISCO_FAIL_PITOT:
		send_pitot(d);
		break;
	case DISCO_FAIL_OVERHEAT:
		if (on)
			SEND(arsdk_cmd_send_Common_OverHeatState_OverHeatChanged(ITF));
		break;
	case DISCO_FAIL_CAMERA:
	case DISCO_FAIL_STORAGE:
		if (on && d->recording) {
			/* recording stops on the failure */
			d->recording = 0;
			d->videos++;
			SEND(arsdk_cmd_send_Ardrone3_MediaRecordEvent_VideoEventChanged(ITF,
				ARSDK_ARDRONE3_MEDIARECORDEVENT_VIDEOEVENTCHANGED_EVENT_STOP,
				bit == DISCO_FAIL_STORAGE ?
				ARSDK_ARDRONE3_MEDIARECORDEVENT_VIDEOEVENTCHANGED_ERROR_MEMORYFULL :
				ARSDK_ARDRONE3_MEDIARECORDEVENT_VIDEOEVENTCHANGED_ERROR_NOTAVAILABLE));
		}
		send_media_states(d);
		send_storage(d);
		break;
	case DISCO_FAIL_LINK_WEAK:
		SEND(arsdk_cmd_send_Common_CommonState_WifiSignalChanged(ITF, on ? -88 : -45));
		break;
	default:
		send_sensors(d);
		break;
	}
	return 0;
}

void disco_flight_plan_stopped(struct disco *d)
{
	if (d->mavlink_state == ARSDK_COMMON_MAVLINKSTATE_MAVLINKFILEPLAYINGSTATECHANGED_STATE_STOPPED)
		return;
	d->mavlink_state = ARSDK_COMMON_MAVLINKSTATE_MAVLINKFILEPLAYINGSTATECHANGED_STATE_STOPPED;
	LOGI("flight plan stopped");
	if (d->itf != NULL)
		send_mavlink_state(d);
}

static const struct { const char *name; int32_t value; } alerts[] = {
	{ "none", ARSDK_ARDRONE3_PILOTINGSTATE_ALERTSTATECHANGED_STATE_NONE },
	{ "user", ARSDK_ARDRONE3_PILOTINGSTATE_ALERTSTATECHANGED_STATE_USER },
	{ "cut_out", ARSDK_ARDRONE3_PILOTINGSTATE_ALERTSTATECHANGED_STATE_CUT_OUT },
	{ "critical_battery", ARSDK_ARDRONE3_PILOTINGSTATE_ALERTSTATECHANGED_STATE_CRITICAL_BATTERY },
	{ "low_battery", ARSDK_ARDRONE3_PILOTINGSTATE_ALERTSTATECHANGED_STATE_LOW_BATTERY },
	{ "too_much_angle", ARSDK_ARDRONE3_PILOTINGSTATE_ALERTSTATECHANGED_STATE_TOO_MUCH_ANGLE },
	{ "almost_empty_battery", ARSDK_ARDRONE3_PILOTINGSTATE_ALERTSTATECHANGED_STATE_ALMOST_EMPTY_BATTERY },
};

/* "auto" goes back to alerts from the battery level. */
int disco_set_alert(struct disco *d, const char *name)
{
	size_t i;
	if (strcmp(name, "auto") == 0) {
		d->alert_forced = 0;
		return 0;
	}
	for (i = 0; i < sizeof(alerts) / sizeof(alerts[0]); i++) {
		if (strcmp(name, alerts[i].name) == 0)
			break;
	}
	if (i == sizeof(alerts) / sizeof(alerts[0]))
		return -EINVAL;
	d->alert_forced = 1;
	d->alert = alerts[i].value;
	if (d->itf != NULL)
		SEND(arsdk_cmd_send_Ardrone3_PilotingState_AlertStateChanged(ITF, d->alert));
	return 0;
}

static const char *alert_name(int32_t value)
{
	size_t i;
	for (i = 0; i < sizeof(alerts) / sizeof(alerts[0]); i++) {
		if (alerts[i].value == value)
			return alerts[i].name;
	}
	return "unknown";
}

int disco_status(const struct disco *d, char *buf, size_t len)
{
	const struct disco_settings *s = &d->s;
	int n = snprintf(buf, len,
			"name=%s max_alt=%.0f min_alt=%.0f max_dist=%.0f no_fly_over=%u "
			"circling=%s,%u pitch_mode=%s home_type=%s rth_delay=%u "
			"recording=%d photos=%u videos=%u flights=%u alert=%s "
			"magneto=%d pitot=%d tilt=%.0f pan=%.0f wifi=%d,%u fp=%s failures=0x%x "
			"country=%s,%s,%s factory=%s",
			s->name, s->max_altitude, s->min_altitude, s->max_distance,
			s->no_fly_over_max_distance,
			s->circling_direction ? "ccw" : "cw", s->circling_altitude,
			s->pitch_mode ? "inverted" : "normal",
			s->home_type ? "pilot" : "takeoff", s->return_home_delay,
			d->recording, d->photos, d->videos, d->flights,
			alert_name(d->alert), d->magneto_step, d->pitot_calibrating,
			s->camera_tilt, s->camera_pan, s->wifi_type, s->wifi_channel,
			d->mavlink_state == ARSDK_COMMON_MAVLINKSTATE_MAVLINKFILEPLAYINGSTATECHANGED_STATE_PLAYING ?
			"playing" : d->mavlink_state ==
			ARSDK_COMMON_MAVLINKSTATE_MAVLINKFILEPLAYINGSTATECHANGED_STATE_PAUSED ?
			"paused" : "stopped", d->failures,
			s->country, s->auto_country ? "auto" : "manual",
			s->outdoor ? "outdoor" : "indoor",
			d->factory_country[0] ? d->factory_country : "none");
	if (n >= 0 && (size_t)n + 1 < len) {
		buf[n++] = ' ';
		n += rc_status(&d->rc, buf + n, len - (size_t)n);
	}
	return n;
}

void disco_init(struct disco *d, struct pomp_loop *loop, const char *name)
{
	memset(d, 0, sizeof(*d));
	d->loop = loop;
	disco_reset_settings(&d->s, name);
	d->pilot_lat = d->pilot_lon = 500.0;
	d->alert = ARSDK_ARDRONE3_PILOTINGSTATE_ALERTSTATECHANGED_STATE_NONE;
	d->mavlink_state = ARSDK_COMMON_MAVLINKSTATE_MAVLINKFILEPLAYINGSTATECHANGED_STATE_STOPPED;
	snprintf(d->run_id, sizeof(d->run_id), "%08lX", (unsigned long)time(NULL));
	rc_init(&d->rc, loop);
	d->picture_timer = pomp_timer_new(loop, &picture_done, d);
	d->calib_timer = pomp_timer_new(loop, &calib_tick, d);
	d->cam_timer = pomp_timer_new(loop, &camera_tick, d);
}

void disco_clear(struct disco *d)
{
	if (d->picture_timer != NULL)
		pomp_timer_destroy(d->picture_timer);
	if (d->calib_timer != NULL)
		pomp_timer_destroy(d->calib_timer);
	if (d->cam_timer != NULL)
		pomp_timer_destroy(d->cam_timer);
	d->picture_timer = d->calib_timer = d->cam_timer = NULL;
	rc_clear(&d->rc);
}
