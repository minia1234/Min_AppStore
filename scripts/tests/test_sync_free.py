import json
import unittest
from unittest.mock import patch
from sync_free import build_catalog, collect_inventory
from test_sync import FakeGitHub
import test_sync as fixtures


class FreeSyncTests(unittest.TestCase):
    def setUp(self):
        fixture=fixtures.SyncTests(); fixture.setUp()
        self.manifest,self.asset=fixture.manifest,fixture.asset
        self.snapshot={'catalog':{'schemaVersion':1,'apps':[]},'registry':{'schemaVersion':1,'linked':['minia1234/app'],'disabled':[]}}

    def test_public_release_is_verified_then_uses_github_storage_only(self):
        actual={key:self.manifest[key] for key in ('packageName','versionCode','versionName','minSdk','signerSha256','sha256','size')}
        with patch('sync_free.inspect_apk',return_value=actual) as inspector:
            catalog=build_catalog(self.snapshot,FakeGitHub(self.manifest,self.asset),'https://minappstore.test')
        version=catalog['apps'][0]['versions'][0]
        self.assertNotIn('storageKey',version)
        self.assertEqual(version['githubUrl'],self.asset['browser_download_url'])
        self.assertEqual(version['downloadUrl'],'https://minappstore.test/downloads/dev.test.app/1.apk')
        inspector.assert_called_once()

    def test_wrong_signer_or_replaced_version_cannot_publish(self):
        with patch('sync_free.inspect_apk',return_value={**self.manifest,'signerSha256':'0'*64}),self.assertRaises(ValueError):
            build_catalog(self.snapshot,FakeGitHub(self.manifest,self.asset),'https://minappstore.test')

    def test_disabled_app_retains_pin_and_skips_upstream(self):
        old={'repository':'minia1234/app','packageName':'dev.test.app','versions':[self.manifest],'disabled':False}
        self.snapshot['catalog']['apps']=[old]
        self.snapshot['registry']['disabled']=['minia1234/app']
        upstream=FakeGitHub(self.manifest,self.asset)
        with patch.object(upstream,'json',side_effect=AssertionError('Disabled app must not fetch')):
            result=build_catalog(self.snapshot,upstream,'https://minappstore.test')
        self.assertTrue(result['apps'][0]['disabled'])
        self.assertEqual(result['apps'][0]['versions'][0]['signerSha256'],old['versions'][0]['signerSha256'])

    def test_unlinked_apps_are_excluded(self):
        self.snapshot['registry']['linked']=[]
        self.assertEqual(build_catalog(self.snapshot,FakeGitHub(self.manifest,self.asset),'https://minappstore.test')['apps'],[])

    def test_inventory_keeps_only_public_allowed_owner_records(self):
        class Source:
            def json(self,path,**options):
                owner='minia1234' if '/minia1234/' in path else 'AIProject-k'
                record={'full_name':owner+'/app','name':'app','owner':{'login':owner},'private':False,'description':'test','default_branch':'main'}
                return [record,{**record,'full_name':owner+'/private','private':True},{**record,'full_name':'outsider/app','owner':{'login':'outsider'}}]
        inventory=collect_inventory(Source())
        self.assertEqual([repo['full_name'] for repo in inventory],['minia1234/app','AIProject-k/app'])
        self.assertTrue(all(repo['private'] is False for repo in inventory))


if __name__=='__main__': unittest.main()
