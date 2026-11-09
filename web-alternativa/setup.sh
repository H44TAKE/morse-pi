#!/usr/bin/env bash
# Rode na Raspberry Pi:  bash setup.sh
set -e
sudo apt update
sudo apt install -y python3-venv python3-pip libportaudio2 alsa-utils openssl unzip wget libatlas-base-dev
python3 -m venv .venv
. .venv/bin/activate
pip install flask numpy gpiozero sounddevice vosk

# Modelo de voz pt-BR pequeno (~30 MB, offline)
if [ ! -d model ]; then
  wget -q https://alphacephei.com/vosk/models/vosk-model-small-pt-0.3.zip
  unzip -q vosk-model-small-pt-0.3.zip
  mv vosk-model-small-pt-0.3 model
  rm vosk-model-small-pt-0.3.zip
fi

# Certificado HTTPS (necessário para o celular liberar a lanterna)
[ -f cert.pem ] || openssl req -x509 -newkey rsa:2048 -nodes -days 365 \
  -keyout key.pem -out cert.pem -subj "/CN=morsepi"

echo "Pronto. Rode:  . .venv/bin/activate && python app.py"
