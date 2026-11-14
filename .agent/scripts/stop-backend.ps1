Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
  Where-Object { $_.CommandLine -like '*fs-admin.jar*' } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force; "stopped pid=$($_.ProcessId)" }
