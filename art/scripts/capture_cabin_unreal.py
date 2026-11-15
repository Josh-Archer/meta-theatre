"""Capture the saved cabin map with Unreal's Vulkan editor viewport.

Run in the asset lab using -ExecutePythonScript, without -nullrhi.
The capture is an authoring preview, not a Quest runtime screenshot.
"""
import json
import time
from pathlib import Path
import unreal

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / 'art/renders/cabin_20261114/cabin-unreal.png'
REPORT = ROOT / 'art/validation/cabin-20261114/unreal-render.json'
levels = unreal.get_editor_subsystem(unreal.LevelEditorSubsystem)
actors = unreal.get_editor_subsystem(unreal.EditorActorSubsystem)
assert levels.load_level('/Game/MetaTheatre/Cabin20261114/ObservationCabin')
camera = next(a for a in actors.get_all_level_actors()
              if a.get_actor_label().startswith('Cabin review'))
component = camera.get_cine_camera_component()
pp = component.get_editor_property('post_process_settings')
pp.auto_exposure_bias = -4
component.set_editor_property('post_process_settings', pp)
assert levels.save_current_level()
OUTPUT.unlink(missing_ok=True)
unreal.EditorPythonScripting.set_keep_python_script_alive(True)
started = time.monotonic()
state = {'requested': False, 'task': None, 'finished': False}


def tick(delta):
    if state['finished']:
        return
    elapsed = time.monotonic() - started
    if not state['requested'] and elapsed > 15:
        # Screenshot scheduling can tick Slate recursively; mark it first.
        state['requested'] = True
        state['task'] = unreal.AutomationLibrary.take_high_res_screenshot(
            1920, 1234, str(OUTPUT), camera=camera, delay=3.0)
        unreal.log('CABIN_VIEWPORT_CAPTURE_REQUESTED')
    if (state['task'] and state['task'].is_task_done() and
            OUTPUT.exists() and OUTPUT.stat().st_size > 10000):
        REPORT.write_text(json.dumps({'image': str(OUTPUT), 'bytes': OUTPUT.stat().st_size,
                                     'renderer': 'Unreal Vulkan editor viewport',
                                     'headset_verified': False}, indent=2) + '\n')
        unreal.log('CABIN_VIEWPORT_CAPTURE_OK ' + str(OUTPUT))
        state['finished'] = True
        # Let the script runner exit on its next tick. Removing a Slate delegate
        # while it is executing can corrupt the delegate in UE 5.8 on Linux.
        unreal.EditorPythonScripting.set_keep_python_script_alive(False)
    elif elapsed > 150:
        unreal.log_error('CABIN_VIEWPORT_CAPTURE_TIMEOUT')
        state['finished'] = True
        unreal.EditorPythonScripting.set_keep_python_script_alive(False)


handle = unreal.register_slate_post_tick_callback(tick)
