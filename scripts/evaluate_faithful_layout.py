#!/usr/bin/env python3
"""Exact-copy layout experiment. Timings are HOST ONLY; all inputs are synthetic."""
import hashlib,json,os,re,selectors,subprocess,time
from pathlib import Path
from compare_local_format_models import ROOT,BINARY,verified

WORDS = re.compile(r"[^\t\n\v\f\r \u0085\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000]+")
ASSETS = ROOT / 'app/src/main/assets/local-format'
GREETING = re.compile(r'^(bonjour|bonsoir|salut|cher|chère|hello|hi|dear|good morning)\b', re.I)

def faithful_input(case):
    kind=case['format']; source=case['text'].strip(); words=WORDS.findall(source)
    assert kind in ('list','email') and 0<len(words)<=512 and '\0' not in source and len(source)<=16000
    prefix=(ASSETS/f'layout-{kind}.prompt').read_bytes()
    prompt=prefix.decode('utf-8')+'<|im_start|>user\n'+source.replace('<|','< |')+'<|im_end|>\n<|im_start|>assistant\n'
    quote=lambda x:json.dumps(x,ensure_ascii=False)
    gap='\n• ' if kind=='list' else '\n\n'
    grammar='root ::= '+(quote('• ')+' ' if kind=='list' else '')+' sep '.join(map(quote,words))+'\nsep ::= '+quote(' ')+' | '+quote(gap)+'\n'
    direct=source if kind=='email' and len(words)<=5 and not GREETING.match(source) else '• '+source if kind=='list' and len(words)==1 else None
    return prompt,grammar,direct,hashlib.sha256(prefix).hexdigest()

def shape(text,kind):
    text=text.strip().replace('\r\n','\n'); roles=[]; offset=0
    for line in text.split('\n'):
        if not line.strip(): continue
        bullet=kind=='list' and line.startswith('• ')
        count=len(WORDS.findall(line[2:] if bullet else line))
        roles.append({'role':'bullet' if bullet else 'plain','start':offset,'end':offset+count});offset+=count
    plain=re.sub(r'(?m)^• ','',text) if kind=='list' else text
    matches=list(WORDS.finditer(plain))
    gaps=[plain[matches[i-1].end():matches[i].start()] for i in range(1,len(matches))]
    return {'words':[m.group() for m in matches],
        'breaks_after_words':[i+1 for i,g in enumerate(gaps) if '\n' in g],
        'paragraphs_after_words':[i+1 for i,g in enumerate(gaps) if '\n\n' in g], 'line_roles':roles}

def score(case,output):
    got=shape(output,case['format']);ref=shape(case['expected'],case['format']);words=WORDS.findall(case['text'])
    assert ref['words']==words,case['id']
    faithful=got['words']==words;a=set(got['breaks_after_words']);b=set(ref['breaks_after_words'])
    return {'fidelity_exact_words':faithful,'layout_reference_exact':output.strip()==case['expected'].strip(),
        'layout_extra_breaks':sorted(a-b) if faithful else None,'layout_missing_breaks':sorted(b-a) if faithful else None,
        'reference_requires_unavailable_heading':case['format']=='list' and any(x['role']=='plain' for x in ref['line_roles']),
        'actual_layout':got,'reference_layout':ref,'grouping_human_review':None}

def run(threads,cases_path):
    model=ROOT/'app/src/localFormatPrototype/assets/local-format/LFM2.5-350M-Q4_K_M.gguf'
    meta={'file':model.name,'repo':'LiquidAI/LFM2.5-350M-GGUF','revision':'9969000761ce34de907bf20017cbfc3d52d6eaf9',
          'size':229312224,'sha256':'7e6f72643caafc9a68256686638c4d7916f2cec76d1df478d4c3ddcd95a6aed4'}
    assert verified(model,meta),'Run fetch_local_format_model.py first'
    corpus=Path(cases_path);cases=json.loads(corpus.read_text())
    report={'candidate':meta,'host_only':True,'harness_revision':3,'threads':threads,'prompt_batch':4096,'micro_batch':128,
        'binary_sha256':hashlib.sha256(BINARY.read_bytes()).hexdigest(),'corpus_sha256':hashlib.sha256(corpus.read_bytes()).hexdigest(),
        'limitations':'CLI process load per case; not phone latency or resident Android timing. Reference layout is distinct from fidelity.','cases':[]}
    destination=ROOT/f'docs/benchmarks/local-format/lfm350-faithful-t{threads}.json'
    for case in cases:
        prompt,grammar,direct,prefix_sha=faithful_input(case)
        row={**case,'prompt':prompt,'grammar':grammar,'prefix_sha256':prefix_sha,'route':'direct' if direct is not None else 'model'}
        if direct is not None:
            output=direct;row.update(exit_code=0,process_ms=0,first_stdout_ms_including_model_load=None,eog_observed=None)
        else:
            budget=min(2048,max(192,len(case['text'].encode('utf-16-le'))//2+128))
            args=[str(BINARY),'-m',str(model),'-p',prompt,'--grammar',grammar,'--no-conversation','--no-display-prompt','--simple-io','--no-warmup','--color','off',
                '--override-kv','tokenizer.ggml.add_bos_token=bool:false','-t',str(threads),'-tb',str(threads),'-c','4096','-b','4096','-ub','128',
                '-n',str(budget),'--temp','0.1','--top-k','50','--repeat-penalty','1.05','--seed','1234','--samplers','penalties;top_k;temperature']
            started=time.monotonic();first=None;buffers={'out':bytearray(),'err':bytearray()};timed_out=False
            process=subprocess.Popen(args,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
            selector=selectors.DefaultSelector()
            for stream,label in [(process.stdout,'out'),(process.stderr,'err')]:selector.register(stream,selectors.EVENT_READ,label)
            while selector.get_map():
                if time.monotonic()-started>90:process.kill();timed_out=True
                for item,_ in selector.select(timeout=.1):
                    data=os.read(item.fileobj.fileno(),65536)
                    if not data:selector.unregister(item.fileobj);continue
                    buffers[item.data].extend(data)
                    if item.data=='out' and first is None and data.strip():first=(time.monotonic()-started)*1000
            code=process.wait();selector.close()
            raw=buffers['out'].decode('utf-8');stderr=buffers['err'].decode('utf-8','replace')
            output=raw.strip().removesuffix('[end of text]').rstrip()
            row.update(exit_code=code,timeout=timed_out,eog_observed=raw.rstrip().endswith('[end of text]'),stdout=raw,stderr=stderr,
                process_ms=(time.monotonic()-started)*1000,first_stdout_ms_including_model_load=first,
                token_budget=budget,native_metrics=[x.strip() for x in stderr.splitlines() if 'eval time' in x or 'load time' in x or 'total time' in x])
        row.update(output=output,**score(case,output));report['cases'].append(row)
        destination.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
        print(case['id'],json.dumps({k:row[k] for k in ['route','fidelity_exact_words','layout_extra_breaks','layout_missing_breaks','process_ms','output']},ensure_ascii=False),flush=True)
    print('REPORT',destination,flush=True)
