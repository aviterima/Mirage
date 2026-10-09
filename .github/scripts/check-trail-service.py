"""Blocking live named search and city browsing; bounded queries like the app."""
import json, pathlib, urllib.request, urllib.parse, time, math
out=pathlib.Path('acceptance-evidence');out.mkdir(exist_ok=True)
url='https://maps.mail.ru/osm/tools/overpass/api/interpreter'
messages=[]
def query(label,ql):
    for attempt in range(2):
        try:
            req=urllib.request.Request(url+'?'+urllib.parse.urlencode({'data':ql}),headers={'User-Agent':'Mirage/0.15.1 (https://github.com/aviterima/Mirage)'})
            with urllib.request.urlopen(req,timeout=45) as response: data=response.read(4_000_001)
            assert len(data)<=4_000_000
            root=json.loads(data)
            assert not root.get('remark'),root.get('remark')
            assert root['elements']
            (out/f'{label}.json').write_bytes(data)
            message=f"PASS {label}, attempt {attempt+1}: {len(root['elements'])} elements"
            messages.append(message);print(message,flush=True)
            (out/'live-trail-service.txt').write_text('\n'.join(messages)+'\n')
            return root['elements']
        except Exception as e:
            message=f'FAIL {label}, attempt {attempt+1}: {e}'
            messages.append(message);print(message,flush=True)
            (out/'live-trail-service.txt').write_text('\n'.join(messages)+'\n')
            if attempt: raise
            time.sleep(35)
area='(33.060844,-112.508654,33.959156,-111.431346)'
found=query('named-echo-canyon',f'[out:json][timeout:25][maxsize:268435456];way{area}[highway~"^(path|footway|track|steps)$"][name][area!=yes]->.paths;relation{area}[route~"^(hiking|foot)$"][name]->.routes;(way.paths[name~"Echo Canyon",i];relation.routes[name~"Echo Canyon",i];);out geom 100;')
assert len(found)<100
lat,lon=32.2226,-110.9747
d=50000/111320;dx=d/math.cos(math.radians(lat));area=f'({lat-d},{lon-dx},{lat+d},{lon+dx})'
meta=query('browse-tucson',f'[out:json][timeout:25][maxsize:268435456];(way{area}[highway~"^(path|footway|track|steps)$"][name][area!=yes][access!~"^(private|no)$"][foot!~"^(private|no)$"];relation{area}[route~"^(hiking|foot)$"][name][access!~"^(private|no)$"][foot!~"^(private|no)$"];);out tags center 1000;')
def distance(e):
    c=e['center'];return (c['lat']-lat)**2+((c['lon']-lon)*math.cos(math.radians(lat)))**2
groups={}
for e in meta:
    if 'center' in e and e.get('tags',{}).get('name'): groups.setdefault(e['tags']['name'],[]).append(e)
chosen=[]
for group in sorted(groups.values(),key=lambda g:min(map(distance,g)))[:20]:
    chosen+=sorted(group,key=distance)[:80-len(chosen)]
selectors=''.join(f"{kind}(id:{','.join(str(e['id']) for e in chosen if e['type']==kind)});" for kind in ('way','relation') if any(e['type']==kind for e in chosen))
geometry=query('browse-tucson-geometry',f'[out:json][timeout:25][maxsize:268435456];({selectors});out geom 100;')
assert 0<len(geometry)<100
assert any(len(e.get('geometry',[]))>=2 or e.get('members') for e in geometry)
print('PASS: city-only Tucson browsing returns full mapped geometry',flush=True)
