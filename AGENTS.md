# MinAppStore project rules

- Use Ponytail full: smallest complete change, no speculative features.
- Work directly on `main`. Do not create branches or branch-based worktrees.
- User requires free operation without payment-method signup or paid upgrades. Do not enable R2. Production is Workers Free + SQLite Durable Object metadata + public GitHub Release APKs.
- Keep the source repository `minia1234/Min_AppStore` private unless explicitly instructed otherwise.
- Keep Android package `dev.indi.appstore`, signing continuity and persistent keys stable. Display name is MinAppStore.
- Preserve linked/disabled choices, signer pins and conditional publication. Never bypass APK integrity or signing checks.
- Never stage credentials, `.dev.vars`, `.wrangler`, `.env`, databases, signing keys, APKs, device captures, build output or the nested ApplicationTest checkout.
- Stage purpose-scoped files explicitly. Do not use bulk staging commands.
- Actual device proof, local fixtures, cloud deployment and GitHub Actions proof must be reported separately.
