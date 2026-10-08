"""Record a live provider check separately from deterministic app acceptance tests."""
import json, pathlib, urllib.request, urllib.parse
out=pathlib.Path('acceptance-evidence');out.mkdir(exist_ok=True)
query='[out:json][timeout:25][maxsize:268435456];(relation(33.060844,-112.508654,33.959156,-111.431346)[route~"^(hiking|foot)$"][name~"Echo Canyon",i];way(33.060844,-112.508654,33.959156,-111.431346)[highway~"^(path|footway|track|steps)$"][name~"Echo Canyon",i][area!=yes];);out geom 100;'
url='https://maps.mail.ru/osm/tools/overpass/api/interpreter'
try:
    req=urllib.request.Request(url+'?'+urllib.parse.urlencode({'data':query}),headers={'User-Agent':'Mirage/0.15.0 (https://github.com/aviterima/Mirage)'})
    with urllib.request.urlopen(req,timeout=45) as response: data=response.read(4_000_001)
    assert len(data)<=4_000_000
    root=json.loads(data)
    assert not root.get('remark'),root.get('remark')
    assert 0<len(root['elements'])<100
    (out/'live-trail-response.json').write_bytes(data)
    message=f"PASS: {url}; Echo Canyon; {len(root['elements'])} mapped elements"
    (out/'live-trail-service.txt').write_text(message+'\n');print(message)
except Exception as e:
    message=f'FAIL: {url}: {e}'
    (out/'live-trail-service.txt').write_text(message+'\n');print(message)
    raise
