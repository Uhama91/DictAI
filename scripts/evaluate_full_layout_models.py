#!/usr/bin/env python3
"""Compare full-source layout on the production JNI; no hand-selected spans enter the prompt.
The fixed few-shot prompt predates these cases. Phone speed must be measured separately.
"""
import argparse, hashlib, json, re, subprocess
from datetime import datetime, timezone
from pathlib import Path
from evaluate_boundary_layout import CASES, render
from compare_local_format_models import ROOT, CACHE, CANDIDATES

def quote(s): return json.dumps(s, ensure_ascii=False)
def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--model', choices=['lfm350',*CANDIDATES], required=True)
    parser.add_argument('--prompt-style', choices=['examples','brief'], default='examples')
    parser.add_argument('--decoding', choices=['faithful','free'], default='faithful')
    parser.add_argument('--ids', nargs='+', help='Only these case IDs, in supplied order')
    parser.add_argument('--threads', type=int, choices=range(1,9), default=2)
    args=parser.parse_args();key=args.model
    known={case[0]:case for case in CASES}
    selected=args.ids or list(known)
    if len(set(selected))!=len(selected) or any(case_id not in known for case_id in selected):
        parser.error('--ids must contain distinct known IDs: '+', '.join(known))
    if key=='lfm350': model=ROOT/'app/src/localFormatPrototype/assets/local-format/LFM2.5-350M-Q4_K_M.gguf'
    else:
        meta=json.loads((CACHE/key/'manifest.json').read_text());model=CACHE/key/meta['file']
    previous=json.loads((ROOT/'docs/benchmarks/local-format/feedback-model-comparison-2026-09-09.json').read_text())
    prefixes={kind:next(row['prompt'].rsplit('<|im_start|>user\n',1)[0] for row in previous['cases'] if row['experiment']=='qwen08' and row['format']==kind) for kind in ['list','email']}
    stamp=datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    directory=ROOT/'.native-cache/full-layout-v3'/(key+'-'+args.prompt_style+'-'+args.decoding+'-t'+str(args.threads)+'-'+stamp);directory.mkdir(parents=True)
    print('RUN_DIRECTORY',directory,flush=True)
    inputs=[]
    for id,kind,parts,expected in (known[case_id] for case_id in selected):
        source=' '.join(parts);words=source.split();prefix=prefixes[kind]
        if args.prompt_style=='brief':prefix=prefix.split('<|im_end|>')[0]+'<|im_end|>\n'
        if not key.startswith('qwen'):prefix='<|startoftext|>'+prefix
        prompt=prefix+'<|im_start|>user\n'+source+'<|im_end|>\n<|im_start|>assistant\n'+('<think>\n\n</think>\n\n' if key in ['qwen08','qwen2'] else '')
        grammar='root ::= '+('"• " ' if kind=='list' else '')+' sep '.join(quote(word) for word in words)+'\nsep ::= " " | '+quote('\n• ' if kind=='list' else '\n\n')+'\n'
        if args.decoding=='free':grammar='root ::= [^\\x00]*\n'
        budget=min(2048,max(192,len(source.encode('utf-16-le'))//2+128))
        for suffix,value in [('prompt',prompt),('grammar',grammar),('budget',str(budget))]: (directory/(id+'.'+suffix)).write_text(value)
        inputs.append({'id':id,'format':kind,'source':source,'expected_output':render(parts,expected,kind),'prompt':prompt,'grammar':grammar,'token_budget':budget})
    (directory/'index.txt').write_text('\n'.join(row['id'] for row in inputs)+'\n')
    jdk=Path('/home/ullie/.cache/dictai-build-tools/java/usr/lib/jvm/java-17-openjdk-amd64');build=ROOT/'.native-cache/local-format-jni-host'
    driver=ROOT/'scripts/LocalFormatFixtureRunner.java'
    classes=ROOT/'.native-cache/local-format-fixture-runner/java';classes.mkdir(parents=True,exist_ok=True)
    subprocess.run([str(jdk/'bin/javac'),'-encoding','UTF-8','-cp',str(build/'java'),'-d',str(classes),str(driver)],check=True)
    command=[str(jdk/'bin/java'),f'-Djava.library.path={build}/out','-cp',str(classes)+':'+str(build/'java'),'com.kafkasl.phonewhisper.LocalFormatFixtureRunner',str(model),str(directory),str(args.threads),'20000']
    interrupted=False
    with (directory/'jni.log').open('w') as log, subprocess.Popen(command,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,bufsize=1) as process:
        try:
            for line in process.stdout:
                log.write(line);log.flush();print(line.rstrip(),flush=True)
            return_code=process.wait()
        except KeyboardInterrupt:
            interrupted=True;process.terminate()
            try:process.wait(timeout=5)
            except subprocess.TimeoutExpired:process.kill();process.wait()
            return_code=process.returncode
    rows=[]
    for row in inputs:
        id=row['id']
        if not (directory/(id+'.metrics')).exists():continue
        raw=(directory/(id+'.output')).read_text();first,total,complete=(directory/(id+'.metrics')).read_text().split('\t')
        lines=[line for line in raw.strip().splitlines() if line.strip()]
        shape=row['format']!='list' or bool(lines) and all(line.startswith('• ') and line[2:].strip() for line in lines)
        words=' '.join(line[2:] for line in lines).split() if row['format']=='list' else raw.split()
        fidelity=shape and words==row['source'].split() and complete=='true'
        lexical=lambda value:re.findall(r'[^\W_]+', value.casefold())
        lexical_same=lexical(raw)==lexical(row['source']) and complete=='true'
        exact=raw.strip()==row['expected_output'] if fidelity else None
        partial=(directory/(id+'.partial')).read_text()
        rows.append({**row,'output':raw,'partial_output':partial,'quality_evaluated':fidelity,'completed':complete=='true','fidelity':fidelity,'lexical_sequence_equal_ignoring_case_punctuation':lexical_same,'exact_grouping':exact,'first_chunk_ms':int(first),'generation_ms':int(total)})
        print(id,'fidelity',fidelity,'exact',exact,'ms',total,json.dumps(raw,ensure_ascii=False),flush=True)
    report={'model':key,'selected_ids':selected,'run_status':'interrupted' if interrupted else 'completed' if return_code==0 and len(rows)==len(inputs) else 'failed','exit_code':return_code,'deadline_ms':20000,'smoke_tests_repeated':False,'model_and_context_load_ms':int((directory/'model-load.metrics').read_text()) if (directory/'model-load.metrics').exists() else None,'thinking_enabled':False,'prompt_style':args.prompt_style,'decoding':args.decoding,'model_sha256':hashlib.file_digest(model.open('rb'),'sha256').hexdigest(),'host_only':True,'runtime':'production JNI, model resident, x86_64, no concurrent model/build jobs','driver_sha256':hashlib.sha256(driver.read_bytes()).hexdigest(),'jni_binary_sha256':hashlib.sha256((build/'out/libdictai_llm.so').read_bytes()).hexdigest(),'jni_source_sha256':hashlib.sha256((ROOT/'app/src/main/cpp/llm/local_format_jni.cpp').read_bytes()).hexdigest(),'context':4096,'threads':args.threads,'temperature':.1,'top_k':50,'repeat_penalty':1.05,'seed':1234,'limitations':['Generation includes prefill but excludes model/context loading; not phone latency. File cache is not reset. No smoke-test warmup.', 'Incomplete generations retain their last cumulative fragment for diagnostics only; their grouping is not scored.','Prompt and grammar see the entire source only. Hand-selected spans are used solely to specify expected outputs.','Exact grouping is a strict reference match, not a general semantic quality metric; inspect alternatives manually.'],'cases':rows}
    (directory/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print('COMPLETED',sum(r['completed'] for r in rows),'/',len(inputs),flush=True)
    print('EXACT',sum(r['exact_grouping'] is True for r in rows),'/',len(rows),'REPORT',directory/'report.json')
if __name__=='__main__':main()
