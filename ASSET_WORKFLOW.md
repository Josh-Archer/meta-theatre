# 3D asset workflow

The [combined asset and efficiency validation](art/validation/REMAINING_ASSETS_20261112.md)
records the integrated build, package audit and automated Quest results.

The ISS material source, reviewed exports and validation are documented in
[`art/ISS.md`](art/ISS.md).

The [November 11 material and performance pass](art/validation/REALISM_PERFORMANCE_20261111.md) records the current chair/Roadster sources, exports, shader checks, and Quest verification status.

The [November 11 celestial pass](art/validation/CELESTIAL_20261111.md) records
the planet lighting, NASA lunar terrain, Milky Way grade, editable Blender
source, Unreal review assets and current native validation.

The live, local-time Earth, its editable Blender source, preserved static
variants, Unreal sandbox copies, attribution, and validation notes are documented in
[`art/EARTH.md`](art/EARTH.md).
The eight-planet environment, Moon, shared Sun, Saturn rings, catalog sky and
runtime budgets are documented in [`art/SOLAR_SYSTEM.md`](art/SOLAR_SYSTEM.md).
The upgraded 8K Milky Way, editable source, matched previews, Unreal material,
and Quest validation limits are documented in [`art/SPACE.md`](art/SPACE.md).
The rebuilt lounge chair, packed Blender source, Unreal LODs, runtime costs,
and headset installation status are documented in [`art/CHAIR.md`](art/CHAIR.md).
The modern Roadster with an open roof and Starman, its standalone editable source,
runtime cost and Unreal review are documented in [`art/ROADSTER.md`](art/ROADSTER.md).
The ISS surface materials, exact geometry preservation, export steps and
headset validation status are documented in [`art/ISS.md`](art/ISS.md).

## Current Meta Theatre app

This project is a native Android/Meta Quest app. It loads `.glb` models from
`app/src/main/assets/`, including `hull.glb` and `lounge_chair.glb`. Blender is
the source authoring tool; export optimized glTF Binary (`.glb`) models there
when they are intended for the current app. Keep editable `.blend` files in
`art/blender/` and any reusable source textures in `art/textures/`.
The baked phases `earth_{day,night,twilight}.glb` and `weather/cloud_patterns.png`
are art sources only. Do not copy them back into `app/src/main/assets/`.
`hull.glb` stays there because the spaceship cabin loads it.

Large assets use Git LFS. Source files use project-relative texture and output
paths. Execute an authoring script through MCP with its `__file__` set to the
script's absolute path, or from an editable source saved in `art/blender/`.
For example, with `script` pointing to an authoring script:

```python
exec(compile(script.read_text(), str(script), 'exec'), {'__file__': str(script)})
```

Use Blender's glTF 2.0 exporter with applied transforms, sensible object names,
packed or correctly referenced textures, and materials based on Principled BSDF.
The dynamic Earth is an explicit exception: its neutral GLB materials are replaced
by custom Meta Spatial SDK shaders and separately loaded maps at runtime.
Check the exported model in the app because Quest performance, material support,
and draw calls matter as much as desktop render quality. Keep high-resolution
source textures in `art/`; make appropriately sized runtime textures for the
headset.

## Blender and Codex MCP

- Blender 5.2.2 LTS is installed from the Blender Foundation Snap package.
- The Blender Lab MCP extension is installed and enabled in Blender.
- Codex has a global `blender` stdio MCP entry pointing to
  `tools/blender-mcp-venv/bin/blender-mcp`.
- The MCP bridge is restricted to `127.0.0.1:9876`; the MCP client uses
  `BLENDER_MCP_HOST=127.0.0.1`.
- Start Blender with `blender --online-mode`. Online access is enabled because
  the extension requires it to start its local bridge. The GUI must remain open
  while Codex operates on the live scene.
- If Codex does not show the Blender tools yet, reload/restart Codex so it reads
  the new MCP configuration. Verify against the default scene before opening
  important work.

The Blender Lab integration can execute Python in Blender with broad access to
the user's files. Keep it bound to loopback, save source files in versioned
project folders, and inspect generated scripts/operations before applying them
to important scenes.

## Unreal Engine path

Unreal is not the renderer/runtime for the current app. Adding Unreal would be
a separate Quest application or a migration of the existing app. For a future
Unreal project, author in Blender and export static meshes as FBX (or use the
Unreal glTF importer when enabled); keep a copy of the Blender source. Unreal
can then use its own materials, lighting, and platform-specific optimization.

Unreal Engine 5.8.3 is installed at `~/Applications/UnrealEngine/5.8.3/`. A
separate blank Blueprint asset sandbox is at
`~/Documents/Unreal Projects/MetaTheatreAssetLab/`; it is not a migration of
the native Quest app. Modeling Tools, Epic's Unreal MCP, and All Toolsets are
enabled in that sandbox.

Codex has a global `unreal` MCP entry at `http://127.0.0.1:8000/mcp`. Keep the
asset sandbox open while using it. Its MCP server binds to loopback and starts
automatically for interactive editor sessions; this was confirmed after a
normal editor restart. The MCP feature and some toolsets are experimental.

The separate Unreal Android SDK is installed at `~/Android/UnrealSdk` with
platform 35, build-tools 35.0.1, and NDK r27c. The launch script sets JDK 21
for Unreal while leaving the system default Java 17 intact. Unreal's Android
platform now reports buildable.

Meta XR Integration 207.0 (plugin descriptor `OculusXR.uplugin`) is installed
and enabled at `~/Documents/Unreal Projects/MetaTheatreAssetLab/Plugins/MetaXR/`.
Meta lists UE 5.8.0 as the requirement. The Linux editor modules were compiled
successfully against UE 5.8.3 and the editor log confirms `OculusXRHMD`,
`OculusXRPassthrough`, and `OculusXRTelemetry` load. The vendor source triggers
UE 5.8's Linux unreachable-code warning as an error, so the plugin's
`OculusXRHMD.Build.cs` sets that diagnostic to Off for this module only.
Meta's Windows-only editor utilities are not available on Linux. No Android
APK has been packaged or run on a headset. Epic's [Linux Development
Quickstart](https://dev.epicgames.com/documentation/unreal-engine/linux-development-quickstart-for-unreal-engine)
and [Android requirements for Unreal 5.8](https://dev.epicgames.com/documentation/unreal-engine/android-development-requirements-for-unreal-engine?lang=en-US)
cover the editor and Android toolchain. Meta's [Unreal setup guide](https://developers.meta.com/vr/documentation/unreal/unreal-quick-start-setup-dev-environ/)
describes completing Quest setup.
