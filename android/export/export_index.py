"""Перевод index.npz в файлы, читаемые на Android.

    index.bin   — векторы подряд, float32 little-endian, N × dim
    index.json  — название товара и миниатюра для каждого вектора

Миниатюры делает export_thumbs.py; ссылка на них имеет вид "a/<n>.jpg",
где префикс означает «лежит в assets». Снимки, добавленные на телефоне,
получают префикс "f/" и хранятся в памяти приложения.

Запуск:  .venv/bin/python android/export/export_index.py
"""
import json
from pathlib import Path

import numpy as np

HERE = Path(__file__).parent
ROOT = HERE.parent.parent
ASSETS = HERE.parent / 'app' / 'src' / 'main' / 'assets'


def main():
    data = np.load(ROOT / 'index.npz', allow_pickle=False)
    vectors = data['vectors'].astype('float32')
    labels = data['labels'].tolist()

    ASSETS.mkdir(parents=True, exist_ok=True)
    (ASSETS / 'index.bin').write_bytes(vectors.tobytes(order='C'))

    have_thumbs = (ASSETS / 'thumbs').is_dir()
    meta = {
        'dim': int(vectors.shape[1]),
        'count': int(vectors.shape[0]),
        'labels': labels,
        'thumbs': [f'a/{n}.jpg' if have_thumbs else '' for n in range(len(labels))],
    }
    (ASSETS / 'index.json').write_text(json.dumps(meta, ensure_ascii=False), encoding='utf-8')

    print(f'векторов: {meta["count"]}, товаров: {len(set(labels))}, '
          f'размерность: {meta["dim"]}')
    print(f'index.bin  {(ASSETS / "index.bin").stat().st_size / 1024 / 1024:.1f} МБ')
    print(f'index.json {(ASSETS / "index.json").stat().st_size / 1024:.0f} КБ')
    print('миниатюры: ' + ('есть' if have_thumbs else 'нет, запустите export_thumbs.py'))


if __name__ == '__main__':
    main()
