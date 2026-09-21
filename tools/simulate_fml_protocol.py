#!/usr/bin/env python3
"""Офлайн-симулятор протокола Forge/Fabric для ModChecker.

В песочнице/CI без доступа к repo.purpurmc.org нельзя выполнить `mvn clean package`,
поэтому алгоритм разбора пакетов из ModListListener.java здесь воспроизведён 1-в-1
на Python и прогнан на синтетических пакетах реальных форматов:

  * fml:handshake, FML2 (Forge/NeoForge 1.13+): varint-индекс + varint-строки;
  * FML|HS, legacy (Forge 1.7.10–1.12.2): байт-дискриминатор + readUTF (2 байта длины);
  * minecraft:register: имена каналов через байт 0x00.

Скрипт проверяет, что:
  1. корректные пакеты разбираются в ожидаемый список модов;
  2. мусорные/обрезанные пакеты не «взрываются» и не дают ложных данных.

Запуск:  python3 tools/simulate_fml_protocol.py
"""
from __future__ import annotations

import sys

CORE_MOD_IDS = {"minecraft", "forge", "fml", "mcp", "neoforge", "java", "modlauncher", "forgespi"}
FABRIC_MARKERS = (
    "fabric:registry/sync", "fabric-screen-handler-api", "fabric-networking-api",
    "fabric-keybindings-api", "fabric-resource-loader", "fabric-lifecycle-events",
    "fabric-item-api", "fabric-transfer-api",
)

failures: list[str] = []


# ------------------------------------------------------------------ кодирование
def varint(value: int) -> bytes:
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def utf(text: str) -> bytes:
    """Строка в формате FML2: varint-длина + UTF-8."""
    data = text.encode("utf-8")
    return varint(len(data)) + data


def legacy_utf(text: str) -> bytes:
    """Строка в формате DataOutputStream.writeUTF: 2 байта длины + UTF-8."""
    data = text.encode("utf-8")
    return len(data).to_bytes(2, "big") + data


# ------------------------------------------------------------------ курсор (аналог Cursor из Java)
class Cursor:
    def __init__(self, data: bytes, offset: int = 0) -> None:
        self.data = data
        self.pos = offset

    def has_remaining(self) -> bool:
        return self.pos < len(self.data)

    def read_varint(self) -> int:
        result = 0
        shift = 0
        while True:
            if not self.has_remaining():
                raise ValueError("Пакет оборвался на varint")
            if shift >= 32:
                raise ValueError("Слишком длинный varint")
            current = self.data[self.pos]
            self.pos += 1
            result |= (current & 0x7F) << shift
            if not current & 0x80:
                return result
            shift += 7

    def read_utf(self) -> str:
        length = self.read_varint()
        if length < 0 or length > len(self.data) - self.pos:
            raise ValueError(f"Некорректная длина строки: {length}")
        chunk = self.data[self.pos:self.pos + length]
        self.pos += length
        return chunk.decode("utf-8")


# ------------------------------------------------------------------ парсеры (как в Java)
def sanitize(mods: list[str]) -> list[str]:
    unique: list[str] = []
    seen: set[str] = set()
    for mod in mods:
        if mod is None:
            continue
        value = mod.strip()
        if not value:
            continue
        parts = value.split(" ", 1)
        mod_id = parts[0]
        version = parts[1].strip() if len(parts) > 1 else ""
        if "(" in version:
            version = version[version.index("(") + 1:].replace(")", "").strip()
        # Пустой идентификатор или пустая версия — признак битого пакета.
        if not mod_id or (version is not None and len(parts) > 1 and not version):
            continue
        if mod_id.lower() in CORE_MOD_IDS:
            continue
        entry = f"{mod_id} ({version})" if len(parts) > 1 and version else mod_id
        if entry not in seen:
            seen.add(entry)
            unique.append(entry)
    return sorted(unique, key=str.lower)


def clamp_count(count: int) -> int:
    return 0 if count < 0 else min(count, 4096)


def parse_fml_handshake(payload: bytes) -> list[str]:
    """Канал fml:handshake (FML2): индекс 2 — modid'ы, индекс 3 — (имя, версия)."""
    cursor = Cursor(payload)
    index = cursor.read_varint()
    mods: list[str] = []

    if index == 2:  # C2SModListReply
        for _ in range(clamp_count(cursor.read_varint())):
            if not cursor.has_remaining():
                return []          # пакет обрезан — частичные данные не показываем
            mods.append(cursor.read_utf())
    elif index == 3:  # ModVersions: map modId -> (name, version)
        for _ in range(clamp_count(cursor.read_varint())):
            if not cursor.has_remaining():
                return []
            mod_id = cursor.read_utf()
            cursor.read_utf()      # отображаемое имя
            version = cursor.read_utf()
            if not mod_id.strip() or not version.strip():
                return []
            mods.append(f"{mod_id} ({version})")

    return sanitize(mods)


def parse_legacy_fml_hs(payload: bytes) -> list[str]:
    """Канал FML|HS (1.7.10–1.12.2): байт-дискриминатор 2 = ModList."""
    if not payload or (payload[0] & 0xFF) != 2:
        return []

    # Вариант 1: readUTF (2 байта длины).
    mods = _legacy_data_input(payload)
    if not mods:
        # Вариант 2: varint-строки.
        mods = _legacy_varint(payload)
    return mods


def _legacy_data_input(payload: bytes) -> list[str]:
    mods: list[str] = []
    try:
        pos = 1
        declared = clamp_count(int.from_bytes(payload[pos:pos + 4], "big"))
        pos += 4
        for _ in range(declared):
            strings: list[str] = []
            for _read in range(2):
                length = int.from_bytes(payload[pos:pos + 2], "big")
                pos += 2
                chunk = payload[pos:pos + length]
                if len(chunk) != length:
                    raise ValueError("обрезанная строка")
                pos += length
                strings.append(chunk.decode("utf-8"))
            if not strings[0].strip() or not strings[1].strip():
                return []
            mods.append(f"{strings[0]} ({strings[1]})")
    except (ValueError, IndexError):
        return []
    return sanitize(mods)


def _legacy_varint(payload: bytes) -> list[str]:
    mods: list[str] = []
    try:
        cursor = Cursor(payload, 1)
        for _ in range(clamp_count(cursor.read_varint())):
            if not cursor.has_remaining():
                return []
            mod_id = cursor.read_utf()
            version = cursor.read_utf()
            if not mod_id.strip() or not version.strip():
                return []
            mods.append(f"{mod_id} ({version})")
    except ValueError:
        return []
    return sanitize(mods)


def parse_register(payload: bytes) -> tuple[list[str], bool]:
    """Канал minecraft:register: список каналов + признак Fabric-клиента."""
    channels = [c.strip() for c in payload.decode("utf-8", "replace").split("\x00") if c.strip()]
    fabric = any(c.lower().startswith(FABRIC_MARKERS) for c in channels)
    return channels, fabric


# ------------------------------------------------------------------ проверки
def describe(value) -> str:
    if isinstance(value, list) and len(value) > 8:
        return f"[{len(value)} элементов] {value[:3]} …"
    return repr(value)


def check(name: str, actual, expected) -> None:
    if actual == expected:
        print(f"  [ OK ] {name}: {describe(actual)}")
    else:
        failures.append(f"{name}: ожидалось {describe(expected)}, получено {describe(actual)}")
        print(f"  [FAIL] {name}\n         ожидалось: {describe(expected)}\n         получено:  {describe(actual)}")


def main() -> int:
    print("=== 1. fml:handshake / FML2, пакет ModVersions (индекс 3) ===")
    payload = varint(3) + varint(4)
    for mod_id, display, version in (
        ("minecraft", "Minecraft", "1.21.1"),
        ("forge", "Forge", "52.0.42"),
        ("jei", "Just Enough Items", "19.21.0.247"),
        ("sodium", "Sodium", "0.6.9"),
    ):
        payload += utf(mod_id) + utf(display) + utf(version)
    check("список модов без служебных", parse_fml_handshake(payload), ["jei (19.21.0.247)", "sodium (0.6.9)"])

    print("\n=== 2. fml:handshake / FML2, пакет C2SModListReply (индекс 2) ===")
    payload = varint(2) + varint(3) + utf("forge") + utf("jei") + utf("journeymap")
    check("только modid", parse_fml_handshake(payload), ["jei", "journeymap"])

    print("\n=== 3. FML|HS / legacy, ModList (дискриминатор 2, readUTF) ===")
    payload = bytes([2]) + (2).to_bytes(4, "big")
    payload += legacy_utf("jei") + legacy_utf("19.21.0.247") + legacy_utf("optifine") + legacy_utf("HD_U_I7")
    check("legacy-формат 1.12.2", parse_legacy_fml_hs(payload), ["jei (19.21.0.247)", "optifine (HD_U_I7)"])

    print("\n=== 4. FML|HS / legacy, ModList (дискриминатор 2, varint-строки) ===")
    payload = bytes([2]) + varint(2) + utf("lithium") + utf("0.14.9") + utf("iris") + utf("1.8.9")
    check("запасной вариант разбора", parse_legacy_fml_hs(payload), ["iris (1.8.9)", "lithium (0.14.9)"])

    print("\n=== 5. Прочие дискриминаторы игнорируются ===")
    check("ClientHello (1)", parse_legacy_fml_hs(bytes([1]) + legacy_utf("FML") + legacy_utf("1.12.2")), [])
    check("HandshakeAck (-1)", parse_legacy_fml_hs(bytes([0xFF]) + b"\x00\x00\x00\x02"), [])

    print("\n=== 6. minecraft:register ===")
    channels, fabric = parse_register(b"fabric:registry/sync/direct\x00fabric-networking-api-v1\x00minecraft:brand\x00")
    check("количество каналов", len(channels), 3)
    check("признак Fabric", fabric, True)
    channels, fabric = parse_register(b"fml:handshake\x00FML|HS\x00")
    check("Forge-каналы не дают Fabric", fabric, False)
    check("список Forge-каналов", channels, ["fml:handshake", "FML|HS"])

    print("\n=== 7. Устойчивость к мусору и обрезкам ===")
    garbage = [
        b"",
        b"\xff\xff\xff\xff\xff\xff\xff\xff",      # бесконечный varint
        varint(3) + varint(10_000_000),           # заявлено 10 млн модов
        varint(2) + varint(5) + utf("jei"),       # обрезанный список
        utf("minecraft") + utf("1.21.1"),         # не handshake вообще
        bytes([2]) + b"\x00\x00\x7f\xff",         # битый legacy-пакет
        b"\x00" * 3,
    ]
    for index, chunk in enumerate(garbage):
        # В Java любой сбой разбора перехватывается (RuntimeException/IOException)
        # и просто логируется — сервер продолжает работать. «exception» здесь = «безопасно проигнорировано».
        try:
            mods_a = parse_fml_handshake(chunk) if chunk else []
        except ValueError:
            mods_a = "exception"
        try:
            mods_b = parse_legacy_fml_hs(chunk)
        except ValueError:
            mods_b = "exception"
        huge = (isinstance(mods_a, list) and len(mods_a) > 4096) or (isinstance(mods_b, list) and len(mods_b) > 4096)
        # Мусорный пакет не должен давать НИКАКОГО списка модов:
        # в Java частичный разбор тоже отбрасывается (return List.of()).
        wrong_mods = any(
            isinstance(value, list) and len(value) > 0
            for value in (mods_a, mods_b)
        )
        if huge or wrong_mods:
            failures.append(f"мусорный пакет #{index}: результат {mods_a!r} / {mods_b!r}")
            print(f"  [FAIL] пакет #{index} ({len(chunk)} байт): {mods_a!r} / {mods_b!r}")
        else:
            print(f"  [ OK ] пакет #{index} ({len(chunk)} байт) -> fml2={mods_a}, legacy={mods_b}")

    print("\n=== 8. Большие пакеты и защита от DoS (clampCount = 4096) ===")
    payload = varint(2) + varint(3000) + b"".join(utf(f"mod{i}") for i in range(3000))
    check("3000 реальных модов разобраны", len(parse_fml_handshake(payload)), 3000)

    # Заявлено 5 000 000 элементов, а данных нет: цикл ограничен 4096 итерациями,
    # после чего пакет отбрасывается как обрезанный (в Java — return List.of()).
    payload = varint(2) + varint(5_000_000) + utf("jei")
    check("завышенное количество не приводит к зависанию", parse_fml_handshake(payload), [])

    # Заявлено 5000, реально прислано 5000: чтение ограничено лимитом 4096 — DoS исключён.
    payload = varint(2) + varint(5000) + b"".join(utf(f"mod{i}") for i in range(5000))
    check("чтение ограничено лимитом 4096", len(parse_fml_handshake(payload)), 4096)

    print("\n=== ИТОГ ===")
    if failures:
        print(f"  Провалено проверок: {len(failures)}")
        for failure in failures:
            print(f"  [FAIL] {failure}")
        return 1
    print("  Алгоритм разбора пакетов ведёт себя корректно на всех форматах.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
