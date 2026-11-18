# disco-flying-wing-fpv

Open-source Android app for the Parrot Disco flying wing flown with a
SkyController 2: live video in a side-by-side headset view (FPV) with a
HUD, on modern Android phones. Built on the community
[Unified GroundSdk](https://github.com/UnifiedGroundSdk/groundsdk-manifest)
fork of Parrot's GroundSDK, and developed against a fake SkyController 2 +
Disco so it can be tested entirely in software.

Not affiliated with or endorsed by Parrot. "Parrot", "Disco",
"SkyController" and "FreeFlight" are trademarks of Parrot Drones SAS.

## Licence

BSD-3-Clause, see `LICENSE`. The app uses Parrot's Ground SDK, distributed
under the PARROT GROUND SDK End User License Agreement (`EULA.md` in the
SDK manifest), whose open-source components are BSD-3-Clause.

Portions copyright (c) 2019–Present PARROT DRONES SAS. All rights reserved.

## Building

`make help` lists the targets. The Ground SDK fork is checked out into
`sdk/` by `make sdk-sync`, at the exact revisions recorded in
`sdk.lock.xml`.

Status: planning. See:

- `PLAN.md`: architecture, milestones, test rig, repositories
- `TASKS.md`: current backlog
- `NOTES.md`: research notes and sources
