# Diagnosis rules

The numbers behind each row of SPEC "Diagnosis engine", as `:analysis` (`Diagnosis.kt`) applies them, and why.
They are starting values: tune them against real sessions, and change this file with the code and its test
(`DiagnosisTest`, one that fires and one that doesn't per rule).

## Shared ground

- **Dips** are `DIP` and `DROPOUT` events. Gaps, lost tuners and no-answer events are not dips.
- **Exposure** is readings × poll interval. Readings are evenly spaced, so this is time actually read; gaps and
  pauses don't count against a rate.
- **Dip rate** compares two groups as dips starting in each per hour of exposure. A rate rule fires at **3×**, or
  when the calmer group had no dips at all (the evidence then says "none in calm hours" instead of a ratio).
  Each group needs **an hour or more** of exposure, and the worse group **3 or more dips**. **Likely** takes
  5 or more.
- **Median snq gaps** (channel vs channels, VHF vs UHF) fire at **20 points** and are **Likely at 30**.
- **Ordering**: Likely first, then by score: how far past its line the evidence is (1 = at the line), capped at
  5 so a pile of evidence for one rule can't bury a clearer one. Ties keep the table's order, which puts causes
  (wind, rain, heat) before mechanisms (flutter).

## Rules

| Rule | Fires when | Likely when |
| --- | --- | --- |
| Overload | A channel's locked median ss ≥ 95 and median snq < 70 | Median snq is also poor (< the marginal threshold, 60) |
| Weak signal | A channel's median ss < 35 and median snq < 60 | Median ss is poor (< 25) |
| One channel bad | A channel's median snq is 20+ under the median of the other channels' medians, and the low channels are half or fewer (one of two counts). A channel that never locked counts as 0 | Gap 30+. "At every hour" is added when it was lower in every clock hour both were read |
| VHF weak | Median of VHF channels' medians 20+ under the UHF channels' (bands from the lineup) | Gap 30+ |
| Upper UHF | Dip rate on RF 30–36 is 3× the rate on lower channels | 5+ dips there and none lower |
| Time of day | Over 50% of dip time starts in one 4-hour window of local time, with dips in it on 2+ days, in a session of 28 h or more (so the window was seen twice) | 3+ days |
| Wind | Dip rate in hours with gusts > 30 km/h is 3× the rate in hours with gusts < 15 km/h | 5+ windy dips |
| Rain | Dip rate in hours with ≥ 0.1 mm precipitation is 3× the dry (0 mm) rate | 5+ wet dips |
| Heat | Dip rate in the hottest quarter of hours read is 3× the coolest quarter; needs 8+ hours with a temperature | 5+ hot dips |
| Common path | 2+ channels read at the same time on 2+ tuners (most 2-second stretches have more than one channel), and 80%+ of dips start within 2 s of a dip on every other channel; 3+ dips | 90%+ and 5+ dips |
| One tuner | A channel read on 2+ tuners, an hour+ on each, dips 3× as often on one as on the others together; 3+ dips there. A dip's tuner is that of the channel's reading nearest its start | 5+ dips there and none on the others |
| Impulse noise | 3+ `DIP` events whose lowest snq stayed at or above the good line (80): symbol errors alone | They're half or more of all dips |
| Flutter | Locked snq swings 20+ points within 10 s, 5+ times in all (each swing counted once, then 10 s skipped) | 20+ swings |
| Not RF | Any device-unreachable event | 2+ of them, or no dips at all |

## Weather

- Hours are clock hours in UTC; a dip belongs to the hour it starts in. Only hours with readings count.
- The weather finding names the **peak**: the run of consecutive condition hours holding the most of its dips,
  when that run holds at least half of all the session's dips ("19 of 23 dips fell on Nov 3 between noon and
  6 PM").
- The SPEC's Not RF half "seq at 100 during reported glitches" needs the user to report a glitch, which the app
  can't yet; only the device-unreachable half is built.
