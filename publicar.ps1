# Compila os dois apps e publica uma release no GitHub (os apps instalados oferecem a atualização).
$ErrorActionPreference = 'Stop'
$v = (Select-String -Path android/app/build.gradle.kts -Pattern 'versionName = "(.+)"').Matches[0].Groups[1].Value
Push-Location android; .\gradlew.bat assembleControleRelease assembleRostoRelease; Pop-Location
Copy-Item android/app/build/outputs/apk/controle/release/app-controle-release.apk MorsePi.apk -Force
Copy-Item android/app/build/outputs/apk/rosto/release/app-rosto-release.apk MorseRosto.apk -Force
git add -A; git commit -m "Versao $v" 2>$null; git push
gh release create "v$v" MorsePi.apk MorseRosto.apk --title "Versao $v" --notes "$($args -join ' ')"
