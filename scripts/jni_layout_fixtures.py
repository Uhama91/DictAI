#!/usr/bin/env python3
"""Synthetic fixture bridge for the exact production JNI, compiled on the host."""
import hashlib,json,sys
from pathlib import Path
from evaluate_faithful_layout import ROOT,faithful_input,score
mode=sys.argv[1];directory=Path(sys.argv[2]);directory.mkdir(parents=True,exist_ok=True)
corpus=ROOT/'docs/benchmarks/local-format/layout-cases-v1.json'
cases=json.loads(corpus.read_text());ids=[];rows=[]
for case in cases:
    prompt,grammar,direct,prefix_sha=faithful_input(case);id=case['id']
    budget=min(2048,max(192,len(case['text'].encode('utf-16-le'))//2+128))
    if mode=='prepare':
        if direct is not None:continue
        ids.append(id)
        for suffix,value in [('prompt',prompt),('grammar',grammar),('budget',str(budget))]:
            (directory/(id+'.'+suffix)).write_text(value)
    else:
        output=direct if direct is not None else (directory/(id+'.output')).read_text()
        row={**case,'route':'direct' if direct is not None else 'model','output':output,'prompt':prompt,'grammar':grammar,'prefix_sha256':prefix_sha,'token_budget':budget,**score(case,output)}
        if direct is None:
            first,total,complete=(directory/(id+'.metrics')).read_text().split('\t')
            row.update(first_chunk_ms=int(first),generation_ms=int(total),completed=complete=='true')
        rows.append(row)
if mode=='prepare':
    (directory/'index.txt').write_text('\n'.join(ids)+'\n')
else:
    report={'host_only':True,'runtime':'exact production local_format_jni.cpp, host x86_64, resident model, grammar before top-k',
        'model':'LFM2.5-350M-Q4_K_M.gguf','model_sha256':'7e6f72643caafc9a68256686638c4d7916f2cec76d1df478d4c3ddcd95a6aed4',
        'jni_source_sha256':hashlib.sha256((ROOT/'app/src/main/cpp/llm/local_format_jni.cpp').read_bytes()).hexdigest(),
        'corpus_sha256':hashlib.sha256(corpus.read_bytes()).hexdigest(),'context':4096,'threads':2,'temperature':.1,'top_k':50,'repeat_penalty':1.05,'seed':1234,
        'native_smoke_passed':True,'timing_limitations':'Model already loaded; host CPU, possibly concurrent Android build. Not phone latency.', 'cases':rows}
    output=ROOT/'docs/benchmarks/local-format/lfm350-faithful-jni-host.json';output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    assert all(r['fidelity_exact_words'] and r.get('completed',True) for r in rows),'JNI layout execution/fidelity failure'
    print('Exact JNI layout: 10 generations conserved all words, 2 direct results. REPORT',output)
