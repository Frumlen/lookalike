"""Сжатие модели до int8 и замер, сколько это стоит в точности.

Сравнивается с базой index.npz, посчитанной исходной моделью fp32:
  * насколько уехали сами векторы,
  * меняется ли результат поиска.

Запуск:  .venv/bin/python android/export/quantize.py
"""
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort
from PIL import Image

HERE = Path(__file__).parent
ROOT = HERE.parent.parent
FP32 = HERE / 'dinov2_base.onnx'
INT8 = HERE / 'dinov2_base_int8.onnx'
PHOTOS = ROOT / 'photos'
SIZE = 224
MEAN = np.array([0.485, 0.456, 0.406], dtype='float32')
STD = np.array([0.229, 0.224, 0.225], dtype='float32')


def preprocess(path):
    """То же, что делает transformers: resize по короткой стороне, центр, нормировка."""
    img = Image.open(path).convert('RGB')
    w, h = img.size
    scale = 256 / min(w, h)
    img = img.resize((round(w * scale), round(h * scale)), Image.BICUBIC)
    w, h = img.size
    left, top = (w - SIZE) // 2, (h - SIZE) // 2
    img = img.crop((left, top, left + SIZE, top + SIZE))
    arr = np.asarray(img, dtype='float32') / 255.0
    arr = (arr - MEAN) / STD
    return arr.transpose(2, 0, 1)[None]


def quantize():
    from onnxruntime.quantization import QuantType, quantize_dynamic
    print('сжимаю до int8 …')
    # Только MatMul: в них лежит почти весь объём модели, а свёртку первого слоя
    # среда исполнения в int8 не считает.
    quantize_dynamic(str(FP32), str(INT8), weight_type=QuantType.QInt8,
                     op_types_to_quantize=['MatMul'])
    for path in (FP32, INT8):
        print(f'  {path.name:<26} {path.stat().st_size / 1024 / 1024:>6.0f} МБ')


def accuracy(vectors, labels):
    """Убрать кадр и найти его по остальным."""
    sims = vectors @ vectors.T
    np.fill_diagonal(sims, -1)
    order = np.argsort(-sims, axis=1)
    top1 = (labels[order[:, 0]] == labels).mean()
    top3 = np.mean([labels[i] in labels[order[i, :3]] for i in range(len(labels))])
    top5 = np.mean([labels[i] in labels[order[i, :5]] for i in range(len(labels))])
    return top1, top3, top5


def main():
    if not INT8.exists():
        quantize()

    base = np.load(ROOT / 'index.npz', allow_pickle=False)
    files, labels, ref = base['files'], base['labels'], base['vectors']
    print(f'\nсчитаю {len(files)} кадров сжатой моделью …')

    sess = ort.InferenceSession(str(INT8), providers=['CPUExecutionProvider'])
    got, times = [], []
    for n, rel in enumerate(files, 1):
        batch = preprocess(PHOTOS / rel)
        t0 = time.perf_counter()
        vec = sess.run(None, {'pixel_values': batch})[0][0]
        times.append(time.perf_counter() - t0)
        got.append(vec / (np.linalg.norm(vec) + 1e-9))
        if n % 200 == 0:
            print(f'  {n}/{len(files)}', flush=True)
    got = np.array(got, dtype='float32')

    drift = (got * ref).sum(axis=1)
    print(f'\nвекторы: совпадение со старыми в среднем {drift.mean():.4f}, '
          f'худший кадр {drift.min():.4f}')

    a32 = accuracy(ref, labels)
    a8 = accuracy(got, labels)
    print(f'\n{"":<12}{"TOP-1":>9}{"TOP-3":>9}{"TOP-5":>9}')
    print(f'{"fp32":<12}{a32[0]*100:>8.1f}%{a32[1]*100:>8.1f}%{a32[2]*100:>8.1f}%')
    print(f'{"int8":<12}{a8[0]*100:>8.1f}%{a8[1]*100:>8.1f}%{a8[2]*100:>8.1f}%')

    # совпадает ли верхний ответ у двух моделей
    def top1_labels(vectors):
        sims = vectors @ vectors.T
        np.fill_diagonal(sims, -1)
        return labels[np.argmax(sims, axis=1)]
    same = (top1_labels(ref) == top1_labels(got)).mean()
    print(f'\nверхний ответ совпадает у fp32 и int8: {same*100:.1f}% кадров')
    print(f'скорость int8 на этой машине: {np.median(times)*1000:.0f} мс на кадр')


if __name__ == '__main__':
    main()
