# SPDX-License-Identifier: MIT
# Builds the Emberlight DLSS shim on Windows and installs it with NVIDIA's DLSS, DLSS-FG and DLSS-RR libraries.
# usage: powershell -ExecutionPolicy Bypass -File build-dlss.ps1 <minecraft game dir>
# Needs git and the Visual Studio Build Tools with "Desktop development with C++".
# Downloads the DLSS SDK only from NVIDIA's GitHub (github.com/NVIDIA/DLSS) and the Vulkan headers from
# github.com/KhronosGroup/Vulkan-Headers. By running this you accept NVIDIA's RTX SDK licence (LICENSE.txt in that
# repository).
param([Parameter(Mandatory = $true)][string]$GameDir)
$ErrorActionPreference = 'Stop'
$Tag = if ($env:DLSS_TAG) { $env:DLSS_TAG } else { 'v310.9.16' }
$Here = $PSScriptRoot
$Work = Join-Path ([IO.Path]::GetTempPath()) ("emberlight-dlss-" + [guid]::NewGuid())
New-Item -ItemType Directory $Work | Out-Null
try {
	git clone -q --depth 1 --branch $Tag --filter=blob:none --sparse https://github.com/NVIDIA/DLSS.git "$Work\dlss"
	if ($LASTEXITCODE) { throw 'cloning NVIDIA/DLSS failed' }
	git -C "$Work\dlss" sparse-checkout set --no-cone '/include/*' '/LICENSE.txt' '/lib/Windows_x86_64/x64/nvsdk_ngx_s.lib' '/lib/Windows_x86_64/rel/nvngx_dlss.dll' '/lib/Windows_x86_64/rel/nvngx_dlssg.dll' '/lib/Windows_x86_64/rel/nvngx_dlssd.dll'
	if ($LASTEXITCODE) { throw 'fetching the DLSS SDK files failed' }
	git clone -q --depth 1 --filter=blob:none --sparse https://github.com/KhronosGroup/Vulkan-Headers.git "$Work\vk"
	git -C "$Work\vk" sparse-checkout set include
	if ($LASTEXITCODE) { throw 'fetching the Vulkan headers failed' }

	$vswhere = "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe"
	if (!(Test-Path $vswhere)) { throw 'Visual Studio Build Tools not found (install "Desktop development with C++")' }
	$vs = & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
	if (!$vs) { throw 'no Visual Studio with the C++ x64 tools found' }
	$vcvars = Join-Path $vs 'VC\Auxiliary\Build\vcvars64.bat'

	$Out = Join-Path $GameDir 'emberlight\dlss'
	New-Item -ItemType Directory -Force $Out | Out-Null
	$inc = "/I`"$Work\dlss\include`" /I`"$Work\vk\include`""
	$cmd = "call `"$vcvars`" >nul && cd /d `"$Work`" " +
		"&& cl /nologo /O2 /MT /c $inc `"$Here\emrion_ngx.c`" " +
		"&& cl /nologo /O2 /MT /EHsc /c $inc `"$Here\emrion_ngx_dlssg.cpp`" " +
		"&& link /nologo /DLL /OUT:`"$Out\emrion_ngx.dll`" emrion_ngx.obj emrion_ngx_dlssg.obj " +
		"`"$Work\dlss\lib\Windows_x86_64\x64\nvsdk_ngx_s.lib`" advapi32.lib user32.lib ole32.lib shell32.lib version.lib"
	cmd /c $cmd
	if ($LASTEXITCODE) { throw 'building the shim failed' }
	Copy-Item "$Work\dlss\lib\Windows_x86_64\rel\nvngx_dlss.dll", "$Work\dlss\lib\Windows_x86_64\rel\nvngx_dlssg.dll", "$Work\dlss\lib\Windows_x86_64\rel\nvngx_dlssd.dll" $Out
	Copy-Item "$Work\dlss\LICENSE.txt" (Join-Path $Out 'NVIDIA-RTX-SDK-LICENSE.txt')
	Write-Host "Installed DLSS into $Out"
} finally {
	Remove-Item -Recurse -Force $Work -ErrorAction SilentlyContinue
}
