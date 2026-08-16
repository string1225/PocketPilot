[CmdletBinding()]
param(
    [switch]$SkipVerification
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Invoke-CheckedCommand {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Executable,

        [Parameter(ValueFromRemainingArguments = $true)]
        [string[]]$CommandArguments
    )

    & $Executable @CommandArguments
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed with exit code ${LASTEXITCODE}: $Executable $($CommandArguments -join ' ')"
    }
}

$repoRoot = (git rev-parse --show-toplevel).Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($repoRoot)) {
    throw "Run this script from inside the PocketPilot Git repository."
}

Push-Location $repoRoot
try {
    $branch = (git branch --show-current).Trim()
    if ($LASTEXITCODE -ne 0 -or $branch -ne "dev") {
        throw "Releases must be created from the dev branch; current branch is '$branch'."
    }

    $dirtyFiles = @(git status --porcelain --untracked-files=all)
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to inspect the Git working tree."
    }
    if ($dirtyFiles.Count -gt 0) {
        throw "Commit all intended changes before releasing. The working tree is not clean."
    }

    Invoke-CheckedCommand git fetch origin dev --tags

    $localCommit = (git rev-parse HEAD).Trim()
    $remoteCommit = (git rev-parse origin/dev).Trim()
    if ($localCommit -ne $remoteCommit) {
        throw "HEAD must exactly match origin/dev. Commit and push dev before creating the release tag."
    }

    $gradleFile = Join-Path $repoRoot "apps/android/app/build.gradle.kts"
    $gradleText = Get-Content -Raw -Encoding utf8 $gradleFile
    $versionNameMatch = [regex]::Match(
        $gradleText,
        '(?m)^\s*versionName\s*=\s*"(?<version>[^"]+)"'
    )
    $versionCodeMatch = [regex]::Match(
        $gradleText,
        '(?m)^\s*versionCode\s*=\s*(?<code>\d+)'
    )

    if (-not $versionNameMatch.Success -or -not $versionCodeMatch.Success) {
        throw "Could not read versionName/versionCode from $gradleFile."
    }

    $versionName = $versionNameMatch.Groups["version"].Value
    $versionCode = [int64]$versionCodeMatch.Groups["code"].Value
    $semanticVersionPattern = '^(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})$'
    if ($versionName -notmatch $semanticVersionPattern) {
        throw "versionName '$versionName' must be a stable semantic version such as 0.2.0."
    }
    if ($versionCode -lt 1) {
        throw "versionCode must be a positive integer."
    }

    $tagName = "v$versionName"
    $currentParts = @($versionName.Split('.') | ForEach-Object { [int64]$_ })
    $highestReleasedVersion = $null
    foreach ($existingTag in @(git tag --list 'v*')) {
        $candidate = $existingTag.TrimStart('v')
        if ($candidate -notmatch $semanticVersionPattern) {
            continue
        }
        $candidateParts = @($candidate.Split('.') | ForEach-Object { [int64]$_ })
        if ($null -eq $highestReleasedVersion) {
            $highestReleasedVersion = $candidateParts
            continue
        }
        for ($index = 0; $index -lt 3; $index++) {
            if ($candidateParts[$index] -gt $highestReleasedVersion[$index]) {
                $highestReleasedVersion = $candidateParts
                break
            }
            if ($candidateParts[$index] -lt $highestReleasedVersion[$index]) {
                break
            }
        }
    }
    if ($null -ne $highestReleasedVersion) {
        $isGreater = $false
        for ($index = 0; $index -lt 3; $index++) {
            if ($currentParts[$index] -gt $highestReleasedVersion[$index]) {
                $isGreater = $true
                break
            }
            if ($currentParts[$index] -lt $highestReleasedVersion[$index]) {
                break
            }
        }
        if (-not $isGreater) {
            $highestText = $highestReleasedVersion -join '.'
            throw "versionName $versionName must be greater than the highest existing release tag v$highestText."
        }
    }

    & git show-ref --verify --quiet "refs/tags/$tagName"
    if ($LASTEXITCODE -eq 0) {
        throw "Tag $tagName already exists locally. Bump versionCode and versionName for every release."
    }

    $remoteTag = @(git ls-remote --tags origin "refs/tags/$tagName")
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to check whether $tagName exists on origin."
    }
    if ($remoteTag.Count -gt 0) {
        throw "Tag $tagName already exists on origin. Bump versionCode and versionName for every release."
    }

    if (-not $SkipVerification) {
        Invoke-CheckedCommand pnpm install --frozen-lockfile
        Invoke-CheckedCommand pnpm check

        $androidDirectory = Join-Path $repoRoot "apps/android"
        Push-Location $androidDirectory
        try {
            Invoke-CheckedCommand .\gradlew.bat `
                :sshj-android:test `
                :app:testDebugUnitTest `
                :app:lintDebug `
                :app:assembleDebug `
                :app:assembleDebugAndroidTest `
                --stacktrace
        } finally {
            Pop-Location
        }

        $generatedAssetChanges = @(
            git status --porcelain --untracked-files=all -- `
                apps/android/app/src/main/assets/pocketpilot-runtime.js
        )
        if ($LASTEXITCODE -ne 0) {
            throw "Unable to verify the generated Android runtime asset."
        }
        if ($generatedAssetChanges.Count -gt 0) {
            throw "pnpm check changed the Android runtime asset. Commit the generated file before releasing."
        }

        $verificationChanges = @(git status --porcelain --untracked-files=all)
        if ($LASTEXITCODE -ne 0) {
            throw "Unable to inspect the Git working tree after verification."
        }
        if ($verificationChanges.Count -gt 0) {
            throw "Verification changed repository files. Review and commit them before releasing."
        }
    }

    Invoke-CheckedCommand git tag -a $tagName -m "PocketPilot $versionName"

    try {
        Invoke-CheckedCommand git push origin "refs/tags/$tagName"
    } catch {
        Write-Warning "The local tag $tagName was created but could not be pushed. Fix the remote problem, then run: git push origin refs/tags/$tagName"
        throw
    }

    Write-Host "Pushed $tagName (versionCode $versionCode). GitHub Actions will build, sign, verify, and publish the release."
    Write-Host "Follow progress at https://github.com/string1225/PocketPilot/actions/workflows/release.yml"
} finally {
    Pop-Location
}
