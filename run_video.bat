@echo off
REM ===== AutoDrive — รันกับวิดีโอตัวอย่าง / ไฟล์ของคุณ =====
REM ดับเบิลคลิก = ใช้วิดีโอตัวอย่าง (โหลดอัตโนมัติครั้งแรก)
REM หรือ ลากไฟล์วิดีโอมาวางบนไฟล์นี้ เพื่อใช้วิดีโอนั้น
cd /d "%~dp0"
if "%~1"=="" (
  ".venv\Scripts\python.exe" autodrive.py
) else (
  ".venv\Scripts\python.exe" autodrive.py --source "%~1"
)
pause
