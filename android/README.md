# WING Monitor – aplikace pro Android

Nativní aplikace pro Android, kterou si muzikant sám řídí svůj odposlech (Aux mix) na pultu Behringer WING. Vybere si svůj Aux a hlavní nástroj a příště se aplikace otevře rovnou na ovládání hlasitosti tohoto nástroje: velká tlačítka − / + a ztlumení, žádná řada malých faderů.

Balíček: `cz.svatjir.wingmonitor`, Android 7.0 (API 24) a novější, cílové SDK 34.

## Spojení s pultem

Aplikace mluví s pultem protokolem **WING Native přes WebSocket na TCP 2222**, stejně jako oficiální aplikace WING-Q. Mění pouze Aux mix hráče:

- `ch.N.send.B.lvl` / `ch.N.send.B.on` – hlasitost a zapnutí kanálu N v Auxu B (to mění − / + a ztlumení),
- `bus.B.fdr` / `bus.B.mute` – celková hlasitost Auxu (položka **Master**).

Gain předzesilovačů ani hlavní mix nikdy nemění. Změny z pultu nebo z WING-Q se v aplikaci projeví okamžitě. V emulátoru se aplikace připojí automaticky na `10.0.2.2:2222` (tj. na PC se simulátorem pultu). V telefonu pult najde přes `WING?` na UDP 2222, nebo zadáte IP po klepnutí na štítek **MIX**. Bez spojení se změny ukládají jen v telefonu.

Popis protokolu: [WING Remote Protocols 3.1 (PDF)](https://cdn-media.empowertribe.com/2106004e217b8eb2639d93672823eef5/WING_Remote-Protocols-3.1-03.pdf). Uzly jsou 32bitové hashe cest jako `ch.1.send.1.lvl`, hlasitosti se zapisují jako float32 (`0xd5`), zapnutí/vypnutí jako int16 (`0xd3`).

## Skupiny a kanály

Aplikace má vlastní skupiny, z pultu bere jen názvy a barvy kanálů:

- **Bicí** – automaticky všechny pojmenované kanály v barvě bicích. Barva se odhadne z názvů (kick, snare, tom, OH…) nebo se jednou nastaví v *Barva bicích*.
- **Žestě** (Trumpeta, Pozoun, Tuba), **Zpěvy** (Zpěv 1–3), **Basa**, **Rytmika** (Elektrická kytara, Akustická kytara, Kytara 2, Piáno, Harmonika, Ukulele), **Housle**.
- **Master** – celková hlasitost vybraného Auxu.

Každý nástroj se na své stránce přiřadí ke kanálu pultu (*Kanál z pultu*); výběr řadí kanály podle barev. Skupiny s jedním nástrojem (Basa, Housle) otevřou rovnou stránku hlasitosti. Poslední známé názvy a barvy se pamatují i bez spojení.

Pořadí skupin (včetně Masteru), nástrojů ve skupině i kanálů bicích jde změnit prstem: dlouze podržet, přetáhnout a pustit.

## Pedál

| Klapka | Krátce | Podržet |
|---|---|---|
| levá | předchozí položka / o 1 dB tišeji | opakuje |
| prostřední | otevřít / vrátit se | přes 0,65 s: ztlumit (na stránce nástroje a Masteru), zpět (v seznamu) |
| pravá | další položka / o 1 dB hlasitěji | opakuje |

Skutečný pedál (ESP32) se připojuje přes Bluetooth LE: aplikace po klepnutí na štítek **BLE: VYP** spustí GATT server se službou `8d53dc1d-1db7-4cd3-868b-8a527460aa84` a zápisovou charakteristikou `…aa85`. Každý zápis má dva bajty `[tlačítko 1–3, stisknuto 1/0]`. Opakování a podržení vyhodnocuje aplikace.

V emulátoru posílá PC simulátor pedálu stejné hrany jako ADB broadcast:

```powershell
adb shell am broadcast -a cz.svatjir.wingmonitor.PEDAL -n cz.svatjir.wingmonitor/.PedalBroadcastReceiver --ei button 3 --ei pressed 1
```

Aplikace přijímá i šipky a Enter z klávesnice.

## Úrovně vstupů, citlivost a automatika

**Živé úrovně z pultu.** Po připojení si aplikace stejně jako WING-Q objedná měření vstupů: na nativním kanálu 3 pošle `d3 <UDP port> d4 <serial> dc ab 00 … ab 27 de` (vstupy 1–40) a asi každých 5 s obnovení `d3 <port> d4 <serial>`. Pult pak posílá UDP pakety na port **1241**: 4 bajty serial, pak 11 hodnot int16 (1/256 dB) na kanál; aplikace bere max(il, ir). Formát je převzatý z WING-Q, se skutečným pultem zatím ověřený není.

V **emulátoru** je potřeba UDP port po každém startu emulátoru přesměrovat: `adb -s emulator-5554 emu redir add udp:1241:1241` (dělá to `tools/pc-simulator/run.bat`). Telefon v síti pakety dostává přímo.

**Nastavení pro každý nástroj** (*Citlivost a automatika* na stránce nástroje; u bicích pro každý kanál):

- **Práh** – pod ním je kanál šum nebo ticho, nad ním hraje. Bez vlastní hodnoty platí výchozí práh z *Aktivní vstupy*.
- **Automaticky ztlumit šum** (výchozí vypnuto) – když je vstup 1,5 s pod prahem (třeba lidé mluví v sále), aplikace kanál **v Auxu hráče** vypne a jakmile úroveň práh znovu překročí, zase ho zapne. Otevření trvá jeden rámec měření plus zápis po síti (~50–150 ms), takže úplný začátek fráze se může ztratit.
- **Ochrana proti přebuzení** (výchozí vypnuto) – když vstup dosáhne −1 dBFS, sníží se odeslání kanálu do Auxu hráče o 3 dB, nejvýš jednou za 2 s a celkem nejvýš o 12 dB. Stránka nástroje snížení ukazuje s tlačítkem **VRÁTIT**. Chrání to jen uši hráče: zkreslení vzniká na vstupu, gain musí stáhnout zvukař.

**Bezpečnost:** ruční ztlumení/zapnutí nebo zapnutí odeslání z pultu či WING-Q má přednost před automatikou. Když úrovně 1,2 s nechodí, hráč změní Aux nebo aplikaci zavře, všechny automaticky ztlumené kanály se zase zapnou. Násilně ukončená aplikace to udělat nemůže; kanál pak zůstane ztlumený, dokud se aplikace znovu nepřipojí (po připojení svá ztlumení obnoví) nebo ho někdo nezapne.

**Aktivní vstupy** (ze seznamu skupiny) ukazují kanály, které jsou právě nad svým prahem, seřazené podle úrovně, a nastavují výchozí práh.

## Překlad

Potřeba je JDK 17 a Android SDK (cesta v `local.properties` nebo v proměnné `ANDROID_HOME`).

```powershell
cd android
.\gradlew.bat :app:assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Nebo složku `android` otevřete v Android Studiu jako Gradle projekt.

Testováno proti simulátoru pultu (`tools/wing-mock`) a s WING-Q v emulátoru. **Se skutečným pultem WING zatím ověřeno není.**
