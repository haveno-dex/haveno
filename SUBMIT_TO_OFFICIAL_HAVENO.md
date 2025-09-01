# How to Submit Your BTC to XMR Transition PR to Official Haveno Repository

## Current Situation
- Your fork is at: `https://github.com/retoaccess1/haveno-reto`
- Official repository is at: `https://github.com/haveno-dex/haveno`
- Your branch `btc-to-xmr-transition` has significant changes that need to be submitted upstream

## Option 1: Fork and Create PR (Recommended)

### Step 1: Fork the Official Repository
1. Go to: https://github.com/haveno-dex/haveno
2. Click "Fork" button (top-right)
3. Create fork under your GitHub account

### Step 2: Update Your Local Repository
After forking, update your local repository to point to your new fork:

```bash
# Replace YOUR_USERNAME with your actual GitHub username
git remote set-url origin https://github.com/YOUR_USERNAME/haveno.git

# Verify remotes are set correctly
git remote -v
# Should show:
# origin    https://github.com/YOUR_USERNAME/haveno.git (fetch)
# origin    https://github.com/YOUR_USERNAME/haveno.git (push)
# upstream  https://github.com/haveno-dex/haveno.git (fetch)
# upstream  https://github.com/haveno-dex/haveno.git (push)
```

### Step 3: Rebase Your Changes (Important!)
Your branch is behind the official master by many commits. You need to rebase:

```bash
# Switch to master and update it
git checkout master
git pull upstream master
git push origin master

# Rebase your feature branch
git checkout btc-to-xmr-transition
git rebase upstream/master

# Resolve any conflicts that arise during rebase
# After resolving conflicts, continue with:
# git rebase --continue

# Force push to your fork (since rebase rewrites history)
git push -f origin btc-to-xmr-transition
```

### Step 4: Create the Pull Request
1. Go to your fork: `https://github.com/YOUR_USERNAME/haveno`
2. Click "Compare & pull request" button
3. Set base repository: `haveno-dex/haveno` base: `master`
4. Set head repository: `YOUR_USERNAME/haveno` compare: `btc-to-xmr-transition`
5. Use the title: **"Complete BTC to XMR transition in CLI and apitest modules"**
6. Copy the content from `PR_DESCRIPTION.md` as the PR description

## Option 2: Submit as Patch File

If you prefer not to create a GitHub account, you can submit the patch file:

### Files Created:
- `haveno-official-btc-to-xmr-transition.patch` (4.2MB) - Complete patch against official upstream
- `PR_DESCRIPTION.md` - Detailed PR description

### How to Submit:
1. **Create GitHub Issue**: Go to https://github.com/haveno-dex/haveno/issues
2. **Title**: "BTC to XMR Transition - CLI and API Test Modules"
3. **Description**: Copy content from `PR_DESCRIPTION.md`
4. **Attach**: Upload the patch file `haveno-official-btc-to-xmr-transition.patch`

## Option 3: Contact Haveno Team Directly

You can reach out to the Haveno team through:
- **Matrix**: #haveno-development:monero.social
- **Email**: contact@haveno.exchange
- **GitHub Discussions**: https://github.com/haveno-dex/haveno-meta

## What Your PR Includes

### Major Changes:
- **113 files changed** with 4,596 additions and 1,417 deletions
- Complete transition from BTC to XMR terminology
- All 81 CLI methods properly implemented
- Updated test suites and documentation
- Removed deprecated transaction fee rate methods

### Key Files:
- `cli/src/main/java/haveno/cli/CliMain.java` - Complete CLI implementation
- `cli/src/main/java/haveno/cli/GrpcClient.java` - Updated gRPC client
- Multiple new XMR-specific service classes and option parsers
- Updated table builders and column formatters
- Comprehensive test updates

## Important Notes

⚠️ **Rebase Required**: Your changes are based on an older version of the official repository. You'll need to rebase against the latest upstream/master to avoid conflicts.

✅ **Quality Assured**: All changes have been tested and compile successfully.

📝 **Well Documented**: Comprehensive help documentation for all CLI methods included.

🔄 **Breaking Changes**: Some CLI methods removed (tx fee rate methods), API method names changed from BTC to XMR.

## Next Steps

1. **Choose your preferred option** (Fork + PR recommended)
2. **Follow the steps** for your chosen option
3. **Be prepared to address feedback** from the Haveno maintainers
4. **Consider breaking down** the large PR into smaller, focused PRs if requested

## Support

If you need help with any of these steps, the Haveno community is active on Matrix and ready to help contributors.
