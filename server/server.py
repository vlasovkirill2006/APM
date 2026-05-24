from flask import Flask, request
import io
import base64
import requests as http_requests
from PIL import Image

app = Flask(__name__)

OLLAMA_URL = "http://localhost:11434/api/generate"
OLLAMA_MODEL = "llava-llama3"


def ocr_with_ollama(img: Image.Image) -> str:
    img.thumbnail((1280, 1280), Image.LANCZOS)
    buf = io.BytesIO()
    img.save(buf, format="JPEG", quality=95)
    img_b64 = base64.b64encode(buf.getvalue()).decode("utf-8")

    payload = {
        "model": OLLAMA_MODEL,
        "prompt": (
            "Read all text visible in this image. "
            "Output ONLY the text you see, in Russian if it is Russian, in the original language otherwise. "
            "No explanations, no comments, just the text. "
            "If there is no text, respond: Текст не найден."
        ),
        "images": [img_b64],
        "stream": False,
    }
    resp = http_requests.post(OLLAMA_URL, json=payload, timeout=60)
    resp.raise_for_status()
    return resp.json().get("response", "").strip()


def caption_with_ollama(img: Image.Image) -> str:
    img.thumbnail((640, 640), Image.LANCZOS)
    buf = io.BytesIO()
    img.save(buf, format="JPEG", quality=85)
    img_b64 = base64.b64encode(buf.getvalue()).decode("utf-8")

    payload = {
        "model": OLLAMA_MODEL,
        "prompt": (
            "You are a navigation assistant for a blind person. "
            "YOUR RESPONSE MUST BE IN RUSSIAN LANGUAGE ONLY. NO ENGLISH WORDS IN THE RESPONSE. "
            "Describe the photo using ONLY this exact format with positions: "
            "'Перед вами [объект]. Слева [объект]. Справа [объект].' "
            "Only large objects: furniture, doors, windows, stairs, walls. "
            "Use only these Russian position words: перед вами, слева, справа, за. "
            "Maximum 3-4 sentences. No adjectives. No 'я вижу', no 'на фото', no 'на изображении'. "
            "EXAMPLE OF CORRECT RESPONSE: Перед вами кровать. За кроватью шкаф. Слева дверь. Справа стол со стулом. "
            "RESPOND IN RUSSIAN ONLY:"
        ),
        "images": [img_b64],
        "stream": False,
    }
    resp = http_requests.post(OLLAMA_URL, json=payload, timeout=60)
    resp.raise_for_status()
    return resp.json().get("response", "").strip()


@app.route("/test", methods=["GET", "POST"])
def test():
    return "BlindAssist server is running"


@app.route("/hello", methods=["POST"])
def hello():
    img_file = request.files.get("img_file")
    if img_file is None:
        return "No image received", 400

    mode = request.form.get("mode", "caption").lower()
    sensor_angle = int(request.form.get("SensorAngle", 0))

    try:
        img = Image.open(io.BytesIO(img_file.read())).convert("RGB")
    except Exception as e:
        return f"Cannot open image: {e}", 400

    if sensor_angle != 0:
        img = img.rotate(-sensor_angle, resample=Image.BICUBIC, expand=True)

    if mode == "ocr":
        try:
            result = ocr_with_ollama(img)
            return result if result else "Текст не найден"
        except Exception as e:
            return f"OCR error: {e}", 500
    else:
        try:
            result = caption_with_ollama(img)
            return result if result else "Не удалось описать изображение"
        except Exception as e:
            return f"Caption error: {e}", 500


if __name__ == "__main__":
    import socket
    hostname = socket.gethostname()
    try:
        local_ip = socket.gethostbyname(hostname)
    except Exception:
        local_ip = "127.0.0.1"

    # Проверяем что Ollama запущена
    try:
        http_requests.get("http://localhost:11434", timeout=3)
        print("[BlindAssist] Ollama: OK")
    except Exception:
        print("[BlindAssist] ВНИМАНИЕ: Ollama не запущена! Запусти Ollama перед стартом сервера.")

    print(f"\n[BlindAssist] Server starting...")
    print(f"[BlindAssist] Local IP: {local_ip}")
    print(f"[BlindAssist] Укажи в ServerAnalyzer.kt: http://{local_ip}:5000")
    print(f"[BlindAssist] Тест: http://{local_ip}:5000/test\n")
    app.run(host="0.0.0.0", port=5000, debug=False)
