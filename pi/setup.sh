#!/usr/bin/env bash
# Na Pi, dentro da pasta ~/morse:   bash setup.sh
# Não apaga o Atlas: este projeto reaproveita a chave Groq e o modelo Vosk de ~/atlas.
set -e
python3 -m venv .venv
. .venv/bin/activate
pip install -q numpy bless httpx vosk gpiozero
echo "Pronto. Rode:  . .venv/bin/activate && python server.py"
echo "Antes, pare o Atlas se estiver rodando (usa o mesmo microfone):  sudo systemctl stop atlas"
