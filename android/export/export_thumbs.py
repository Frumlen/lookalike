"""Миниатюры эталонных снимков для показа в приложении.

Кладёт по одной картинке на каждый вектор базы, в том же порядке.
Нужны, чтобы на телефоне было видно, что именно система запомнила,
и можно было убрать неудачный кадр.

Запуск:  .venv/bin/python android/export/export_thumbs.py [размер]
"""
import sys
from pathlib import Path

import numpy as np
from PIL import Image

HERE = Path(__file__).parent
ROOT = HERE.parent.parent
THUMBS = HERE.parent / 'app' / 'src' / 'main' / 'assets' / 'thumbs'
SIZE = int(sys.argv[1]) if len(sys.argv) > 1 else 128
QUALITY = 80


def main():
    base = np.load(ROOT / 'index.npz', allow_pickle=False)
    files = base['files']

    if THUMBS.exists():
        for old in THUMBS.glob('*.jpg'):
            old.unlink()
    THUMBS.mkdir(parents=True, exist_ok=True)

    total = 0
    for n, rel in enumerate(files):
        try:
            img = Image.open(ROOT / 'photos' / rel).convert('RGB')
        except Exception as exc:
            print(f'  ! {rel}: {type(exc).__name__}')
            continue
        # Квадрат по центру — так же, как видит модель
        side = min(img.size)
        left = (img.width - side) // 2
        top = (img.height - side) // 2
        img = img.crop((left, top, left + side, top + side))
        img = img.resize((SIZE, SIZE), Image.LANCZOS)
        out = THUMBS / f'{n}.jpg'
        img.save(out, 'JPEG', quality=QUALITY)
        total += out.stat().st_size

    print(f'миниатюр: {len(list(THUMBS.glob("*.jpg")))} по {SIZE}px')
    print(f'всего: {total / 1024 / 1024:.1f} МБ')


if __name__ == '__main__':
    main()
