/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 *
 * fake-sc2: the Disco's RC receiver (see rc.h).
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <math.h>

#include <libpomp.h>
#include "arsdk/arsdk.h"
#include "rc.h"

#define ULOG_TAG rc_receiver
#include "ulog.h"
ULOG_DECLARE_TAG(rc_receiver);

#define LOGI(_fmt, ...)	ULOGI(_fmt, ##__VA_ARGS__)
#define LOGW(_fmt, ...)	ULOGW(_fmt, ##__VA_ARGS__)

#define SEND(_call) do { \
		int _res = (_call); \
		if (_res < 0) \
			LOGW("%s: %s", #_call, strerror(-_res)); \
	} while (0)
#define ITF rc->itf, NULL, NULL

/* list_flags of map items */
#define LIST_FIRST  1
#define LIST_LAST   2

#define B(_n) (1u << (_n))
#define ACT(_a) ARSDK_RC_CHANNEL_ACTION_##_a
#define TYPE(_t) ARSDK_RC_CHANNEL_TYPE_##_t

#define MONITOR_MS     5000  /* the whole list again while monitored */
#define NEUTRAL_MS     1000  /* neutral calibration */
#define FULL_TRAVEL    60    /* a stick seen beyond +-60 % both ways */
#define SWITCH_TRAVEL  60    /* a switch moved by 60 % */
#define TAKE_CONTROL_ON 50   /* the take-control switch, on */

/* What each action accepts (bitfield of channel types). */
static uint32_t supported_types(int32_t action)
{
	switch (action) {
	case ACT(ROLL):
	case ACT(PITCH):
	case ACT(YAW):
		return B(TYPE(SIGNED_AXIS));
	case ACT(GAZ):
		return B(TYPE(SIGNED_AXIS)) | B(TYPE(UNSIGNED_AXIS));
	case ACT(TAKEOFF_LAND):
	case ACT(EMERGENCY):
	case ACT(RETURN_HOME):
		return B(TYPE(MONOSTABLE_BUTTON)) | B(TYPE(BISTABLE_BUTTON));
	case ACT(PILOTING_MODE):
		return B(TYPE(BISTABLE_BUTTON)) | B(TYPE(TRISTATE_BUTTON)) | B(TYPE(ROTARY_BUTTON));
	case ACT(TAKE_CONTROL):
		return B(TYPE(BISTABLE_BUTTON)) | B(TYPE(TRISTATE_BUTTON));
	default:
		return 0;
	}
}

/* The actions to calibrate before the radio can be enabled. */
static uint32_t required_actions(void)
{
	return B(ACT(ROLL)) | B(ACT(PITCH)) | B(ACT(GAZ)) | B(ACT(TAKE_CONTROL));
}

static uint32_t calibrated_actions(const struct disco_rc *rc)
{
	uint32_t bits = 0;
	int a;
	for (a = 1; a < RC_ACTIONS; a++) {
		if (rc->act[a].channel >= 0)
			bits |= B(a);
	}
	return bits;
}

static int ready(const struct disco_rc *rc)
{
	uint32_t req = required_actions();
	return rc->neutral_done && (calibrated_actions(rc) & req) == req;
}

/* ------------------------------------------------------------------ */
/* Events                                                              */
/* ------------------------------------------------------------------ */

static void send_receiver(struct disco_rc *rc)
{
	if (rc->itf == NULL)
		return;
	SEND(arsdk_cmd_send_Rc_Receiver_state(ITF,
			rc->connected ? ARSDK_RC_RECEIVER_STATE_CONNECTED
				: ARSDK_RC_RECEIVER_STATE_DISCONNECTED,
			rc->connected ? rc->protocol : "", (uint8_t)rc->enabled));
}

static void send_calibration(struct disco_rc *rc)
{
	if (rc->itf == NULL)
		return;
	SEND(arsdk_cmd_send_Rc_Calibration_state(ITF, rc->cal_type,
			rc->cal_type == ARSDK_RC_CALIBRATION_TYPE_MIN_MAX ?
				rc->cal_action : ACT(INVALID),
			required_actions(), calibrated_actions(rc),
			(uint8_t)rc->neutral_done));
}

static void send_actions(struct disco_rc *rc)
{
	int a;
	if (rc->itf == NULL)
		return;
	/* a map keyed by action (no list flags in rc.xml) */
	for (a = 1; a < RC_ACTIONS; a++) {
		SEND(arsdk_cmd_send_Rc_Channel_action_item(ITF, a, supported_types(a),
				rc->act[a].channel >= 0 ? rc->act[a].type : TYPE(INVALID),
				rc->act[a].inverted));
	}
}

/* The channel's action (the first one calibrated on it), INVALID if none. */
static int32_t action_of(const struct disco_rc *rc, int channel)
{
	int a;
	for (a = 1; a < RC_ACTIONS; a++) {
		if (rc->act[a].channel == channel)
			return a;
	}
	return ACT(INVALID);
}

static void send_channels(struct disco_rc *rc)
{
	int c;
	if (rc->itf == NULL || !rc->connected)
		return;
	for (c = 0; c < RC_CHANNELS; c++) {
		uint8_t flags = (c == 0 ? LIST_FIRST : 0) | (c == RC_CHANNELS - 1 ? LIST_LAST : 0);
		SEND(arsdk_cmd_send_Rc_Channel_value(ITF, (uint8_t)(c + 1),
				action_of(rc, c), rc->ch[c], flags));
	}
}

/* While monitored: the whole list at the start and every MONITOR_MS, and
 * each channel as it moves (a single map item). Channel values are
 * acknowledged events: sending them all at a high rate would queue
 * acknowledgements faster than a slow link turns them round. */
static void monitor_tick(struct pomp_timer *timer, void *userdata)
{
	send_channels(userdata);
}

static void send_channel(struct disco_rc *rc, int c)
{
	if (rc->itf == NULL || !rc->connected || !rc->monitoring)
		return;
	SEND(arsdk_cmd_send_Rc_Channel_value(ITF, (uint8_t)(c + 1), action_of(rc, c), rc->ch[c], 0));
}

void rc_send_states(struct disco_rc *rc)
{
	if (rc->itf == NULL)
		return;
	send_receiver(rc);
	send_actions(rc);
	send_calibration(rc);
	SEND(arsdk_cmd_send_Rc_Channels_monitor_state(ITF, (uint8_t)rc->monitoring));
}

/* ------------------------------------------------------------------ */
/* Calibration                                                         */
/* ------------------------------------------------------------------ */

static void calibration_done(struct disco_rc *rc)
{
	rc->cal_type = ARSDK_RC_CALIBRATION_TYPE_NONE;
	pomp_timer_clear(rc->cal_timer);
	send_calibration(rc);
	send_actions(rc);
}

static void neutral_done(struct pomp_timer *timer, void *userdata)
{
	struct disco_rc *rc = userdata;
	if (rc->cal_type != ARSDK_RC_CALIBRATION_TYPE_NEUTRAL)
		return;
	memcpy(rc->neutral, rc->ch, sizeof(rc->neutral));
	rc->neutral_done = 1;
	LOGI("neutral calibrated");
	calibration_done(rc);
}

/* During a min/max calibration, a channel moved: the one that travelled
 * fully (an axis both ways, a switch by SWITCH_TRAVEL) is the action's. */
static void calibration_watch(struct disco_rc *rc, int c)
{
	int16_t d = (int16_t)(rc->ch[c] - rc->neutral[c]);
	int done;
	if (d < rc->cal_lo[c])
		rc->cal_lo[c] = d;
	if (d > rc->cal_hi[c])
		rc->cal_hi[c] = d;
	if (rc->cal_channel_type == TYPE(SIGNED_AXIS))
		/* centred stick: both ways */
		done = rc->cal_lo[c] <= -FULL_TRAVEL && rc->cal_hi[c] >= FULL_TRAVEL;
	else if (rc->cal_channel_type == TYPE(UNSIGNED_AXIS))
		/* a throttle at rest at one end: across its whole travel */
		done = rc->cal_hi[c] - rc->cal_lo[c] >= 2 * FULL_TRAVEL;
	else
		done = rc->cal_hi[c] - rc->cal_lo[c] >= SWITCH_TRAVEL;
	if (!done)
		return;
	rc->act[rc->cal_action].channel = c;
	rc->act[rc->cal_action].type = rc->cal_channel_type;
	LOGI("action %d on channel %d", rc->cal_action, c + 1);
	calibration_done(rc);
}

static void start_calibration(struct disco_rc *rc, int32_t type, int32_t action, int32_t channel_type)
{
	if (!rc->connected || rc->enabled) {
		LOGW("calibration refused: %s", !rc->connected ? "no receiver" : "receiver enabled");
		send_calibration(rc);
		return;
	}
	if (type == ARSDK_RC_CALIBRATION_TYPE_NEUTRAL) {
		rc->cal_type = type;
		send_calibration(rc);
		pomp_timer_set(rc->cal_timer, NEUTRAL_MS);
		return;
	}
	if (type != ARSDK_RC_CALIBRATION_TYPE_MIN_MAX || action <= 0 || action >= RC_ACTIONS
			|| !rc->neutral_done || !(supported_types(action) & B(channel_type))) {
		LOGW("calibration refused: type %d action %d channel type %d%s", type, action,
				channel_type, rc->neutral_done ? "" : " (neutral first)");
		send_calibration(rc);
		return;
	}
	rc->cal_type = type;
	rc->cal_action = action;
	rc->cal_channel_type = channel_type;
	memset(rc->cal_lo, 0, sizeof(rc->cal_lo));
	memset(rc->cal_hi, 0, sizeof(rc->cal_hi));
	send_calibration(rc);
}

static void reset_calibration(struct disco_rc *rc)
{
	int a;
	rc->neutral_done = 0;
	for (a = 0; a < RC_ACTIONS; a++) {
		rc->act[a].channel = -1;
		rc->act[a].type = TYPE(INVALID);
		rc->act[a].inverted = 0;
	}
	rc->enabled = 0;
}

/* ------------------------------------------------------------------ */
/* Commands                                                            */
/* ------------------------------------------------------------------ */

int rc_recv_cmd(struct disco_rc *rc, const struct arsdk_cmd *cmd)
{
	uint8_t u8 = 0;
	int32_t i32 = 0, i32b = 0, i32c = 0;

	if (rc->itf == NULL)
		return 0;
	switch (cmd->id) {
	case ARSDK_ID_RC_MONITOR_CHANNELS:
		if (arsdk_cmd_dec_Rc_Monitor_channels(cmd, &u8) >= 0) {
			rc->monitoring = u8 != 0;
			if (rc->monitoring) {
				send_channels(rc);
				pomp_timer_set_periodic(rc->monitor_timer, MONITOR_MS, MONITOR_MS);
			}
			else
				pomp_timer_clear(rc->monitor_timer);
			SEND(arsdk_cmd_send_Rc_Channels_monitor_state(ITF, (uint8_t)rc->monitoring));
		}
		return 1;
	case ARSDK_ID_RC_START_CALIBRATION:
		if (arsdk_cmd_dec_Rc_Start_calibration(cmd, &i32, &i32b, &i32c) >= 0)
			start_calibration(rc, i32, i32b, i32c);
		return 1;
	case ARSDK_ID_RC_ABORT_CALIBRATION:
		calibration_done(rc);
		return 1;
	case ARSDK_ID_RC_RESET_CALIBRATION:
		reset_calibration(rc);
		rc->cal_type = ARSDK_RC_CALIBRATION_TYPE_NONE;
		send_receiver(rc);
		send_calibration(rc);
		send_actions(rc);
		return 1;
	case ARSDK_ID_RC_INVERT_CHANNEL:
		if (arsdk_cmd_dec_Rc_Invert_channel(cmd, &i32, &u8) >= 0 && i32 > 0 && i32 < RC_ACTIONS) {
			rc->act[i32].inverted = u8 != 0;
			send_actions(rc);
		}
		return 1;
	case ARSDK_ID_RC_ENABLE_RECEIVER:
		if (arsdk_cmd_dec_Rc_Enable_receiver(cmd, &u8) >= 0) {
			if (!u8) {
				rc->enabled = 0;
			} else if (!rc->landed) {
				/* as the Disco: "Could not set RC receiver state:
				 * drone is not landed" */
				LOGW("Could not set RC receiver state: drone is not landed");
			} else if (!rc->connected || !ready(rc)) {
				LOGW("RC receiver not enabled: %s", !rc->connected ?
						"no receiver" : "calibration missing");
			} else {
				rc->enabled = 1;
			}
			LOGI("receiver %s", rc->enabled ? "enabled" : "disabled");
			send_receiver(rc);
		}
		return 1;
	default:
		return 0;
	}
}

/* ------------------------------------------------------------------ */
/* Flight and control socket                                           */
/* ------------------------------------------------------------------ */

void rc_landed(struct disco_rc *rc, int landed)
{
	rc->landed = landed;
}

/* The action's value, -1..1, inversion and neutral applied. */
static double action_value(const struct disco_rc *rc, int32_t action)
{
	const struct rc_action *a = &rc->act[action];
	double v;
	if (a->channel < 0)
		return 0;
	v = (rc->ch[a->channel] - rc->neutral[a->channel]) / 100.0;
	if (a->inverted)
		v = -v;
	/* a throttle from its rest (0..2 of travel) to -1..1 */
	if (a->type == TYPE(UNSIGNED_AXIS))
		v = fabs(v) - 1;
	return fmax(-1, fmin(1, v));
}

int rc_piloting(const struct disco_rc *rc, double *roll, double *pitch, double *gaz)
{
	if (!rc->enabled || !rc->connected)
		return 0;
	if (rc->act[ACT(TAKE_CONTROL)].channel >= 0
			&& action_value(rc, ACT(TAKE_CONTROL)) * 100 < TAKE_CONTROL_ON)
		return 0;
	*roll = action_value(rc, ACT(ROLL));
	*pitch = action_value(rc, ACT(PITCH));
	*gaz = action_value(rc, ACT(GAZ));
	return 1;
}

static void set_channel(struct disco_rc *rc, int c, int value)
{
	int16_t v = (int16_t)(value < -100 ? -100 : value > 100 ? 100 : value);
	if (v == rc->ch[c])
		return;
	rc->ch[c] = v;
	send_channel(rc, c);
	if (rc->cal_type == ARSDK_RC_CALIBRATION_TYPE_MIN_MAX)
		calibration_watch(rc, c);
}

int rc_status(const struct disco_rc *rc, char *buf, size_t len)
{
	double r, p, g;
	int on = rc_piloting(rc, &r, &p, &g);
	return snprintf(buf, len, "rc=%s,%s,%s,cal=0x%x%s,piloting=%d",
			rc->connected ? rc->protocol : "none",
			rc->enabled ? "enabled" : "disabled",
			rc->monitoring ? "monitored" : "quiet",
			calibrated_actions(rc), rc->neutral_done ? "+neutral" : "", on);
}

int rc_ctrl(struct disco_rc *rc, const char *line, char *reply, size_t len)
{
	char what[16] = "", arg[16] = "";
	int a = 0, b = 0, c = 0, d = -1000;
	int n = sscanf(line, "%*s %15s %15s", what, arg);

	if (n < 1) {
		char st[160];
		rc_status(rc, st, sizeof(st));
		return snprintf(reply, len, "ok %s\n", st);
	}
	if (strcmp(what, "connect") == 0 && n == 2) {
		size_t i;
		for (i = 0; arg[i] != '\0' && i < sizeof(rc->protocol) - 1; i++)
			rc->protocol[i] = (char)(arg[i] >= 'a' && arg[i] <= 'z' ? arg[i] - 32 : arg[i]);
		rc->protocol[i] = '\0';
		rc->connected = 1;
		LOGI("receiver %s connected", rc->protocol);
		send_receiver(rc);
	} else if (strcmp(what, "reset") == 0) {
		/* a fresh receiver: nothing calibrated, radio off, not monitored */
		reset_calibration(rc);
		rc->cal_type = ARSDK_RC_CALIBRATION_TYPE_NONE;
		rc->monitoring = 0;
		pomp_timer_clear(rc->monitor_timer);
		pomp_timer_clear(rc->cal_timer);
		memset(rc->ch, 0, sizeof(rc->ch));
		rc_send_states(rc);
	} else if (strcmp(what, "disconnect") == 0) {
		rc->connected = 0;
		LOGI("receiver lost");
		send_receiver(rc);
	} else if (strcmp(what, "ch") == 0
			&& sscanf(line, "%*s %*s %d %d", &a, &b) == 2 && a >= 1 && a <= RC_CHANNELS) {
		set_channel(rc, a - 1, b);
	} else if (strcmp(what, "sticks") == 0
			&& sscanf(line, "%*s %*s %d %d %d %d", &a, &b, &c, &d) >= 3) {
		/* on the channels calibrated for them, as the radio would */
		int32_t acts[] = { ACT(ROLL), ACT(PITCH), ACT(GAZ), ACT(TAKE_CONTROL) };
		int vals[] = { a, b, c, d };
		int i;
		for (i = 0; i < 4; i++) {
			const struct rc_action *x = &rc->act[acts[i]];
			if (x->channel < 0 || (i == 3 && d == -1000))
				continue;
			set_channel(rc, x->channel, rc->neutral[x->channel]
					+ (x->inverted ? -vals[i] : vals[i]));
		}
	} else {
		return snprintf(reply, len, "error rc [connect <sbus|sumd>|disconnect|reset|ch <1..%d> <-100..100>|"
				"sticks <roll> <pitch> <gaz> [control]]\n", RC_CHANNELS);
	}
	return snprintf(reply, len, "ok\n");
}

void rc_set_itf(struct disco_rc *rc, struct arsdk_cmd_itf *itf)
{
	rc->itf = itf;
	if (itf == NULL) {
		rc->monitoring = 0;
		pomp_timer_clear(rc->monitor_timer);
	}
}

void rc_init(struct disco_rc *rc, struct pomp_loop *loop)
{
	memset(rc, 0, sizeof(*rc));
	rc->loop = loop;
	rc->landed = 1;
	rc->cal_type = ARSDK_RC_CALIBRATION_TYPE_NONE;
	reset_calibration(rc);
	rc->monitor_timer = pomp_timer_new(loop, &monitor_tick, rc);
	rc->cal_timer = pomp_timer_new(loop, &neutral_done, rc);
}

void rc_clear(struct disco_rc *rc)
{
	if (rc->monitor_timer != NULL)
		pomp_timer_destroy(rc->monitor_timer);
	if (rc->cal_timer != NULL)
		pomp_timer_destroy(rc->cal_timer);
	rc->monitor_timer = rc->cal_timer = NULL;
}
