# Simulátor pultu WING (mock)

**Lokální simulátor** pultu WING pro testování aplikace WING Monitor i oficiální WING-Q bez skutečného pultu. Nic se nepřeposílá do žádného hardwaru.

## Co umí

- **Vyhledání** – na `WING?` (UDP 2222) odpoví `WING,<ip>,WING-MOCK,wing-bk,MOCK0001,3.1.0`.
- **WING Native na TCP 2222** – sám rozliší WebSocket (WING-Q, WING Monitor) a čisté TCP (libwing/wapi). Podporuje výpis celého stavu (`da dc`), čtení uzlu (`d7 <hash> dc`), zápis (`d7 <hash> <hodnota>`) a každou změnu rozešle ostatním připojeným klientům.
- **Strom uzlů** – jméno pultu, firmware, model, `ch.1–40` (jméno, barva, ikona, fader, mute, odeslání do 16 busů: lvl/on/pan/mode), `aux.1–8`, `bus.1–16` (jméno, fader, mute, pan, šířka) a značka konce výpisu `$ctl.lib.$actionidx`, na kterou čeká WING-Q. Hashe uzlů odpovídají WING-Q.
- **Ukázková kapela** – CH 1–8 bicí (Kick, Snare, HiHat, Tom 1, Tom 2, Floor, OH L, OH R), 9 Bass DI, 10–15 rytmika (E-Gtr, A-Gtr, Gtr 2, Piano, Harmonika, Ukulele), 16–18 zpěvy (Vox 1–3), 19–21 žestě (Trumpeta, Pozoun, Tuba), 22 Housle. Ostatní kanály jsou bez jména. Přejmenování kanálu změní i jeho aktivní jméno (`$name`).
- **Podmnožina OSC na UDP 2223** – `/ch/{1..48}/send/{1..16}/lvl|on`, `/bus/{1..16}/fdr|mute`, synchronně s nativními hodnotami.
- **Měření vstupů** – přijme objednávku měření od WING-Q na nativním kanálu 3 (`d3 <port> d4 <serial> dc ab <ch-1>… de`, obnovení bez `dc…de`, vyprší po 10 s) a každých 50 ms posílá UDP pakety na IP a port klienta: 4 bajty serial + 11 hodnot int16 (1/256 dB) na kanál. Úrovně dodává simulátor vstupů na `127.0.0.1:2229` (`{"meters":[{"ch":16,"dbfs":-20,"peak":-18}]}`); když 1 s nic nepřijde, všechny kanály ukazují −72 dBFS.

Neumí: definice uzlů (`dd`), jiná měření než vstupní kanály a zbytek stromu WING. WING-Q ukáže jen to, co je uvedeno výše.

## Spuštění

```powershell
py -3.13 mock_wing.py --bind 127.0.0.1 --log-file wing_events.jsonl
```

- Emulátor vidí PC na adrese `10.0.2.2`, takže pro emulátor stačí `127.0.0.1`. Pro skutečný telefon použijte `--bind 0.0.0.0 --advertise-ip <IP počítače v síti>` a ve Windows Firewallu povolte soukromou síť.
- `--log-file` zapisuje každý přijatý zápis a výpis jako JSONL (cesta, hodnota, kódování, surové bajty). Podle toho jsme porovnávali zápisy WING-Q a WING Monitor; používají to i e2e testy.

Testy:

```powershell
py -3.13 -m unittest -v
```
