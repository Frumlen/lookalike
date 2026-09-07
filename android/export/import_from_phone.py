"""Приём базы, выгруженной из приложения, обратно на компьютер.

Из файла достаются снимки, сделанные на телефоне, и раскладываются
по папкам photos/<название товара>/. После этого достаточно обычной
пересборки — векторы посчитаются на общих правилах:

    .venv/bin/python build_index.py build

Запуск:  .venv/bin/python android/export/import_from_phone.py база.zip
"""
import json
import re
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).parent.parent.parent
PHOTOS = ROOT / 'photos'


def safe(name):
    return re.sub(r'\s+', ' ', re.sub(r'[\\/:*?"<>|]+', ' ', name)).strip()[:80]


def main():
    archive = Path(sys.argv[1])
    with zipfile.ZipFile(archive) as zf:
        meta = json.loads(zf.read('meta.json'))
        labels = meta['labels']
        thumbs = meta.get('thumbs') or [''] * len(labels)
        print(f'в файле: {len(labels)} снимков, {len(set(labels))} товаров')

        known = {p.name for p in PHOTOS.iterdir() if p.is_dir()} if PHOTOS.exists() else set()
        added, new_products = 0, set()

        for n, (label, thumb) in enumerate(zip(labels, thumbs)):
            if not thumb:
                continue
            folder = PHOTOS / safe(label)
            # Кадры из assets уже есть на компьютере — переносим только снятые телефоном.
            # Отличаем по тому, что таких папок у нас может не быть вовсе.
            if folder.name in known and not _is_new(zf, thumb):
                continue
            folder.mkdir(parents=True, exist_ok=True)
            target = folder / f'phone_{n}.jpg'
            if target.exists():
                continue
            target.write_bytes(zf.read(f'thumbs/{thumb}'))
            added += 1
            if folder.name not in known:
                new_products.add(folder.name)

    print(f'добавлено снимков: {added}')
    if new_products:
        print(f'новых товаров: {len(new_products)}')
        for name in sorted(new_products):
            print(f'   {name}')
    print('\nтеперь пересоберите базу:  .venv/bin/python build_index.py build')


def _is_new(zf, thumb):
    """Снимок с телефона крупнее миниатюр из assets (224 против 128)."""
    return zf.getinfo(f'thumbs/{thumb}').file_size > 9000


if __name__ == '__main__':
    main()
