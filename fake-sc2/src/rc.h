/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 *
 * fake-sc2: the Disco's RC receiver (ARSDK feature Rc, rc.xml), for a hobby
 * radio on the C.H.U.C.K. (S.BUS or SUMD, NOTES.md section 31). The
 * receiver, its channels and the radio's sticks come from the control
 * socket ("rc ..."); the app sees what the Disco's flight program would
 * send: receiver state, channel values while monitored, calibration
 * (neutral, then each action's channel by its min and max), inversion,
 * the supported actions; enabling is refused in flight or before the
 * required actions are calibrated. Behaviour inferred from rc.xml and
 * dragon-prog's strings: to check against a real Disco.
 */
#ifndef FAKE_RC_H
#define FAKE_RC_H

#include <stdint.h>
#include <stddef.h>

struct pomp_loop;
struct pomp_timer;
struct arsdk_cmd;
struct arsdk_cmd_itf;

#define RC_CHANNELS 16
#define RC_ACTIONS  10  /* ARSDK_RC_CHANNEL_ACTION_COUNT */

struct rc_action {
	int     channel;        /* -1: not calibrated */
	int32_t type;           /* ARSDK_RC_CHANNEL_TYPE_* calibrated, INVALID if none */
	uint8_t inverted;
};

struct disco_rc {
	struct pomp_loop      *loop;
	struct arsdk_cmd_itf  *itf;     /* NULL when no app is connected */

	int      connected;             /* a receiver sends frames */
	char     protocol[8];           /* "SBUS", "SUMD" */
	int      enabled;               /* the Disco applies the radio */
	int      landed;                /* enabling only on the ground */
	int      monitoring;
	struct pomp_timer *monitor_timer;

	int16_t  ch[RC_CHANNELS];       /* -100..100 */
	int16_t  neutral[RC_CHANNELS];
	int      neutral_done;
	struct rc_action act[RC_ACTIONS];

	/* calibration in progress */
	int32_t  cal_type;              /* ARSDK_RC_CALIBRATION_TYPE_* */
	int32_t  cal_action;
	int32_t  cal_channel_type;
	int16_t  cal_lo[RC_CHANNELS], cal_hi[RC_CHANNELS];
	struct pomp_timer *cal_timer;
};

void rc_init(struct disco_rc *rc, struct pomp_loop *loop);
void rc_clear(struct disco_rc *rc);

/* The app connected (itf) or left (NULL); on connection, the states. */
void rc_set_itf(struct disco_rc *rc, struct arsdk_cmd_itf *itf);
void rc_send_states(struct disco_rc *rc);

/* A command from the app; returns 1 if it is an Rc one. */
int rc_recv_cmd(struct disco_rc *rc, const struct arsdk_cmd *cmd);

/* The Disco is on the ground (enabling allowed) or not. */
void rc_landed(struct disco_rc *rc, int landed);

/* Control socket: "rc connect <sbus|sumd>", "rc disconnect", "rc reset",
 * "rc ch <1..16> <-100..100>", "rc sticks <roll> <pitch> <gaz> [control]"
 * (on the channels calibrated for them), "rc" (status). Writes the reply. */
int rc_ctrl(struct disco_rc *rc, const char *line, char *reply, size_t len);

/* The radio flies the Disco: enabled, connected and, if a take-control
 * channel is calibrated, its switch on. The sticks, -1..1. */
int rc_piloting(const struct disco_rc *rc, double *roll, double *pitch, double *gaz);

/* Status for the control socket's "disco": "rc=<enabled>,<piloting>". */
int rc_status(const struct disco_rc *rc, char *buf, size_t len);

#endif /* FAKE_RC_H */
