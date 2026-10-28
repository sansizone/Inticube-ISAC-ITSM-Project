@echo off
cd /d %~dp0
cd frontend
call npm ci || exit /b 1
call npm run build || exit /b 1
cd ..\backend
call mvn -q clean package -DskipTests || exit /b 1
cd ..
if not exist dist\config mkdir dist\config
copy /y backend\target\isac-project.jar dist\ >nul
copy /y deploy\start.bat dist\ >nul
copy /y deploy\start.sh dist\ >nul
copy /y deploy\postgres-init.sql dist\ >nul
if not exist dist\config\application.yml copy deploy\config\application.yml dist\config\ >nul
echo Done: dist\isac-project.jar
