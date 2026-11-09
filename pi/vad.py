"""Detecção de fala (copiado do Atlas, atlas/audio.py)."""
RATE = 16000


FLOOR_CAP = 1500.0  # ruído de fundo aprendido nunca passa disso (RMS, escala de 16 bits)


def collect_utterance(chunks, max_s: float = 12.0, wait_s: float = 6.0, silence_s: float = 0.9,
                      min_rms: float = 300.0, chunk: int = 1280):
    """Junta os pedaços de áudio de UMA fala: começa quando alguém fala e termina quando para.

    `chunks` é um iterador de arrays int16 (já com ganho) de `chunk` amostras. Aprende o ruído de
    fundo antes da fala (percentil 20) e usa como referência, então funciona em sala barulhenta.
    Devolve um array int16, ou None se ninguém falou em `wait_s` segundos.
    """
    import numpy as np
    from collections import deque

    secs = chunk / RATE
    pre: deque = deque(maxlen=8)        # ~0,6 s antes do começo, para não cortar a primeira sílaba
    floor_win: deque = deque(maxlen=25)  # ~2 s de volumes recentes (só enquanto ainda ninguém falou)
    out: list = []
    speech_rms: list = []
    started = False
    hot = quiet = 0
    elapsed = 0.0
    for a in chunks:
        elapsed += secs
        rms = float(np.sqrt(np.mean(a.astype(np.float32) ** 2)))
        # Ruído de fundo aprendido antes da fala. O teto evita que uma fala que já começou
        # (a pessoa fala logo após a palavra de ativação) seja confundida com ruído.
        floor = min(float(np.percentile(floor_win, 20)) if floor_win else rms, FLOOR_CAP)
        if not started:
            hot = hot + 1 if rms > max(min_rms, floor * 2.5) else 0
            if hot >= 2:  # 2 pedaços seguidos: ignora estalos
                started = True
                out.extend(pre)
                out.append(a)
                continue
            floor_win.append(rms)
            pre.append(a)
            if elapsed > wait_s:
                return None
        else:
            out.append(a)
            speech_rms.append(rms)
            # Limite de "silêncio": o ruído de fundo (x2,5), mas nunca acima de metade do volume forte da
            # própria fala (o fundo aprendido pode estar errado, ex.: a pessoa já começou falando), e nunca
            # abaixo de 12% dessa fala. Assim funciona em sala quieta e em sala barulhenta.
            ref = float(np.percentile(speech_rms, 90))
            end_thr = max(min_rms * 0.7, ref * 0.12, min(floor * 2.5, ref * 0.5))
            quiet = quiet + 1 if rms < end_thr else 0
            if quiet * secs >= silence_s:
                del out[len(out) - max(0, quiet - 4):]  # tira o silêncio final (deixa ~0,3 s)
                break
            if len(out) * secs >= max_s:
                break
    if not started:
        return None
    return np.concatenate(out)
