import urllib.request,urllib.error,json,pathlib
base='https://friction-v1-api-production.up.railway.app'
assert 'function canUndo(p)' in urllib.request.urlopen(base+'/app.js').read().decode(), 'Updated UI not live yet'
token=None
def api(route,body=None):
 headers={'Content-Type':'application/json'}
 if token:headers['Authorization']='Bearer '+token
 req=urllib.request.Request(base+'/api/v1'+route,data=None if body is None else json.dumps(body).encode(),headers=headers)
 with urllib.request.urlopen(req,timeout=30) as r:return json.load(r)
identity=api('/installations',{});token=identity['token'];before=api('/state')
assert any(m['id']=='gpt-6-sol' for m in before['assistantOptions'])
settings={'model':'gpt-6-sol','reasoning':'low','serviceTier':'fast'}
api('/assistant-settings',settings);assert api('/state')['assistantSettings']==settings
p=api('/proposals',{'changes':[{'action':'set_setting','field':'waitSeconds','value':9}],'expectedRevision':0,'title':'Verification: latest action'})
api('/proposals/'+p['id']+'/confirm',{'version':p['version']})
q=api('/proposals',{'changes':[{'action':'remove_task','taskId':'sprint'}],'expectedRevision':1,'title':'Verification: task restoration'})
api('/proposals/'+q['id']+'/confirm',{'version':q['version']})
for args in [{'proposalId':p['id']},{'proposalId':q['id'],'cardId':'task:sprint'}]:
 try:api('/cancel',args);raise AssertionError('Undo should have been rejected')
 except urllib.error.HTTPError as e:assert e.code==409
api('/cancel',{'proposalId':q['id']});after=api('/state')
assert after['config']['tasks']==before['config']['tasks']
assert after['config']['settings']['waitSeconds']==9
assert after['undoableChangeId'] is None
try:api('/cancel',{'proposalId':p['id']});raise AssertionError('Older undo should remain blocked')
except urllib.error.HTTPError as e:assert e.code==409
result={'passed':True,'syntheticInstallation':identity['installationId'],'settings':after['assistantSettings'],'taskOrderRestored':True,'olderUndoRejected':True,'partialUndoRejected':True,'noUndoChain':True,'revision':after['revision']}
pathlib.Path('work/undo-sol-2026-09-23/live-result.json').write_text(json.dumps(result,indent=2));print(json.dumps(result))
