import unittest
import io
import json
from unittest.mock import patch
from sync_store import MAX_A, RUN_A, RUN_B, Storage, validate_manifest, choose_latest, reserve_budget, projected_upload, synchronize

class Missing(Exception):
    response={'Error':{'Code':'NoSuchKey'}}

class MemoryS3:
    def __init__(self):
        self.objects={'indi-store-bucket.json':json.dumps({'dedicatedTo':'indi-store','owner':'minia1234'}).encode()}
        self.objects['repositories.json']=json.dumps({'schemaVersion':1,'updatedAt':'2026-10-21T00:00:00Z','linked':['minia1234/app']}).encode()
        self.sizes={}
        self.operations=[]
        self.fail_publish=False
    def get_object(self,Bucket,Key):
        if Key not in self.objects:raise Missing()
        return {'Body':io.BytesIO(self.objects[Key]),'ETag':'"old"'}
    def put_object(self,Bucket,Key,Body,**kwargs):
        if kwargs.get('IfNoneMatch')=='*' and Key in self.objects:raise ValueError('Precondition failed')
        if Key=='catalog.json' and self.fail_publish:raise ValueError('Concurrent catalog changed')
        self.operations.append(('put',Key,kwargs))
        self.objects[Key]=Body.read() if hasattr(Body,'read') else Body
        return {'ETag':'"new"'}
    def delete_object(self,Bucket,Key):
        self.operations.append(('delete',Key,{}))
        self.objects.pop(Key,None)
    def list_objects_v2(self,**kwargs):
        return {'Contents':[{'Key':key,'Size':self.sizes.get(key,len(value))} for key,value in self.objects.items()]}

class FakeGitHub:
    def __init__(self,manifest,asset):
        self.manifest,self.asset=manifest,asset
        self.reads=[]
        self.webp=b'RIFF'+(12).to_bytes(4,'little')+b'WEBPVP8 '+b'\x00'*4
    def json(self,path,**kwargs):
        if path.startswith('/users/'):
            return [{'full_name':'minia1234/app','owner':{'login':'minia1234'},'topics':['indi-store'],'private':False}]
        if '/releases?' not in path:
            return {'full_name':'minia1234/app','owner':{'login':'minia1234'},'private':False,'topics':[]}
        return [{'draft':False,'prerelease':False,'published_at':'2026-10-21T00:00:00Z','body':'실제 변경','assets':[dict(self.asset,download_count=7),{'name':'store-manifest.json','size':1000,'browser_download_url':'https://github.com/minia1234/app/releases/download/v1/store-manifest.json'}]}]
    def read(self,url,*args):
        self.reads.append(url)
        return self.webp if url.endswith('.webp') else json.dumps(self.manifest).encode()
    def download(self,url,size,path):
        path.write_bytes(b'x'*size)
        return self.manifest['sha256']

class SyncTests(unittest.TestCase):
    def setUp(self):
        self.pin={'packageName':'dev.test.app','signerSha256':'a'*64}
        self.asset={'name':'app.apk','size':100,'digest':'sha256:'+'b'*64,'browser_download_url':'https://github.com/minia1234/app/releases/download/v1/app.apk'}
        self.manifest={'schemaVersion':1,'packageName':'dev.test.app','name':'테스트','summary':'요약','description':'설명','category':'도구','author':'minia1234','tags':[],'iconUrl':'','screenshots':[],'versionCode':1,'versionName':'1','minSdk':26,'sha256':'b'*64,'signerSha256':'a'*64,'size':100,'asset':'app.apk'}
    def test_manifest_matches_public_asset_and_pin(self):
        self.assertEqual(validate_manifest(self.manifest,[self.asset],self.pin,'minia1234/app')['versionCode'],1)
        for key,value in [('sha256','c'*64),('size',101),('packageName','dev.other.app'),('signerSha256','c'*64),('name','YOUR_APP_NAME')]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate_manifest({**self.manifest,key:value},[self.asset],self.pin,'minia1234/app')
    def test_latest_uses_version_code(self):
        self.assertEqual(choose_latest([{'versionCode':2},{'versionCode':12},{'versionCode':4}])['versionCode'],12)
    def test_budget_reserves_failed_runs_and_monthly_ceiling(self):
        first=reserve_budget({},'2026-10',100,200)
        self.assertEqual(reserve_budget(first,'2026-10',100,200)['classA'],200)
        with self.assertRaises(ValueError):reserve_budget({'month':'2026-10','classA':MAX_A,'classB':0},'2026-10',100,200)
    def test_no_upload_at_8gb_and_never_reclaims_before_publish(self):
        self.assertFalse(projected_upload(7_999_999_900,100))
        self.assertTrue(projected_upload(7_999_999_800,100))
        self.assertFalse(projected_upload(9_000_000_000,1))
    def actual(self):
        return {key:self.manifest[key] for key in ('packageName','versionCode','versionName','minSdk','signerSha256','sha256','size')}
    def test_full_bucket_falls_back_to_github_and_counts_real_downloads(self):
        s3=MemoryS3();s3.objects['apks/orphan.apk']=b'';s3.sizes['apks/orphan.apk']=8_000_000_000
        with patch('sync_store.inspect_apk',return_value=self.actual()):
            catalog=synchronize(Storage(s3,'bucket'),FakeGitHub(self.manifest,self.asset),'minia1234','https://store.example.com')
        app=catalog['apps'][0]
        self.assertEqual(app['downloadCount'],7)
        self.assertNotIn('storageKey',app['versions'][0])
        self.assertEqual(app['versions'][0]['downloadUrl'],'https://store.example.com/downloads/dev.test.app/1.apk')
        publication=next(i for i,op in enumerate(s3.operations) if op[:2]==('put','catalog.json'))
        cleanup=next(i for i,op in enumerate(s3.operations) if op[:2]==('delete','apks/orphan.apk'))
        self.assertLess(publication,cleanup)
    def test_bad_actual_signer_preserves_catalog_and_apks(self):
        s3=MemoryS3();s3.objects['catalog.json']=b'{"schemaVersion":1,"apps":[]}'
        s3.objects['apks/old.apk']=b'keep'
        with patch('sync_store.inspect_apk',return_value={**self.actual(),'signerSha256':'c'*64}),self.assertRaises(ValueError):
            synchronize(Storage(s3,'bucket'),FakeGitHub(self.manifest,self.asset),'minia1234','https://store.example.com')
        self.assertEqual(s3.objects['catalog.json'],b'{"schemaVersion":1,"apps":[]}')
        self.assertEqual(s3.objects['apks/old.apk'],b'keep')
        self.assertIn('sync-budget.json',s3.objects)
    def test_failed_conditional_publication_never_deletes_apks(self):
        s3=MemoryS3();s3.objects['apks/old.apk']=b'keep';s3.fail_publish=True
        with patch('sync_store.inspect_apk',return_value=self.actual()),self.assertRaises(ValueError):
            synchronize(Storage(s3,'bucket'),FakeGitHub(self.manifest,self.asset),'minia1234','https://store.example.com')
        self.assertEqual(s3.objects['apks/old.apk'],b'keep')
    def test_first_registration_inspects_even_existing_orphan_key(self):
        s3=MemoryS3()
        key=f"apks/dev.test.app/1/{'b'*64}.apk"
        s3.objects[key]=b'x'*100
        with patch('sync_store.inspect_apk',return_value={**self.actual(),'signerSha256':'c'*64}),self.assertRaises(ValueError):
            synchronize(Storage(s3,'bucket'),FakeGitHub(self.manifest,self.asset),'minia1234','https://store.example.com')
    def test_unverified_orphan_is_not_served_even_when_its_size_matches(self):
        s3=MemoryS3();key=f"apks/dev.test.app/1/{'b'*64}.apk";s3.objects[key]=b'?'*100
        with patch('sync_store.inspect_apk',return_value=self.actual()):
            catalog=synchronize(Storage(s3,'bucket'),FakeGitHub(self.manifest,self.asset),'minia1234','https://store.example.com')
        self.assertNotIn('storageKey',catalog['apps'][0]['versions'][0])
        self.assertNotIn(key,s3.objects)
    def test_existing_lock_blocks_another_sync_without_deleting_it(self):
        s3=MemoryS3();s3.objects['sync-lock.json']=b'{"id":"other"}'
        with self.assertRaises(ValueError):synchronize(Storage(s3,'bucket'),FakeGitHub(self.manifest,self.asset),'minia1234','https://store.example.com')
        self.assertEqual(s3.objects['sync-lock.json'],b'{"id":"other"}')
    def test_uncertain_storage_write_keeps_lock_for_operator_review(self):
        s3=MemoryS3()
        original=s3.put_object
        def timeout(**kwargs):
            if kwargs['Key']=='catalog.json':raise TimeoutError('Write may still commit')
            return original(**kwargs)
        s3.put_object=timeout
        with patch('sync_store.inspect_apk',return_value=self.actual()),self.assertRaises(TimeoutError):
            synchronize(Storage(s3,'bucket'),FakeGitHub(self.manifest,self.asset),'minia1234','https://store.example.com')
        self.assertIn('sync-lock.json',s3.objects)
    def test_cross_repo_package_takeover_is_rejected(self):
        s3=MemoryS3();s3.objects['catalog.json']=json.dumps({'schemaVersion':1,'apps':[{'repository':'minia1234/first','packageName':'dev.test.app','versions':[self.actual()]}]}).encode()
        before=s3.objects['catalog.json']
        with self.assertRaises(ValueError):synchronize(Storage(s3,'bucket'),FakeGitHub(self.manifest,self.asset),'minia1234','https://store.example.com')
        self.assertEqual(s3.objects['catalog.json'],before)
    def test_origin_rejects_queries_fragments_and_paths_before_any_write(self):
        for origin in ('https://store.example.com?x=1','https://store.example.com#fragment','https://store.example.com/path'):
            with self.subTest(origin=origin):
                s3=MemoryS3()
                with self.assertRaises(ValueError):synchronize(Storage(s3,'bucket'),FakeGitHub(self.manifest,self.asset),'minia1234',origin)
                self.assertEqual(s3.operations,[])
    def test_owner_webp_icon_and_screenshots_are_mirrored(self):
        self.manifest.update(iconUrl='https://raw.githubusercontent.com/minia1234/app/main/icon.webp',screenshots=['https://github.com/minia1234/app/releases/download/v1/screen.webp'])
        s3=MemoryS3();github=FakeGitHub(self.manifest,self.asset)
        with patch('sync_store.inspect_apk',return_value=self.actual()):
            catalog=synchronize(Storage(s3,'bucket'),github,'minia1234','https://store.example.com')
        app=catalog['apps'][0]
        self.assertTrue(app['iconUrl'].startswith('https://store.example.com/media/'))
        self.assertEqual(app['iconUrl'],app['screenshots'][0])
        key=app['iconUrl'].split('https://store.example.com/')[1]
        self.assertEqual(s3.objects[key],github.webp)
        self.assertEqual(app['mediaSources'][key.split('/')[1][:-5]],self.manifest['iconUrl'])
    def test_full_bucket_keeps_original_image_url(self):
        self.manifest['iconUrl']='https://raw.githubusercontent.com/minia1234/app/main/icon.webp'
        s3=MemoryS3();s3.objects['apks/orphan.apk']=b'';s3.sizes['apks/orphan.apk']=8_000_000_000
        with patch('sync_store.inspect_apk',return_value=self.actual()):
            catalog=synchronize(Storage(s3,'bucket'),FakeGitHub(self.manifest,self.asset),'minia1234','https://store.example.com')
        self.assertEqual(catalog['apps'][0]['iconUrl'],self.manifest['iconUrl'])
        self.assertFalse(any(key.startswith('media/') for key in s3.objects))
    def test_external_images_are_not_fetched(self):
        self.manifest['iconUrl']='https://images.example.com/icon.webp'
        s3=MemoryS3();github=FakeGitHub(self.manifest,self.asset)
        with patch('sync_store.inspect_apk',return_value=self.actual()):
            catalog=synchronize(Storage(s3,'bucket'),github,'minia1234','https://store.example.com')
        self.assertEqual(catalog['apps'][0]['iconUrl'],self.manifest['iconUrl'])
        self.assertNotIn(self.manifest['iconUrl'],github.reads)
    def test_bad_webp_preserves_published_catalog_and_media(self):
        self.manifest['iconUrl']='https://raw.githubusercontent.com/minia1234/app/main/icon.webp'
        s3=MemoryS3();s3.objects['catalog.json']=b'{"schemaVersion":1,"apps":[]}'
        old_key='media/'+('c'*64)+'.webp';s3.objects[old_key]=b'keep'
        github=FakeGitHub(self.manifest,self.asset);github.webp=b'not a webp'
        with patch('sync_store.inspect_apk',return_value=self.actual()),self.assertRaises(ValueError):
            synchronize(Storage(s3,'bucket'),github,'minia1234','https://store.example.com')
        self.assertEqual(s3.objects['catalog.json'],b'{"schemaVersion":1,"apps":[]}')
        self.assertEqual(s3.objects[old_key],b'keep')
    def test_media_cleanup_only_happens_after_publication(self):
        self.manifest['iconUrl']='https://raw.githubusercontent.com/minia1234/app/main/icon.webp'
        for failed in (False,True):
            with self.subTest(failed=failed):
                s3=MemoryS3();key='media/'+('c'*64)+'.webp';s3.objects[key]=b'keep';s3.fail_publish=failed
                with patch('sync_store.inspect_apk',return_value=self.actual()):
                    if failed:
                        with self.assertRaises(ValueError):synchronize(Storage(s3,'bucket'),FakeGitHub(self.manifest,self.asset),'minia1234','https://store.example.com')
                        self.assertEqual(s3.objects[key],b'keep')
                    else:
                        synchronize(Storage(s3,'bucket'),FakeGitHub(self.manifest,self.asset),'minia1234','https://store.example.com')
                        publication=next(i for i,op in enumerate(s3.operations) if op[:2]==('put','catalog.json'))
                        cleanup=next(i for i,op in enumerate(s3.operations) if op[:2]==('delete',key))
                        self.assertLess(publication,cleanup)
    def test_publisher_metadata_requires_webp_images(self):
        for changes in ({'iconUrl':'https://github.com/minia1234/app/releases/download/v1/icon.png'},{'screenshots':['https://images.example.com/screen.jpg']}):
            with self.subTest(changes=changes),self.assertRaises(ValueError):
                validate_manifest({**self.manifest,**changes},[self.asset],self.pin,'minia1234/app')
    def test_discovery_reaches_owner_repositories_after_first_hundred(self):
        github=FakeGitHub(self.manifest,self.asset);original=github.json
        def pages(path,**kwargs):
            if path.startswith('/users/') and 'page=2' not in path:
                return [{'full_name':f'minia1234/other{i}','topics':[],'private':False} for i in range(100)]
            return original(path)
        github.json=pages
        with patch('sync_store.inspect_apk',return_value=self.actual()):
            catalog=synchronize(Storage(MemoryS3(),'bucket'),github,'minia1234','https://store.example.com')
        self.assertEqual(len(catalog['apps']),1)
    def test_known_github_fallback_version_does_not_repeat_apk_transfer(self):
        s3=MemoryS3();s3.objects['apks/fill.apk']=b'';s3.sizes['apks/fill.apk']=8_000_000_000
        with patch('sync_store.inspect_apk',return_value=self.actual()):
            synchronize(Storage(s3,'bucket'),FakeGitHub(self.manifest,self.asset),'minia1234','https://store.example.com')
        s3.objects['apks/fill.apk']=b''
        github=FakeGitHub(self.manifest,self.asset)
        def unexpected(*args):raise AssertionError('Verified fallback APK must not download again')
        github.download=unexpected
        with patch('sync_store.inspect_apk',side_effect=AssertionError('No repeated SDK inspection')):
            synchronize(Storage(s3,'bucket'),github,'minia1234','https://store.example.com')
    def test_one_hundred_apps_with_nine_images_fit_reserved_operation_budget(self):
        template=self.manifest
        class HundredApps:
            def json(self,path,**kwargs):
                if path.startswith('/users/'):
                    return [] if 'page=2' in path else [{'full_name':f'minia1234/app{i}','owner':{'login':'minia1234'},'topics':['indi-store'],'private':False} for i in range(100)]
                self.repo=path.split('/')[3]
                if '/releases?' not in path:
                    return {'full_name':'minia1234/'+self.repo,'owner':{'login':'minia1234'},'private':False}
                self.manifest={**template,'packageName':'dev.test.'+self.repo,'iconUrl':f'https://raw.githubusercontent.com/minia1234/{self.repo}/main/icon.webp','screenshots':[f'https://raw.githubusercontent.com/minia1234/{self.repo}/main/screen{i}.webp' for i in range(8)]}
                self.asset={'name':'app.apk','size':100,'digest':'sha256:'+'b'*64,'browser_download_url':f'https://github.com/minia1234/{self.repo}/releases/download/v1/app.apk','download_count':1}
                return [{'published_at':'2026-10-21T00:00:00Z','assets':[self.asset,{'name':'store-manifest.json','size':1000,'browser_download_url':f'https://github.com/minia1234/{self.repo}/releases/download/v1/store-manifest.json'}]}]
            def read(self,url,*args):
                if url.endswith('.webp'):
                    payload=b'VP8 '+url.encode()
                    return b'RIFF'+(len(payload)+4).to_bytes(4,'little')+b'WEBP'+payload
                return json.dumps(self.manifest).encode()
            def download(self,url,size,path):
                path.write_text(json.dumps(self.manifest))
                return self.manifest['sha256']
        s3=MemoryS3();storage=Storage(s3,'bucket')
        s3.objects['repositories.json']=json.dumps({'schemaVersion':1,'updatedAt':'2026-10-21T00:00:00Z','linked':[f'minia1234/app{i}' for i in range(100)]}).encode()
        with patch('sync_store.inspect_apk',side_effect=lambda path:json.loads(path.read_text())):
            catalog=synchronize(storage,HundredApps(),'minia1234','https://store.example.com')
        self.assertEqual(len(catalog['apps']),100)
        self.assertEqual(sum(key.startswith('media/') for key in s3.objects),900)
        self.assertLessEqual(storage.a,RUN_A)
        self.assertLessEqual(storage.b,RUN_B)
    def test_missing_registry_means_no_automatically_linked_apps(self):
        s3=MemoryS3();s3.objects.pop('repositories.json')
        with patch('sync_store.inspect_apk',return_value=self.actual()):
            catalog=synchronize(Storage(s3,'bucket'),FakeGitHub(self.manifest,self.asset),'minia1234','https://store.example.com')
        self.assertEqual(catalog['apps'],[])
    def test_unlinked_repository_is_removed_from_public_catalog(self):
        s3=MemoryS3();s3.objects['catalog.json']=json.dumps({'schemaVersion':1,'apps':[{'repository':'minia1234/app','packageName':'dev.test.app','versions':[self.actual()]}]}).encode()
        s3.objects['repositories.json']=b'{"schemaVersion":1,"linked":[],"updatedAt":"2026-10-21T00:00:00Z"}'
        catalog=synchronize(Storage(s3,'bucket'),FakeGitHub(self.manifest,self.asset),'minia1234','https://store.example.com')
        self.assertEqual(catalog['apps'],[])
    def test_malformed_or_unallowed_registry_preserves_catalog(self):
        for linked in (['outsider/app'],['minia1234/app','MINIA1234/APP']):
            with self.subTest(linked=linked):
                s3=MemoryS3();s3.objects['catalog.json']=b'{"schemaVersion":1,"apps":[]}'
                s3.objects['repositories.json']=json.dumps({'schemaVersion':1,'linked':linked}).encode()
                with self.assertRaises(ValueError):synchronize(Storage(s3,'bucket'),FakeGitHub(self.manifest,self.asset),'minia1234','https://store.example.com')
                self.assertEqual(s3.objects['catalog.json'],b'{"schemaVersion":1,"apps":[]}')
    def test_linked_repositories_from_both_accounts_sync_without_topics(self):
        template=self.manifest
        class BothOwners(FakeGitHub):
            def json(self,path,**kwargs):
                self.repository='/'.join(path.split('/')[2:4])
                owner=self.repository.split('/')[0]
                if '/releases?' not in path:
                    return {'full_name':self.repository,'owner':{'login':owner},'private':False,'topics':[]}
                self.manifest={**template,'packageName':'dev.test.'+('personal' if owner=='minia1234' else 'organization')}
                self.asset={'name':'app.apk','size':100,'digest':'sha256:'+'b'*64,'browser_download_url':f'https://github.com/{self.repository}/releases/download/v1/app.apk'}
                return [{'published_at':'2026-10-21T00:00:00Z','assets':[self.asset,{'name':'store-manifest.json','size':1000,'browser_download_url':f'https://github.com/{self.repository}/releases/download/v1/store-manifest.json'}]}]
            def download(self,url,size,path):
                path.write_text(json.dumps(self.manifest))
                return self.manifest['sha256']
        s3=MemoryS3()
        s3.objects['indi-store-bucket.json']=json.dumps({'dedicatedTo':'indi-store','owners':['minia1234','AIProject-k']}).encode()
        s3.objects['repositories.json']=json.dumps({'schemaVersion':1,'linked':['minia1234/app','AIProject-k/app']}).encode()
        with patch('sync_store.inspect_apk',side_effect=lambda path:json.loads(path.read_text())):
            catalog=synchronize(Storage(s3,'bucket'),BothOwners(self.manifest,self.asset),'minia1234,AIProject-k','https://store.example.com')
        self.assertEqual({app['repository'] for app in catalog['apps']},{'minia1234/app','AIProject-k/app'})

    def test_disabled_linked_app_keeps_verified_pin_and_files_without_github_refresh(self):
        s3=MemoryS3();key='apks/keep.apk';s3.objects[key]=b'keep'
        previous={'repository':'minia1234/app','packageName':'dev.test.app','versions':[{**self.actual(),'storageKey':key}]}
        s3.objects['catalog.json']=json.dumps({'schemaVersion':1,'apps':[previous]}).encode()
        s3.objects['repositories.json']=json.dumps({'schemaVersion':1,'linked':['minia1234/app'],'disabled':['minia1234/app']}).encode()
        github=FakeGitHub(self.manifest,self.asset)
        with patch.object(github,'json',side_effect=AssertionError('Disabled app must not refresh')):
            catalog=synchronize(Storage(s3,'bucket'),github,'minia1234','https://store.example.com')
        self.assertTrue(catalog['apps'][0]['disabled']);self.assertEqual(s3.objects[key],b'keep')
        self.assertEqual(catalog['apps'][0]['versions'][0]['signerSha256'],self.pin['signerSha256'])

    def test_mutable_raw_image_url_is_refetched_and_republished(self):
        self.manifest['iconUrl']='https://raw.githubusercontent.com/minia1234/app/main/icon.webp'
        s3=MemoryS3();github=FakeGitHub(self.manifest,self.asset)
        with patch('sync_store.inspect_apk',return_value=self.actual()):
            first=synchronize(Storage(s3,'bucket'),github,'minia1234','https://store.example.com')
        github.webp=b'RIFF'+(14).to_bytes(4,'little')+b'WEBPVP8 '+b'\x00'*6
        with patch('sync_store.inspect_apk',side_effect=AssertionError('APK should reuse known identity')):
            second=synchronize(Storage(s3,'bucket'),github,'minia1234','https://store.example.com')
        self.assertNotEqual(first['apps'][0]['iconUrl'],second['apps'][0]['iconUrl'])
if __name__=='__main__':unittest.main()

