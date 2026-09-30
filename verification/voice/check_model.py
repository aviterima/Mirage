"""Exercise the actual local native model, not a stub. Usage: python check_model.py binary model"""
import sys,pathlib,json,subprocess,time,tempfile
root=pathlib.Path(__file__).resolve().parents[2]
system=(root/'android/app/src/main/assets/voice-intent-prompt.txt').read_text()
cases=[
 ('After this take me to my saved home location.', 'add_saved','Home','NEXT',0),
 ('Put my Weekend route at the end of this trip.', 'add_saved','Weekend','END',0),
 ('Save this whole trip as Tuesday errands.', 'save_new','Tuesday errands','NEXT',0),
 ('Stay here another twenty minutes.', 'extend','','NEXT',20),
 ('Put Moxy Scottsdale at the end of this trip.', 'add_place','Moxy Scottsdale','END',0),
 ('Save the changes I made to this itinerary.', 'save_changes','','NEXT',0),
 ('Please tell me what happens next.', 'status','','NEXT',0),
 ('Remove Coffee from the upcoming stops.', 'remove','Coffee','NEXT',0),
 ('Take me there.', 'clarify','','NEXT',0),
 ('Open my email and send a message.', 'clarify','','NEXT',0),
]
results=[]
for text,action,target,placement,minutes in cases:
 user='Saved catalog: ["Home", "Weekend", "Office"]\nUpcoming stops: ["Coffee", "Office"]\nInstruction: '+json.dumps(text)
 prompt='<|im_start|>system\n'+system+'<|im_end|>\n<|im_start|>user\n'+user+'<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n'
 with tempfile.NamedTemporaryFile(mode='w',suffix='.txt') as f:
  f.write(prompt);f.flush();t=time.monotonic()
  r=subprocess.run([sys.argv[1],sys.argv[2],f.name],capture_output=True,text=True,timeout=65)
  try:
   out=json.loads(r.stdout);ok=out['action']==action and out['target'].casefold()==target.casefold() and out['placement']==placement and out['minutes']==minutes
  except Exception:out=r.stderr[-600:];ok=False
  result=dict(instruction=text,passed=ok,seconds=round(time.monotonic()-t,2),output=out);results.append(result);print(json.dumps(result),flush=True)
pathlib.Path('voice-model-results.json').write_text(json.dumps(results,indent=2))
sys.exit(0 if all(r['passed'] for r in results) else 1)
