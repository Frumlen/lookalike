"""Проверка устройства с приложением lookalike.

Делает то же, что будет делать 1С: запрашивает вес и штрихкод по HTTP
и отдельно опрашивает приложение как весы по «Протоколу 100».

Запускать с любой машины в той же сети — зависимостей нет.

    python3 tools/test_device.py 192.168.1.77
    python3 tools/test_device.py 192.168.1.77 --http 8099 --scale 5001
"""
import argparse
import json
import socket
import struct
import sys
import time
import urllib.error
import urllib.request

HEADER = b'\xF8\x55\xCE'
CMD_GET_MASSA = 0x23
CMD_ACK_MASSA = 0x24
CMD_NACK = 0xF0
DIVISION = (0.1, 1.0, 10.0, 100.0, 1000.0)


def crc16(data, init=0x0000):
    """CRC-16-CCITT, как в протоколе Масса-К: считается от байта команды."""
    crc = init
    for byte in data:
        crc ^= byte << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) & 0xFFFF if crc & 0x8000 else (crc << 1) & 0xFFFF
    return crc


def frame(command, init=0x0000):
    body = bytes([command])
    return HEADER + struct.pack('<H', len(body)) + body + struct.pack('<H', crc16(body, init))


# ------------------------------------------------------------------ HTTP

def check_http(host, port, timeout):
    print(f'HTTP  http://{host}:{port}')
    ok, notes = True, []
    for path in ('/', '/weight', '/product'):
        url = f'http://{host}:{port}{path}'
        started = time.perf_counter()
        try:
            with urllib.request.urlopen(url, timeout=timeout) as response:
                raw = response.read().decode('utf-8')
            spent = (time.perf_counter() - started) * 1000
            data = json.loads(raw)
            print(f'  {path:<9} {spent:>5.0f} мс  {json.dumps(data, ensure_ascii=False)}')
            if path == '/':
                notes = describe(data)
        except (urllib.error.URLError, socket.timeout, OSError) as exc:
            print(f'  {path:<9} НЕ ОТВЕТИЛ: {exc}')
            ok = False
        except json.JSONDecodeError:
            print(f'  {path:<9} ответ не разобран: {raw[:120]}')
            ok = False
    return ok, notes


def ean13_ok(code):
    """Проверка контрольной цифры EAN-13."""
    if len(code) != 13 or not code.isdigit():
        return False
    total = sum((d if i % 2 == 0 else d * 3)
                for i, d in enumerate(int(c) for c in code[:12]))
    return (10 - total % 10) % 10 == int(code[12])


def describe(data):
    """Разбор ответа плюс проверки, из-за которых касса может не найти товар."""
    weight = data.get('weight_g')
    name = data.get('name') or '—'
    plu = data.get('plu') or ''
    barcode = data.get('weight_barcode') or ''
    age = data.get('age_ms')
    notes = []

    if weight is None:
        print('      вес: нет данных — на устройстве ещё не запрашивали массу')
        notes.append('вес не получен')
    else:
        stable = 'устоялась' if data.get('stable') else 'ещё скачет'
        print(f'      вес: {weight} г ({stable})')
        if not data.get('stable'):
            notes.append('масса не устоялась')

    print(f'      товар: {name}')

    if not plu:
        print('      номер на весах: НЕ ЗАДАН — касса товар не найдёт')
        notes.append('нет номера на весах')
    else:
        print(f'      номер на весах: {plu}')

    if barcode:
        mark = 'верный' if ean13_ok(barcode) else 'НЕВЕРНАЯ контрольная цифра'
        print(f'      весовой штрихкод: {barcode}  ({mark})')
        if not ean13_ok(barcode):
            notes.append('штрихкод не проходит проверку')
    elif plu:
        print('      весовой штрихкод: пуст — разметка не даёт 13 цифр')
        notes.append('разметка штрихкода неверна')

    if isinstance(age, int) and age > 60000:
        print(f'      ! данные обновлялись {age // 1000} с назад')

    return notes


# ----------------------------------------------------- эмуляция весов

def check_scale(host, port, timeout):
    print(f'\nВЕСЫ  {host}:{port}  (Протокол 100, как подключится 1С)')
    for init in (0x0000, 0xFFFF):
        request = frame(CMD_GET_MASSA, init)
        started = time.perf_counter()
        try:
            with socket.create_connection((host, port), timeout) as sock:
                sock.settimeout(timeout)
                sock.sendall(request)
                head = recv_exactly(sock, 5)
                if head[:3] != HEADER:
                    print(f'  CRC 0x{init:04X}: ответ не похож на кадр весов: {head.hex(" ").upper()}')
                    continue
                length = struct.unpack('<H', head[3:5])[0]
                body = recv_exactly(sock, length)
                recv_exactly(sock, 2)                      # CRC ответа
            spent = (time.perf_counter() - started) * 1000

            print(f'  отправлено: {request.hex(" ").upper()}')
            print(f'  получено:   {(head + body).hex(" ").upper()}')
            print(f'  время: {spent:.0f} мс')
            return parse_massa(body)
        except (socket.timeout, OSError) as exc:
            print(f'  CRC 0x{init:04X}: {type(exc).__name__}: {exc}')
    return False


def recv_exactly(sock, count):
    chunks = b''
    while len(chunks) < count:
        part = sock.recv(count - len(chunks))
        if not part:
            raise OSError('соединение закрыто раньше времени')
        chunks += part
    return chunks


def parse_massa(body):
    if not body:
        print('  пустой ответ')
        return False
    command = body[0]
    if command == CMD_NACK:
        print('  устройство не поняло команду')
        return False
    if command != CMD_ACK_MASSA or len(body) < 9:
        print(f'  неожиданный ответ, код 0x{command:02X}')
        return False

    weight = struct.unpack('<i', body[1:5])[0]
    step = DIVISION[body[5]] if body[5] < len(DIVISION) else 1.0
    print(f'  масса: {weight * step:.0f} г, '
          f'{"устоялась" if body[6] else "скачет"}'
          f'{", NET" if body[7] else ""}'
          f'{", ноль" if body[8] else ""}')
    return True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('host', help='адрес устройства с приложением')
    parser.add_argument('--http', type=int, default=8099)
    parser.add_argument('--scale', type=int, default=5001)
    parser.add_argument('--timeout', type=float, default=5.0)
    args = parser.parse_args()

    print(f'проверяю {args.host}\n')
    http_ok, notes = check_http(args.host, args.http, args.timeout)
    scale_ok = check_scale(args.host, args.scale, args.timeout)

    print('\n' + '─' * 52)
    print(f'  HTTP (вес и штрихкод):  {"работает" if http_ok else "НЕ ОТВЕЧАЕТ"}')
    print(f'  весы (только вес):      {"работает" if scale_ok else "НЕ ОТВЕЧАЕТ"}')

    if not (http_ok or scale_ok):
        print('\n  проверьте: приложение открыто, сервер запущен в «Настройки → Касса»,')
        print('  устройство в той же сети, порты совпадают')
        return 1

    if notes:
        print('\n  до передачи в 1С осталось:')
        for note in notes:
            print(f'    — {note}')
    elif http_ok:
        print('\n  всё готово: 1С получит и вес, и штрихкод')
    return 0


if __name__ == '__main__':
    sys.exit(main())
