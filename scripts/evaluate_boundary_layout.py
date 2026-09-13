#!/usr/bin/env python3
"""Research only: classify manually supplied candidate spans with the production JNI.
Candidate extraction is NOT validated by this first experiment. Timings are host only.
"""
import argparse, hashlib, json, subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
CASES=[
 ('list-feedback','list',['du lait','des oranges','du pain','du chocolat,','des roses','du riz'],'SSSSSS'),
 ('list-feedback-pin','list',['Du lait','des oranges','du pin','du chocolat,','des roses','du riz'],'SSSSSS'),
 ('list-flour','list',['de la farine','du pain','du lait','de la levure','du riz','des oranges'],'SSSSSS'),
 ('list-tea','list',['du thé à la menthe','du fromage de chèvre','des figues'],'SSS'),
 ('list-farm','list',['du lait','de la ferme','du pain'],'SJS'),
 ('list-mill','list',['de la farine','du moulin','des œufs'],'SJS'),
 ('list-complements','list',['la couleur','du pain','et la date','du rendez-vous'],'SJSJ'),
 ('list-actions','list',['Demain, appeler Julie pour confirmer les 23 élèves,','imprimer 2 fiches par élève','et apporter les cahiers bleus.'],'SSS'),
 ('mail-feedback-m','email',['Bonjour,','Voici un premier test qui vise à vérifier que le post-traitement sur le mail fonctionne comme il faut','cordialement','M. l’utilisateur.'],'SSSS'),
 ('mail-feedback-monsieur','email',['Bonjour,','voici un test qui vérifie que le post-traitement sur le mail fonctionne comme il faut','cordialement','Monsieur l’utilisateur.'],'SSSS'),
 ('mail-fr-named','email',['Bonsoir','Léa','tout est prêt','bien cordialement','Noé'],'SJSSS'),
 ('mail-fr-adverb','email',['Bonjour','je vous réponds','cordialement'],'SSJ'),
 ('mail-quoted','email',['Bonjour','le mot «','cordialement','» doit rester dans le texte','merci de vérifier'],'SSJJJ'),
 ('list-en','list',['2 notebooks,','5 blue pens','and 1 green folder.'],'SSS'),
 ('list-en-modifier','list',['a bag','of red apples','and some milk'],'SJS'),
 ('mail-en','email',['Hello','Morgan','the files are ready','kind regards','Sam'],'SJSSS'),
 ('mail-en-quoted','email',['Hello','Alex,','I wrote the word','thanks','on the card.'],'SJSJJ'),
]
SYSTEM={
 'list':"Label the consecutive fragments of a list. START begins a separate item. JOIN continues the previous item, including a noun's modifier. The first fragment is START. Return one numbered label per fragment and nothing else.",
 'email':"Label the consecutive fragments of an email. START begins a new part: greeting, body, closing or signature. JOIN continues the same part. Keep a quoted phrase together. The first fragment is START. Return one numbered label per fragment and nothing else.",
}
EXAMPLES={
 'list':[
  (['du café','de la cafetière','des serviettes','en papier','du sucre'],'SJSJS'),
  (['a blue jacket','with a hood','two white shirts'],'SJS')],
 'email':[
  (['Bonjour','Nadia,','voici le document demandé.','Cordialement,','Luc.'],'SJSSS'),
  (['Hi','Ben,','Please write','best wishes','on the label.'],'SJSJJ')],
}
def numbered(parts):return '\n'.join(f'{i+1}: {p}' for i,p in enumerate(parts))
def labels(plan):return '\n'.join(f'{i+1}: '+('START' if x=='S' else 'JOIN') for i,x in enumerate(plan))
def request(kind,parts,key,mode):
 out=('' if key.startswith('qwen') else '<|startoftext|>')+'<|im_start|>system\n'+SYSTEM[kind]+'<|im_end|>\n'
 examples=[] if mode=='brief' else EXAMPLES[kind]
 if mode=='balanced' and kind=='list':examples=[(['des carottes','des bananes','du beurre'],'SSS'),EXAMPLES[kind][1]]
 for chunks,plan in examples:out+='<|im_start|>user\n'+numbered(chunks)+'<|im_end|>\n<|im_start|>assistant\n'+labels(plan)+'<|im_end|>\n'
 return out+'<|im_start|>user\n'+numbered(parts)+'<|im_end|>\n<|im_start|>assistant\n'+('<think>\n\n</think>\n\n' if key=='qwen08' else '')
def grammar(parts):
 q=lambda x:json.dumps(x,ensure_ascii=False)
 return 'root ::= '+q('1: START')+''.join(' '+q(f'\n{i+1}: ')+' label' for i in range(1,len(parts)))+'\nlabel ::= "START" | "JOIN"\n'
def render(parts,plan,kind):
 out='• ' if kind=='list' else ''
 for i,p in enumerate(parts):out+=('' if i==0 else ('\n• ' if kind=='list' else '\n\n') if plan[i]=='S' else ' ')+p
 return out

def main():
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--model',choices=['lfm350','lfm12-q4km','qwen08','qwen15'],default='lfm350');parser.add_argument('--mode',choices=['original','brief','balanced'],default='original');args=parser.parse_args();key=args.model
 if key=='lfm350':model=ROOT/'app/src/localFormatPrototype/assets/local-format/LFM2.5-350M-Q4_K_M.gguf'
 else:
  cache=ROOT/'.native-cache/format-models'/key;model=cache/json.loads((cache/'manifest.json').read_text())['file']
 directory=ROOT/'.native-cache/boundary-layout-v1'/(key+'-'+args.mode);directory.mkdir(parents=True,exist_ok=True)
 for id,kind,parts,plan in CASES:
  for suffix,value in [('prompt',request(kind,parts,key,args.mode)),('grammar',grammar(parts)),('budget',str(32+12*len(parts)))]: (directory/(id+'.'+suffix)).write_text(value)
 (directory/'index.txt').write_text('\n'.join(c[0] for c in CASES)+'\n')
 jdk=Path('/home/ullie/.cache/dictai-build-tools/java/usr/lib/jvm/java-17-openjdk-amd64');build=ROOT/'.native-cache/local-format-jni-host'
 command=[str(jdk/'bin/java'),f'-Djava.library.path={build}/out','-cp',str(build/'java'),'com.kafkasl.phonewhisper.Main',str(model),str(directory)]
 with (directory/'jni.log').open('w') as log:subprocess.run(command,stdout=log,stderr=subprocess.STDOUT,check=True)
 rows=[]
 for id,kind,parts,expected in CASES:
  raw=(directory/(id+'.output')).read_text();first,total,complete=(directory/(id+'.metrics')).read_text().split('\t')
  pattern=[f'{i+1}: ' for i in range(len(parts))];lines=raw.splitlines()
  valid=complete=='true' and len(lines)==len(parts) and all(line in (prefix+'START',prefix+'JOIN') for line,prefix in zip(lines,pattern)) and lines[0]=='1: START'
  plan=''.join('S' if x.endswith('START') else 'J' for x in lines) if valid else None
  row={'id':id,'format':kind,'candidate_spans':parts,'expected_plan':expected,'plan':plan,'plan_exact':plan==expected,'raw_output':raw,'output':render(parts,plan,kind) if valid else None,'first_chunk_ms':int(first),'generation_ms':int(total),'completed':complete=='true','prompt':request(kind,parts,key,args.mode),'grammar':grammar(parts)}
  rows.append(row);print(id,plan,'expected',expected,'ms',total,flush=True)
 report={'model':key,'prompt_variant':args.mode,'model_sha256':hashlib.file_digest(model.open('rb'),'sha256').hexdigest(),'host_only':True,'candidates':'Manually supplied, including false boundaries. Candidate generation is a separate unvalidated stage.','jni_source_sha256':hashlib.sha256((ROOT/'app/src/main/cpp/llm/local_format_jni.cpp').read_bytes()).hexdigest(),'context':4096,'threads':2,'temperature':.1,'seed':1234,'cases':rows}
 (directory/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
 print('EXACT',sum(r['plan_exact'] for r in rows),'/',len(rows),'REPORT',directory/'report.json')
if __name__=='__main__':main()
