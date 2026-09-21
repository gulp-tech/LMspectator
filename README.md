# LMspectator / ModChecker

**ModChecker** — плагин для серверов **Purpur / Paper 1.21.1** (Java 21), который показывает
администратору, с какого клиента зашёл игрок, какая у него версия игры и какие моды удалось
прочитать из стандартных каналов данных Minecraft.

- **Главный класс:** `ru.musd348.modchecker.ModChecker`
- **Автор:** musd348
- **Команда:** `/checkmods <ник_игрока>` (алиасы: `/checkmod`, `/mods`, `/cm`)
- **Право:** `modchecker.use` (по умолчанию только операторы)

## Формат вывода

```
[ModChecker] Информация о игроке Steve:
• Клиент: Fabric
• Версия: 1.21.1
• Моды: sodium (0.6.9), lithium (0.14.9), iris (1.8.9)
```

Все сообщения рендерятся через **MiniMessage** (`net.kyori.adventure.text.minimessage`),
никаких legacy-кодов `§` — предупреждений компилятора и депрекаций не будет.

## Как это работает

| Источник | Что даёт | API |
| --- | --- | --- |
| `minecraft:brand` | имя клиента (Fabric, Forge, Lunar, Badlion, …) | `Player#getClientBrandName()` |
| номер сетевого протокола | версия игры клиента (767 → 1.21.1) | `Player#getProtocolVersion()` (Paper/Purpur API) |
| `fml:handshake` | список модов Forge/NeoForge 1.13+ (формат FML2) | `PluginMessageListener` |
| `FML\|HS` | список модов Forge 1.7.10–1.12.2 (legacy-формат) | `PluginMessageListener` |
| `minecraft:register` | уточнение типа клиента по каналам Fabric API | `PluginMessageListener` |

Парсер каналов написан «мягко»: varint-строки FML2 и `readUTF` legacy-формата пробуются
по очереди, любой битый/обрезанный пакет просто игнорируется (сервер не падает, лог не спамится).

### Честные ограничения

1. **Forge 1.20.5+ и NeoForge** передают список модов в фазе **login/configuration**
   (пакет `ModVersions`, канал `forge:handshake`). Bukkit-каналы работают только в фазе
   `play`, поэтому без обращения к NMS/интерналам Paper такой список не перехватить —
   для таких игроков плагин покажет клиента и версию, а в строке «Моды» будет `Нет модов`.
2. **Fabric** не отправляет список модов по стандартным каналам вообще — только бренд
   (`Fabric`) и свои каналы регистрации. Плагин корректно определит клиент и версию,
   mods останутся пустыми.
3. Точный список модов реально получить у **Forge 1.13–1.20.4** и **Forge 1.7.10–1.12.2**.
4. Если бренд пустой, клиент считается `Vanilla`.

Отладочный лог разбора пакетов включается флагом JVM: `-Dmodchecker.debug=true`.

## Сборка

Требуется **JDK 21** и **Maven 3.9+**, а также доступ к `repo.purpurmc.org` и Maven Central:

```bash
mvn clean package
```

Готовый файл появится в `target/ModChecker-1.0.0.jar` — его и нужно класть в `plugins/`.

Если Maven в системе не установлен, сгенерируйте обёртку один раз (в `.mvn/wrapper/`
уже лежит `maven-wrapper.properties` с дистрибутивом 3.9.9):

```bash
mvn wrapper:wrapper -Dmaven=3.9.9   # создаст mvnw / mvnw.cmd
./mvnw clean package                # дальше Maven можно не ставить в систему
```

### Офлайн-проверки без Maven

В каталоге `tools/` лежат два скрипта, которые не требуют ни JDK, ни интернета —
ими удобно проверять проект в CI или в песочнице без доступа к Maven-репозиториям:

```bash
# синтаксис Java (tree-sitter), pom.xml, plugin.yml, баланс тегов MiniMessage, статика
python3 tools/verify_project.py

# эталонный прогон алгоритма разбора fml:handshake / FML|HS / minecraft:register
# на синтетических пакетах реальных форматов + устойчивость к мусору и DoS
python3 tools/simulate_fml_protocol.py
```

Для `verify_project.py` нужны пакеты `tree-sitter`, `tree-sitter-java` и `pyyaml`
(`pip install tree-sitter tree-sitter-java pyyaml`).

## Структура проекта

```
pom.xml                                                     # Java 21 + Purpur API 1.21.1
src/main/java/ru/musd348/modchecker/
├── ModChecker.java                                         # главный класс, команда /checkmods, кэш данных
├── ModListListener.java                                    # слушатель каналов fml:handshake / FML|HS / minecraft:register
├── ClientInfo.java                                         # неизменяемый снимок данных о клиенте (record)
└── ClientDetector.java                                     # бренд → имя клиента, протокол → версия игры
src/main/resources/plugin.yml                               # манифест плагина
```
