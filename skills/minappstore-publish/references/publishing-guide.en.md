# Connect another Android app to MinAppStore

[한국어 가이드](publishing-guide.md)

Upload a **signed APK and `store-manifest.json` to a public GitHub Release**, then link that repository in the admin page. You do not need to copy app source into the store repository or deploy a separate server for each app.

If the source is private, keep it private and choose a public distribution repository for release files. This guide does not create repositories or change visibility. Public APKs can be downloaded by anyone; do not embed secrets or internal configuration in them.

| Setting | Current value |
| --- | --- |
| Admin | https://minappstore-api.eum-user.workers.dev/admin |
| Public catalog | https://minappstore-api.eum-user.workers.dev/api/catalog |
| Allowed repository owners | minia1234, AIProject-k |
| Central runner | https://github.com/AIProject-k/ApplicationTest/actions/workflows/store-sync.yml |
| Automatic sync | Daily at 03:00 KST (18:00 UTC on the previous day); execution may be delayed |
| Immediate sync | Run workflow → main in the central runner |

Repositories owned by other accounts are not currently supported. Individual app repositories do not need Cloudflare credentials or the admin token.

## 1. Check the project and signing identity

In the app project's chat, use:

```text
$minappstore-publish Inspect this Android project, publish its signed APK
and store-manifest.json to AIProject-k/MyApp, and connect it to MinAppStore.
Preserve the package name and signing key.
```

Replace the repository with the actual target. The skill responds in English or Korean according to your request. If it is not listed yet, open a new chat or restart Codex. You can also attach `../SKILL.md` and ask the agent to follow it.

To use it on another PC, copy the **entire** `skills/minappstore-publish` directory into that PC's `.codex/skills/minappstore-publish` directory (or `CODEX_HOME/skills/minappstore-publish` when configured). Include references and assets. The skill contains no admin token or signing key. Configure GitHub login, Android SDK and app signing separately on that PC.

For manual preparation:

- Install Python 3.11+, Android SDK Build-tools with `aapt` and `apksigner`, and GitHub CLI with write access to the target distribution repository.
- Build a **signed, single APK** using the project's existing build process. An AAB alone is not supported.
- For an Android Gradle project, `.\gradlew.bat :app:assembleRelease` may be appropriate from its actual Gradle root. Without signing configuration, that command does not guarantee a signed APK.
- Use the existing Android APK export process for Flutter, Unity or Godot projects.
- Updates require the same `packageName`, the same signing certificate and a higher `versionCode`. Changing only `versionName` is insufficient.
- The maximum APK size is 512 MiB. Use an `.apk` filename containing ASCII letters, digits, underscores, hyphens and dots.

## 2. Write display metadata

Copy the skill's `assets/store-metadata.en.json` to the app project's `store-metadata.json`. Replace the sample name, summary, description, category, author and tags with your app's information. The default skill installation is `C:\Users\user\.codex\skills\minappstore-publish` on this PC.

```json
{
  "name": "My App",
  "summary": "What the app does",
  "description": "Describe the features and how to use them.",
  "category": "Tools",
  "author": "AIProject-k",
  "tags": ["tools"],
  "iconUrl": "",
  "screenshots": []
}
```

Icons and screenshots are optional. When included, they must be public HTTPS URLs ending in `.webp`. Limits: name 80 characters, summary 200, description 10,000, category 40, author 80; up to 12 tags of 40 characters each and 8 screenshots. APK identity and integrity fields are generated in the next step.

The store UI supports English and Korean, but publisher-provided names, descriptions, categories and release notes remain in their original language. This metadata format does not automatically generate translations.

## 3. Generate the manifest from the actual APK

The following PowerShell example runs from the **app project's root**. Adjust the APK source path and output filename for your project.

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$skillRoot = Join-Path $env:USERPROFILE '.codex/skills/minappstore-publish'
if ($env:CODEX_HOME) { $skillRoot = Join-Path $env:CODEX_HOME 'skills/minappstore-publish' }
New-Item -ItemType Directory -Path release -Force | Out-Null
$apk = 'release/MyApp-1.0.0.apk'
Copy-Item -LiteralPath 'app/build/outputs/apk/release/app-release.apk' -Destination $apk
python "$skillRoot/assets/store_manifest.py" --apk $apk --metadata store-metadata.json --output release/store-manifest.json
if ($LASTEXITCODE -ne 0) { throw 'APK/manifest validation failed. Stop publishing.' }
```

The inspector reads package, version, minimum SDK, signing certificate, SHA-256 and file size from the actual APK. Regenerate after modifying or renaming the APK. The uploaded filename must match `asset` in the manifest. Never commit keystores, passwords, `.env` files or generated APKs.

## 4. Publish both release assets

In GitHub, open **Releases → Draft a new release**, check the target commit and new version tag, then attach:

```text
MyApp-1.0.0.apk
store-manifest.json
```

The manifest filename must be exactly `store-manifest.json`. Drafts and prereleases are ignored. After reviewing the files, signing identity and repository, use **Publish release** for a normal public release. Do not overwrite an existing version's APK; increment versionCode and use a new tag.

The CLI example below **publishes publicly**. Set the repository, tag, APK and release notes to real values. Confirm that the remote tag already points to the intended distribution-repository commit. With private source, use a tag from the designated public distribution repository, not the private source repository.

```powershell
$repo = 'AIProject-k/MyAndroidApp' # Change to the actual public distribution repository
$tag = 'v1.0.0' # A tag already prepared in that repository
gh release create $tag $apk release/store-manifest.json --repo $repo --verify-tag --title $tag --notes-file release/release-notes.md
if ($LASTEXITCODE -ne 0) { throw 'Release publication failed. Investigate before retrying.' }
gh release view $tag --repo $repo --json url,isDraft,isPrerelease,assets
```

MinAppStore checks GitHub asset size and `sha256:` digest and inspects the downloaded APK. Do not hand-edit binary identity fields to bypass validation.

## 5. Link and synchronize

1. Open the admin URL and sign in with the existing admin token. Do not put it in app repositories or public files.
2. Search for the public distribution repository and select **Link**.
3. If disabled, check **Show disabled items** and select **Enable**.
4. Open **Run sync**, then use GitHub's **Run workflow → main → Run workflow**. If you lack Actions access, ask the administrator or wait for the daily run.

Link a new public repository in admin, then run central sync. With an APK and no manifest, the name and icon come from the APK, the description from the repository description, and screenshots and tags stay empty.

**Publishing a release in a different app repository does not trigger the central runner immediately.** Current triggers are releases in `ApplicationTest` itself, the daily schedule and manual dispatch. You do not need to copy the admin token or central workflow into every app repository.

## 6. Confirm the result and publish updates

- Check that the central Actions run concludes with `success`.
- Check the catalog's `repository`, `packageName` and highest `versionCode`.
- Refresh the Android store and check its Install or Update action.
- Android installation still requires user approval. Sync success and actual device update success are separate results.

For future versions: keep the signing key → increase versionCode → build → regenerate manifest → publish a new release → synchronize. Link the repository only once. Central sync verifies and registers APKs; it does not build app source.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Repository missing from admin | Allowed owner, public visibility, updated inventory cache |
| Linked app is missing | Enabled state, normal public release, APK and exact manifest filename, completed sync |
| New release not reflected immediately | Use the central runner's Run workflow; another repository's release is not an immediate trigger |
| Signing/hash validation failure | Same signing key, unchanged APK after manifest generation, no replaced historical assets |
| No Update action | Same package and an actual versionCode higher than the installed version |
| HTTP 409 | Wait for the active operation and retry from fresh state; never force-delete the lock |
| One app blocks a multi-app sync | Fix that app's metadata and rerun; do not clear the existing catalog |

This guide describes the existing free setup. Reading or creating it does not authorize publishing releases, changing repository visibility, issuing tokens or expanding allowed owners.
