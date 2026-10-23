"""Generate and publish a GitHub-only catalog; never enables a paid storage service."""
import base64
import copy
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
from urllib.parse import urlsplit
import zipfile

from store_manifest import MAX_APK, https_url, inspect_apk, sdk_tool, validate_manifest
from sync_store import GitHub, OWNERS, choose_latest, selected_repositories, disabled_repositories

# shortcut: icons ride inside the 2 MB catalog, so ~100 apps x 12 KB is the ceiling; move them to their own storage keys if the catalog nears 2 MB.
MAX_ICON=12_000


def apk_label_icon(path):
    """The APK's label and its largest raster launcher icon under MAX_ICON; adaptive XML icons cannot be rendered here."""
    badging=subprocess.check_output([sdk_tool('aapt'),'dump','badging',str(path)],text=True,encoding='utf-8',errors='replace',timeout=120)
    label=re.search(r"^application-label:'(.*)'$",badging,re.M)
    icons=sorted(((int(density),name) for density,name in re.findall(r"^application-icon-(\d+):'(.+)'$",badging,re.M)),reverse=True)
    with zipfile.ZipFile(path) as apk:
        for _,name in icons:
            try: info=apk.getinfo(name)
            except KeyError: continue
            if info.file_size>MAX_ICON: continue
            data=apk.read(info)
            if data.startswith(b'\x89PNG\r\n\x1a\n') or (data[:4]==b'RIFF' and data[8:12]==b'WEBP'):
                return (label[1].strip() if label else ''),data
    return (label[1].strip() if label else ''),None


def apk_release(asset,previous,pin,github,name):
    """Version facts for a release that ships one APK and no store manifest."""
    url=asset['browser_download_url']
    if not 0 < asset['size'] <= MAX_APK or not url.startswith(f'https://github.com/{name}/releases/download/') or urlsplit(url).query or urlsplit(url).fragment:
        raise ValueError('Invalid release APK asset')
    digest=(asset.get('digest') or '').removeprefix('sha256:')
    # shortcut: assets without a GitHub digest are matched by URL and size only; Android still rejects a swapped file by its published SHA-256.
    known=next((v for v in (previous or {}).get('versions',[]) if v['githubUrl']==url and v['size']==asset['size'] and digest in ('',v['sha256'])),None)
    if known:
        facts={field:known[field] for field in ('versionCode','versionName','minSdk','signerSha256','sha256','size')}
        facts['packageName']=previous['packageName']
    else:
        with tempfile.TemporaryDirectory(prefix='minappstore-') as directory:
            apk=Path(directory)/'app.apk'
            sha256=github.download(url,asset['size'],apk)
            if digest and sha256!=digest: raise ValueError('Downloaded APK hash mismatch')
            facts=inspect_apk(apk)
            facts['label'],facts['icon']=apk_label_icon(apk)
    # The server rejects the whole catalog on these, so fail this app alone here.
    if not facts['versionName'].strip() or len(facts['versionName'])>80 or not 0 < facts['versionCode'] <= 2_100_000_000 or not 0 < facts['minSdk'] <= 1000:
        raise ValueError('APK version fields are outside store limits')
    if pin and (facts['packageName']!=pin['packageName'] or facts['signerSha256']!=pin['signerSha256']):
        raise ValueError('Pinned package/signer changed')
    return dict(facts,githubUrl=url,asset=asset['name'],apkOnly=True)


def store_origin(value):
    value=value.strip().rstrip('/')
    https_url(value)
    parsed=urlsplit(value)
    if parsed.path or parsed.query or parsed.fragment:
        raise ValueError('STORE_ORIGIN must be a bare HTTPS origin')
    return value


def collect_inventory(github):
    repositories=[]
    seen=set()
    for owner in OWNERS:
        for page in range(1,12):
            batch=github.json(f'/users/{owner}/repos?type=owner&sort=full_name&per_page=100&page={page}',allow_next=True)
            if not isinstance(batch,list) or (page==11 and batch): raise ValueError('Repository inventory exceeds 1,000 per account')
            for repo in batch:
                name=repo['full_name']
                if repo.get('private') or repo['owner']['login'].lower()!=owner.lower() or name.lower() in seen: continue
                seen.add(name.lower())
                repositories.append({'full_name':name,'name':repo['name'],'owner':{'login':repo['owner']['login']},'description':(repo.get('description') or '')[:1000],
                    'html_url':'https://github.com/'+name,'private':False,'archived':bool(repo.get('archived')),'default_branch':repo.get('default_branch') or ''})
            if len(batch)<100: break
    return repositories


def build_catalog(snapshot, github, origin, inventory=()):
    origin=store_origin(origin)
    registry=snapshot['registry']
    selected=selected_repositories(registry,OWNERS)
    disabled=disabled_repositories(registry,OWNERS)
    linked={name.lower() for name in selected}
    # Public repositories join on their own once a release carries an APK; disabling one keeps it out.
    selected=selected+[repo['full_name'] for repo in inventory if repo['full_name'].lower() not in linked|disabled]
    old={app['repository'].lower():copy.deepcopy(app) for app in snapshot['catalog']['apps']}
    apps={name:app for name,app in old.items() if name in {item.lower() for item in selected}}
    for name,app in apps.items():
        app['disabled']=name in disabled
        app.pop('mediaSources',None)
        for version in app['versions']: version.pop('storageKey',None)
    failures=[]
    for selected_name in selected:
        if selected_name.lower() in disabled: continue
        # A broken repository keeps its last verified entry instead of blocking every other app.
        try:
            repository=github.json('/repos/'+selected_name)
            name=repository['full_name']
            if repository.get('private') or name.lower()!=selected_name.lower() or repository['owner']['login'].lower() not in {owner.lower() for owner in OWNERS}:
                raise ValueError('Only the selected owners public repositories are allowed')
            previous=old.get(name.lower())
            pin={'packageName':previous['packageName'],'signerSha256':choose_latest(previous['versions'])['signerSha256']} if previous else None
            manifests=[]
            for release in github.json('/repos/'+name+'/releases?per_page=100'):
                if release.get('draft') or release.get('prerelease'): continue
                asset=next((asset for asset in release['assets'] if asset['name']=='store-manifest.json'),None)
                if asset is None:
                    apks=[asset for asset in release['assets'] if re.fullmatch(r'[A-Za-z0-9_.-]+\.apk',asset['name'])]
                    # Without a manifest only an unambiguous single APK can be published.
                    if len(apks)!=1: continue
                    manifest=apk_release(apks[0],previous,pin,github,name)
                else:
                    if not 0 < asset['size'] <= 100_000 or not asset['browser_download_url'].startswith(f'https://github.com/{name}/releases/download/'):
                        raise ValueError('Invalid release manifest asset')
                    manifest=validate_manifest(json.loads(github.read(asset['browser_download_url'],100_000)),release['assets'],pin,name)
                pin=pin or {'packageName':manifest['packageName'],'signerSha256':manifest['signerSha256']}
                manifest.update(publishedAt=release['published_at'],releaseNotes=(release.get('body') or '')[:10_000],downloadCount=sum(a.get('download_count',0) for a in release['assets'] if a['name']==manifest['asset']))
                manifests.append(manifest)
            if not manifests:
                if previous: raise ValueError('Previously published app has no valid release manifests')
                continue
            latest=choose_latest(manifests)
            if len({m['versionCode'] for m in manifests})!=len(manifests): raise ValueError('Duplicate versionCode')
            if any(key!=name.lower() and other['packageName']==latest['packageName'] for key,other in apps.items()): raise ValueError('Package belongs to multiple repositories')
            previous_versions={v['versionCode']:v for v in previous['versions']} if previous else {}
            if previous and latest['versionCode']<max(previous_versions): raise ValueError('Version regression')
            for manifest in manifests:
                if manifest['versionCode'] in previous_versions and manifest['sha256']!=previous_versions[manifest['versionCode']]['sha256']:
                    raise ValueError('A published versionCode was replaced')
            fields=('packageName','versionCode','versionName','minSdk','signerSha256','sha256','size')
            known=previous and previous['packageName']==latest['packageName'] and any(all(v.get(field)==latest[field] for field in fields if field!='packageName') for v in previous['versions'])
            if not known and not latest.get('apkOnly'):
                with tempfile.TemporaryDirectory(prefix='minappstore-') as directory:
                    apk=Path(directory)/'app.apk'
                    if github.download(latest['githubUrl'],latest['size'],apk)!=latest['sha256']: raise ValueError('Downloaded APK hash mismatch')
                    actual=inspect_apk(apk)
                    if any(actual[field]!=latest[field] for field in fields): raise ValueError('Actual APK identity differs from manifest')
            if latest.get('apkOnly'):
                # No manifest: the APK's own label and icon, with the repository description as text.
                about=(repository.get('description') or '').strip()
                label=(latest.get('label') or (previous or {}).get('name') or name.split('/')[1])[:80].strip()
                app={'packageName':latest['packageName'],'name':label,'summary':(about or label)[:200],'description':(about or label)[:10_000],
                    'category':'기타','author':repository['owner']['login'],'tags':[],'iconUrl':'','screenshots':[]}
                if latest.get('icon'):
                    kind='png' if latest['icon'].startswith(b'\x89PNG') else 'webp'
                    app.update(iconUrl=f"{origin}/icons/{latest['packageName']}/{latest['versionCode']}.{kind}",iconData=base64.b64encode(latest['icon']).decode())
                elif 'label' not in latest and previous:
                    # The latest APK was verified in an earlier run, so its icon is already in the catalog.
                    app.update({key:previous[key] for key in ('iconUrl','iconData') if key in previous})
            else:
                app={field:latest[field] for field in ('packageName','name','summary','description','category','author','tags','iconUrl','screenshots')}
            versions=[]
            for manifest in sorted(manifests,key=lambda item:item['versionCode'],reverse=True):
                version={field:manifest[field] for field in ('versionCode','versionName','minSdk','sha256','size','githubUrl','publishedAt','releaseNotes','signerSha256')}
                version['downloadUrl']=f"{origin}/downloads/{app['packageName']}/{version['versionCode']}.apk"
                versions.append(version)
            app.update(repository=name,versions=versions,downloadCount=sum(m['downloadCount'] for m in manifests),disabled=False)
            apps[name.lower()]=app
        except Exception as error:
            failures.append(f'{selected_name}: {type(error).__name__}: {error}')
    if len({app['packageName'] for app in apps.values()})!=len(apps): raise ValueError('Package belongs to multiple repositories')
    return {'schemaVersion':1,'updatedAt':datetime.now(timezone.utc).isoformat(),'apps':sorted(apps.values(),key=lambda app:app['packageName'])},failures


def api(origin,token,method='GET',payload=None):
    body=None if payload is None else json.dumps(payload,ensure_ascii=False).encode()
    if body and len(body)>2_000_000: raise ValueError('Catalog exceeds 2 MB')
    request=urllib.request.Request(origin+'/api/admin/sync',data=body,method=method,headers={'Authorization':'Bearer '+token,'Content-Type':'application/json','User-Agent':'MinAppStore-Sync/1.0'})
    try:
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self,*args,**kwargs): return None
        with urllib.request.build_opener(NoRedirect).open(request,timeout=60) as response:
            data=response.read(2_000_001)
            if len(data)>2_000_000: raise ValueError('Snapshot exceeds 2 MB')
            return json.loads(data)
    except urllib.error.HTTPError as error:
        raise RuntimeError('Store sync rejected with HTTP '+str(error.code)+'; no credentials were logged') from None


def main():
    origin=store_origin(os.environ['STORE_ORIGIN'])
    token=os.environ['MINAPPSTORE_ADMIN_TOKEN']
    snapshot=api(origin,token)
    github=GitHub(os.environ['GITHUB_TOKEN'])
    inventory=collect_inventory(github)
    catalog,failures=build_catalog(snapshot,github,origin,inventory)
    linked={name.lower() for name in snapshot['registry']['linked']}
    auto_link=[app['repository'] for app in catalog['apps'] if app['repository'].lower() not in linked]
    result=api(origin,token,'PUT',{'catalog':catalog,'autoLink':auto_link,'repositoryInventory':inventory,'expectedCatalogEtag':snapshot['catalogEtag'],'expectedRegistryEtag':snapshot['registryEtag']})
    if result.get('published') is not True: raise RuntimeError('Store did not confirm publication')
    print(f"Published {len(catalog['apps'])} verified apps ({len(auto_link)} newly linked); APK storage: public GitHub Releases")
    if failures:
        # Fail the run so the owner sees which repositories kept their previous entry.
        print('Kept the last verified entry for:',*failures,sep='\n',file=sys.stderr)
        raise SystemExit(1)


if __name__=='__main__': main()
