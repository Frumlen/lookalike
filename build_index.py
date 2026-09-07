"""Построение базы эмбеддингов по папкам с фотографиями и поиск похожего.

Обучения нет: DINOv2 берётся с готовыми весами и работает как преобразователь
«картинка → вектор». Классификация — поиск ближайших векторов в базе.

Класс = имя папки внутри photos/. Артикул не используется.

Пересобрать базу:  .venv/bin/python build_index.py build     (считает только новое)
Что в базе:        .venv/bin/python build_index.py stats
Проверить файл:    .venv/bin/python build_index.py test фото.jpg
"""
import json
import os
import sys
from pathlib import Path

import numpy as np
import torch
from PIL import Image
from transformers import AutoImageProcessor, AutoModel

BASE = Path(__file__).parent
PHOTOS = BASE / os.getenv('PHOTOS_DIR', 'photos')
INDEX = BASE / os.getenv('INDEX_FILE', 'index.npz')
MODEL_NAME = os.getenv('MODEL_NAME', 'facebook/dinov2-small')          # 86M параметров, вектор 768
EXTS = {'.jpg', '.jpeg', '.png', '.webp', '.bmp'}
MIN_SIDE = 32          # кадры мельче отбрасываются: процессор принимает их за одноканальные

_model = None
_processor = None


def load_model():
    """Модель грузится один раз и остаётся в памяти."""
    global _model, _processor
    if _model is None:
        print(f'загружаю {MODEL_NAME} …', flush=True)
        _processor = AutoImageProcessor.from_pretrained(MODEL_NAME)
        _model = AutoModel.from_pretrained(MODEL_NAME)
        _model.eval()
        torch.set_num_threads(max(1, (torch.get_num_threads() or 4)))
    return _model, _processor


@torch.no_grad()
def embed(images):
    """Список PIL-картинок → матрица нормированных векторов."""
    model, processor = load_model()
    batch = processor(images=images, return_tensors='pt')
    out = model(**batch)
    # CLS-токен — общее представление кадра
    vectors = out.last_hidden_state[:, 0].numpy()
    vectors /= np.linalg.norm(vectors, axis=1, keepdims=True) + 1e-9
    return vectors.astype('float32')


def embed_paths(paths, batch_size=8):
    vectors, kept = [], []
    for start in range(0, len(paths), batch_size):
        chunk = paths[start:start + batch_size]
        images, good = [], []
        for path in chunk:
            try:
                image = Image.open(path)
                image.load()
                if min(image.size) < MIN_SIDE:
                    print(f'  ! мал  {path.parent.name}/{path.name}: {image.size[0]}x{image.size[1]}')
                    continue
                images.append(image.convert('RGB'))
                good.append(path)
            except Exception as exc:
                print(f'  ! битый {path.parent.name}/{path.name}: {type(exc).__name__}')
        if images:
            vectors.append(embed(images))
            kept.extend(good)
        print(f'  {min(start + batch_size, len(paths))}/{len(paths)}', end='\r', flush=True)
    print(' ' * 30, end='\r')
    return (np.vstack(vectors) if vectors else np.empty((0, 384), 'float32')), kept


def file_key(path):
    """Отпечаток файла: размер и время правки. Меняется — кадр пересчитывается."""
    st = path.stat()
    return f'{st.st_size}:{int(st.st_mtime)}'


def scan():
    """Все изображения в фото_товаров: [(относительный путь, папка, отпечаток)]."""
    found = []
    for folder in sorted(p for p in PHOTOS.iterdir() if p.is_dir()):
        for path in sorted(p for p in folder.iterdir() if p.suffix.lower() in EXTS):
            found.append((str(path.relative_to(PHOTOS)), folder.name, file_key(path)))
    return found


def load_existing():
    """Готовые векторы из базы: {относительный путь: (отпечаток, вектор)}."""
    if not INDEX.exists():
        return {}, False
    data = np.load(INDEX, allow_pickle=False)
    if 'keys' not in data:
        # база собрана старой версией — отпечатков нет, считаем файлы неизменными
        return ({f: (None, v) for f, v in zip(data['files'], data['vectors'])}, True)
    return ({f: (k, v) for f, k, v in zip(data['files'], data['keys'], data['vectors'])}, False)


def build():
    known, legacy = load_existing()
    if legacy:
        print('база без отпечатков файлов — существующие кадры считаю неизменными')
    current = scan()
    print(f'изображений в папках: {len(current)}, в базе: {len(known)}')

    reuse, todo = {}, []
    for rel, folder, key in current:
        old = known.get(rel)
        if old and (old[0] is None or old[0] == key):
            reuse[rel] = old[1]
        else:
            todo.append((rel, folder))

    gone = len(known) - len(reuse)
    print(f'  без изменений: {len(reuse)}')
    print(f'  новых и правленых: {len(todo)}')
    print(f'  удалено из базы: {gone}\n')

    fresh = {}
    if todo:
        by_folder = {}
        for rel, folder in todo:
            by_folder.setdefault(folder, []).append(rel)
        for n, (folder, rels) in enumerate(sorted(by_folder.items()), 1):
            print(f'[{n}/{len(by_folder)}] {folder[:52]:<54} {len(rels)} шт')
            paths = [PHOTOS / r for r in rels]
            vectors, kept = embed_paths(paths)
            for path, vector in zip(kept, vectors):
                fresh[str(path.relative_to(PHOTOS))] = vector

    files, labels, vectors = [], [], []
    for rel, folder, _ in current:
        vector = fresh.get(rel)
        if vector is None:
            vector = reuse.get(rel)
        if vector is None:               # кадр не прочитался — в базу не попадает
            continue
        files.append(rel)
        labels.append(folder)
        vectors.append(vector)

    matrix = np.vstack(vectors).astype('float32')
    keys = {rel: key for rel, _, key in current}
    np.savez_compressed(INDEX, vectors=matrix, labels=np.array(labels),
                        files=np.array(files),
                        keys=np.array([keys[f] for f in files]))
    print(f'\nбаза: {matrix.shape[0]} векторов, {len(set(labels))} классов')
    print(f'сохранено: {INDEX.name}  ({INDEX.stat().st_size / 1024 / 1024:.1f} МБ)')


def stats():
    data = np.load(INDEX, allow_pickle=False)
    labels = data['labels']
    from collections import Counter
    counts = Counter(labels.tolist())
    print(f'классов: {len(counts)}, векторов: {len(labels)}\n')
    for name, n in sorted(counts.items()):
        mark = '  ⚠ мало' if n < 4 else ''
        print(f'  {n:>3}  {name[:62]}{mark}')


class Index:
    """Загруженная база с поиском ближайших."""

    def __init__(self, path=INDEX):
        data = np.load(path, allow_pickle=False)
        self.vectors = data['vectors']
        self.labels = data['labels']
        self.files = data['files']

    @property
    def classes(self):
        return sorted(set(self.labels.tolist()))

    def query(self, image, top=5, per_class='max'):
        """Похожие классы: [(класс, сходство 0..1, файл-эталон)]."""
        vector = embed([image])[0]
        sims = self.vectors @ vector                       # косинусная близость
        best = {}
        for sim, label, file in zip(sims, self.labels, self.files):
            if label not in best or sim > best[label][0]:
                best[label] = (float(sim), file)
        ranked = sorted(best.items(), key=lambda kv: -kv[1][0])[:top]
        return [{'label': lbl, 'score': round(s, 4), 'file': f} for lbl, (s, f) in ranked]


def main():
    action = sys.argv[1] if len(sys.argv) > 1 else 'build'
    if action == 'build':
        build()
    elif action == 'stats':
        stats()
    elif action == 'test':
        index = Index()
        image = Image.open(sys.argv[2]).convert('RGB')
        for row in index.query(image):
            print(f"  {row['score']:.3f}  {row['label']}")
    else:
        print(__doc__)


if __name__ == '__main__':
    main()
