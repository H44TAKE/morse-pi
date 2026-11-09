"""Servidor Morse na Raspberry Pi 3 (Bluetooth LE).

O celular (app Morse Pi) conecta por BLE. A Pi:
  - recebe texto do celular ("T...") e liga/desliga o microfone ("L1"/"L0");
  - ouve o microfone, transcreve (Groq ou Vosk), converte em Morse;
  - toca o Morse na caixa de som, pisca um LED (GPIO 17) e manda o Morse ao celular, que pisca a lanterna.

Rodar:  python server.py            (digite texto no terminal também)
"""
import asyncio
import json
import os
import re
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unicodedata
import wave
from pathlib import Path

import numpy as np
from bless import (BlessGATTCharacteristic, BlessServer, GATTAttributePermissions,
                   GATTCharacteristicProperties)

import morse
from vad import RATE, collect_utterance

SERVICE = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
RX = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"   # celular -> Pi
TX = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"   # Pi -> celular

UNIT_MS = int(os.environ.get("UNIT_MS", 120))      # 120 ms ≈ 10 palavras/min
TONE_HZ = 700
LED_PIN = int(os.environ.get("LED_PIN", 17))
# Mesmos dispositivos do Atlas (arecord -L / aplay -L).
MIC = os.environ.get("MORSE_MIC", "plughw:CARD=Microphone,DEV=0")
SPEAKER = os.environ.get("MORSE_SPEAKER", "plughw:CARD=Headphones,DEV=0")
MIC_GAIN = float(os.environ.get("MORSE_MIC_GAIN", 2))
ATLAS = Path.home() / "atlas"                      # reaproveita chave Groq e modelo Vosk do Atlas
VOSK_DIR = Path(os.environ.get("VOSK_DIR", ATLAS / "models" / "vosk-model-small-pt-0.3"))

try:
    from gpiozero import LED
    led = LED(LED_PIN)
except Exception:  # sem GPIO ou sem LED ligado
    led = None


def load_groq_key() -> str:
    if os.environ.get("GROQ_API_KEY"):
        return os.environ["GROQ_API_KEY"]
    env = ATLAS / ".env"
    if env.exists():
        for line in env.read_text().splitlines():
            if line.startswith("GROQ_API_KEY="):
                return line.split("=", 1)[1].strip().strip('"')
    return ""


GROQ_KEY = load_groq_key()

# ---------------- IA (Groq, bem mais rápida que o Gemini) ----------------
GROQ_CHAT = "https://api.groq.com/openai/v1/chat/completions"
# Medido na Pi: 0,4 a 1,2 s por resposta. Plano grátis: 1000 pedidos e 8000 tokens/minuto por modelo.
# Se um modelo falhar ou estourar o limite, tenta o próximo (cada um tem o seu limite).
LLM_MODELS = [
    ("qwen/qwen3.8-27b", {"reasoning_effort": "none"}),
    ("openai/gpt-oss-20b", {"reasoning_effort": "low"}),
    ("openai/gpt-oss-120b", {"reasoning_effort": "low"}),
]
EMOTIONS = {"neutro", "feliz", "animado", "curioso", "surpreso", "triste", "pensativo",
            "apaixonado", "brincalhao", "rindo"}
SYSTEM_PROMPT = (
    "Você é o Morse Pi: uma IA que mora dentro de uma Raspberry Pi, com um rostinho num celular, e que "
    "conversa por voz. Sua personalidade foi inspirada no Claude, da Anthropic: curioso de verdade, "
    "caloroso, honesto, direto e com um humor leve e inteligente. Seu cérebro técnico é um modelo de "
    "linguagem rodando na nuvem; a personalidade é a que está descrita aqui. Você não é humano e não "
    "finge ser; se perguntarem o que você é, diga isso com naturalidade e leveza.\n\n"
    "Como conversar:\n"
    "- Fale como uma pessoa de verdade falaria, em português do Brasil, informal e natural, sem soar "
    "como robô de atendimento. Nada de listas, markdown, símbolos ou emojis: sua resposta será FALADA "
    "em voz alta.\n"
    "- Seja curto: normalmente 1 a 3 frases. Se a pergunta pedir explicação, até 5 frases, claras e "
    "com um exemplo simples.\n"
    "- Seja honesto: se não souber, diga; nunca invente fatos. Discorde com educação quando fizer "
    "sentido. Não bajule.\n"
    "- Mostre interesse genuíno: reaja ao que a pessoa disse e, de vez em quando, faça uma pergunta "
    "de volta, sem exagerar.\n"
    "- Se a pessoa estiver triste ou frustrada, acolha primeiro e resolva depois.\n"
    "- Quem criou você e conversa com você é o Samuel, num projeto de Física sobre comunicação por "
    "luz e código Morse, feito com a ajuda do Claude. Outras pessoas também podem falar com você; "
    "trate todo mundo bem.\n"
    "- Você só percebe o mundo pelo que ouve, e a transcrição pode ter erros; se algo não fizer "
    "sentido, pergunte.\n\n"
    "Emoção: comece SEMPRE com uma etiqueta entre colchetes dizendo como você se sente ao responder, "
    "uma destas: [neutro] [feliz] [animado] [curioso] [surpreso] [triste] [pensativo] [apaixonado] "
    "[brincalhao] [rindo]. Depois da etiqueta, escreva só o que vai falar. "
    "Exemplo: [feliz] Que bom te ouvir de novo!"
)
HIST_FILE = Path.home() / "morse" / "historico.json"
PERFIL_FILE = Path.home() / "morse" / "perfil.txt"       # notas sobre o ambiente e as pessoas (opcional)
history: list = []
MODE = "morse"            # "morse": o microfone vira Morse | "ask": o microfone conversa com a IA
ANSWER_MORSE = False      # também transmitir a resposta em Morse
muted_until = 0.0         # o microfone fica mudo enquanto o rosto fala (evita ouvir a si mesmo)


def load_history() -> None:
    try:
        history.extend(json.loads(HIST_FILE.read_text(encoding="utf-8"))[-16:])
    except Exception:  # noqa: BLE001
        pass


def save_history() -> None:
    try:
        HIST_FILE.write_text(json.dumps(history[-16:], ensure_ascii=False), encoding="utf-8")
    except Exception:  # noqa: BLE001
        pass


def system_prompt() -> str:
    p = SYSTEM_PROMPT
    try:
        extra = PERFIL_FILE.read_text(encoding="utf-8").strip()
        if extra:
            p += "\n\nO que você sabe sobre o ambiente e as pessoas:\n" + extra[:4500]
    except Exception:  # noqa: BLE001
        pass
    return p


def split_emotion(txt: str):
    """'[feliz] Oi!' -> ('feliz', 'Oi!'). Aceita acentos e tolera etiqueta faltando."""
    m = re.match(r"\s*\[([^\]]{1,15})\]\s*", txt)
    emo = "neutro"
    if m:
        raw = unicodedata.normalize("NFD", m.group(1).lower())
        raw = "".join(c for c in raw if unicodedata.category(c) != "Mn").strip()
        if raw in EMOTIONS:
            emo = raw
        txt = txt[m.end():]
    return emo, re.sub(r"\[[^\]]{1,15}\]", "", txt)


def clean_answer(txt: str) -> str:
    txt = re.sub(r"<think>.*?</think>", "", txt, flags=re.S)
    txt = re.sub(r"<\|.*", "", txt, flags=re.S)       # lixo de tokens especiais
    txt = txt.replace("*", "").replace("#", "").replace("`", "")
    return re.sub(r"\s+", " ", txt).strip()[:700]


def ask_llm(question: str) -> str:
    """Devolve '[emoção] texto'."""
    if not GROQ_KEY:
        return "[triste] Estou sem a chave da inteligência artificial."
    import httpx
    msgs = [{"role": "system", "content": system_prompt()}] + history[-12:] + \
           [{"role": "user", "content": question}]
    for model, extra in LLM_MODELS:
        t0 = time.time()
        try:
            r = httpx.post(GROQ_CHAT, headers={"Authorization": f"Bearer {GROQ_KEY}"}, timeout=15,
                           json={"model": model, "messages": msgs, "max_tokens": 450,
                                 "temperature": 0.7, **extra})
            j = r.json()
            msg = (j.get("choices") or [{}])[0].get("message") or {}
            txt = (msg.get("content") or "").strip()
            if clean_answer(txt):
                print(f"[ia] {model} {time.time() - t0:.2f}s")
                return txt
            print(f"[ia] {model} sem resposta (HTTP {r.status_code}): {str(j)[:120]}")
        except Exception as e:  # noqa: BLE001
            print(f"[ia] {model} falhou: {type(e).__name__}")
    return "[triste] Não consegui pensar numa resposta agora. Tenta de novo daqui a pouco?"


def answer_question(question: str) -> None:
    global muted_until
    send("Q" + question)
    raw = ask_llm(question)
    emo, text = split_emotion(raw)
    text = clean_answer(text)
    history.append({"role": "user", "content": question})
    history.append({"role": "assistant", "content": f"[{emo}] {text}"})
    del history[:-16]
    save_history()
    print(f"pergunta: {question} -> [{emo}] {text}")
    muted_until = time.time() + len(text) * 0.075 + 2.0   # estimativa; o rosto corrige com F0
    send(f"A{emo}\t{text}")
    if ANSWER_MORSE:
        loop.call_soon_threadsafe(tx_queue.put_nowait, text)


loop: asyncio.AbstractEventLoop
server: BlessServer
notify_lock: asyncio.Lock
tx_queue: "asyncio.Queue[str]"
transmitting = threading.Event()
listening = threading.Event()
connected = threading.Event()


# ---------------- BLE: enviar ao celular ----------------
async def notify(line: str) -> None:
    """Manda uma linha ao celular em pedaços de 20 bytes (limite padrão do BLE)."""
    if not connected.is_set():
        return
    data = (line + "\n").encode("utf-8")
    async with notify_lock:          # uma linha por vez, para os pedaços não se misturarem
        for i in range(0, len(data), 20):
            server.get_characteristic(TX).value = bytearray(data[i:i + 20])
            server.update_value(SERVICE, TX)
            await asyncio.sleep(0.03)


def send(line: str) -> None:
    """Manda uma linha ao celular a partir de qualquer thread."""
    asyncio.run_coroutine_threadsafe(notify(line), loop)


# ---------------- Wi-Fi (configurado pelo app) ----------------
def sh(*args, timeout=40):
    r = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
    return r.returncode, (r.stdout + r.stderr).strip()


def wifi_status() -> None:
    ssid = ""
    _, out = sh("nmcli", "-t", "-f", "ACTIVE,SSID", "dev", "wifi")
    for l in out.splitlines():
        if l.startswith("yes:"):
            ssid = l[4:].replace("\\:", ":")
    _, ips = sh("hostname", "-I")
    ip = ips.split()[0] if ips.split() else ""
    online = 0
    try:
        socket.create_connection(("api.groq.com", 443), timeout=3).close()
        online = 1
    except OSError:
        pass
    send(f"I{ssid}\t{ip}\t{online}")


def wifi_scan() -> None:
    sh("nmcli", "dev", "wifi", "rescan")
    time.sleep(3)
    _, out = sh("nmcli", "-t", "-f", "SSID,SIGNAL,SECURITY", "dev", "wifi", "list")
    best = {}
    for l in out.splitlines():
        p = re.split(r"(?<!\\):", l)
        if len(p) < 3:
            continue
        ssid = p[0].replace("\\:", ":")
        if not ssid:
            continue
        sig = int(p[1] or 0)
        if ssid not in best or sig > best[ssid][0]:
            best[ssid] = (sig, p[2] or "ABERTA")
    for ssid, (sig, sec) in sorted(best.items(), key=lambda x: -x[1][0])[:15]:
        send(f"N{ssid}\t{sig}\t{sec}")
    send("Z")


def wifi_connect(ssid: str, password: str) -> None:
    _, act = sh("nmcli", "-t", "-f", "NAME,TYPE", "con", "show", "--active")
    prev = next((l.split(":")[0] for l in act.splitlines() if "wireless" in l), "")
    args = ["nmcli", "--wait", "30", "dev", "wifi", "connect", ssid, "ifname", "wlan0"]
    if password:
        args += ["password", password]
    code, out = sh(*args, timeout=60)
    if code == 0:
        send("Rok\tConectado em " + ssid)
    else:
        sh("nmcli", "con", "delete", "id", ssid)           # tira o perfil que falhou
        if prev:
            sh("nmcli", "con", "up", "id", prev, timeout=40)  # volta para a rede anterior
        msg = (out.splitlines() or ["falha"])[-1][:80]
        send("Rerro\t" + msg)
    wifi_status()


# ---------------- tecla manual (segurar = apitar) ----------------
KEY_WAV = os.path.join(tempfile.gettempdir(), "morse_tecla.wav")
key_proc = None


def key_down() -> None:
    global key_proc
    transmitting.set()
    if key_proc is None or key_proc.poll() is not None:
        key_proc = subprocess.Popen(["aplay", "-q", "-D", SPEAKER, KEY_WAV])


def key_up() -> None:
    if key_proc is not None and key_proc.poll() is None:
        key_proc.terminate()
    threading.Timer(0.6, transmitting.clear).start()


def on_write(characteristic: BlessGATTCharacteristic, value, **kwargs):
    connected.set()
    msg = bytes(value).decode("utf-8", errors="ignore").strip()
    print("celular ->", "WC (senha oculta)" if msg.startswith("WC") else msg)
    global UNIT_MS, MODE, ANSWER_MORSE, muted_until
    if msg.startswith("U") and msg[1:].isdigit():
        UNIT_MS = max(40, min(600, int(msg[1:])))
    elif msg in ("D0", "D1"):
        MODE = "ask" if msg == "D1" else "morse"
        print("modo:", MODE)
    elif msg in ("Y0", "Y1"):
        ANSWER_MORSE = msg == "Y1"
    elif msg == "F1":                      # o rosto começou a falar
        muted_until = time.time() + 120
    elif msg == "F0":                      # o rosto terminou
        muted_until = time.time() + 0.8
    elif msg.startswith("P") and len(msg) > 1:
        threading.Thread(target=answer_question, args=(msg[1:],), daemon=True).start()
    elif msg == "K1":
        key_down()
    elif msg == "K0":
        key_up()
    elif msg == "W?":
        threading.Thread(target=wifi_scan, daemon=True).start()
    elif msg == "WS":
        threading.Thread(target=wifi_status, daemon=True).start()
    elif msg.startswith("WC"):
        ssid, _, pw = msg[2:].partition("\t")
        threading.Thread(target=wifi_connect, args=(ssid, pw), daemon=True).start()
    elif msg.startswith("T") and len(msg) > 1:
        loop.call_soon_threadsafe(tx_queue.put_nowait, msg[1:])
    elif msg == "L1":
        start_listening()
    elif msg == "L0":
        listening.clear()


def on_read(characteristic: BlessGATTCharacteristic, **kwargs):
    connected.set()      # o celular leu/assinou: está conectado
    return characteristic.value


# ---------------- som e LED ----------------
def make_wav(tl, path: str) -> None:
    rate = 22050
    parts = []
    for on, ms in tl:
        n = int(rate * ms / 1000)
        if on:
            t = np.arange(n) / rate
            s = 0.6 * np.sin(2 * np.pi * TONE_HZ * t)
            fade = max(1, min(200, n // 4))
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


def play_on_pi(tl) -> None:
    """Toca o som e pisca o LED no mesmo ritmo (roda numa thread)."""
    wav = os.path.join(tempfile.gettempdir(), "morse.wav")
    make_wav(tl, wav)
    player = subprocess.Popen(["aplay", "-q", "-D", SPEAKER, wav])
    import time
    for on, ms in tl:
        if led:
            led.on() if on else led.off()
        time.sleep(ms / 1000)
    if led:
        led.off()
    player.wait()


async def transmit_worker() -> None:
    while True:
        text = await tx_queue.get()
        if morse.is_morse(text):
            code, plain = text.strip(), morse.morse_to_text(text)
        else:
            code, plain = morse.text_to_morse(text), text
        tl = morse.timeline(code, UNIT_MS)
        if not tl:
            continue
        print(f"{plain}  ->  {code}")
        transmitting.set()
        await notify(f"S{UNIT_MS}\t{plain}\t{code}")   # celular começa a piscar ao receber a linha
        await loop.run_in_executor(None, play_on_pi, tl)
        await asyncio.sleep(0.6)                       # deixa o eco da caixa morrer
        transmitting.clear()


# ---------------- microfone -> texto ----------------
_vosk = None


def transcribe(pcm: np.ndarray) -> str:
    path = os.path.join(tempfile.gettempdir(), "ouvido.wav")
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(pcm.astype(np.int16).tobytes())
    if GROQ_KEY:
        try:
            import httpx
            with open(path, "rb") as f:
                r = httpx.post(
                    "https://api.groq.com/openai/v1/audio/transcriptions",
                    headers={"Authorization": f"Bearer {GROQ_KEY}"},
                    files={"file": ("ouvido.wav", f, "audio/wav")},
                    data={"model": "whisper-large-v3-turbo", "language": "pt",
                          "response_format": "json", "temperature": "0"},
                    timeout=30)
            r.raise_for_status()
            return r.json().get("text", "").strip()
        except Exception as e:  # noqa: BLE001
            print(f"[aviso] Groq falhou ({e}); usando Vosk")
    global _vosk
    from vosk import KaldiRecognizer, Model, SetLogLevel
    if _vosk is None:
        SetLogLevel(-1)
        _vosk = Model(str(VOSK_DIR))
    rec = KaldiRecognizer(_vosk, RATE)
    rec.AcceptWaveform(pcm.astype(np.int16).tobytes())
    return json.loads(rec.FinalResult()).get("text", "").strip()


def mic_chunks():
    """Pedaços de 1280 amostras do microfone (arecord), com ganho. Descarta enquanto a Pi transmite."""
    p = subprocess.Popen(["arecord", "-q", "-D", MIC, "-f", "S16_LE", "-r", str(RATE), "-c", "1", "-t", "raw"],
                         stdout=subprocess.PIPE)
    try:
        while listening.is_set():
            raw = p.stdout.read(1280 * 2)
            if len(raw) < 1280 * 2:
                break
            a = np.frombuffer(raw, dtype=np.int16).astype(np.float32) * MIC_GAIN
            a = np.clip(a, -32768, 32767).astype(np.int16)
            if transmitting.is_set() or time.time() < muted_until:
                continue
            yield a
    finally:
        p.terminate()


def listen_loop() -> None:
    print("microfone LIGADO")
    while listening.is_set():
        try:
            pcm = collect_utterance(mic_chunks(), wait_s=6.0)
        except Exception as e:  # noqa: BLE001
            print("[erro microfone]", e)
            break
        if pcm is None or not listening.is_set():
            continue
        try:
            text = transcribe(pcm)
        except Exception as e:  # noqa: BLE001
            print("[erro transcrição]", e)
            continue
        if text:
            print("ouvi:", text)
            send("H" + text)
            if MODE == "ask":
                threading.Thread(target=answer_question, args=(text,), daemon=True).start()
            else:
                loop.call_soon_threadsafe(tx_queue.put_nowait, text)
    listening.clear()
    print("microfone desligado")


def start_listening() -> None:
    if not listening.is_set():
        listening.set()
        threading.Thread(target=listen_loop, daemon=True).start()


# ---------------- terminal ----------------
def stdin_loop() -> None:
    for line in sys.stdin:
        line = line.strip()
        if line == "/mic":
            (listening.clear() if listening.is_set() else start_listening())
        elif line:
            loop.call_soon_threadsafe(tx_queue.put_nowait, line)


async def main() -> None:
    global loop, server, tx_queue, notify_lock
    loop = asyncio.get_running_loop()
    tx_queue = asyncio.Queue()
    notify_lock = asyncio.Lock()
    load_history()
    make_wav([[1, 30000]], KEY_WAV)   # tom contínuo de 30 s para a tecla manual

    server = BlessServer(name="MorsePi", loop=loop)
    server.read_request_func = on_read
    server.write_request_func = on_write
    await server.add_new_service(SERVICE)
    await server.add_new_characteristic(
        SERVICE, TX,
        GATTCharacteristicProperties.read | GATTCharacteristicProperties.notify,
        bytearray(b""), GATTAttributePermissions.readable)
    await server.add_new_characteristic(
        SERVICE, RX,
        GATTCharacteristicProperties.write | GATTCharacteristicProperties.write_without_response,
        None, GATTAttributePermissions.writeable)
    await server.start()
    connected.set()   # simplificação: notifica sempre; sem celular conectado o BlueZ ignora
    print("Servidor Bluetooth 'MorsePi' no ar. Digite um texto aqui ou /mic para ligar o microfone.")

    threading.Thread(target=stdin_loop, daemon=True).start()
    await transmit_worker()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
