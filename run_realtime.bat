@echo off
REM ===== AutoDrive — รันสดจากเว็บแคม (real-time) =====
REM ดับเบิลคลิกไฟล์นี้เพื่อเริ่ม  /  กด Q ในหน้าต่างเพื่อออก
cd /d "%~dp0"
".venv\Scripts\python.exe" autodrive.py --source 0 --imgsz 480 --skip 1
pause
