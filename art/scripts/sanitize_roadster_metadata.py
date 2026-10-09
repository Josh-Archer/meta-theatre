"""Run in Blender after export. Remove workstation paths without changing geometry or pixels."""
from pathlib import Path
import subprocess, struct, tempfile, shutil
from io_scene_fbx import parse_fbx, encode_bin
ROOT=Path(__file__).resolve().parents[2]
old=str(ROOT).encode();home=str(Path.home()).encode()
replacement=b'//'+b'./'*((len(old)-7)//2)+b'../..'
replacement+=b'/'*(len(old)-len(replacement))
neutral=b'/tmp/'+b'/'*(len(home)-5)
assert len(old)==len(replacement) and len(home)==len(neutral)
backup=ROOT/'art/validation/roadster-v3/private-metadata-backup';backup.mkdir(parents=True,exist_ok=True)
source=ROOT/'art/blender/space_roadster.blend';shutil.copy2(source,backup/source.name)
with tempfile.TemporaryDirectory(prefix='theatre-roadster-') as tmp:
    raw=Path(tmp)/'source.raw'
    with raw.open('wb') as out:subprocess.run(['zstd','-dc',str(source)],stdout=out,check=True)
    original=raw.read_bytes();data=bytearray(original);assert data[:7]==b'BLENDER'
    endian='<' if data[8:9]==b'v' else '>';pointer=8 if data[7:8]==b'-' else 4
    header=struct.Struct(endian+'4sI'+('Q' if pointer==8 else 'I')+'II');offset=12
    while offset<len(data):
        code,length,*_=header.unpack_from(data,offset);start=offset+header.size;end=start+length
        block=bytes(data[start:end]);clean=block.replace(old,replacement).replace(home,neutral)
        if clean!=block:
            assert code not in {b'TEST',b'DNA1'} and not block.startswith((b'\x89PNG',b'\xff\xd8'))
            data[start:end]=clean
        offset=end
        if code==b'ENDB':break
    assert bytes(data)==original.replace(old,replacement).replace(home,neutral)
    assert home not in data and old not in data
    raw.write_bytes(data)
    subprocess.run(['zstd','-q','-f','-3',str(raw),'-o',str(source)],check=True)
    path=ROOT/'art/exports/flybys/space_roadster.fbx';shutil.copy2(path,backup/path.name)
    tree,version=parse_fbx.parse(str(path))
    methods={ord(k):'add_'+v for k,v in {'B':'bool','C':'char','Z':'int8','Y':'int16','I':'int32','L':'int64','F':'float32','D':'float64','R':'bytes','S':'string','i':'int32_array','l':'int64_array','f':'float32_array','d':'float64_array','b':'bool_array','c':'byte_array'}.items()}
    def copy(item):
        result=encode_bin.FBXElem(item.id)
        for kind,value in zip(item.props_type,item.props):
            if kind==ord('S'):value=value.replace(old,b'../../..').replace(home,b'~')
            getattr(result,methods[kind])(value)
        result.elems=[copy(e) for e in item.elems];return result
    cleaned=copy(tree);out=Path(tmp)/'safe.fbx'
    original_hack=encode_bin._write_timedate_hack
    try:
        encode_bin._write_timedate_hack=lambda root:None
        encode_bin.write(str(out),cleaned,version)
    finally:
        encode_bin._write_timedate_hack=original_hack
    actual,_=parse_fbx.parse(str(out))
    def compare(a,b):
        assert a.id==b.id and a.props_type==b.props_type and len(a.elems)==len(b.elems)
        for kind,v,w in zip(a.props_type,a.props,b.props):
            assert (v.replace(old,b'../../..').replace(home,b'~') if kind==ord('S') else v)==w
        for x,y in zip(a.elems,b.elems):compare(x,y)
    compare(tree,actual);shutil.copy2(out,path)
result={'blendGeometryAndPackedPixelsPreserved':True,'fbxNumericPropertiesPreserved':True,'workstationPathsRemoved':True}
