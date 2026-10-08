"""A blocking live-provider check, independent of deterministic app acceptance."""
import json, pathlib, urllib.request, urllib.parse, time
out=pathlib.Path('acceptance-evidence');out.mkdir(exist_ok=True)
area='(33.060844,-112.508654,33.959156,-111.431346)'
query=f'[out:json][timeout:25][maxsize:268435456];way{area}[highway~"^(path|footway|track|steps)$"][name][area!=yes]->.paths;relation{area}[route~"^(hiking|foot)$"][name]->.routes;(way.paths[name~"Echo Canyon",i];relation.routes[name~"Echo Canyon",i];);out geom 100;'
url='https://maps.mail.ru/osm/tools/overpass/api/interpreter'
messages=[]
for attempt in range(2):
    try:
        req=urllib.request.Request(url+'?'+urllib.parse.urlencode({'data':query}),headers={'User-Agent':'Mirage/0.15.0 (https://github.com/aviterima/Mirage)'})
        with urllib.request.urlopen(req,timeout=45) as response: data=response.read(4_000_001)
        assert len(data)<=4_000_000
        root=json.loads(data)
        assert not root.get('remark'),root.get('remark')
        assert 0<len(root['elements'])<100
        (out/'live-trail-response.json').write_bytes(data)
        message=f"PASS attempt {attempt+1}: {url}; Echo Canyon; {len(root['elements'])} mapped elements"
        messages.append(message);print(message)
        (out/'live-trail-service.txt').write_text('\n'.join(messages)+'\n')
        break
    except Exception as e:
        message=f'FAIL attempt {attempt+1}: {url}: {e}'
        messages.append(message);print(message)
        (out/'live-trail-service.txt').write_text('\n'.join(messages)+'\n')
        if attempt: raise
        time.sleep(35)
