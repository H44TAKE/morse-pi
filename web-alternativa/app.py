"""Servidor Morse na Raspberry Pi 3.

- Celular abre https://<ip-da-pi>:5000 -> lanterna/som/vibração piscam em Morse.
- Pi também pisca um LED (GPIO 17) e toca o som na caixa de som.
- Microfone da Pi (opcional, Vosk offline) -> fala vira texto -> Morse.
"""
import json
import os
import queue
import subprocess
import tempfile
import threading
import time
import wave

import numpy as np
from flask import Flask, Response, jsonify, request, send_from_directory

import morse

UNIT_MS = int(os.environ.get("UNIT_MS", 120))   # 120 ms ≈ 10 palavras/min
TONE_HZ = 700
LED_PIN = int(os.environ.get("LED_PIN", 17))
VOSK_MODEL = os.environ.get("VOSK_MODEL", "model")  # pasta do modelo pt-BR

app = Flask(__name__, static_folder="static")

# ---------- LED (opcional) ----------
try:
    from gpiozero import LED
    led = LED(LED_PIN)
except Exception:
    led = None

# ---------- clientes (celulares) via SSE ----------
clients = []
clients_lock = threading.Lock()


def broadcast(obj):
    with clients_lock:
        for q in list(clients):
            q.put(obj)


@app.route("/events")
def events():
    q = queue.Queue()
    with clients_lock:
        clients.append(q)

    def gen():
        try:
            yield "retry: 1000\n\n"
            while True:
                try:
                    yield f"data: {json.dumps(q.get(timeout=15))}\n\n"
                except queue.Empty:
                    yield ": ping\n\n"
        finally:
            with clients_lock:
                clients.remove(q)

    return Response(gen(), mimetype="text/event-stream")


# ---------- transmissão ----------
tx_queue = queue.Queue()
transmitting = threading.Event()


def make_wav(tl, path):
    rate = 22050
    parts = []
    for on, ms in tl:
        n = int(rate * ms / 1000)
        if on:
            t = np.arange(n) / rate
            s = 0.6 * np.sin(2 * np.pi * TONE_HZ * t)
            fade = min(200, n // 4)           # evita "clique" no começo/fim
            s[:fade] *= np.linspace(0, 1, fade)
            s[-fade:] *= np.linspace(1, 0, fade)
        else:
            s = np.zeros(n)
        parts.append(s)
    data = (np.concatenate(parts) * 32767).astype(np.int16)
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(data.tobytes())


def worker():
    while True:
        text = tx_queue.get()
        if morse.is_morse(text):
            code, plain = text.strip(), morse.morse_to_text(text)
        else:
            code, plain = morse.text_to_morse(text), text
        tl = morse.timeline(code, UNIT_MS)
        if not tl:
            continue
        transmitting.set()
        broadcast({"text": plain, "morse": code, "timeline": tl})
        wav = os.path.join(tempfile.gettempdir(), "morse.wav")
        make_wav(tl, wav)
        player = subprocess.Popen(["aplay", "-q", wav])
        for on, ms in tl:                     # LED no mesmo ritmo do som
            if led:
                led.on() if on else led.off()
            time.sleep(ms / 1000)
        if led:
            led.off()
        player.wait()
        time.sleep(0.5)                       # deixa o eco da caixa morrer
        transmitting.clear()


threading.Thread(target=worker, daemon=True).start()


@app.route("/send", methods=["POST"])
def send():
    text = (request.json or {}).get("text", "").strip()
    if not text:
        return jsonify(ok=False), 400
    tx_queue.put(text)
    return jsonify(ok=True)


# ---------- microfone -> texto (Vosk) ----------
listening = threading.Event()


def listen_loop():
    import sounddevice as sd
    from vosk import KaldiRecognizer, Model

    rec = KaldiRecognizer(Model(VOSK_MODEL), 16000)
    audio = queue.Queue()

    def cb(indata, frames, t, status):
        if not transmitting.is_set():         # ignora o que a própria caixa toca
            audio.put(bytes(indata))

    with sd.RawInputStream(samplerate=16000, blocksize=4000, dtype="int16",
                           channels=1, callback=cb):
        while listening.is_set():
            try:
                data = audio.get(timeout=1)
            except queue.Empty:
                continue
            if rec.AcceptWaveform(data):
                text = json.loads(rec.Result()).get("text", "").strip()
                if text:
                    broadcast({"heard": text})
                    tx_queue.put(text)


@app.route("/listen", methods=["POST"])
def listen():
    on = bool((request.json or {}).get("on"))
    if on and not listening.is_set():
        if not os.path.isdir(VOSK_MODEL):
            return jsonify(ok=False, error="Modelo Vosk não encontrado"), 500
        listening.set()
        threading.Thread(target=listen_loop, daemon=True).start()
    elif not on:
        listening.clear()
    return jsonify(ok=True, listening=listening.is_set())


@app.route("/")
def index():
    return send_from_directory("static", "index.html")


if __name__ == "__main__":
    ctx = ("cert.pem", "key.pem") if os.path.exists("cert.pem") else None
    app.run(host="0.0.0.0", port=5000, ssl_context=ctx, threaded=True)
