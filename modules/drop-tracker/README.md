# Drop Tracker

A benchmark tool for measuring a spot or a party's income. A single voiced command records everything the
party gains over a run and reports per-hour rates, so you can compare hunting spots, setups, or drop-rate
changes with real numbers.

## How to use
- `.drops start` - begin a run.
- `.drops status` - see the totals so far without stopping.
- `.drops stop` - end the run and print the report.

## What it records
Across the whole party, for the length of a run:
- Kills per monster.
- XP and SP.
- Adena, seal stones, Ancient Adena.
- Every item gained.

It then reports totals and per-hour rates. Seal stones are priced from a configurable Ancient Adena value, and
the report prints low, mid, and high estimates so you can see the spread.

## Install
1. Copy the `drop-tracker` folder into `game/modules/`.
2. Enable it in the launcher and restart.

## Configuration (`config/module.ini`)
- `Enabled` - master switch. False and restart means the `.drops` command is not registered.
- `AncientAdenaPriceLow` / `AncientAdenaPriceMid` / `AncientAdenaPriceHigh` - adena value of one Ancient Adena,
  used to price seal stones. All three are printed so you can see the range.
- `TopItemLines` - how many gained items are listed in the in-game report. The server log always lists them all.
- `WriteCsv` - append one summary row per run to a CSV file.
- `CsvFile` - the CSV path, relative to the game server folder (default `log/drop-tracker.csv`).
- `WriteCsvOnLogout` - also write a run that ended because its owner logged out. Off by default, since such a run
  is usually abandoned (it is always written to the server log either way).

## Remove
Disable it, stop the server, delete the folder. The `.drops` command disappears with it. No ids or database tables.

## License
GNU General Public License v3.0.
