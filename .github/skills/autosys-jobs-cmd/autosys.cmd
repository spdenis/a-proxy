@echo off
rem Queries AutoSys jobs through the AutoSys proxy API and prints its JSON answer.
rem Pure cmd: needs only curl.exe (part of Windows 10 1803 and later), no PowerShell.
rem
rem   autosys.cmd COMMAND [NAME] [-Proxy NAME_OR_URL] [-Status STATUS] [-Comment "text"]
rem
rem   session                       who am I, token expiry
rem   job NAME / status NAME / deps NAME / runs NAME / jobs BOX
rem   search "PATTERN*"             find jobs by name (wildcards * and %%)
rem   force-start NAME [-Comment "why"]
rem   kill NAME [-Comment "why"]
rem   change-status NAME -Status RUNNING^|STARTING^|SUCCESS^|FAILURE^|INACTIVE^|TERMINATED [-Comment "why"]
rem   set-token [TOKEN]             saves the token of the chosen proxy (asks for it when not given)
rem   proxy-add NAME URL            saves a proxy under a name
rem   proxy-use NAME_OR_URL         makes it the proxy every later call uses
rem   proxy-list                    the proxy in use and the saved ones
rem
rem The proxy is, in this order: -Proxy on the call, AUTOSYS_PROXY_URL (a URL or a saved name),
rem the one chosen with proxy-use, http://localhost:8080.
rem The token is read from AUTOSYS_PROXY_TOKEN if set, else from the chosen proxy's own file in
rem %%USERPROFILE%%\.autosys-proxy (token for http://localhost:8080, token-SCHEME-HOST-PORT for others;
rem the same files as autosys.ps1), or from AUTOSYS_PROXY_TOKEN_FILE.
rem Exit code 0 = success, 1 = the API returned a problem (printed as JSON), 2 = setup problem.

setlocal DisableDelayedExpansion
set "CMD="
set "NAME="
set "URLARG="
set "PROXY="
set "STATUS="
set "COMMENT="

:parse
if "%~1"=="" goto parsed
if /i "%~1"=="-Proxy" ( set "PROXY=%~2" & shift & shift & goto parse )
if /i "%~1"=="-Status" ( set "STATUS=%~2" & shift & shift & goto parse )
if /i "%~1"=="-Comment" ( set "COMMENT=%~2" & shift & shift & goto parse )
if not defined CMD ( set "CMD=%~1" & shift & goto parse )
if not defined NAME ( set "NAME=%~1" & shift & goto parse )
if not defined URLARG ( set "URLARG=%~1" & shift & goto parse )
>&2 echo Too many arguments. Run: autosys.cmd help
exit /b 2
:parsed

setlocal EnableDelayedExpansion
set "LETTERS=ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
set "JOBCHARS=%LETTERS%_.#-"
set "PATCHARS=%JOBCHARS%*%%"
set "NAMECHARS=%LETTERS%_.-"
set "URLCHARS=%LETTERS%-._~:/[]@%%"
set "TOKENCHARS=%LETTERS%_-"
set "DEFAULT_URL=http://localhost:8080"
set "CFG=%USERPROFILE%\.autosys-proxy"
set "PROXIES=%CFG%\proxies.txt"
set "CURRENT=%CFG%\current-proxy.txt"
set "TMPB=%TEMP%\aproxy-%RANDOM%%RANDOM%"

set "OK="
for %%c in (set-token session job status deps runs jobs search force-start kill change-status proxy-list proxy-add proxy-use) do if /i "!CMD!"=="%%c" set "CMD=%%c" & set "OK=1"
if not defined OK goto usage

rem --- which proxy
set "BASE=%DEFAULT_URL%"
set "SRC="
if defined PROXY (
    set "SRC=!PROXY!"
) else if defined AUTOSYS_PROXY_URL (
    set "SRC=!AUTOSYS_PROXY_URL!"
) else if exist "%CURRENT%" (
    set /p SRC=<"%CURRENT%"
)
if not defined SRC goto based
set "V=!SRC!"
call :resolve
if errorlevel 1 exit /b 2
set "BASE=!R!"
:based
set "U=!BASE!"
call :tokenfile
set "HOWTO=Log in to AutoSys through the proxy, press Copy token on !BASE!/proxy/token, then run in your own terminal: autosys.cmd set-token"
if defined PROXY set "HOWTO=!HOWTO! -Proxy !PROXY!"

goto cmd_%CMD%

rem ===================================================================== proxies

:cmd_proxy-list
echo Using: !BASE!
set "T=no"
if exist "!TF!" set "T=yes"
if defined AUTOSYS_PROXY_TOKEN set "T=yes, from AUTOSYS_PROXY_TOKEN"
echo Token saved: !T!
if not exist "%PROXIES%" (
    echo No proxies saved yet. Add one with: autosys.cmd proxy-add NAME URL
    exit /b 0
)
echo Saved proxies, * = in use:
for /f "usebackq eol=# tokens=1,* delims==" %%a in ("%PROXIES%") do (
    set "U=%%b"
    call :tokenfile
    set "T=no"
    if exist "!TF!" set "T=yes"
    set "M= "
    if /i "%%b"=="!BASE!" set "M=*"
    echo  !M! %%a = %%b   token: !T!
)
exit /b 0

:cmd_proxy-add
if not defined NAME goto usage
if not defined URLARG goto usage
set "CHK=!NAME!"
set "ALLOW=%NAMECHARS%"
call :onlychars
if errorlevel 1 ( >&2 echo Proxy name "!NAME!" may only contain letters, digits, _ . - & exit /b 2 )
set "V=!URLARG!"
call :isurl
if errorlevel 1 ( >&2 echo "!URLARG!" is not an http or https URL. & exit /b 2 )
set "CHK=!URLARG!"
set "ALLOW=%URLCHARS%"
call :onlychars
if errorlevel 1 ( >&2 echo "!URLARG!" contains characters a proxy URL cannot have. & exit /b 2 )
set "U=!URLARG!"
if "!U:~-1!"=="/" set "U=!U:~0,-1!"
if not exist "%CFG%" mkdir "%CFG%"
if exist "%PROXIES%" (
    findstr /v /b /i /l /c:"!NAME!=" "%PROXIES%" >"%PROXIES%.tmp"
) else (
    type nul >"%PROXIES%.tmp"
)
>>"%PROXIES%.tmp" echo(!NAME!=!U!
move /y "%PROXIES%.tmp" "%PROXIES%" >nul
echo Saved proxy "!NAME!" = !U!. Switch to it with: autosys.cmd proxy-use !NAME!
exit /b 0

:cmd_proxy-use
if not defined NAME goto usage
set "V=!NAME!"
call :resolve
if errorlevel 1 exit /b 2
if not exist "%CFG%" mkdir "%CFG%"
>"%CURRENT%" echo(!NAME!
set "U=!R!"
call :tokenfile
echo Now using !NAME! = !R!
if not exist "!TF!" if not defined AUTOSYS_PROXY_TOKEN echo No token saved for it yet: log in to AutoSys through it, press Copy token on !R!/proxy/token, then run: autosys.cmd set-token
if defined AUTOSYS_PROXY_URL echo Note: AUTOSYS_PROXY_URL is set in this terminal and takes precedence.
exit /b 0

rem ===================================================================== token

:cmd_set-token
set "TOKEN="
if defined NAME (
    set "TOKEN=!NAME!"
) else (
    set /p "TOKEN=Paste the API token from !BASE!/proxy/token and press Enter: "
)
if not defined TOKEN ( >&2 echo No token given. & exit /b 2 )
set "CHK=!TOKEN!"
set "ALLOW=%TOKENCHARS%"
call :onlychars
if errorlevel 1 ( >&2 echo That is not an API token: it looks like asp_ followed by letters and digits. & exit /b 2 )
if not "!TOKEN:~0,4!"=="asp_" ( >&2 echo That is not an API token: it starts with asp_ & exit /b 2 )
set "METHOD=GET"
set "APIPATH=/api/v1/session"
call :api
if not "!CODE:~0,1!"=="2" (
    >&2 echo The proxy at !BASE! did not accept the token, status !CODE!.
    call :cleanup
    exit /b 2
)
if not exist "%CFG%" mkdir "%CFG%"
>"%TF%" echo(!TOKEN!
echo Token for !BASE! saved to %TF%. Session:
type "%TMPB%.body"
echo(
if defined AUTOSYS_PROXY_TOKEN echo Note: AUTOSYS_PROXY_TOKEN is set in this terminal and takes precedence over the file.
call :cleanup
exit /b 0

rem ===================================================================== API calls

:cmd_session
set "APIPATH=/api/v1/session"
goto get

:cmd_job
set "SUFFIX="
goto jobget
:cmd_status
set "SUFFIX=/status"
goto jobget
:cmd_deps
set "SUFFIX=/dependencies"
goto jobget
:cmd_runs
set "SUFFIX=/runs"
goto jobget
:cmd_jobs
set "SUFFIX=/jobs"
goto jobget

:cmd_search
if not defined NAME ( >&2 echo A name pattern is required: autosys.cmd search "PATTERN*" & exit /b 2 )
set "CHK=!NAME!"
set "ALLOW=%PATCHARS%"
call :onlychars
if errorlevel 1 ( >&2 echo Not a valid job name pattern: letters, digits, _ . # - and the wildcards * %% & exit /b 2 )
set "APIPATH=/api/v1/jobs"
set "QUERY=name=!NAME!"
goto get

:cmd_force-start
set "ACTION=FORCE_STARTJOB"
goto action
:cmd_kill
set "ACTION=KILLJOB"
goto action
:cmd_change-status
set "ACTION=CHANGE_STATUS"
set "OK="
for %%s in (RUNNING STARTING SUCCESS FAILURE INACTIVE TERMINATED) do if /i "!STATUS!"=="%%s" set "STATUS=%%s" & set "OK=1"
if not defined OK ( >&2 echo change-status needs -Status RUNNING, STARTING, SUCCESS, FAILURE, INACTIVE or TERMINATED & exit /b 2 )
goto action

:jobget
call :jobpath
if errorlevel 1 exit /b 2
set "APIPATH=!JP!!SUFFIX!"
goto get

rem changes AutoSys: run only when the user has asked for it and confirmed the job name
:action
call :jobpath
if errorlevel 1 exit /b 2
set "APIPATH=!JP!/actions"
set "J={"action":"!ACTION!""
if "!ACTION!"=="CHANGE_STATUS" set "J=!J!,"status":"!STATUS!""
if not defined COMMENT goto action_send
set "JC=!COMMENT:\=\\!"
set "JC=!JC:"=\"!"
set "J=!J!,"comment":"!JC!""
:action_send
set "J=!J!}"
>"%TMPB%.json" echo(!J!
set "METHOD=POST"
goto call

:get
set "METHOD=GET"
:call
call :loadtoken
if errorlevel 1 exit /b 2
call :api
set "RC=1"
if "!CODE!"=="000" (
    >&2 echo The proxy at !BASE! is not reachable:
    if exist "%TMPB%.err" >&2 type "%TMPB%.err"
    set "RC=2"
) else if "!CODE!"=="401" (
    >&2 type "%TMPB%.body"
    >&2 echo(
    >&2 echo 401: the token is missing, expired or was replaced by a newer login. !HOWTO!
    set "RC=2"
) else (
    type "%TMPB%.body"
    echo(
    if "!CODE:~0,1!"=="2" set "RC=0"
)
call :cleanup
exit /b !RC!

rem ===================================================================== helpers

rem METHOD, APIPATH, optional QUERY (name=value, URL-encoded by curl), TOKEN -> CODE, %TMPB%.body
:api
>"%TMPB%.hdr" echo(Authorization: Bearer !TOKEN!
set "CODE=000"
if "!METHOD!"=="POST" (
    rem lets the proxy answer a duplicate of this request from the first one instead of acting twice
    curl.exe -sS --max-time 180 -o "%TMPB%.body" -w "%%{http_code}" -H "@%TMPB%.hdr" -H "Accept: application/json" -H "Content-Type: application/json" -H "Idempotency-Key: cmd-%RANDOM%%RANDOM%-%RANDOM%%RANDOM%-%RANDOM%" -X POST --data-binary "@%TMPB%.json" "!BASE!!APIPATH!" >"%TMPB%.code" 2>"%TMPB%.err"
) else if defined QUERY (
    curl.exe -sS --max-time 120 -o "%TMPB%.body" -w "%%{http_code}" -H "@%TMPB%.hdr" -H "Accept: application/json" -G --data-urlencode "!QUERY!" "!BASE!!APIPATH!" >"%TMPB%.code" 2>"%TMPB%.err"
) else (
    curl.exe -sS --max-time 120 -o "%TMPB%.body" -w "%%{http_code}" -H "@%TMPB%.hdr" -H "Accept: application/json" "!BASE!!APIPATH!" >"%TMPB%.code" 2>"%TMPB%.err"
)
del /q "%TMPB%.hdr" 2>nul
if exist "%TMPB%.code" set /p CODE=<"%TMPB%.code"
if not defined CODE set "CODE=000"
if not exist "%TMPB%.body" type nul >"%TMPB%.body"
exit /b 0

:loadtoken
where curl.exe >nul 2>&1
if errorlevel 1 ( >&2 echo curl.exe was not found; it is part of Windows 10 1803 and later. & exit /b 1 )
set "TOKEN="
if defined AUTOSYS_PROXY_TOKEN set "TOKEN=!AUTOSYS_PROXY_TOKEN!"
if not defined TOKEN if exist "%TF%" set /p TOKEN=<"%TF%"
if not defined TOKEN ( >&2 echo No API token yet for !BASE!. !HOWTO! & exit /b 1 )
exit /b 0

rem NAME -> JP, the job's API path; errorlevel 1 when it is not a job name
:jobpath
if not defined NAME ( >&2 echo A job name is required: autosys.cmd !CMD! JOB_NAME & exit /b 1 )
set "CHK=!NAME!"
set "ALLOW=%JOBCHARS%"
call :onlychars
if errorlevel 1 ( >&2 echo Not a valid AutoSys job name: letters, digits, _ . # - only. & exit /b 1 )
set "JP=/api/v1/jobs/!NAME:#=%%23!"
exit /b 0

rem errorlevel 1 when CHK is empty or has a character outside ALLOW
:onlychars
if not defined CHK exit /b 1
set "BAD="
for /f "delims=%ALLOW%" %%x in ("x!CHK!") do set "BAD=1"
if defined BAD exit /b 1
exit /b 0

:isurl
if /i "!V:~0,7!"=="http://" exit /b 0
if /i "!V:~0,8!"=="https://" exit /b 0
exit /b 1

rem V, a saved name or a URL -> R, the proxy's base URL
:resolve
set "R="
call :isurl
if not errorlevel 1 set "R=!V!"
if not defined R if exist "%PROXIES%" for /f "usebackq eol=# tokens=1,* delims==" %%a in ("%PROXIES%") do if /i "%%a"=="!V!" set "R=%%b"
if not defined R ( >&2 echo Unknown proxy "!V!". Add it with: autosys.cmd proxy-add !V! URL - or see: autosys.cmd proxy-list & exit /b 1 )
if "!R:~-1!"=="/" set "R=!R:~0,-1!"
exit /b 0

rem U, a proxy URL -> TF, the file with its token (tokens are per proxy, never sent to another one)
:tokenfile
if defined AUTOSYS_PROXY_TOKEN_FILE ( set "TF=!AUTOSYS_PROXY_TOKEN_FILE!" & exit /b 0 )
if /i "!U!"=="%DEFAULT_URL%" ( set "TF=%CFG%\token" & exit /b 0 )
set "K=!U:://=-!"
set "K=!K::=-!"
set "K=!K:/=_!"
set "TF=%CFG%\token-!K!"
exit /b 0

:cleanup
del /q "%TMPB%.*" 2>nul
exit /b 0

:usage
echo Usage: autosys.cmd COMMAND [NAME] [-Proxy NAME_OR_URL] [-Status STATUS] [-Comment "text"]
echo   session ^| job NAME ^| status NAME ^| deps NAME ^| runs NAME ^| jobs BOX ^| search "PATTERN*"
echo   force-start NAME ^| kill NAME ^| change-status NAME -Status STATUS   [-Comment "why"]
echo   set-token [TOKEN] ^| proxy-add NAME URL ^| proxy-use NAME_OR_URL ^| proxy-list
exit /b 2
