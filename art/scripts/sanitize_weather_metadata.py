"""Run inside Blender after export. Strip local paths from FBX string metadata only."""
from pathlib import Path
import tempfile, shutil
from io_scene_fbx import parse_fbx, encode_bin
ROOT=Path(__file__).resolve().parents[2]
source=ROOT/'art/exports/weather/weather_patterns.fbx'
backup=ROOT/'art/validation/weather-source-checkpoints';backup.mkdir(parents=True,exist_ok=True)
shutil.copy2(source,backup/source.name)
tree,version=parse_fbx.parse(str(source))
old=str(ROOT).encode();home=str(Path.home()).encode()
methods={ord(k):'add_'+v for k,v in {'B':'bool','C':'char','Z':'int8','Y':'int16','I':'int32','L':'int64','F':'float32','D':'float64','R':'bytes','S':'string','i':'int32_array','l':'int64_array','f':'float32_array','d':'float64_array','b':'bool_array','c':'byte_array'}.items()}
def clean(value): return value.replace(old,b'../../..').replace(home,b'~')
def copy(item):
    result=encode_bin.FBXElem(item.id)
    for kind,value in zip(item.props_type,item.props):
        getattr(result,methods[kind])(clean(value) if kind==ord('S') else value)
    result.elems=[copy(e) for e in item.elems]
    return result
with tempfile.TemporaryDirectory(prefix='weather-metadata-') as temporary:
    path=Path(temporary)/'safe.fbx'
    hack=encode_bin._write_timedate_hack
    try:
        encode_bin._write_timedate_hack=lambda _:None
        encode_bin.write(str(path),copy(tree),version)
    finally: encode_bin._write_timedate_hack=hack
    actual,_=parse_fbx.parse(str(path))
    def compare(a,b):
        assert a.id==b.id and a.props_type==b.props_type and len(a.elems)==len(b.elems)
        for kind,v,w in zip(a.props_type,a.props,b.props): assert (clean(v) if kind==ord('S') else v)==w
        for x,y in zip(a.elems,b.elems): compare(x,y)
    compare(tree,actual)
    assert home not in path.read_bytes()
    shutil.copy2(path,source)
result={'fbx_numeric_properties_preserved':True,'workstation_paths_removed':True}
