@echo off
rem 启动后端（需要 DeepSeek 密钥，否则聊天与报告生成会失败）
rem PowerShell 用法：  $env:DEEPSEEK_API_KEY="sk-..."; .\start-backend.cmd
cd /d "%~dp0backend"
if "%DEEPSEEK_API_KEY%"=="" (
  echo [错误] 环境变量 DEEPSEEK_API_KEY 未设置，聊天与报告生成会失败。
  exit /b 1
)
echo 已读取密钥前 8 位: %DEEPSEEK_API_KEY:~0,8%...
java -jar target\psych-backend-0.0.1.jar