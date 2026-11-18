# PC simulátory (Windows)

Dva programy pro PC, se kterými jde aplikaci vyzkoušet bez pedálu a bez pultu:

- **`pedal_simulator.py`** – tři velká tlačítka jako na pedálu. Do emulátoru posílá stisky přes ADB broadcast (`cz.svatjir.wingmonitor.PEDAL`), ke skutečnému telefonu se připojí přes Bluetooth LE úplně stejně jako ESP32.
- **`input_simulator.py`** – přehraje CC0 skladbu a k ní posílá úrovně jednotlivých stop simulátoru pultu (`127.0.0.1:2229`), který je dál posílá aplikacím jako skutečný WING. Pokud běží emulátor, posílá je záložně i přes ADB. **Šum sálu** drží všechny kanály aspoň na −50 dBFS (lidé mluví v sále) a **LUPNOUT** u stopy ji na 0,4 s vybudí na 0 dBFS. Na výběr je Potassium (šest stop) a Chopinovo Nokturno (sólový klavír).

Žádný ze simulátorů neposílá nic do skutečného pultu.

## Spuštění

```powershell
py -3.13 -m pip install -r requirements.txt
py -3.13 pedal_simulator.py
```

nebo `run.bat`, který navíc přesměruje UDP port 1241 do emulátoru (`adb emu redir add udp:1241:1241`). Bez toho do aplikace v emulátoru nedorazí živé úrovně ze simulátoru pultu. Přesměrování zmizí s každým restartem emulátoru.

`adb` se hledá v `PATH`, v `ANDROID_HOME` / `ANDROID_SDK_ROOT` a ve výchozí složce Android Studia (`%LOCALAPPDATA%\Android\Sdk`).

### Emulátor

Simulátor sám najde běžící emulátor; stav ukáže `Ovládám Android přes ADB: emulator-5554`. Pak stačí mačkat nebo držet tlačítka.

### Skutečný telefon (BLE)

1. V aplikaci WING Monitor klepněte na štítek **BLE: VYP** a povolte přístup k zařízením v okolí.
2. V simulátoru klikněte na **BLE K TELEFONU** (po odpojení se tlačítko jmenuje **PŘIPOJIT BLE K TELEFONU**).

PC potřebuje funkční Bluetooth LE adaptér. Telefon se najde podle UUID služby, párování v systému není potřeba.

### Simulátor vstupů

Z panelu pedálu ho otevře **SIMULÁTOR VSTUPŮ**. Vyberte skladbu a klikněte na **PŘEHRÁT**. Zaškrtávátka u stop zapínají a vypínají jejich měření, v přehrávaném masteru je neztlumí. Které vstupy aplikace ukáže jako aktivní, určuje práh v aplikaci.

Zdroje a licence nahrávek jsou v `audio/potassium/README.md` a `audio/chopin-README.md`. Obálky úrovní se přegenerují takto:

```powershell
py -3.13 generate_stem_envelopes.py
py -3.13 generate_chopin_envelope.py
```

## Chování tlačítek

- **Levá / pravá:** procházení seznamu, na stránce nástroje nebo Masteru tišeji / hlasitěji. Podržení akci opakuje.
- **Prostřední krátce:** vybrat položku, nebo se vrátit ze stránky hlasitosti.
- **Prostřední podržet (650 ms):** zpět ze seznamu, nebo ztlumit na stránce nástroje či Masteru.

V režimu ADB posílá panel stejné hrany stisk/puštění jako pedál přes BLE. Načasování podržení a opakování řeší aplikace.

## BLE protokol pedálu

- UUID služby: `8d53dc1d-1db7-4cd3-868b-8a527460aa84`
- UUID zápisové charakteristiky: `8d53dc1d-1db7-4cd3-868b-8a527460aa85`
- Každý zápis má dva bajty `[tlačítko, stisknuto]`: tlačítko `1` = levá, `2` = prostřední, `3` = pravá; `1` = stisknuto, `0` = puštěno.
- Zápis s potvrzením (write with response), jeden zápis na každou hranu.

Je to vlastní GATT služba, ne Bluetooth klávesnice (HID). Příkazy nejsou šifrované ani ověřené, viz [párování](../../README.md#párování-pedálu-s-telefonem).
