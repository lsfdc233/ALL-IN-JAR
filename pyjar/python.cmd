@echo off
rem pyjar 的 Windows 便捷入口: python.cmd 等价于 java -jar python.jar
java -jar "%~dp0python.jar" %*
exit /b %ERRORLEVEL%
