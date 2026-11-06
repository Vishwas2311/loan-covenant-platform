import json
import sys
t=json.load(open('truth_pages.json')); f=json.load(open(sys.argv[1] if len(sys.argv)>1 else 'docs/statements/found_pypdf.json'))
tot=ok=top2=0; miss=[]
for d,v in t.items():
    if d.startswith('_') or d not in f: continue
    for k in ('BS','PL','CF'):
        if k not in v: continue
        tot+=1; got=f[d]['found'].get(k,[]); want=v[k] if isinstance(v[k],list) else [v[k]]
        if got[:1] and got[0] in want: ok+=1
        if set(got[:2])&set(want): top2+=1
        else: miss.append(f'{d}.{k}')
print(f'{sys.argv[1] if len(sys.argv)>1 else "v1"}: top-1 {ok}/{tot} = {100*ok/tot:.1f}%   in top-2 {top2}/{tot} = {100*top2/tot:.1f}%')
print('misses:', ' '.join(miss))
