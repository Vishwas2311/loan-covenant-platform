"""Score statement readers. truth: {doc: {values: {KEY: [acceptable values]}}}. Correct within 0.5%."""
import json, sys
truth=json.load(open(sys.argv[1])); res={}
for f in sys.argv[2:]:
    for d,ms in json.load(open(f)).items(): res.setdefault(d,{}).update(ms)
methods=sorted({m for d in truth for m in res.get(d,{})})
def ok(a,cands): return any(abs(a-b)<=max(0.01,0.005*abs(b)) for b in cands)
tot={m:dict(c=0,w=0,ws=0,miss=0,s=0.0,docs=0) for m in methods}; n=0; perdoc={}
for d,t in truth.items():
    vals=t['values']; n+=len(vals); perdoc[d]={}
    for m in methods:
        r=res.get(d,{}).get(m)
        if not r: continue
        got=r.get('values',{}); flagged=bool(r.get('issues')) or 'error' in r
        c=sum(1 for k,v in vals.items() if k in got and ok(got[k],v)); w=sum(1 for k,v in vals.items() if k in got and not ok(got[k],v))
        x=tot[m]; x['c']+=c; x['w']+=w; x['ws']+= w if not flagged else 0; x['miss']+=len(vals)-c-w; x['s']+=r.get('seconds',0); x['docs']+=1
        perdoc[d][m]=f'{c}/{len(vals)}'+(f' w{w}' if w else '')
print('doc  '+' '.join(f'{m:>10}' for m in methods))
for d,row in perdoc.items(): print(f'{d:5}'+' '.join(f'{row.get(m,"-"):>10}' for m in methods))
print(f'\n{n} values, {len(truth)} documents')
print(f'{"method":6} {"accuracy":>9} {"correct":>8} {"wrong":>6} {"silent wrong":>13} {"missing":>8} {"sec/doc":>8}')
for m in methods:
    x=tot[m]; print(f'{m:6} {100*x["c"]/n:8.1f}% {x["c"]:>8} {x["w"]:>6} {x["ws"]:>13} {x["miss"]:>8} {x["s"]/max(1,x["docs"]):>8.1f}')
