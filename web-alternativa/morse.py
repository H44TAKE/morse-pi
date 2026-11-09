"""Conversão texto <-> código Morse e linha do tempo de pulsos."""
import re
import unicodedata

MORSE = {
    "A": ".-", "B": "-...", "C": "-.-.", "D": "-..", "E": ".", "F": "..-.",
    "G": "--.", "H": "....", "I": "..", "J": ".---", "K": "-.-", "L": ".-..",
    "M": "--", "N": "-.", "O": "---", "P": ".--.", "Q": "--.-", "R": ".-.",
    "S": "...", "T": "-", "U": "..-", "V": "...-", "W": ".--", "X": "-..-",
    "Y": "-.--", "Z": "--..",
    "0": "-----", "1": ".----", "2": "..---", "3": "...--", "4": "....-",
    "5": ".....", "6": "-....", "7": "--...", "8": "---..", "9": "----.",
    ".": ".-.-.-", ",": "--..--", "?": "..--..", "!": "-.-.--", "/": "-..-.",
    "-": "-....-", "(": "-.--.", ")": "-.--.-", ":": "---...", "@": ".--.-.",
}
REVERSE = {v: k for k, v in MORSE.items()}


def normalize(text: str) -> str:
    """Tira acentos (ç -> c, ã -> a) e passa para maiúsculas."""
    text = unicodedata.normalize("NFD", text)
    text = "".join(c for c in text if unicodedata.category(c) != "Mn")
    return text.upper()


def is_morse(text: str) -> bool:
    return bool(text.strip()) and re.fullmatch(r"[.\-/ _•–]+", text.strip()) is not None


def text_to_morse(text: str) -> str:
    """'oi mundo' -> '--- ..  -- ..- -. -.. ---' (letras separadas por espaço, palavras por ' / ')."""
    words = []
    for word in normalize(text).split():
        letters = [MORSE[c] for c in word if c in MORSE]
        if letters:
            words.append(" ".join(letters))
    return " / ".join(words)


def morse_to_text(code: str) -> str:
    code = code.replace("•", ".").replace("–", "-").replace("_", "-")
    words = re.split(r"\s*/\s*|\s{3,}", code.strip())
    out = []
    for w in words:
        out.append("".join(REVERSE.get(sym, "?") for sym in w.split()))
    return " ".join(out)


def timeline(morse: str, unit_ms: int = 120):
    """Lista de [ligado(1/0), duração_ms]. ponto=1u, traço=3u, entre símbolos=1u,
    entre letras=3u, entre palavras=7u."""
    events = []
    words = morse.split(" / ")
    for wi, word in enumerate(words):
        letters = word.split()
        for li, letter in enumerate(letters):
            for si, sym in enumerate(letter):
                events.append([1, unit_ms * (3 if sym == "-" else 1)])
                if si < len(letter) - 1:
                    events.append([0, unit_ms])
            if li < len(letters) - 1:
                events.append([0, unit_ms * 3])
        if wi < len(words) - 1:
            events.append([0, unit_ms * 7])
    return events


if __name__ == "__main__":
    m = text_to_morse("SOS Física!")
    print(m, "->", morse_to_text(m))
