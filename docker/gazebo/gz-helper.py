#!/usr/bin/env python3
# Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
#
# Commands from sim/bridge to Gazebo, one per UDP datagram on
# 127.0.0.1:5410 (Python because Gazebo's transport has Python bindings in
# the image, not Node ones):
#
#   throw <heading_deg>   the pilot's hand launch: releases the Disco from
#                         the hand holding it (DetachableJoint), pushes it forward
#                         along the heading (and a little up) for 0.3 s,
#                         to about 13 m/s, like the real Disco is thrown
#
# Uses Gazebo's ApplyLinkWrench system (in the world).
import math
import os
import socket
import time

from gz.msgs10.entity_pb2 import Entity
from gz.msgs10.empty_pb2 import Empty
from gz.msgs10.entity_wrench_pb2 import EntityWrench
from gz.transport13 import Node

WORLD = os.environ.get("GZ_WORLD", "disco")
LINK = os.environ.get("THROW_LINK", "disco_zephyr::wing")
MASS = float(os.environ.get("THROW_MASS", "1.9"))  # the Zephyr with its camera
THROW_S = 0.3
THROW_SPEED = float(os.environ.get("THROW_SPEED", "13"))
THROW_UP_DEG = 12

node = Node()
push = node.advertise(f"/world/{WORLD}/wrench/persistent", EntityWrench)
clear = node.advertise(f"/world/{WORLD}/wrench/clear", Entity)
release = node.advertise("/disco/release", Empty)


def link() -> Entity:
    e = Entity()
    e.name = LINK
    e.type = Entity.LINK
    return e


def throw(heading_deg: float) -> None:
    # Gazebo's world: x east, y north, z up; heading from north, clockwise
    h = math.radians(heading_deg)
    up = math.radians(THROW_UP_DEG)
    a = THROW_SPEED / THROW_S
    w = EntityWrench()
    w.entity.CopyFrom(link())
    w.wrench.force.x = MASS * a * math.cos(up) * math.sin(h)
    w.wrench.force.y = MASS * a * math.cos(up) * math.cos(h)
    w.wrench.force.z = MASS * (a * math.sin(up) + 9.81)
    release.publish(Empty())
    push.publish(w)
    time.sleep(THROW_S)
    clear.publish(link())
    print(f"thrown: heading {heading_deg:.0f}, {THROW_SPEED:.0f} m/s", flush=True)


def main() -> None:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("127.0.0.1", int(os.environ.get("HELPER_PORT", "5410"))))
    print("gz-helper: listening", flush=True)
    while True:
        words = sock.recv(256).decode(errors="replace").split()
        try:
            if words and words[0] == "throw":
                throw(float(words[1]) if len(words) > 1 else 0.0)
            elif words:
                print(f"gz-helper: unknown command {words}", flush=True)
        except (ValueError, IndexError) as e:
            print(f"gz-helper: {words}: {e}", flush=True)


if __name__ == "__main__":
    main()
