# Potassium – CC0 vícestopá ukázka

Zdroj: album [Everything is Free](https://github.com/ichbinsoftware/everythingisfree), skladba **Potassium** od Software-Entwicklungskit (90 BPM).

Album, master i jednotlivé stopy jsou uvolněné pod **CC0 1.0 Universal**. Seznam stop s popisem je v [`src/4.Potassium/README.md`](https://github.com/ichbinsoftware/everythingisfree/tree/main/src/4.Potassium).

Simulátor vstupů přehrává komprimovaný master a k němu posílá úrovně šesti stop:

| Stopa | Kanál simulátoru pultu | Skupina v aplikaci |
|---|---|---|
| Kick | CH 1 | Bicí |
| Snare | CH 2 | Bicí |
| Hats | CH 3 | Bicí |
| Lead vocal | CH 16 | Zpěvy / Zpěv 1 |
| Pad + bass | CH 9 | Basa |
| Keys | CH 13 | Rytmika / Piáno |

Žestě, kytary ani housle v ukázce nejsou, takže tyto vstupy zůstávají potichu (nebo na úrovni šumu sálu). Úrovně jsou předpočítané RMS obálky (dBFS po 100 ms) v `meter_envelopes.json`. Po výměně stop je přegenerujete ve složce `tools/pc-simulator`:

```powershell
py -3.13 generate_stem_envelopes.py
```
