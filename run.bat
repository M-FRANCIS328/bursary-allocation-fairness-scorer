@echo off
rem Compile the three source files into the out folder
javac -d out src\*.java || exit /b 1
rem Start the web application, then open http://localhost:8080
java -cp out App
