import base64
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile
from sync_free import apk_label_icon, build_catalog, collect_inventory
from test_sync import FakeGitHub
import test_sync as fixtures

PNG=b'\x89PNG\r\n\x1a\n'+b'0'*20


class ApkOnlyGitHub(FakeGitHub):
    """Releases carry a single APK and no store-manifest.json."""
    def json(self,path,**kwargs):
        if '/releases?' in path:
            return [{'draft':False,'prerelease':False,'published_at':'2026-10-21T00:00:00Z','body':'노트','assets':[dict(self.asset,download_count=3)]}]
        return super().json(path,**kwargs)


class FreeSyncTests(unittest.TestCase):
    def setUp(self):
        fixture=fixtures.SyncTests(); fixture.setUp()
        self.manifest,self.asset=fixture.manifest,fixture.asset
        self.snapshot={'catalog':{'schemaVersion':1,'apps':[]},'registry':{'schemaVersion':1,'linked':['minia1234/app'],'disabled':[]}}

    def test_public_release_is_verified_then_uses_github_storage_only(self):
        actual={key:self.manifest[key] for key in ('packageName','versionCode','versionName','minSdk','signerSha256','sha256','size')}
        with patch('sync_free.inspect_apk',return_value=actual) as inspector:
            catalog,failures=build_catalog(self.snapshot,FakeGitHub(self.manifest,self.asset),'https://minappstore.test')
        self.assertEqual(failures,[])
        version=catalog['apps'][0]['versions'][0]
        self.assertNotIn('storageKey',version)
        self.assertEqual(version['githubUrl'],self.asset['browser_download_url'])
        self.assertEqual(version['downloadUrl'],'https://minappstore.test/downloads/dev.test.app/1.apk')
        inspector.assert_called_once()

    def test_wrong_signer_or_replaced_version_cannot_publish(self):
        with patch('sync_free.inspect_apk',return_value={**self.manifest,'signerSha256':'0'*64}):
            catalog,failures=build_catalog(self.snapshot,FakeGitHub(self.manifest,self.asset),'https://minappstore.test')
        self.assertEqual(catalog['apps'],[])
        self.assertIn('Actual APK identity differs',failures[0])

    def test_broken_repository_keeps_previous_entry_while_others_publish(self):
        actual={key:self.manifest[key] for key in ('packageName','versionCode','versionName','minSdk','signerSha256','sha256','size')}
        previous={'repository':'minia1234/broken','packageName':'dev.test.broken','versions':[{**self.manifest,'packageName':'dev.test.broken'}],'disabled':False}
        self.snapshot['catalog']['apps']=[previous]
        self.snapshot['registry']['linked']=['minia1234/app','minia1234/broken']
        # FakeGitHub answers every repository lookup as minia1234/app, so the broken one fails its identity check.
        with patch('sync_free.inspect_apk',return_value=actual):
            catalog,failures=build_catalog(self.snapshot,FakeGitHub(self.manifest,self.asset),'https://minappstore.test')
        self.assertEqual([app['repository'] for app in catalog['apps']],['minia1234/app','minia1234/broken'])
        self.assertEqual(catalog['apps'][1],previous)
        self.assertEqual(len(failures),1)
        self.assertTrue(failures[0].startswith('minia1234/broken: ValueError'))

    def test_disabled_app_retains_pin_and_skips_upstream(self):
        old={'repository':'minia1234/app','packageName':'dev.test.app','versions':[self.manifest],'disabled':False}
        self.snapshot['catalog']['apps']=[old]
        self.snapshot['registry']['disabled']=['minia1234/app']
        upstream=FakeGitHub(self.manifest,self.asset)
        with patch.object(upstream,'json',side_effect=AssertionError('Disabled app must not fetch')):
            result,_=build_catalog(self.snapshot,upstream,'https://minappstore.test')
        self.assertTrue(result['apps'][0]['disabled'])
        self.assertEqual(result['apps'][0]['versions'][0]['signerSha256'],old['versions'][0]['signerSha256'])

    def test_unlinked_apps_are_excluded(self):
        self.snapshot['registry']['linked']=[]
        self.assertEqual(build_catalog(self.snapshot,FakeGitHub(self.manifest,self.asset),'https://minappstore.test')[0]['apps'],[])

    def actual(self):
        return {key:self.manifest[key] for key in ('packageName','versionCode','versionName','minSdk','signerSha256','sha256','size')}

    def apk_only_app(self):
        with patch('sync_free.inspect_apk',return_value=self.actual()),patch('sync_free.apk_label_icon',return_value=('라벨 앱',PNG)):
            catalog,failures=build_catalog(self.snapshot,ApkOnlyGitHub(self.manifest,self.asset),'https://minappstore.test')
        self.assertEqual(failures,[])
        return catalog['apps'][0]

    def test_apk_only_release_uses_apk_label_and_icon(self):
        app=self.apk_only_app()
        self.assertEqual((app['name'],app['summary'],app['author'],app['downloadCount']),('라벨 앱','라벨 앱','minia1234',3))
        self.assertEqual(app['iconUrl'],'https://minappstore.test/icons/dev.test.app/1.png')
        self.assertEqual(base64.b64decode(app['iconData']),PNG)
        self.assertEqual(app['versions'][0]['sha256'],self.manifest['sha256'])

    def test_known_apk_only_release_is_not_downloaded_again(self):
        previous=self.apk_only_app()
        self.snapshot['catalog']['apps']=[previous]
        upstream=ApkOnlyGitHub(self.manifest,self.asset)
        with patch.object(upstream,'download',side_effect=AssertionError('Known APK must not download')):
            catalog,failures=build_catalog(self.snapshot,upstream,'https://minappstore.test')
        self.assertEqual(failures,[])
        self.assertEqual(catalog['apps'][0],previous)

    def test_inventory_does_not_link_repositories(self):
        self.snapshot['registry']={'schemaVersion':1,'linked':[],'disabled':['AIProject-k/hidden']}
        inventory=[{'full_name':'minia1234/app'},{'full_name':'AIProject-k/hidden'}]
        with patch('sync_free.inspect_apk',return_value=self.actual()),patch('sync_free.apk_label_icon',return_value=('앱',None)):
            catalog,failures=build_catalog(self.snapshot,ApkOnlyGitHub(self.manifest,self.asset),'https://minappstore.test',inventory)
        self.assertEqual(failures,[])
        self.assertEqual(catalog['apps'],[])

    def test_apk_icon_is_the_largest_raster_under_the_size_cap(self):
        badging="application-label:'내 앱'\napplication-icon-640:'res/big.png'\napplication-icon-480:'res/mid.png'\napplication-icon-65534:'res/adaptive.xml'\n"
        with tempfile.TemporaryDirectory() as directory:
            apk=Path(directory)/'app.apk'
            with zipfile.ZipFile(apk,'w') as archive:
                archive.writestr('res/big.png',PNG+b'0'*13_000)
                archive.writestr('res/mid.png',PNG)
                archive.writestr('res/adaptive.xml',b'<adaptive-icon/>')
            with patch('sync_free.sdk_tool',return_value='aapt'),patch('sync_free.subprocess.check_output',return_value=badging):
                self.assertEqual(apk_label_icon(apk),('내 앱',PNG))

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
