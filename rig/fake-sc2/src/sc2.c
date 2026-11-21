/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 *
 * fake-sc2: the SkyController 2's input mapper and settings (see sc2.h).
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>

#include "sc2.h"

#define ULOG_TAG sc2
#include "ulog.h"
ULOG_DECLARE_TAG(sc2);

#define LOGI(_fmt, ...)	ULOGI(_fmt, ##__VA_ARGS__)
#define LOGW(_fmt, ...)	ULOGW(_fmt, ##__VA_ARGS__)

#define SEND(_call) do { \
		int _res = (_call); \
		if (_res < 0) \
			LOGW("%s: %s", #_call, strerror(-_res)); \
	} while (0)
#define ITF c->itf, NULL, NULL

/* list_flags of the Mapper's list items */
#define LIST_FIRST  1
#define LIST_LAST   2
#define LIST_EMPTY  4

#define B(_n) (1u << (_n))
#define BA(_a) ARSDK_MAPPER_BUTTON_ACTION_##_a
#define AA(_a) ARSDK_MAPPER_AXIS_ACTION_##_a

/* Input bits (GroundSdk's Sc2Gamepad): buttons 0-6, the right slider's ends
 * as buttons 10-11, the sticks' directions 12-19, the left slider's ends
 * 20-21; axes 0-5. */
static const struct { const char *name; int bit; } s_buttons[] = {
	{ "settings", 0 }, { "rth", 1 }, { "takeoff", 2 }, { "b", 3 },
	{ "a", 4 }, { "rear_left", 5 }, { "rear_right", 6 },
	{ "right_slider_down", 10 }, { "right_slider_up", 11 },
	{ "left_slider_down", 20 }, { "left_slider_up", 21 },
};

static const char *const s_axes[SC2_AXES] = {
	"left_h", "left_v", "right_h", "right_v", "left_slider", "right_slider",
};

/*
 * The Disco's default mapping (assumed, see sc2.h): mode 2 sticks (left:
 * throttle and the loiter direction, right: roll and pitch), the left
 * slider tilts the camera, the right slider's ends set the exposure, the
 * front buttons take a picture and record, return home and take-off/land
 * on their labelled buttons, the others for the app.
 */
static const struct sc2_mapping s_disco_default[] = {
	{ 0, AA(ROLL), 2, 0 },
	{ 0, AA(PITCH), 3, 0 },
	{ 0, AA(YAW), 0, 0 },
	{ 0, AA(GAZ), 1, 0 },
	{ 0, AA(CAMERA_TILT), 4, 0 },
	{ 0, BA(RETURN_HOME), -1, B(1) },
	{ 0, BA(TAKEOFF_LAND), -1, B(2) },
	{ 0, BA(TAKE_PICTURE), -1, B(5) },
	{ 0, BA(VIDEO_RECORD), -1, B(6) },
	{ 0, BA(CAMERA_EXPOSITION_DEC), -1, B(10) },
	{ 0, BA(CAMERA_EXPOSITION_INC), -1, B(11) },
	{ 0, BA(APP_0), -1, B(0) },
	{ 0, BA(APP_1), -1, B(3) },
	{ 0, BA(APP_2), -1, B(4) },
};

int sc2_button_bit(const char *name)
{
	size_t i;
	for (i = 0; i < sizeof(s_buttons) / sizeof(s_buttons[0]); i++) {
		if (strcmp(name, s_buttons[i].name) == 0)
			return s_buttons[i].bit;
	}
	return -1;
}

int sc2_axis_index(const char *name)
{
	int i;
	for (i = 0; i < SC2_AXES; i++) {
		if (strcmp(name, s_axes[i]) == 0)
			return i;
	}
	return -1;
}

void sc2_reset_mapping(struct sc2 *c)
{
	size_t i;
	c->nmap = 0;
	for (i = 0; i < sizeof(s_disco_default) / sizeof(s_disco_default[0]); i++) {
		c->map[c->nmap] = s_disco_default[i];
		c->map[c->nmap].uid = c->next_uid++;
		c->nmap++;
	}
	for (i = 0; i < SC2_AXES; i++) {
		c->expo[i] = ARSDK_MAPPER_EXPO_TYPE_LINEAR;
		c->inverted[i] = 0;
	}
	/* sticks: a little expo, as Parrot's defaults */
	c->expo[0] = c->expo[1] = c->expo[2] = c->expo[3] = ARSDK_MAPPER_EXPO_TYPE_EXPO_2;
}

void sc2_init(struct sc2 *c, uint16_t product, const char *ssid,
		const struct sc2_ops *ops)
{
	memset(c, 0, sizeof(*c));
	c->product = product;
	c->next_uid = 1;
	if (ops != NULL)
		c->ops = *ops;
	sc2_reset_mapping(c);
	snprintf(c->ap_ssid, sizeof(c->ap_ssid), "%s", ssid);
	c->ap_channel = 6;
	c->ap_band = ARSDK_SKYCTRL_ACCESSPOINTSETTINGSSTATE_WIFISELECTIONCHANGED_BAND_2_4GHZ;
	c->ap_security = 0; /* open */
	c->piloting_source = ARSDK_SKYCTRL_COPILOTINGSTATE_PILOTINGSOURCE_SOURCE_SKYCONTROLLER;
}

/* ------------------------------------------------------------------ */
/* Mapper lists                                                        */
/* ------------------------------------------------------------------ */

static void send_mappings(struct sc2 *c)
{
	int i, nb = 0, na = 0, ib = 0, ia = 0;
	uint8_t flags;
	for (i = 0; i < c->nmap; i++) {
		if (c->map[i].axis < 0)
			nb++;
		else
			na++;
	}
	if (nb == 0)
		SEND(arsdk_cmd_send_Mapper_Button_mapping_item(ITF, 0, 0, 0, 0, LIST_EMPTY));
	if (na == 0)
		SEND(arsdk_cmd_send_Mapper_Axis_mapping_item(ITF, 0, 0, 0, 0, 0, LIST_EMPTY));
	for (i = 0; i < c->nmap; i++) {
		const struct sc2_mapping *m = &c->map[i];
		if (m->axis < 0) {
			flags = (ib == 0 ? LIST_FIRST : 0) | (ib == nb - 1 ? LIST_LAST : 0);
			ib++;
			SEND(arsdk_cmd_send_Mapper_Button_mapping_item(ITF, m->uid,
					c->product, m->action, m->buttons, flags));
		} else {
			flags = (ia == 0 ? LIST_FIRST : 0) | (ia == na - 1 ? LIST_LAST : 0);
			ia++;
			SEND(arsdk_cmd_send_Mapper_Axis_mapping_item(ITF, m->uid,
					c->product, m->action, m->axis, m->buttons, flags));
		}
	}
}

static void send_expo_inverted(struct sc2 *c)
{
	int i;
	for (i = 0; i < SC2_AXES; i++) {
		uint8_t flags = (i == 0 ? LIST_FIRST : 0) | (i == SC2_AXES - 1 ? LIST_LAST : 0);
		SEND(arsdk_cmd_send_Mapper_Expo_map_item(ITF, (uint32_t)i + 1,
				c->product, i, c->expo[i], flags));
	}
	for (i = 0; i < SC2_AXES; i++) {
		uint8_t flags = (i == 0 ? LIST_FIRST : 0) | (i == SC2_AXES - 1 ? LIST_LAST : 0);
		SEND(arsdk_cmd_send_Mapper_Inverted_map_item(ITF, (uint32_t)i + 1,
				c->product, i, c->inverted[i], flags));
	}
}

void sc2_send_states(struct sc2 *c)
{
	if (c->itf == NULL)
		return;
	SEND(arsdk_cmd_send_Mapper_Active_product(ITF, c->product));
	send_mappings(c);
	send_expo_inverted(c);
	SEND(arsdk_cmd_send_Mapper_Grab_state(ITF, c->grabbed_buttons,
			c->grabbed_axes, c->buttons_state));
	SEND(arsdk_cmd_send_Skyctrl_AccessPointSettingsState_AccessPointSSIDChanged(ITF,
			c->ap_ssid));
	SEND(arsdk_cmd_send_Skyctrl_AccessPointSettingsState_AccessPointChannelChanged(ITF,
			c->ap_channel));
	SEND(arsdk_cmd_send_Skyctrl_AccessPointSettingsState_WifiSelectionChanged(ITF,
			ARSDK_SKYCTRL_ACCESSPOINTSETTINGSSTATE_WIFISELECTIONCHANGED_TYPE_MANUAL,
			c->ap_band, c->ap_channel));
	SEND(arsdk_cmd_send_Skyctrl_AccessPointSettingsState_WifiSecurityChanged(ITF,
			c->ap_security, c->ap_key));
	SEND(arsdk_cmd_send_Skyctrl_CoPilotingState_PilotingSource(ITF,
			c->piloting_source));
	SEND(arsdk_cmd_send_Skyctrl_CalibrationState_MagnetoCalibrationQualityUpdatesState(ITF,
			c->quality_updates));
	SEND(arsdk_cmd_send_Skyctrl_WifiState_WifiCountryChanged(ITF, "US"));
	SEND(arsdk_cmd_send_Skyctrl_WifiState_WifiEnvironmentChanged(ITF, 1 /* outdoor */));
}

static struct sc2_mapping *find_action(struct sc2 *c, int32_t action, int axis_kind)
{
	int i;
	for (i = 0; i < c->nmap; i++) {
		if (c->map[i].action == action && (c->map[i].axis >= 0) == axis_kind)
			return &c->map[i];
	}
	return NULL;
}

/* Maps an action to inputs: replaces its previous mapping; buttons 0
 * (and no axis) removes it. */
static void map_action(struct sc2 *c, int32_t action, int32_t axis,
		uint32_t buttons)
{
	struct sc2_mapping *m = find_action(c, action, axis >= 0);
	if (m == NULL) {
		if (c->nmap == SC2_MAX_MAPPINGS || (axis < 0 && buttons == 0))
			return;
		m = &c->map[c->nmap++];
		m->uid = c->next_uid++;
		m->action = action;
	}
	if (axis < 0 && buttons == 0) {
		*m = c->map[--c->nmap];
		return;
	}
	m->axis = axis;
	m->buttons = buttons;
}

/* ------------------------------------------------------------------ */
/* Commands                                                            */
/* ------------------------------------------------------------------ */

int sc2_recv_cmd(struct sc2 *c, const struct arsdk_cmd *cmd)
{
	uint16_t product = 0;
	int32_t action = 0, axis = 0, i32 = 0;
	uint32_t buttons = 0, axes = 0;
	uint8_t u8 = 0;
	const char *str = NULL;

	if (c->itf == NULL)
		return 0;

	switch (cmd->id) {
	case ARSDK_ID_MAPPER_GRAB:
		if (arsdk_cmd_dec_Mapper_Grab(cmd, &buttons, &axes) < 0)
			return 1;
		c->grabbed_buttons = buttons;
		c->grabbed_axes = axes;
		LOGI("grabbed buttons 0x%x axes 0x%x", buttons, axes);
		SEND(arsdk_cmd_send_Mapper_Grab_state(ITF, buttons, axes,
				c->buttons_state));
		return 1;
	case ARSDK_ID_MAPPER_MAP_BUTTON_ACTION:
		if (arsdk_cmd_dec_Mapper_Map_button_action(cmd, &product, &action,
				&buttons) < 0)
			return 1;
		if (product == c->product)
			map_action(c, action, -1, buttons);
		send_mappings(c);
		return 1;
	case ARSDK_ID_MAPPER_MAP_AXIS_ACTION:
		if (arsdk_cmd_dec_Mapper_Map_axis_action(cmd, &product, &action,
				&axis, &buttons) < 0)
			return 1;
		if (product == c->product && axis >= -1 && axis < SC2_AXES)
			map_action(c, action, axis, buttons);
		send_mappings(c);
		return 1;
	case ARSDK_ID_MAPPER_RESET_MAPPING:
		if (arsdk_cmd_dec_Mapper_Reset_mapping(cmd, &product) < 0)
			return 1;
		LOGI("mapping reset (product 0x%04x)", product);
		if (product == c->product || product == 0)
			sc2_reset_mapping(c);
		send_mappings(c);
		send_expo_inverted(c);
		return 1;
	case ARSDK_ID_MAPPER_SET_EXPO:
		if (arsdk_cmd_dec_Mapper_Set_expo(cmd, &product, &axis, &i32) < 0)
			return 1;
		if (product == c->product && axis >= 0 && axis < SC2_AXES)
			c->expo[axis] = i32;
		send_expo_inverted(c);
		return 1;
	case ARSDK_ID_MAPPER_SET_INVERTED:
		if (arsdk_cmd_dec_Mapper_Set_inverted(cmd, &product, &axis, &u8) < 0)
			return 1;
		if (product == c->product && axis >= 0 && axis < SC2_AXES)
			c->inverted[axis] = u8 != 0;
		send_expo_inverted(c);
		return 1;
	case ARSDK_ID_SKYCTRL_ACCESSPOINTSETTINGS_ACCESSPOINTSSID:
		if (arsdk_cmd_dec_Skyctrl_AccessPointSettings_AccessPointSSID(cmd, &str) >= 0) {
			snprintf(c->ap_ssid, sizeof(c->ap_ssid), "%s", str);
			SEND(arsdk_cmd_send_Skyctrl_AccessPointSettingsState_AccessPointSSIDChanged(ITF,
					c->ap_ssid));
		}
		return 1;
	case ARSDK_ID_SKYCTRL_ACCESSPOINTSETTINGS_ACCESSPOINTCHANNEL:
		if (arsdk_cmd_dec_Skyctrl_AccessPointSettings_AccessPointChannel(cmd, &u8) >= 0) {
			c->ap_channel = u8;
			SEND(arsdk_cmd_send_Skyctrl_AccessPointSettingsState_AccessPointChannelChanged(ITF,
					c->ap_channel));
		}
		return 1;
	case ARSDK_ID_SKYCTRL_ACCESSPOINTSETTINGS_WIFISELECTION:
		if (arsdk_cmd_dec_Skyctrl_AccessPointSettings_WifiSelection(cmd, &i32,
				&action, &u8) >= 0) {
			c->ap_band = action;
			c->ap_channel = u8;
			SEND(arsdk_cmd_send_Skyctrl_AccessPointSettingsState_WifiSelectionChanged(ITF,
					i32, c->ap_band, c->ap_channel));
		}
		return 1;
	case ARSDK_ID_SKYCTRL_ACCESSPOINTSETTINGS_WIFISECURITY:
		if (arsdk_cmd_dec_Skyctrl_AccessPointSettings_WifiSecurity(cmd, &i32, &str) >= 0) {
			c->ap_security = i32;
			snprintf(c->ap_key, sizeof(c->ap_key), "%s", str);
			SEND(arsdk_cmd_send_Skyctrl_AccessPointSettingsState_WifiSecurityChanged(ITF,
					c->ap_security, c->ap_key));
		}
		return 1;
	case ARSDK_ID_SKYCTRL_COPILOTING_SETPILOTINGSOURCE:
		if (arsdk_cmd_dec_Skyctrl_CoPiloting_SetPilotingSource(cmd, &i32) >= 0) {
			c->piloting_source = i32;
			SEND(arsdk_cmd_send_Skyctrl_CoPilotingState_PilotingSource(ITF, i32));
		}
		return 1;
	case ARSDK_ID_SKYCTRL_CALIBRATION_ENABLEMAGNETOCALIBRATIONQUALITYUPDATES:
		if (arsdk_cmd_dec_Skyctrl_Calibration_EnableMagnetoCalibrationQualityUpdates(cmd, &u8) >= 0) {
			c->quality_updates = u8 != 0;
			SEND(arsdk_cmd_send_Skyctrl_CalibrationState_MagnetoCalibrationQualityUpdatesState(ITF,
					c->quality_updates));
		}
		return 1;
	default:
		return 0;
	}
}

/* ------------------------------------------------------------------ */
/* Inputs                                                              */
/* ------------------------------------------------------------------ */

/* Expo curves: LINEAR, then EXPO_0 (light) to EXPO_4 (strong). */
static int apply_expo(int32_t expo, int value)
{
	static const double k[] = { 0, 0.2, 0.4, 0.6, 0.8 };
	double v = value / 100.0, a;
	if (expo <= 0 || expo > 4)
		return value;
	a = k[expo];
	return (int)((v * (1 - a) + v * v * v * a) * 100);
}

int sc2_button(struct sc2 *c, uint32_t bit, int pressed)
{
	uint32_t mask = 1u << bit;
	int i;

	if (pressed)
		c->buttons_state |= mask;
	else
		c->buttons_state &= ~mask;

	if (c->itf != NULL && (c->grabbed_buttons & mask)) {
		SEND(arsdk_cmd_send_Mapper_Grab_button_event(ITF, bit, pressed ?
				ARSDK_MAPPER_BUTTON_EVENT_PRESS :
				ARSDK_MAPPER_BUTTON_EVENT_RELEASE));
		return 0;
	}
	for (i = 0; i < c->nmap; i++) {
		const struct sc2_mapping *m = &c->map[i];
		if (m->axis >= 0 || m->buttons != mask)
			continue;
		if (!pressed)
			return 0; /* actions fire on the press */
		LOGI("button %u: action %d", bit, m->action);
		if (m->action <= ARSDK_MAPPER_BUTTON_ACTION_APP_15) {
			if (c->itf == NULL)
				return -ENOTCONN;
			SEND(arsdk_cmd_send_Mapper_Application_button_event(ITF, m->action));
		} else if (c->ops.button_action != NULL) {
			c->ops.button_action(c->ops.userdata, m->action);
		}
		return 0;
	}
	return -ENOENT;
}

int sc2_axis(struct sc2 *c, int axis, int value)
{
	int i, v;
	if (axis < 0 || axis >= SC2_AXES)
		return -EINVAL;
	value = value < -100 ? -100 : value > 100 ? 100 : value;
	c->axis_value[axis] = value;
	if (c->itf != NULL && (c->grabbed_axes & (1u << axis))) {
		SEND(arsdk_cmd_send_Mapper_Grab_axis_event(ITF, (uint32_t)axis,
				(int8_t)value));
		return 0;
	}
	v = c->inverted[axis] ? -value : value;
	v = apply_expo(c->expo[axis], v);
	for (i = 0; i < c->nmap; i++) {
		const struct sc2_mapping *m = &c->map[i];
		if (m->axis != axis || (m->buttons & ~c->buttons_state) != 0)
			continue;
		if (m->action < ARSDK_MAPPER_AXIS_ACTION_ROLL) {
			if (c->itf != NULL)
				SEND(arsdk_cmd_send_Mapper_Application_axis_event(ITF,
						m->action, (int8_t)v));
		} else if (c->ops.axis_action != NULL) {
			c->ops.axis_action(c->ops.userdata, m->action, v);
		}
		return 0;
	}
	return -ENOENT;
}

int sc2_status(const struct sc2 *c, char *buf, size_t len)
{
	int i, n;
	n = snprintf(buf, len, "grabbed=0x%x,0x%x pressed=0x%x ssid=%s ch=%u source=%d map=",
			c->grabbed_buttons, c->grabbed_axes, c->buttons_state,
			c->ap_ssid, c->ap_channel, c->piloting_source);
	for (i = 0; i < c->nmap && n > 0 && (size_t)n < len; i++) {
		const struct sc2_mapping *m = &c->map[i];
		if (m->axis >= 0)
			n += snprintf(buf + n, len - (size_t)n, "%sa%d:%d", i ? "," : "",
					m->axis, m->action);
		else
			n += snprintf(buf + n, len - (size_t)n, "%sb%x:%d", i ? "," : "",
					m->buttons, m->action);
	}
	if (n > 0 && (size_t)n < len)
		n += snprintf(buf + n, len - (size_t)n, " axes=%d,%d,%d,%d,%d,%d",
				c->axis_value[0], c->axis_value[1], c->axis_value[2],
				c->axis_value[3], c->axis_value[4], c->axis_value[5]);
	return n;
}
