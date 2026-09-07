"""Замер модели-кодировщика на собранных фотографиях.

Считает векторы для всех кадров из photos/ и проверяет поиск способом
«убрать кадр и найти его по остальным». Печатает точность и скорость.

Запуск:  .venv/bin/python android/export/bench.py путь/к/model.onnx
"""
import sys
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort

HERE = Path(__file__).parent
ROOT = HERE.parent.parent
sys.path.insert(0, str(HERE))
from quantize import preprocess                                   # noqa: E402


def accuracy(vectors, labels):
    sims = vectors @ vectors.T
    np.fill_diagonal(sims, -1)
    order = np.argsort(-sims, axis=1)
    return (
        (labels[order[:, 0]] == labels).mean(),
        np.mean([labels[i] in labels[order[i, :3]] for i in range(len(labels))]),
        np.mean([labels[i] in labels[order[i, :5]] for i in range(len(labels))]),
    )


def main():
    model = Path(sys.argv[1])
    base = np.load(ROOT / 'index.npz', allow_pickle=False)
    files, labels = base['files'], base['labels']

    sess = ort.InferenceSession(str(model), providers=['CPUExecutionProvider'])
    print(f'{model.name}  ({model.stat().st_size / 1024 / 1024:.0f} МБ), '
          f'кадров: {len(files)}', flush=True)

    vectors, times = [], []
    for n, rel in enumerate(files, 1):
        batch = preprocess(ROOT / 'photos' / rel)
        t0 = time.perf_counter()
        vec = sess.run(None, {'pixel_values': batch})[0][0]
        times.append(time.perf_counter() - t0)
        vectors.append(vec / (np.linalg.norm(vec) + 1e-9))
        if n % 400 == 0:
            print(f'  {n}/{len(files)}', flush=True)
    vectors = np.array(vectors, dtype='float32')

    top1, top3, top5 = accuracy(vectors, labels)
    print(f'\n  размерность вектора: {vectors.shape[1]}')
    print(f'  TOP-1 {top1*100:.1f}%   TOP-3 {top3*100:.1f}%   TOP-5 {top5*100:.1f}%')
    print(f'  скорость: {np.median(times)*1000:.0f} мс на кадр')
    np.save(HERE / f'vectors_{model.stem}.npy', vectors)


if __name__ == '__main__':
    main()
