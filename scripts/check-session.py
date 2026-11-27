#!/usr/bin/env python3
"""Read credentials from environment; exercise login/CSRF without printing secrets."""
import argparse
import http.cookiejar
import json
import os
import urllib.error
import urllib.parse
import urllib.request


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--base',default='http://127.0.0.1:4400')
    args=parser.parse_args()
    jar=http.cookiejar.CookieJar()
    client=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    def request(path,body=None,headers=None):
        data=None if body is None else body.encode()
        req=urllib.request.Request(args.base+path,data=data,headers=headers or {})
        try:
            with client.open(req,timeout=15) as response:
                text=response.read()
                return response.status,json.loads(text) if text else None
        except urllib.error.HTTPError as error:
            return error.code,None
    assert request('/api/topics')[0]==401,'Anonymous content access was not denied'
    status,csrf=request('/api/auth/csrf'); assert status==200
    credentials=urllib.parse.urlencode({'username':os.environ['ARCHDECK_TEST_USERNAME'],'password':os.environ['ARCHDECK_TEST_PASSWORD']})
    assert request('/api/auth/login',credentials,{'Content-Type':'application/x-www-form-urlencoded'})[0]==403,'CSRF login protection failed'
    status,csrf=request('/api/auth/csrf')
    headers={'Content-Type':'application/x-www-form-urlencoded',csrf['headerName']:csrf['token']}
    status,_=request('/api/auth/login',credentials,headers);assert status==200, 'Login failed: '+str(status)
    status,user=request('/api/auth/me');assert status==200
    if user['mustChangePassword']:
        assert request('/api/topics')[0]==403
        print('PASS: unauthenticated access, login CSRF, authenticated session and required password gate')
        return
    status,topics=request('/api/topics');assert status==200 and len(topics)>=5
    status,terms=request('/api/glossary');assert status==200 and len(terms)>=10
    status,comparisons=request('/api/comparisons');assert status==200 and len(comparisons)>=3
    status,csrf=request('/api/auth/csrf')
    assert request('/api/auth/logout','', {csrf['headerName']:csrf['token']})[0] in {200,204}
    assert request('/api/topics')[0]==401
    print('PASS: anonymous denial, login CSRF, session, content counts and logout')


if __name__=='__main__':main()
