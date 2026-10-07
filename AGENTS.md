# Modeling and asset export

- Use the configured `blender` MCP server to model, inspect, and render in the
  live Blender session when those tools are available. Keep MCP actions scoped
  to the open scene and this project's art folders.
- Save editable Blender files under `art/blender/` and authored source textures
  under `art/textures/`.
- The current Quest app consumes `.glb` assets from `app/src/main/assets/`.
  Export there only when an asset is ready to be used by the app; preserve the
  existing asset names unless the app references are updated as well.
- Treat `app/src/main/assets/` as runtime output, not the editable source.
  Preserve a `.blend` source for every modeled asset.
- Check both visual quality and Quest runtime cost. High-resolution desktop
  renders do not establish that a model, material, or texture is suitable for
  the headset.
- Unreal is not integrated into this Android project. Do not create an Unreal
  project or migrate the app unless the user asks for that work.
- Before broad changes to an important scene, save a separate source version.
  The Blender MCP extension executes Python inside Blender and has access to
  files available to the Blender process.
