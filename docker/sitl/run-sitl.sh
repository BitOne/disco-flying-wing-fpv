#!/usr/bin/env bash
# Simulated Disco: ArduPlane SITL with the elevon (flying wing) model.
# MAVLink on TCP 5760; FlightGear native-fdm packets to FG_HOST:5503.
# HOME: lat,lon,alt(m AMSL),heading. Default: Kapiolani Park, Honolulu,
# inside the Hawaii scenery bundled with FlightGear.
set -eu
HOME_LOC=${HOME_LOC:-21.2665,-157.8205,3,60}
cd /ardupilot
exec build/sitl/bin/arduplane \
	--model plane-elevon \
	--home "$HOME_LOC" \
	--defaults Tools/autotest/models/plane.parm,Tools/autotest/default_params/plane-elevons.parm,/sim/disco.parm \
	--enable-fgview \
	--serial0 tcp:0 \
	--sim-address=127.0.0.1 \
	${SITL_ARGS:-}
