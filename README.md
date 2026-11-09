# Morse Pi

Projeto de física (Experimento 12 – Comunicação por luz): uma Raspberry Pi 3 e dois apps Android conversam por Bluetooth (BLE).

- **Morse Pi** (`MorsePi.apk`): controle. Digita texto → Morse (lanterna, bipe e voz), tecla manual, microfone da Pi, Wi‑Fi da Pi, perguntas à IA.
- **Morse Rosto** (`MorseRosto.apk`): carinha animada com 10 emoções que fala as respostas da IA e acende os olhos no ritmo do Morse.
- **pi/**: servidor Python da Pi (BLE, Morse, microfone → Groq Whisper, IA por Groq, Wi‑Fi via nmcli).

## Atualizações
Os apps verificam a última *release* deste repositório ao abrir e mostram o botão **Atualizar**.
Para publicar uma versão nova: aumente `versionCode`/`versionName` em `android/app/build.gradle.kts` e rode `./publicar.ps1`.

A chave de assinatura fica em `chaves/` (fora do Git). Sem ela, os apps novos não conseguem atualizar os antigos — guarde uma cópia.
