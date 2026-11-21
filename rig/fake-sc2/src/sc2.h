/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 *
 * fake-sc2: the SkyController 2's own model, beyond the link to the Disco:
 * its input mapper (which button or axis does what, per drone product, as
 * the app configures it: FreeFlight's control mapping screen), the inputs
 * themselves (buttons, sticks, sliders) grabbed by the app or acting on the
 * drone, its access point settings and the co-piloting source.
 *
 * Modelled on the controller's daemon in its firmware 1.0.9 (mppd: the
 * Mapper and Skyctrl commands it uses) and GroundSdk's Sc2Gamepad (input
 * bits). The default mapping of a Disco is not in any file examined: the
 * one below follows the controller's labels and FreeFlight's actions; to
 * check on the bench.
 */
#ifndef FAKE_SC2_H
#define FAKE_SC2_H

#include <stdint.h>
#include "arsdk/arsdk.h"

#define SC2_AXES          6
#define SC2_MAX_MAPPINGS  32

/* One mapping: an action on a button combination (axis -1), or on an axis
 * (with the buttons that must be held, usually none). */
struct sc2_mapping {
	uint32_t uid;
	int32_t  action;   /* ARSDK_MAPPER_BUTTON_ACTION_* or _AXIS_ACTION_* */
	int32_t  axis;     /* -1 for a button mapping */
	uint32_t buttons;
};

struct sc2_ops {
	/* a mapped button action on the drone side (not APP_n) */
	void (*button_action)(void *userdata, int32_t action);
	/* a mapped axis moved: value -100..100, after inversion and expo */
	void (*axis_action)(void *userdata, int32_t action, int value);
	void *userdata;
};

struct sc2 {
	struct arsdk_cmd_itf *itf;  /* NULL when no app is connected */
	struct sc2_ops       ops;
	uint16_t             product; /* drone product the mappings apply to */

	struct sc2_mapping   map[SC2_MAX_MAPPINGS];
	int                  nmap;
	uint32_t             next_uid;
	int32_t              expo[SC2_AXES];
	uint8_t              inverted[SC2_AXES];

	/* inputs */
	uint32_t             grabbed_buttons, grabbed_axes;
	uint32_t             buttons_state;   /* pressed */
	int                  axis_value[SC2_AXES];

	/* access point (the controller's Wi-Fi for the drone) */
	char                 ap_ssid[33];
	uint8_t              ap_channel;
	int32_t              ap_band;
	int32_t              ap_security;
	char                 ap_key[65];

	int32_t              piloting_source; /* 0 controller sticks, 1 app */
	uint8_t              quality_updates;
};

void sc2_init(struct sc2 *c, uint16_t product, const char *ssid,
		const struct sc2_ops *ops);
void sc2_reset_mapping(struct sc2 *c);

/* On connection, before Skyctrl AllStatesChanged. */
void sc2_send_states(struct sc2 *c);

/* A command from the app; returns 1 if it is one of the controller's. */
int sc2_recv_cmd(struct sc2 *c, const struct arsdk_cmd *cmd);

/* Physical inputs. Returns 0 if the input was used (sent to the app,
 * or a mapped action), -ENOENT if nothing uses it. */
int sc2_button(struct sc2 *c, uint32_t bit, int pressed);
int sc2_axis(struct sc2 *c, int axis, int value);

/* Names for the control socket: buttons "settings", "rth", "takeoff",
 * "b", "a", "rear_left", "rear_right", "right_slider_down",
 * "right_slider_up", "left_slider_down", "left_slider_up"; axes "left_h",
 * "left_v", "right_h", "right_v", "left_slider", "right_slider". */
int sc2_button_bit(const char *name);
int sc2_axis_index(const char *name);

int sc2_status(const struct sc2 *c, char *buf, size_t len);

#endif /* FAKE_SC2_H */
