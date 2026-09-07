"""Экспорт DINOv2 в ONNX для запуска на телефоне.

Модель отдаёт нормированный вектор CLS-токена — ровно то же, что считает
build_index.py, поэтому база эмбеддингов остаётся совместимой.

Запуск:  .venv/bin/python android/export/export_onnx.py
"""
import sys
from pathlib import Path

import numpy as np
import torch
from transformers import AutoImageProcessor, AutoModel

import os
MODEL_NAME = os.getenv('DINO_MODEL', 'facebook/dinov2-base')
OUT = Path(__file__).parent / (MODEL_NAME.split('/')[-1].replace('-', '_') + '.onnx')
SIZE = 224


class Encoder(torch.nn.Module):
    """DINOv2 + нормировка вектора внутри графа: на телефоне меньше кода."""

    def __init__(self, backbone):
        super().__init__()
        self.backbone = backbone

    def forward(self, pixel_values):
        hidden = self.backbone(pixel_values=pixel_values).last_hidden_state
        cls = hidden[:, 0]
        return cls / (cls.norm(dim=1, keepdim=True) + 1e-9)


def main():
    print(f'загружаю {MODEL_NAME} …')
    processor = AutoImageProcessor.from_pretrained(MODEL_NAME)
    model = Encoder(AutoModel.from_pretrained(MODEL_NAME)).eval()

    print('параметры предобработки (нужны в приложении):')
    print(f'  размер:  {SIZE}x{SIZE}')
    print(f'  mean:    {processor.image_mean}')
    print(f'  std:     {processor.image_std}')

    dummy = torch.randn(1, 3, SIZE, SIZE)
    with torch.no_grad():
        reference = model(dummy).numpy()

    torch.onnx.export(
        model, dummy, str(OUT),
        input_names=['pixel_values'], output_names=['embedding'],
        dynamic_axes={'pixel_values': {0: 'batch'}, 'embedding': {0: 'batch'}},
        opset_version=17, do_constant_folding=True)

    size_mb = OUT.stat().st_size / 1024 / 1024
    print(f'\nсохранено: {OUT.name}  ({size_mb:.0f} МБ)')

    import onnxruntime as ort
    sess = ort.InferenceSession(str(OUT), providers=['CPUExecutionProvider'])
    got = sess.run(None, {'pixel_values': dummy.numpy()})[0]
    diff = float(np.abs(got - reference).max())
    print(f'сходимость с PyTorch: максимальное расхождение {diff:.2e}',
          '— совпадает' if diff < 1e-3 else '— РАСХОЖДЕНИЕ')


if __name__ == '__main__':
    main()
