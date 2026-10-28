@echo off
rem TeamBoard start (PostgreSQL). DB settings: config\application.yml or env DB_URL/DB_USERNAME/DB_PASSWORD
cd /d %~dp0
if not "%DB_PASSWORD%"=="" goto run
rem Skip the prompt when config\application.yml has the password itself
if exist config\application.yml (
  findstr /c:"${DB_PASSWORD" config\application.yml >nul
  if errorlevel 1 goto run
)
for /f "usebackq delims=" %%p in (`powershell -NoProfile -Command "$s=Read-Host -AsSecureString 'PostgreSQL password'; [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($s))"`) do set "DB_PASSWORD=%%p"
:run
java -Xms256m -Xmx1g -Dfile.encoding=UTF-8 -jar isac-project.jar
pause
