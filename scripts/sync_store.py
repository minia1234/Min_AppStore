"""Bounded owner-only GitHub release synchronization. No credentials enter the catalog."""
import copy
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
import urllib.request
from urllib.parse import urlsplit
import uuid

from store_manifest import MAX_APK, https_url, inspect_apk, validate_manifest

UPLOAD_LIMIT = 8_000_000_000
WRITE_LIMIT = 9_000_000_000
RUN_A, RUN_B = 1_200, 2_000
MAX_A, MAX_B = 100_000, 100_000
MAX_REPOS, MAX_RELEASES = 100, 100
MAX_IMAGE = 2_000_000
MAX_REQUESTS, MAX_TRANSFER = 4_000, 8_000_000_000
OWNERS=('minia1234','AIProject-k')

def choose_latest(versions):
    return max(versions,key=lambda version:version['versionCode'])

def projected_upload(total, size):
    return total + size < UPLOAD_LIMIT

def reserve_budget(previous, month, class_a=RUN_A, class_b=RUN_B):
    same = previous.get('month') == month
    a = previous.get('classA',0) if same else 0
    b = previous.get('classB',0) if same else 0
    if a + class_a > MAX_A or b + class_b > MAX_B:
        raise ValueError('Monthly sync operation budget exhausted')
    return {'month':month,'classA':a+class_a,'classB':b+class_b}

def configured_owners(value):
    requested=value.split(',') if isinstance(value,str) else value
    canonical={owner.lower():owner for owner in OWNERS}
    if not requested or any(not isinstance(owner,str) or owner.strip().lower() not in canonical for owner in requested):
        raise ValueError('Only minia1234 and AIProject-k are trusted owners')
    result=[canonical[owner.strip().lower()] for owner in requested]
    if len(set(result))!=len(result):
        raise ValueError('Duplicate configured owner')
    return result

def selected_repositories(registry,owners):
    if registry is None:
        return []
    linked=registry.get('linked')
    if registry.get('schemaVersion')!=1 or not isinstance(linked,list) or len(linked)>MAX_REPOS:
        raise ValueError('Invalid repository link registry')
    for repository in linked:
        if not isinstance(repository,str) or not re.fullmatch(r'[A-Za-z0-9-]+/[A-Za-z0-9_.-]+',repository) or repository.split('/')[0].lower() not in {owner.lower() for owner in owners}:
            raise ValueError('Unallowed repository in link registry')
    if len({repository.lower() for repository in linked})!=len(linked):
        raise ValueError('Duplicate repository in link registry')
    return linked

def disabled_repositories(registry,owners):
    disabled=(registry or {}).get('disabled',[])
    if not isinstance(disabled,list) or len(disabled)>2000:
        raise ValueError('Invalid disabled repository list')
    allowed={owner.lower() for owner in owners}
    if any(not isinstance(name,str) or not re.fullmatch(r'[A-Za-z0-9-]+/[A-Za-z0-9_.-]+',name) or name.split('/')[0].lower() not in allowed for name in disabled) or len({name.lower() for name in disabled})!=len(disabled):
        raise ValueError('Invalid disabled repository')
    return {name.lower() for name in disabled}

def owner_webp(url,repository):
    parsed=urlsplit(url)
    prefix='/'+repository+'/'
    return (parsed.path.lower().endswith('.webp') and not parsed.query and not parsed.fragment
        and ((parsed.hostname=='raw.githubusercontent.com' and parsed.path.startswith(prefix))
        or (parsed.hostname=='github.com' and parsed.path.startswith(prefix+'releases/download/'))))

def mirror_images(app,previous,storage,github,repository,origin,inventory,total,trusted_media):
    sources={}
    resolved={}
    for url in [app['iconUrl'],*app['screenshots']]:
        if url in resolved:
            continue
        resolved[url]=url
        if not url or not owner_webp(url,repository):
            continue
        body=github.read(url,MAX_IMAGE)
        if not 20 <= len(body) <= MAX_IMAGE or body[:4]!=b'RIFF' or body[8:12]!=b'WEBP' or int.from_bytes(body[4:8],'little')!=len(body)-8:
            raise ValueError('Image is not a bounded RIFF WebP file')
        digest=hashlib.sha256(body).hexdigest()
        key=f'media/{digest}.webp'
        if key not in inventory and projected_upload(total,len(body)):
            storage.call('put_object',Key=key,Body=body,ContentLength=len(body),ContentType='image/webp',IfNoneMatch='*')
            inventory[key]=len(body)
            trusted_media.add(key)
            total+=len(body)
        if key in trusted_media and key in inventory and inventory[key]==len(body):
            resolved[url]=f'{origin}/media/{digest}.webp'
            sources.setdefault(digest,url)
    app['iconUrl']=resolved[app['iconUrl']]
    app['screenshots']=[resolved[url] for url in app['screenshots']]
    if sources:
        app['mediaSources']=sources
    return total

class GitHub:
    def __init__(self, token):
        self.token, self.calls, self.downloaded = token, 0, 0

    def read(self,url,limit,authenticated=False,allow_next=False):
        self.calls += 1
        if self.calls > MAX_REQUESTS:
            raise ValueError('GitHub request budget exhausted')
        if not url.startswith(('https://api.github.com/','https://github.com/','https://raw.githubusercontent.com/')):
            raise ValueError('Untrusted upstream URL')
        headers = {'User-Agent':'Indi-Store','Accept':'application/vnd.github+json','X-GitHub-Api-Version':'2022-11-28'}
        if authenticated:
            if not url.startswith('https://api.github.com/'):
                raise ValueError('Credentials restricted to GitHub API')
            headers['Authorization'] = 'Bearer '+self.token
        # Public asset URLs never carry the API token across the CDN redirect.
        with urllib.request.urlopen(urllib.request.Request(url,headers=headers),timeout=60) as response:
            body = response.read(limit+1)
            self.downloaded += len(body)
            if self.downloaded > MAX_TRANSFER:
                raise ValueError('Upstream transfer budget exhausted')
            if len(body) > limit:
                raise ValueError('GitHub response exceeds limit')
            if not allow_next and 'rel="next"' in response.headers.get('Link',''):
                raise ValueError('Discovery/history exceeds configured bound; no partial catalog publication')
            return body

    def json(self,path,allow_next=False):
        return json.loads(self.read('https://api.github.com'+path,2_000_000,True,allow_next))

    def download(self,url,size,path):
        self.calls += 1
        self.downloaded += size
        if self.calls > MAX_REQUESTS or self.downloaded > MAX_TRANSFER or not 0 < size <= MAX_APK:
            raise ValueError('APK transfer budget exhausted')
        if not url.startswith('https://github.com/'):
            raise ValueError('Untrusted download host')
        digest, received = hashlib.sha256(), 0
        with urllib.request.urlopen(urllib.request.Request(url,headers={'User-Agent':'Indi-Store'}),timeout=120) as response, open(path,'wb') as target:
            while chunk := response.read(1024*1024):
                received += len(chunk)
                if received > size:
                    raise ValueError('APK exceeds declared size')
                digest.update(chunk)
                target.write(chunk)
        if received != size:
            raise ValueError('Truncated APK')
        return digest.hexdigest()

class Storage:
    def __init__(self,client,bucket):
        self.client,self.bucket,self.a,self.b = client,bucket,0,0
        self.uncertain = False

    def call(self,method,**kwargs):
        kind = 'a' if method in ('put_object','list_objects_v2') else 'b'
        count = getattr(self,kind)+1
        if count > (RUN_A if kind == 'a' else RUN_B):
            raise ValueError('Reserved run operation budget exhausted')
        setattr(self,kind,count)  # Failed/partial attempts count as well.
        try:
            return getattr(self.client,method)(Bucket=self.bucket,**kwargs)
        except Exception as error:
            code = getattr(error,'response',{}).get('Error',{}).get('Code')
            if method == 'put_object' and code not in ('PreconditionFailed','412'):
                self.uncertain = True  # A timed out write can still commit after the response is lost.
            raise

    def get(self,key):
        try:
            response = self.call('get_object',Key=key)
        except Exception as error:
            if getattr(error,'response',{}).get('Error',{}).get('Code') in ('NoSuchKey','404'):
                return None,None
            raise
        body = response['Body'].read(2_000_001)
        if len(body)>2_000_000:
            raise ValueError('Stored metadata exceeds limit')
        return json.loads(body),response['ETag']

    def put_json(self,key,value,**conditions):
        body = json.dumps(value,ensure_ascii=False,separators=(',',':')).encode()
        if len(body)>2_000_000:
            raise ValueError('Catalog exceeds limit')
        return self.call('put_object',Key=key,Body=body,ContentType='application/json',**conditions)

    def inventory(self):
        objects, token = {}, None
        for _ in range(10):
            page = self.call('list_objects_v2',MaxKeys=1000,**({'ContinuationToken':token} if token else {}))
            for obj in page.get('Contents',[]):
                key = obj['Key']
                if not (key.startswith(('apks/','media/')) or key in ('catalog.json','repositories.json','sync-lock.json','sync-budget.json','indi-store-bucket.json')):
                    raise ValueError('Dedicated bucket contains an unknown object')
                objects[key] = obj['Size']
            if not page.get('IsTruncated'):
                return objects
            token = page['NextContinuationToken']
        raise ValueError('Bucket inventory exceeds bound')

def synchronize(storage,github,owners,origin):
    owners=configured_owners(owners)
    origin = origin.strip()
    https_url(origin)
    parsed_origin = urlsplit(origin)
    if parsed_origin.path not in ('','/') or parsed_origin.query or parsed_origin.fragment:
        raise ValueError('STORE_ORIGIN must be a bare HTTPS origin without query or fragment')
    origin = origin.rstrip('/')
    lock_id = str(uuid.uuid4())
    storage.put_json('sync-lock.json',{'id':lock_id,'startedAt':datetime.now(timezone.utc).isoformat()},IfNoneMatch='*')
    try:
        marker,_ = storage.get('indi-store-bucket.json')
        marker_owners=marker.get('owners',[]) if marker else []
        if marker and marker.get('owner'):
            marker_owners=[marker['owner']]
        if not marker or marker.get('dedicatedTo')!='indi-store' or not marker_owners or any(owner.lower() not in {allowed.lower() for allowed in owners} for owner in marker_owners):
            raise ValueError('Dedicated bucket/owners marker missing; initialize it manually')
        ledger,_ = storage.get('sync-budget.json')
        budget = reserve_budget(ledger or {},datetime.now(timezone.utc).strftime('%Y-%m'))
        storage.put_json('sync-budget.json',budget)
        inventory = storage.inventory()
        total = sum(inventory.values())
        if total >= WRITE_LIMIT:
            raise ValueError('9 GB write stop reached; inspect bucket manually')
        old,etag = storage.get('catalog.json')
        if old and (old.get('schemaVersion') != 1 or not isinstance(old.get('apps'),list)):
            raise ValueError('Existing catalog is invalid')
        registry,_=storage.get('repositories.json')
        selected=selected_repositories(registry,owners)
        disabled=disabled_repositories(registry,owners)
        selected_lower={repository.lower() for repository in selected}
        old_apps={app['repository']:copy.deepcopy(app) for app in (old or {}).get('apps',[])}
        apps={repository:app for repository,app in old_apps.items() if repository.lower() in selected_lower}
        for repository,app in apps.items():
            app['disabled']=repository.lower() in disabled
        trusted_media={f'media/{digest}.webp' for app in apps.values() for digest in app.get('mediaSources',{})
            if any(url.endswith(f'/media/{digest}.webp') for url in [app.get('iconUrl',''),*app.get('screenshots',[])])}
        for repository in old_apps:
            if repository.split('/')[0].lower() not in {owner.lower() for owner in owners}:
                raise ValueError('Existing catalog owner differs; refuse trust reset')
        for selected_repository in selected:
            if selected_repository.lower() in disabled:
                continue
            repo=github.json(f'/repos/{selected_repository}')
            repository = repo['full_name']
            if repo.get('private') or repo.get('visibility','public')!='public' or repo['owner']['login'].lower() not in {owner.lower() for owner in owners} or repository.lower()!=selected_repository.lower() or repository.split('/')[0].lower()!=repo['owner']['login'].lower():
                raise ValueError('Repository owner mismatch')
            releases = github.json(f'/repos/{repository}/releases?per_page={MAX_RELEASES}')
            previous = next((app for name,app in apps.items() if name.lower()==repository.lower()),None)
            pin = {'packageName':previous['packageName'],'signerSha256':choose_latest(previous['versions'])['signerSha256']} if previous else None
            manifests = []
            for release in releases:
                if release.get('draft') or release.get('prerelease'):
                    continue
                asset = next((a for a in release['assets'] if a['name']=='store-manifest.json'),None)
                if not asset:
                    continue
                if asset['size']>100_000:
                    raise ValueError('Manifest exceeds limit')
                url = asset['browser_download_url']
                if not url.startswith(f'https://github.com/{repository}/releases/download/'):
                    raise ValueError('Manifest does not belong to repository')
                m = validate_manifest(json.loads(github.read(url,100_000)),release['assets'],pin,repository)
                pin = pin or {'packageName':m['packageName'],'signerSha256':m['signerSha256']}
                m.update(publishedAt=release['published_at'],releaseNotes=(release.get('body') or '')[:10000],downloadCount=sum(a.get('download_count',0) for a in release['assets'] if a['name']==m['asset']))
                manifests.append(m)
            if not manifests:
                if previous:
                    raise ValueError('Existing app has no valid published manifests')
                continue
            latest = choose_latest(manifests)
            if len({m['versionCode'] for m in manifests}) != len(manifests):
                raise ValueError('Duplicate versionCode in releases')
            for app in apps.values():
                if app['packageName']==latest['packageName'] and app['repository']!=repository:
                    raise ValueError('Package name already belongs to another repository')
            if previous:
                previous_by_code = {v['versionCode']:v for v in previous['versions']}
                if latest['versionCode'] < choose_latest(previous['versions'])['versionCode']:
                    raise ValueError('Latest versionCode regressed')
                for m in manifests:
                    if m['versionCode'] in previous_by_code and m['sha256'] != previous_by_code[m['versionCode']]['sha256']:
                        raise ValueError('Published versionCode APK was replaced')
            key = f"apks/{latest['packageName']}/{latest['versionCode']}/{latest['sha256']}.apk"
            trusted_key = previous and any(v.get('storageKey')==key for v in previous['versions'])
            available = key in inventory and bool(trusted_key)
            if available and inventory[key] != latest['size']:
                raise ValueError('Existing R2 APK size mismatch')
            fields=('versionCode','versionName','minSdk','signerSha256','sha256','size')
            known_version=previous and any(all(v.get(field)==latest[field] for field in fields) for v in previous['versions'])
            needs_upload=key not in inventory and projected_upload(total,latest['size'])
            if (key not in inventory or not trusted_key) and (not known_version or needs_upload):
                # Registration verifies the signed APK even when R2 is full and GitHub serves it.
                with tempfile.TemporaryDirectory(prefix='indi-store-') as temp:
                    apk = Path(temp)/'app.apk'
                    if github.download(latest['githubUrl'],latest['size'],apk) != latest['sha256']:
                        raise ValueError('Downloaded APK hash mismatch')
                    if not known_version:
                        actual = inspect_apk(apk)
                        for field in ('packageName',*fields):
                            if actual[field] != latest[field]:
                                raise ValueError(f'Actual APK differs from manifest: {field}')
                    if needs_upload:
                        with apk.open('rb') as body:
                            storage.call('put_object',Key=key,Body=body,ContentLength=latest['size'],ContentType='application/vnd.android.package-archive',IfNoneMatch='*')
                        total += latest['size']
                        inventory[key] = latest['size']
                        available = True
            versions=[]
            for m in sorted(manifests,key=lambda item:item['versionCode'],reverse=True):
                v = {field:m[field] for field in ('versionCode','versionName','minSdk','sha256','size','githubUrl','publishedAt','releaseNotes','signerSha256')}
                v['downloadUrl']=f"{origin}/downloads/{m['packageName']}/{m['versionCode']}.apk"
                if m is latest and available:
                    v['storageKey']=key
                versions.append(v)
            app={field:latest[field] for field in ('packageName','name','summary','description','category','author','tags','iconUrl','screenshots')}
            app.update(repository=repository,versions=versions,downloadCount=sum(m['downloadCount'] for m in manifests))
            app['disabled']=False
            total=mirror_images(app,previous,storage,github,repository,origin,inventory,total,trusted_media)
            apps[repository]=app
        catalog={'schemaVersion':1,'updatedAt':datetime.now(timezone.utc).isoformat(),'apps':sorted(apps.values(),key=lambda app:app['packageName'])}
        if total + len(json.dumps(catalog,ensure_ascii=False).encode()) + 100_000 >= WRITE_LIMIT:
            raise ValueError('Insufficient metadata storage headroom')
        storage.put_json('catalog.json',catalog,**({'IfMatch':etag} if etag else {'IfNoneMatch':'*'}))
        live={v['storageKey'] for app in catalog['apps'] for v in app['versions'] if v.get('storageKey')}
        live.update(f'media/{digest}.webp' for app in catalog['apps'] for digest in app.get('mediaSources',{}))
        # Publication succeeded: failed/old immutable uploads can now be reclaimed safely.
        for key in inventory:
            if key.startswith(('apks/','media/')) and key not in live:
                storage.call('delete_object',Key=key)
        return catalog
    finally:
        # A crashed runner leaves the lock behind deliberately; never expire a potentially live writer.
        if not storage.uncertain:
            storage.call('delete_object',Key='sync-lock.json')

def main():
    import boto3
    from botocore.config import Config
    account=os.environ['R2_ACCOUNT_ID']
    client=boto3.client('s3',endpoint_url=f'https://{account}.r2.cloudflarestorage.com',aws_access_key_id=os.environ['R2_ACCESS_KEY_ID'],aws_secret_access_key=os.environ['R2_SECRET_ACCESS_KEY'],region_name='auto',config=Config(retries={'total_max_attempts':1},connect_timeout=20,read_timeout=120))
    result=synchronize(Storage(client,os.environ['R2_BUCKET']),GitHub(os.environ['GITHUB_TOKEN']),os.environ.get('STORE_OWNERS') or ','.join(OWNERS),os.environ['STORE_ORIGIN'])
    print(f"Published {len(result['apps'])} apps")

if __name__=='__main__':
    main()
