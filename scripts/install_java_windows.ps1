param(
    [string]$InstallRoot = (Join-Path $env:ProgramFiles 'Java\openjdk'),
    [switch]$ConfigureMachine
)

$ErrorActionPreference = 'Stop'
$root = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($InstallRoot)
$javaHome = Join-Path $root 'jdk-21.0.13.1'
$releaseUrl = 'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13.1%2B2/OpenJDK21U-jdk_x64_windows_hotspot_21.0.13.1_2.zip'
$expectedHash = 'bbebea1469996b276683c9299c227b294f85c847039c82633218186a4ad4fbf2'

if (-not (Test-Path -LiteralPath $javaHome)) {
    $stage = Join-Path $root ('.haveno-jdk-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $stage | Out-Null
    try {
        $archive = Join-Path $stage 'jdk.zip'
        Invoke-WebRequest -UseBasicParsing -Uri $releaseUrl -OutFile $archive
        if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $expectedHash) {
            throw 'Temurin archive SHA-256 mismatch.'
        }

        $extracted = Join-Path $stage 'extracted'
        Expand-Archive -LiteralPath $archive -DestinationPath $extracted
        $entries = @(Get-ChildItem -LiteralPath $extracted)
        if ($entries.Count -ne 1 -or -not $entries[0].PSIsContainer -or
            -not (Test-Path -LiteralPath (Join-Path $entries[0].FullName 'bin\java.exe') -PathType Leaf) -or
            -not (Test-Path -LiteralPath (Join-Path $entries[0].FullName 'bin\javac.exe') -PathType Leaf)) {
            throw 'Temurin archive does not contain a complete JDK.'
        }
        [IO.Directory]::Move($entries[0].FullName, $javaHome)
    }
    finally {
        Remove-Item -LiteralPath $stage -Recurse -Force
    }
}

if (-not (Test-Path -LiteralPath (Join-Path $javaHome 'bin\java.exe') -PathType Leaf) -or
    -not (Test-Path -LiteralPath (Join-Path $javaHome 'bin\javac.exe') -PathType Leaf)) {
    throw "Incomplete JDK at $javaHome."
}

if ($ConfigureMachine) {
    $javaBin = '%JAVA_HOME%\bin'
    $key = [Microsoft.Win32.Registry]::LocalMachine.OpenSubKey(
        'SYSTEM\CurrentControlSet\Control\Session Manager\Environment', $true)
    try {
        $machinePath = $key.GetValue('Path', '', [Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames)
        if (($machinePath -split ';') -notcontains $javaBin) {
            $newPath = if ($machinePath) { "$machinePath;$javaBin" } else { $javaBin }
            $key.SetValue('Path', $newPath, [Microsoft.Win32.RegistryValueKind]::ExpandString)
        }
    }
    finally {
        $key.Dispose()
    }
    [Environment]::SetEnvironmentVariable('JAVA_HOME', $javaHome, 'Machine')
}
Write-Output $javaHome
