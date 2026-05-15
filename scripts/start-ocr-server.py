"""
PaddleOCR HTTP 服务启动脚本
依赖: pip install paddleocr fastapi uvicorn
启动: python scripts/start-ocr-server.py
服务地址: http://127.0.0.1:8866
"""
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from paddleocr import PaddleOCR
import base64
import io
import numpy as np
from PIL import Image
import uvicorn

app = FastAPI(title="PaddleOCR Service")

# 初始化 OCR（首次启动会自动下载模型）
ocr = PaddleOCR(use_angle_cls=True, lang='ch', show_log=False)


class OcrRequest(BaseModel):
    images: list[str]  # base64 encoded images


class OcrResultItem(BaseModel):
    text: str
    confidence: float


class OcrResponse(BaseModel):
    results: list[list[OcrResultItem]]


@app.post("/predict/ocr_system", response_model=OcrResponse)
def ocr_predict(req: OcrRequest):
    all_results = []
    for img_b64 in req.images:
        img_bytes = base64.b64decode(img_b64)
        img_array = np.array(Image.open(io.BytesIO(img_bytes)))
        result = ocr.ocr(img_array, cls=True)
        items = []
        if result and result[0]:
            for line in result[0]:
                text = line[1][0]
                confidence = line[1][1]
                items.append(OcrResultItem(text=text, confidence=confidence))
        all_results.append(items)
    return OcrResponse(results=all_results)


if __name__ == "__main__":
    print("正在启动 PaddleOCR HTTP 服务...")
    print("服务地址: http://127.0.0.1:8866")
    uvicorn.run(app, host="127.0.0.1", port=8866)
