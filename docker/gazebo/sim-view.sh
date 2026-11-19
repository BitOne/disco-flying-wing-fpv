#!/usr/bin/env bash
# Line-of-sight view of the simulated Disco on the PC's screen: Gazebo's
# GUI (connected to the server of run-gazebo.sh), its camera standing where
# the pilot stands (PILOT=x,y,z metres from the take-off point, x east,
# y north, z up: eyes 1.7 m above the ground, 15 m behind it), turning to
# keep the Disco in view like the pilot's head does.
# VIEW=chase instead follows the Disco from behind (CHASE=x,y,z offset in
# its frame) and looks at it.
set -eu
PILOT=${PILOT:-0,-15,1.7}
CHASE=${CHASE:-0,8,2}
TARGET=${TARGET:-disco_zephyr}
# horizontal field of view, degrees: 40 is about the angle a monitor
# fills in the viewer's eyes, so the Disco shows at its true apparent size
# (a speck at 300 m, as in real line-of-sight flying); narrower zooms in
FOV=${FOV:-40}

cat > /tmp/sim-view.config <<CONFIG
<?xml version="1.0"?>
<window>
  <width>1280</width>
  <height>800</height>
  <style material_theme="Light" material_primary="DeepOrange" material_accent="LightBlue"/>
  <menus><drawer visible="false"/></menus>
  <dialog_on_exit>false</dialog_on_exit>
</window>
<plugin filename="MinimalScene" name="3D View">
  <gz-gui>
    <title>Disco: line of sight</title>
    <property type="bool" key="showTitleBar">false</property>
    <property type="string" key="state">docked</property>
  </gz-gui>
  <engine>ogre2</engine>
  <scene>scene</scene>
  <ambient_light>0.6 0.6 0.6</ambient_light>
  <background_color>0.62 0.76 0.92</background_color>
  <camera_pose>${PILOT//,/ } 0 0 1.5708</camera_pose>
  <camera_clip><near>0.1</near><far>5000</far></camera_clip>
  <horizontal_fov>${FOV}</horizontal_fov>
  <sky></sky>
</plugin>
<plugin filename="GzSceneManager" name="Scene Manager">
  <gz-gui><property key="state" type="string">floating</property>
    <property key="width" type="double">5</property><property key="height" type="double">5</property>
    <property key="showTitleBar" type="bool">false</property></gz-gui>
</plugin>
<plugin filename="InteractiveViewControl" name="Interactive view control">
  <gz-gui><property key="state" type="string">floating</property>
    <property key="width" type="double">5</property><property key="height" type="double">5</property>
    <property key="showTitleBar" type="bool">false</property></gz-gui>
</plugin>
<plugin filename="CameraTracking" name="Camera Tracking">
  <gz-gui><property key="state" type="string">floating</property>
    <property key="width" type="double">5</property><property key="height" type="double">5</property>
    <property key="showTitleBar" type="bool">false</property></gz-gui>
</plugin>
CONFIG

gz sim -g --gui-config /tmp/sim-view.config -v ${GZ_VERBOSE:-2} &
GUI=$!

# once the GUI is up, point its camera
for _ in $(seq 60); do
	gz topic -l 2>/dev/null | grep -qx /gui/track && break
	sleep 1
done
sleep 3
IFS=, read -r PX PY PZ <<<"$PILOT"
IFS=, read -r CX CY CZ <<<"$CHASE"
if [ "${VIEW:-pilot}" = chase ]; then
	gz topic -t /gui/track -m gz.msgs.CameraTrack -p "track_mode: FOLLOW_LOOK_AT, follow_target: {name: '$TARGET'}, track_target: {name: '$TARGET'}, follow_offset: {x: $CX, y: $CY, z: $CZ}, follow_pgain: 0.5, track_pgain: 1.0"
else
	gz service -s /gui/move_to/pose --reqtype gz.msgs.GUICamera --reptype gz.msgs.Boolean --timeout 3000 \
		--req "pose: {position: {x: $PX, y: $PY, z: $PZ}}" || true
	sleep 2
	gz topic -t /gui/track -m gz.msgs.CameraTrack -p "track_mode: TRACK, track_target: {name: '$TARGET'}, track_pgain: 1.0"
fi
echo "view: ${VIEW:-pilot}"
wait $GUI
