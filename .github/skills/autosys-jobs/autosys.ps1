<#
.SYNOPSIS
    Queries AutoSys jobs through the AutoSys proxy API and prints JSON.

.DESCRIPTION
    Single calls (job, status, deps, runs, jobs, search, session) print the API response.
    Traversals (downstream, upstream, tree) make one call per job and print the collected graph.
    force-start sends FORCE_STARTJOB for one job (only after the user has confirmed it) and prints the outcome.
    change-status sends CHANGE_STATUS with -Status for one job (only after the user has confirmed it).
    kill sends KILLJOB for one running job (only after the user has confirmed it).

    The proxy is chosen by, in this order:
      -Proxy NAME|URL           for this call only
      AUTOSYS_PROXY_URL         environment variable (a URL or a saved name)
      the current proxy         set with "autosys.ps1 proxy-use NAME|URL"
      http://localhost:8080     otherwise
    Names are saved with "autosys.ps1 proxy-add NAME URL" in ~/.autosys-proxy/proxies.json;
    "autosys.ps1 proxy-list" shows them.

    The API token (asp_..., from <proxy>/proxy/token) belongs to one proxy and is read from
      AUTOSYS_PROXY_TOKEN       environment variable, if set (used for whichever proxy is chosen), else
      a file per proxy          saved by "autosys.ps1 set-token": ~/.autosys-proxy/token for
                                http://localhost:8080, ~/.autosys-proxy/token-<scheme>-<host>-<port> for others
                                (one fixed file instead: AUTOSYS_PROXY_TOKEN_FILE)

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File autosys.ps1 proxy-add uat https://aproxy-uat.example:8443
    powershell -NoProfile -ExecutionPolicy Bypass -File autosys.ps1 proxy-use uat
    powershell -NoProfile -ExecutionPolicy Bypass -File autosys.ps1 proxy-list
    powershell -NoProfile -ExecutionPolicy Bypass -File autosys.ps1 status MY_JOB -Proxy local
    powershell -NoProfile -ExecutionPolicy Bypass -File autosys.ps1 set-token     # token from the clipboard
    powershell -NoProfile -ExecutionPolicy Bypass -File autosys.ps1 deps MY_JOB
    powershell -NoProfile -ExecutionPolicy Bypass -File autosys.ps1 downstream MY_JOB -Depth 3
    powershell -NoProfile -ExecutionPolicy Bypass -File autosys.ps1 tree MY_BOX
    powershell -NoProfile -ExecutionPolicy Bypass -File autosys.ps1 force-start MY_JOB -Comment "rerun after fix"
    powershell -NoProfile -ExecutionPolicy Bypass -File autosys.ps1 change-status MY_JOB -Status INACTIVE -Comment "reset"
    powershell -NoProfile -ExecutionPolicy Bypass -File autosys.ps1 kill MY_JOB -Comment "hung since 02:00"
#>
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidateSet('set-token', 'session', 'job', 'status', 'deps', 'runs', 'jobs', 'search', 'downstream', 'upstream', 'tree',
        'force-start', 'change-status', 'kill', 'proxy-list', 'proxy-add', 'proxy-use')]
    [string]$Command,

    [Parameter(Position = 1)]
    [string]$Name,

    # proxy-add: the proxy's base URL
    [Parameter(Position = 2)]
    [string]$Url,

    # the proxy to use for this call: a name saved with proxy-add, or a URL
    [string]$Proxy,

    # traversals: how many levels to follow
    [int]$Depth = 5,

    # traversals: stop after this many jobs (one API call each)
    [int]$MaxJobs = 100,

    # change-status: the new status
    [ValidateSet('RUNNING', 'STARTING', 'SUCCESS', 'FAILURE', 'INACTIVE', 'TERMINATED')]
    [string]$Status,

    # force-start, change-status, kill: why, written to the proxy's audit log (and the event's comment)
    [string]$Comment
)

$ErrorActionPreference = 'Stop'
$defaultUrl = 'http://localhost:8080'
$configDir = Join-Path $HOME '.autosys-proxy'
$proxiesFile = Join-Path $configDir 'proxies.json'

function Fail([string]$message) {
    [Console]::Error.WriteLine($message)
    exit 2
}

# ~/.autosys-proxy/proxies.json: { "current": "<name or URL>", "proxies": { "<name>": "<URL>" } }
function Read-Proxies {
    $cfg = [ordered]@{ current = $null; proxies = [ordered]@{} }
    if (Test-Path -LiteralPath $proxiesFile) {
        try { $json = Get-Content -LiteralPath $proxiesFile -Raw | ConvertFrom-Json } catch { Fail "Cannot read ${proxiesFile}: $($_.Exception.Message)" }
        if ($json.current) { $cfg.current = [string]$json.current }
        if ($json.proxies) {
            foreach ($p in $json.proxies.PSObject.Properties) { $cfg.proxies[$p.Name] = ([string]$p.Value).TrimEnd('/') }
        }
    }
    return $cfg
}

function Save-Proxies($cfg) {
    if (-not (Test-Path -LiteralPath $configDir)) { New-Item -ItemType Directory -Path $configDir | Out-Null }
    $cfg | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $proxiesFile -Encoding UTF8
}

function Is-Url([string]$value) { return $value -match '^https?://[^/\s]+' }

# a saved name or a URL -> the proxy's base URL
function Resolve-Proxy([string]$value) {
    if (Is-Url $value) { return $value.TrimEnd('/') }
    foreach ($key in $proxyCfg.proxies.Keys) {
        if ($key -eq $value) { return $proxyCfg.proxies[$key] }
    }
    $known = if ($proxyCfg.proxies.Count) { ($proxyCfg.proxies.Keys -join ', ') } else { 'none' }
    Fail "Unknown proxy '$value' (saved: $known). Add it with: autosys.ps1 proxy-add $value <URL>"
}

# tokens are per proxy, so a token is never sent to another proxy than the one that issued it
function Token-File([string]$url) {
    if ($env:AUTOSYS_PROXY_TOKEN_FILE) { return $env:AUTOSYS_PROXY_TOKEN_FILE }
    if ($url -eq $defaultUrl) { return Join-Path $configDir 'token' }
    $u = [uri]$url
    $key = ($u.Scheme + '-' + $u.Host + '-' + $u.Port + $u.AbsolutePath.TrimEnd('/')) -replace '[^A-Za-z0-9._-]', '_'
    return Join-Path $configDir "token-$key"
}

$proxyCfg = Read-Proxies
$base = $defaultUrl
if ($Proxy) { $base = Resolve-Proxy $Proxy }
elseif ($env:AUTOSYS_PROXY_URL) { $base = Resolve-Proxy $env:AUTOSYS_PROXY_URL }
elseif ($proxyCfg.current) { $base = Resolve-Proxy $proxyCfg.current }
$tokenFile = Token-File $base
$proxyHint = if ($Proxy) { " -Proxy $Proxy" } else { '' }
$howToSetToken = "Log in to AutoSys through the proxy, press 'Copy token' on $base/proxy/token, then run: autosys.ps1 set-token$proxyHint"

# proxy settings need no token
switch ($Command) {
    'proxy-list' {
        $names = foreach ($key in $proxyCfg.proxies.Keys) {
            $url = $proxyCfg.proxies[$key]
            [ordered]@{ name = $key; url = $url; current = ($url -eq $base); hasToken = (Test-Path -LiteralPath (Token-File $url)) }
        }
        [ordered]@{ using = $base; hasToken = (Test-Path -LiteralPath $tokenFile); proxies = @($names) } | ConvertTo-Json -Depth 5
        exit 0
    }
    'proxy-add' {
        if (-not $Name -or -not $Url) { Fail 'Usage: autosys.ps1 proxy-add NAME URL' }
        if ($Name -notmatch '^[A-Za-z0-9_.-]+$') { Fail "Proxy name '$Name' may only contain letters, digits, _ . -" }
        if (-not (Is-Url $Url)) { Fail "'$Url' is not an http(s) URL" }
        foreach ($key in @($proxyCfg.proxies.Keys)) { if ($key -eq $Name) { $proxyCfg.proxies.Remove($key) } }
        $proxyCfg.proxies[$Name] = $Url.TrimEnd('/')
        Save-Proxies $proxyCfg
        Write-Output "Saved proxy '$Name' = $($Url.TrimEnd('/')). Switch to it with: autosys.ps1 proxy-use $Name"
        exit 0
    }
    'proxy-use' {
        if (-not $Name) { Fail 'Usage: autosys.ps1 proxy-use NAME|URL' }
        $url = Resolve-Proxy $Name
        $proxyCfg.current = $Name
        Save-Proxies $proxyCfg
        Write-Output "Now using $Name ($url)."
        if (-not (Test-Path -LiteralPath (Token-File $url)) -and -not $env:AUTOSYS_PROXY_TOKEN) {
            Write-Output "No token saved for it yet: log in to AutoSys through it, press 'Copy token' on $url/proxy/token, then run: autosys.ps1 set-token"
        }
        if ($env:AUTOSYS_PROXY_URL) { Write-Output "Note: AUTOSYS_PROXY_URL is set in this terminal and takes precedence." }
        exit 0
    }
}

if ($Command -eq 'set-token') {
    $token = if ($Name) { $Name } else { (Get-Clipboard | Out-String) }
    $token = "$token".Trim()
    if ($token -notmatch '^asp_[A-Za-z0-9_-]+$') {
        Fail "No API token (asp_...) $(if ($Name) { 'given' } else { 'in the clipboard' }). $howToSetToken"
    }
} else {
    $token = $env:AUTOSYS_PROXY_TOKEN
    if (-not $token -and (Test-Path -LiteralPath $tokenFile)) {
        $token = (Get-Content -LiteralPath $tokenFile -Raw).Trim()
    }
    if (-not $token) {
        Fail "No API token yet. $howToSetToken"
    }
}
if ($Command -ne 'session' -and $Command -ne 'set-token' -and -not $Name) {
    Fail "A job name is required: autosys.ps1 $Command <JOB_NAME>"
}

# calls an API path; returns @{ ok; status; body } instead of throwing, so traversals can go on
function Get-Api([string]$path, [string]$method = 'Get', $payload = $null) {
    try {
        $headers = @{ Authorization = "Bearer $token"; Accept = 'application/json' }
        if ($payload) {
            # lets the proxy answer a duplicate of this request from the first one instead of acting twice
            $headers['Idempotency-Key'] = [guid]::NewGuid().ToString()
            $body = Invoke-RestMethod -Method $method -Uri ($base + $path) -TimeoutSec 180 -Headers $headers `
                -ContentType 'application/json' -Body ($payload | ConvertTo-Json -Compress)
        } else {
            $body = Invoke-RestMethod -Method $method -Uri ($base + $path) -TimeoutSec 120 -Headers $headers
        }
        return @{ ok = $true; status = 200; body = $body }
    } catch {
        $status = 0
        if ($_.Exception.Response) { $status = [int]$_.Exception.Response.StatusCode }
        $problem = $null
        if ($_.ErrorDetails -and $_.ErrorDetails.Message) {
            try { $problem = $_.ErrorDetails.Message | ConvertFrom-Json } catch { $problem = $_.ErrorDetails.Message }
        }
        if ($status -eq 0) {
            Fail "The proxy at $base is not reachable: $($_.Exception.Message)"
        }
        if ($status -eq 401) {
            $why = if ($problem -and $problem.detail) { $problem.detail } else { 'the token is expired or was replaced by a newer login' }
            Fail "401: $why. $howToSetToken"
        }
        return @{ ok = $false; status = $status; body = $problem }
    }
}

function Job-Path([string]$job, [string]$suffix = '') {
    return '/api/v1/jobs/' + [uri]::EscapeDataString($job) + $suffix
}

function Print($value) {
    $value | ConvertTo-Json -Depth 20
}

function Print-Single([string]$path) {
    $r = Get-Api $path
    Print $r.body
    if (-not $r.ok) { exit 1 }
}

# job names referenced by an AutoSys condition, e.g. "s(A) & (d(B,10.00) | e(C)=1) & v(GLOBAL)=x"
function Condition-Jobs([string]$condition) {
    if (-not $condition) { return @() }
    $names = foreach ($m in [regex]::Matches($condition, '(?i)\b(?:s|success|f|failure|d|done|n|notrunning|t|terminated|e|exitcode)\s*\(\s*([^,()\s]+)')) {
        $m.Groups[1].Value
    }
    return @($names | Select-Object -Unique)
}

# breadth-first walk; $next returns @(@{ job; edge }) for one job's details
function Walk([string]$start, [string]$endpoint, [scriptblock]$next) {
    $seen = @{}
    $nodes = New-Object System.Collections.ArrayList
    $edges = New-Object System.Collections.ArrayList
    $errors = New-Object System.Collections.ArrayList
    $queue = New-Object System.Collections.Queue
    $queue.Enqueue(@{ job = $start; depth = 0 })
    $seen[$start.ToUpperInvariant()] = $true
    $truncated = $false
    while ($queue.Count -gt 0) {
        $item = $queue.Dequeue()
        if ($nodes.Count -ge $MaxJobs) { $truncated = $true; break }
        $r = Get-Api (Job-Path $item.job $endpoint)
        if (-not $r.ok) {
            [void]$errors.Add([ordered]@{ job = $item.job; status = $r.status; problem = $r.body })
            continue
        }
        [void]$nodes.Add([ordered]@{ job = $item.job; depth = $item.depth; details = $r.body })
        foreach ($n in (& $next $r.body)) {
            [void]$edges.Add($n.edge)
            $key = $n.job.ToUpperInvariant()
            if ($item.depth + 1 -le $Depth -and -not $seen.ContainsKey($key)) {
                $seen[$key] = $true
                $queue.Enqueue(@{ job = $n.job; depth = $item.depth + 1 })
            } elseif ($item.depth + 1 -gt $Depth -and -not $seen.ContainsKey($key)) {
                $truncated = $true
            }
        }
    }
    return [ordered]@{
        start = $start; maxDepth = $Depth; jobsVisited = $nodes.Count
        truncated = $truncated; nodes = $nodes; edges = $edges; errors = $errors
    }
}

switch ($Command) {
    # checks the token with the proxy, then saves it for later calls (also from other terminals)
    'set-token' {
        $r = Get-Api '/api/v1/session'
        if (-not $r.ok) { Fail "The proxy did not accept the token (status $($r.status))." }
        $dir = Split-Path -Parent $tokenFile
        if (-not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }
        Set-Content -LiteralPath $tokenFile -Value $token -NoNewline -Encoding Ascii
        if ($IsLinux -or $IsMacOS) { chmod 600 $tokenFile }
        Write-Output "Token for $base saved to $tokenFile for user '$($r.body.username)' (valid until $($r.body.expiresAt) if unused, at most until $($r.body.absoluteExpiresAt))."
        if ($env:AUTOSYS_PROXY_TOKEN) { Write-Output "Note: AUTOSYS_PROXY_TOKEN is set in this terminal and takes precedence over the file." }
    }
    'session' { Print-Single '/api/v1/session' }
    'job'     { Print-Single (Job-Path $Name) }
    'status'  { Print-Single (Job-Path $Name '/status') }
    'deps'    { Print-Single (Job-Path $Name '/dependencies') }
    'runs'    { Print-Single (Job-Path $Name '/runs') }
    'jobs'    { Print-Single (Job-Path $Name '/jobs') }
    'search'  { Print-Single ('/api/v1/jobs?name=' + [uri]::EscapeDataString($Name)) }

    # changes AutoSys: run only when the user has asked for it and confirmed the job name
    'force-start' {
        $payload = [ordered]@{ action = 'FORCE_STARTJOB' }
        if ($Comment) { $payload['comment'] = $Comment }
        $r = Get-Api (Job-Path $Name '/actions') 'Post' $payload
        Print $r.body
        if (-not $r.ok) { exit 1 }
    }

    'kill' {
        $payload = [ordered]@{ action = 'KILLJOB' }
        if ($Comment) { $payload['comment'] = $Comment }
        $r = Get-Api (Job-Path $Name '/actions') 'Post' $payload
        Print $r.body
        if (-not $r.ok) { exit 1 }
    }

    'change-status' {
        if (-not $Status) { Fail "change-status needs -Status (RUNNING, STARTING, SUCCESS, FAILURE, INACTIVE, TERMINATED)" }
        $payload = [ordered]@{ action = 'CHANGE_STATUS'; status = $Status.ToUpperInvariant() }
        if ($Comment) { $payload['comment'] = $Comment }
        $r = Get-Api (Job-Path $Name '/actions') 'Post' $payload
        Print $r.body
        if (-not $r.ok) { exit 1 }
    }

    # jobs whose conditions refer to this job, and the jobs depending on those, ...
    'downstream' {
        $result = Walk $Name '/dependencies' {
            param($d)
            foreach ($dep in @($d.dependents)) {
                if ($dep.jobName) {
                    @{ job = $dep.jobName; edge = [ordered]@{ from = $d.name; to = $dep.jobName
                            condition = $dep.condition; satisfied = $dep.satisfied } }
                }
            }
        }
        $result['nodes'] = @($result['nodes'] | ForEach-Object {
                [ordered]@{ job = $_.job; depth = $_.depth; condition = $_.details.condition } })
        Print $result
    }

    # jobs this job's condition refers to, and their conditions, ...
    'upstream' {
        $result = Walk $Name '/dependencies' {
            param($d)
            $byName = @{}
            foreach ($c in @($d.conditions)) {
                foreach ($j in (Condition-Jobs $c.condition)) { $byName[$j.ToUpperInvariant()] = @($j, $c) }
                if ($c.condition -match '^[A-Za-z0-9_.#-]+$') { $byName[$c.condition.ToUpperInvariant()] = @($c.condition, $c) }
            }
            foreach ($j in (Condition-Jobs $d.condition)) {
                if (-not $byName.ContainsKey($j.ToUpperInvariant())) { $byName[$j.ToUpperInvariant()] = @($j, $null) }
            }
            foreach ($entry in $byName.Values) {
                $c = $entry[1]
                @{ job = $entry[0]; edge = [ordered]@{ from = $entry[0]; to = $d.name
                        expectedValue = if ($c) { $c.expectedValue } else { $null }
                        currentStatus = if ($c) { $c.currentStatus } else { $null }
                        satisfied = if ($c) { $c.satisfied } else { $null } } }
            }
        }
        $result['nodes'] = @($result['nodes'] | ForEach-Object {
                [ordered]@{ job = $_.job; depth = $_.depth; condition = $_.details.condition } })
        Print $result
    }

    # a box with all jobs inside it, nested boxes expanded
    'tree' {
        $result = Walk $Name '/jobs' {
            param($b)
            foreach ($j in @($b.jobs)) {
                if ($j.type -eq 'BOX') { @{ job = $j.name; edge = [ordered]@{ box = $b.name; job = $j.name } } }
            }
        }
        $result['nodes'] = @($result['nodes'] | ForEach-Object {
                [ordered]@{ box = $_.job; depth = $_.depth; jobs = $_.details.jobs } })
        [void]$result.Remove('edges')
        Print $result
    }
}
