"""Поддельные весы Масса-К для проверки приложения.

Отвечает по «Протоколу 100» так же, как настоящие: на запрос массы отдаёт
вес, на запрос параметров — пределы взвешивания. Нужен, чтобы проверить
экран «Весы» в приложении, не дожидаясь доступа к магазину.

Масса меняется по кругу, изображая, что на площадку кладут разные товары:
сначала пару секунд «скачет», потом устаивается.

    python3 tools/fake_scale.py
    python3 tools/fake_scale.py --weight 1234      постоянный вес
    python3 tools/fake_scale.py --port 5001
"""
import argparse
import socket
import struct
import threading
import time

HEADER = b'\xF8\x55\xCE'
CMD_GET_MASSA = 0x23
CMD_ACK_MASSA = 0x24
CMD_GET_SCALE_PAR = 0x75
CMD_ACK_SCALE_PAR = 0x76
CMD_NACK = 0xF0

# По кругу, чтобы было видно, что значение живое
CYCLE = [340, 1234, 2760, 515, 8420]
SETTLE_SECONDS = 2.0        # столько масса «скачет» после смены


class Platform:
    """Что сейчас лежит на площадке."""

    def __init__(self, fixed=None, period=12.0):
        self.fixed = fixed
        self.period = period
        self.started = time.time()

    def read(self):
        if self.fixed is not None:
            return self.fixed, True
        elapsed = time.time() - self.started
        step = int(elapsed // self.period)
        grams = CYCLE[step % len(CYCLE)]
        settling = (elapsed % self.period) < SETTLE_SECONDS
        if settling:
            # Пока не устоялось, показания слегка пляшут
            grams += int((elapsed * 37) % 15) - 7
        return grams, not settling


def crc16(data, init=0x0000):
    crc = init
    for byte in data:
        crc ^= byte << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) & 0xFFFF if crc & 0x8000 else (crc << 1) & 0xFFFF
    return crc


def frame(body):
    return HEADER + struct.pack('<H', len(body)) + body + struct.pack('<H', crc16(body))


def massa_reply(grams, stable):
    return frame(
        bytes([CMD_ACK_MASSA])
        + struct.pack('<i', grams)
        + bytes([1, 1 if stable else 0, 0, 1 if grams == 0 else 0])
    )


def params_reply():
    """Текстовые поля, как их отдают настоящие весы — в кириллице CP1251."""
    text = 'Max 15 кг\r\nMin 0,04 кг\r\ne = 5 г\r\nT = -6 кг\r\n'
    return frame(bytes([CMD_ACK_SCALE_PAR]) + text.encode('cp1251'))


def recv_exactly(sock, count):
    chunks = b''
    while len(chunks) < count:
        part = sock.recv(count - len(chunks))
        if not part:
            return None
        chunks += part
    return chunks


def serve(sock, address, platform):
    peer = f'{address[0]}:{address[1]}'
    try:
        while True:
            head = recv_exactly(sock, 5)
            if head is None:
                break
            if head[:3] != HEADER:
                print(f'  {peer}: не кадр Масса-К: {head.hex(" ").upper()}')
                break
            length = struct.unpack('<H', head[3:5])[0]
            body = recv_exactly(sock, length)
            if body is None:
                break
            recv_exactly(sock, 2)                      # CRC запроса не проверяем

            command = body[0]
            if command == CMD_GET_MASSA:
                grams, stable = platform.read()
                sock.sendall(massa_reply(grams, stable))
                mark = 'устоялась' if stable else 'скачет'
                print(f'  {peer}  запрос массы  ->  {grams} г, {mark}')
            elif command == CMD_GET_SCALE_PAR:
                sock.sendall(params_reply())
                print(f'  {peer}  запрос параметров')
            else:
                sock.sendall(frame(bytes([CMD_NACK])))
                print(f'  {peer}  неизвестная команда 0x{command:02X}')
    except OSError:
        pass
    finally:
        sock.close()


def local_ip():
    """Адрес, по которому нас видно из сети."""
    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        probe.connect(('8.8.8.8', 80))
        return probe.getsockname()[0]
    except OSError:
        return '127.0.0.1'
    finally:
        probe.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=5001)
    parser.add_argument('--weight', type=int, help='постоянная масса в граммах')
    parser.add_argument('--period', type=float, default=12.0, help='смена массы, секунд')
    args = parser.parse_args()

    platform = Platform(args.weight, args.period)
    server = socket.socket()
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(('0.0.0.0', args.port))
    server.listen(8)

    print(f'поддельные весы Масса-К слушают {local_ip()}:{args.port}')
    if args.weight is None:
        print(f'масса меняется по кругу {CYCLE} каждые {args.period:.0f} с')
    else:
        print(f'масса постоянная: {args.weight} г')
    print('впишите этот адрес и порт в приложении: Настройки -> Весы\n')

    while True:
        sock, address = server.accept()
        threading.Thread(target=serve, args=(sock, address, platform), daemon=True).start()


if __name__ == '__main__':
    main()
