"""Encode the native-trajectory Blender frames with explicit preview/phase labels."""
import json
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / 'art/validation/starship-20261113'
OUT = ROOT / 'art/renders/starship_20261113'
frames = json.loads((DATA / 'flight-frames.json').read_text())
labels = {
    'PAD': 'Ready for launch', 'ASCENT': 'Liftoff', 'SEPARATION': 'Stage separation',
    'ORBIT': 'Orbiting Earth', 'DESCENT': 'Atmospheric descent', 'FLIP': 'Landing flip',
    'LANDING': 'Landing burn', 'LANDED': 'Tower recovery',
}

def timestamp(seconds):
    ms = round(seconds * 1000)
    return f'{ms // 3600000:02}:{ms // 60000 % 60:02}:{ms // 1000 % 60:02},{ms % 1000:03}'

cues = []
start = 0
for i in range(1, len(frames) + 1):
    if i == len(frames) or frames[i]['phase'] != frames[start]['phase']:
        cues.append(f"{len(cues)+1}\n{timestamp(start/12)} --> {timestamp(i/12)}\n{labels[frames[start]['phase']]}\n")
        start = i
subtitles = DATA / 'flight.srt'
subtitles.write_text('\n'.join(cues))
# Run in DATA so ffmpeg filter paths need no shell escaping.
filters = "drawtext=fontfile=/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf:text='BLENDER PREVIEW | Staged flight | 6x speed':fontsize=23:fontcolor=white:x=24:y=22:box=1:boxcolor=black@0.65:boxborderw=10,subtitles=flight.srt:force_style='Fontsize=22,Outline=1,MarginV=22'"
subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-y', '-framerate', '12',
                '-i', 'flight-frames/%04d.png', '-vf', filters, '-c:v', 'libx264',
                '-preset', 'slow', '-crf', '18', '-pix_fmt', 'yuv420p', '-movflags',
                '+faststart', str(OUT / 'starship-flight.mp4')], cwd=DATA, check=True)
print(OUT / 'starship-flight.mp4')
