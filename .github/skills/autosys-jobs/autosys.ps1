<#
.SYNOPSIS
    Queries AutoSys jobs through the AutoSys proxy API and prints JSON.

.DESCRIPTION
    Single calls (job, status, deps, runs, jobs, search, session) print the API response.
    Traversals (downstream, upstream, tree) make one call per job and print the collected graph.

    Needs:
      AUTOSYS_PROXY_TOKEN  API token from http://localhost:8080/proxy/token (asp_...)
      AUTOSYS_PROXY_URL    proxy base URL, default http://localhost:8080

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File autosys.ps1 deps MY_JOB
    powershell -NoProfile -ExecutionPolicy Bypass -File autosys.ps1 downstream MY_JOB -Depth 3
    powershell -NoProfile -ExecutionPolicy Bypass -File autosys.ps1 tree MY_BOX
#>
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidateSet('session', 'job', 'status', 'deps', 'runs', 'jobs', 'search', 'downstream', 'upstream', 'tree')]
    [string]$Command,

    [Parameter(Position = 1)]
    [string]$Name,

    # traversals: how many levels to follow
    [int]$Depth = 5,

    # traversals: stop after this many jobs (one API call each)
    [int]$MaxJobs = 100
)

$ErrorActionPreference = 'Stop'
$base = if ($env:AUTOSYS_PROXY_URL) { $env:AUTOSYS_PROXY_URL.TrimEnd('/') } else { 'http://localhost:8080' }
$token = $env:AUTOSYS_PROXY_TOKEN

function Fail([string]$message) {
    [Console]::Error.WriteLine($message)
    exit 2
}

if (-not $token) {
    Fail "AUTOSYS_PROXY_TOKEN is not set. Log in to AutoSys through the proxy, copy the token from $base/proxy/token and set it: `$env:AUTOSYS_PROXY_TOKEN = 'asp_...'"
}
if ($Command -ne 'session' -and -not $Name) {
    Fail "A job name is required: autosys.ps1 $Command <JOB_NAME>"
}

# GET an API path; returns @{ ok; status; body } instead of throwing, so traversals can go on
function Get-Api([string]$path) {
    try {
        $body = Invoke-RestMethod -Method Get -Uri ($base + $path) -TimeoutSec 120 `
            -Headers @{ Authorization = "Bearer $token"; Accept = 'application/json' }
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
            Fail "401: the token is missing, expired or replaced by a newer login. Log in again through the proxy and copy a new token from $base/proxy/token."
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
    'session' { Print-Single '/api/v1/session' }
    'job'     { Print-Single (Job-Path $Name) }
    'status'  { Print-Single (Job-Path $Name '/status') }
    'deps'    { Print-Single (Job-Path $Name '/dependencies') }
    'runs'    { Print-Single (Job-Path $Name '/runs') }
    'jobs'    { Print-Single (Job-Path $Name '/jobs') }
    'search'  { Print-Single ('/api/v1/jobs?name=' + [uri]::EscapeDataString($Name)) }

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
