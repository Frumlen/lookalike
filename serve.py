"""Страница проверки: загружаешь фото — получаешь, на что оно похоже.

Модель не обучается: DINOv2 превращает кадр в вектор, дальше поиск
ближайших в базе, собранной скриптом build_index.py.

Запуск:  .venv/bin/python serve.py 8100
"""
import base64
import io
import json
import sys
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from PIL import Image

from build_index import INDEX, PHOTOS, Index

BASE = Path(__file__).parent

PAGE = """<!doctype html>
<html lang="ru"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>На что похож товар</title>
<style>
 *{box-sizing:border-box} body{margin:0;font:15px/1.5 -apple-system,Segoe UI,Roboto,sans-serif;
   background:#f5f6f8;color:#1a1d21}
 .wrap{max-width:900px;margin:0 auto;padding:24px 16px 50px}
 h1{font-size:20px;margin:0 0 4px} .sub{color:#7a828c;font-size:13px;margin-bottom:18px}
 .drop{background:#fff;border:2px dashed #ccd3dc;border-radius:12px;padding:34px 20px;
   text-align:center;cursor:pointer;transition:.15s}
 .drop:hover,.drop.over{border-color:#3b6fd4;background:#f7faff}
 .drop b{color:#3b6fd4}
 .panel{display:grid;grid-template-columns:230px 1fr;gap:18px;margin-top:18px}
 @media(max-width:640px){.panel{grid-template-columns:1fr}}
 .shot{background:#fff;border:1px solid #e3e5e9;border-radius:10px;padding:10px}
 .shot img{width:100%;border-radius:7px;display:block}
 .res{background:#fff;border:1px solid #e3e5e9;border-radius:10px;padding:14px}
 .row{display:flex;align-items:center;gap:11px;padding:9px 0;border-bottom:1px solid #f2f3f5}
 .row:last-child{border-bottom:0}
 .row img{width:52px;height:52px;object-fit:cover;border-radius:6px;background:#eceef1;flex:none}
 .row .name{flex:1;min-width:0}
 .row .name b{display:block;font-size:14px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
 .bar{height:6px;background:#eceef1;border-radius:3px;margin-top:5px;overflow:hidden}
 .bar i{display:block;height:100%;background:#3b6fd4}
 .pct{font-size:15px;font-weight:600;min-width:52px;text-align:right}
 .row:first-child .pct{color:#2c7a39} .row:first-child .bar i{background:#2c7a39}
 .empty{color:#8a929c;font-style:italic;padding:12px 0}
 .meta{font-size:12px;color:#7a828c;margin-top:12px}
</style></head><body><div class="wrap">
<h1>На что похож товар</h1>
<div class="sub" id="sub">—</div>
<div class="drop" id="drop" onclick="document.getElementById('f').click()">
  Перетащите фотографию сюда или <b>выберите файл</b>
  <input type="file" id="f" accept="image/*" hidden onchange="send(this.files[0])">
</div>
<div class="panel" id="panel" hidden>
  <div class="shot"><img id="shot"></div>
  <div class="res" id="res"></div>
</div>
</div>
<script>
const $=s=>document.querySelector(s);
const esc=s=>String(s).replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
const drop=$('#drop');
['dragenter','dragover'].forEach(e=>drop.addEventListener(e,ev=>{ev.preventDefault();drop.classList.add('over')}));
['dragleave','drop'].forEach(e=>drop.addEventListener(e,ev=>{ev.preventDefault();drop.classList.remove('over')}));
drop.addEventListener('drop',ev=>{if(ev.dataTransfer.files[0])send(ev.dataTransfer.files[0])});
document.addEventListener('paste',ev=>{
  const it=[...ev.clipboardData.items].find(i=>i.type.startsWith('image/'));
  if(it)send(it.getAsFile());
});

async function send(file){
  if(!file)return;
  $('#panel').hidden=false;
  $('#shot').src=URL.createObjectURL(file);
  $('#res').innerHTML='<div class="empty">Считаю…</div>';
  const fd=new FormData(); fd.append('image',file);
  const t0=performance.now();
  const d=await(await fetch('/api/match',{method:'POST',body:fd})).json();
  const ms=Math.round(performance.now()-t0);
  if(d.error){$('#res').innerHTML='<div class="empty">Ошибка: '+esc(d.error)+'</div>';return}
  $('#res').innerHTML=d.matches.map(m=>`
    <div class="row">
      <img src="/эталон/${encodeURI(m.file)}">
      <div class="name"><b>${esc(m.label)}</b>
        <div class="bar"><i style="width:${Math.max(2,(m.score*100).toFixed(0))}%"></i></div>
      </div>
      <div class="pct">${(m.score*100).toFixed(1)}%</div>
    </div>`).join('')+`<div class="meta">Ответ за ${ms} мс</div>`;
}

fetch('/api/info').then(r=>r.json()).then(d=>{
  $('#sub').textContent=`В базе ${d.classes} классов, ${d.vectors} эталонных снимков. `+
    `Модель ${d.model} — обучение не проводилось, используются готовые веса.`;
});
</script></body></html>"""


def parse_multipart(body, boundary):
    """Достаёт первый файл из multipart/form-data без внешних библиотек."""
    marker = b'--' + boundary
    for part in body.split(marker):
        head, _, data = part.partition(b'\r\n\r\n')
        if b'filename=' in head and data:
            return data.rsplit(b'\r\n', 1)[0]
    return None


class Handler(BaseHTTPRequestHandler):
    index = None
    stamp = None

    @classmethod
    def refresh(cls):
        """Если база пересобрана — подхватываем её без перезапуска сервера."""
        mtime = INDEX.stat().st_mtime
        if cls.stamp != mtime:
            cls.index = Index()
            cls.stamp = mtime
            print(f'база перезагружена: {cls.index.vectors.shape[0]} векторов, '
                  f'{len(cls.index.classes)} классов', flush=True)

    def _send(self, payload, ctype='application/json; charset=utf-8', code=200):
        body = payload if isinstance(payload, bytes) else json.dumps(payload, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header('Content-Type', ctype)
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = urllib.parse.unquote(urllib.parse.urlparse(self.path).path)
        if path == '/':
            self._send(PAGE.encode(), 'text/html; charset=utf-8')
        elif path == '/api/info':
            self.refresh()
            self._send({'classes': len(self.index.classes),
                        'vectors': int(self.index.vectors.shape[0]),
                        'model': 'DINOv2-base'})
        elif path.startswith('/эталон/'):
            file = (PHOTOS / path[len('/эталон/'):]).resolve()
            if PHOTOS.resolve() in file.parents and file.is_file():
                self._send(file.read_bytes(), 'image/jpeg')
            else:
                self.send_error(404)
        else:
            self.send_error(404)

    def do_POST(self):
        if urllib.parse.urlparse(self.path).path != '/api/match':
            self.send_error(404)
            return
        ctype = self.headers.get('Content-Type', '')
        if 'boundary=' not in ctype:
            self._send({'error': 'нет файла'})
            return
        boundary = ctype.split('boundary=')[1].strip('"').encode()
        raw = self.rfile.read(int(self.headers.get('Content-Length', 0)))
        blob = parse_multipart(raw, boundary)
        if not blob:
            self._send({'error': 'файл не разобран'})
            return
        try:
            self.refresh()
            image = Image.open(io.BytesIO(blob)).convert('RGB')
            self._send({'matches': self.index.query(image, top=5)})
        except Exception as exc:
            self._send({'error': f'{type(exc).__name__}: {exc}'})

    def log_message(self, fmt, *args):
        pass


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8100
    if not INDEX.exists():
        print(f'Нет базы {INDEX.name}. Сначала: .venv/bin/python build_index.py build')
        return 1
    Handler.refresh()
    print(f'база: {Handler.index.vectors.shape[0]} векторов, '
          f'{len(Handler.index.classes)} классов')
    print(f'открывайте http://127.0.0.1:{port}/')
    ThreadingHTTPServer(('127.0.0.1', port), Handler).serve_forever()


if __name__ == '__main__':
    sys.exit(main() or 0)
