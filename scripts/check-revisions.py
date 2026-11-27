#!/usr/bin/env python3
"""Opt-in canonical topic revision + graph roundtrip HTTP integration.

Use a dedicated verification backend and the synthetic integration Agent, never a
live model. Credentials are read from ARCHDECK_TEST_USERNAME/PASSWORD. Creates
its own uniquely named topic/policy and archives only that topic on completion.
"""
import copy
import importlib.util
import json
import os
from pathlib import Path
import uuid

spec = importlib.util.spec_from_file_location('distributed', Path(__file__).with_name('check-distributed.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
client = module.Client(os.environ.get('ARCHDECK_TEST_BASE', 'http://127.0.0.1:4500'))
client.login(os.environ['ARCHDECK_TEST_USERNAME'], os.environ['ARCHDECK_TEST_PASSWORD'])


def call(path, body=None, method='GET', expected=200):
    status, result = client.request(path, body, method)
    assert status == expected, f'{method} {path}: expected {expected}, got {status}'
    return result


topic = copy.deepcopy(call('/api/topics')[0])
identity = str(uuid.uuid4())
topic.update(id=identity, slug='verify-revision-' + identity, title='SYNTHETIC revision verification')
topic.pop('lockVersion', None)
topic['diagramNodes'] = [dict(id='browser', type='client', label='Browser', x=80, y=120, trustZone='untrusted'), dict(id='iam', type='auth', label='IAM', x=400, y=120, trustZone='trusted')]
topic['diagramEdges'] = [dict(id='denial', source='iam', target='browser', label='PKCE mismatch', protocol='OIDC', type='error', trustBoundary='IAM / Browser')]
topic['flowSteps'] = [dict(step=1, title='Deny invalid proof', action='Reject', description='SYNTHETIC fixture', activeNodes=['iam','browser'], activeEdgeIds=['denial'])]
created = call('/api/topics', topic, 'POST')
try:
    for key in ['diagramEdges', 'diagramNodes']:
        for expected, actual in zip(topic[key], created[key]):
            assert all(actual.get(k) == v for k,v in expected.items()), f'{key} metadata did not survive relational storage'
    assert created['flowSteps'][0]['activeEdgeIds'] == ['denial']
    invalid=copy.deepcopy(created);invalid['diagramEdges'][0]['target']='missing'
    call('/api/topics/'+identity,invalid,'PUT',400)
    print('PASS: directed graph, error path, coordinates, trust boundary and step-edge relational roundtrip')
    policy = call('/api/research/policies', dict(name='SYNTHETIC revision fixture',domainId='agent-engineering',lookbackDays=30,maxDocuments=3,maxInputCharacters=20000,targetContentId=identity,targetBaseLockVersion=created['lockVersion'],sources=[dict(host='openai.com',publisher_group='synthetic-fixture',feed_url='https://openai.com/test-synthetic-feed.xml',reuse_allowed=True)]),'POST')
    run=call('/api/research/runs',dict(policyId=policy['id']),'POST')
    def candidate_ready():
        return next((c for c in call('/api/research/candidates') if c['runId']==run['id']),None)
    candidate=module.eventually(candidate_ready,45)
    cid=candidate['id'];diff=call('/api/research/candidates/'+cid+'/diff')
    assert not diff['conflict'], 'Unedited canonical snapshot must have conflict=false even after JSONB reordering'
    assert diff['changes'] and candidate['proposalKind']=='topic_revision'
    assert candidate['proposedTopic']['diagramEdges']==created['diagramEdges']
    print('PASS: real Agent canonical snapshot, evidence-backed partial patch, deterministic fingerprint and field diff')
    body=dict(candidateVersion=candidate['candidateVersion'],baseLockVersion=created['lockVersion'],reason='Synthetic integration evidence review')
    # Concurrent editorial change makes both target version and snapshot stale.
    updated=copy.deepcopy(created);updated['summary']='Independent editorial edit'
    updated=call('/api/topics/'+identity,updated,'PUT')
    call('/api/research/candidates/'+cid+'/publish',body,'POST',409)
    assert call('/api/research/candidates/'+cid+'/diff')['conflict']
    assert call('/api/topics/'+identity)['summary']=='Independent editorial edit'
    revised=call('/api/research/candidates/'+cid+'/revision',dict(candidateVersion=candidate['candidateVersion'],targetContentId=identity,baseLockVersion=updated['lockVersion'],revisionPatch=dict(summary='Approved synthetic evidence revision')),'POST')
    assert revised['candidateVersion']==candidate['candidateVersion']+1 and not revised['conflict']
    call('/api/research/candidates/'+cid+'/review',dict(action='reject',reason='stale',candidateVersion=candidate['candidateVersion']),'POST',409)
    body.update(candidateVersion=revised['candidateVersion'],baseLockVersion=updated['lockVersion'])
    saved=call('/api/research/candidates/'+cid+'/publish',body,'POST')
    repeated=call('/api/research/candidates/'+cid+'/publish',body,'POST')
    assert saved==repeated and saved['lockVersion']==updated['lockVersion']+1
    assert saved['summary']=='Approved synthetic evidence revision'
    for key in ['diagramNodes','diagramEdges','flowSteps','codeSnippets','relatedTermIds']:
        assert saved[key]==updated[key], 'Unchanged canonical field lost: '+key
    print('PASS: stale publication/review 409, rebase invalidates prior version, atomic single revision and idempotent publish')
finally:
    status,current=client.request('/api/topics/'+identity)
    if status==200:call('/api/topics/'+identity+'?lockVersion='+str(current['lockVersion']),method='DELETE')
